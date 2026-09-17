package ZirconConfig

import chisel3._

/** Execution-unit controls, not ISA funct fields or EXEOp's DIV/REM encodings. */
object MultiplyOp {
    val MUL = 0.U(4.W)
    val MULH = 1.U(4.W)
    val MULHSU = 2.U(4.W)
    val MULHU = 3.U(4.W)
    val FADD = 4.U(4.W)
    val FSUB = 5.U(4.W)
    val FMUL = 6.U(4.W)
    val FMADD = 7.U(4.W)
    val FMSUB = 8.U(4.W)
    val FNMSUB = 9.U(4.W)
    val FNMADD = 10.U(4.W)
}
