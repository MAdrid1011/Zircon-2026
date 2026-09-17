package ZirconConfig
import chisel3._
import chisel3.util._

object EXEOp {
    // alu
    val ADD = 0x0.U(5.W)
    val SLL = 0x1.U(5.W)
    val SLT = 0x2.U(5.W)
    val SLTU = 0x3.U(5.W)
    val XOR = 0x4.U(5.W)
    val SRL = 0x5.U(5.W)
    val OR = 0x6.U(5.W)
    val AND = 0x7.U(5.W)
    val SUB = 0x8.U(5.W)
    val SRA = 0xd.U(5.W)

    // branch
    val BEQ = 0x18.U(5.W)
    val BNE = 0x19.U(5.W)
    val JALR = 0x1a.U(5.W)
    val JAL = 0x1b.U(5.W)
    val BLT = 0x1c.U(5.W)
    val BGE = 0x1d.U(5.W)
    val BLTU = 0x1e.U(5.W)
    val BGEU = 0x1f.U(5.W)

    // mul and div
    val MUL = 0x0.U(4.W)
    val MULH = 0x1.U(4.W)
    val MULHSU = 0x2.U(4.W)
    val MULHU = 0x3.U(4.W)
    val DIV = 0x4.U(4.W)
    val DIVU = 0x5.U(4.W)
    val REM = 0x6.U(4.W)
    val REMU = 0x7.U(4.W)
}
