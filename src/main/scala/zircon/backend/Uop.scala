package zircon.backend

import chisel3._
import chisel3.util._

object ExecutionEndpoint extends ChiselEnum {
  val E0IntCtrl, E1IntSimple, E2LongPipe, M0General, M1Load = Value
}

object UopClass extends ChiselEnum {
  val Integer, Branch, Multiply, Divide, Floating, Load, Store, Atomic, Csr, System = Value
}

object SourceKind extends ChiselEnum {
  val None, IntegerRegister, FloatingRegister, Immediate, ProgramCounter = Value
}

/** Compact issue-queue reference. PC, instruction bits, prediction metadata,
  * and complete architectural side effects reside in the ROB entry.
  */
class UopRef extends Bundle {
  val robTag = UInt(5.W)
  val endpoint = ExecutionEndpoint()
  val uopClass = UopClass()
  val operation = UInt(7.W)
  val sourceKind = Vec(3, SourceKind())
  val sourcePhysical = Vec(2, UInt(6.W))
  val sourceReady = Vec(3, Bool())
  val destinationPhysical = UInt(6.W)
  val writesInteger = Bool()
  val writesFloat = Bool()
  val immediate = UInt(32.W)
}
