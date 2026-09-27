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

    /** Carry-prefix addition keeps a forwarded JALR source off a byte-increment chain. */
    def targetSum(a: UInt, b: UInt): UInt = {
        BLevelPAdder32(a, b, 0.U).io.res
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
    val directTarget = BranchLogic.targetSum(io.pc, io.imm)
    val indirectTarget = BranchLogic.targetSum(io.src1, io.imm)
    val rawTarget = Mux(isJalr, indirectTarget, directTarget)
    val target = Cat(rawTarget(31, 1), rawTarget(0) && !isJalr)
    // JALR discards target bit 0. Both possible unmasked sums are prepared
    // from the early prediction and immediate before a forwarded src1 arrives.
    val expectedEvenSource = io.predOffset - io.imm
    val expectedOddSource = (io.predOffset | 1.U(32.W)) - io.imm
    val indirectMatch = !io.predOffset(0) &&
        (io.src1 === expectedEvenSource || io.src1 === expectedOddSource)
    BranchLogic.resolve(
        io,
        io.src1 === io.src2,
        io.src1 < io.src2,
        target,
        indirectMatch
    )
}
