package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionResult, FaultCandidate, ROBExecutionContext, UopRef}

/** Composes the pre-execution ownership path for the two M3 LSU roles.
  *
  * MemIQ uops obtain operands and ROB context, receive M0/M1 admission, merge
  * replay with direct M0 work, and finally reserve LSQ ownership. The module
  * deliberately exports no completion: cache/MMIO/AXI execution remains the
  * next lifecycle layer and must supply real data or exact faults.
  */
class DualLSUIngress(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val m0Issue = Flipped(Decoupled(new UopRef(config)))
    val m1Issue = Flipped(Decoupled(new UopRef(config)))
    val robRead = Output(Vec(2, Valid(UInt(config.robTagWidth.W))))
    val robContext = Input(Vec(2, Valid(new ROBExecutionContext(config))))
    val prfReadPhysical = Output(Vec(4, UInt(physicalWidth.W)))
    val prfReadData = Input(Vec(4, UInt(32.W)))

    val fault = Output(Vec(2, new FaultCandidate(config)))
    val loadForward = Output(Valid(new LoadStoreForward(config)))
    val loadComplete = Flipped(Decoupled(new LoadCompletion(config)))
    val m0Completion = Decoupled(new CompletionResult(config))
    val m1Completion = Decoupled(new CompletionResult(config))
    val loadContextRead = Input(Valid(UInt(config.robTagWidth.W)))
    val loadContext = Output(Valid(new LoadQueueContext(config)))
    val commitAuthorize = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val storeEffect = Decoupled(new StoreEffect(config))
    val storeEffectComplete = Input(Valid(new StoreEffectComplete(config)))
    val retire = Input(Vec(config.commitWidth,
      Valid(UInt(config.robTagWidth.W))))
    val retireMetadata = Output(Vec(config.commitWidth,
      Valid(new MemoryRetireMetadata(config))))

    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val loadCount = Output(UInt(log2Ceil(config.loadQueueEntries + 1).W))
    val storeCount = Output(UInt(log2Ceil(config.storeQueueEntries + 1).W))
  })

  val operandRead = Module(new MemoryOperandRead(config))
  val admission = Module(new DualLSUAdmission(config))
  val m0Arbiter = Module(new M0RequestArbiter(config))
  val ingress = Module(new MemoryQueueIngress(config))
  val loadCompletion = Module(new DualMemoryLoadCompletion(config))

  operandRead.io.issue(0).valid := io.m0Issue.valid
  operandRead.io.issue(0).bits := io.m0Issue.bits
  io.m0Issue.ready := operandRead.io.issue(0).ready
  operandRead.io.issue(1).valid := io.m1Issue.valid
  operandRead.io.issue(1).bits := io.m1Issue.bits
  io.m1Issue.ready := operandRead.io.issue(1).ready
  operandRead.io.robContext := io.robContext
  io.robRead := operandRead.io.robRead
  io.prfReadPhysical := operandRead.io.prfReadPhysical
  operandRead.io.prfReadData := io.prfReadData
  operandRead.io.flush := io.flush

  admission.io.m0Input <> operandRead.io.request(0)
  admission.io.m1Input <> operandRead.io.request(1)
  admission.io.robHeadTag := io.robHeadTag
  admission.io.squash := io.squash
  admission.io.flush := io.flush

  m0Arbiter.io.direct <> admission.io.m0Issue
  m0Arbiter.io.replay <> admission.io.m1Replay
  m0Arbiter.io.robHeadTag := io.robHeadTag
  m0Arbiter.io.squash := io.squash
  m0Arbiter.io.flush := io.flush

  ingress.io.input(0) <> m0Arbiter.io.output
  ingress.io.input(1) <> admission.io.m1Issue
  ingress.io.robHeadTag := io.robHeadTag
  ingress.io.squash := io.squash
  ingress.io.flush := io.flush
  io.fault := ingress.io.fault
  io.loadForward := ingress.io.loadForward
  ingress.io.loadComplete.valid := io.loadComplete.valid
  ingress.io.loadComplete.bits := io.loadComplete.bits
  io.loadComplete.ready := ingress.io.loadComplete.ready
  loadCompletion.io.loadResult <> ingress.io.loadResult
  loadCompletion.io.robHeadTag := io.robHeadTag
  loadCompletion.io.squash := io.squash
  loadCompletion.io.flush := io.flush
  io.m0Completion <> loadCompletion.io.m0Completion
  io.m1Completion <> loadCompletion.io.m1Completion
  ingress.io.loadContextRead := io.loadContextRead
  io.loadContext := ingress.io.loadContext
  ingress.io.commitAuthorize.valid := io.commitAuthorize.valid
  ingress.io.commitAuthorize.bits := io.commitAuthorize.bits
  io.commitAuthorize.ready := ingress.io.commitAuthorize.ready
  io.storeEffect.valid := ingress.io.storeEffect.valid
  io.storeEffect.bits := ingress.io.storeEffect.bits
  ingress.io.storeEffect.ready := io.storeEffect.ready
  ingress.io.storeEffectComplete := io.storeEffectComplete
  for (lane <- 0 until config.commitWidth) {
    ingress.io.retire(lane) := io.retire(lane)
    io.retireMetadata(lane) := ingress.io.retireMetadata(lane)
  }
  io.loadCount := ingress.io.loadCount
  io.storeCount := ingress.io.storeCount

  when(io.flush || io.squash.valid) {
    assert(!io.m0Issue.ready && !io.m1Issue.ready,
      "dual LSU ingress accepted a MemIQ uop during recovery")
  }
}
