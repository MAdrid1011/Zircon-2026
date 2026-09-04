package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionBuffer, CompletionResult, FirstFaultRecord, ROBTagOrder}

/** Formats integer load data and retains it in one frozen two-entry LSU buffer.
  * The input is accepted only when the buffer has space, so its upstream LSQ
  * can keep cache-response ownership under completion backpressure.
  */
class MemoryLoadCompletion(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val loadResult = Flipped(Decoupled(new MemoryLoadResult(config)))
    /** A non-load M0 completion, used by a successful committed store. */
    val effectCompletion = Flipped(Decoupled(new CompletionResult(config)))
    val fault = Flipped(Decoupled(new FirstFaultRecord(config)))
    val completion = Decoupled(new CompletionResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(2.W))
  })

  val buffer = Module(new CompletionBuffer(config, depth = 2))
  // Cache and forwarding data are word aligned.  Narrow loads retain the
  // effective address so the architectural byte/halfword lane is selected
  // before sign or zero extension.
  val byte = MuxLookup(io.loadResult.bits.address(1, 0),
    io.loadResult.bits.data(7, 0))(Seq(
      1.U -> io.loadResult.bits.data(15, 8),
      2.U -> io.loadResult.bits.data(23, 16),
      3.U -> io.loadResult.bits.data(31, 24)))
  val half = Mux(io.loadResult.bits.address(1),
    io.loadResult.bits.data(31, 16), io.loadResult.bits.data(15, 0))
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

  val loadAge = ROBTagOrder.ageFromHead(
    io.loadResult.bits.robTag, io.robHeadTag, config)
  val effectAge = ROBTagOrder.ageFromHead(
    io.effectCompletion.bits.robTag, io.robHeadTag, config)
  val faultAge = ROBTagOrder.ageFromHead(io.fault.bits.robTag, io.robHeadTag, config)
  val selectLoad = io.loadResult.valid &&
    (!io.fault.valid || loadAge < faultAge) &&
    (!io.effectCompletion.valid || loadAge < effectAge)
  val selectFault = io.fault.valid && !selectLoad &&
    (!io.effectCompletion.valid || faultAge < effectAge)
  val selectEffect = io.effectCompletion.valid && !selectLoad && !selectFault
  buffer.io.enqueue.valid := selectLoad || selectFault || selectEffect
  buffer.io.enqueue.bits.robTag := Mux(selectLoad,
    io.loadResult.bits.robTag, Mux(selectFault, io.fault.bits.robTag,
      io.effectCompletion.bits.robTag))
  buffer.io.enqueue.bits.writesInteger := Mux(selectLoad,
    io.loadResult.bits.writesInteger, Mux(selectEffect,
      io.effectCompletion.bits.writesInteger, false.B))
  buffer.io.enqueue.bits.destinationPhysical := Mux(selectLoad,
    io.loadResult.bits.destinationPhysical, Mux(selectEffect,
      io.effectCompletion.bits.destinationPhysical, 0.U))
  buffer.io.enqueue.bits.data := Mux(selectLoad, formattedData,
    Mux(selectEffect, io.effectCompletion.bits.data, 0.U))
  io.loadResult.ready := selectLoad && buffer.io.enqueue.ready
  io.fault.ready := selectFault && buffer.io.enqueue.ready
  io.effectCompletion.ready := selectEffect && buffer.io.enqueue.ready
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
  when(io.fault.valid) {
    assert(io.fault.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "memory fault completion received an out-of-range ROB tag")
  }
  when(io.effectCompletion.valid) {
    assert(io.effectCompletion.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "memory effect completion received an out-of-range ROB tag")
    assert(!io.effectCompletion.bits.writesInteger,
      "the M0 store-effect completion must not fabricate a register write")
  }
  when(io.loadResult.valid && io.fault.valid) {
    assert(io.loadResult.bits.robTag =/= io.fault.bits.robTag,
      "a memory result and fault cannot complete the same ROB tag")
  }
  when(io.loadResult.valid && io.effectCompletion.valid) {
    assert(io.loadResult.bits.robTag =/= io.effectCompletion.bits.robTag,
      "a memory load and effect completion cannot name the same ROB tag")
  }
  when(io.fault.valid && io.effectCompletion.valid) {
    assert(io.fault.bits.robTag =/= io.effectCompletion.bits.robTag,
      "a memory fault and effect completion cannot name the same ROB tag")
  }
  io.count := buffer.io.count
}
