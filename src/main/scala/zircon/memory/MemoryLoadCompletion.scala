package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionBuffer, CompletionResult}

/** Formats integer load data and retains it in one frozen two-entry LSU buffer.
  * The input is accepted only when the buffer has space, so its upstream LSQ
  * can keep cache-response ownership under completion backpressure.
  */
class MemoryLoadCompletion(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val loadResult = Flipped(Decoupled(new MemoryLoadResult(config)))
    val completion = Decoupled(new CompletionResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(2.W))
  })

  val buffer = Module(new CompletionBuffer(config, depth = 2))
  val byte = io.loadResult.bits.data(7, 0)
  val half = io.loadResult.bits.data(15, 0)
  val byteExtended = Mux(io.loadResult.bits.unsignedLoad,
    Cat(0.U(24.W), byte), Cat(Fill(24, byte(7)), byte))
  val halfExtended = Mux(io.loadResult.bits.unsignedLoad,
    Cat(0.U(16.W), half), Cat(Fill(16, half(15)), half))
  val formattedData = MuxLookup(io.loadResult.bits.accessSize,
    io.loadResult.bits.data)(Seq(
      0.U -> byteExtended,
      1.U -> halfExtended,
      2.U -> io.loadResult.bits.data
    ))

  buffer.io.enqueue.valid := io.loadResult.valid
  buffer.io.enqueue.bits.robTag := io.loadResult.bits.robTag
  buffer.io.enqueue.bits.writesInteger := io.loadResult.bits.writesInteger
  buffer.io.enqueue.bits.destinationPhysical := io.loadResult.bits.destinationPhysical
  buffer.io.enqueue.bits.data := formattedData
  io.loadResult.ready := buffer.io.enqueue.ready
  io.completion <> buffer.io.dequeue
  buffer.io.robHeadTag := io.robHeadTag
  buffer.io.squash := io.squash
  buffer.io.flush := io.flush

  when(io.loadResult.valid) {
    assert(io.loadResult.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "memory load completion received an out-of-range ROB tag")
    when(io.loadResult.bits.writesInteger) {
      assert(io.loadResult.bits.destinationPhysical =/= 0.U,
        "integer load completion attempted to write p0")
    }
  }
  io.count := buffer.io.count
}
