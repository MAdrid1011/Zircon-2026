package zircon.trace

import chisel3._

class RetireEvent extends Bundle {
  val valid = Bool()
  val order = UInt(64.W)
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val privilege = UInt(2.W)

  val gprWrite = Bool()
  val gprAddress = UInt(5.W)
  val gprData = UInt(32.W)
  val fprWrite = Bool()
  val fprAddress = UInt(5.W)
  val fprData = UInt(32.W)

  val csrWrite = Bool()
  val csrAddress = UInt(12.W)
  val csrData = UInt(32.W)

  val memoryAddress = UInt(32.W)
  val memoryReadMask = UInt(4.W)
  val memoryWriteMask = UInt(4.W)
  val memoryReadData = UInt(32.W)
  val memoryWriteData = UInt(32.W)

  val trap = Bool()
  val interrupt = Bool()
  val cause = UInt(32.W)
  val trapValue = UInt(32.W)
  val fflags = UInt(5.W)
}
