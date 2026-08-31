package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Read-only executable slice of the frozen 1 KiB, two-way M3 L1D.
  *
  * Stores, atomics, dirty eviction, L2 transfer, and device traffic remain
  * outside this module. Four miss owners and eight load waiters make cacheable
  * loads live without treating an AXI response as a fabricated completion.
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
    val dataRequest = Decoupled(new DataReadRequest(config))
    val dataResponse = Flipped(Decoupled(new DataReadResponse(config)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val cacheValid = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val cacheTag = Reg(Vec(ways, Vec(sets, UInt(tagWidth.W))))
  val cacheData = Reg(Vec(ways, Vec(sets, Vec(wordsPerLine, UInt(32.W)))))

  val mshrValid = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrIssued = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrFilled = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrFault = RegInit(VecInit.fill(mshrCount)(false.B))
  val mshrLineAddress = Reg(Vec(mshrCount, UInt(32.W)))
  val mshrSet = Reg(Vec(mshrCount, UInt(setWidth.W)))
  val mshrWay = Reg(Vec(mshrCount, UInt(log2Ceil(ways).W)))

  val waiterValid = RegInit(VecInit.fill(waiterCount)(false.B))
  val waiterMshr = Reg(Vec(waiterCount, UInt(mshrWidth.W)))
  val waiterTag = Reg(Vec(waiterCount, UInt(config.robTagWidth.W)))
  val waiterWord = Reg(Vec(waiterCount, UInt(log2Ceil(wordsPerLine).W)))

  val immediateValid = RegInit(false.B)
  val immediateResponse = Reg(new LoadCompletion(config))
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

  val immediateRequest = !io.request.bits.requiresCache || anyCacheHit
  val immediateAvailable = !immediateValid || io.completion.ready
  val missResources = anyFreeWaiter && (anyMatchingMshr ||
    (anyFreeMshr && hasVictimWay))
  io.request.ready := !recoveryBlocked && Mux(immediateRequest,
    immediateAvailable, missResources)

  val unissued = VecInit((0 until mshrCount).map(index =>
    mshrValid(index) && !mshrIssued(index)))
  val hasUnissued = unissued.asUInt.orR
  val unissuedIndex = PriorityEncoder(unissued.asUInt)
  io.dataRequest.valid := hasUnissued && !recoveryBlocked
  io.dataRequest.bits.mshr := unissuedIndex
  io.dataRequest.bits.lineAddress := mshrLineAddress(unissuedIndex)

  val responseMshrInRange = io.dataResponse.bits.mshr < mshrCount.U
  val responseMshrValid = responseMshrInRange &&
    mshrValid(io.dataResponse.bits.mshr) &&
    mshrIssued(io.dataResponse.bits.mshr) &&
    !mshrFilled(io.dataResponse.bits.mshr)
  io.dataResponse.ready := responseMshrValid
  when(io.dataResponse.valid) {
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
      when(!mshrIssued(mshr)) {
        mshrValid(mshr) := false.B
        mshrFilled(mshr) := false.B
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
      when(!mshrIssued(mshr) && !hasSurvivor) {
        mshrValid(mshr) := false.B
        mshrFilled(mshr) := false.B
      }
    }
  }.otherwise {
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
          mshrLineAddress(chosenMshr) := requestLineAddress
          mshrSet(chosenMshr) := requestSet
          mshrWay(chosenMshr) := victimWay
          // The old line cannot remain hit-visible while its way is reserved
          // for an in-flight refill.
          for (way <- 0 until ways) {
            when(victimWay === way.U) {
              cacheValid(way)(requestSet) := false.B
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
    val responseMshr = io.dataResponse.bits.mshr
    mshrFilled(responseMshr) := true.B
    mshrFault(responseMshr) := io.dataResponse.bits.accessFault
    when(!io.dataResponse.bits.accessFault) {
      val fillWay = mshrWay(responseMshr)
      val fillSet = mshrSet(responseMshr)
      for (way <- 0 until ways) {
        when(fillWay === way.U) {
          cacheValid(way)(fillSet) := true.B
          cacheTag(way)(fillSet) := mshrLineAddress(responseMshr)(31,
            lineOffsetWidth + setWidth)
          for (word <- 0 until wordsPerLine) {
            cacheData(way)(fillSet)(word) := io.dataResponse.bits.lineData(word)
          }
        }
      }
    }
  }
  for (mshr <- 0 until mshrCount) {
    when(mshrValid(mshr) && mshrFilled(mshr) && !waiterRemaining(mshr)) {
      mshrValid(mshr) := false.B
      mshrIssued(mshr) := false.B
      mshrFilled(mshr) := false.B
    }
  }

  when(io.request.fire && !immediateRequest) {
    assert(anyFreeWaiter, "L1D accepted a miss without a waiter owner")
    assert(anyMatchingMshr || (anyFreeMshr && hasVictimWay),
      "L1D accepted a miss without an MSHR and victim owner")
  }
  for (waiter <- 0 until waiterCount) {
    when(waiterValid(waiter)) {
      assert(mshrValid(waiterMshr(waiter)),
        "L1D waiter referenced a non-live MSHR")
    }
  }
}
