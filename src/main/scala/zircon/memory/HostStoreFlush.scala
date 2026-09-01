package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Holds one trace-selected committed store until its dirty line has reached a
  * successful external ID-5 writeback. It is only instantiated in trace
  * elaborations; normal cacheable stores bypass this module completely.
  */
class HostStoreFlush(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private object State extends ChiselEnum {
    val Idle, TransferL1D, EvictL2, WaitWriteback, Release = Value
  }

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new StoreWriteResult(config)))
    val output = Decoupled(new StoreWriteResult(config))
    val enabled = Input(Bool())
    val address = Input(UInt(32.W))
    val l1dFlush = Decoupled(UInt(32.W))
    val l2Flush = Decoupled(UInt(32.W))
    val writebackComplete = Input(Valid(UInt(32.W)))
  })

  private val state = RegInit(State.Idle)
  val lineAddress = Reg(UInt(32.W))
  val heldResult = Reg(new StoreWriteResult(config))
  val hostMatch = io.enabled && !io.input.bits.accessFault &&
    io.input.bits.address === io.address
  val inputLineAddress = Cat(io.input.bits.address(31, 5), 0.U(5.W))

  io.l1dFlush.valid := state === State.TransferL1D
  io.l1dFlush.bits := lineAddress
  io.l2Flush.valid := state === State.EvictL2
  io.l2Flush.bits := lineAddress

  io.output.valid := (state === State.Idle && io.input.valid && !hostMatch) ||
    state === State.Release
  io.output.bits := Mux(state === State.Release, heldResult, io.input.bits)
  // A matching result is consumed into the controller immediately. This keeps
  // the exact store identity stable even while the L1D/L2 transfers and the
  // retained ID-5 owner take an arbitrary number of cycles.
  io.input.ready := state === State.Idle && (hostMatch || io.output.ready)

  when(state === State.Idle && io.input.fire && hostMatch) {
    heldResult := io.input.bits
    lineAddress := inputLineAddress
    state := State.TransferL1D
  }
  when(io.l1dFlush.fire) {
    state := State.EvictL2
  }
  when(io.l2Flush.fire) {
    state := State.WaitWriteback
  }
  when(state === State.WaitWriteback && io.writebackComplete.valid &&
      io.writebackComplete.bits === lineAddress) {
    state := State.Release
  }
  when(state === State.Release && io.output.fire) {
    state := State.Idle
  }

  when(io.l1dFlush.valid || io.l2Flush.valid) {
    assert(lineAddress(4, 0) === 0.U,
      "trace host flush must transfer a cache-line-aligned address")
  }
}
