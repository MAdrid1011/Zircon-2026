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

/** Test-only M2/M3 handshakes. This port is absent from every production config. */
class M2Observation extends Bundle {
  val e0Start = Bool()
  val e1Start = Bool()
  val e2Start = Bool()
  val e1Completion = Bool()
  val e2Completion = Bool()
  val m0Ingress = Bool()
  val m1Ingress = Bool()
  val m0Fault = Bool()
  val m1Fault = Bool()
  val m0FaultTag = UInt(6.W)
  val m1FaultTag = UInt(6.W)
  val robHeadTag = UInt(6.W)
  val loadQueueCount = UInt(5.W)
  val storeQueueCount = UInt(5.W)
  val orderedGroupValid = Bool()
  val orderedGroupCount = UInt(3.W)
}

class ZirconCoreIO(cfg: ZirconCoreConfig) extends Bundle {
  val axi = new AXI4MasterPort(addressWidth = 32, dataWidth = 32, idWidth = 4)
  val interrupts = Input(new InterruptInputs)
  val trace = if (cfg.enableTrace) Some(Output(Vec(cfg.commitWidth, new RetireEvent))) else None
  val m2Observation = if (cfg.enableM2Observation) Some(Output(new M2Observation)) else None
}
