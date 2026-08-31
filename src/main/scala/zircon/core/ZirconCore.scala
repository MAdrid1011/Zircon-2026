package zircon.core

import chisel3._
import zircon.ZirconCoreConfig
import zircon.bus.AXI4Defaults

/** Integration shell established by M0. Pipeline blocks replace the idle
  * master in subsequent milestones without changing the public top-level IO.
  */
class ZirconCore(cfg: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  override val desiredName: String = "ZirconCore"

  val io = IO(new ZirconCoreIO(cfg))

  AXI4Defaults.driveIdle(io.axi)
  io.trace.foreach(_ := 0.U.asTypeOf(Vec(cfg.commitWidth, new zircon.trace.RetireEvent)))

  // Keep interrupt pins represented in the M0 netlist before the CSR/trap
  // controller is connected. They are deliberately not architectural state.
  dontTouch(io.interrupts)
}
