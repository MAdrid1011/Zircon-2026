package zircon.frontend

import chisel3._
import chisel3.util.{Valid, log2Ceil}
import zircon.ZirconCoreConfig

/** Connects four control predecoders, target selection, and speculative history. */
class FetchControlPrediction(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val fetchBase = Input(UInt(32.W))
    val instructions = Input(Vec(config.fetchWidth, UInt(32.W)))
    val slotValid = Input(Vec(config.fetchWidth, Bool()))
    val predictorsReady = Input(Bool())
    val directionTaken = Input(Vec(config.fetchWidth, Bool()))
    val btb = Input(Vec(config.fetchWidth, new BranchTargetPrediction))
    val rasTopValid = Input(Bool())
    val rasTop = Input(UInt(32.W))
    val accept = Input(Bool())
    val historyRecover = Input(Valid(UInt(64.W)))
    val clearHistory = Input(Bool())

    val predecode = Output(Vec(config.fetchWidth, new ControlPredecode))
    val redirect = Output(Valid(new FetchRedirectPrediction(config)))
    val unresolvedIndirect = Output(Valid(UInt(log2Ceil(config.fetchWidth).W)))
    val acceptedMask = Output(UInt(config.fetchWidth.W))
    val rasAction = Output(Valid(new RasAction))
    val historyBefore = Output(Vec(config.fetchWidth, UInt(64.W)))
    val currentHistory = Output(UInt(64.W))
  })

  require(config.fetchWidth == 4,
    "the fetch control predictor is frozen for four-wide fetch")

  val predecoders = Seq.tabulate(config.fetchWidth) { slot =>
    val predecoder = Module(new RV32ControlPredecoder)
    predecoder.io.pc := io.fetchBase + (slot * 4).U
    predecoder.io.instruction := io.instructions(slot)
    predecoder
  }
  val predecode = VecInit(predecoders.map(_.io.predecode))
  io.predecode := predecode

  val selector = Module(new FetchTargetSelector(config))
  selector.io.fetchBase := io.fetchBase
  selector.io.predictorsReady := io.predictorsReady
  selector.io.slotValid := io.slotValid
  selector.io.predecode := predecode
  selector.io.btb := io.btb
  selector.io.directionTaken := io.directionTaken
  selector.io.rasTopValid := io.rasTopValid
  selector.io.rasTop := io.rasTop
  io.redirect := selector.io.redirect
  io.unresolvedIndirect := selector.io.unresolvedIndirect
  io.acceptedMask := selector.io.acceptedMask
  io.rasAction := selector.io.rasAction

  val history = Module(new SpeculativeGlobalHistory(config))
  history.io.slotValid := io.slotValid
  history.io.conditional := VecInit(predecode.map(_.conditional))
  history.io.predictedTaken := io.directionTaken
  history.io.acceptedMask := selector.io.acceptedMask
  history.io.advance := io.accept
  history.io.recover := io.historyRecover
  history.io.clear := io.clearHistory
  io.historyBefore := history.io.historyBefore
  io.currentHistory := history.io.current

  when(io.accept) {
    assert(io.predictorsReady,
      "a fetch group was accepted while predictor output was stalled")
    assert(selector.io.acceptedMask.orR,
      "an empty fetch group was accepted")
    assert(!io.historyRecover.valid && !io.clearHistory,
      "a fetch group was accepted during history recovery or clear")
  }
}
