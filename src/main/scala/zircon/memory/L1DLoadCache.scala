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

  require(cache.bytes == 1024 && ways == 2 && cache.lineBytes == 32,
    "the frozen M3 L1D load slice is 1 KiB, two-way, and 32-byte-line")
  require(mshrCount == 4 && waiterCount == 8,
    "the frozen M3 load slice has four MSHRs and eight LQ waiters")

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new LoadStoreForward(config)))
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

  val immediateValid = RegInit(false.B)
  val immediateResponse = Reg(new LoadCompletion(config))
  val storeResultValid = RegInit(false.B)
  val storeResultBits = Reg(new StoreWriteResult(config))
  val l2ProbeActive = RegInit(false.B)
  val l2ProbeMshr = Reg(UInt(mshrWidth.W))
  val recoveryBlocked = io.flush || io.squash.valid

  val requestSet = io.request.bits.address(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val requestTag = io.request.bits.address(31, lineOffsetWidth + setWidth)
  val requestWord = io.request.bits.address(lineOffsetWidth - 1, 2)
  val requestLineAddress = Cat(io.request.bits.address(31, lineOffsetWidth),
    0.U(lineOffsetWidth.W))
  val cacheHit = VecInit((0 until ways).map(way =>
    cacheValid(way)(requestSet) && cacheTag(way)(requestSet) === requestTag))
  val anyCacheHit = cacheHit.asUInt.orR
  val hitWay = PriorityEncoder(cacheHit.asUInt)
  val hitData = Wire(UInt(32.W))
  hitData := 0.U
  for (way <- 0 until ways) {
    when(cacheHit(way)) {
      hitData := cacheData(way)(requestSet)(requestWord)
    }
  }

  val matchingMshr = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && mshrLineAddress(index) === requestLineAddress))
  val anyMatchingMshr = matchingMshr.asUInt.orR
  val matchingMshrIndex = PriorityEncoder(matchingMshr.asUInt)
  val freeMshr = VecInit((0 until mshrCount).map(index => !mshrValid(index)))
  val anyFreeMshr = freeMshr.asUInt.orR
  val freeMshrIndex = PriorityEncoder(freeMshr.asUInt)
  val freeWaiter = (~waiterValid.asUInt)(waiterCount - 1, 0)
  val anyFreeWaiter = freeWaiter.orR
  val freeWaiterIndex = PriorityEncoder(freeWaiter)

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
  val fenceCanTransfer = io.fenceDrain && !anyMshrValid && !storeResultValid &&
    !immediateValid && !l2ProbeActive
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
  io.fenceDrained := !anyMshrValid && !storeResultValid && !immediateValid &&
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

  val immediateRequest = !io.request.bits.requiresCache ||
    anyCacheHit
  val immediateAvailable = !immediateValid || io.completion.ready
  val newMissNeedsL2Insert = !anyMatchingMshr && anyFreeMshr && hasVictimWay &&
    victimValid
  val storeNewMissNeedsL2Insert = !anyStoreLineMshr && anyFreeMshr &&
    storeHasVictimWay && storeVictimValid
  val loadL2Insert = io.request.valid && io.request.bits.cacheable &&
    !io.storeRequest.valid && !io.flushLine.valid && !io.fenceDrain && !recoveryBlocked && !immediateRequest &&
    anyFreeWaiter && newMissNeedsL2Insert
  val storeL2Insert = io.storeRequest.valid && storeOwnerAvailable &&
    !io.flushLine.valid && !io.fenceDrain && !recoveryBlocked &&
    !anyStoreCacheHit && !anyStoreLineMshr && anyFreeMshr &&
    storeHasVictimWay && storeNewMissNeedsL2Insert
  val l2InsertForStore = !flushL2Insert && !fenceL2Insert && storeL2Insert
  val l2InsertWay = Mux(flushL2Insert, flushWay,
    Mux(fenceL2Insert, fenceDirtyWay, Mux(l2InsertForStore, storeVictimWay, victimWay)))
  val l2InsertSet = Mux(flushL2Insert, flushSet,
    Mux(fenceL2Insert, fenceDirtySet, Mux(l2InsertForStore, storeSet, requestSet)))
  io.l2Insert.valid := flushL2Insert || fenceL2Insert || loadL2Insert || storeL2Insert
  io.l2Insert.bits.lineAddress := Cat(cacheTag(l2InsertWay)(l2InsertSet), l2InsertSet,
    0.U(lineOffsetWidth.W))
  io.l2Insert.bits.dirty := cacheDirty(l2InsertWay)(l2InsertSet)
  for (word <- 0 until wordsPerLine) {
    io.l2Insert.bits.lineData(word) := cacheData(l2InsertWay)(l2InsertSet)(word)
  }
  io.flushLine.ready := flushL2Insert && io.l2Insert.ready
  val missResources = anyFreeWaiter && (anyMatchingMshr ||
    (anyFreeMshr && hasVictimWay && (!newMissNeedsL2Insert || io.l2Insert.ready)))
  val storeMissResources = anyStoreMatchingMshr ||
    (!anyStoreLineMshr && anyFreeMshr && storeHasVictimWay &&
      (!storeNewMissNeedsL2Insert || io.l2Insert.ready))
  // Device and atomic requests retain their ordered M0 owner. Only a
  // commit-authorized cacheable non-atomic store may change L1D state.
  io.request.ready := io.request.bits.cacheable && !io.fenceDrain && !recoveryBlocked &&
    !io.storeRequest.valid && !io.flushLine.valid &&
    Mux(immediateRequest, immediateAvailable, missResources)
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
    (immediateValid || selectedWaiterValid)
  io.completion.bits.robTag := Mux(immediateValid, immediateResponse.robTag,
    waiterTag(selectedWaiterIndex))
  io.completion.bits.cacheData := Mux(immediateValid, immediateResponse.cacheData,
    selectedWaiterData)
  io.completion.bits.accessFault := Mux(immediateValid,
    immediateResponse.accessFault, mshrFault(selectedWaiterMshr))
  io.completion.bits.faultAddress := Mux(immediateValid,
    immediateResponse.faultAddress, selectedWaiterAddress)

  val completionWaiterFire = io.completion.fire && !immediateValid &&
    selectedWaiterValid
  val waiterRemaining = (0 until mshrCount).map { mshr =>
    (0 until waiterCount).map { waiter =>
      waiterValid(waiter) && waiterMshr(waiter) === mshr.U &&
        !(completionWaiterFire && selectedWaiterIndex === waiter.U)
    }.reduce(_ || _)
  }

  when(io.flush) {
    immediateValid := false.B
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
    when(immediateValid && ROBTagOrder.isYounger(
      immediateResponse.robTag, io.squash.bits, io.robHeadTag, config)) {
      immediateValid := false.B
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
      when(immediateValid) {
        immediateValid := false.B
      }.otherwise {
        waiterValid(selectedWaiterIndex) := false.B
      }
    }
    when(io.request.fire) {
      when(immediateRequest) {
        immediateValid := true.B
        immediateResponse.robTag := io.request.bits.robTag
        immediateResponse.cacheData := Mux(io.request.bits.requiresCache,
          hitData, 0.U)
        immediateResponse.accessFault := false.B
        immediateResponse.faultAddress := io.request.bits.address
      }.otherwise {
        val chosenMshr = Mux(anyMatchingMshr, matchingMshrIndex, freeMshrIndex)
        waiterValid(freeWaiterIndex) := true.B
        waiterMshr(freeWaiterIndex) := chosenMshr
        waiterTag(freeWaiterIndex) := io.request.bits.robTag
        waiterWord(freeWaiterIndex) := requestWord
        when(!anyMatchingMshr) {
          mshrValid(chosenMshr) := true.B
          mshrIssued(chosenMshr) := false.B
          mshrFilled(chosenMshr) := false.B
          mshrFault(chosenMshr) := false.B
          mshrL2ProbeIssued(chosenMshr) := false.B
          mshrL2Resolved(chosenMshr) := false.B
          mshrLineAddress(chosenMshr) := requestLineAddress
          mshrSet(chosenMshr) := requestSet
          mshrWay(chosenMshr) := victimWay
          // The old line cannot remain hit-visible while its way is reserved
          // for an in-flight refill.
          for (way <- 0 until ways) {
            when(victimWay === way.U) {
              cacheValid(way)(requestSet) := false.B
              cacheDirty(way)(requestSet) := false.B
            }
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

  when(io.request.fire && !immediateRequest) {
    assert(anyFreeWaiter, "L1D accepted a miss without a waiter owner")
    assert(anyMatchingMshr || (anyFreeMshr && hasVictimWay),
      "L1D accepted a miss without an MSHR and victim owner")
  }
  when(io.request.fire) {
    assert(io.request.bits.cacheable,
      "the executable L1D slice accepted a non-cacheable M0 owner")
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
