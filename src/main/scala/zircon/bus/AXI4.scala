package zircon.bus

import chisel3._
import chisel3.util._

object AXI4Resp {
  val Okay: UInt = 0.U(2.W)
  val ExOkay: UInt = 1.U(2.W)
  val SlaveError: UInt = 2.U(2.W)
  val DecodeError: UInt = 3.U(2.W)
}

object AXI4Burst {
  val Fixed: UInt = 0.U(2.W)
  val Incrementing: UInt = 1.U(2.W)
  val Wrapping: UInt = 2.U(2.W)
}

class AXI4Address(val addressWidth: Int = 32, val idWidth: Int = 4) extends Bundle {
  val id = UInt(idWidth.W)
  val addr = UInt(addressWidth.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = Bool()
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
}

class AXI4WriteData(val dataWidth: Int = 32) extends Bundle {
  require(dataWidth % 8 == 0)
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
  val last = Bool()
}

class AXI4WriteResponse(val idWidth: Int = 4) extends Bundle {
  val id = UInt(idWidth.W)
  val resp = UInt(2.W)
}

class AXI4ReadData(val dataWidth: Int = 32, val idWidth: Int = 4) extends Bundle {
  val id = UInt(idWidth.W)
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

/** AXI4 master port. Zircon emits only INCR bursts but accepts all legal
  * response channel backpressure and cross-ID read response ordering.
  */
class AXI4MasterPort(
    val addressWidth: Int = 32,
    val dataWidth: Int = 32,
    val idWidth: Int = 4
) extends Bundle {
  val aw = Decoupled(new AXI4Address(addressWidth, idWidth))
  val w = Decoupled(new AXI4WriteData(dataWidth))
  val b = Flipped(Decoupled(new AXI4WriteResponse(idWidth)))
  val ar = Decoupled(new AXI4Address(addressWidth, idWidth))
  val r = Flipped(Decoupled(new AXI4ReadData(dataWidth, idWidth)))
}

object AXI4Defaults {
  def driveIdle(port: AXI4MasterPort): Unit = {
    port.aw.valid := false.B
    port.aw.bits := 0.U.asTypeOf(port.aw.bits)
    port.w.valid := false.B
    port.w.bits := 0.U.asTypeOf(port.w.bits)
    port.b.ready := true.B
    port.ar.valid := false.B
    port.ar.bits := 0.U.asTypeOf(port.ar.bits)
    port.r.ready := true.B
  }
}
