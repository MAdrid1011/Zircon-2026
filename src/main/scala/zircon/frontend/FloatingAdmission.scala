package zircon.frontend

import chisel3._

/** Classifies the first executable RV32F subset without creating a pipeline
  * side effect. Backend dispatch consumes this gate only when the complete F
  * scoreboard/E2/commit path is connected.
  */
class FloatingAdmission extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val mstatusFs = Input(UInt(2.W))
    val decoded = Output(new FloatingDecodedInstruction)
    val floatingOpcode = Output(Bool())
    val live = Output(Bool())
    val illegal = Output(Bool())
  })

  val decoder = Module(new RV32FMetadataDecoder)
  decoder.io.instruction := io.instruction
  io.decoded := decoder.io.decoded

  val opcode = io.instruction(6, 0)
  val floatingOpcode = opcode === "b0000111".U || opcode === "b0100111".U ||
    opcode === "b1000011".U || opcode === "b1000111".U ||
    opcode === "b1001011".U || opcode === "b1001111".U ||
    opcode === "b1010011".U
  val nonRoundingE2 = decoder.io.decoded.operation === FloatingOperation.FmvWX ||
    decoder.io.decoded.operation === FloatingOperation.FmvXW ||
    decoder.io.decoded.operation === FloatingOperation.FsgnjS ||
    decoder.io.decoded.operation === FloatingOperation.FsgnjnS ||
    decoder.io.decoded.operation === FloatingOperation.FsgnjxS ||
    decoder.io.decoded.operation === FloatingOperation.FminS ||
    decoder.io.decoded.operation === FloatingOperation.FmaxS ||
    decoder.io.decoded.operation === FloatingOperation.FleS ||
    decoder.io.decoded.operation === FloatingOperation.FltS ||
    decoder.io.decoded.operation === FloatingOperation.FeqS ||
    decoder.io.decoded.operation === FloatingOperation.FclassS

  io.floatingOpcode := floatingOpcode
  io.live := floatingOpcode && decoder.io.decoded.legal && nonRoundingE2 &&
    io.mstatusFs =/= 0.U
  io.illegal := floatingOpcode && !io.live
}
