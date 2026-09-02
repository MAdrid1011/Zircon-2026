package zircon.memory

import chisel3._
import chisel3.util._
import zircon.{PMARegionKind, ZirconCoreConfig}
import zircon.backend.ROBTagOrder

/** Executable 1 KiB L1D slice with an exclusive L2 transfer boundary.
  *
  * Commit-authorized cacheable stores update the exclusive L1D copy and mark
  * it dirty. Store misses reuse the four refill MSHRs and only report their
  * exact result after the allocated line has been filled and updated. Victims
  * transfer their dirty bit with the sole L1D-to-L2 ownership record; an L2
  * AXI writeback owner drains dirty L2 victims in the following M3 slice.
  */
class L1DLoadCache(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val cache = config.l1d
  private val ways = cache.ways
  private val wordsPerLine = cache.lineBytes / 4
  private val lineOffsetWidth = log2Ceil(cache.lineBytes)
  private val sets = cache.bytes / (ways * cache.lineBytes)
  private val setWidth = log2Ceil(sets)
  private val tagWidth = 32 - lineOffsetWidth - setWidth
  private val mshrCount = cache.mshrs
  private val mshrWidth = log2Ceil(mshrCount)
  private val waiterCount = config.loadQueueEntries
  private val waiterWidth = log2Ceil(waiterCount)
  private val wordBanks = 4
  private val wordBankWidth = log2Ceil(wordBanks)

  require(cache.bytes == 1024 && ways == 2 && cache.lineBytes == 32,
    "the frozen M3 L1D load slice is 1 KiB, two-way, and 32-byte-line")
  require(mshrCount == 4 && waiterCount == 8,
    "the frozen M3 load slice has four MSHRs and eight LQ waiters")
  require(wordsPerLine % wordBanks == 0,
    "the frozen M3 L1D data array must divide evenly across its word banks")

  val io = IO(new Bundle {
    /** Two cacheable load candidates receive parallel tag/data lookups. The
      * conflict policy may retain either candidate rather than fabricating a
      * completion when a bank or miss resource is unavailable. */
    val request = Flipped(Vec(config.decodeWidth,
      Decoupled(new LoadStoreForward(config))))
    val completion = Decoupled(new LoadCompletion(config))
    /** L2 owns physical AXI demand slots; L1D carries only its local MSHR
      * token across this boundary. */
    val dataRequest = Decoupled(new L2DemandRequest(config))
    val dataResponse = Flipped(Decoupled(new L2DemandResponse(config)))
    /** Exclusive L1D<->L2 line moves. A new L1D miss first transfers any
      * resident victim, then probes L2 before issuing its AXI fallback. */
    val l2Insert = Decoupled(new CacheLineTransfer(config))
    val l2Lookup = Decoupled(new L2LookupRequest(config))
    val l2Response = Flipped(Decoupled(new L2LookupResponse(config)))
    /** Trace-only exact dirty-line transfer. A matching committed host store
      * uses this path to move its sole L1D copy into L2 before ID-5 writeback.
      */
    val flushLine = Flipped(Decoupled(UInt(32.W)))
    /** Cache-global FENCE drain. Existing demand owners continue to drain;
      * new L1D ingress stalls until every dirty resident line reaches L2. */
    val fenceDrain = Input(Bool())
    val fenceDrained = Output(Bool())
    /** A commit-authorized cacheable store. It becomes irreversible only on
      * this handshake, and its result remains owned until the SQ consumes it.
      */
    val storeRequest = Flipped(Decoupled(new StoreEffect(config)))
    val storeResult = Decoupled(new StoreWriteResult(config))
    val storeBusy = Output(Bool())
    /** Atomics bypass the read-only data path. They wait for a same-line MSHR
      * before their AXI read/modify/write begins, then invalidate on a
      * successful externally visible write. */
    val atomicAccept = Input(Valid(new AtomicMemoryEffect(config)))
    /** Failed SC is resolved inside the atomic owner and does not need backing
      * memory, so it may bypass a dirty-line external-access block. */
    val atomicRequiresExternal = Input(Bool())
    val atomicAcceptReady = Output(Bool())
    val atomicInvalidate = Input(Valid(UInt(32.W)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val cacheValid = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val cacheDirty = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val cacheTag = Reg(Vec(ways, Vec(sets, UInt(tagWidth.W))))
  val cacheData = Reg(Vec(ways, Vec(sets, Vec(wordsPerLine, UInt(32.W)))))

  val mshrValid = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrIssued = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrFilled = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrFault = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrL2ProbeIssued = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrL2Resolved = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrStorePending = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrStoreEffect = Reg(Vec(mshrCount, new StoreEffect(config)))
  val mshrLineAddress = Reg(Vec(mshrCount, UInt(32.W)))
  val mshrSet = Reg(Vec(mshrCount, UInt(setWidth.W)))
  val mshrWay = Reg(Vec(mshrCount, UInt(log2Ceil(ways).W)))

  val waiterValid = RegInit(VecInit.fill(waiterCount)(false.B))
  val waiterMshr = Reg(Vec(waiterCount, UInt(mshrWidth.W)))
  val waiterTag = Reg(Vec(waiterCount, UInt(config.robTagWidth.W)))
  val waiterWord = Reg(Vec(waiterCount, UInt(log2Ceil(wordsPerLine).W)))
  val mshrHasWaiter = Wire(Vec(mshrCount, Bool()))
  for (mshr <- 0 until mshrCount) {
    mshrHasWaiter(mshr) := (0 until waiterCount).map(waiter =>
      waiterValid(waiter) && waiterMshr(waiter) === mshr.U).reduce(_ || _)
  }
  val mshrHasDemand = Wire(Vec(mshrCount, Bool()))
  for (mshr <- 0 until mshrCount) {
    mshrHasDemand(mshr) := mshrHasWaiter(mshr) || mshrStorePending(mshr)
  }

  // Each read port has a retained hit/fully-forwarded result slot. The
  // downstream LQ completion transport remains one-wide for this increment,
  // but two accepted hits cannot overwrite one another while it drains.
  val immediateValid = RegInit(VecInit.fill(config.decodeWidth)(false.B))
  val immediateResponse = Reg(Vec(config.decodeWidth, new LoadCompletion(config)))
  val storeResultValid = RegInit(false.B)
  val storeResultBits = Reg(new StoreWriteResult(config))
  val l2ProbeActive = RegInit(false.B)
  val l2ProbeMshr = Reg(UInt(mshrWidth.W))
  val recoveryBlocked = io.flush || io.squash.valid

  val firstRequestAge = ROBTagOrder.ageFromHead(io.request(0).bits.robTag,
    io.robHeadTag, config)
  val secondRequestAge = ROBTagOrder.ageFromHead(io.request(1).bits.robTag,
    io.robHeadTag, config)
  val selectFirstRequest = io.request(0).valid &&
    (!io.request(1).valid || firstRequestAge <= secondRequestAge)
  val selectedRequest = Wire(new LoadStoreForward(config))
  selectedRequest := Mux(selectFirstRequest, io.request(0).bits,
    io.request(1).bits)
  val selectedRequestValid = io.request(0).valid || io.request(1).valid
  val selectedRequestPort = Mux(selectFirstRequest, 0.U, 1.U)

  val portSet = (0 until config.decodeWidth).map(port =>
    io.request(port).bits.address(lineOffsetWidth + setWidth - 1, lineOffsetWidth))
  val portTag = (0 until config.decodeWidth).map(port =>
    io.request(port).bits.address(31, lineOffsetWidth + setWidth))
  val portWord = (0 until config.decodeWidth).map(port =>
    io.request(port).bits.address(lineOffsetWidth - 1, 2))
  val portBank = portWord.map(_(wordBankWidth - 1, 0))
  val portLineAddress = (0 until config.decodeWidth).map(port =>
    Cat(io.request(port).bits.address(31, lineOffsetWidth),
      0.U(lineOffsetWidth.W)))
  val portCacheHit = (0 until config.decodeWidth).map(port => VecInit(
    (0 until ways).map(way => cacheValid(way)(portSet(port)) &&
      cacheTag(way)(portSet(port)) === portTag(port))))
  val portAnyCacheHit = portCacheHit.map(_.asUInt.orR)
  val portHitData = (0 until config.decodeWidth).map { port =>
    val data = Wire(UInt(32.W))
    data := 0.U
    for (way <- 0 until ways) {
      when(portCacheHit(port)(way)) {
        data := cacheData(way)(portSet(port))(portWord(port))
      }
    }
    data
  }

  val requestSet = selectedRequest.address(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val requestWord = selectedRequest.address(lineOffsetWidth - 1, 2)
  val requestLineAddress = Cat(selectedRequest.address(31, lineOffsetWidth),
    0.U(lineOffsetWidth.W))

  val matchingMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrLineAddress(index) === requestLineAddress))
  val anyMatchingMshr = matchingMshr.asUInt.orR
  val matchingMshrIndex = PriorityEncoder(matchingMshr.asUInt)
  val freeMshr = VecInit((0 until mshrCount).map(index => !mshrValid(index)))
  val anyFreeMshr = freeMshr.asUInt.orR
  val freeMshrIndex = PriorityEncoder(freeMshr.asUInt)
  val secondFreeMshr = VecInit((0 until mshrCount).map(index =>
    !mshrValid(index) && index.U =/= freeMshrIndex))
  val anySecondFreeMshr = secondFreeMshr.asUInt.orR
  val secondFreeMshrIndex = PriorityEncoder(secondFreeMshr.asUInt)
  val freeWaiter = (~waiterValid.asUInt)(waiterCount - 1, 0)
  val anyFreeWaiter = freeWaiter.orR
  val freeWaiterIndex = PriorityEncoder(freeWaiter)
  val secondFreeWaiter = VecInit((0 until waiterCount).map(index =>
    !waiterValid(index) && index.U =/= freeWaiterIndex))
  val anySecondFreeWaiter = secondFreeWaiter.asUInt.orR
  val secondFreeWaiterIndex = PriorityEncoder(secondFreeWaiter.asUInt)

  val storeSet = io.storeRequest.bits.address(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val storeTag = io.storeRequest.bits.address(31, lineOffsetWidth + setWidth)
  val storeWord = io.storeRequest.bits.address(lineOffsetWidth - 1, 2)
  val storeLineAddress = Cat(io.storeRequest.bits.address(31, lineOffsetWidth),
    0.U(lineOffsetWidth.W))
  val storeCacheHit = VecInit((0 until ways).map(way =>
    cacheValid(way)(storeSet) && cacheTag(way)(storeSet) === storeTag))
  val anyStoreCacheHit = storeCacheHit.asUInt.orR
  val storeHitWay = PriorityEncoder(storeCacheHit.asUInt)
  val storeMatchingMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && !mshrFilled(index) && !mshrStorePending(index) &&
      mshrLineAddress(index) === storeLineAddress))
  val anyStoreMatchingMshr = storeMatchingMshr.asUInt.orR
  val storeMatchingMshrIndex = PriorityEncoder(storeMatchingMshr.asUInt)
  val storeLineMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrLineAddress(index) === storeLineAddress))
  val anyStoreLineMshr = storeLineMshr.asUInt.orR
  val anyMshrStorePending = mshrStorePending.asUInt.orR
  val storeOwnerAvailable = !storeResultValid && !anyMshrStorePending
  val atomicLineAddress = Cat(io.atomicAccept.bits.address(31, lineOffsetWidth),
    0.U(lineOffsetWidth.W))
  val atomicMatchesMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrLineAddress(index) === atomicLineAddress)).asUInt.orR
  val atomicSet = io.atomicAccept.bits.address(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val atomicTag = io.atomicAccept.bits.address(31, lineOffsetWidth + setWidth)
  val atomicDirtyResident = VecInit((0 until ways).map(way =>
    cacheValid(way)(atomicSet) && cacheDirty(way)(atomicSet) &&
      cacheTag(way)(atomicSet) === atomicTag)).asUInt.orR
  val flushSet = io.flushLine.bits(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val flushTag = io.flushLine.bits(31, lineOffsetWidth + setWidth)
  val flushHits = VecInit((0 until ways).map(way =>
    cacheValid(way)(flushSet) && cacheTag(way)(flushSet) === flushTag))
  val flushHit = flushHits.asUInt.orR
  val flushWay = PriorityEncoder(flushHits.asUInt)
  val flushDirty = flushHit && cacheDirty(flushWay)(flushSet)
  val flushLineAddress = Cat(io.flushLine.bits(31, lineOffsetWidth),
    0.U(lineOffsetWidth.W))
  val flushHasMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrLineAddress(index) === flushLineAddress)).asUInt.orR
  val flushL2Insert = io.flushLine.valid && flushDirty && !flushHasMshr
  val anyMshrValid = mshrValid.asUInt.orR

  val immediateSlotWidth = log2Ceil(config.decodeWidth)
  var selectedImmediateValid: Bool = false.B
  var selectedImmediateIndex: UInt = 0.U(immediateSlotWidth.W)
  var selectedImmediateAge: UInt = 0.U((config.robIndexWidth + 1).W)
  for (slot <- 0 until config.decodeWidth) {
    val age = ROBTagOrder.ageFromHead(immediateResponse(slot).robTag,
      io.robHeadTag, config)
    val take = immediateValid(slot) && (!selectedImmediateValid ||
      age < selectedImmediateAge)
    selectedImmediateIndex = Mux(take, slot.U, selectedImmediateIndex)
    selectedImmediateAge = Mux(take, age, selectedImmediateAge)
    selectedImmediateValid = selectedImmediateValid || immediateValid(slot)
  }
  val completionImmediateFire = io.completion.fire && selectedImmediateValid
  val immediateSlotAvailable = VecInit((0 until config.decodeWidth).map(slot =>
    !immediateValid(slot) || (completionImmediateFire &&
      selectedImmediateIndex === slot.U)))

  val fenceCanTransfer = io.fenceDrain && !anyMshrValid && !storeResultValid &&
    !selectedImmediateValid && !l2ProbeActive
  var fenceDirtyFound: Bool = false.B
  var fenceDirtyWay: UInt = 0.U(log2Ceil(ways).W)
  var fenceDirtySet: UInt = 0.U(setWidth.W)
  for (set <- 0 until sets; way <- 0 until ways) {
    val dirty = cacheValid(way)(set) && cacheDirty(way)(set)
    val take = dirty && !fenceDirtyFound
    fenceDirtyWay = Mux(take, way.U, fenceDirtyWay)
    fenceDirtySet = Mux(take, set.U, fenceDirtySet)
    fenceDirtyFound = fenceDirtyFound || dirty
  }
  val fenceL2Insert = fenceCanTransfer && !io.flushLine.valid && fenceDirtyFound
  io.fenceDrained := !anyMshrValid && !storeResultValid && !selectedImmediateValid &&
    !l2ProbeActive && !fenceDirtyFound
  // An external atomic cannot observe a dirty L1D line until its later L2
  // writeback owner exists. Blocking preserves coherent memory semantics.
  io.atomicAcceptReady := !recoveryBlocked && (!io.atomicRequiresExternal ||
    (!atomicMatchesMshr && !atomicDirtyResident)) && !io.flushLine.valid

  val reservedWay = Wire(Vec(ways, Bool()))
  for (way <- 0 until ways) {
    reservedWay(way) := (0 until mshrCount).map(index =>
      mshrValid(index) && mshrSet(index) === requestSet &&
        mshrWay(index) === way.U).reduce(_ || _)
  }
  val invalidWay = VecInit((0 until ways).map(way =>
    !reservedWay(way) && !cacheValid(way)(requestSet)))
  val usableWay = VecInit((0 until ways).map(way => !reservedWay(way)))
  val hasInvalidWay = invalidWay.asUInt.orR
  val victimWays = Mux(hasInvalidWay, invalidWay.asUInt, usableWay.asUInt)
  val hasVictimWay = victimWays.orR
  val victimWay = PriorityEncoder(victimWays)
  val victimValid = cacheValid(victimWay)(requestSet)

  val storeReservedWay = Wire(Vec(ways, Bool()))
  for (way <- 0 until ways) {
    storeReservedWay(way) := (0 until mshrCount).map(index =>
      mshrValid(index) && mshrSet(index) === storeSet &&
        mshrWay(index) === way.U).reduce(_ || _)
  }
  val storeInvalidWay = VecInit((0 until ways).map(way =>
    !storeReservedWay(way) && !cacheValid(way)(storeSet)))
  val storeUsableWay = VecInit((0 until ways).map(way => !storeReservedWay(way)))
  val storeHasInvalidWay = storeInvalidWay.asUInt.orR
  val storeVictimWays = Mux(storeHasInvalidWay, storeInvalidWay.asUInt,
    storeUsableWay.asUInt)
  val storeHasVictimWay = storeVictimWays.orR
  val storeVictimWay = PriorityEncoder(storeVictimWays)
  val storeVictimValid = cacheValid(storeVictimWay)(storeSet)

  val portImmediateRequest = VecInit((0 until config.decodeWidth).map(port =>
    !io.request(port).bits.requiresCache || portAnyCacheHit(port)))
  val immediateRequest = Mux(selectFirstRequest, portImmediateRequest(0),
    portImmediateRequest(1))
  val sameWordBank = portBank(0) === portBank(1)
  val dualImmediateCompatible = io.request(0).valid && io.request(1).valid &&
    portImmediateRequest(0) && portImmediateRequest(1) &&
    !(io.request(0).bits.requiresCache && io.request(1).bits.requiresCache &&
      sameWordBank)
  // A different-set hit may retain its result alongside one miss. The miss
  // either reserves an invalid way or, for the clean-victim increment, owns
  // the sole L1D-to-L2 transfer in the same acceptance cycle. Dirty victims
  // remain on the deterministic replay path until their transfer/writeback
  // pressure matrix is widened separately.
  val dualHitMissPort0Hit = portAnyCacheHit(0) && !portImmediateRequest(1)
  val dualHitMissPort1Hit = portAnyCacheHit(1) && !portImmediateRequest(0)
  val dualHitMissMissAddress = Mux(dualHitMissPort0Hit,
    io.request(1).bits.address, io.request(0).bits.address)
  val dualHitMissLineAddress = Cat(dualHitMissMissAddress(31, lineOffsetWidth),
    0.U(lineOffsetWidth.W))
  val dualHitMissSet = dualHitMissMissAddress(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val dualHitMissWord = dualHitMissMissAddress(lineOffsetWidth - 1, 2)
  val dualHitMissTag = Mux(dualHitMissPort0Hit, io.request(1).bits.robTag,
    io.request(0).bits.robTag)
  val dualHitMissMatchingMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrLineAddress(index) === dualHitMissLineAddress))
  val dualHitMissAnyMatchingMshr = dualHitMissMatchingMshr.asUInt.orR
  val dualHitMissMatchingMshrIndex = PriorityEncoder(dualHitMissMatchingMshr.asUInt)
  val dualHitMissReservedWay = Wire(Vec(ways, Bool()))
  for (way <- 0 until ways) {
    dualHitMissReservedWay(way) := (0 until mshrCount).map(index =>
      mshrValid(index) && mshrSet(index) === dualHitMissSet &&
        mshrWay(index) === way.U).reduce(_ || _)
  }
  val dualHitMissInvalidWay = VecInit((0 until ways).map(way =>
    !dualHitMissReservedWay(way) && !cacheValid(way)(dualHitMissSet)))
  val dualHitMissHasInvalidWay = dualHitMissInvalidWay.asUInt.orR
  val dualHitMissUsableWay = VecInit((0 until ways).map(way =>
    !dualHitMissReservedWay(way)))
  val dualHitMissVictimWays = Mux(dualHitMissHasInvalidWay,
    dualHitMissInvalidWay.asUInt, dualHitMissUsableWay.asUInt)
  val dualHitMissHasVictimWay = dualHitMissVictimWays.orR
  val dualHitMissWay = PriorityEncoder(dualHitMissVictimWays)
  val dualHitMissVictimValid = cacheValid(dualHitMissWay)(dualHitMissSet)
  val dualHitMissVictimDirty = dualHitMissVictimValid &&
    cacheDirty(dualHitMissWay)(dualHitMissSet)
  val dualHitMissCandidate = io.request(0).valid && io.request(1).valid &&
    io.request(0).bits.cacheable && io.request(1).bits.cacheable &&
    (dualHitMissPort0Hit || dualHitMissPort1Hit) &&
    // A same-set pair is safe only when the miss has an invalid way. The
    // replacement path below therefore never invalidates the hit-visible way.
    (portSet(0) =/= portSet(1) || dualHitMissHasInvalidWay)
  // Two different-line misses can reserve independent invalid ways and MSHRs
  // without claiming the single L1D-to-L2 victim-transfer port. Same-set pairs
  // need two distinct invalid ways; all accepted owners remain independent
  // while the existing L2 probe interface drains them.
  val dualDifferentSetMissMatchingMshr = (0 until config.decodeWidth).map(port =>
    VecInit((0 until mshrCount).map(index =>
      mshrValid(index) && mshrLineAddress(index) === portLineAddress(port))))
  val dualDifferentSetMissAnyMatchingMshr = dualDifferentSetMissMatchingMshr.map(
    _.asUInt.orR)
  val dualDifferentSetMissReservedWay = (0 until config.decodeWidth).map { port =>
    val reserved = Wire(Vec(ways, Bool()))
    for (way <- 0 until ways) {
      reserved(way) := (0 until mshrCount).map(index =>
        mshrValid(index) && mshrSet(index) === portSet(port) &&
          mshrWay(index) === way.U).reduce(_ || _)
    }
    reserved
  }
  val dualDifferentSetMissInvalidWay = (0 until config.decodeWidth).map(port =>
    VecInit((0 until ways).map(way =>
      !dualDifferentSetMissReservedWay(port)(way) && !cacheValid(way)(portSet(port)))))
  val dualDifferentSetMissHasInvalidWay = dualDifferentSetMissInvalidWay.map(
    _.asUInt.orR)
  val dualDifferentSetMissWay = dualDifferentSetMissInvalidWay.map(ways =>
    PriorityEncoder(ways.asUInt))
  val dualDifferentSetMissSameSet = portSet(0) === portSet(1)
  val dualDifferentSetMissSecondWayMask = VecInit((0 until ways).map(way =>
    dualDifferentSetMissInvalidWay(1)(way) &&
      way.U =/= dualDifferentSetMissWay(0)))
  val dualDifferentSetMissHasDistinctWays = !dualDifferentSetMissSameSet ||
    dualDifferentSetMissSecondWayMask.asUInt.orR
  val dualDifferentSetMissSecondWay = PriorityEncoder(
    dualDifferentSetMissSecondWayMask.asUInt)
  val dualDifferentSetMissAllocatedWay = (0 until config.decodeWidth).map { port =>
    if (port == 0) dualDifferentSetMissWay(0)
    else Mux(dualDifferentSetMissSameSet,
      dualDifferentSetMissSecondWay, dualDifferentSetMissWay(1))
  }
  val dualDifferentSetMissCandidate = io.request(0).valid && io.request(1).valid &&
    io.request(0).bits.cacheable && io.request(1).bits.cacheable &&
    !portImmediateRequest(0) && !portImmediateRequest(1) &&
    portLineAddress(0) =/= portLineAddress(1) &&
    (portSet(0) =/= portSet(1) || dualDifferentSetMissHasDistinctWays)
  val sameLineDualMiss = io.request(0).valid && io.request(1).valid &&
    io.request(0).bits.cacheable && io.request(1).bits.cacheable &&
    !portImmediateRequest(0) && !portImmediateRequest(1) &&
    portLineAddress(0) === portLineAddress(1)
  val sameLineDualWaiterCredits = anyFreeWaiter && anySecondFreeWaiter
  // If the pair cannot consume two waiter credits, it falls through to the
  // existing oldest-first path. This keeps a victim transfer coupled to a
  // real accepted request instead of evicting a line for a blocked younger
  // waiter.
  val loadWaiterCredits = Mux(sameLineDualMiss && sameLineDualWaiterCredits,
    sameLineDualWaiterCredits, anyFreeWaiter)
  val selectedImmediateSlotAvailable = Mux(selectFirstRequest,
    immediateSlotAvailable(0), immediateSlotAvailable(1))
  val dualHitMissImmediateSlotAvailable = Mux(dualHitMissPort0Hit,
    immediateSlotAvailable(0), immediateSlotAvailable(1))
  val newMissNeedsL2Insert = !anyMatchingMshr && anyFreeMshr && hasVictimWay &&
    victimValid
  val dualHitMissNewNeedsL2Insert = !dualHitMissAnyMatchingMshr && anyFreeMshr &&
    dualHitMissHasVictimWay && dualHitMissVictimValid
  val storeNewMissNeedsL2Insert = !anyStoreLineMshr && anyFreeMshr &&
    storeHasVictimWay && storeVictimValid
  val loadL2Insert = selectedRequestValid && selectedRequest.cacheable &&
    !io.storeRequest.valid && !io.flushLine.valid && !io.fenceDrain && !recoveryBlocked && !immediateRequest &&
    loadWaiterCredits && newMissNeedsL2Insert
  val storeL2Insert = io.storeRequest.valid && storeOwnerAvailable &&
    !io.flushLine.valid && !io.fenceDrain && !recoveryBlocked &&
    !anyStoreCacheHit && !anyStoreLineMshr && anyFreeMshr &&
    storeHasVictimWay && storeNewMissNeedsL2Insert
  // This transfer is asserted only when both exact request owners can be
  // allocated. Otherwise an L2-ready handshake could evict a line without a
  // matching MSHR/waiter record.
  val dualHitMissL2Insert = dualHitMissCandidate &&
    !io.storeRequest.valid && !io.flushLine.valid && !io.fenceDrain &&
    !recoveryBlocked && dualHitMissImmediateSlotAvailable && anyFreeWaiter &&
    dualHitMissNewNeedsL2Insert && !dualHitMissVictimDirty
  val l2InsertForStore = !flushL2Insert && !fenceL2Insert && storeL2Insert
  val l2InsertWay = Mux(flushL2Insert, flushWay,
    Mux(fenceL2Insert, fenceDirtyWay, Mux(l2InsertForStore, storeVictimWay,
      Mux(dualHitMissL2Insert, dualHitMissWay, victimWay))))
  val l2InsertSet = Mux(flushL2Insert, flushSet,
    Mux(fenceL2Insert, fenceDirtySet, Mux(l2InsertForStore, storeSet,
      Mux(dualHitMissL2Insert, dualHitMissSet, requestSet))))
  io.l2Insert.valid := flushL2Insert || fenceL2Insert || loadL2Insert || storeL2Insert ||
    dualHitMissL2Insert
  io.l2Insert.bits.lineAddress := Cat(cacheTag(l2InsertWay)(l2InsertSet), l2InsertSet,
    0.U(lineOffsetWidth.W))
  io.l2Insert.bits.dirty := cacheDirty(l2InsertWay)(l2InsertSet)
  for (word <- 0 until wordsPerLine) {
    io.l2Insert.bits.lineData(word) := cacheData(l2InsertWay)(l2InsertSet)(word)
  }
  io.flushLine.ready := flushL2Insert && io.l2Insert.ready
  val missResources = anyFreeWaiter && (anyMatchingMshr ||
    (anyFreeMshr && hasVictimWay && (!newMissNeedsL2Insert || io.l2Insert.ready)))
  val sameLineDualMissResources = sameLineDualWaiterCredits &&
    (anyMatchingMshr || (anyFreeMshr && hasVictimWay &&
      (!newMissNeedsL2Insert || io.l2Insert.ready)))
  val storeMissResources = anyStoreMatchingMshr ||
    (!anyStoreLineMshr && anyFreeMshr && storeHasVictimWay &&
      (!storeNewMissNeedsL2Insert || io.l2Insert.ready))
  // Device and atomic requests retain their ordered M0 owner. Only a
  // commit-authorized cacheable non-atomic store may change L1D state.
  // Miss ownership remains one-wide until MSHR/victim arbitration itself is
  // widened. Two immediate hits may proceed together only when their data
  // reads use different word banks and both retained result slots are free.
  val requestAdmissionOpen = !io.fenceDrain && !recoveryBlocked &&
    !io.storeRequest.valid && !io.flushLine.valid
  val selectedRequestReady = selectedRequest.cacheable && requestAdmissionOpen &&
    Mux(immediateRequest, selectedImmediateSlotAvailable, missResources)
  val dualImmediateReady = dualImmediateCompatible && requestAdmissionOpen &&
    io.request(0).bits.cacheable && io.request(1).bits.cacheable &&
    immediateSlotAvailable(0) && immediateSlotAvailable(1)
  val dualHitMissResources = anyFreeWaiter &&
    (dualHitMissAnyMatchingMshr || (anyFreeMshr && dualHitMissHasVictimWay &&
      !dualHitMissVictimDirty &&
      (!dualHitMissNewNeedsL2Insert || io.l2Insert.ready)))
  val dualHitMissReady = dualHitMissCandidate && requestAdmissionOpen &&
    dualHitMissImmediateSlotAvailable && dualHitMissResources
  val dualDifferentSetMissResources = sameLineDualWaiterCredits &&
    anyFreeMshr && anySecondFreeMshr &&
    !dualDifferentSetMissAnyMatchingMshr(0) &&
    !dualDifferentSetMissAnyMatchingMshr(1) &&
    dualDifferentSetMissHasInvalidWay(0) &&
    dualDifferentSetMissHasInvalidWay(1) &&
    dualDifferentSetMissHasDistinctWays
  val dualDifferentSetMissReady = dualDifferentSetMissCandidate &&
    requestAdmissionOpen && dualDifferentSetMissResources
  val sameLineDualMissReady = sameLineDualMiss && requestAdmissionOpen &&
    sameLineDualMissResources
  for (port <- 0 until config.decodeWidth) {
    val selected = selectedRequestPort === port.U
    io.request(port).ready := Mux(dualImmediateCompatible, dualImmediateReady,
      Mux(dualHitMissReady, true.B,
        Mux(dualDifferentSetMissReady, true.B,
          Mux(sameLineDualMissReady, true.B, selected && selectedRequestReady))))
  }
  io.storeRequest.ready := !io.fenceDrain && !recoveryBlocked && !io.flushLine.valid && storeOwnerAvailable &&
    !io.storeRequest.bits.isAtomic &&
    (anyStoreCacheHit || storeMissResources)
  io.storeResult.valid := storeResultValid
  io.storeResult.bits := storeResultBits
  io.storeBusy := storeResultValid || anyMshrStorePending

  val unresolvedL2 = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && !mshrL2ProbeIssued(index) && !mshrFilled(index)))
  val hasUnresolvedL2 = unresolvedL2.asUInt.orR
  val unresolvedL2Index = PriorityEncoder(unresolvedL2.asUInt)
  io.l2Lookup.valid := hasUnresolvedL2 && !l2ProbeActive && !recoveryBlocked
  io.l2Lookup.bits.lineAddress := mshrLineAddress(unresolvedL2Index)
  io.l2Response.ready := l2ProbeActive && mshrValid(l2ProbeMshr) &&
    mshrL2ProbeIssued(l2ProbeMshr)
  when(io.l2Lookup.fire) {
    l2ProbeActive := true.B
    l2ProbeMshr := unresolvedL2Index
    mshrL2ProbeIssued(unresolvedL2Index) := true.B
  }
  when(io.l2Response.valid) {
    assert(io.l2Response.ready,
      "L1D received an L2 response without its live transfer owner")
  }

  val unissued = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrL2Resolved(index) && !mshrIssued(index) &&
      !mshrFilled(index) && mshrHasDemand(index)))
  val hasUnissued = unissued.asUInt.orR
  val unissuedIndex = PriorityEncoder(unissued.asUInt)
  io.dataRequest.valid := hasUnissued && !recoveryBlocked
  io.dataRequest.bits.client := L2DemandClient.Data.U
  io.dataRequest.bits.clientMshr := unissuedIndex
  io.dataRequest.bits.lineAddress := mshrLineAddress(unissuedIndex)

  val responseClientIsData = io.dataResponse.bits.client === L2DemandClient.Data.U
  val responseMshrInRange = responseClientIsData &&
    io.dataResponse.bits.clientMshr < mshrCount.U
  val responseMshrValid = responseMshrInRange &&
    mshrValid(io.dataResponse.bits.clientMshr) &&
    mshrIssued(io.dataResponse.bits.clientMshr) &&
    !mshrFilled(io.dataResponse.bits.clientMshr)
  io.dataResponse.ready := responseMshrValid
  when(io.dataResponse.valid) {
    assert(responseClientIsData,
      "L1D received an L2 demand response for another client")
    assert(responseMshrInRange, "L1D received an out-of-range data MSHR response")
    assert(responseMshrValid, "L1D received a response without a live MSHR owner")
  }

  var selectedWaiterValid: Bool = false.B
  var selectedWaiterIndex: UInt = 0.U(waiterWidth.W)
  var selectedWaiterAge: UInt = 0.U((config.robIndexWidth + 1).W)
  for (index <- 0 until waiterCount) {
    val candidate = waiterValid(index) && mshrValid(waiterMshr(index)) &&
      mshrFilled(waiterMshr(index))
    val age = ROBTagOrder.ageFromHead(waiterTag(index), io.robHeadTag, config)
    val take = candidate && (!selectedWaiterValid || age < selectedWaiterAge)
    selectedWaiterValid = selectedWaiterValid || candidate
    selectedWaiterIndex = Mux(take, index.U, selectedWaiterIndex)
    selectedWaiterAge = Mux(take, age, selectedWaiterAge)
  }
  val selectedWaiterMshr = waiterMshr(selectedWaiterIndex)
  val selectedWaiterAddress = mshrLineAddress(selectedWaiterMshr) +
    Cat(waiterWord(selectedWaiterIndex), 0.U(2.W))
  val selectedWaiterData = Wire(UInt(32.W))
  selectedWaiterData := 0.U
  for (way <- 0 until ways) {
    when(mshrWay(selectedWaiterMshr) === way.U) {
      selectedWaiterData := cacheData(way)(mshrSet(selectedWaiterMshr))(
        waiterWord(selectedWaiterIndex))
    }
  }

  io.completion.valid := !recoveryBlocked &&
    (selectedImmediateValid || selectedWaiterValid)
  io.completion.bits.robTag := Mux(selectedImmediateValid,
    immediateResponse(selectedImmediateIndex).robTag,
    waiterTag(selectedWaiterIndex))
  io.completion.bits.cacheData := Mux(selectedImmediateValid,
    immediateResponse(selectedImmediateIndex).cacheData,
    selectedWaiterData)
  io.completion.bits.accessFault := Mux(selectedImmediateValid,
    immediateResponse(selectedImmediateIndex).accessFault,
    mshrFault(selectedWaiterMshr))
  io.completion.bits.faultAddress := Mux(selectedImmediateValid,
    immediateResponse(selectedImmediateIndex).faultAddress,
    selectedWaiterAddress)

  val completionWaiterFire = io.completion.fire && !selectedImmediateValid &&
    selectedWaiterValid
  val waiterRemaining = (0 until mshrCount).map { mshr =>
    (0 until waiterCount).map { waiter =>
      waiterValid(waiter) && waiterMshr(waiter) === mshr.U &&
        !(completionWaiterFire && selectedWaiterIndex === waiter.U)
    }.reduce(_ || _)
  }

  when(io.flush) {
    immediateValid.foreach(_ := false.B)
    waiterValid.foreach(_ := false.B)
    for (mshr <- 0 until mshrCount) {
      // A commit-authorized store cannot be cancelled by later recovery. It
      // must finish its fill and cache update before its exact SQ result.
      when(!mshrStorePending(mshr) && !mshrIssued(mshr)) {
        when(!mshrL2ProbeIssued(mshr)) {
          mshrValid(mshr) := false.B
          mshrFilled(mshr) := false.B
          mshrL2Resolved(mshr) := false.B
        }
      }
    }
  }.elsewhen(io.squash.valid) {
    for (slot <- 0 until config.decodeWidth) {
      when(immediateValid(slot) && ROBTagOrder.isYounger(
        immediateResponse(slot).robTag, io.squash.bits, io.robHeadTag, config)) {
        immediateValid(slot) := false.B
      }
    }
    for (waiter <- 0 until waiterCount) {
      when(waiterValid(waiter) && ROBTagOrder.isYounger(
        waiterTag(waiter), io.squash.bits, io.robHeadTag, config)) {
        waiterValid(waiter) := false.B
      }
    }
    for (mshr <- 0 until mshrCount) {
      val hasSurvivor = (0 until waiterCount).map(waiter =>
        waiterValid(waiter) && waiterMshr(waiter) === mshr.U &&
          !ROBTagOrder.isYounger(waiterTag(waiter), io.squash.bits,
            io.robHeadTag, config)).reduce(_ || _)
      when(!mshrStorePending(mshr) && !mshrIssued(mshr) &&
          !mshrL2ProbeIssued(mshr) && !hasSurvivor) {
        mshrValid(mshr) := false.B
        mshrFilled(mshr) := false.B
        mshrL2Resolved(mshr) := false.B
      }
    }
  }.otherwise {
    when(io.storeResult.fire) {
      storeResultValid := false.B
    }
    when(io.completion.fire) {
      when(selectedImmediateValid) {
        immediateValid(selectedImmediateIndex) := false.B
      }.otherwise {
        waiterValid(selectedWaiterIndex) := false.B
      }
    }
    for (port <- 0 until config.decodeWidth) {
      when(io.request(port).fire && portImmediateRequest(port)) {
        immediateValid(port) := true.B
        immediateResponse(port).robTag := io.request(port).bits.robTag
        immediateResponse(port).cacheData := Mux(io.request(port).bits.requiresCache,
          portHitData(port), 0.U)
        immediateResponse(port).accessFault := false.B
        immediateResponse(port).faultAddress := io.request(port).bits.address
      }
    }
    val selectedRequestFire = selectedRequestValid && Mux(selectFirstRequest,
      io.request(0).fire, io.request(1).fire)
    val dualSameLineMissFire = sameLineDualMissReady && io.request(0).fire &&
      io.request(1).fire
    val dualHitMissFire = dualHitMissReady && io.request(0).fire &&
      io.request(1).fire
    val dualDifferentSetMissFire = dualDifferentSetMissReady &&
      io.request(0).fire && io.request(1).fire
    val selectedMissFire = selectedRequestFire && !immediateRequest &&
      !dualSameLineMissFire && !dualHitMissFire && !dualDifferentSetMissFire
    when(dualDifferentSetMissFire) {
      for (port <- 0 until config.decodeWidth) {
        val allocatedMshr = if (port == 0) freeMshrIndex else secondFreeMshrIndex
        val allocatedWaiter = if (port == 0) freeWaiterIndex else secondFreeWaiterIndex
        waiterValid(allocatedWaiter) := true.B
        waiterMshr(allocatedWaiter) := allocatedMshr
        waiterTag(allocatedWaiter) := io.request(port).bits.robTag
        waiterWord(allocatedWaiter) := portWord(port)
        mshrValid(allocatedMshr) := true.B
        mshrIssued(allocatedMshr) := false.B
        mshrFilled(allocatedMshr) := false.B
        mshrFault(allocatedMshr) := false.B
        mshrL2ProbeIssued(allocatedMshr) := false.B
        mshrL2Resolved(allocatedMshr) := false.B
        mshrLineAddress(allocatedMshr) := portLineAddress(port)
        mshrSet(allocatedMshr) := portSet(port)
        mshrWay(allocatedMshr) := dualDifferentSetMissAllocatedWay(port)
        for (way <- 0 until ways) {
          when(dualDifferentSetMissAllocatedWay(port) === way.U) {
            cacheValid(way)(portSet(port)) := false.B
            cacheDirty(way)(portSet(port)) := false.B
          }
        }
      }
    }.elsewhen(dualSameLineMissFire || dualHitMissFire || selectedMissFire) {
      val chosenMshr = Mux(dualHitMissFire,
        Mux(dualHitMissAnyMatchingMshr, dualHitMissMatchingMshrIndex, freeMshrIndex),
        Mux(anyMatchingMshr, matchingMshrIndex, freeMshrIndex))
      when(dualSameLineMissFire) {
        waiterValid(freeWaiterIndex) := true.B
        waiterMshr(freeWaiterIndex) := chosenMshr
        waiterTag(freeWaiterIndex) := io.request(0).bits.robTag
        waiterWord(freeWaiterIndex) := portWord(0)
        waiterValid(secondFreeWaiterIndex) := true.B
        waiterMshr(secondFreeWaiterIndex) := chosenMshr
        waiterTag(secondFreeWaiterIndex) := io.request(1).bits.robTag
        waiterWord(secondFreeWaiterIndex) := portWord(1)
      }.elsewhen(dualHitMissFire) {
        waiterValid(freeWaiterIndex) := true.B
        waiterMshr(freeWaiterIndex) := chosenMshr
        waiterTag(freeWaiterIndex) := dualHitMissTag
        waiterWord(freeWaiterIndex) := dualHitMissWord
      }.otherwise {
        waiterValid(freeWaiterIndex) := true.B
        waiterMshr(freeWaiterIndex) := chosenMshr
        waiterTag(freeWaiterIndex) := selectedRequest.robTag
        waiterWord(freeWaiterIndex) := requestWord
      }
      val createsMshr = Mux(dualHitMissFire, !dualHitMissAnyMatchingMshr,
        !anyMatchingMshr)
      val allocatedLineAddress = Mux(dualHitMissFire, dualHitMissLineAddress,
        requestLineAddress)
      val allocatedSet = Mux(dualHitMissFire, dualHitMissSet, requestSet)
      val allocatedWay = Mux(dualHitMissFire, dualHitMissWay, victimWay)
      when(createsMshr) {
        mshrValid(chosenMshr) := true.B
        mshrIssued(chosenMshr) := false.B
        mshrFilled(chosenMshr) := false.B
        mshrFault(chosenMshr) := false.B
        mshrL2ProbeIssued(chosenMshr) := false.B
        mshrL2Resolved(chosenMshr) := false.B
        mshrLineAddress(chosenMshr) := allocatedLineAddress
        mshrSet(chosenMshr) := allocatedSet
        mshrWay(chosenMshr) := allocatedWay
        // The old line cannot remain hit-visible while its way is reserved
        // for an in-flight refill.
        for (way <- 0 until ways) {
          when(allocatedWay === way.U) {
            cacheValid(way)(allocatedSet) := false.B
            cacheDirty(way)(allocatedSet) := false.B
          }
        }
      }
    }
    when(io.storeRequest.fire) {
      assert(io.storeRequest.bits.pmaKind === PMARegionKind.Memory.code.U,
        "L1D store path accepted a non-memory PMA effect")
      assert(!io.storeRequest.bits.isAtomic,
        "L1D store path cannot accept an atomic effect")
      when(anyStoreCacheHit) {
        for (way <- 0 until ways) {
          when(storeHitWay === way.U) {
            cacheDirty(way)(storeSet) := true.B
            for (word <- 0 until wordsPerLine) {
              when(storeWord === word.U) {
                cacheData(way)(storeSet)(word) := MemoryByteLanes.merge(
                  cacheData(way)(storeSet)(word), io.storeRequest.bits.writeData,
                  io.storeRequest.bits.writeMask)
              }
            }
          }
        }
        storeResultValid := true.B
        storeResultBits.robTag := io.storeRequest.bits.robTag
        storeResultBits.address := io.storeRequest.bits.address
        storeResultBits.accessFault := false.B
      }.otherwise {
        val chosenMshr = Mux(anyStoreMatchingMshr, storeMatchingMshrIndex,
          freeMshrIndex)
        mshrStorePending(chosenMshr) := true.B
        mshrStoreEffect(chosenMshr) := io.storeRequest.bits
        when(!anyStoreMatchingMshr) {
          mshrValid(chosenMshr) := true.B
          mshrIssued(chosenMshr) := false.B
          mshrFilled(chosenMshr) := false.B
          mshrFault(chosenMshr) := false.B
          mshrL2ProbeIssued(chosenMshr) := false.B
          mshrL2Resolved(chosenMshr) := false.B
          mshrLineAddress(chosenMshr) := storeLineAddress
          mshrSet(chosenMshr) := storeSet
          mshrWay(chosenMshr) := storeVictimWay
          for (way <- 0 until ways) {
            when(storeVictimWay === way.U) {
              cacheValid(way)(storeSet) := false.B
              cacheDirty(way)(storeSet) := false.B
            }
          }
        }
      }
    }
  }

  when(io.dataRequest.fire) {
    mshrIssued(unissuedIndex) := true.B
  }
  when(io.dataResponse.fire) {
    val responseMshr = io.dataResponse.bits.clientMshr
    val responseHasStore = mshrStorePending(responseMshr)
    val responseStore = mshrStoreEffect(responseMshr)
    mshrFilled(responseMshr) := true.B
    mshrFault(responseMshr) := io.dataResponse.bits.accessFault
    when(responseHasStore) {
      mshrStorePending(responseMshr) := false.B
      storeResultValid := true.B
      storeResultBits.robTag := responseStore.robTag
      storeResultBits.address := responseStore.address
      storeResultBits.accessFault := io.dataResponse.bits.accessFault
    }
    when(!io.dataResponse.bits.accessFault) {
      val fillWay = mshrWay(responseMshr)
      val fillSet = mshrSet(responseMshr)
      for (way <- 0 until ways) {
        when(fillWay === way.U) {
          cacheValid(way)(fillSet) := true.B
          cacheDirty(way)(fillSet) := responseHasStore
          cacheTag(way)(fillSet) := mshrLineAddress(responseMshr)(31,
            lineOffsetWidth + setWidth)
          for (word <- 0 until wordsPerLine) {
            cacheData(way)(fillSet)(word) := Mux(responseHasStore &&
              responseStore.address(lineOffsetWidth - 1, 2) === word.U,
              MemoryByteLanes.merge(io.dataResponse.bits.lineData(word),
                responseStore.writeData, responseStore.writeMask),
              io.dataResponse.bits.lineData(word))
          }
        }
      }
    }
  }
  when(io.l2Response.fire) {
    val responseMshr = l2ProbeMshr
    val responseHasStore = mshrStorePending(responseMshr)
    val responseStore = mshrStoreEffect(responseMshr)
    l2ProbeActive := false.B
    mshrL2Resolved(responseMshr) := true.B
    when(io.l2Response.bits.hit) {
      mshrFilled(responseMshr) := true.B
      mshrFault(responseMshr) := false.B
      when(responseHasStore) {
        mshrStorePending(responseMshr) := false.B
        storeResultValid := true.B
        storeResultBits.robTag := responseStore.robTag
        storeResultBits.address := responseStore.address
        storeResultBits.accessFault := false.B
      }
      val fillWay = mshrWay(responseMshr)
      val fillSet = mshrSet(responseMshr)
      for (way <- 0 until ways) {
        when(fillWay === way.U) {
          cacheValid(way)(fillSet) := true.B
          cacheDirty(way)(fillSet) := io.l2Response.bits.transfer.dirty ||
            responseHasStore
          cacheTag(way)(fillSet) := mshrLineAddress(responseMshr)(31,
            lineOffsetWidth + setWidth)
          for (word <- 0 until wordsPerLine) {
            cacheData(way)(fillSet)(word) := Mux(responseHasStore &&
              responseStore.address(lineOffsetWidth - 1, 2) === word.U,
              MemoryByteLanes.merge(io.l2Response.bits.transfer.lineData(word),
                responseStore.writeData, responseStore.writeMask),
              io.l2Response.bits.transfer.lineData(word))
          }
        }
      }
    }
  }
  when(io.atomicInvalidate.valid) {
    val atomicSet = io.atomicInvalidate.bits(lineOffsetWidth + setWidth - 1,
      lineOffsetWidth)
    val atomicTag = io.atomicInvalidate.bits(31, lineOffsetWidth + setWidth)
    for (way <- 0 until ways) {
      when(cacheValid(way)(atomicSet) && cacheTag(way)(atomicSet) === atomicTag) {
        assert(!cacheDirty(way)(atomicSet),
          "atomic invalidation cannot discard a dirty L1D line")
        cacheValid(way)(atomicSet) := false.B
        cacheDirty(way)(atomicSet) := false.B
      }
    }
  }
  when(io.flushLine.fire || (fenceL2Insert && io.l2Insert.fire)) {
    val selectedFlushWay = Mux(fenceL2Insert, fenceDirtyWay, flushWay)
    val selectedFlushSet = Mux(fenceL2Insert, fenceDirtySet, flushSet)
    val selectedFlushAddress = Cat(cacheTag(selectedFlushWay)(selectedFlushSet),
      selectedFlushSet, 0.U(lineOffsetWidth.W))
    assert(selectedFlushAddress(lineOffsetWidth - 1, 0) === 0.U,
      "L1D exact-line flush must use a line-aligned address")
    for (way <- 0 until ways) {
      when(selectedFlushWay === way.U) {
        cacheValid(way)(selectedFlushSet) := false.B
        cacheDirty(way)(selectedFlushSet) := false.B
      }
    }
  }
  for (mshr <- 0 until mshrCount) {
    when(mshrValid(mshr) && mshrFilled(mshr) && !waiterRemaining(mshr) &&
        !mshrStorePending(mshr)) {
      mshrValid(mshr) := false.B
      mshrIssued(mshr) := false.B
      mshrFilled(mshr) := false.B
      mshrL2ProbeIssued(mshr) := false.B
      mshrL2Resolved(mshr) := false.B
    }
    when(mshrValid(mshr) && mshrL2Resolved(mshr) && !mshrIssued(mshr) &&
        !mshrFilled(mshr) && !mshrHasDemand(mshr)) {
      mshrValid(mshr) := false.B
      mshrL2ProbeIssued(mshr) := false.B
      mshrL2Resolved(mshr) := false.B
    }
  }

  val selectedRequestFire = selectedRequestValid && Mux(selectFirstRequest,
    io.request(0).fire, io.request(1).fire)
  val dualSameLineMissFire = sameLineDualMissReady && io.request(0).fire &&
    io.request(1).fire
  val dualHitMissFire = dualHitMissReady && io.request(0).fire &&
    io.request(1).fire
  val dualDifferentSetMissFire = dualDifferentSetMissReady &&
    io.request(0).fire && io.request(1).fire
  val selectedMissFire = selectedRequestFire && !immediateRequest &&
    !dualSameLineMissFire && !dualHitMissFire && !dualDifferentSetMissFire
  when(selectedMissFire) {
    assert(anyFreeWaiter, "L1D accepted a miss without a waiter owner")
    assert(anyMatchingMshr || (anyFreeMshr && hasVictimWay),
      "L1D accepted a miss without an MSHR and victim owner")
  }
  when(dualSameLineMissFire) {
    assert(sameLineDualWaiterCredits,
      "L1D accepted a same-line pair without two exact waiter credits")
    assert(portLineAddress(0) === portLineAddress(1),
      "L1D dual-miss admission must merge only one exact line")
    assert(anyMatchingMshr || (anyFreeMshr && hasVictimWay),
      "L1D accepted a merged miss without one MSHR and victim owner")
  }
  when(dualHitMissFire) {
    assert(dualHitMissCandidate && dualHitMissImmediateSlotAvailable,
      "L1D accepted a dual hit/miss pair without its retained hit owner")
    assert(dualHitMissResources,
      "L1D accepted a dual hit/miss pair without an exact miss owner")
    assert(dualHitMissAnyMatchingMshr || dualHitMissHasInvalidWay ||
      (dualHitMissHasVictimWay && !dualHitMissVictimDirty && io.l2Insert.fire),
      "L1D dual hit/miss allocation requires a merge, invalid way, or clean victim transfer")
  }
  when(dualDifferentSetMissFire) {
    assert(dualDifferentSetMissResources,
      "L1D accepted dual misses without two exact MSHR/waiter/way credits")
    assert(freeMshrIndex =/= secondFreeMshrIndex,
      "L1D dual misses allocated one MSHR to two independent lines")
    when(portSet(0) === portSet(1)) {
      assert(dualDifferentSetMissHasDistinctWays,
        "L1D same-set dual misses require two independent invalid ways")
    }
  }
  for (port <- 0 until config.decodeWidth) {
    when(io.request(port).fire) {
      assert(io.request(port).bits.cacheable,
        "the executable L1D slice accepted a non-cacheable M0 owner")
    }
  }
  when(io.storeRequest.fire && !anyStoreCacheHit) {
    assert(anyStoreMatchingMshr ||
      (!anyStoreLineMshr && anyFreeMshr && storeHasVictimWay),
      "L1D accepted a store miss without an MSHR and victim owner")
  }
  for (waiter <- 0 until waiterCount) {
    when(waiterValid(waiter)) {
      assert(mshrValid(waiterMshr(waiter)),
        "L1D waiter referenced a non-live MSHR")
    }
  }
}
