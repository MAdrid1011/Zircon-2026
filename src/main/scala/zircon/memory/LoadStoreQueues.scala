package zircon.memory

import chisel3._
import chisel3.util._
import zircon.{PMARegionKind, ZirconCoreConfig}
import zircon.backend.ROBTagOrder
import zircon.frontend.IntOperation

/** Eight-entry load and store queues with byte-precise forwarding.
  *
  * Allocation creates only local speculative state. In particular, an SQ
  * entry cannot produce `storeEffect` until its exact ROB tag has received
  * `commitAuthorize`; the effect must then complete before retirement can
  * release the record. This keeps cache/MMIO actions outside recovery.
  */
class LoadStoreQueues(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val lqEntries = config.loadQueueEntries
  private val sqEntries = config.storeQueueEntries
  private val lqIndexWidth = log2Ceil(lqEntries)
  private val sqIndexWidth = log2Ceil(sqEntries)
  private val lqCountWidth = log2Ceil(lqEntries + 1)
  private val sqCountWidth = log2Ceil(sqEntries + 1)
  // Covers the two-batch M0/M1 replay path before a head device group is sealed.
  private val burstableGroupCollectionCycles = 6

  require(lqEntries == 8 && sqEntries == 8,
    "the frozen M3 contract requires eight LQ and SQ entries")

  val io = IO(new Bundle {
    val allocate = Flipped(Vec(config.decodeWidth,
      Decoupled(new MemoryQueueAllocate(config))))
    val storeAddress = Flipped(Decoupled(new StoreAddressUpdate(config)))
    val storeData = Flipped(Decoupled(new StoreDataUpdate(config)))
    val loadAddress = Flipped(Vec(config.decodeWidth,
      Decoupled(new LoadAddressQuery(config))))
    val loadForward = Vec(config.decodeWidth,
      Decoupled(new LoadStoreForward(config)))
    val loadComplete = Flipped(Decoupled(new LoadCompletion(config)))
    val loadResult = Decoupled(new MemoryLoadResult(config))
    val loadFault = Decoupled(new LoadAccessFault(config))
    val loadContextRead = Input(Valid(UInt(config.robTagWidth.W)))
    val loadContext = Output(Valid(new LoadQueueContext(config)))

    val commitAuthorize = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val storeEffect = Decoupled(new StoreEffect(config))
    val storeEffectComplete = Input(Valid(new StoreEffectComplete(config)))
    val storeCommitInFlight = Output(Bool())
    val atomicEffect = Decoupled(new AtomicMemoryEffect(config))
    val atomicComplete = Flipped(Decoupled(new AtomicMemoryResult(config)))
    val atomicResult = Decoupled(new AtomicMemoryResult(config))
    /** Retains an accepted AMO/LR/SC through architectural retirement so an
      * interrupt cannot replay an irreversible bus operation. */
    val atomicInFlight = Output(Bool())
    /** Earliest live aq atomic. Core-level MemIQ gating admits only older
      * memory work until this exact record completes. */
    val atomicAcquireBarrier = Output(Valid(UInt(config.robTagWidth.W)))
    val deviceLoadEffect = Decoupled(new OrderedLoadEffect(config))
    val deviceLoadInFlight = Output(Bool())
    val burstableDeviceGroup = Decoupled(new OrderedIOGroup(config = config))
    val burstableDeviceGroupAccepted = Input(Valid(new OrderedIOGroup(config = config)))

    /** A FENCE/FENCE.I at the live ROB head asks whether every older local
      * memory owner has drained. The tag, rather than total occupancy, keeps
      * speculative younger work from deadlocking the serializing instruction.
      */
    val orderingBarrier = Input(Valid(UInt(config.robTagWidth.W)))
    val orderingReady = Output(Bool())

    val retire = Input(Vec(config.commitWidth,
      Valid(UInt(config.robTagWidth.W))))
    val retireMetadata = Output(Vec(config.commitWidth,
      Valid(new MemoryRetireMetadata(config))))

    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())

    val loadCount = Output(UInt(lqCountWidth.W))
    val storeCount = Output(UInt(sqCountWidth.W))
    val loadCapacity = Output(UInt(2.W))
    val storeCapacity = Output(UInt(2.W))
  })

  val lqValid = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqTag = Reg(Vec(lqEntries, UInt(config.robTagWidth.W)))
  val lqAccessSize = Reg(Vec(lqEntries, UInt(2.W)))
  val lqUnsignedLoad = Reg(Vec(lqEntries, Bool()))
  val lqDestinationPhysical = Reg(Vec(lqEntries,
    UInt(log2Ceil(config.intPhysicalRegisters).W)))
  val lqWritesInteger = Reg(Vec(lqEntries, Bool()))
  val lqFloatingDestination = Reg(Vec(lqEntries, UInt(5.W)))
  val lqWritesFloat = Reg(Vec(lqEntries, Bool()))
  val lqM1Owner = Reg(Vec(lqEntries, Bool()))
  val lqIsAtomic = Reg(Vec(lqEntries, Bool()))
  val lqAtomicOperation = Reg(Vec(lqEntries, UInt(7.W)))
  val lqPmaKind = Reg(Vec(lqEntries, UInt(2.W)))
  val lqAq = Reg(Vec(lqEntries, Bool()))
  val lqRl = Reg(Vec(lqEntries, Bool()))
  val lqAddressValid = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqAddress = Reg(Vec(lqEntries, UInt(32.W)))
  val lqReadMask = Reg(Vec(lqEntries, UInt(4.W)))
  val lqForwardMask = Reg(Vec(lqEntries, UInt(4.W)))
  val lqForwardData = Reg(Vec(lqEntries, UInt(32.W)))
  val lqCompleted = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqEffectIssued = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqMetadataValid = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqMetadata = Reg(Vec(lqEntries, new MemoryRetireMetadata(config)))

  val sqValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqTag = Reg(Vec(sqEntries, UInt(config.robTagWidth.W)))
  val sqAddressValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqAddress = Reg(Vec(sqEntries, UInt(32.W)))
  val sqAccessSize = Reg(Vec(sqEntries, UInt(2.W)))
  val sqWriteMask = Reg(Vec(sqEntries, UInt(4.W)))
  val sqDataValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqWriteData = Reg(Vec(sqEntries, UInt(32.W)))
  val sqIsAtomic = Reg(Vec(sqEntries, Bool()))
  val sqAtomicOperation = Reg(Vec(sqEntries, UInt(7.W)))
  val sqDestinationPhysical = Reg(Vec(sqEntries,
    UInt(log2Ceil(config.intPhysicalRegisters).W)))
  val sqWritesInteger = Reg(Vec(sqEntries, Bool()))
  val sqWritesFloat = Reg(Vec(sqEntries, Bool()))
  val sqPmaKind = Reg(Vec(sqEntries, UInt(2.W)))
  val sqAq = Reg(Vec(sqEntries, Bool()))
  val sqRl = Reg(Vec(sqEntries, Bool()))
  val sqCommitAuthorized = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqEffectIssued = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqEffectComplete = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqEffectFault = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqMetadataValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqMetadata = Reg(Vec(sqEntries, new MemoryRetireMetadata(config)))

  // ROB distance is shared by forwarding, effect arbitration, recovery, and
  // ordering checks.  Materializing it once per queue entry avoids rebuilding
  // the same modulo-age arithmetic in every consumer.
  val lqAge = VecInit(lqTag.map(tag =>
    ROBTagOrder.ageFromHead(tag, io.robHeadTag, config)))
  val sqAge = VecInit(sqTag.map(tag =>
    ROBTagOrder.ageFromHead(tag, io.robHeadTag, config)))

  val burstableGroupWaitValid = RegInit(false.B)
  val burstableGroupWaitHead = Reg(UInt(config.robTagWidth.W))
  val burstableGroupWaitCycles = RegInit(0.U(3.W))
  val atomicResultValid = RegInit(false.B)
  val atomicResultBits = Reg(new AtomicMemoryResult(config))

  private def findMatch(
      valid: Vec[Bool],
      tags: Vec[UInt],
      tag: UInt,
      entries: Int,
      indexWidth: Int
  ): (Bool, UInt) = {
    val matches = VecInit((0 until entries).map(index =>
      valid(index) && tags(index) === tag))
    (matches.asUInt.orR, PriorityEncoder(matches.asUInt)(indexWidth - 1, 0))
  }

  /** Select the lowest ROB-age candidate with a balanced reduction tree. */
  private def selectOldest(
      candidates: Seq[Bool],
      ages: Seq[UInt],
      indexWidth: Int
  ): (Bool, UInt) = {
    require(candidates.nonEmpty && candidates.length == ages.length)
    var valid = candidates.toVector
    var indices = candidates.indices.map(_.U(indexWidth.W)).toVector
    var ageTree = ages.toVector
    while (valid.length > 1) {
      val nextValid = Vector.newBuilder[Bool]
      val nextIndices = Vector.newBuilder[UInt]
      val nextAges = Vector.newBuilder[UInt]
      var pair = 0
      while (pair < valid.length) {
        if (pair + 1 == valid.length) {
          nextValid += valid(pair)
          nextIndices += indices(pair)
          nextAges += ageTree(pair)
        } else {
          val rightWins = valid(pair + 1) &&
            (!valid(pair) || ageTree(pair + 1) < ageTree(pair))
          nextValid += (valid(pair) || valid(pair + 1))
          nextIndices += Mux(rightWins, indices(pair + 1), indices(pair))
          nextAges += Mux(rightWins, ageTree(pair + 1), ageTree(pair))
        }
        pair += 2
      }
      valid = nextValid.result()
      indices = nextIndices.result()
      ageTree = nextAges.result()
    }
    (valid.head, indices.head)
  }

  private def capacity(free: UInt, entries: Int): UInt =
    Mux(PopCount(free) >= config.decodeWidth.U, config.decodeWidth.U,
      PopCount(free)(1, 0))

  val recoveryBlocked = io.flush || io.squash.valid
  val lqFree0 = (~lqValid.asUInt)(lqEntries - 1, 0)
  val sqFree0 = (~sqValid.asUInt)(sqEntries - 1, 0)
  io.loadCapacity := Mux(recoveryBlocked, 0.U, capacity(lqFree0, lqEntries))
  io.storeCapacity := Mux(recoveryBlocked, 0.U, capacity(sqFree0, sqEntries))

  val allocateLoad = VecInit(io.allocate.map(port =>
    port.valid && port.bits.allocateLoad))
  val allocateStore = VecInit(io.allocate.map(port =>
    port.valid && port.bits.allocateStore))
  val allocationFits = PopCount(lqFree0) >= PopCount(allocateLoad) &&
    PopCount(sqFree0) >= PopCount(allocateStore)
  io.allocate.foreach(_.ready := !recoveryBlocked && allocationFits)

  val lqAllocation0OH = PriorityEncoderOH(lqFree0)
  val lqFree1 = lqFree0 & ~Mux(io.allocate(0).fire && allocateLoad(0),
    lqAllocation0OH, 0.U(lqEntries.W))
  val lqAllocation1OH = PriorityEncoderOH(lqFree1)
  val lqAllocation = VecInit(OHToUInt(lqAllocation0OH), OHToUInt(lqAllocation1OH))

  val sqAllocation0OH = PriorityEncoderOH(sqFree0)
  val sqFree1 = sqFree0 & ~Mux(io.allocate(0).fire && allocateStore(0),
    sqAllocation0OH, 0.U(sqEntries.W))
  val sqAllocation1OH = PriorityEncoderOH(sqFree1)
  val sqAllocation = VecInit(OHToUInt(sqAllocation0OH), OHToUInt(sqAllocation1OH))

  val (storeAddressMatch, storeAddressIndex) = findMatch(
    sqValid, sqTag, io.storeAddress.bits.robTag, sqEntries, sqIndexWidth)
  io.storeAddress.ready := !recoveryBlocked && storeAddressMatch

  val (storeDataMatch, storeDataIndex) = findMatch(
    sqValid, sqTag, io.storeData.bits.robTag, sqEntries, sqIndexWidth)
  io.storeData.ready := !recoveryBlocked && storeDataMatch

  val loadAddressMatch = Wire(Vec(config.decodeWidth, Bool()))
  val loadAddressIndex = Wire(Vec(config.decodeWidth, UInt(lqIndexWidth.W)))
  val requestedForwardMask = Wire(Vec(config.decodeWidth, UInt(4.W)))
  val forwardData = Wire(Vec(config.decodeWidth, UInt(32.W)))
  for (request <- 0 until config.decodeWidth) {
    val (addressMatch, addressIndex) = findMatch(
      lqValid, lqTag, io.loadAddress(request).bits.robTag, lqEntries, lqIndexWidth)
    loadAddressMatch(request) := addressMatch
    loadAddressIndex(request) := addressIndex
    val queryAge = ROBTagOrder.ageFromHead(
      io.loadAddress(request).bits.robTag, io.robHeadTag, config)
    val queryWordAddress = MemoryByteLanes.wordAddress(io.loadAddress(request).bits.address)
    val olderUnknownAddress = (0 until sqEntries).map(index =>
      sqValid(index) && queryAge > sqAge(index) &&
        !sqAddressValid(index)).reduce(_ || _)
    val olderUnknownData = (0 until sqEntries).map(index =>
      sqValid(index) && sqAddressValid(index) &&
        queryAge > sqAge(index) &&
        MemoryByteLanes.wordAddress(sqAddress(index)) === queryWordAddress &&
        (sqWriteMask(index) & io.loadAddress(request).bits.readMask).orR &&
        !sqDataValid(index)).reduce(_ || _)

    val forwardMaskBits = Wire(Vec(4, Bool()))
    val forwardBytes = Wire(Vec(4, UInt(8.W)))
    for (byte <- 0 until 4) {
      var chosenValid: Bool = false.B
      var chosenTag: UInt = 0.U(config.robTagWidth.W)
      var chosenAge: UInt = 0.U((config.robIndexWidth + 1).W)
      var chosenData: UInt = 0.U(8.W)
      for (index <- 0 until sqEntries) {
        val candidate = sqValid(index) && sqAddressValid(index) && sqDataValid(index) &&
          queryAge > sqAge(index) &&
          MemoryByteLanes.wordAddress(sqAddress(index)) === queryWordAddress &&
          sqWriteMask(index)(byte)
        val take = candidate && (!chosenValid || sqAge(index) > chosenAge)
        chosenTag = Mux(take, sqTag(index), chosenTag)
        chosenAge = Mux(take, sqAge(index), chosenAge)
        chosenData = Mux(take, sqWriteData(index)(8 * byte + 7, 8 * byte), chosenData)
        chosenValid = chosenValid || candidate
      }
      forwardMaskBits(byte) := chosenValid
      forwardBytes(byte) := chosenData
    }
    requestedForwardMask(request) := forwardMaskBits.asUInt & io.loadAddress(request).bits.readMask
    forwardData(request) := forwardBytes.asUInt
    val loadMayProceed = addressMatch && !olderUnknownAddress && !olderUnknownData
    io.loadForward(request).valid := io.loadAddress(request).valid &&
      !recoveryBlocked && loadMayProceed
    io.loadForward(request).bits.robTag := io.loadAddress(request).bits.robTag
    io.loadForward(request).bits.address := io.loadAddress(request).bits.address
    io.loadForward(request).bits.readMask := io.loadAddress(request).bits.readMask
    io.loadForward(request).bits.forwardMask := requestedForwardMask(request)
    io.loadForward(request).bits.forwardData := forwardData(request)
    io.loadForward(request).bits.requiresCache := requestedForwardMask(request) =/=
      io.loadAddress(request).bits.readMask
    io.loadForward(request).bits.cacheable := lqPmaKind(addressIndex) ===
      PMARegionKind.Memory.code.U && !lqIsAtomic(addressIndex)
    io.loadAddress(request).ready := io.loadForward(request).ready &&
      !recoveryBlocked && loadMayProceed
  }

  val (loadCompleteMatch, loadCompleteIndex) = findMatch(
    lqValid, lqTag, io.loadComplete.bits.robTag, lqEntries, lqIndexWidth)
  val loadCompleteEligible = loadCompleteMatch && lqAddressValid(loadCompleteIndex) &&
    !lqCompleted(loadCompleteIndex) && !recoveryBlocked
  val completedLoadData = MemoryByteLanes.merge(
    io.loadComplete.bits.cacheData,
    lqForwardData(loadCompleteIndex),
    lqForwardMask(loadCompleteIndex))
  io.loadResult.valid := io.loadComplete.valid && loadCompleteEligible &&
    !io.loadComplete.bits.accessFault
  io.loadResult.bits.robTag := lqTag(loadCompleteIndex)
  io.loadResult.bits.destinationPhysical := lqDestinationPhysical(loadCompleteIndex)
  io.loadResult.bits.writesInteger := lqWritesInteger(loadCompleteIndex)
  io.loadResult.bits.floatingDestination := lqFloatingDestination(loadCompleteIndex)
  io.loadResult.bits.writesFloat := lqWritesFloat(loadCompleteIndex)
  io.loadResult.bits.m1Owner := lqM1Owner(loadCompleteIndex)
  io.loadResult.bits.accessSize := lqAccessSize(loadCompleteIndex)
  io.loadResult.bits.unsignedLoad := lqUnsignedLoad(loadCompleteIndex)
  io.loadResult.bits.data := completedLoadData
  io.loadFault.valid := io.loadComplete.valid && loadCompleteEligible &&
    io.loadComplete.bits.accessFault
  io.loadFault.bits.robTag := lqTag(loadCompleteIndex)
  io.loadFault.bits.m1Owner := lqM1Owner(loadCompleteIndex)
  io.loadFault.bits.trapValue := io.loadComplete.bits.faultAddress
  io.loadComplete.ready := loadCompleteEligible && Mux(io.loadComplete.bits.accessFault,
    io.loadFault.ready, io.loadResult.ready)
  val (loadContextMatch, loadContextIndex) = findMatch(
    lqValid, lqTag, io.loadContextRead.bits, lqEntries, lqIndexWidth)
  io.loadContext.valid := io.loadContextRead.valid && loadContextMatch && !recoveryBlocked
  io.loadContext.bits.robTag := lqTag(loadContextIndex)
  io.loadContext.bits.accessSize := lqAccessSize(loadContextIndex)
  io.loadContext.bits.unsignedLoad := lqUnsignedLoad(loadContextIndex)
  io.loadContext.bits.destinationPhysical := lqDestinationPhysical(loadContextIndex)
  io.loadContext.bits.writesInteger := lqWritesInteger(loadContextIndex)
  io.loadContext.bits.floatingDestination := lqFloatingDestination(loadContextIndex)
  io.loadContext.bits.writesFloat := lqWritesFloat(loadContextIndex)
  io.loadContext.bits.m1Owner := lqM1Owner(loadContextIndex)
  io.loadContext.bits.isAtomic := lqIsAtomic(loadContextIndex)
  io.loadContext.bits.atomicOperation := lqAtomicOperation(loadContextIndex)
  io.loadContext.bits.aq := lqAq(loadContextIndex)
  io.loadContext.bits.rl := lqRl(loadContextIndex)

  private def isDevicePma(kind: UInt): Bool = kind === PMARegionKind.DeviceStrong.code.U ||
    kind === PMARegionKind.DeviceBurstable.code.U

  private def isLr(operation: UInt): Bool = operation === IntOperation.LrW.asUInt
  private def isSc(operation: UInt): Bool = operation === IntOperation.ScW.asUInt
  private def isAmo(operation: UInt): Bool = operation === IntOperation.AmoSwapW.asUInt ||
    operation === IntOperation.AmoAddW.asUInt || operation === IntOperation.AmoXorW.asUInt ||
    operation === IntOperation.AmoAndW.asUInt || operation === IntOperation.AmoOrW.asUInt ||
    operation === IntOperation.AmoMinW.asUInt || operation === IntOperation.AmoMaxW.asUInt ||
    operation === IntOperation.AmoMinuW.asUInt || operation === IntOperation.AmoMaxuW.asUInt

  private def advanceRobTag(tag: UInt, distance: Int): UInt = {
    var advanced = tag
    for (_ <- 0 until distance) {
      val index = advanced(config.robIndexWidth - 1, 0)
      val generation = advanced(config.robTagWidth - 1)
      val wraps = index === (config.robEntries - 1).U
      advanced = Cat(Mux(wraps, !generation, generation),
        Mux(wraps, 0.U(config.robIndexWidth.W), index + 1.U))
    }
    advanced
  }

  val (commitMatch, commitIndex) = findMatch(
    sqValid, sqTag, io.commitAuthorize.bits, sqEntries, sqIndexWidth)
  val commitEligible = commitMatch && sqAddressValid(commitIndex) &&
    sqDataValid(commitIndex) && !sqCommitAuthorized(commitIndex) &&
    !sqIsAtomic(commitIndex) &&
    (sqPmaKind(commitIndex) === PMARegionKind.Memory.code.U ||
      isDevicePma(sqPmaKind(commitIndex)))
  io.commitAuthorize.ready := !recoveryBlocked && commitEligible

  val storeCandidates = (0 until sqEntries).map { index =>
    sqValid(index) && sqCommitAuthorized(index) &&
      !sqEffectIssued(index) &&
      sqPmaKind(index) =/= PMARegionKind.DeviceBurstable.code.U
  }
  val (selectedStoreValid, selectedStoreIndex) = selectOldest(
    storeCandidates, sqAge.toSeq, sqIndexWidth)
  io.storeEffect.valid := selectedStoreValid && !recoveryBlocked
  io.storeEffect.bits.robTag := sqTag(selectedStoreIndex)
  io.storeEffect.bits.address := sqAddress(selectedStoreIndex)
  io.storeEffect.bits.accessSize := sqAccessSize(selectedStoreIndex)
  io.storeEffect.bits.writeMask := sqWriteMask(selectedStoreIndex)
  io.storeEffect.bits.writeData := sqWriteData(selectedStoreIndex)
  io.storeEffect.bits.isAtomic := sqIsAtomic(selectedStoreIndex)
  io.storeEffect.bits.pmaKind := sqPmaKind(selectedStoreIndex)
  io.storeEffect.bits.aq := sqAq(selectedStoreIndex)
  io.storeEffect.bits.rl := sqRl(selectedStoreIndex)

  val (headLoadMatch, headLoadIndex) = findMatch(
    lqValid, lqTag, io.robHeadTag, lqEntries, lqIndexWidth)
  val deviceLoadEligible = headLoadMatch && lqAddressValid(headLoadIndex) &&
    !lqM1Owner(headLoadIndex) && !lqIsAtomic(headLoadIndex) &&
    lqPmaKind(headLoadIndex) === PMARegionKind.DeviceStrong.code.U &&
    !lqEffectIssued(headLoadIndex) &&
    !lqCompleted(headLoadIndex)
  io.deviceLoadEffect.valid := deviceLoadEligible && !recoveryBlocked
  io.deviceLoadEffect.bits.robTag := lqTag(headLoadIndex)
  io.deviceLoadEffect.bits.address := lqAddress(headLoadIndex)
  io.deviceLoadEffect.bits.accessSize := lqAccessSize(headLoadIndex)
  io.deviceLoadEffect.bits.pmaKind := lqPmaKind(headLoadIndex)

  val (headStoreMatch, headStoreIndex) = findMatch(
    sqValid, sqTag, io.robHeadTag, sqEntries, sqIndexWidth)

  val headAtomicLoad = headLoadMatch && lqIsAtomic(headLoadIndex) &&
    lqPmaKind(headLoadIndex) === PMARegionKind.Memory.code.U
  val headAtomicStore = headStoreMatch && sqIsAtomic(headStoreIndex) &&
    sqPmaKind(headStoreIndex) === PMARegionKind.Memory.code.U
  val headAtomicOperation = Mux(headAtomicLoad,
    lqAtomicOperation(headLoadIndex), sqAtomicOperation(headStoreIndex))
  val headAtomicLr = isLr(headAtomicOperation)
  val headAtomicSc = isSc(headAtomicOperation)
  val headAtomicAmo = isAmo(headAtomicOperation)
  val atomicLoadReady = headAtomicLoad && lqAddressValid(headLoadIndex) &&
    !lqEffectIssued(headLoadIndex) && !lqCompleted(headLoadIndex)
  val atomicStoreReady = headAtomicStore && sqAddressValid(headStoreIndex) &&
    sqDataValid(headStoreIndex) && !sqEffectIssued(headStoreIndex) &&
    !sqEffectComplete(headStoreIndex)
  val atomicPairReady = atomicLoadReady && atomicStoreReady &&
    lqAddress(headLoadIndex) === sqAddress(headStoreIndex) &&
    lqAtomicOperation(headLoadIndex) === sqAtomicOperation(headStoreIndex)
  val atomicEffectEligible = (headAtomicLr && atomicLoadReady) ||
    (headAtomicSc && atomicStoreReady) || (headAtomicAmo && atomicPairReady)
  val atomicDestinationPhysical = Mux(headAtomicLoad,
    lqDestinationPhysical(headLoadIndex), sqDestinationPhysical(headStoreIndex))
  val atomicWritesInteger = Mux(headAtomicLoad,
    lqWritesInteger(headLoadIndex), sqWritesInteger(headStoreIndex))
  val atomicAddress = Mux(headAtomicLoad, lqAddress(headLoadIndex),
    sqAddress(headStoreIndex))
  val atomicAq = Mux(headAtomicLoad, lqAq(headLoadIndex), sqAq(headStoreIndex))
  val atomicRl = Mux(headAtomicLoad, lqRl(headLoadIndex), sqRl(headStoreIndex))
  io.atomicEffect.valid := atomicEffectEligible && !recoveryBlocked &&
    !atomicResultValid
  io.atomicEffect.bits.robTag := io.robHeadTag
  io.atomicEffect.bits.operation := headAtomicOperation
  io.atomicEffect.bits.address := atomicAddress
  io.atomicEffect.bits.writeData := Mux(headAtomicStore,
    sqWriteData(headStoreIndex), 0.U)
  io.atomicEffect.bits.writeMask := Mux(headAtomicStore,
    sqWriteMask(headStoreIndex), 0.U)
  io.atomicEffect.bits.destinationPhysical := atomicDestinationPhysical
  io.atomicEffect.bits.writesInteger := atomicWritesInteger
  io.atomicEffect.bits.aq := atomicAq
  io.atomicEffect.bits.rl := atomicRl
  when((headAtomicLoad || headAtomicStore) && !recoveryBlocked) {
    assert(headAtomicLr || headAtomicSc || headAtomicAmo,
      "live atomic LSQ owner retained an unsupported RV32A operation")
  }

  val burstableHeadLoad = headLoadMatch && lqAddressValid(headLoadIndex) &&
    !lqM1Owner(headLoadIndex) && !lqIsAtomic(headLoadIndex) &&
    lqPmaKind(headLoadIndex) === PMARegionKind.DeviceBurstable.code.U &&
    !lqEffectIssued(headLoadIndex) && !lqCompleted(headLoadIndex)
  val burstableHeadStore = headStoreMatch && sqAddressValid(headStoreIndex) &&
    sqDataValid(headStoreIndex) && !sqIsAtomic(headStoreIndex) &&
    sqPmaKind(headStoreIndex) === PMARegionKind.DeviceBurstable.code.U &&
    sqCommitAuthorized(headStoreIndex) && !sqEffectIssued(headStoreIndex)
  val burstableGroupLoad = burstableHeadLoad
  val burstableGroupStore = !burstableGroupLoad && burstableHeadStore
  val burstableGroupEligible = burstableGroupLoad || burstableGroupStore
  val burstableGroupWaitMature = burstableGroupWaitValid &&
    burstableGroupWaitHead === io.robHeadTag &&
      burstableGroupWaitCycles === burstableGroupCollectionCycles.U
  val groupAddress = Mux(burstableGroupLoad,
    lqAddress(headLoadIndex), sqAddress(headStoreIndex))
  val groupSize = Mux(burstableGroupLoad,
    lqAccessSize(headLoadIndex), sqAccessSize(headStoreIndex))
  val groupPma = PMARegionKind.DeviceBurstable.code.U
  val groupStride = (1.U(33.W) << groupSize)(31, 0)
  val groupMembers = Wire(Vec(4, Bool()))
  val group = Wire(new OrderedIOGroup(config = config))
  group := 0.U.asTypeOf(group)
  for (member <- 0 until 4) {
    val expectedTag = advanceRobTag(io.robHeadTag, member)
    val (loadMatch, loadIndex) = findMatch(
      lqValid, lqTag, expectedTag, lqEntries, lqIndexWidth)
    val (storeMatch, storeIndex) = findMatch(
      sqValid, sqTag, expectedTag, sqEntries, sqIndexWidth)
    val loadEligible = loadMatch && lqAddressValid(loadIndex) &&
      !lqM1Owner(loadIndex) && !lqIsAtomic(loadIndex) &&
      lqPmaKind(loadIndex) === PMARegionKind.DeviceBurstable.code.U &&
      !lqEffectIssued(loadIndex) && !lqCompleted(loadIndex)
    val storeEligible = storeMatch && sqAddressValid(storeIndex) &&
      sqDataValid(storeIndex) && !sqIsAtomic(storeIndex) &&
      sqPmaKind(storeIndex) === PMARegionKind.DeviceBurstable.code.U &&
      !sqEffectIssued(storeIndex)
    val memberAddress = Mux(burstableGroupLoad,
      lqAddress(loadIndex), sqAddress(storeIndex))
    val memberSize = Mux(burstableGroupLoad,
      lqAccessSize(loadIndex), sqAccessSize(storeIndex))
    val expectedAddress = member match {
      case 0 => groupAddress
      case 1 => groupAddress + groupStride
      case 2 => groupAddress + groupStride + groupStride
      case 3 => groupAddress + groupStride + groupStride + groupStride
    }
    val candidate = Mux(burstableGroupLoad, loadEligible, storeEligible) &&
      memberAddress === expectedAddress && memberSize === groupSize
    groupMembers(member) := (if (member == 0) burstableGroupEligible else
      groupMembers(member - 1) && candidate)

    val request = group.requests(member)
    request.order := ROBTagOrder.ageFromHead(expectedTag, io.robHeadTag, config)
    request.robTag := expectedTag
    request.address := memberAddress
    request.write := burstableGroupStore
    request.size := memberSize
    request.writeData := Mux(burstableGroupStore, sqWriteData(storeIndex), 0.U)
    request.writeMask := Mux(burstableGroupStore, sqWriteMask(storeIndex), 0.U)
    request.burstable := true.B
    request.regionTag := groupPma
  }
  group.count := PopCount(groupMembers)
  io.burstableDeviceGroup.valid := burstableGroupEligible && burstableGroupWaitMature &&
    !recoveryBlocked
  io.burstableDeviceGroup.bits := group

  val (effectCompleteMatch, effectCompleteIndex) = findMatch(
    sqValid, sqTag, io.storeEffectComplete.bits.robTag, sqEntries, sqIndexWidth)

  val (atomicCompleteLoadMatch, atomicCompleteLoadIndex) = findMatch(
    lqValid, lqTag, io.atomicComplete.bits.robTag, lqEntries, lqIndexWidth)
  val (atomicCompleteStoreMatch, atomicCompleteStoreIndex) = findMatch(
    sqValid, sqTag, io.atomicComplete.bits.robTag, sqEntries, sqIndexWidth)
  val atomicCompleteLr = isLr(io.atomicComplete.bits.operation)
  val atomicCompleteSc = isSc(io.atomicComplete.bits.operation)
  val atomicCompleteAmo = isAmo(io.atomicComplete.bits.operation)
  val atomicCompleteNeedsLoad = atomicCompleteLr || atomicCompleteAmo
  val atomicCompleteNeedsStore = atomicCompleteSc || atomicCompleteAmo
  val atomicCompleteLoadEligible = atomicCompleteLoadMatch &&
    lqIsAtomic(atomicCompleteLoadIndex) &&
    lqAtomicOperation(atomicCompleteLoadIndex) === io.atomicComplete.bits.operation &&
    lqEffectIssued(atomicCompleteLoadIndex) && !lqCompleted(atomicCompleteLoadIndex)
  val atomicCompleteStoreEligible = atomicCompleteStoreMatch &&
    sqIsAtomic(atomicCompleteStoreIndex) &&
    sqAtomicOperation(atomicCompleteStoreIndex) === io.atomicComplete.bits.operation &&
    sqEffectIssued(atomicCompleteStoreIndex) && !sqEffectComplete(atomicCompleteStoreIndex)
  val atomicCompleteEligible = !recoveryBlocked && !atomicResultValid &&
    (atomicCompleteNeedsLoad === atomicCompleteLoadEligible) &&
    (atomicCompleteNeedsStore === atomicCompleteStoreEligible) &&
    (atomicCompleteLr || atomicCompleteSc || atomicCompleteAmo)
  io.atomicComplete.ready := atomicCompleteEligible
  io.atomicResult.valid := atomicResultValid && !recoveryBlocked
  io.atomicResult.bits := atomicResultBits

  val lqRetire = VecInit((0 until lqEntries).map(index =>
    lqValid(index) && io.retire.map(port => port.valid &&
      port.bits === lqTag(index)).reduce(_ || _)))
  val sqRetire = VecInit((0 until sqEntries).map(index =>
    sqValid(index) && io.retire.map(port => port.valid &&
      port.bits === sqTag(index)).reduce(_ || _)))
  for (lane <- 0 until config.commitWidth) {
    val (retireLqMatch, retireLqIndex) = findMatch(
      lqValid, lqTag, io.retire(lane).bits, lqEntries, lqIndexWidth)
    val (retireSqMatch, retireSqIndex) = findMatch(
      sqValid, sqTag, io.retire(lane).bits, sqEntries, sqIndexWidth)
    val preferLoad = retireLqMatch && lqMetadataValid(retireLqIndex)
    val atomicPair = retireLqMatch && retireSqMatch && sqIsAtomic(retireSqIndex) &&
      lqIsAtomic(retireLqIndex)
    val atomicMetadata = Wire(new MemoryRetireMetadata(config))
    atomicMetadata.robTag := lqMetadata(retireLqIndex).robTag
    atomicMetadata.address := lqMetadata(retireLqIndex).address
    atomicMetadata.readMask := lqMetadata(retireLqIndex).readMask
    atomicMetadata.writeMask := sqMetadata(retireSqIndex).writeMask
    atomicMetadata.readData := lqMetadata(retireLqIndex).readData
    atomicMetadata.writeData := sqMetadata(retireSqIndex).writeData
    io.retireMetadata(lane).valid := io.retire(lane).valid && Mux(atomicPair,
      lqMetadataValid(retireLqIndex) && sqMetadataValid(retireSqIndex),
      preferLoad || (retireSqMatch && sqMetadataValid(retireSqIndex)))
    io.retireMetadata(lane).bits := Mux(atomicPair, atomicMetadata,
      Mux(preferLoad, lqMetadata(retireLqIndex), sqMetadata(retireSqIndex)))
    when(io.retire(lane).valid && retireLqMatch) {
      assert(lqCompleted(retireLqIndex),
        "a load cannot retire before its memory result is complete")
    }
    when(io.retire(lane).valid && retireSqMatch) {
      assert(sqEffectComplete(retireSqIndex),
        "a store cannot retire before its committed effect completes")
    }
    when(io.retire(lane).valid && atomicPair &&
      lqMetadataValid(retireLqIndex) && sqMetadataValid(retireSqIndex)) {
      assert(lqMetadata(retireLqIndex).address === sqMetadata(retireSqIndex).address,
        "atomic LQ/SQ retire metadata must describe one address")
    }
  }

  val lqSquashSurvivor = VecInit((0 until lqEntries).map(index =>
    lqValid(index) && !(lqAge(index) > ROBTagOrder.ageFromHead(
      io.squash.bits, io.robHeadTag, config))))
  val sqSquashSurvivor = VecInit((0 until sqEntries).map(index =>
    sqValid(index) && !(sqAge(index) > ROBTagOrder.ageFromHead(
      io.squash.bits, io.robHeadTag, config))))

  when(io.flush) {
    for (index <- 0 until sqEntries) {
      assert(!sqValid(index) || !sqCommitAuthorized(index) || sqEffectFault(index),
        "a flush can discard a commit-authorized store only after its exact fault")
    }
    lqValid.foreach(_ := false.B)
    sqValid.foreach(_ := false.B)
    atomicResultValid := false.B
    burstableGroupWaitValid := false.B
    burstableGroupWaitCycles := 0.U
  }.elsewhen(io.squash.valid) {
    for (index <- 0 until sqEntries) {
      when(sqValid(index) && !sqSquashSurvivor(index)) {
        assert(!sqCommitAuthorized(index),
          "selective recovery cannot discard a commit-authorized store")
      }
    }
    for (index <- 0 until lqEntries) {
      lqValid(index) := lqSquashSurvivor(index)
    }
    for (index <- 0 until sqEntries) {
      sqValid(index) := sqSquashSurvivor(index)
    }
    when(atomicResultValid && ROBTagOrder.ageFromHead(
        atomicResultBits.robTag, io.robHeadTag, config) >
        ROBTagOrder.ageFromHead(io.squash.bits, io.robHeadTag, config)) {
      atomicResultValid := false.B
    }
    burstableGroupWaitValid := false.B
    burstableGroupWaitCycles := 0.U
  }.otherwise {
    for (index <- 0 until lqEntries) {
      when(lqRetire(index)) {
        lqValid(index) := false.B
      }
    }
    for (index <- 0 until sqEntries) {
      when(sqRetire(index)) {
        sqValid(index) := false.B
      }
    }

    for (lane <- 0 until config.decodeWidth) {
      when(io.allocate(lane).fire) {
        assert(io.allocate(lane).bits.allocateLoad || io.allocate(lane).bits.allocateStore,
          "memory allocation must reserve at least one queue")
        when(io.allocate(lane).bits.allocateLoad) {
          val index = lqAllocation(lane)
          lqValid(index) := true.B
          lqTag(index) := io.allocate(lane).bits.robTag
          lqAccessSize(index) := io.allocate(lane).bits.accessSize
          lqUnsignedLoad(index) := io.allocate(lane).bits.unsignedLoad
          lqDestinationPhysical(index) := io.allocate(lane).bits.destinationPhysical
          lqWritesInteger(index) := io.allocate(lane).bits.writesInteger
          lqFloatingDestination(index) := io.allocate(lane).bits.floatingDestination
          lqWritesFloat(index) := io.allocate(lane).bits.writesFloat
          lqM1Owner(index) := io.allocate(lane).bits.m1Owner
          lqIsAtomic(index) := io.allocate(lane).bits.isAtomic
          lqAtomicOperation(index) := io.allocate(lane).bits.atomicOperation
          lqPmaKind(index) := io.allocate(lane).bits.pmaKind
          lqAq(index) := io.allocate(lane).bits.aq
          lqRl(index) := io.allocate(lane).bits.rl
          lqAddressValid(index) := false.B
          lqCompleted(index) := false.B
          lqEffectIssued(index) := false.B
          lqMetadataValid(index) := false.B
        }
        when(io.allocate(lane).bits.allocateStore) {
          val index = sqAllocation(lane)
          sqValid(index) := true.B
          sqTag(index) := io.allocate(lane).bits.robTag
          sqAddressValid(index) := false.B
          sqAccessSize(index) := io.allocate(lane).bits.accessSize
          sqDataValid(index) := false.B
          sqIsAtomic(index) := io.allocate(lane).bits.isAtomic
          sqAtomicOperation(index) := io.allocate(lane).bits.atomicOperation
          sqDestinationPhysical(index) := io.allocate(lane).bits.destinationPhysical
          sqWritesInteger(index) := io.allocate(lane).bits.writesInteger
          sqWritesFloat(index) := io.allocate(lane).bits.writesFloat
          sqPmaKind(index) := io.allocate(lane).bits.pmaKind
          sqAq(index) := io.allocate(lane).bits.aq
          sqRl(index) := io.allocate(lane).bits.rl
          sqCommitAuthorized(index) := false.B
          sqEffectIssued(index) := false.B
          sqEffectComplete(index) := false.B
          sqEffectFault(index) := false.B
          sqMetadataValid(index) := false.B
        }
      }
    }

    when(io.storeAddress.fire) {
      sqAddressValid(storeAddressIndex) := true.B
      sqAddress(storeAddressIndex) := io.storeAddress.bits.address
      sqWriteMask(storeAddressIndex) := io.storeAddress.bits.writeMask
    }
    when(io.storeData.fire) {
      sqDataValid(storeDataIndex) := true.B
      sqWriteData(storeDataIndex) := io.storeData.bits.writeData
    }
    for (request <- 0 until config.decodeWidth) {
      when(io.loadAddress(request).fire) {
        lqAddressValid(loadAddressIndex(request)) := true.B
        lqAddress(loadAddressIndex(request)) := io.loadAddress(request).bits.address
        lqReadMask(loadAddressIndex(request)) := io.loadAddress(request).bits.readMask
        lqForwardMask(loadAddressIndex(request)) := requestedForwardMask(request)
        lqForwardData(loadAddressIndex(request)) := forwardData(request)
      }
    }
    when(io.loadComplete.fire) {
      lqCompleted(loadCompleteIndex) := true.B
      when(!io.loadComplete.bits.accessFault) {
        lqMetadataValid(loadCompleteIndex) := true.B
        lqMetadata(loadCompleteIndex).robTag := lqTag(loadCompleteIndex)
        lqMetadata(loadCompleteIndex).address := lqAddress(loadCompleteIndex)
        lqMetadata(loadCompleteIndex).readMask := lqReadMask(loadCompleteIndex)
        lqMetadata(loadCompleteIndex).writeMask := 0.U
        lqMetadata(loadCompleteIndex).readData := completedLoadData
        lqMetadata(loadCompleteIndex).writeData := 0.U
      }
    }
    when(io.deviceLoadEffect.fire) {
      lqEffectIssued(headLoadIndex) := true.B
    }
    when(io.burstableDeviceGroupAccepted.valid) {
      assert(io.burstableDeviceGroupAccepted.bits.count =/= 0.U &&
        io.burstableDeviceGroupAccepted.bits.count <= 4.U,
        "accepted DeviceBurstable group must contain one through four members")
      for (member <- 0 until 4) {
        when(member.U < io.burstableDeviceGroupAccepted.bits.count) {
          val request = io.burstableDeviceGroupAccepted.bits.requests(member)
          val (loadMatch, loadIndex) = findMatch(
            lqValid, lqTag, request.robTag, lqEntries, lqIndexWidth)
          val (storeMatch, storeIndex) = findMatch(
            sqValid, sqTag, request.robTag, sqEntries, sqIndexWidth)
          when(request.write) {
            assert(storeMatch && sqAddressValid(storeIndex) && sqDataValid(storeIndex) &&
              !sqIsAtomic(storeIndex) &&
              sqPmaKind(storeIndex) === PMARegionKind.DeviceBurstable.code.U &&
              !sqEffectIssued(storeIndex),
              "accepted DeviceBurstable write must retain an eligible SQ owner")
            sqCommitAuthorized(storeIndex) := true.B
            sqEffectIssued(storeIndex) := true.B
          }.otherwise {
            assert(loadMatch && lqAddressValid(loadIndex) && !lqM1Owner(loadIndex) &&
              !lqIsAtomic(loadIndex) &&
              lqPmaKind(loadIndex) === PMARegionKind.DeviceBurstable.code.U &&
              !lqEffectIssued(loadIndex) && !lqCompleted(loadIndex),
              "accepted DeviceBurstable read must retain an eligible LQ owner")
            lqEffectIssued(loadIndex) := true.B
          }
        }
      }
    }
    when(io.commitAuthorize.fire) {
      sqCommitAuthorized(commitIndex) := true.B
    }
    when(io.storeEffect.fire) {
      sqEffectIssued(selectedStoreIndex) := true.B
    }
    when(io.atomicEffect.fire) {
      when(headAtomicLoad) {
        lqEffectIssued(headLoadIndex) := true.B
      }
      when(headAtomicStore) {
        sqEffectIssued(headStoreIndex) := true.B
      }
    }
    when(io.atomicComplete.fire) {
      atomicResultValid := true.B
      atomicResultBits := io.atomicComplete.bits
      when(atomicCompleteNeedsLoad) {
        assert(atomicCompleteLoadEligible,
          "atomic result lost its issued LQ owner")
        lqCompleted(atomicCompleteLoadIndex) := true.B
        when(!io.atomicComplete.bits.accessFault) {
          lqMetadataValid(atomicCompleteLoadIndex) := true.B
          lqMetadata(atomicCompleteLoadIndex).robTag :=
            lqTag(atomicCompleteLoadIndex)
          lqMetadata(atomicCompleteLoadIndex).address :=
            lqAddress(atomicCompleteLoadIndex)
          lqMetadata(atomicCompleteLoadIndex).readMask :=
            io.atomicComplete.bits.readMask
          lqMetadata(atomicCompleteLoadIndex).writeMask := 0.U
          lqMetadata(atomicCompleteLoadIndex).readData :=
            io.atomicComplete.bits.readData
          lqMetadata(atomicCompleteLoadIndex).writeData := 0.U
        }
      }
      when(atomicCompleteNeedsStore) {
        assert(atomicCompleteStoreEligible,
          "atomic result lost its issued SQ owner")
        sqEffectComplete(atomicCompleteStoreIndex) := true.B
        sqEffectFault(atomicCompleteStoreIndex) := io.atomicComplete.bits.accessFault
        when(!io.atomicComplete.bits.accessFault) {
          sqMetadataValid(atomicCompleteStoreIndex) := true.B
          sqMetadata(atomicCompleteStoreIndex).robTag :=
            sqTag(atomicCompleteStoreIndex)
          sqMetadata(atomicCompleteStoreIndex).address :=
            sqAddress(atomicCompleteStoreIndex)
          sqMetadata(atomicCompleteStoreIndex).readMask := 0.U
          sqMetadata(atomicCompleteStoreIndex).writeMask := Mux(
            io.atomicComplete.bits.storePerformed,
            io.atomicComplete.bits.writeMask, 0.U)
          sqMetadata(atomicCompleteStoreIndex).readData := 0.U
          sqMetadata(atomicCompleteStoreIndex).writeData := Mux(
            io.atomicComplete.bits.storePerformed,
            io.atomicComplete.bits.writeData, 0.U)
        }
      }
    }
    when(io.atomicResult.fire) {
      atomicResultValid := false.B
    }
    when(io.storeEffectComplete.valid && effectCompleteMatch) {
      assert(sqCommitAuthorized(effectCompleteIndex) && sqEffectIssued(effectCompleteIndex),
        "store effect completion requires an issued commit-authorized action")
      assert(!sqEffectComplete(effectCompleteIndex), "store effect completed twice")
      sqEffectComplete(effectCompleteIndex) := true.B
      sqEffectFault(effectCompleteIndex) := io.storeEffectComplete.bits.accessFault
      when(!io.storeEffectComplete.bits.accessFault) {
        sqMetadataValid(effectCompleteIndex) := true.B
        sqMetadata(effectCompleteIndex).robTag := sqTag(effectCompleteIndex)
        sqMetadata(effectCompleteIndex).address := sqAddress(effectCompleteIndex)
        sqMetadata(effectCompleteIndex).readMask := 0.U
        sqMetadata(effectCompleteIndex).writeMask := sqWriteMask(effectCompleteIndex)
        sqMetadata(effectCompleteIndex).readData := 0.U
        sqMetadata(effectCompleteIndex).writeData := sqWriteData(effectCompleteIndex)
      }
    }
    when(!burstableGroupEligible || io.burstableDeviceGroupAccepted.valid) {
      burstableGroupWaitValid := false.B
      burstableGroupWaitCycles := 0.U
    }.elsewhen(!burstableGroupWaitValid || burstableGroupWaitHead =/= io.robHeadTag) {
      burstableGroupWaitValid := true.B
      burstableGroupWaitHead := io.robHeadTag
      burstableGroupWaitCycles := 0.U
    }.elsewhen(burstableGroupWaitCycles =/= burstableGroupCollectionCycles.U) {
      burstableGroupWaitCycles := burstableGroupWaitCycles + 1.U
    }
  }

  assert(!io.allocate(1).valid || io.allocate(0).valid,
    "LSQ allocation lane 1 cannot be valid when lane 0 is a bubble")
  when(io.allocate(0).fire && io.allocate(1).fire) {
    assert(io.allocate(0).bits.robTag =/= io.allocate(1).bits.robTag,
      "two LSQ allocations cannot share a ROB tag")
  }
  for (first <- 0 until lqEntries; second <- first + 1 until lqEntries) {
    assert(!(lqValid(first) && lqValid(second) && lqTag(first) === lqTag(second)),
      "two live LQ entries cannot share a ROB tag")
  }
  for (first <- 0 until sqEntries; second <- first + 1 until sqEntries) {
    assert(!(sqValid(first) && sqValid(second) && sqTag(first) === sqTag(second)),
      "two live SQ entries cannot share a ROB tag")
  }
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "LSQ squash boundary ROB index out of range")
    assert(!io.allocate.exists(_.fire) && !io.storeAddress.fire && !io.storeData.fire &&
      !io.loadAddress.exists(_.fire) && !io.commitAuthorize.fire && !io.storeEffect.fire &&
      !io.atomicEffect.fire && !io.atomicComplete.fire,
      "LSQ transferred local work during selective squash")
  }
  when(io.loadAddress(0).fire && io.loadAddress(1).fire) {
    assert(io.loadAddress(0).bits.robTag =/= io.loadAddress(1).bits.robTag,
      "two LQ load-forward ports cannot accept the same ROB tag")
  }
  assert(PopCount(lqValid) <= lqEntries.U, "LQ occupancy exceeded its depth")
  assert(PopCount(sqValid) <= sqEntries.U, "SQ occupancy exceeded its depth")
  val orderingBarrierAge = ROBTagOrder.ageFromHead(
    io.orderingBarrier.bits, io.robHeadTag, config)
  val olderLoadPending = VecInit((0 until lqEntries).map(index =>
    lqValid(index) && lqAge(index) < orderingBarrierAge)).asUInt.orR
  val olderStorePending = VecInit((0 until sqEntries).map(index =>
    sqValid(index) && sqAge(index) < orderingBarrierAge)).asUInt.orR
  io.orderingReady := !io.orderingBarrier.valid ||
    !(olderLoadPending || olderStorePending)
  io.loadCount := PopCount(lqValid)
  io.storeCount := PopCount(sqValid)
  io.storeCommitInFlight := VecInit((0 until sqEntries).map(index =>
    sqValid(index) && sqCommitAuthorized(index) && !sqEffectFault(index))).asUInt.orR
  // A completed device read remains irreversible until its real ROB entry
  // retires. Taking an interrupt earlier would flush its completion and make
  // MRET repeat the external read.
  io.deviceLoadInFlight := VecInit((0 until lqEntries).map(index =>
    lqValid(index) && lqEffectIssued(index) && !lqIsAtomic(index))).asUInt.orR
  io.atomicInFlight := VecInit((0 until lqEntries).map(index =>
    lqValid(index) && lqIsAtomic(index) && lqEffectIssued(index))).asUInt.orR ||
    VecInit((0 until sqEntries).map(index =>
      sqValid(index) && sqIsAtomic(index) && sqEffectIssued(index))).asUInt.orR ||
    atomicResultValid

  val acquireCandidates = (0 until lqEntries).map(index =>
    lqValid(index) && lqIsAtomic(index) && lqAq(index)) ++
    (0 until sqEntries).map(index =>
      sqValid(index) && sqIsAtomic(index) && sqAq(index))
  val acquireAges = lqAge.toSeq ++ sqAge.toSeq
  val acquireTags = VecInit(lqTag.toSeq ++ sqTag.toSeq)
  val (acquireBarrierValid, acquireBarrierIndex) = selectOldest(
    acquireCandidates, acquireAges, log2Ceil(lqEntries + sqEntries))
  io.atomicAcquireBarrier.valid := acquireBarrierValid && !recoveryBlocked
  io.atomicAcquireBarrier.bits := acquireTags(acquireBarrierIndex)
}
