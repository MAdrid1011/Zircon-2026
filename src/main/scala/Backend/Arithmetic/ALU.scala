import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._
import Shifter._

class ALUIO extends Bundle {
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val op = Input(UInt(5.W))
    val res = Output(UInt(32.W))
}

class ALU extends Module {
    val io = IO(new ALUIO)

    val isSlt = io.op === SLT
    val isSltu = io.op === SLTU
    val isAnd = io.op === AND
    val isOr = io.op === OR
    val isXor = io.op === XOR
    val isSll = io.op === SLL
    val isSra = io.op === SRA

    // SUB, SLT and SLTU share the same subtraction controls.
    val subtract = (io.op === SUB) || isSlt || isSltu
    val adderSrc2 = Mux(io.op === ADD, io.src2, Mux(subtract, ~io.src2, 4.U(32.W)))
    val adder = BLevelPAdder32(io.src1, adderSrc2, subtract.asUInt)

    val shifter = Shifter(Mux(isSll, Reverse(io.src1), io.src1), io.src2(4, 0), isSra)

    val lessSigned = io.src1(31) && !io.src2(31) ||
        !(io.src1(31) ^ io.src2(31)) && adder.io.res(31)
    val rightShift = io.op === SRL || isSra
    // Exactly one selector is active, including all default src1 + 4 operations.
    val useAdder = !(isSlt || isSltu || isAnd || isOr || isXor || isSll || rightShift)
    io.res := Mux1H(Seq(
        useAdder -> adder.io.res,
        isSltu -> Cat(0.U(31.W), !adder.io.cout.get),
        isSlt -> Cat(0.U(31.W), lessSigned),
        isAnd -> (io.src1 & io.src2),
        isOr -> (io.src1 | io.src2),
        isXor -> (io.src1 ^ io.src2),
        isSll -> Reverse(shifter.io.res),
        rightShift -> shifter.io.res,
    ))
}
