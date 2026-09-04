package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.{FetchFault, InstructionFetchPacket, InstructionFetchWord}

object L1InstructionCacheState extends ChiselEnum {
  val Idle, Lookup, L2Lookup, L2Response, Demand, Refill, Present, Drain,
    L2Drain = Value
}

/** One-MSHR, non-inclusive instruction cache for the executable M3 frontend.
  *
  * The cache never owns raw AXI traffic. A miss is a retained Instruction
  * client request to the four-owner L2 demand engine, so a redirect can cancel
  * only work before that request handshakes and must drain it afterwards.
  */
class L1InstructionCache(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val cache = config.l1i
  private val ways = cache.ways
  private val wordsPerLine = cache.lineBytes / 4
  private val lineOffsetWidth = log2Ceil(cache.lineBytes)
  private val sets = cache.bytes / (ways * cache.lineBytes)
  private val setWidth = log2Ceil(sets)
  private val tagWidth = 32 - lineOffsetWidth - setWidth
  private val countWidth = log2Ceil(config.fetchWidth + 1)

  require(cache.bytes == 1024 && ways == 2 && cache.lineBytes == 32,
    "the frozen M3 L1I is 1 KiB, two-way, and has 32-byte lines")
  require(cache.mshrs == 1, "the frozen M3 L1I owns one local MSHR")
  require(wordsPerLine == 8 && config.fetchWidth == 4,
    "L1I packet and refill geometry are frozen")

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val redirect = Input(Valid(UInt(32.W)))
    val invalidate = Input(Bool())
    val response = Decoupled(new InstructionFetchPacket(config))
    val responseNextPc = Input(UInt(32.W))
    /** Allows a packet handoff to launch the next lookup without an idle bubble. */
    val continueAfterResponse = Input(Bool())
    /** Allows a sequential next-line lookahead while the current packet waits. */
    val lookaheadEnable = Input(Bool())
    /** Read-only resident-L2 probe before a demand AXI refill. */
    val l2Lookup = Decoupled(UInt(32.W))
    val l2LookupResponse = Flipped(Decoupled(new L2InstructionLookupResponse(config)))
    val l2Request = Decoupled(new L2DemandRequest(config))
    val l2Response = Flipped(Decoupled(new L2DemandResponse(config)))
    /** Successful AXI refills must allocate or merge in L2 before L1I can
      * install them. A merge returns the newer resident L2 line. */
    val l2Insert = Decoupled(new CacheLineTransfer(config))
    val l2InsertHit = Input(Bool())
    val l2InsertData = Input(Vec(config.l2.lineBytes / 4, UInt(32.W)))
    val currentPc = Output(UInt(32.W))
    val busy = Output(Bool())
    val draining = Output(Bool())
    /** No accepted instruction demand or lookahead owner remains. */
    val coherenceDrained = Output(Bool())
  })

  val state = RegInit(L1InstructionCacheState.Idle)
  val pc = RegInit(config.resetVector.U(32.W))
  // Predictors observe the response payload even while response.valid is
  // low.  Initialize the retained request fields so their architectural
  // alignment invariants hold from reset rather than depending on simulator
  // treatment of uninitialized registers.
  val requestBase = RegInit(config.resetVector.U(32.W))
  val requestCount = RegInit(0.U(countWidth.W))
  val requestLine = RegInit(0.U(32.W))
  val missWay = Reg(UInt(log2Ceil(ways).W))
  val packetWords = Reg(Vec(config.fetchWidth, new InstructionFetchWord))
  val lookaheadIssued = RegInit(false.B)
  val lookaheadInFlight = RegInit(false.B)
  val lookaheadLine = Reg(UInt(32.W))
  val lookaheadSet = Reg(UInt(setWidth.W))
  val lookaheadTag = Reg(UInt(tagWidth.W))
  val lookaheadWay = Reg(UInt(log2Ceil(ways).W))

  val cacheValid = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val cacheTag = Reg(Vec(ways, Vec(sets, UInt(tagWidth.W))))
  val cacheDataMemories = Seq.fill(ways)(Module(new L2LineMemory(
    sets, wordsPerLine * 32)))
  val dataReadSet = WireDefault(0.U(setWidth.W))
  val dataReadWay = WireDefault(0.U(log2Ceil(ways).W))
  val dataRead = MuxLookup(dataReadWay, 0.U((wordsPerLine * 32).W))(
    (0 until ways).map(way => way.U -> cacheDataMemories(way).io.readData))
  val dataReadWords = dataRead.asTypeOf(Vec(wordsPerLine, UInt(32.W)))
  val dataWriteEnable = WireDefault(false.B)
  val dataWriteWay = WireDefault(0.U(log2Ceil(ways).W))
  val dataWriteSet = WireDefault(0.U(setWidth.W))
  val dataWriteData = WireDefault(0.U((wordsPerLine * 32).W))
  for (way <- 0 until ways) {
    cacheDataMemories(way).io.clk := clock
    cacheDataMemories(way).io.readEnable := true.B
    cacheDataMemories(way).io.readAddress := dataReadSet
    cacheDataMemories(way).io.writeEnable :=
      dataWriteEnable && dataWriteWay === way.U
    cacheDataMemories(way).io.writeAddress := dataWriteSet
    cacheDataMemories(way).io.writeData := dataWriteData
  }
  val replacement = RegInit(VecInit.fill(sets)(false.B))

  val requestSet = requestBase(lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val requestTag = requestBase(31, lineOffsetWidth + setWidth)
  val requestWord = requestBase(lineOffsetWidth - 1, 2)
  val hitWays = VecInit((0 until ways).map(way =>
    cacheValid(way)(requestSet) && cacheTag(way)(requestSet) === requestTag))
  val cacheHit = hitWays.asUInt.orR
  val hitWay = PriorityEncoder(hitWays.asUInt)
  // The line store has a registered read address. Prepare the address during
  // the cycle that captures a request (and during a no-idle continuation) so
  // Lookup can consume the returned line without exposing a HitRead bubble.
  val pcSet = pc(lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val pcTag = pc(31, lineOffsetWidth + setWidth)
  val pcHitWays = VecInit((0 until ways).map(way =>
    cacheValid(way)(pcSet) && cacheTag(way)(pcSet) === pcTag))
  val pcHitWay = PriorityEncoder(pcHitWays.asUInt)
  val continuationSet = io.responseNextPc(
    lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val continuationTag = io.responseNextPc(31, lineOffsetWidth + setWidth)
  val continuationHitWays = VecInit((0 until ways).map(way =>
    cacheValid(way)(continuationSet) &&
      cacheTag(way)(continuationSet) === continuationTag))
  val continuationHitWay = PriorityEncoder(continuationHitWays.asUInt)
  dataReadSet := Mux(state === L1InstructionCacheState.Idle, pcSet,
    Mux(state === L1InstructionCacheState.Present && io.response.fire &&
      io.continueAfterResponse, continuationSet, requestSet))
  dataReadWay := Mux(state === L1InstructionCacheState.Idle, pcHitWay,
    Mux(state === L1InstructionCacheState.Present && io.response.fire &&
      io.continueAfterResponse, continuationHitWay, hitWay))
  val invalidWays = VecInit((0 until ways).map(way => !cacheValid(way)(requestSet)))
  val victimWay = Mux(invalidWays.asUInt.orR, PriorityEncoder(invalidWays.asUInt),
    replacement(requestSet).asUInt)

  val wordsUntilLine = wordsPerLine.U - pc(lineOffsetWidth - 1, 2)
  val nextCount = Mux(wordsUntilLine < config.fetchWidth.U,
    wordsUntilLine, config.fetchWidth.U)
  val continuedWordsUntilLine = wordsPerLine.U -
    io.responseNextPc(lineOffsetWidth - 1, 2)
  val continuedCount = Mux(continuedWordsUntilLine < config.fetchWidth.U,
    continuedWordsUntilLine, config.fetchWidth.U)
  val sequentialLookaheadLine = requestLine + cache.lineBytes.U(32.W)
  val sequentialLookaheadSet = sequentialLookaheadLine(
    lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val sequentialLookaheadTag = sequentialLookaheadLine(
    31, lineOffsetWidth + setWidth)
  val sequentialLookaheadHitWays = VecInit((0 until ways).map(way =>
    cacheValid(way)(sequentialLookaheadSet) &&
      cacheTag(way)(sequentialLookaheadSet) === sequentialLookaheadTag))
  val sequentialLookaheadInvalidWays = VecInit((0 until ways).map(way =>
    !cacheValid(way)(sequentialLookaheadSet)))
  val sequentialLookaheadVictim = Mux(
    sequentialLookaheadInvalidWays.asUInt.orR,
    PriorityEncoder(sequentialLookaheadInvalidWays.asUInt),
    replacement(sequentialLookaheadSet).asUInt)
  val sequentialLookaheadNeeded = state === L1InstructionCacheState.Present &&
    io.lookaheadEnable && !lookaheadIssued && !lookaheadInFlight &&
    requestBase(lineOffsetWidth - 1, 2) === 0.U &&
    requestCount === config.fetchWidth.U &&
    !sequentialLookaheadHitWays.asUInt.orR

  val normalDemand = state === L1InstructionCacheState.Demand
  val responseOwnsRefill = state === L1InstructionCacheState.Refill ||
    lookaheadInFlight
  val responseNeedsL2Insert = responseOwnsRefill && io.l2Response.valid &&
    !io.redirect.valid && !io.l2Response.bits.accessFault
  io.l2Lookup.valid := state === L1InstructionCacheState.L2Lookup &&
    !io.redirect.valid
  io.l2Lookup.bits := requestLine
  io.l2LookupResponse.ready := state === L1InstructionCacheState.L2Response ||
    state === L1InstructionCacheState.L2Drain
  io.l2Request.valid := (normalDemand || sequentialLookaheadNeeded) &&
    !io.redirect.valid
  io.l2Request.bits.client := L2DemandClient.Instruction.U
  io.l2Request.bits.clientMshr := 0.U
  io.l2Request.bits.lineAddress := Mux(normalDemand, requestLine,
    sequentialLookaheadLine)
  io.l2Insert.valid := responseNeedsL2Insert
  io.l2Insert.bits.lineAddress := Mux(state === L1InstructionCacheState.Refill,
    requestLine, lookaheadLine)
  io.l2Insert.bits.dirty := false.B
  for (word <- 0 until wordsPerLine) {
    io.l2Insert.bits.lineData(word) := io.l2Response.bits.lineData(word)
  }
  io.l2Response.ready := (state === L1InstructionCacheState.Refill ||
    state === L1InstructionCacheState.Drain || lookaheadInFlight) &&
    (!responseNeedsL2Insert || io.l2Insert.ready)
  val refillLineData = Wire(Vec(wordsPerLine, UInt(32.W)))
  for (word <- 0 until wordsPerLine) {
    refillLineData(word) := Mux(io.l2InsertHit, io.l2InsertData(word),
      io.l2Response.bits.lineData(word))
  }

  io.response.valid := state === L1InstructionCacheState.Present && !io.redirect.valid
  io.response.bits.base := requestBase
  io.response.bits.count := requestCount
  io.response.bits.words := packetWords
  io.currentPc := pc
  io.busy := state =/= L1InstructionCacheState.Idle
  io.draining := state === L1InstructionCacheState.Drain
  io.coherenceDrained := state === L1InstructionCacheState.Idle && !lookaheadInFlight

  when(io.redirect.valid) {
    assert(!io.redirect.bits(1, 0).orR,
      "L1I redirect target must satisfy IALIGN=32")
  }
  when(io.l2Request.fire) {
    assert(io.l2Request.bits.lineAddress(4, 0) === 0.U,
      "L1I sent an unaligned line demand")
    assert(io.l2Request.bits.lineAddress(11, 0) <= (4096 - cache.lineBytes).U,
      "L1I demand crossed an AXI 4 KiB boundary")
  }
  when(io.l2Lookup.fire) {
    assert(io.l2Lookup.bits(4, 0) === 0.U,
      "L1I issued an unaligned resident-L2 lookup")
  }
  when(io.l2Response.fire) {
    assert(io.l2Response.bits.client === L2DemandClient.Instruction.U,
      "L1I received a non-instruction demand response")
    assert(io.l2Response.bits.clientMshr === 0.U,
      "L1I received a response for an unknown local MSHR")
  }
  when(io.l2Response.fire && responseNeedsL2Insert) {
    assert(io.l2Insert.fire,
      "L1I consumed a successful AXI refill without dynamic L2 allocation")
  }
  when(io.l2Insert.fire) {
    assert(!io.l2Insert.bits.dirty,
      "L1I attempted to allocate a dirty instruction line")
  }
  when(io.l2Request.fire && sequentialLookaheadNeeded) {
    lookaheadIssued := true.B
    lookaheadInFlight := true.B
    lookaheadLine := sequentialLookaheadLine
    lookaheadSet := sequentialLookaheadSet
    lookaheadTag := sequentialLookaheadTag
    lookaheadWay := sequentialLookaheadVictim
  }
  when(io.l2Response.fire && lookaheadInFlight && !io.redirect.valid) {
    when(!io.l2Response.bits.accessFault) {
      cacheValid(lookaheadWay)(lookaheadSet) := true.B
      cacheTag(lookaheadWay)(lookaheadSet) := lookaheadTag
      dataWriteEnable := true.B
      dataWriteWay := lookaheadWay
      dataWriteSet := lookaheadSet
      dataWriteData := Cat(refillLineData.reverse)
      replacement(lookaheadSet) := !lookaheadWay.asBool
    }
    lookaheadInFlight := false.B
  }
  when(state === L1InstructionCacheState.Present) {
    assert(requestCount >= 1.U && requestCount <= config.fetchWidth.U,
      "L1I presented an illegal fetch packet length")
  }
  when(io.response.fire) {
    assert(!io.responseNextPc(1, 0).orR,
      "L1I response continuation must satisfy IALIGN=32")
  }

  when(io.invalidate) {
    for (way <- 0 until ways) {
      for (set <- 0 until sets) {
        cacheValid(way)(set) := false.B
      }
    }
  }

  when(io.redirect.valid) {
    pc := io.redirect.bits
    lookaheadIssued := false.B
    when(state === L1InstructionCacheState.L2Response) {
      state := Mux(io.l2LookupResponse.fire, L1InstructionCacheState.Idle,
        L1InstructionCacheState.L2Drain)
    }.elsewhen(state === L1InstructionCacheState.Refill || lookaheadInFlight) {
      state := Mux(io.l2Response.fire, L1InstructionCacheState.Idle,
        L1InstructionCacheState.Drain)
      lookaheadInFlight := false.B
    }.elsewhen(state === L1InstructionCacheState.Drain) {
      when(io.l2Response.fire) {
        state := L1InstructionCacheState.Idle
      }
    }.otherwise {
      state := L1InstructionCacheState.Idle
    }
  }.otherwise {
    switch(state) {
      is(L1InstructionCacheState.Idle) {
        when(io.enable) {
          requestBase := pc
          requestCount := nextCount(countWidth - 1, 0)
          requestLine := Cat(pc(31, lineOffsetWidth), 0.U(lineOffsetWidth.W))
          state := L1InstructionCacheState.Lookup
        }
      }
      is(L1InstructionCacheState.Lookup) {
        when(cacheHit) {
          for (slot <- 0 until config.fetchWidth) {
            val wordIndex = requestWord + slot.U
            packetWords(slot).instruction := Mux(slot.U < requestCount,
              dataReadWords(wordIndex), 0.U)
            packetWords(slot).fault.valid := false.B
            packetWords(slot).fault.cause := 0.U
            packetWords(slot).fault.tval := 0.U
          }
          replacement(requestSet) := !hitWay.asBool
          state := L1InstructionCacheState.Present
        }.elsewhen(lookaheadInFlight) {
          // The lone local MSHR belongs to the retained lookahead. A lookup for
          // another line waits here until that AXI-owned response drains.
        }.otherwise {
          missWay := victimWay
          state := L1InstructionCacheState.L2Lookup
        }
      }
      is(L1InstructionCacheState.L2Lookup) {
        when(io.l2Lookup.fire) {
          state := L1InstructionCacheState.L2Response
        }
      }
      is(L1InstructionCacheState.L2Response) {
        when(io.l2LookupResponse.fire) {
          when(io.l2LookupResponse.bits.hit) {
            for (slot <- 0 until config.fetchWidth) {
              val wordIndex = requestWord + slot.U
              packetWords(slot).instruction := Mux(slot.U < requestCount,
                io.l2LookupResponse.bits.lineData(wordIndex), 0.U)
              packetWords(slot).fault.valid := false.B
              packetWords(slot).fault.cause := 0.U
              packetWords(slot).fault.tval := 0.U
            }
            when(!io.invalidate) {
              cacheValid(missWay)(requestSet) := true.B
              cacheTag(missWay)(requestSet) := requestTag
              dataWriteEnable := true.B
              dataWriteWay := missWay
              dataWriteSet := requestSet
              dataWriteData := Cat(io.l2LookupResponse.bits.lineData.reverse)
              replacement(requestSet) := !missWay.asBool
            }
            state := L1InstructionCacheState.Present
          }.otherwise {
            state := L1InstructionCacheState.Demand
          }
        }
      }
      is(L1InstructionCacheState.Demand) {
        when(io.l2Request.fire) {
          state := L1InstructionCacheState.Refill
        }
      }
      is(L1InstructionCacheState.Refill) {
        when(io.l2Response.fire) {
          for (slot <- 0 until config.fetchWidth) {
            val wordIndex = requestWord + slot.U
            packetWords(slot).instruction := Mux(slot.U < requestCount &&
              !io.l2Response.bits.accessFault,
              refillLineData(wordIndex), 0.U)
            packetWords(slot).fault.valid := slot.U < requestCount &&
              io.l2Response.bits.accessFault
            packetWords(slot).fault.cause := 1.U
            packetWords(slot).fault.tval := requestBase + (slot * 4).U
          }
          when(!io.l2Response.bits.accessFault && !io.invalidate) {
            cacheValid(missWay)(requestSet) := true.B
            cacheTag(missWay)(requestSet) := requestTag
            dataWriteEnable := true.B
            dataWriteWay := missWay
            dataWriteSet := requestSet
            dataWriteData := Cat(refillLineData.reverse)
            replacement(requestSet) := !missWay.asBool
          }
          state := L1InstructionCacheState.Present
        }
      }
      is(L1InstructionCacheState.Present) {
        when(io.response.fire) {
          pc := io.responseNextPc
          lookaheadIssued := false.B
          when(io.continueAfterResponse) {
            requestBase := io.responseNextPc
            requestCount := continuedCount(countWidth - 1, 0)
            requestLine := Cat(io.responseNextPc(31, lineOffsetWidth),
              0.U(lineOffsetWidth.W))
            state := L1InstructionCacheState.Lookup
          }.otherwise {
            state := L1InstructionCacheState.Idle
          }
        }
      }
      is(L1InstructionCacheState.Drain) {
        when(io.l2Response.fire) {
          state := L1InstructionCacheState.Idle
        }
      }
      is(L1InstructionCacheState.L2Drain) {
        when(io.l2LookupResponse.fire) {
          state := L1InstructionCacheState.Idle
        }
      }
    }
  }
}
