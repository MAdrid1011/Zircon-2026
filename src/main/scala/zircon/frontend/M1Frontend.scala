package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{BranchProvider, BranchResolutionResult, BranchTrainingRecord, CommitRedirect, CommitRedirectReason}
import zircon.memory.{L1InstructionCache, L2DemandRequest, L2DemandResponse}

/** Executable-M1 frontend from the temporary AXI transport to two-wide decode.
  *
  * L1I replaces the M1 AXI transport. Prediction state and the fetch/decode
  * contract remain unchanged across the shared-demand boundary.
  */
class M1Frontend(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val l2Request = Decoupled(new L2DemandRequest(config))
    val l2Response = Flipped(Decoupled(new L2DemandResponse(config)))
    val decode = Vec(config.decodeWidth, Decoupled(new FetchQueueEntry(config)))

    val branchTraining = Input(Valid(new BranchTrainingRecord(config)))
    val executeRecovery = Input(Valid(new BranchResolutionResult(config)))
    val commitRedirect = Input(Valid(new CommitRedirect))

    val currentPc = Output(UInt(32.W))
    val fetchBusy = Output(Bool())
    val fetchDraining = Output(Bool())
    val unresolvedIndirect = Output(Bool())
    val predictorsReady = Output(Bool())
    val queueCount = Output(UInt(log2Ceil(config.fetchWidth + 1).W))
  })

  require(config.fetchWidth == 4 && config.decodeWidth == 2,
    "M1Frontend is frozen for four-wide fetch and two-wide decode")

  val fetch = Module(new L1InstructionCache(config))
  val bimodal = Module(new BankedBimodalPredictor(config))
  val btb = Module(new BankedBranchTargetBuffer(config))
  val ras = Module(new ReturnAddressStack)
  val control = Module(new FetchControlPrediction(config))
  val queue = Module(new FetchDecodeQueue(config))

  val commitRedirect = io.commitRedirect.valid
  val executeRecovery = io.executeRecovery.valid && !commitRedirect
  val frontendRedirect = commitRedirect || executeRecovery
  val redirectTarget = Mux(commitRedirect, io.commitRedirect.bits.target,
    io.executeRecovery.bits.redirectTarget)

  val unresolvedIndirect = RegInit(false.B)
  val fenceICommit = commitRedirect &&
    io.commitRedirect.bits.reason === CommitRedirectReason.FenceI

  fetch.io.invalidate := fenceICommit

  bimodal.io.fetchBase := fetch.io.response.bits.base
  bimodal.io.train.valid := io.branchTraining.valid &&
    io.branchTraining.bits.metadata.conditional
  bimodal.io.train.bits.pc := io.branchTraining.bits.metadata.pc
  bimodal.io.train.bits.taken := io.branchTraining.bits.actualTaken

  btb.io.fetchBase := fetch.io.response.bits.base
  btb.io.train.valid := io.branchTraining.valid
  btb.io.train.bits.pc := io.branchTraining.bits.metadata.pc
  btb.io.train.bits.target := io.branchTraining.bits.actualTarget
  btb.io.train.bits.conditional := io.branchTraining.bits.metadata.conditional
  btb.io.train.bits.call := io.branchTraining.bits.metadata.call
  btb.io.train.bits.ret := io.branchTraining.bits.metadata.ret
  btb.io.invalidate := fenceICommit

  val predictorsReady = bimodal.io.ready && btb.io.ready
  control.io.fetchBase := fetch.io.response.bits.base
  for (slot <- 0 until config.fetchWidth) {
    control.io.instructions(slot) := fetch.io.response.bits.words(slot).instruction
    control.io.slotValid(slot) := slot.U < fetch.io.response.bits.count
    control.io.directionTaken(slot) := bimodal.io.predictions(slot).taken
    control.io.btb(slot) := btb.io.predictions(slot)
  }
  control.io.predictorsReady := predictorsReady
  control.io.rasTopValid := ras.io.topValid
  control.io.rasTop := ras.io.top
  control.io.historyRecover.valid := executeRecovery
  control.io.historyRecover.bits := io.executeRecovery.bits.recoveryHistory
  control.io.clearHistory := commitRedirect

  ras.io.clear := commitRedirect
  ras.io.recover.valid := executeRecovery
  ras.io.recover.bits.pointerBefore := io.executeRecovery.bits.rasPointerBefore
  ras.io.recover.bits.countBefore := io.executeRecovery.bits.rasCountBefore
  ras.io.recover.bits.action.push := io.executeRecovery.bits.rasPush
  ras.io.recover.bits.action.pop := io.executeRecovery.bits.rasPop
  ras.io.recover.bits.action.returnAddress := io.executeRecovery.bits.rasReturnAddress

  val acceptedCount = PopCount(control.io.acceptedMask)
  val responseCanEnqueue = control.io.acceptedMask.orR && queue.io.enqueue.ready &&
    (!control.io.rasAction.valid || ras.io.speculate.ready)
  queue.io.enqueue.valid := fetch.io.response.valid && responseCanEnqueue &&
    !frontendRedirect
  queue.io.enqueue.bits.count := acceptedCount
  for (slot <- 0 until config.fetchWidth) {
    val entry = queue.io.enqueue.bits.entries(slot)
    val pc = fetch.io.response.bits.base + (slot * 4).U
    val predictedControl = control.io.redirect.valid &&
      control.io.redirect.bits.slot === slot.U
    val predictedTaken = Mux(control.io.predecode(slot).conditional,
      bimodal.io.predictions(slot).taken, predictedControl)
    val predictedTarget = Mux(predictedTaken,
      Mux(control.io.predecode(slot).direct,
        control.io.predecode(slot).directTarget,
        control.io.redirect.bits.target), pc + 4.U)

    entry.instruction := fetch.io.response.bits.words(slot).instruction
    entry.prediction.pc := pc
    entry.prediction.historyBefore := control.io.historyBefore(slot)
    entry.prediction.predictedTaken := predictedTaken
    entry.prediction.predictedTarget := predictedTarget
    entry.prediction.conditional := control.io.predecode(slot).conditional
    entry.prediction.call := control.io.predecode(slot).call
    entry.prediction.ret := control.io.predecode(slot).ret
    entry.prediction.provider := BranchProvider.Base
    entry.prediction.alternateProvider := BranchProvider.Base
    entry.prediction.providerPrediction := bimodal.io.predictions(slot).taken
    entry.prediction.alternatePrediction := false.B
    entry.prediction.btbWay := btb.io.predictions(slot).way
    entry.prediction.rasPointerBefore := ras.io.pointer
    entry.prediction.rasCountBefore := ras.io.count
    entry.privilege := 3.U
    entry.fault := fetch.io.response.bits.words(slot).fault
  }

  val enqueueFire = queue.io.enqueue.fire
  control.io.accept := enqueueFire
  ras.io.speculate.valid := enqueueFire && control.io.rasAction.valid
  ras.io.speculate.bits := control.io.rasAction.bits

  queue.io.flush := frontendRedirect
  io.decode <> queue.io.dequeue

  fetch.io.enable := io.enable && predictorsReady && !unresolvedIndirect &&
    !frontendRedirect
  fetch.io.redirect.valid := frontendRedirect
  fetch.io.redirect.bits := redirectTarget
  fetch.io.response.ready := responseCanEnqueue && !frontendRedirect
  fetch.io.responseNextPc := Mux(control.io.redirect.valid,
    control.io.redirect.bits.target,
    fetch.io.response.bits.base + (acceptedCount << 2))
  val responseHasFetchFault = (0 until config.fetchWidth).map(slot =>
    slot.U < fetch.io.response.bits.count && fetch.io.response.bits.words(slot).fault.valid
  ).reduce(_ || _)
  // A targetless JALR may only fetch again after E0 installs the real target.
  // Direct control targets can enter the next L1I lookup on the response edge.
  fetch.io.continueAfterResponse := io.enable && predictorsReady &&
    !unresolvedIndirect && !frontendRedirect &&
    !control.io.unresolvedIndirect.valid && !responseHasFetchFault
  fetch.io.lookaheadEnable := io.enable && predictorsReady &&
    !unresolvedIndirect && !frontendRedirect &&
    !control.io.unresolvedIndirect.valid && !control.io.redirect.valid &&
    !responseHasFetchFault
  io.l2Request <> fetch.io.l2Request
  fetch.io.l2Response <> io.l2Response

  when(frontendRedirect) {
    unresolvedIndirect := false.B
  }.elsewhen(enqueueFire && control.io.unresolvedIndirect.valid) {
    unresolvedIndirect := true.B
  }

  io.currentPc := fetch.io.currentPc
  io.fetchBusy := fetch.io.busy
  io.fetchDraining := fetch.io.draining
  io.unresolvedIndirect := unresolvedIndirect
  io.predictorsReady := predictorsReady
  io.queueCount := queue.io.count

  when(enqueueFire) {
    assert(acceptedCount =/= 0.U,
      "M1 frontend accepted an empty fetch prefix")
    assert(acceptedCount <= fetch.io.response.bits.count,
      "M1 frontend accepted slots beyond the AXI fetch packet")
    assert(!frontendRedirect,
      "M1 frontend enqueued work during a higher-priority redirect")
  }
  when(unresolvedIndirect) {
    assert(!fetch.io.l2Request.valid,
      "M1 frontend issued a demand beyond an unresolved indirect branch")
  }
  when(fenceICommit) {
    assert(!io.branchTraining.valid,
      "FENCE.I invalidation raced with branch predictor training")
  }
}
