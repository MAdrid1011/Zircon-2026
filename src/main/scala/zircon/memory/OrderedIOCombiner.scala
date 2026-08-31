package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class OrderedIORequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val order = UInt(64.W)
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val write = Bool()
  val size = UInt(2.W)
  val writeData = UInt(32.W)
  val writeMask = UInt(4.W)
  val burstable = Bool()
  val regionTag = UInt(8.W)
}

class OrderedIOGroup(
    val maxBeats: Int = 4,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Bundle {
  val count = UInt(log2Ceil(maxBeats + 1).W)
  val requests = Vec(maxBeats, new OrderedIORequest(config))
}

/** Collects adjacent, program-order device requests. The commit controller
  * asserts forceFlush when no additional adjacent ROB entry is available.
  * An incompatible request remains backpressured until the current group has
  * been accepted, so device order cannot be inverted.
  */
class OrderedIOCombiner(
    val maxBeats: Int = 4,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  require(maxBeats == 4, "the architectural MMIO burst limit is four beats")

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OrderedIORequest(config)))
    val forceFlush = Input(Bool())
    val out = Decoupled(new OrderedIOGroup(maxBeats, config))
  })

  val entries = Reg(Vec(maxBeats, new OrderedIORequest(config)))
  val count = RegInit(0.U(log2Ceil(maxBeats + 1).W))
  val nonEmpty = count =/= 0.U
  val full = count === maxBeats.U
  val lastIndex = Mux(nonEmpty, count - 1.U, 0.U)
  val head = entries(0)
  val last = entries(lastIndex(log2Ceil(maxBeats) - 1, 0))
  val stride = (1.U(33.W) << io.in.bits.size)(31, 0)

  val compatible = nonEmpty &&
    head.burstable && io.in.bits.burstable &&
    (io.in.bits.order === last.order + 1.U) &&
    (io.in.bits.address === last.address + stride) &&
    (io.in.bits.write === head.write) &&
    (io.in.bits.size === head.size) &&
    (io.in.bits.regionTag === head.regionTag) &&
    (io.in.bits.address(31, 12) === head.address(31, 12))

  val closeForIncompatible = io.in.valid && !compatible
  io.out.valid := nonEmpty && (io.forceFlush || full || !head.burstable || closeForIncompatible)
  io.out.bits.count := count
  io.out.bits.requests := entries

  io.in.ready := !nonEmpty || (!io.out.valid && compatible && !full)

  when(io.out.fire) {
    count := 0.U
  }.elsewhen(io.in.fire) {
    entries(count(log2Ceil(maxBeats) - 1, 0)) := io.in.bits
    count := count + 1.U
  }

  assert(count <= maxBeats.U)
  when(nonEmpty) {
    assert(head.writeMask.orR || !head.write)
  }
}
