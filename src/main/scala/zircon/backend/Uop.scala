package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.FloatingOperation

object ExecutionEndpoint extends ChiselEnum {
  val E0IntCtrl, E1IntSimple, E2LongPipe, M0General, M1Load = Value
}

object EndpointMask {
  val Width = 5
  val None = 0
  val E0 = 1 << 0
  val E1 = 1 << 1
  val E2 = 1 << 2
  val M0 = 1 << 3
  val M1 = 1 << 4
  val IntegerSimple = E0 | E1
  val CacheableLoadCandidate = M0 | M1
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
class UopRef(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val allowedEndpoints = UInt(EndpointMask.Width.W)
  val uopClass = UopClass()
  val operation = UInt(7.W)
  val sourceKind = Vec(3, SourceKind())
  val sourcePhysical = Vec(2, UInt(6.W))
  val sourceReady = Vec(3, Bool())
  val destinationPhysical = UInt(6.W)
  val writesInteger = Bool()
  val writesFloat = Bool()
  // Floating operands are architectural FPR indexes. They deliberately stay
  // separate from the renamed integer physical-source namespace.
  val floatingOperation = FloatingOperation()
  val floatingSource = Vec(3, UInt(5.W))
  val floatingDestination = UInt(5.W)
  // Static rm is copied from the instruction; dynamic rm is resolved from the
  // committed frm value at dispatch, after all older FP-control CSR writes.
  val floatingRoundingMode = UInt(3.W)
  val immediate = UInt(32.W)
}
