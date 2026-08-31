package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class BimodalTraining extends Bundle {
  val pc = UInt(32.W)
  val taken = Bool()
}

class BimodalPrediction extends Bundle {
  val counter = UInt(2.W)
  val taken = Bool()
}

/** Four-bank implementation of the frozen 512-entry, two-bit Base table. */
class BankedBimodalPredictor(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val banksCount = 4
  private val rowsPerBank = 128

  val io = IO(new Bundle {
    val fetchBase = Input(UInt(32.W))
    val predictions = Output(Vec(config.fetchWidth, new BimodalPrediction))
    val train = Input(Valid(new BimodalTraining))
    val ready = Output(Bool())
  })

  require(config.fetchWidth == 4, "the bank mapping is frozen for four-wide fetch")

  val banks = Seq.fill(banksCount)(Mem(rowsPerBank, UInt(2.W)))
  val scrubbing = RegInit(true.B)
  val scrubRow = RegInit(0.U(7.W))

  val queryPc = VecInit.tabulate(config.fetchWidth)(slot =>
    io.fetchBase + (slot * 4).U)
  val queryBank = VecInit(queryPc.map(_(3, 2)))
  val queryRow = VecInit(queryPc.map(_(10, 4)))
  val trainBank = io.train.bits.pc(3, 2)
  val trainRow = io.train.bits.pc(10, 4)

  val bankReads = Wire(Vec(banksCount, UInt(2.W)))
  for (bank <- 0 until banksCount) {
    val slotMatches = (0 until config.fetchWidth).map(slot =>
      queryBank(slot) === bank.U)
    val queryRowForBank = Mux1H((0 until config.fetchWidth).map(slot =>
      slotMatches(slot) -> queryRow(slot)))
    val trainingThisBank = io.train.valid && !scrubbing && trainBank === bank.U
    val readRow = Mux(trainingThisBank, trainRow, queryRowForBank)
    bankReads(bank) := banks(bank).read(readRow)

    assert(PopCount(slotMatches) === 1.U,
      "a four-instruction fetch group must access every bimodal bank once")
  }

  val trainCounter = bankReads(trainBank)
  val incrementedCounter = Mux(trainCounter === 3.U, 3.U, trainCounter + 1.U)
  val decrementedCounter = Mux(trainCounter === 0.U, 0.U, trainCounter - 1.U)
  val trainedCounter = Mux(io.train.bits.taken,
    incrementedCounter, decrementedCounter)

  for (bank <- 0 until banksCount) {
    when(scrubbing) {
      banks(bank).write(scrubRow, 1.U)
    }.elsewhen(io.train.valid && trainBank === bank.U) {
      banks(bank).write(trainRow, trainedCounter)
    }
  }

  when(scrubbing) {
    when(scrubRow === (rowsPerBank - 1).U) {
      scrubbing := false.B
    }.otherwise {
      scrubRow := scrubRow + 1.U
    }
  }

  io.ready := !scrubbing && !io.train.valid
  for (slot <- 0 until config.fetchWidth) {
    val rawCounter = Mux1H((0 until banksCount).map(bank =>
      (queryBank(slot) === bank.U) -> bankReads(bank)))
    io.predictions(slot).counter := Mux(io.ready, rawCounter, 1.U)
    io.predictions(slot).taken := io.ready && rawCounter(1)
  }

  assert(!io.fetchBase(1, 0).orR,
    "bimodal fetch base must be four-byte aligned")
  when(io.train.valid) {
    assert(!scrubbing, "bimodal training cannot arrive during reset scrub")
    assert(!io.train.bits.pc(1, 0).orR,
      "bimodal training PC must be four-byte aligned")
  }
}
