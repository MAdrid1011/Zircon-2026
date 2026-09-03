package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.BranchProvider

/** Direction prediction returned by the frozen miniTAGE provider. */
class MiniTagePrediction extends Bundle {
  val taken = Bool()
  val provider = BranchProvider()
  val alternateProvider = BranchProvider()
  val providerPrediction = Bool()
  val alternatePrediction = Bool()
}

/** One committed training event. History is the checkpoint captured before the branch. */
class MiniTageTraining extends Bundle {
  val pc = UInt(32.W)
  val historyBefore = UInt(64.W)
  val actualTaken = Bool()
  val provider = BranchProvider()
  val alternateProvider = BranchProvider()
  val providerPrediction = Bool()
  val alternatePrediction = Bool()
}

class MiniTageEntry(val tagWidth: Int) extends Bundle {
  val valid = Bool()
  val tag = UInt(tagWidth.W)
  val counter = UInt(3.W)
  val useful = UInt(2.W)
}

/**
  * The M4 conditional direction provider: a 512-entry two-bit base table and
  * three 128-entry tagged tables with 4/16/64-bit history.  Tables are banked
  * by the low two PC bits so a four-instruction fetch has one read per bank.
  * Training is commit-only; speculative history and recovery remain owned by
  * FetchControlPrediction.
  */
