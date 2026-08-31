package zircon.memory

import chisel3._
import zircon.{PMARegionKind, ZirconCoreConfig}

class PMAAttributes extends Bundle {
  val kind = UInt(2.W)
  val readable = Bool()
  val writable = Bool()
  val executable = Bool()
  val atomic = Bool()

  def cacheable: Bool = kind === PMARegionKind.Memory.code.U
  def device: Bool = kind === PMARegionKind.DeviceStrong.code.U ||
    kind === PMARegionKind.DeviceBurstable.code.U
  def burstable: Bool = kind === PMARegionKind.DeviceBurstable.code.U
}

/** Ordered, first-match PMA classifier. Earlier configuration entries have
  * priority, which permits a narrow device window to override a broad region.
  */
class PMAClassifier(cfg: ZirconCoreConfig) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(32.W))
    val attributes = Output(new PMAAttributes)
    val matched = Output(Bool())
  })

  io.attributes := 0.U.asTypeOf(new PMAAttributes)
  io.matched := false.B

  var previousMatch: Bool = false.B
  for (entry <- cfg.pma) {
    val matches = (io.address & entry.mask.U(32.W)) === (entry.base & entry.mask).U(32.W)
    val select = matches && !previousMatch
    when(select) {
      io.attributes.kind := entry.kind.code.U
      io.attributes.readable := entry.readable.B
      io.attributes.writable := entry.writable.B
      io.attributes.executable := entry.executable.B
      io.attributes.atomic := entry.atomic.B
      io.matched := true.B
    }
    previousMatch = previousMatch || matches
  }
}
