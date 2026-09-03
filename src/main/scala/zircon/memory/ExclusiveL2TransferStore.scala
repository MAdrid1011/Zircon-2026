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
    /** A non-faulting L1I AXI refill allocates a clean non-inclusive copy.
      * A resident D line wins an exact collision and its current data is
      * returned to L1I, so an old external refill cannot overwrite it. */
    val instructionInsert = Flipped(Decoupled(new CacheLineTransfer(config)))
    val instructionInsertHit = Output(Bool())
    val instructionInsertData = Output(Vec(config.l2.lineBytes / 4, UInt(32.W)))
    /** Hit requests move the only L2 copy into the response transfer buffer. */
    val lookup = Flipped(Decoupled(new L2LookupRequest(config)))
    val response = Decoupled(new L2LookupResponse(config))
    /** I-side probes retain the L2 copy and return a read-only line snapshot. */
    val instructionLookup = Flipped(Decoupled(UInt(32.W)))
    val instructionResponse = Decoupled(new L2InstructionLookupResponse(config))
    /** Exact-line cleanup. Dirty lines enter the retained victim FIFO; clean
      * or absent lines acknowledge without a writeback. */
    val flushLine = Flipped(Decoupled(UInt(32.W)))
    /** Stable while `flushLine.valid`; tells a cleanup controller whether the
      * accepted operation creates an ID-5 writeback obligation. */
    val flushLineDirty = Output(Bool())
    /** Cache-global FENCE drain. Dirty resident lines enter the retained
      * victim FIFO one at a time; the top level waits for ID-5 completion. */
    val fenceDrain = Input(Bool())
    val fenceDrained = Output(Bool())
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
  // Store one complete line per memory word.  Keeping the line packed avoids
  // elaborating one wide mux per word over every way/set entry, while the
  // `Mem` interface preserves the existing one-cycle transfer contract.
  val lineMem = Mem(lineCount, UInt((wordsPerLine * 32).W))
  def lineIndex(way: UInt, set: UInt): UInt =
    (way * sets.U + set)(log2Ceil(lineCount) - 1, 0)
  def lineWord(line: UInt, word: Int): UInt =
    line(word * 32 + 31, word * 32)
  def packedLine(words: Vec[UInt]): UInt = Cat(words.reverse)
  // All clients are mutually excluded by the ready equations below.  A
  // single shared read address therefore represents the sole active line
  // operation and prevents Chisel from creating one memory port per word or
  // per consumer.
  val readAddress = WireDefault(0.U(log2Ceil(lineCount).W))
  val lineRead = lineMem(readAddress)
  val lineWriteEnable = WireDefault(false.B)
  val lineWriteAddress = WireDefault(0.U(log2Ceil(lineCount).W))
  val lineWriteData = WireDefault(0.U((wordsPerLine * 32).W))
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
  val insertLine = lineRead
  val canInsert = !insertHit && (!replacingDirty || victimQueue.io.enq.ready)
  // First version is a single array port: an insertion owns the cycle over a
  // lookup, including while a dirty victim is waiting for FIFO credit.
  io.insert.ready := !io.fenceDrain && !responseValid && !instructionResponseValid &&
    !io.invalidate.valid && !io.flushLine.valid && canInsert

  val instructionInsertSet = io.instructionInsert.bits.lineAddress(
    lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val instructionInsertTag = io.instructionInsert.bits.lineAddress(31,
    lineOffsetWidth + setWidth)
  val instructionInsertHits = VecInit((0 until ways).map(way =>
    lineValid(way)(instructionInsertSet) &&
      lineTag(way)(instructionInsertSet) === instructionInsertTag))
  val instructionInsertHit = instructionInsertHits.asUInt.orR
  val instructionInsertHitWay = PriorityEncoder(instructionInsertHits.asUInt)
  val instructionInsertInvalidWays = VecInit((0 until ways).map(way =>
    !lineValid(way)(instructionInsertSet)))
  val instructionInsertWay = Mux(instructionInsertInvalidWays.asUInt.orR,
    PriorityEncoder(instructionInsertInvalidWays.asUInt),
    replacementWay(instructionInsertSet))
  val instructionReplacingValid = lineValid(instructionInsertWay)(instructionInsertSet)
  val instructionReplacingDirty = instructionReplacingValid &&
    lineDirty(instructionInsertWay)(instructionInsertSet)
  val instructionInsertLine = lineRead
  val canInstructionInsert = instructionInsertHit || !instructionReplacingDirty ||
    victimQueue.io.enq.ready
  io.instructionInsert.ready := !io.fenceDrain && !io.insert.valid && !responseValid &&
    !instructionResponseValid && !io.invalidate.valid && !io.flushLine.valid &&
    canInstructionInsert
  io.instructionInsertHit := instructionInsertHit
  for (word <- 0 until wordsPerLine) {
    io.instructionInsertData(word) := Mux(instructionInsertHit,
      lineWord(instructionInsertLine, word),
      io.instructionInsert.bits.lineData(word))
  }

  val lookupSet = io.lookup.bits.lineAddress(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val lookupTag = io.lookup.bits.lineAddress(31, lineOffsetWidth + setWidth)
  val lookupHits = VecInit((0 until ways).map(way =>
    lineValid(way)(lookupSet) && lineTag(way)(lookupSet) === lookupTag))
  val lookupHit = lookupHits.asUInt.orR
  val lookupWay = PriorityEncoder(lookupHits.asUInt)
  val lookupLine = lineRead
  io.lookup.ready := !io.fenceDrain && !io.invalidate.valid && !io.insert.valid &&
    !io.instructionInsert.valid && !io.flushLine.valid && !responseValid &&
    !instructionResponseValid

  val instructionLookupSet = io.instructionLookup.bits(
    lineOffsetWidth + setWidth - 1, lineOffsetWidth)
  val instructionLookupTag = io.instructionLookup.bits(31,
    lineOffsetWidth + setWidth)
  val instructionLookupHits = VecInit((0 until ways).map(way =>
    lineValid(way)(instructionLookupSet) &&
      lineTag(way)(instructionLookupSet) === instructionLookupTag))
  val instructionLookupHit = instructionLookupHits.asUInt.orR
  val instructionLookupWay = PriorityEncoder(instructionLookupHits.asUInt)
  val instructionLookupLine = lineRead
  // One Reg-backed L2 array port is shared deterministically: D insertion
  // wins, then clean I fill, then exclusive D transfer, then I probe.
  io.instructionLookup.ready := !io.fenceDrain && !io.invalidate.valid && !io.insert.valid &&
    !io.instructionInsert.valid && !io.lookup.valid && !io.flushLine.valid && !responseValid &&
    !instructionResponseValid

  val flushSet = io.flushLine.bits(lineOffsetWidth + setWidth - 1,
    lineOffsetWidth)
  val flushTag = io.flushLine.bits(31, lineOffsetWidth + setWidth)
  val flushHits = VecInit((0 until ways).map(way =>
    lineValid(way)(flushSet) && lineTag(way)(flushSet) === flushTag))
  val flushHit = flushHits.asUInt.orR
  val flushWay = PriorityEncoder(flushHits.asUInt)
  val flushDirty = flushHit && lineDirty(flushWay)(flushSet)
  val flushLineData = lineRead
  io.flushLineDirty := flushDirty
  io.flushLine.ready := !io.fenceDrain && !responseValid && !instructionResponseValid &&
    Mux(flushDirty, victimQueue.io.enq.ready, true.B)

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
  io.invalidateReady := !io.fenceDrain && !responseValid && !io.flushLine.valid && !invalidateDirty

  var fenceDirtyFound: Bool = false.B
  var fenceDirtyWay: UInt = 0.U(wayWidth.W)
  var fenceDirtySet: UInt = 0.U(setWidth.W)
  for (set <- 0 until sets; way <- 0 until ways) {
    val dirty = lineValid(way)(set) && lineDirty(way)(set)
    val take = dirty && !fenceDirtyFound
    fenceDirtyWay = Mux(take, way.U, fenceDirtyWay)
    fenceDirtySet = Mux(take, set.U, fenceDirtySet)
    fenceDirtyFound = fenceDirtyFound || dirty
  }
  val fenceEvict = io.fenceDrain && !responseValid && !instructionResponseValid &&
    fenceDirtyFound && victimQueue.io.enq.ready
  val fenceLineData = lineRead
  io.fenceDrained := !responseValid && !instructionResponseValid &&
    !fenceDirtyFound && victimQueue.io.count === 0.U

  // The operation priority mirrors the state-update chain below.  For a
  // valid instruction insertion this also supplies the resident collision
  // line to `instructionInsertData` without adding another read port.
  when(fenceEvict) {
    readAddress := lineIndex(fenceDirtyWay, fenceDirtySet)
  }.elsewhen(io.flushLine.valid && io.flushLine.ready) {
    readAddress := lineIndex(flushWay, flushSet)
  }.elsewhen(io.insert.valid && io.insert.ready) {
    readAddress := lineIndex(insertWay, insertSet)
  }.elsewhen(io.instructionInsert.valid && io.instructionInsert.ready) {
    readAddress := lineIndex(instructionInsertHitWay, instructionInsertSet)
  }.elsewhen(io.lookup.valid && io.lookup.ready) {
    readAddress := lineIndex(lookupWay, lookupSet)
  }.elsewhen(io.instructionLookup.valid && io.instructionLookup.ready) {
    readAddress := lineIndex(instructionLookupWay, instructionLookupSet)
  }.elsewhen(io.instructionInsert.valid) {
    readAddress := lineIndex(instructionInsertHitWay, instructionInsertSet)
  }

  when(io.insert.fire) {
    lineWriteEnable := true.B
    lineWriteAddress := lineIndex(insertWay, insertSet)
    lineWriteData := packedLine(io.insert.bits.lineData)
  }.elsewhen(io.instructionInsert.fire && !instructionInsertHit) {
    lineWriteEnable := true.B
    lineWriteAddress := lineIndex(instructionInsertWay, instructionInsertSet)
    lineWriteData := packedLine(io.instructionInsert.bits.lineData)
  }
  when(lineWriteEnable) {
    lineMem(lineWriteAddress) := lineWriteData
  }

  when(io.insert.valid) {
    assert(io.insert.bits.lineAddress(lineOffsetWidth - 1, 0) === 0.U,
      "L2 insertion must transfer a line-aligned address")
    assert(!insertHit, "L2 insert would duplicate a stable D-side line")
  }
  when(io.instructionInsert.fire) {
    assert(io.instructionInsert.bits.lineAddress(lineOffsetWidth - 1, 0) === 0.U,
      "L2 instruction insertion must transfer a line-aligned address")
    assert(!io.instructionInsert.bits.dirty,
      "L2 instruction insertion must remain clean")
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
  when(fenceEvict) {
    victimQueue.io.enq.valid := true.B
    victimQueue.io.enq.bits.lineAddress := Cat(lineTag(fenceDirtyWay)(fenceDirtySet),
      fenceDirtySet, 0.U(lineOffsetWidth.W))
    victimQueue.io.enq.bits.dirty := true.B
    for (word <- 0 until wordsPerLine) {
      victimQueue.io.enq.bits.lineData(word) :=
        lineWord(fenceLineData, word)
    }
    for (way <- 0 until ways) {
      when(fenceDirtyWay === way.U) {
        lineValid(way)(fenceDirtySet) := false.B
        lineDirty(way)(fenceDirtySet) := false.B
      }
    }
  }.elsewhen(io.flushLine.fire) {
    when(flushDirty) {
      victimQueue.io.enq.valid := true.B
      victimQueue.io.enq.bits.lineAddress := Cat(lineTag(flushWay)(flushSet), flushSet,
        0.U(lineOffsetWidth.W))
      victimQueue.io.enq.bits.dirty := true.B
      for (word <- 0 until wordsPerLine) {
        victimQueue.io.enq.bits.lineData(word) := lineWord(flushLineData, word)
      }
    }
    when(flushHit) {
      for (way <- 0 until ways) {
        when(flushWay === way.U) {
          lineValid(way)(flushSet) := false.B
          lineDirty(way)(flushSet) := false.B
        }
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
        victimQueue.io.enq.bits.lineData(word) := lineWord(insertLine, word)
      }
    }
    for (way <- 0 until ways) {
      when(insertWay === way.U) {
        lineValid(way)(insertSet) := true.B
        lineDirty(way)(insertSet) := io.insert.bits.dirty
        lineTag(way)(insertSet) := insertTag
      }
    }
    replacementWay(insertSet) := Mux(insertWay === (ways - 1).U,
      0.U(wayWidth.W), insertWay + 1.U)
  }.elsewhen(io.instructionInsert.fire) {
    when(!instructionInsertHit) {
      when(instructionReplacingDirty) {
        victimQueue.io.enq.valid := true.B
        victimQueue.io.enq.bits.lineAddress := Cat(
          lineTag(instructionInsertWay)(instructionInsertSet), instructionInsertSet,
          0.U(lineOffsetWidth.W))
        victimQueue.io.enq.bits.dirty := true.B
        for (word <- 0 until wordsPerLine) {
          victimQueue.io.enq.bits.lineData(word) :=
            lineWord(instructionInsertLine, word)
        }
      }
      for (way <- 0 until ways) {
        when(instructionInsertWay === way.U) {
          lineValid(way)(instructionInsertSet) := true.B
          lineDirty(way)(instructionInsertSet) := false.B
          lineTag(way)(instructionInsertSet) := instructionInsertTag
        }
      }
      replacementWay(instructionInsertSet) := Mux(instructionInsertWay ===
        (ways - 1).U, 0.U(wayWidth.W), instructionInsertWay + 1.U)
    }
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
            responseBits.transfer.lineData(word) := lineWord(lookupLine, word)
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
              lineWord(instructionLookupLine, word)
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
