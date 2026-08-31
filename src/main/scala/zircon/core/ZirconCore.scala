package zircon.core

import chisel3._
import chisel3.util.PopCount
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionResult, FaultCandidate, LongIssueQueue, LongPipe,
  M1BackendSubsystem, MemIssueQueue, SourceKind, UopClass}
import zircon.frontend.M1Frontend
import zircon.memory.{AXIDataReadEngine, AXIDataStoreEngine, DualLSUIngress, L1DLoadCache}
import zircon.trace.RetireTraceFormatter

/** Executable M3 integration of fetch, integer backend, E2, and LSU ownership.
 *
 * E2 and M0/M1 share the two auxiliary PRF read ports under the frozen global
 * three-start limit. The LSU request/response ownership path is live through
 * the completion network. Cacheable integer loads and commit-authorized
 * cacheable stores own real AXI transactions; MMIO, atomics, writeback, L2,
 * and the final dual-LSU conflict policy remain later M3 work.
 */
class ZirconCore(cfg: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  override val desiredName: String = "ZirconCore"

  val io = IO(new ZirconCoreIO(cfg))

  val frontend = Module(new M1Frontend(cfg))
  val backend = Module(new M1BackendSubsystem(cfg))
  val longQueue = Module(new LongIssueQueue(cfg))
  val longPipe = Module(new LongPipe(cfg))
  val memQueue = Module(new MemIssueQueue(cfg, allowIssueRecycle = false))
  val lsuIngress = Module(new DualLSUIngress(cfg))
  val l1dLoadCache = Module(new L1DLoadCache(cfg))
  val dataReadEngine = Module(new AXIDataReadEngine(cfg))
  val dataStoreEngine = Module(new AXIDataStoreEngine(cfg))
  val auxiliaryRead = Module(new AuxiliaryReadArbiter(cfg))

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
  val traceReadRequired = if (cfg.enableTrace) {
    backend.io.retired.map(retired =>
      retired.valid && retired.bits.entry.allocatesPhysical).reduce(_ || _)
  } else false.B
  val integerStarts = PopCount(Seq(backend.io.e0Start, backend.io.e1Start))
  auxiliaryRead.io.traceReadRequired := traceReadRequired
  auxiliaryRead.io.startSlots := 3.U - integerStarts
  auxiliaryRead.io.robHeadTag := backend.io.robHead.bits.robTag
  auxiliaryRead.io.readData := backend.io.auxReadData

  auxiliaryRead.io.candidate(0).valid := longQueue.io.issue.valid
  auxiliaryRead.io.candidate(0).bits.robTag := longQueue.io.issue.bits.robTag
  for (source <- 0 until 2) {
    auxiliaryRead.io.candidate(0).bits.sourcePhysical(source) :=
      longQueue.io.issue.bits.sourcePhysical(source)
    auxiliaryRead.io.candidate(0).bits.sourceRequired(source) :=
      longQueue.io.issue.bits.sourceKind(source) === SourceKind.IntegerRegister
  }
  longPipe.io.robHeadTag := backend.io.robHead.bits.robTag
  longPipe.io.squash := backend.io.squash
  longPipe.io.flush := backend.io.globalFlush
  longPipe.io.input.valid := longQueue.io.issue.valid && auxiliaryRead.io.grant(0)
  longPipe.io.input.bits.uop := longQueue.io.issue.bits
  longPipe.io.input.bits.lhs := auxiliaryRead.io.candidateData(0)(0)
  longPipe.io.input.bits.rhs := auxiliaryRead.io.candidateData(0)(1)
  longQueue.io.issue.ready := longPipe.io.input.ready && auxiliaryRead.io.grant(0)

  for (lane <- 0 until cfg.decodeWidth) {
    memQueue.io.enqueue(lane) <> backend.io.memEnqueue(lane)
  }
  backend.io.memCapacity := memQueue.io.enqueueCapacity
  memQueue.io.integerReady := backend.io.integerReady
  memQueue.io.robHeadTag := backend.io.robHead.bits.robTag
  memQueue.io.squash := backend.io.squash
  memQueue.io.flush := backend.io.globalFlush

  for ((queueIssue, candidate) <- Seq(
      (memQueue.io.m0Issue, 1), (memQueue.io.m1Issue, 2))) {
    auxiliaryRead.io.candidate(candidate).valid := queueIssue.valid
    auxiliaryRead.io.candidate(candidate).bits.robTag := queueIssue.bits.robTag
    for (source <- 0 until 2) {
      auxiliaryRead.io.candidate(candidate).bits.sourcePhysical(source) :=
        queueIssue.bits.sourcePhysical(source)
      auxiliaryRead.io.candidate(candidate).bits.sourceRequired(source) :=
        queueIssue.bits.sourceKind(source) === SourceKind.IntegerRegister
    }
  }

  lsuIngress.io.m0Issue.valid := memQueue.io.m0Issue.valid && auxiliaryRead.io.grant(1)
  lsuIngress.io.m0Issue.bits := memQueue.io.m0Issue.bits
  memQueue.io.m0Issue.ready := lsuIngress.io.m0Issue.ready && auxiliaryRead.io.grant(1)
  lsuIngress.io.m1Issue.valid := memQueue.io.m1Issue.valid && auxiliaryRead.io.grant(2)
  lsuIngress.io.m1Issue.bits := memQueue.io.m1Issue.bits
  memQueue.io.m1Issue.ready := lsuIngress.io.m1Issue.ready && auxiliaryRead.io.grant(2)
  for (source <- 0 until 2) {
    lsuIngress.io.prfReadData(source) := auxiliaryRead.io.candidateData(1)(source)
    lsuIngress.io.prfReadData(source + 2) :=
      auxiliaryRead.io.candidateData(2)(source)
  }
  backend.io.memoryExecutionRead := lsuIngress.io.robRead
  lsuIngress.io.robContext := backend.io.memoryExecutionContext
  lsuIngress.io.robHeadTag := backend.io.robHead.bits.robTag
  lsuIngress.io.squash := backend.io.squash
  lsuIngress.io.flush := backend.io.globalFlush
  lsuIngress.io.loadForwardReady := l1dLoadCache.io.request.ready
  l1dLoadCache.io.request.valid := lsuIngress.io.loadForward.valid
  l1dLoadCache.io.request.bits := lsuIngress.io.loadForward.bits
  lsuIngress.io.loadComplete <> l1dLoadCache.io.completion
  l1dLoadCache.io.dataRequest <> dataReadEngine.io.request
  l1dLoadCache.io.dataResponse <> dataReadEngine.io.response
  l1dLoadCache.io.robHeadTag := backend.io.robHead.bits.robTag
  l1dLoadCache.io.squash := backend.io.squash
  l1dLoadCache.io.flush := backend.io.globalFlush
  l1dLoadCache.io.storeAccept.valid := lsuIngress.io.storeEffect.valid
  l1dLoadCache.io.storeAccept.bits := lsuIngress.io.storeEffect.bits
  dataStoreEngine.io.invalidateReady := l1dLoadCache.io.storeAcceptReady
  dataStoreEngine.io.effect.valid := lsuIngress.io.storeEffect.valid
  dataStoreEngine.io.effect.bits := lsuIngress.io.storeEffect.bits
  lsuIngress.io.storeEffect.ready := dataStoreEngine.io.effect.ready
  l1dLoadCache.io.storeCommit.valid := dataStoreEngine.io.effect.fire
  l1dLoadCache.io.storeCommit.bits := dataStoreEngine.io.effect.bits
  l1dLoadCache.io.activeStore := dataStoreEngine.io.activeStore
  lsuIngress.io.loadContextRead.valid := false.B
  lsuIngress.io.loadContextRead.bits := 0.U
  val robHeadIsStore = backend.io.robHead.valid &&
    backend.io.robHead.bits.entry.decoded.uopClass === UopClass.Store
  // A store becomes externally visible only when its true ROB head owns an SQ
  // record. It remains incomplete until the exact B response reaches M0.
  lsuIngress.io.commitAuthorize.valid := robHeadIsStore
  lsuIngress.io.commitAuthorize.bits := backend.io.robHead.bits.robTag
  lsuIngress.io.storeWriteResult <> dataStoreEngine.io.result
  lsuIngress.io.storeEffectComplete.valid := dataStoreEngine.io.result.fire
  lsuIngress.io.storeEffectComplete.bits.robTag := dataStoreEngine.io.result.bits.robTag
  lsuIngress.io.storeEffectComplete.bits.accessFault :=
    dataStoreEngine.io.result.bits.accessFault
  for (lane <- 0 until cfg.commitWidth) {
    lsuIngress.io.retire(lane).valid := backend.io.retired(lane).valid
    lsuIngress.io.retire(lane).bits := backend.io.retired(lane).bits.robTag
  }

  backend.io.otherCompletion(0) <> longPipe.io.completion
  backend.io.otherCompletion(1) <> lsuIngress.io.m0Completion
  backend.io.otherCompletion(2) <> lsuIngress.io.m1Completion
  backend.io.otherFault(0) := 0.U.asTypeOf(new FaultCandidate(cfg))
  backend.io.otherFault(1) := lsuIngress.io.fault(0)
  backend.io.otherFault(2) := lsuIngress.io.fault(1)
  backend.io.interrupts := io.interrupts
  backend.io.interruptBlocked := lsuIngress.io.storeCommitInFlight
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
      traceReadPhysical, auxiliaryRead.io.readPhysical(lane))
  }

  io.axi.aw.valid := dataStoreEngine.io.aw.valid
  io.axi.aw.bits := dataStoreEngine.io.aw.bits
  dataStoreEngine.io.aw.ready := io.axi.aw.ready
  io.axi.w.valid := dataStoreEngine.io.w.valid
  io.axi.w.bits := dataStoreEngine.io.w.bits
  dataStoreEngine.io.w.ready := io.axi.w.ready
  dataStoreEngine.io.b.valid := io.axi.b.valid
  dataStoreEngine.io.b.bits := io.axi.b.bits
  io.axi.b.ready := dataStoreEngine.io.b.ready
  val arLockValid = RegInit(false.B)
  val arLockData = RegInit(false.B)
  val dataArTurn = RegInit(false.B)
  val unlockedDataSelection = Mux(frontend.io.ar.valid && dataReadEngine.io.ar.valid,
    dataArTurn, dataReadEngine.io.ar.valid)
  val selectDataAr = Mux(arLockValid, arLockData, unlockedDataSelection)
  io.axi.ar.valid := Mux(selectDataAr, dataReadEngine.io.ar.valid,
    frontend.io.ar.valid)
  io.axi.ar.bits := Mux(selectDataAr, dataReadEngine.io.ar.bits,
    frontend.io.ar.bits)
  frontend.io.ar.ready := io.axi.ar.ready && !selectDataAr
  dataReadEngine.io.ar.ready := io.axi.ar.ready && selectDataAr
  when(!arLockValid && io.axi.ar.valid && !io.axi.ar.ready) {
    arLockValid := true.B
    arLockData := selectDataAr
  }
  when(io.axi.ar.fire) {
    arLockValid := false.B
    dataArTurn := !selectDataAr
  }

  val rToFetch = io.axi.r.bits.id === 0.U
  frontend.io.r.valid := io.axi.r.valid && rToFetch
  frontend.io.r.bits := io.axi.r.bits
  dataReadEngine.io.r.valid := io.axi.r.valid && !rToFetch
  dataReadEngine.io.r.bits := io.axi.r.bits
  io.axi.r.ready := Mux(rToFetch, frontend.io.r.ready,
    dataReadEngine.io.r.ready)
  when(io.axi.r.valid) {
    assert(io.axi.r.bits.id <= 4.U,
      "top-level AXI R used an ID outside fetch and four data owners")
  }

  io.trace.foreach { trace =>
    val formatter = Module(new RetireTraceFormatter(cfg))
    formatter.io.retired := backend.io.retired
    formatter.io.memoryMetadata := lsuIngress.io.retireMetadata
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
    observation.m0Ingress := lsuIngress.io.m0Issue.fire
    observation.m1Ingress := lsuIngress.io.m1Issue.fire
    observation.m0Fault := lsuIngress.io.fault(0).valid
    observation.m1Fault := lsuIngress.io.fault(1).valid
    observation.m0FaultTag := lsuIngress.io.fault(0).record.robTag
    observation.m1FaultTag := lsuIngress.io.fault(1).record.robTag
    observation.robHeadTag := backend.io.robHead.bits.robTag
  }
}
