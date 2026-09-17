import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._

class BranchIO extends Bundle {
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val op = Input(UInt(5.W))
    val pc = Input(UInt(32.W))
    val imm = Input(UInt(32.W))
    // Relative displacement for direct branches; absolute target for JALR.
    val predOffset = Input(UInt(32.W))
    val realJp = Output(Bool())
    val predFail = Output(Bool())
    val jumpTgt = Output(UInt(32.W))
    // IALIGN=32. The enclosing pipeline qualifies this flag with instruction validity.
    val targetMisaligned = Output(Bool())
}

object BranchLogic {

    /** Four parallel byte sums, the original four-block carry network, then local increments. */
    def targetSum(a: UInt, b: UInt): UInt = {
        val left = (0 until 4).map(i => a(i * 8 + 7, i * 8))
        val right = (0 until 4).map(i => b(i * 8 + 7, i * 8))
        val sums = left.zip(right).map { case (x, y) => x +& y }
        val propagate = VecInit(left.zip(right).map { case (x, y) => (x ^ y).andR }).asUInt
        val generate = VecInit(sums.map(_(8))).asUInt
        val (_, _, carries) = BLevelCarry4(propagate, generate, 0.U(1.W))
        VecInit((0 until 4).map { i =>
            val low = sums(i)(7, 0)
            if (i == 0) low else low + carries(i - 1)
        }).asUInt
    }

    def resolve(io: BranchIO, equal: Bool, unsignedLess: Bool, target: UInt, indirectMatch: Bool): Unit = {
        val isJalr = io.op === JALR
        val isJump = io.op(4, 1) === (0x1a >> 1).U
        val isControl = io.op(4, 3).andR
        // Signed and unsigned order differ exactly when the operand signs differ.
        val less = unsignedLess ^ (!io.op(1) && (io.src1(31) ^ io.src2(31)))
        val condition = Mux(io.op(2), less, equal) ^ io.op(0)
        val taken = isControl && (isJump || condition)
        // Compare both displacements in parallel with the operand comparison.
        val directFail = Mux(taken, io.predOffset =/= io.imm, io.predOffset =/= 4.U)
        io.realJp := taken
        io.jumpTgt := target
        io.predFail := Mux(isJalr, !indirectMatch, io.op(4) && directFail)
        io.targetMisaligned := taken && target(1, 0).orR
    }
}

/** Combinational RV32 branch execution; registers, validity and recovery are external. */
class Branch extends Module {
    val io = IO(new BranchIO)
    val isJalr = io.op === JALR
    val rawTarget = BranchLogic.targetSum(Mux(isJalr, io.src1, io.pc), io.imm)
    val target = Cat(rawTarget(31, 1), rawTarget(0) && !isJalr)
    val comparison = BLevelPAdder32(io.src1, ~io.src2, 1.U)
    BranchLogic.resolve(
        io,
        io.src1 === io.src2,
        !comparison.io.cout.asBool,
        target,
        target === io.predOffset
    )
}
