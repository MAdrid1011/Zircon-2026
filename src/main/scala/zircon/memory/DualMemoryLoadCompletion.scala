package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionResult, FaultCandidate, FirstFaultRecord}

/** Sends each retained load result to its frozen M0 or M1 completion buffer. */
class DualMemoryLoadCompletion(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val loadResult = Flipped(Decoupled(new MemoryLoadResult(config)))
    val fault = Flipped(Vec(2, Decoupled(new FirstFaultRecord(config))))
    val faultAccepted = Output(Vec(2, new FaultCandidate(config)))
    val m0Completion = Decoupled(new CompletionResult(config))
    val m1Completion = Decoupled(new CompletionResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val m0Count = Output(UInt(2.W))
    val m1Count = Output(UInt(2.W))
  })

  val m0Buffer = Module(new MemoryLoadCompletion(config))
  val m1Buffer = Module(new MemoryLoadCompletion(config))
  for (buffer <- Seq(m0Buffer, m1Buffer)) {
    buffer.io.robHeadTag := io.robHeadTag
    buffer.io.squash := io.squash
    buffer.io.flush := io.flush
  }

  m0Buffer.io.loadResult.valid := io.loadResult.valid &&
    !io.loadResult.bits.m1Owner
  m0Buffer.io.loadResult.bits := io.loadResult.bits
  m1Buffer.io.loadResult.valid := io.loadResult.valid &&
    io.loadResult.bits.m1Owner
  m1Buffer.io.loadResult.bits := io.loadResult.bits
  io.loadResult.ready := Mux(io.loadResult.valid && io.loadResult.bits.m1Owner,
    m1Buffer.io.loadResult.ready, m0Buffer.io.loadResult.ready)

  m0Buffer.io.fault <> io.fault(0)
  m1Buffer.io.fault <> io.fault(1)
  for (lane <- 0 until 2) {
    io.faultAccepted(lane).valid := io.fault(lane).fire
    io.faultAccepted(lane).record := io.fault(lane).bits
  }

  io.m0Completion <> m0Buffer.io.completion
  io.m1Completion <> m1Buffer.io.completion
  io.m0Count := m0Buffer.io.count
  io.m1Count := m1Buffer.io.count

  when(io.squash.valid || io.flush) {
    assert(!io.loadResult.ready,
      "dual load completion accepted a response during recovery")
  }
}
