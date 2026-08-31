package zircon.core

import chisel3._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionResult, FaultCandidate, LongIssueQueue, LongPipe,
  M1BackendSubsystem}
import zircon.frontend.M1Frontend
import zircon.trace.RetireTraceFormatter

/** Executable M2 integration of fetch, integer backend, E2, and simulation trace.
  *
  * LongPipe is connected through the existing unified completion network. Both
  * LSU endpoints deliberately advertise zero capacity until M3, so they cannot
  * fabricate architectural progress.
  */
class ZirconCore(cfg: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  override val desiredName: String = "ZirconCore"

  val io = IO(new ZirconCoreIO(cfg))

  val frontend = Module(new M1Frontend(cfg))
  val backend = Module(new M1BackendSubsystem(cfg))
  val longQueue = Module(new LongIssueQueue(cfg))
  val longPipe = Module(new LongPipe(cfg))

  frontend.io.enable := true.B
  for (lane <- 0 until cfg.decodeWidth) {
    backend.io.input(lane).valid := frontend.io.decode(lane).valid
    backend.io.input(lane).bits := frontend.io.decode(lane).bits
    frontend.io.decode(lane).ready := backend.io.input(lane).ready
  }
  frontend.io.branchTraining := backend.io.branchTraining
  frontend.io.executeRecovery := backend.io.frontendRecovery
  frontend.io.commitRedirect := backend.io.redirect

  for (lane <- 0 until cfg.decodeWidth) {
    longQueue.io.enqueue(lane) <> backend.io.longEnqueue(lane)
  }
  backend.io.longCapacity := longQueue.io.enqueueCapacity
  longQueue.io.integerReady := backend.io.integerReady
  longQueue.io.robHeadTag := backend.io.robHead.bits.robTag
  longQueue.io.squash := backend.io.squash
  longQueue.io.flush := backend.io.globalFlush
  longPipe.io.robHeadTag := backend.io.robHead.bits.robTag
  longPipe.io.squash := backend.io.squash
  longPipe.io.flush := backend.io.globalFlush

  val traceReadRequired = if (cfg.enableTrace) {
    backend.io.retired.map(retired =>
      retired.valid && retired.bits.entry.allocatesPhysical).reduce(_ || _)
  } else false.B
  longPipe.io.input.valid := longQueue.io.issue.valid && !traceReadRequired
  longPipe.io.input.bits.uop := longQueue.io.issue.bits
  longPipe.io.input.bits.lhs := backend.io.auxReadData(0)
  longPipe.io.input.bits.rhs := backend.io.auxReadData(1)
  longQueue.io.issue.ready := longPipe.io.input.ready && !traceReadRequired

  backend.io.memCapacity := 0.U
  for (lane <- 0 until cfg.decodeWidth) {
    backend.io.memEnqueue(lane).ready := false.B
  }
  backend.io.otherCompletion(0) <> longPipe.io.completion
  for (endpoint <- 1 until 3) {
    backend.io.otherCompletion(endpoint).valid := false.B
    backend.io.otherCompletion(endpoint).bits :=
      0.U.asTypeOf(new CompletionResult(cfg))
  }
  for (endpoint <- 0 until 3) {
    backend.io.otherFault(endpoint) := 0.U.asTypeOf(new FaultCandidate(cfg))
  }
  backend.io.interrupts := io.interrupts
  backend.io.interruptBlocked := false.B
  backend.io.systemSerializingReady := true.B
  backend.io.fpCommit.valid := false.B
  backend.io.fpCommit.bits.flags := 0.U
  backend.io.fpCommit.bits.dirty := false.B
  for (lane <- 0 until cfg.commitWidth) {
    val traceReadPhysical = Mux(
      backend.io.retired(lane).valid &&
        backend.io.retired(lane).bits.entry.allocatesPhysical,
      backend.io.retired(lane).bits.entry.newPhysicalDestination,
      0.U)
    backend.io.auxReadPhysical(lane) := Mux(traceReadRequired,
      traceReadPhysical, longQueue.io.issue.bits.sourcePhysical(lane))
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

  io.m2Observation.foreach { observation =>
    observation.e0Start := backend.io.e0Start
    observation.e1Start := backend.io.e1Start
    observation.e2Start := longPipe.io.input.fire
    observation.e1Completion := backend.io.e1Completion
    observation.e2Completion := backend.io.e2Completion
  }
}
