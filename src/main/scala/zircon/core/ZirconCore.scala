package zircon.core

import chisel3._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionResult, FaultCandidate, M1BackendSubsystem}
import zircon.frontend.M1Frontend
import zircon.trace.RetireTraceFormatter

/** Executable M1 integration of fetch, integer backend, and simulation trace.
  *
  * LongPipe and both LSU endpoints deliberately advertise zero capacity. They
  * cannot accept a uop or return a completion until their real M2/M3 paths are
  * integrated, so the core never fabricates architectural progress.
  */
class ZirconCore(cfg: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  override val desiredName: String = "ZirconCore"

  val io = IO(new ZirconCoreIO(cfg))

  val frontend = Module(new M1Frontend(cfg))
  val backend = Module(new M1BackendSubsystem(cfg))

  frontend.io.enable := true.B
  for (lane <- 0 until cfg.decodeWidth) {
    backend.io.input(lane).valid := frontend.io.decode(lane).valid
    backend.io.input(lane).bits := frontend.io.decode(lane).bits
    frontend.io.decode(lane).ready := backend.io.input(lane).ready
  }
  frontend.io.branchTraining := backend.io.branchTraining
  frontend.io.executeRecovery := backend.io.frontendRecovery
  frontend.io.commitRedirect := backend.io.redirect

  backend.io.longCapacity := 0.U
  backend.io.memCapacity := 0.U
  for (lane <- 0 until cfg.decodeWidth) {
    backend.io.longEnqueue(lane).ready := false.B
    backend.io.memEnqueue(lane).ready := false.B
  }
  for (endpoint <- 0 until 3) {
    backend.io.otherCompletion(endpoint).valid := false.B
    backend.io.otherCompletion(endpoint).bits :=
      0.U.asTypeOf(new CompletionResult(cfg))
    backend.io.otherFault(endpoint) := 0.U.asTypeOf(new FaultCandidate(cfg))
  }
  backend.io.interrupts := io.interrupts
  backend.io.interruptBlocked := false.B
  backend.io.systemSerializingReady := true.B
  backend.io.fpCommit.valid := false.B
  backend.io.fpCommit.bits.flags := 0.U
  backend.io.fpCommit.bits.dirty := false.B
  for (lane <- 0 until cfg.commitWidth) {
    backend.io.auxReadPhysical(lane) := Mux(
      backend.io.retired(lane).valid &&
        backend.io.retired(lane).bits.entry.allocatesPhysical,
      backend.io.retired(lane).bits.entry.newPhysicalDestination,
      0.U)
  }

  io.axi.aw.valid := false.B
  io.axi.aw.bits := 0.U.asTypeOf(io.axi.aw.bits)
  io.axi.w.valid := false.B
  io.axi.w.bits := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.b.ready := true.B
  io.axi.ar.valid := frontend.io.ar.valid
  io.axi.ar.bits := frontend.io.ar.bits
  frontend.io.ar.ready := io.axi.ar.ready
  frontend.io.r.valid := io.axi.r.valid
  frontend.io.r.bits := io.axi.r.bits
  io.axi.r.ready := frontend.io.r.ready

  io.trace.foreach { trace =>
    val formatter = Module(new RetireTraceFormatter(cfg))
    formatter.io.retired := backend.io.retired
    formatter.io.gprData := backend.io.auxReadData
    formatter.io.csrWrite := backend.io.csrWrite
    formatter.io.trapCommit := backend.io.trapCommit
    formatter.io.trapEntry := backend.io.trapEntry
    formatter.io.trapLane := backend.io.trapLane
    formatter.io.currentFflags := backend.io.currentFflags
    trace := formatter.io.events
  }
}
