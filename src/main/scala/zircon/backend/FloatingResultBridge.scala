package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Transfers an E2 floating result into the ordinary ROB completion network
  * and, when required, the retained FPR commit queue.
  *
  * A float-writing result first enters `FloatingResultQueue`, then this module
  * retains its matching non-GPR ROB completion until the ROB accepts it. This
  * guarantees that retirement cannot observe a completed FPR-writing entry
  * without an exact queued FPR result, while avoiding a completion-ready to
  * result-queue-ready combinational loop.
  */
class FloatingResultBridge(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FloatingMoveResult(config)))
    val completion = Decoupled(new CompletionResult(config))
    val floatingResult = Decoupled(new FloatingResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val pendingCompletion = RegInit(false.B)
  val pending = Reg(new CompletionResult(config))
  val recoveryBlocked = io.flush || io.squash.valid
  val inputFloatWrite = io.input.bits.writesFloat
  // Integer-result F operations normally bypass FloatingResultQueue, but an
  // IEEE exception flag is architectural floating state and therefore needs
  // the same ROB-tagged commit ownership as an FPR write.
  val requiresFloatingCommit = inputFloatWrite || io.input.bits.flags.orR

  io.completion.valid := !recoveryBlocked && (pendingCompletion ||
    (io.input.valid && !requiresFloatingCommit && !pendingCompletion))
  io.completion.bits.robTag := Mux(pendingCompletion, pending.robTag,
    io.input.bits.robTag)
  io.completion.bits.writesInteger := Mux(pendingCompletion,
    pending.writesInteger, io.input.bits.writesInteger)
  io.completion.bits.destinationPhysical := Mux(pendingCompletion,
    pending.destinationPhysical, io.input.bits.integerDestinationPhysical)
  io.completion.bits.data := Mux(pendingCompletion, pending.data,
    io.input.bits.integerData)

  io.floatingResult.valid := io.input.valid && requiresFloatingCommit &&
    !pendingCompletion && !recoveryBlocked
  io.floatingResult.bits.robTag := io.input.bits.robTag
  io.floatingResult.bits.writesFloat := io.input.bits.writesFloat
  io.floatingResult.bits.fprAddress := io.input.bits.floatDestination
  io.floatingResult.bits.fprData := io.input.bits.floatData
  io.floatingResult.bits.flags := io.input.bits.flags

  io.input.ready := !recoveryBlocked && !pendingCompletion && Mux(requiresFloatingCommit,
    io.floatingResult.ready, io.completion.ready)

  val pendingYounger = pendingCompletion && ROBTagOrder.isYounger(
    pending.robTag, io.squash.bits, io.robHeadTag, config)
  when(io.flush) {
    pendingCompletion := false.B
  }.elsewhen(io.squash.valid) {
    when(pendingYounger) { pendingCompletion := false.B }
  }.otherwise {
    when(pendingCompletion && io.completion.fire) {
      pendingCompletion := false.B
    }
    when(io.input.fire && requiresFloatingCommit) {
      pendingCompletion := true.B
      pending.robTag := io.input.bits.robTag
      pending.writesInteger := io.input.bits.writesInteger
      pending.destinationPhysical := io.input.bits.integerDestinationPhysical
      pending.data := io.input.bits.integerData
    }
  }

  when(io.input.valid) {
    assert(io.input.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "floating result bridge received an out-of-range ROB tag")
    assert(io.input.bits.writesInteger || io.input.bits.writesFloat,
      "floating result bridge received a completion without a destination")
    assert(!(io.input.bits.writesInteger && io.input.bits.writesFloat),
      "floating result bridge cannot split a dual-namespace result")
  }
  when(io.input.fire && requiresFloatingCommit) {
    assert(!io.completion.fire,
      "floating-state result completed the ROB before entering its retained queue")
  }
  when(io.floatingResult.fire) {
    assert(io.input.fire && !pendingCompletion,
      "floating result queue accepted a tag without capturing its completion")
  }
  when(io.squash.valid) {
    assert(!io.input.fire && !io.floatingResult.fire && !io.completion.fire,
      "floating result bridge transferred work during selective squash")
  }
}
