import chisel3._
import chisel3.util._
import ZirconConfig._

class DecoderIO(p: FrontendParams) extends Bundle {
    val in = Input(new FrontendInstruction(p))
    val out = Output(new FrontendInstruction(p))
}

/** Single-instruction combinational decode, ported from Zircon-2024's Decoder. */
class Decoder(
    p: FrontendParams = FrontendParams(),
    support: DecodeSupport = DecodeSupport.current,
) extends RawModule {
    val io = IO(new DecoderIO(p))
    val inst = io.in.inst
    val table = DecodeTable.entries
    table.filter(_.supportedBy(support)).combinations(2).foreach { pair =>
        val left = pair.head.pattern
        val right = pair.last.pattern
        require(
            ((left.value ^ right.value) & left.mask & right.mask) != 0,
            s"Decode patterns ${pair.head.name} and ${pair.last.name} overlap",
        )
    }
    // Missing consumers are removed during elaboration, not checked through extra comparators.
    val hits = VecInit(table.map(row => if (row.supportedBy(support)) row.pattern === inst else false.B))
    val instPkgOut = WireDefault(io.in)

    /* Parallel Match and Balanced Control Selection */
    def any(select: DecodeEntry => Boolean): Bool = {
        val terms = table.indices.filter(i => select(table(i))).map(hits(_))
        if (terms.isEmpty) false.B else VecInit(terms).reduceTree(_ || _)
    }
    def control(width: Int)(select: DecodeEntry => Int): UInt = {
        val terms = table.indices.filter(i => select(table(i)) != 0).map { i =>
            Fill(width, hits(i)) & select(table(i)).U(width.W)
        }
        if (terms.isEmpty) 0.U(width.W) else VecInit(terms).reduceTree(_ | _)
    }
    val usesRounding = any(_.rounding)
    val validRounding = !usesRounding || inst(14, 12) <= 4.U || inst(14, 12) === 7.U
    val legal = hits.reduceTree(_ || _) && validRounding
    val priorException = io.in.fault || io.in.exception.valid
    val execute = legal && !priorException

    /* Immediate Rearrangement: Preserve the 2024 I/S/B/U/J Layouts */
    def signExtend(value: UInt): UInt = Cat(Fill(32 - value.getWidth, value(value.getWidth - 1)), value)
    val immediates = Seq(
        DecodeImmediate.I -> signExtend(inst(31, 20)),
        DecodeImmediate.S -> signExtend(Cat(inst(31, 25), inst(11, 7))),
        DecodeImmediate.B -> signExtend(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))),
        DecodeImmediate.U -> Cat(inst(31, 12), 0.U(12.W)),
        DecodeImmediate.J -> signExtend(Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))),
        DecodeImmediate.Shift -> Cat(0.U(27.W), inst(24, 20)),
        DecodeImmediate.CSR -> Cat(0.U(15.W), inst(19, 15), inst(31, 20)),
        DecodeImmediate.Fence -> Cat(0.U(20.W), inst(31, 20)),
    )
    val immediate = VecInit(immediates.map { case (kind, value) =>
        Fill(32, any(_.immType == kind)) & value
    }).reduceTree(_ | _)

    /* Fill Only Decode-Owned Fields; PD Register and Prediction Information Pass Through */
    instPkgOut.fu := Mux(execute, control(4)(_.fu), DecodeUnit.None.U)
    instPkgOut.op := Mux(execute, control(5)(_.op), 0.U)
    instPkgOut.src1Sel := Mux(execute, control(2)(_.src1Sel), DecodeSource.Register.U)
    instPkgOut.src2Imm := execute && any(_.src2Imm)
    instPkgOut.imm := Mux(execute, immediate, 0.U)
    instPkgOut.rm := Mux(execute && usesRounding, inst(14, 12), 0.U)
    instPkgOut.aq := execute && any(_.aqrl) && inst(26)
    instPkgOut.rl := execute && any(_.aqrl) && inst(25)

    // Preserve an older fault even when the returned instruction bits are invalid.
    when(!priorException && !legal) {
        instPkgOut.exception.valid := true.B
        instPkgOut.exception.cause := DecodeException.IllegalInstruction.U
        instPkgOut.exception.tval := inst
    }
    io.out := instPkgOut
}
