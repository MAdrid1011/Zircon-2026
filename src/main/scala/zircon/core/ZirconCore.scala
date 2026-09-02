package zircon.core

import chisel3._
import chisel3.util.{Arbiter, Decoupled, PopCount, RRArbiter, Valid}
import zircon.{PMARegionKind, ZirconCoreConfig}
import zircon.backend.{CompletionResult, FaultCandidate, FloatingIssueQueue,
  FloatingScoreboard, LongIssueQueue, LongPipe, M1BackendSubsystem, MemIssueQueue,
  ROBTagOrder, SourceKind, UopClass}
import zircon.frontend.{IntOperation, M1Frontend}
import zircon.memory.{AtomicMemoryEngine, AXIDataReadEngine, AXIL2WritebackEngine,
  AXIOrderedIOEngine, CacheFenceDrainController, DualLSUIngress,
  ExclusiveL2TransferStore, ExternalCoherenceController, L1DLoadCache,
  HostStoreFlush, L2DemandClient, L2DemandRequest, LoadCompletion,
  OrderedIOCombiner, OrderedIOGroup, OrderedIOGroupStreamer, StoreWriteResult}
import zircon.trace.RetireTraceFormatter

/** Executable M3 integration of fetch, integer backend, E2, and LSU ownership.
 *
 * E2 and M0/M1 share the two auxiliary PRF read ports under the frozen global
 * three-start limit. The LSU request/response ownership path is live through
 * the completion network. Cacheable integer loads and commit-authorized
 * cacheable stores own the exclusive dirty L1D copy; exact-head MMIO and
 * atomics and dirty L2 victims own real AXI transactions. Burst collection and
 * the final dual-LSU conflict policy remain later M3 work.
 */
