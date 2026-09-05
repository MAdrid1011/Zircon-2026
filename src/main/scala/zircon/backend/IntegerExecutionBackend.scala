package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Closed M1 integer issue/execute/writeback loop.
  *
  * Rename/dispatch, branch metadata, recovery policy, and commit policy remain
  * outside this composition. This module owns IntIQ through integer completion
  * and the shared ROB/PRF/ready state used by that path.
  */
class IntegerExecutionBackend(
    config: ZirconCoreConfig = ZirconCoreConfig.default,
    registeredWakeup: Boolean = false,
    allowIssueRecycle: Boolean = true
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)
  private val robCountWidth = log2Ceil(config.robEntries + 1)
  private val intCountWidth = log2Ceil(config.intIssueEntries + 1)

  val io = IO(new Bundle {
    val robEnqueue = Flipped(Vec(config.decodeWidth,
      Decoupled(new ROBEnqueue(config))))
    val robEnqueueTag = Output(Vec(config.decodeWidth,
      Valid(UInt(config.robTagWidth.W))))
    val robCapacity = Output(UInt(2.W))
    val readyAllocation = Input(Vec(config.decodeWidth,
      Valid(UInt(physicalWidth.W))))

    val intEnqueue = Flipped(Vec(config.decodeWidth,
      Decoupled(new UopRef(config))))
    val intCapacity = Output(UInt(2.W))
    val intCount = Output(UInt(intCountWidth.W))

    val otherCompletion = Flipped(Vec(3,
      Decoupled(new CompletionResult(config))))
    val completionAccepted = Output(Vec(config.completionWidth, Bool()))
    val completionDiscarded = Output(Vec(config.completionWidth, Bool()))
    val wakeup = Output(Vec(config.completionWidth,
      new PhysicalWakeup(config)))

    val branchResolve = Decoupled(new BranchResolutionRequest(config))
    val e0Fault = Output(new FaultCandidate(config))
    val csrAccess = Output(new CSRAccessRequest)
    val csrAccessData = Input(UInt(32.W))
    val csrAccessLegal = Input(Bool())
    val systemSerializingReady = Input(Bool())
    val commitSideEffect = Output(Vec(config.commitWidth,
      new CommitSideEffect))
    val e0Occupied = Output(Bool())
    val e1Count = Output(UInt(1.W))
    val e0Start = Output(Bool())
    val e1Start = Output(Bool())
    val e1Completion = Output(Bool())
    val e2Completion = Output(Bool())

    val auxReadPhysical = Input(Vec(2, UInt(physicalWidth.W)))
    val auxReadData = Output(Vec(2, UInt(32.W)))
    val memoryExecutionRead = Input(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val memoryExecutionContext = Output(Vec(2,
      Valid(new ROBExecutionContext(config))))
    val integerReady = Output(UInt(config.intPhysicalRegisters.W))

    val commit = Vec(config.commitWidth, Decoupled(new ROBCommit(config)))
    val rollback = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val rollbackUndo = Decoupled(new ROBRollbackBundle(config))
    val rollbackActive = Output(Bool())
    val rollbackDone = Output(Bool())
    val robHeadTag = Output(UInt(config.robTagWidth.W))
    val robHeadControl = Output(Vec(2, new ROBControlInfo))
    // One-cycle head snapshot for age-only scheduling and recovery consumers.
    // The ROB/commit interface above remains live and architecturally exact.
    val scheduledRobHeadTag = Output(UInt(config.robTagWidth.W))
    val robHead = Output(Valid(new ROBCommit(config)))
    val robCount = Output(UInt(robCountWidth.W))

    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val recoveryActive = Input(Bool())
    val flush = Input(Bool())
  })

  val state = Module(new IntegerBackendState(config))
  val issue = Module(new IntegerIssueQueue(config,
    registeredWakeupMatch = registeredWakeup,
    allowIssueRecycle = allowIssueRecycle))
  val operandRead = Module(new IntegerOperandRead(config))
  val shortPipes = Module(new IntegerShortPipes(config))

  state.io.enqueue <> io.robEnqueue
  io.robEnqueueTag := state.io.enqueueTag
  io.robCapacity := state.io.enqueueCapacity
  state.io.readyAllocation := io.readyAllocation
  state.io.commit <> io.commit
  state.io.rollback <> io.rollback
  state.io.rollbackUndo <> io.rollbackUndo
  io.rollbackActive := state.io.rollbackActive
  io.rollbackDone := state.io.rollbackDone
  io.robHeadTag := state.io.robHeadTag
  io.robHead := state.io.robHead
  io.robHeadControl := state.io.robHeadControl
  io.robCount := state.io.robCount

  // Keep ROB head movement out of the issue/operand/fault combinational
  // domain. Relative age ordering of live tags is invariant across this
  // bounded snapshot lag; generation checks continue to reject stale work.
  val scheduledRobHeadTag = RegNext(state.io.robHeadTag)
  io.scheduledRobHeadTag := scheduledRobHeadTag
  // Keep age consumers in separate physical fanout domains.  Each snapshot
  // has the same one-cycle latency as the architectural scheduling tag; only
  // the physical driver is split so ROB head movement does not form one
  // high-fanout route through every execution sub-block.
  val issueRobHeadTag = RegNext(state.io.robHeadTag)
  val shortPipeRobHeadTag = RegNext(state.io.robHeadTag)
  dontTouch(issueRobHeadTag)
  dontTouch(shortPipeRobHeadTag)
  // Wakeup is a control-only hint; delaying it by one cycle keeps completion
  // and PRF writeback from feeding the IntIQ candidate/update cone directly.
  // Dispatch still uses the separately registered ready bitmap at the core
  // boundary, while retained queue entries preserve their own source-ready
  // state until this snapshot arrives.
  val scheduledWakeup = if (registeredWakeup) RegNext(state.io.wakeup)
    else state.io.wakeup

  issue.io.enqueue <> io.intEnqueue
  issue.io.wakeup := scheduledWakeup
  issue.io.integerReady := state.io.integerReady
  issue.io.robHeadTag := issueRobHeadTag
  issue.io.squash := io.squash
  issue.io.flush := io.flush
  io.intCapacity := issue.io.enqueueCapacity
  io.intCount := issue.io.count

  operandRead.io.issue(0) <> issue.io.issueE0
  operandRead.io.issue(1) <> issue.io.issueE1
  operandRead.io.robContext := state.io.executionContext
  state.io.executionRead := operandRead.io.robRead
  state.io.memoryExecutionRead := io.memoryExecutionRead
  io.memoryExecutionContext := state.io.memoryExecutionContext
  operandRead.io.flush := io.flush

  for (port <- 0 until 4) {
    state.io.readPhysical(port) := operandRead.io.prfReadPhysical(port)
    operandRead.io.prfReadData(port) := state.io.readData(port)
  }
  for (port <- 0 until 2) {
    state.io.readPhysical(port + 4) := io.auxReadPhysical(port)
    io.auxReadData(port) := state.io.readData(port + 4)
  }
  io.integerReady := state.io.integerReady

  shortPipes.io.e0 <> operandRead.io.execute(0)
  shortPipes.io.e1 <> operandRead.io.execute(1)
  shortPipes.io.robHeadTag := shortPipeRobHeadTag
  shortPipes.io.squash := io.squash
  shortPipes.io.recoveryActive := io.recoveryActive
  shortPipes.io.flush := io.flush
  shortPipes.io.csrAccessData := io.csrAccessData
  shortPipes.io.csrAccessLegal := io.csrAccessLegal
  shortPipes.io.systemSerializingReady := io.systemSerializingReady
  io.csrAccess := shortPipes.io.csrAccess
  io.branchResolve <> shortPipes.io.branchResolve
  io.e0Fault := shortPipes.io.e0Fault
  io.e0Occupied := shortPipes.io.e0Occupied
  io.e1Count := shortPipes.io.e1Count
  io.e0Start := shortPipes.io.e0.fire
  io.e1Start := shortPipes.io.e1.fire
  io.e1Completion := shortPipes.io.e1Completion.fire
  io.e2Completion := io.otherCompletion(0).fire

  for (lane <- 0 until config.commitWidth) {
    shortPipes.io.retire(lane).valid := io.commit(lane).fire
    shortPipes.io.retire(lane).bits := io.commit(lane).bits.robTag

    val serialized = io.commit(lane).valid &&
      (io.commit(lane).bits.entry.decoded.uopClass === UopClass.Csr ||
        io.commit(lane).bits.entry.decoded.uopClass === UopClass.System)
    val matchingSideEffect = shortPipes.io.commitSideEffect.valid &&
      shortPipes.io.commitSideEffect.bits.robTag === io.commit(lane).bits.robTag
    io.commitSideEffect(lane).csrWrite := matchingSideEffect &&
      shortPipes.io.commitSideEffect.bits.sideEffect.csrWrite
    io.commitSideEffect(lane).csrAddress :=
      shortPipes.io.commitSideEffect.bits.sideEffect.csrAddress
    io.commitSideEffect(lane).csrData :=
      shortPipes.io.commitSideEffect.bits.sideEffect.csrData
    io.commitSideEffect(lane).serializingReady := !serialized ||
      (matchingSideEffect &&
        shortPipes.io.commitSideEffect.bits.sideEffect.serializingReady)

    when(serialized && io.commit(lane).ready) {
      assert(matchingSideEffect,
        "a CSR/System retirement lacked its tagged E0 side effect")
    }
  }

  state.io.endpointCompletion(0) <> shortPipes.io.e0Completion
  state.io.endpointCompletion(1) <> shortPipes.io.e1Completion
  for (endpoint <- 0 until 3) {
    state.io.endpointCompletion(endpoint + 2) <>
      io.otherCompletion(endpoint)
  }
  io.completionAccepted := state.io.completionAccepted
  io.completionDiscarded := state.io.completionDiscarded
  io.wakeup := state.io.wakeup

  state.io.squash := io.squash
  state.io.flush := io.flush

  for (queueLane <- 0 until config.decodeWidth) {
    when(io.intEnqueue(queueLane).fire) {
      val matchesRobAllocation = (0 until config.decodeWidth).map(robLane =>
        state.io.enqueueTag(robLane).valid &&
          state.io.enqueueTag(robLane).bits ===
            io.intEnqueue(queueLane).bits.robTag).reduce(_ || _)
      assert(matchesRobAllocation,
        "IntIQ enqueue did not match a same-cycle ROB allocation")
    }
  }
  for (allocation <- io.readyAllocation) {
    when(allocation.valid) {
      val matchesRobDestination = (0 until config.decodeWidth).map(robLane =>
        io.robEnqueue(robLane).fire &&
          io.robEnqueue(robLane).bits.entry.allocatesPhysical &&
          io.robEnqueue(robLane).bits.entry.newPhysicalDestination ===
            allocation.bits).reduce(_ || _)
      assert(matchesRobDestination,
        "ready allocation did not match a same-cycle ROB destination")
    }
  }
}
