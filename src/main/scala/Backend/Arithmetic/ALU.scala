import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._
import Shifter._

class ALUIO(pipelined: Boolean = false) extends Bundle {
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val op = Input(UInt(5.W))
    val res = Output(UInt(32.W))
    val advance = if (pipelined) Some(Input(Bool())) else None
}

class ALU(pipelined: Boolean = false) extends Module {
    val io = IO(new ALUIO(pipelined))
    def capture[T <: Data](value: T): T =
        if (pipelined) RegEnable(value, io.advance.get) else value

    val isSlt = capture(io.op === SLT)
    val isSltu = capture(io.op === SLTU)
    val isAnd = capture(io.op === AND)
    val isOr = capture(io.op === OR)
    val isXor = capture(io.op === XOR)
    val isSll = capture(io.op === SLL)
    val isSra = capture(io.op === SRA)

    // SUB, SLT and SLTU share the same subtraction controls.
    val subtract = (io.op === SUB) || io.op === SLT || io.op === SLTU
    val src1 = capture(io.src1)
    val src2 = capture(io.src2)
    val adderSrc2 = capture(io.src2 ^ Fill(32, subtract))
    val adder = BLevelPAdder32(src1, adderSrc2, capture(subtract.asUInt))
    // Link-PC increments run beside operand arithmetic. Their default select
    // must not add an opcode mux before the shared ADD/SUB carry network.
    val pcStep = BLevelPAdder32(src1, 4.U(32.W), 0.U(1.W)).io.res

    val shifter = Shifter(capture(Mux(io.op === SLL, Reverse(io.src1), io.src1)), src2(4, 0), isSra)

    val lessSigned = src1(31) && !src2(31) ||
        !(src1(31) ^ src2(31)) && adder.io.res(31)
    val rightShift = capture(io.op === SRL || io.op === SRA)
    val useAdder = capture(io.op === ADD || io.op === SUB)
    val usePcStep = !(useAdder || isSlt || isSltu || isAnd || isOr || isXor || isSll || rightShift)
    io.res := Mux1H(Seq(
        useAdder -> adder.io.res,
        usePcStep -> pcStep,
        isSltu -> Cat(0.U(31.W), !adder.io.cout.get),
        isSlt -> Cat(0.U(31.W), lessSigned),
        isAnd -> (src1 & src2),
        isOr -> (src1 | src2),
        isXor -> (src1 ^ src2),
        isSll -> Reverse(shifter.io.res),
        rightShift -> shifter.io.res,
    ))
}
