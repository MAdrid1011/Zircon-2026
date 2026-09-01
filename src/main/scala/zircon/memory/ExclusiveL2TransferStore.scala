package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Owns stable D-side L2 lines and the first exclusive transfer boundary.
  *
  * An L1D eviction transfers one complete line into this store. A lookup hit
  * removes the L2 copy immediately and retains it in `response`, which is the
  * sole transfer-buffer owner until L1D accepts it. Dirty L2 replacement lines
  * enter the two-entry victim queue; an L2 AXI/writeback owner is deliberately
  * outside this component and must drain that queue later.
  */
class ExclusiveL2TransferStore(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val cache = config.l2
  private val ways = cache.ways
  private val wordsPerLine = cache.lineBytes / 4
  private val lineOffsetWidth = log2Ceil(cache.lineBytes)
  private val sets = cache.bytes / (ways * cache.lineBytes)
  private val setWidth = log2Ceil(sets)
  private val tagWidth = 32 - lineOffsetWidth - setWidth
  private val wayWidth = log2Ceil(ways)
  private val lineCount = ways * sets

  require(ways == 4 && cache.lineBytes == 32,
    "the frozen M3 L2 is four-way with 32-byte lines")
  require(cache.bytes == 4096 || cache.bytes == 8192,
    "the frozen M3 L2 only permits 4 KiB and 8 KiB points")
  require(cache.mshrs == 4,
    "the surrounding frozen M3 L2 has four MSHRs")

  val io = IO(new Bundle {
    /** Transfer from L1D eviction or an earlier transfer owner into L2. */
    val insert = Flipped(Decoupled(new CacheLineTransfer(config)))
    /** Hit requests move the only L2 copy into the response transfer buffer. */
    val lookup = Flipped(Decoupled(new L2LookupRequest(config)))
    val response = Decoupled(new L2LookupResponse(config))
    /** I-side probes retain the L2 copy and return a read-only line snapshot. */
    val instructionLookup = Flipped(Decoupled(UInt(32.W)))
    val instructionResponse = Decoupled(new L2InstructionLookupResponse(config))
    /** Trace-only exact dirty-line eviction into the retained victim FIFO. */
    val flushLine = Flipped(Decoupled(UInt(32.W)))
    /** A direct external write must remove a matching clean L2 copy before it
      * can become architecturally visible. Dirty lines wait for writeback. */
    val invalidate = Input(Valid(UInt(32.W)))
    val invalidateReady = Output(Bool())
    /** Dirty evictions await a later AXI writeback owner. */
    val victim = Decoupled(new CacheLineTransfer(config))
    val transferBusy = Output(Bool())
    val victimCount = Output(UInt(2.W))
    val residentLineCount = Output(UInt(log2Ceil(lineCount + 1).W))
  })

  val lineValid = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val lineDirty = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))
  val lineTag = Reg(Vec(ways, Vec(sets, UInt(tagWidth.W))))
  val lineData = Reg(Vec(ways, Vec(sets, Vec(wordsPerLine, UInt(32.W)))))
  val replacementWay = RegInit(VecInit.fill(sets)(0.U(wayWidth.W)))

  val responseValid = RegInit(false.B)
  val responseBits = Reg(new L2LookupResponse(config))
  io.response.valid := responseValid
  io.response.bits := responseBits
  io.transferBusy := responseValid

  val instructionResponseValid = RegInit(false.B)
  val instructionResponseBits = Reg(new L2InstructionLookupResponse(config))
  io.instructionResponse.valid := instructionResponseValid
  io.instructionResponse.bits := instructionResponseBits

  val victimQueue = Module(new Queue(new CacheLineTransfer(config), entries = 2))
  io.victim <> victimQueue.io.deq
  io.victimCount := victimQueue.io.count
  victimQueue.io.enq.valid := false.B
  victimQueue.io.enq.bits := 0.U.asTypeOf(new CacheLineTransfer(config))

  val insertSet = io.insert.bits.lineAddress(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val insertTag = io.insert.bits.lineAddress(31, lineOffsetWidth + setWidth)
  val insertHits = VecInit((0 until ways).map(way =>
    lineValid(way)(insertSet) && lineTag(way)(insertSet) === insertTag))
  val insertHit = insertHits.asUInt.orR
  val insertInvalidWays = VecInit((0 until ways).map(way => !lineValid(way)(insertSet)))
  val insertHasInvalidWay = insertInvalidWays.asUInt.orR
  val insertWay = Mux(insertHasInvalidWay, PriorityEncoder(insertInvalidWays.asUInt),
    replacementWay(insertSet))
  val replacingValid = lineValid(insertWay)(insertSet)
  val replacingDirty = replacingValid && lineDirty(insertWay)(insertSet)
  val canInsert = !insertHit && (!replacingDirty || victimQueue.io.enq.ready)
  // First version is a single array port: an insertion owns the cycle over a
  // lookup, including while a dirty victim is waiting for FIFO credit.
  io.insert.ready := !responseValid && !instructionResponseValid &&
    !io.invalidate.valid && !io.flushLine.valid && canInsert

  val lookupSet = io.lookup.bits.lineAddress(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val lookupTag = io.lookup.bits.lineAddress(31, lineOffsetWidth + setWidth)
  val lookupHits = VecInit((0 until ways).map(way =>
    lineValid(way)(lookupSet) && lineTag(way)(lookupSet) === lookupTag))
  val lookupHit = lookupHits.asUInt.orR
  val lookupWay = PriorityEncoder(lookupHits.asUInt)
  io.lookup.ready := !io.invalidate.valid && !io.insert.valid && !io.flushLine.valid &&
    !responseValid && !instructionResponseValid

  val instructionLookupSet = io.instructionLookup.bits(
    lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val instructionLookupTag = io.instructionLookup.bits(31,
    lineOffsetWidth + setWidth)
  val instructionLookupHits = VecInit((0 until ways).map(way =>
    lineValid(way)(instructionLookupSet) &&
      lineTag(way)(instructionLookupSet) === instructionLookupTag))
  val instructionLookupHit = instructionLookupHits.asUInt.orR
  val instructionLookupWay = PriorityEncoder(instructionLookupHits.asUInt)
  // One Reg-backed L2 array port is shared deterministically: a mutating D
  // action wins, then exclusive D transfer, then read-only I probe.
  io.instructionLookup.ready := !io.invalidate.valid && !io.insert.valid &&
    !io.lookup.valid && !io.flushLine.valid && !responseValid &&
    !instructionResponseValid

  val flushSet = io.flushLine.bits(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val flushTag = io.flushLine.bits(31, lineOffsetWidth + setWidth)
  val flushHits = VecInit((0 until ways).map(way =>
    lineValid(way)(flushSet) && lineTag(way)(flushSet) === flushTag))
  val flushHit = flushHits.asUInt.orR
  val flushWay = PriorityEncoder(flushHits.asUInt)
  val flushDirty = flushHit && lineDirty(flushWay)(flushSet)
  io.flushLine.ready := !responseValid && flushDirty && victimQueue.io.enq.ready

  val invalidateSet = io.invalidate.bits(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val invalidateTag = io.invalidate.bits(31, lineOffsetWidth + setWidth)
  val invalidateHits = VecInit((0 until ways).map(way =>
    lineValid(way)(invalidateSet) && lineTag(way)(invalidateSet) === invalidateTag))
  val invalidateHit = invalidateHits.asUInt.orR
  val invalidateWay = PriorityEncoder(invalidateHits.asUInt)
  val invalidateDirty = invalidateHit && lineDirty(invalidateWay)(invalidateSet)
  // Readiness only inspects registered L2 state and the caller's stable
  // address. It must not depend on this cycle's insert/lookup valid or on an
  // upstream effect fire, otherwise a store-ready to invalidation-valid loop
  // can cross the top-level LSU/cache boundary.
  io.invalidateReady := !responseValid && !io.flushLine.valid && !invalidateDirty

  when(io.insert.valid) {
    assert(io.insert.bits.lineAddress(lineOffsetWidth - 1, 0) === 0.U,
      "L2 insertion must transfer a line-aligned address")
    assert(!insertHit, "L2 insert would duplicate a stable D-side line")
  }
  when(io.lookup.fire) {
    assert(io.lookup.bits.lineAddress(lineOffsetWidth - 1, 0) === 0.U,
      "L2 lookup must use a line-aligned address")
  }
  when(io.instructionLookup.fire) {
    assert(io.instructionLookup.bits(lineOffsetWidth - 1, 0) === 0.U,
      "L2 instruction lookup must use a line-aligned address")
  }
  when(io.invalidate.valid) {
    assert(io.invalidateReady,
      "L2 invalidation attempted to discard a dirty or otherwise busy line")
    assert(io.invalidate.bits(lineOffsetWidth - 1, 0) === 0.U,
      "L2 invalidation must use a line-aligned address")
  }
  when(io.flushLine.valid) {
    assert(io.flushLine.bits(lineOffsetWidth - 1, 0) === 0.U,
      "L2 exact-line flush must use a line-aligned address")
  }

  when(io.response.fire) {
    responseValid := false.B
  }
  when(io.instructionResponse.fire) {
    instructionResponseValid := false.B
  }
  when(io.flushLine.fire) {
    victimQueue.io.enq.valid := true.B
    victimQueue.io.enq.bits.lineAddress := Cat(lineTag(flushWay)(flushSet), flushSet,
      0.U(lineOffsetWidth.W))
    victimQueue.io.enq.bits.dirty := true.B
    for (word <- 0 until wordsPerLine) {
      victimQueue.io.enq.bits.lineData(word) := lineData(flushWay)(flushSet)(word)
    }
    for (way <- 0 until ways) {
      when(flushWay === way.U) {
        lineValid(way)(flushSet) := false.B
        lineDirty(way)(flushSet) := false.B
      }
    }
  }.elsewhen(io.invalidate.valid) {
    when(invalidateHit) {
      for (way <- 0 until ways) {
        when(invalidateWay === way.U) {
          lineValid(way)(invalidateSet) := false.B
          lineDirty(way)(invalidateSet) := false.B
        }
      }
    }
  }.elsewhen(io.insert.fire) {
    when(replacingDirty) {
      victimQueue.io.enq.valid := true.B
      victimQueue.io.enq.bits.lineAddress := Cat(lineTag(insertWay)(insertSet),
        insertSet, 0.U(lineOffsetWidth.W))
      victimQueue.io.enq.bits.dirty := true.B
      for (word <- 0 until wordsPerLine) {
        victimQueue.io.enq.bits.lineData(word) := lineData(insertWay)(insertSet)(word)
      }
    }
    for (way <- 0 until ways) {
      when(insertWay === way.U) {
        lineValid(way)(insertSet) := true.B
        lineDirty(way)(insertSet) := io.insert.bits.dirty
        lineTag(way)(insertSet) := insertTag
        for (word <- 0 until wordsPerLine) {
          lineData(way)(insertSet)(word) := io.insert.bits.lineData(word)
        }
      }
    }
    replacementWay(insertSet) := Mux(insertWay === (ways - 1).U,
      0.U(wayWidth.W), insertWay + 1.U)
  }.elsewhen(io.lookup.fire) {
    responseValid := true.B
    responseBits.hit := lookupHit
    responseBits.transfer.lineAddress := io.lookup.bits.lineAddress
    responseBits.transfer.dirty := false.B
    for (word <- 0 until wordsPerLine) {
      responseBits.transfer.lineData(word) := 0.U
    }
    when(lookupHit) {
      for (way <- 0 until ways) {
        when(lookupWay === way.U) {
          lineValid(way)(lookupSet) := false.B
          responseBits.transfer.dirty := lineDirty(way)(lookupSet)
          responseBits.transfer.lineAddress := Cat(lineTag(way)(lookupSet),
            lookupSet, 0.U(lineOffsetWidth.W))
          for (word <- 0 until wordsPerLine) {
            responseBits.transfer.lineData(word) := lineData(way)(lookupSet)(word)
          }
        }
      }
    }
  }.elsewhen(io.instructionLookup.fire) {
    instructionResponseValid := true.B
    instructionResponseBits.hit := instructionLookupHit
    instructionResponseBits.lineAddress := io.instructionLookup.bits
    for (word <- 0 until wordsPerLine) {
      instructionResponseBits.lineData(word) := 0.U
    }
    when(instructionLookupHit) {
      for (way <- 0 until ways) {
        when(instructionLookupWay === way.U) {
          instructionResponseBits.lineAddress := Cat(lineTag(way)(instructionLookupSet),
            instructionLookupSet, 0.U(lineOffsetWidth.W))
          for (word <- 0 until wordsPerLine) {
            instructionResponseBits.lineData(word) :=
              lineData(way)(instructionLookupSet)(word)
          }
        }
      }
    }
  }

  io.residentLineCount := PopCount(lineValid.flatten)

  when(io.response.valid && io.response.bits.hit) {
    assert(io.response.bits.transfer.lineAddress(lineOffsetWidth - 1, 0) === 0.U,
      "L2 transfer response lost line alignment")
  }
  assert(io.victimCount <= 2.U, "L2 dirty-victim occupancy exceeded two entries")
}