class ZirconCore(cfg: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  override val desiredName: String = "ZirconCore"

  val io = IO(new ZirconCoreIO(cfg))

  val frontend = Module(new M1Frontend(cfg))
  val backend = Module(new M1BackendSubsystem(cfg))
  val longQueue = Module(new LongIssueQueue(cfg))
  val longPipe = Module(new LongPipe(cfg))
  val floatingQueue = Module(new FloatingIssueQueue(cfg))
  val floatingScoreboard = Module(new FloatingScoreboard(cfg))
  val memQueue = Module(new MemIssueQueue(cfg, allowIssueRecycle = false))
  val lsuIngress = Module(new DualLSUIngress(cfg))
  val l1dLoadCache = Module(new L1DLoadCache(cfg))
  val l2TransferStore = Module(new ExclusiveL2TransferStore(cfg))
  val l2WritebackEngine = Module(new AXIL2WritebackEngine(cfg))
  val cacheFenceDrain = Module(new CacheFenceDrainController)
  val l2DemandEngine = Module(new AXIDataReadEngine(cfg))
  val l2DemandArbiter = Module(new RRArbiter(new L2DemandRequest(cfg), 2))
  val orderedIOEngine = Module(new AXIOrderedIOEngine(config = cfg))
  val atomicEngine = Module(new AtomicMemoryEngine(cfg))
  val orderedIOCombiner = Module(new OrderedIOCombiner(config = cfg))
  val orderedIOStreamer = Module(new OrderedIOGroupStreamer(config = cfg))
  val auxiliaryRead = Module(new AuxiliaryReadArbiter(cfg))
  val externalCoherence = Module(new ExternalCoherenceController)

  externalCoherence.io.request.valid := io.externalCoherence.request.valid
  externalCoherence.io.request.bits := io.externalCoherence.request.bits
  io.externalCoherence.request.ready := externalCoherence.io.request.ready
  io.externalCoherence.response.valid := externalCoherence.io.response.valid
  io.externalCoherence.response.bits := externalCoherence.io.response.bits
  externalCoherence.io.response.ready := io.externalCoherence.response.ready
  externalCoherence.io.instructionDrained := frontend.io.coherenceDrained
  externalCoherence.io.writebackComplete := l2WritebackEngine.io.completed

  frontend.io.enable := true.B
  frontend.io.coherenceBlock := externalCoherence.io.cacheableIngressBlocked
  frontend.io.coherenceInvalidate := externalCoherence.io.l1iInvalidate
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
    floatingQueue.io.enqueue(lane) <> backend.io.floatingEnqueue(lane)
    floatingScoreboard.io.allocate(lane) := backend.io.floatingAllocate(lane)
  }
  backend.io.longCapacity := longQueue.io.enqueueCapacity
  backend.io.floatingCapacity := floatingQueue.io.enqueueCapacity
  backend.io.floatingScoreboardEmpty := floatingScoreboard.io.empty
  floatingQueue.io.integerReady := backend.io.integerReady
  floatingQueue.io.robHeadTag := backend.io.robHead.bits.robTag
  floatingQueue.io.squash := backend.io.squash
  floatingQueue.io.flush := backend.io.globalFlush
  floatingScoreboard.io.robHeadTag := backend.io.robHead.bits.robTag
  floatingScoreboard.io.squash := backend.io.squash
  floatingScoreboard.io.flush := backend.io.globalFlush
  floatingScoreboard.io.readRelease.valid := false.B
  floatingScoreboard.io.readRelease.bits := 0.U.asTypeOf(
    new zircon.backend.FloatingScoreboardAllocation(cfg))
  floatingScoreboard.io.complete.valid := false.B
  floatingScoreboard.io.complete.bits := 0.U.asTypeOf(
    new zircon.backend.FloatingScoreboardCompletion(cfg))
  floatingQueue.io.issue.ready := false.B
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

  val m0BlockedByAcquire = lsuIngress.io.atomicAcquireBarrier.valid &&
    ROBTagOrder.isYounger(memQueue.io.m0Issue.bits.robTag,
      lsuIngress.io.atomicAcquireBarrier.bits, backend.io.robHead.bits.robTag, cfg)
  val m1BlockedByAcquire = lsuIngress.io.atomicAcquireBarrier.valid &&
    ROBTagOrder.isYounger(memQueue.io.m1Issue.bits.robTag,
      lsuIngress.io.atomicAcquireBarrier.bits, backend.io.robHead.bits.robTag, cfg)
  val m0IssueAllowed = !m0BlockedByAcquire
  val m1IssueAllowed = !m1BlockedByAcquire
  for ((queueIssue, candidate, allowed) <- Seq(
      (memQueue.io.m0Issue, 1, m0IssueAllowed),
      (memQueue.io.m1Issue, 2, m1IssueAllowed))) {
    auxiliaryRead.io.candidate(candidate).valid := queueIssue.valid && allowed
    auxiliaryRead.io.candidate(candidate).bits.robTag := queueIssue.bits.robTag
    for (source <- 0 until 2) {
      auxiliaryRead.io.candidate(candidate).bits.sourcePhysical(source) :=
        queueIssue.bits.sourcePhysical(source)
      auxiliaryRead.io.candidate(candidate).bits.sourceRequired(source) :=
        queueIssue.bits.sourceKind(source) === SourceKind.IntegerRegister
    }
  }

  lsuIngress.io.m0Issue.valid := memQueue.io.m0Issue.valid && m0IssueAllowed &&
    auxiliaryRead.io.grant(1)
  lsuIngress.io.m0Issue.bits := memQueue.io.m0Issue.bits
  memQueue.io.m0Issue.ready := lsuIngress.io.m0Issue.ready && m0IssueAllowed &&
    auxiliaryRead.io.grant(1)
  lsuIngress.io.m1Issue.valid := memQueue.io.m1Issue.valid && m1IssueAllowed &&
    auxiliaryRead.io.grant(2)
  lsuIngress.io.m1Issue.bits := memQueue.io.m1Issue.bits
  memQueue.io.m1Issue.ready := lsuIngress.io.m1Issue.ready && m1IssueAllowed &&
    auxiliaryRead.io.grant(2)
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
  // Device and atomic candidates retain their ordered M0 owner. Cacheable
  // LQ forwards enter the two-port L1D directly; it owns bank conflict and
  // miss-resource backpressure, so an unaccepted payload remains at its LQ.
  for (lane <- 0 until cfg.decodeWidth) {
    val cacheableLoadForward = lsuIngress.io.loadForward(lane).bits.cacheable
    l1dLoadCache.io.request(lane).valid :=
      lsuIngress.io.loadForward(lane).valid && cacheableLoadForward &&
        !externalCoherence.io.cacheableIngressBlocked
    l1dLoadCache.io.request(lane).bits := lsuIngress.io.loadForward(lane).bits
    lsuIngress.io.loadForward(lane).ready := Mux(cacheableLoadForward,
      l1dLoadCache.io.request(lane).ready &&
        !externalCoherence.io.cacheableIngressBlocked, true.B)
  }
  l2DemandArbiter.io.in(0) <> l1dLoadCache.io.dataRequest
  l2DemandArbiter.io.in(1) <> frontend.io.l2Request
  l2DemandEngine.io.request <> l2DemandArbiter.io.out
  val l2ResponseToInstruction = l2DemandEngine.io.response.bits.client ===
    L2DemandClient.Instruction.U
  val l2ResponseToData = l2DemandEngine.io.response.bits.client ===
    L2DemandClient.Data.U
  l1dLoadCache.io.dataResponse.valid := l2DemandEngine.io.response.valid &&
    l2ResponseToData
  l1dLoadCache.io.dataResponse.bits := l2DemandEngine.io.response.bits
  frontend.io.l2Response.valid := l2DemandEngine.io.response.valid &&
    l2ResponseToInstruction
  frontend.io.l2Response.bits := l2DemandEngine.io.response.bits
  l2DemandEngine.io.response.ready := Mux(l2ResponseToInstruction,
    frontend.io.l2Response.ready, Mux(l2ResponseToData,
      l1dLoadCache.io.dataResponse.ready, false.B))
  when(l2DemandEngine.io.response.valid) {
    assert(l2ResponseToInstruction || l2ResponseToData,
      "L2 demand response named an unsupported top-level client")
  }
  l1dLoadCache.io.l2Insert <> l2TransferStore.io.insert
  frontend.io.l2Insert <> l2TransferStore.io.instructionInsert
  frontend.io.l2InsertHit := l2TransferStore.io.instructionInsertHit
  frontend.io.l2InsertData := l2TransferStore.io.instructionInsertData
  l1dLoadCache.io.l2Lookup <> l2TransferStore.io.lookup
  l1dLoadCache.io.l2Response <> l2TransferStore.io.response
  frontend.io.l2Lookup <> l2TransferStore.io.instructionLookup
  frontend.io.l2LookupResponse <> l2TransferStore.io.instructionResponse
  // A retained dirty L2 victim transfers to the ID-5 AXI owner. The owner only
  // releases it after a successful B response, including across error retries.
  l2TransferStore.io.victim <> l2WritebackEngine.io.victim
  l1dLoadCache.io.fenceDrain := cacheFenceDrain.io.l1dDrain
  l2TransferStore.io.fenceDrain := cacheFenceDrain.io.l2Drain
  cacheFenceDrain.io.l1dDrained := l1dLoadCache.io.fenceDrained
  cacheFenceDrain.io.l2Drained := l2TransferStore.io.fenceDrained
  cacheFenceDrain.io.writebackBusy := l2WritebackEngine.io.busy
  l1dLoadCache.io.robHeadTag := backend.io.robHead.bits.robTag
  l1dLoadCache.io.squash := backend.io.squash
  l1dLoadCache.io.flush := backend.io.globalFlush

  // Atomics bypass the speculative L1D slice. Their one-owner AXI lifecycle
  // returns through the LSQ first, so retire metadata and the sole M0
  // completion remain keyed by the original ROB tag.
  l1dLoadCache.io.atomicAccept.valid := lsuIngress.io.atomicEffect.valid &&
    !externalCoherence.io.cacheableIngressBlocked
  l1dLoadCache.io.atomicAccept.bits := lsuIngress.io.atomicEffect.bits
  l1dLoadCache.io.atomicRequiresExternal := atomicEngine.io.externalAccessRequired
  val atomicExternalSafe = !atomicEngine.io.externalAccessRequired ||
    l2TransferStore.io.invalidateReady
  atomicEngine.io.effect.valid := lsuIngress.io.atomicEffect.valid &&
    l1dLoadCache.io.atomicAcceptReady && atomicExternalSafe &&
    !l1dLoadCache.io.storeRequest.valid &&
    !externalCoherence.io.cacheableIngressBlocked
  atomicEngine.io.effect.bits := lsuIngress.io.atomicEffect.bits
  lsuIngress.io.atomicEffect.ready := atomicEngine.io.effect.ready &&
    l1dLoadCache.io.atomicAcceptReady && atomicExternalSafe &&
    !l1dLoadCache.io.storeRequest.valid &&
    !externalCoherence.io.cacheableIngressBlocked
  lsuIngress.io.atomicComplete <> atomicEngine.io.result
  atomicEngine.io.flush := backend.io.globalFlush
  atomicEngine.io.invalidate.valid := l1dLoadCache.io.storeRequest.fire
  atomicEngine.io.invalidate.bits := l1dLoadCache.io.storeRequest.bits.address
  atomicEngine.io.invalidateLine := externalCoherence.io.reservationInvalidateLine
  // Once AW/W were accepted, conservatively invalidate even on BRESP failure:
  // an AXI slave error must not leave an old L1D word architecturally visible.
  l1dLoadCache.io.atomicInvalidate.valid := atomicEngine.io.result.fire &&
    atomicEngine.io.result.bits.storePerformed
  l1dLoadCache.io.atomicInvalidate.bits := atomicEngine.io.result.bits.faultAddress

  val cacheStoreEffect = lsuIngress.io.storeEffect.valid &&
    lsuIngress.io.storeEffect.bits.pmaKind === PMARegionKind.Memory.code.U
  val deviceStoreEffect = lsuIngress.io.storeEffect.valid &&
    lsuIngress.io.storeEffect.bits.pmaKind === PMARegionKind.DeviceStrong.code.U
  val deviceLoadEffect = lsuIngress.io.deviceLoadEffect
  val deviceLoadAtLiveHead = deviceLoadEffect.valid && backend.io.robHead.valid &&
    backend.io.robHead.bits.entry.decoded.uopClass === UopClass.Load
  val singleDeviceGroupFromLoad = deviceLoadAtLiveHead
  val singleDeviceGroupFromStore = !singleDeviceGroupFromLoad && deviceStoreEffect
  val singleDeviceGroupValid = (singleDeviceGroupFromLoad || singleDeviceGroupFromStore) &&
    !l1dLoadCache.io.storeBusy
  val singleDeviceGroup = Wire(new OrderedIOGroup(config = cfg))
  singleDeviceGroup := 0.U.asTypeOf(singleDeviceGroup)
  singleDeviceGroup.count := 1.U
  val deviceRequest = singleDeviceGroup.requests(0)
  val selectedDeviceTag = Mux(singleDeviceGroupFromLoad,
    deviceLoadEffect.bits.robTag, lsuIngress.io.storeEffect.bits.robTag)
  val selectedDeviceAddress = Mux(singleDeviceGroupFromLoad,
    deviceLoadEffect.bits.address, lsuIngress.io.storeEffect.bits.address)
  val selectedDeviceSize = Mux(singleDeviceGroupFromLoad,
    deviceLoadEffect.bits.accessSize, lsuIngress.io.storeEffect.bits.accessSize)
  val selectedDevicePma = Mux(singleDeviceGroupFromLoad,
    deviceLoadEffect.bits.pmaKind, lsuIngress.io.storeEffect.bits.pmaKind)
  deviceRequest.order := selectedDeviceTag
  deviceRequest.robTag := selectedDeviceTag
  deviceRequest.address := selectedDeviceAddress
  deviceRequest.write := singleDeviceGroupFromStore
  deviceRequest.size := selectedDeviceSize
  deviceRequest.writeData := Mux(singleDeviceGroupFromStore,
    lsuIngress.io.storeEffect.bits.writeData, 0.U)
  deviceRequest.writeMask := Mux(singleDeviceGroupFromStore,
    lsuIngress.io.storeEffect.bits.writeMask, 0.U)
  deviceRequest.burstable := false.B
  deviceRequest.regionTag := selectedDevicePma

  val orderedCollectionCancel = backend.io.globalFlush || backend.io.squash.valid
  orderedIOStreamer.io.group <> lsuIngress.io.burstableDeviceGroup
  orderedIOStreamer.io.cancel := orderedCollectionCancel
  orderedIOCombiner.io.in <> orderedIOStreamer.io.request
  orderedIOCombiner.io.forceFlush := orderedIOStreamer.io.forceFlush
  orderedIOCombiner.io.cancel := orderedCollectionCancel
  orderedIOStreamer.io.accepted := orderedIOCombiner.io.out.fire
  val orderedGroupArbiter = Module(new Arbiter(new OrderedIOGroup(config = cfg), 2))
  orderedGroupArbiter.io.in(0).valid := singleDeviceGroupValid
  orderedGroupArbiter.io.in(0).bits := singleDeviceGroup
  orderedGroupArbiter.io.in(1) <> orderedIOCombiner.io.out
  orderedIOEngine.io.group.valid := orderedGroupArbiter.io.out.valid &&
    !l1dLoadCache.io.storeBusy
  orderedIOEngine.io.group.bits := orderedGroupArbiter.io.out.bits
  orderedGroupArbiter.io.out.ready := orderedIOEngine.io.group.ready &&
    !l1dLoadCache.io.storeBusy
  lsuIngress.io.burstableDeviceGroupAccepted.valid := orderedIOCombiner.io.out.fire
  lsuIngress.io.burstableDeviceGroupAccepted.bits := orderedIOCombiner.io.out.bits
  deviceLoadEffect.ready := singleDeviceGroupFromLoad &&
    orderedGroupArbiter.io.in(0).ready && !l1dLoadCache.io.storeBusy

  l1dLoadCache.io.storeRequest.valid := cacheStoreEffect && !orderedIOEngine.io.busy &&
    !externalCoherence.io.cacheableIngressBlocked
  l1dLoadCache.io.storeRequest.bits := lsuIngress.io.storeEffect.bits
  lsuIngress.io.storeEffect.ready := Mux(cacheStoreEffect,
    l1dLoadCache.io.storeRequest.ready && !orderedIOEngine.io.busy,
    Mux(deviceStoreEffect,
      !singleDeviceGroupFromLoad && orderedGroupArbiter.io.in(0).ready &&
        !l1dLoadCache.io.storeBusy,
      false.B))
  // Cacheable stores already own their exclusive L1D line. Only an external
  // atomic invalidates a clean L2 copy before its AXI read/modify/write.
  l2TransferStore.io.invalidate.valid := atomicEngine.io.effect.fire &&
    atomicEngine.io.externalWriteRequired
  // L2 ownership is line-granular even though an AMO's AXI and retire address
  // remain its naturally aligned word address.
  l2TransferStore.io.invalidate.bits := lsuIngress.io.atomicEffect.bits.address &
    "hffffffe0".U

  val deviceLoadCompletion = Wire(Decoupled(new LoadCompletion(cfg)))
  val deviceStoreResult = Wire(Decoupled(new StoreWriteResult(cfg)))
  deviceLoadCompletion.valid := orderedIOEngine.io.response.valid &&
    !orderedIOEngine.io.response.bits.write
  deviceLoadCompletion.bits.robTag := orderedIOEngine.io.response.bits.robTag
  deviceLoadCompletion.bits.cacheData := orderedIOEngine.io.response.bits.readData
  deviceLoadCompletion.bits.accessFault := orderedIOEngine.io.response.bits.accessFault
  deviceLoadCompletion.bits.faultAddress := orderedIOEngine.io.response.bits.address
  deviceStoreResult.valid := orderedIOEngine.io.response.valid &&
    orderedIOEngine.io.response.bits.write
  deviceStoreResult.bits.robTag := orderedIOEngine.io.response.bits.robTag
  deviceStoreResult.bits.address := orderedIOEngine.io.response.bits.address
  deviceStoreResult.bits.accessFault := orderedIOEngine.io.response.bits.accessFault
  orderedIOEngine.io.response.ready := Mux(orderedIOEngine.io.response.bits.write,
    deviceStoreResult.ready, deviceLoadCompletion.ready)

  val loadCompletionArbiter = Module(new Arbiter(new LoadCompletion(cfg), 2))
  loadCompletionArbiter.io.in(0) <> l1dLoadCache.io.completion
  loadCompletionArbiter.io.in(1) <> deviceLoadCompletion
  lsuIngress.io.loadComplete <> loadCompletionArbiter.io.out

  val storeResultArbiter = Module(new Arbiter(new StoreWriteResult(cfg), 2))
  val l1dCleanupArbiter = Module(new Arbiter(UInt(32.W), 2))
  val l2CleanupArbiter = Module(new Arbiter(UInt(32.W), 2))
  l1dCleanupArbiter.io.in(0) <> externalCoherence.io.l1dCleanup
  l2CleanupArbiter.io.in(0) <> externalCoherence.io.l2Cleanup
  l1dLoadCache.io.flushLine <> l1dCleanupArbiter.io.out
  l2TransferStore.io.flushLine <> l2CleanupArbiter.io.out
  externalCoherence.io.l2CleanupDirty := l2TransferStore.io.flushLineDirty
  if (cfg.enableHostFlush) {
    val hostStoreFlush = Module(new HostStoreFlush(cfg))
    val hostControl = io.hostFlush.get
    hostStoreFlush.io.enabled := hostControl.enable
    hostStoreFlush.io.address := hostControl.address
    hostStoreFlush.io.l1dFlush <> l1dCleanupArbiter.io.in(1)
    hostStoreFlush.io.l2Flush <> l2CleanupArbiter.io.in(1)
    hostStoreFlush.io.writebackComplete := l2WritebackEngine.io.completed
    hostStoreFlush.io.input <> l1dLoadCache.io.storeResult
    storeResultArbiter.io.in(0) <> hostStoreFlush.io.output
  } else {
    l1dCleanupArbiter.io.in(1).valid := false.B
    l1dCleanupArbiter.io.in(1).bits := 0.U
    l2CleanupArbiter.io.in(1).valid := false.B
    l2CleanupArbiter.io.in(1).bits := 0.U
    storeResultArbiter.io.in(0) <> l1dLoadCache.io.storeResult
  }
  storeResultArbiter.io.in(1) <> deviceStoreResult
  lsuIngress.io.loadContextRead.valid := false.B
  lsuIngress.io.loadContextRead.bits := 0.U
  val robHeadIsStore = backend.io.robHead.valid &&
    backend.io.robHead.bits.entry.decoded.uopClass === UopClass.Store
  // A store becomes externally visible only when its true ROB head owns an SQ
  // record. It remains incomplete until the exact B response reaches M0.
  lsuIngress.io.commitAuthorize.valid := robHeadIsStore
  lsuIngress.io.commitAuthorize.bits := backend.io.robHead.bits.robTag
  lsuIngress.io.storeWriteResult <> storeResultArbiter.io.out
  lsuIngress.io.storeEffectComplete.valid := storeResultArbiter.io.out.fire
  lsuIngress.io.storeEffectComplete.bits.robTag := storeResultArbiter.io.out.bits.robTag
  lsuIngress.io.storeEffectComplete.bits.accessFault :=
    storeResultArbiter.io.out.bits.accessFault
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
  backend.io.interruptBlocked := lsuIngress.io.storeCommitInFlight ||
    lsuIngress.io.deviceLoadInFlight || lsuIngress.io.atomicInFlight ||
    atomicEngine.io.busy
  // FENCE/FENCE.I execute only at the ROB head. The LSQ exact-age query first
  // drains older owners; the cache controller then writes every older dirty
  // L1D/L2 line through ID-5 before commit may retire the serializing uop.
  val headFence = backend.io.robHead.valid &&
    (backend.io.robHead.bits.entry.decoded.operation === IntOperation.Fence ||
      backend.io.robHead.bits.entry.decoded.operation === IntOperation.FenceI)
  lsuIngress.io.orderingBarrier.valid := headFence
  lsuIngress.io.orderingBarrier.bits := backend.io.robHead.bits.robTag
  cacheFenceDrain.io.request := headFence && lsuIngress.io.orderingReady
  backend.io.systemSerializingReady := !headFence ||
    (lsuIngress.io.orderingReady && cacheFenceDrain.io.complete)
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

  // AXI4 omits WID, so W beats must stay in AW acceptance order. Lock the W
  // channel from an AW handshake through its final beat; responses still route
  // independently by ID and may return in another legal order.
  val writeLockValid = RegInit(false.B)
  val writeLockOwner = RegInit(0.U(2.W)) // 0 L2 writeback, 1 device, 2 atomic.
  val writebackAwSelected = !orderedIOEngine.io.aw.valid && !atomicEngine.io.aw.valid &&
    l2WritebackEngine.io.aw.valid
  val orderedAwSelected = orderedIOEngine.io.aw.valid
  val atomicAwSelected = !orderedAwSelected && atomicEngine.io.aw.valid
  val selectedAwOwner = Mux(orderedAwSelected, 1.U,
    Mux(atomicAwSelected, 2.U, 0.U))
  io.axi.aw.valid := !writeLockValid && (orderedAwSelected || atomicAwSelected ||
    writebackAwSelected)
  io.axi.aw.bits := Mux(orderedAwSelected, orderedIOEngine.io.aw.bits,
    Mux(atomicAwSelected, atomicEngine.io.aw.bits, l2WritebackEngine.io.aw.bits))
  orderedIOEngine.io.aw.ready := io.axi.aw.ready && !writeLockValid && orderedAwSelected
  atomicEngine.io.aw.ready := io.axi.aw.ready && !writeLockValid && atomicAwSelected
  l2WritebackEngine.io.aw.ready := io.axi.aw.ready && !writeLockValid && writebackAwSelected
  when(io.axi.aw.fire) {
    writeLockValid := true.B
    writeLockOwner := selectedAwOwner
  }

  val writebackWSelected = writeLockValid && writeLockOwner === 0.U
  val orderedWSelected = writeLockValid && writeLockOwner === 1.U
  val atomicWSelected = writeLockValid && writeLockOwner === 2.U
  io.axi.w.valid := writeLockValid && Mux(writebackWSelected, l2WritebackEngine.io.w.valid,
    Mux(orderedWSelected, orderedIOEngine.io.w.valid, atomicEngine.io.w.valid))
  io.axi.w.bits := Mux(writebackWSelected, l2WritebackEngine.io.w.bits,
    Mux(orderedWSelected, orderedIOEngine.io.w.bits, atomicEngine.io.w.bits))
  l2WritebackEngine.io.w.ready := io.axi.w.ready && writebackWSelected
  orderedIOEngine.io.w.ready := io.axi.w.ready && orderedWSelected
  atomicEngine.io.w.ready := io.axi.w.ready && atomicWSelected
  when(io.axi.w.fire && io.axi.w.bits.last) {
    writeLockValid := false.B
  }

  val bToWriteback = io.axi.b.bits.id === 5.U
  val bToOrderedIO = io.axi.b.bits.id === 6.U
  val bToAtomic = io.axi.b.bits.id === 7.U
  l2WritebackEngine.io.b.valid := io.axi.b.valid && bToWriteback
  l2WritebackEngine.io.b.bits := io.axi.b.bits
  orderedIOEngine.io.b.valid := io.axi.b.valid && bToOrderedIO
  orderedIOEngine.io.b.bits := io.axi.b.bits
  atomicEngine.io.b.valid := io.axi.b.valid && bToAtomic
  atomicEngine.io.b.bits := io.axi.b.bits
  io.axi.b.ready := Mux(bToWriteback, l2WritebackEngine.io.b.ready,
    Mux(bToOrderedIO, orderedIOEngine.io.b.ready,
      Mux(bToAtomic, atomicEngine.io.b.ready, false.B)))
  when(io.axi.b.valid) {
    assert(bToWriteback || bToOrderedIO || bToAtomic,
      "top-level AXI B used an ID outside writeback, ordered-device, and atomic owners")
  }

  val arLockValid = RegInit(false.B)
  val arLockOwner = RegInit(0.U(2.W))
  val arTurn = RegInit(0.U(2.W)) // 0 shared L2 demand, 1 ordered device, 2 atomic.
  val arClientValid = Wire(Vec(4, Bool()))
  arClientValid(0) := l2DemandEngine.io.ar.valid
  arClientValid(1) := orderedIOEngine.io.ar.valid
  arClientValid(2) := atomicEngine.io.ar.valid
  arClientValid(3) := false.B
  val arTurnNext = Mux(arTurn === 3.U, 0.U(2.W), arTurn + 1.U)
  val arTurnAfterNext = Mux(arTurnNext === 3.U, 0.U(2.W), arTurnNext + 1.U)
  val arTurnAfterAfterNext = Mux(arTurnAfterNext === 3.U, 0.U(2.W),
    arTurnAfterNext + 1.U)
  val unlockedArOwner = Mux(arClientValid(arTurn), arTurn,
    Mux(arClientValid(arTurnNext), arTurnNext,
      Mux(arClientValid(arTurnAfterNext), arTurnAfterNext, arTurnAfterAfterNext)))
  val selectedArOwner = Mux(arLockValid, arLockOwner, unlockedArOwner)
  io.axi.ar.valid := Mux(selectedArOwner === 0.U, l2DemandEngine.io.ar.valid,
    Mux(selectedArOwner === 1.U, orderedIOEngine.io.ar.valid,
      Mux(selectedArOwner === 2.U, atomicEngine.io.ar.valid, false.B)))
  io.axi.ar.bits := Mux(selectedArOwner === 0.U, l2DemandEngine.io.ar.bits,
    Mux(selectedArOwner === 1.U, orderedIOEngine.io.ar.bits,
      atomicEngine.io.ar.bits))
  l2DemandEngine.io.ar.ready := io.axi.ar.ready && selectedArOwner === 0.U
  orderedIOEngine.io.ar.ready := io.axi.ar.ready && selectedArOwner === 1.U
  atomicEngine.io.ar.ready := io.axi.ar.ready && selectedArOwner === 2.U
  when(!arLockValid && io.axi.ar.valid && !io.axi.ar.ready) {
    arLockValid := true.B
    arLockOwner := selectedArOwner
  }
  when(io.axi.ar.fire) {
    arLockValid := false.B
    arTurn := Mux(selectedArOwner === 3.U, 0.U(2.W), selectedArOwner + 1.U)
  }

  val rToData = io.axi.r.bits.id >= 1.U && io.axi.r.bits.id <= 4.U
  val rToOrderedIO = io.axi.r.bits.id === 6.U
  val rToAtomic = io.axi.r.bits.id === 7.U
  l2DemandEngine.io.r.valid := io.axi.r.valid && rToData
  l2DemandEngine.io.r.bits := io.axi.r.bits
  orderedIOEngine.io.r.valid := io.axi.r.valid && rToOrderedIO
  orderedIOEngine.io.r.bits := io.axi.r.bits
  atomicEngine.io.r.valid := io.axi.r.valid && rToAtomic
  atomicEngine.io.r.bits := io.axi.r.bits
  io.axi.r.ready := Mux(rToData, l2DemandEngine.io.r.ready,
    Mux(rToOrderedIO, orderedIOEngine.io.r.ready,
      Mux(rToAtomic, atomicEngine.io.r.ready, false.B)))
  when(io.axi.r.valid) {
    assert(rToData || rToOrderedIO || rToAtomic,
      "top-level AXI R used an ID outside L2 demand, ordered-device, and atomic owners")
  }

  io.trace.foreach { trace =>
    val formatter = Module(new RetireTraceFormatter(cfg))
    formatter.io.retired := backend.io.retired
    formatter.io.memoryMetadata := lsuIngress.io.retireMetadata
    formatter.io.gprData := backend.io.auxReadData
    formatter.io.fprWrite := 0.U.asTypeOf(Valid(new zircon.backend.FloatingRegisterWrite(cfg)))
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
    observation.loadForwardValid := VecInit(lsuIngress.io.loadForward.map(_.valid))
    observation.l1dRequest := VecInit(l1dLoadCache.io.request.map(_.fire))
    observation.l1dRequestTag := VecInit(l1dLoadCache.io.request.map(_.bits.robTag))
    observation.robHeadTag := backend.io.robHead.bits.robTag
    observation.loadQueueCount := lsuIngress.io.loadCount
    observation.storeQueueCount := lsuIngress.io.storeCount
    observation.orderedGroupValid := lsuIngress.io.burstableDeviceGroup.valid
    observation.orderedGroupCount := lsuIngress.io.burstableDeviceGroup.bits.count
    observation.l2VictimCount := l2TransferStore.io.victimCount
    observation.l2WritebackBusy := l2WritebackEngine.io.busy
  }
}
