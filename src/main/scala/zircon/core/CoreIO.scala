package zircon.core

import chisel3._
import zircon.ZirconCoreConfig
import zircon.bus.AXI4MasterPort
import zircon.trace.RetireEvent

class InterruptInputs extends Bundle {
  val meip = Bool()
  val msip = Bool()
  val mtip = Bool()
}

class ZirconCoreIO(cfg: ZirconCoreConfig) extends Bundle {
  val axi = new AXI4MasterPort(addressWidth = 32, dataWidth = 32, idWidth = 4)
  val interrupts = Input(new InterruptInputs)
  val trace = if (cfg.enableTrace) Some(Output(Vec(cfg.commitWidth, new RetireEvent))) else None
}