class MiniTagePredictor(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val banks = 4
  private val baseRows = 128
  private val taggedRows = 32
  private val tableCount = 3
  private val historyLengths = Seq(4, 16, 64)
  private val tagWidths = Seq(7, 8, 9)

  val io = IO(new Bundle {
    val fetchBase = Input(UInt(32.W))
    val historyBefore = Input(Vec(config.fetchWidth, UInt(64.W)))
    val predictions = Output(Vec(config.fetchWidth, new MiniTagePrediction))
    val train = Input(Valid(new MiniTageTraining))
    val ready = Output(Bool())
  })

  require(config.fetchWidth == 4,
    "miniTAGE bank mapping is frozen for four-wide fetch")

  val base = Seq.fill(banks)(Mem(baseRows, UInt(2.W)))
  val tagged = Seq.tabulate(tableCount, banks)((table, _) =>
    Mem(taggedRows, new MiniTageEntry(tagWidths(table))))

  val scrubbing = RegInit(true.B)
  val scrubRow = RegInit(0.U(7.W))
  val training = io.train.valid && !scrubbing

  private def fold(history: UInt, length: Int, width: Int): UInt = {
    val chunks = (0 until ((length + width - 1) / width)).map { chunk =>
      val low = chunk * width
      val high = math.min(length - 1, low + width - 1)
      val piece = history(high, low)
      if (piece.getWidth < width) Cat(0.U((width - piece.getWidth).W), piece)
      else piece
    }
    chunks.reduce(_ ^ _)
  }

  private def tableIndex(pc: UInt, history: UInt, length: Int): UInt =
    pc(8, 2) ^ fold(history, length, 7)

  private def tableTag(pc: UInt, history: UInt, length: Int, width: Int): UInt = {
    val mixed = pc ^ Cat(0.U((32 - width).W), fold(history, length, width))
    mixed(width - 1, 0)
  }

  val queryPc = VecInit.tabulate(config.fetchWidth)(slot =>
    io.fetchBase + (slot * 4).U(32.W))
  val queryBank = VecInit(queryPc.map(_(3, 2)))
  val queryBaseRow = VecInit(queryPc.map(_(10, 4)))
  val baseReads = Wire(Vec(banks, UInt(2.W)))
  for (bank <- 0 until banks) {
    val matches = (0 until config.fetchWidth).map(slot => queryBank(slot) === bank.U)
    val row = Mux1H((0 until config.fetchWidth).map(slot =>
      matches(slot) -> queryBaseRow(slot)))
    baseReads(bank) := base(bank).read(row)
    assert(PopCount(matches) === 1.U,
      "a four-instruction fetch group must access every miniTAGE base bank once")
  }

  val queryIndex = Seq.tabulate(tableCount, config.fetchWidth) { (table, slot) =>
    tableIndex(queryPc(slot), io.historyBefore(slot), historyLengths(table))
  }
  val queryEntries = Seq.tabulate(tableCount, config.fetchWidth) { (table, slot) =>
    val bankEntries = (0 until banks).map { bank =>
      val row = queryIndex(table)(slot)(6, 2)
      tagged(table)(bank).read(row)
    }
    Mux1H((0 until banks).map(bank =>
      (queryIndex(table)(slot)(1, 0) === bank.U) -> bankEntries(bank)))
  }

  for (slot <- 0 until config.fetchWidth) {
    val baseCounter = Mux1H((0 until banks).map(bank =>
      (queryBank(slot) === bank.U) -> baseReads(bank)))
    val hits = (0 until tableCount).map(table =>
      queryEntries(table)(slot).valid &&
        queryEntries(table)(slot).tag === tableTag(queryPc(slot),
          io.historyBefore(slot), historyLengths(table), tagWidths(table)))
    val provider = Wire(BranchProvider())
    provider := BranchProvider.Base
    when(hits(0)) { provider := BranchProvider.Tagged0 }
    when(hits(1)) { provider := BranchProvider.Tagged1 }
    when(hits(2)) { provider := BranchProvider.Tagged2 }

    val providerTaken = Wire(Bool())
    providerTaken := baseCounter(1)
    when(hits(0)) { providerTaken := queryEntries(0)(slot).counter(2) }
    when(hits(1)) { providerTaken := queryEntries(1)(slot).counter(2) }
    when(hits(2)) { providerTaken := queryEntries(2)(slot).counter(2) }

    val alternateTaken = Wire(Bool())
    alternateTaken := baseCounter(1)
    when(hits(0)) { alternateTaken := queryEntries(0)(slot).counter(2) }
    when(hits(1)) { alternateTaken := queryEntries(1)(slot).counter(2) }
    when(hits(2)) { alternateTaken := queryEntries(2)(slot).counter(2) }
    when(provider === BranchProvider.Tagged0 && hits(0)) {
      alternateTaken := baseCounter(1)
      when(hits(1)) { alternateTaken := queryEntries(1)(slot).counter(2) }
      when(hits(2)) { alternateTaken := queryEntries(2)(slot).counter(2) }
    }
    when(provider === BranchProvider.Tagged1 && hits(1)) {
      alternateTaken := baseCounter(1)
      when(hits(0)) { alternateTaken := queryEntries(0)(slot).counter(2) }
      when(hits(2)) { alternateTaken := queryEntries(2)(slot).counter(2) }
    }
    when(provider === BranchProvider.Tagged2 && hits(2)) {
      alternateTaken := baseCounter(1)
      when(hits(0)) { alternateTaken := queryEntries(0)(slot).counter(2) }
      when(hits(1)) { alternateTaken := queryEntries(1)(slot).counter(2) }
    }

    io.predictions(slot).taken := io.ready && providerTaken
    io.predictions(slot).provider := provider
    io.predictions(slot).alternateProvider := MuxCase(BranchProvider.Base, Seq(
      (provider === BranchProvider.Tagged0 && hits(1)) -> BranchProvider.Tagged1,
      (provider === BranchProvider.Tagged0 && hits(2)) -> BranchProvider.Tagged2,
      (provider === BranchProvider.Tagged1 && hits(0)) -> BranchProvider.Tagged0,
      (provider === BranchProvider.Tagged1 && hits(2)) -> BranchProvider.Tagged2,
      (provider === BranchProvider.Tagged2 && hits(0)) -> BranchProvider.Tagged0,
      (provider === BranchProvider.Tagged2 && hits(1)) -> BranchProvider.Tagged1
    ))
    io.predictions(slot).providerPrediction := providerTaken
    io.predictions(slot).alternatePrediction := alternateTaken
  }

  val trainBaseBank = io.train.bits.pc(3, 2)
  val trainBaseRow = io.train.bits.pc(10, 4)
  val trainBaseCounter = Mux1H((0 until banks).map(bank =>
    (trainBaseBank === bank.U) -> base(bank).read(trainBaseRow)))
  val baseIncrement = Mux(trainBaseCounter === 3.U, 3.U, trainBaseCounter + 1.U)
  val baseDecrement = Mux(trainBaseCounter === 0.U, 0.U, trainBaseCounter - 1.U)
  val trainedBase = Mux(io.train.bits.actualTaken, baseIncrement, baseDecrement)

  val trainEntries = Seq.tabulate(tableCount) { table =>
    val index = tableIndex(io.train.bits.pc, io.train.bits.historyBefore,
      historyLengths(table))
    val entry = Mux1H((0 until banks).map(bank =>
      (index(1, 0) === bank.U) -> tagged(table)(bank).read(index(6, 2))))
    val widened = Wire(new MiniTageEntry(tagWidths(table)))
    widened := entry
    widened
  }
  val trainHits = VecInit((0 until tableCount).map { table =>
    trainEntries(table).valid && trainEntries(table).tag === tableTag(io.train.bits.pc,
      io.train.bits.historyBefore, historyLengths(table), tagWidths(table))
  })

  val allocationTable = PriorityEncoder(VecInit((0 until tableCount).map(!trainHits(_))).asUInt)
  val trainingMispredict = io.train.bits.providerPrediction =/= io.train.bits.actualTaken

  for (bank <- 0 until banks) {
    when(scrubbing) {
      base(bank).write(scrubRow, 1.U)
    }.elsewhen(training && trainBaseBank === bank.U) {
      base(bank).write(trainBaseRow, trainedBase)
    }
    for (table <- 0 until tableCount) {
      val index = tableIndex(io.train.bits.pc, io.train.bits.historyBefore,
        historyLengths(table))
      val updateCounter = Mux(io.train.bits.actualTaken,
        Mux(trainEntries(table).counter === 7.U, 7.U,
          trainEntries(table).counter + 1.U),
        Mux(trainEntries(table).counter === 0.U, 0.U,
          trainEntries(table).counter - 1.U))
      val updateEntry = Wire(new MiniTageEntry(tagWidths(table)))
      updateEntry.valid := trainEntries(table).valid
      updateEntry.tag := trainEntries(table).tag
      updateEntry.counter := updateCounter
      val usefulIncrease = io.train.bits.providerPrediction === io.train.bits.actualTaken &&
        io.train.bits.alternatePrediction =/= io.train.bits.actualTaken
      updateEntry.useful := Mux(usefulIncrease,
        Mux(trainEntries(table).useful === 3.U, 3.U,
          trainEntries(table).useful + 1.U),
        Mux(trainEntries(table).useful === 0.U, 0.U,
          trainEntries(table).useful - 1.U))
      when(scrubbing) {
        tagged(table)(bank).write(scrubRow(4, 0),
          0.U.asTypeOf(new MiniTageEntry(tagWidths(table))))
      }.elsewhen(training && trainHits(table) &&
          io.train.bits.provider.asUInt === BranchProvider.Tagged0.asUInt + table.U) {
        when(index(1, 0) === bank.U) {
          tagged(table)(bank).write(index(6, 2), updateEntry)
        }
      }.elsewhen(training && trainingMispredict && allocationTable === table.U) {
        val allocated = Wire(new MiniTageEntry(tagWidths(table)))
        allocated.valid := true.B
        allocated.tag := tableTag(io.train.bits.pc, io.train.bits.historyBefore,
          historyLengths(table), tagWidths(table))
        allocated.counter := Mux(io.train.bits.actualTaken, 4.U, 3.U)
        allocated.useful := 0.U
        when(index(1, 0) === bank.U) {
          tagged(table)(bank).write(index(6, 2), allocated)
        }
      }
    }
  }

  when(scrubbing) {
    when(scrubRow === 127.U) { scrubbing := false.B }
      .otherwise { scrubRow := scrubRow + 1.U }
  }
  io.ready := !scrubbing && !io.train.valid

  assert(!io.fetchBase(1, 0).orR, "miniTAGE fetch base must be aligned")
  when(io.train.valid) {
    assert(!scrubbing, "miniTAGE training cannot arrive during scrub")
    assert(!io.train.bits.pc(1, 0).orR, "miniTAGE training PC must be aligned")
  }
}
