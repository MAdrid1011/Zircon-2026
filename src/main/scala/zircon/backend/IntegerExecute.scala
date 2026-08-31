package zircon.backend

import chisel3._
import chisel3.util._
import zircon.frontend.IntOperation

class IntegerExecuteRequest extends Bundle {
  val operation = IntOperation()
  val lhs = UInt(32.W)
  val rhs = UInt(32.W)
  val pc = UInt(32.W)
  val immediate = UInt(32.W)
}

class IntegerExecuteResponse extends Bundle {
  val result = UInt(32.W)
  val controlValid = Bool()
  val controlTaken = Bool()
  val controlTarget = UInt(32.W)
  val nextPc = UInt(32.W)
  val instructionAddressMisaligned = Bool()
}

/** Stateless RV32I integer semantics shared by E0 and E1.
  * Admission, buffering, redirect and precise exceptions remain outside this block.
  */
class IntegerExecute extends Module {
  val io = IO(new Bundle {
    val request = Input(new IntegerExecuteRequest)
    val response = Output(new IntegerExecuteResponse)
  })

  val result = WireDefault(0.U(32.W))
  val controlValid = WireDefault(false.B)
  val controlTaken = WireDefault(false.B)
  val controlTarget = WireDefault(0.U(32.W))
  val sequentialPc = io.request.pc + 4.U

  switch(io.request.operation) {
    is(IntOperation.Lui) { result := io.request.immediate }
    is(IntOperation.Auipc) { result := io.request.pc + io.request.immediate }
    is(IntOperation.Add) { result := io.request.lhs + io.request.rhs }
    is(IntOperation.Sub) { result := io.request.lhs - io.request.rhs }
    is(IntOperation.Sll) { result := (io.request.lhs << io.request.rhs(4, 0))(31, 0) }
    is(IntOperation.Slt) {
      result := Mux(io.request.lhs.asSInt < io.request.rhs.asSInt, 1.U, 0.U)
    }
    is(IntOperation.Sltu) { result := Mux(io.request.lhs < io.request.rhs, 1.U, 0.U) }
    is(IntOperation.Xor) { result := io.request.lhs ^ io.request.rhs }
    is(IntOperation.Srl) { result := io.request.lhs >> io.request.rhs(4, 0) }
    is(IntOperation.Sra) {
      result := (io.request.lhs.asSInt >> io.request.rhs(4, 0)).asUInt
    }
    is(IntOperation.Or) { result := io.request.lhs | io.request.rhs }
    is(IntOperation.And) { result := io.request.lhs & io.request.rhs }

    is(IntOperation.Lb, IntOperation.Lh, IntOperation.Lw,
      IntOperation.Lbu, IntOperation.Lhu, IntOperation.Sb,
      IntOperation.Sh, IntOperation.Sw) {
      result := io.request.lhs + io.request.immediate
    }

    is(IntOperation.Beq) {
      controlValid := true.B
      controlTaken := io.request.lhs === io.request.rhs
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Bne) {
      controlValid := true.B
      controlTaken := io.request.lhs =/= io.request.rhs
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Blt) {
      controlValid := true.B
      controlTaken := io.request.lhs.asSInt < io.request.rhs.asSInt
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Bge) {
      controlValid := true.B
      controlTaken := io.request.lhs.asSInt >= io.request.rhs.asSInt
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Bltu) {
      controlValid := true.B
      controlTaken := io.request.lhs < io.request.rhs
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Bgeu) {
      controlValid := true.B
      controlTaken := io.request.lhs >= io.request.rhs
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Jal) {
      result := sequentialPc
      controlValid := true.B
      controlTaken := true.B
      controlTarget := io.request.pc + io.request.immediate
    }
    is(IntOperation.Jalr) {
      result := sequentialPc
      controlValid := true.B
      controlTaken := true.B
      controlTarget := (io.request.lhs + io.request.immediate) & "hfffffffe".U
    }
  }

  io.response.result := result
  io.response.controlValid := controlValid
  io.response.controlTaken := controlTaken
  io.response.controlTarget := controlTarget
  io.response.nextPc := Mux(controlValid && controlTaken, controlTarget, sequentialPc)
  io.response.instructionAddressMisaligned :=
    controlValid && controlTaken && controlTarget(1, 0).orR
}
