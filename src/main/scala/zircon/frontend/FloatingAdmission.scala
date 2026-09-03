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
    val currentFrm = Input(UInt(3.W))
    val decoded = Output(new FloatingDecodedInstruction)
    val floatingOpcode = Output(Bool())
    val effectiveRoundingMode = Output(UInt(3.W))
    val roundingLegal = Output(Bool())
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
  val executableE2 = decoder.io.decoded.operation === FloatingOperation.FmvWX ||
    decoder.io.decoded.operation === FloatingOperation.FmvXW ||
    decoder.io.decoded.operation === FloatingOperation.FaddS ||
    decoder.io.decoded.operation === FloatingOperation.FsubS ||
    decoder.io.decoded.operation === FloatingOperation.FmulS ||
    decoder.io.decoded.operation === FloatingOperation.FdivS ||
    decoder.io.decoded.operation === FloatingOperation.FsgnjS ||
    decoder.io.decoded.operation === FloatingOperation.FsgnjnS ||
    decoder.io.decoded.operation === FloatingOperation.FsgnjxS ||
    decoder.io.decoded.operation === FloatingOperation.FminS ||
    decoder.io.decoded.operation === FloatingOperation.FmaxS ||
    decoder.io.decoded.operation === FloatingOperation.FleS ||
    decoder.io.decoded.operation === FloatingOperation.FltS ||
    decoder.io.decoded.operation === FloatingOperation.FeqS ||
    decoder.io.decoded.operation === FloatingOperation.FclassS ||
    decoder.io.decoded.operation === FloatingOperation.FcvtSW ||
    decoder.io.decoded.operation === FloatingOperation.FcvtSWu ||
    decoder.io.decoded.operation === FloatingOperation.FcvtWS ||
    decoder.io.decoded.operation === FloatingOperation.FcvtWuS
  val effectiveRoundingMode = Mux(decoder.io.decoded.dynamicRounding,
    io.currentFrm, decoder.io.decoded.roundingMode)
  val roundingLegal = !decoder.io.decoded.usesRoundingMode ||
    effectiveRoundingMode <= 4.U

  io.floatingOpcode := floatingOpcode
  io.effectiveRoundingMode := effectiveRoundingMode
  io.roundingLegal := roundingLegal
  io.live := floatingOpcode && decoder.io.decoded.legal && executableE2 &&
    roundingLegal && io.mstatusFs =/= 0.U
  io.illegal := floatingOpcode && !io.live
}
