package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.FetchQueueEntry

/** Integrated M1 dispatch, integer execution, and selective-recovery domain.
  *
  * LongPipe, LSU, commit policy, CSR state, and the frontend remain outside
  * this composition. Their explicit ports keep this module usable while those
  * milestone blocks are implemented without weakening atomic dispatch or
  * recovery invariants.
  */
class IntegerDispatchRecoveryBackend(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)
  private val freeCountWidth = log2Ceil(config.intPhysicalRegisters + 1)
  private val robCountWidth = log2Ceil(config.robEntries + 1)
  private val intCountWidth = log2Ceil(config.intIssueEntries + 1)

  val io = IO(new Bundle {
    val input = Flipped(Vec(config.decodeWidth,
      Decoupled(new FetchQueueEntry(config))))

    val longCapacity = Input(UInt(2.W))
    val memCapacity = Input(UInt(2.W))
    val longEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val memEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val otherCompletion = Flipped(Vec(3,
      Decoupled(new CompletionResult(config))))
    val otherFault = Input(Vec(3, new FaultCandidate(config)))

    val csrAccess = Output(new CSRAccessRequest)
    val csrAccessData = Input(UInt(32.W))
    val csrAccessLegal = Input(Bool())
    val systemSerializingReady = Input(Bool())
    val commitSideEffect = Output(Vec(config.commitWidth,
      new CommitSideEffect))

    val commit = Vec(config.commitWidth, Decoupled(new ROBCommit(config)))
    val renameCommit = Input(Vec(config.commitWidth,
      new RenameCommit(physicalWidth)))
    val branchCommit = Flipped(Decoupled(new BranchDataReference(config)))
    val branchTraining = Output(Valid(new BranchTrainingRecord(config)))
    val firstFaultClear = Input(Bool())
    val firstFault = Output(Valid(new FirstFaultRecord(config)))

    val frontendRecovery = Output(Valid(
      new BranchResolutionResult(config)))
    val squash = Output(Valid(UInt(config.robTagWidth.W)))
    val dispatchBlocked = Output(Bool())
    val recoveryActive = Output(Bool())
    val globalFlush = Input(Bool())

    val auxReadPhysical = Input(Vec(2, UInt(physicalWidth.W)))
    val auxReadData = Output(Vec(2, UInt(32.W)))
    val memoryExecutionRead = Input(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val memoryExecutionContext = Output(Vec(2,
      Valid(new ROBExecutionContext(config))))
    val integerReady = Output(UInt(config.intPhysicalRegisters.W))
    val speculativeMap = Output(Vec(32, UInt(physicalWidth.W)))
    val committedMap = Output(Vec(32, UInt(physicalWidth.W)))

    val acceptedCount = Output(UInt(2.W))
    val renameFreeCount = Output(UInt(freeCountWidth.W))
    val robHead = Output(Valid(new ROBCommit(config)))
    val robCount = Output(UInt(robCountWidth.W))
    val intCount = Output(UInt(intCountWidth.W))
    val branchDataCount = Output(UInt(
      log2Ceil(config.branchDataEntries + 1).W))
    val e0Start = Output(Bool())
    val e1Start = Output(Bool())
    val e1Completion = Output(Bool())
    val e2Completion = Output(Bool())
  })

  val dispatch = Module(new BackendDispatch(config))
  val rename = Module(new IntegerRename(config))
  val execution = Module(new IntegerExecutionBackend(config))
  val recovery = Module(new BranchRecoverySubsystem(config))
  val firstFault = Module(new FirstFaultTracker(
    candidateWidth = config.decodeWidth + 1 + 3, config = config))

  for (lane <- 0 until config.decodeWidth) {
    dispatch.io.input(lane).valid := io.input(lane).valid
    dispatch.io.input(lane).bits := io.input(lane).bits
    io.input(lane).ready := dispatch.io.input(lane).ready

    dispatch.io.robEnqueue(lane) <> execution.io.robEnqueue(lane)
    dispatch.io.robTags(lane) := execution.io.robEnqueueTag(lane)
    dispatch.io.intEnqueue(lane) <> execution.io.intEnqueue(lane)

    io.longEnqueue(lane).valid := dispatch.io.longEnqueue(lane).valid
    io.longEnqueue(lane).bits := dispatch.io.longEnqueue(lane).bits
    dispatch.io.longEnqueue(lane).ready := io.longEnqueue(lane).ready
    io.memEnqueue(lane).valid := dispatch.io.memEnqueue(lane).valid
    io.memEnqueue(lane).bits := dispatch.io.memEnqueue(lane).bits
    dispatch.io.memEnqueue(lane).ready := io.memEnqueue(lane).ready

    rename.io.request(lane) := dispatch.io.renameRequest(lane)
    dispatch.io.renameResponse(lane) := rename.io.response(lane)
    execution.io.readyAllocation(lane) := dispatch.io.readyAllocation(lane)
  }

  dispatch.io.renameFreeCount := rename.io.freeCount
  dispatch.io.renameReady := rename.io.canAllocate
  rename.io.accept := dispatch.io.renameAccept
  dispatch.io.robCapacity := execution.io.robCapacity
  dispatch.io.intCapacity := execution.io.intCapacity
  dispatch.io.longCapacity := io.longCapacity
  dispatch.io.memCapacity := io.memCapacity
  dispatch.io.integerReady := execution.io.integerReady
  dispatch.io.blocked := io.globalFlush || recovery.io.dispatchBlocked ||
    execution.io.rollbackActive
  io.acceptedCount := dispatch.io.acceptedCount

  dispatch.io.bdbAllocate <> recovery.io.allocate
  dispatch.io.bdbAllocatedIndex := recovery.io.allocatedIndex
  recovery.io.resolve <> execution.io.branchResolve
  recovery.io.robHeadTag := execution.io.robHeadTag
  recovery.io.robRollback <> execution.io.rollback
  recovery.io.robRollbackDone := execution.io.rollbackDone
  recovery.io.commit <> io.branchCommit
  recovery.io.globalFlush := io.globalFlush
  io.branchTraining := recovery.io.training
  io.frontendRecovery := recovery.io.frontendRecovery
  io.squash := recovery.io.squash
  io.dispatchBlocked := dispatch.io.blocked
  io.recoveryActive := recovery.io.recoveryActive ||
    execution.io.rollbackActive

  execution.io.squash := recovery.io.squash
  execution.io.recoveryActive := recovery.io.recoveryActive
  execution.io.flush := io.globalFlush
  execution.io.csrAccessData := io.csrAccessData
  execution.io.csrAccessLegal := io.csrAccessLegal
  execution.io.systemSerializingReady := io.systemSerializingReady
  io.csrAccess := execution.io.csrAccess
  io.commitSideEffect := execution.io.commitSideEffect
  rename.io.rollback <> execution.io.rollbackUndo
  rename.io.commit := io.renameCommit
  rename.io.flushToCommitted := io.globalFlush

  for (endpoint <- 0 until 3) {
    execution.io.otherCompletion(endpoint) <> io.otherCompletion(endpoint)
  }
  execution.io.auxReadPhysical := io.auxReadPhysical
  io.auxReadData := execution.io.auxReadData
  execution.io.memoryExecutionRead := io.memoryExecutionRead
  io.memoryExecutionContext := execution.io.memoryExecutionContext
  io.integerReady := execution.io.integerReady
  io.speculativeMap := rename.io.speculativeMap
  io.committedMap := rename.io.committedMap

  for (lane <- 0 until config.commitWidth) {
    io.commit(lane).valid := execution.io.commit(lane).valid
    io.commit(lane).bits := execution.io.commit(lane).bits
    execution.io.commit(lane).ready := io.commit(lane).ready
  }

  firstFault.io.robHeadTag := execution.io.robHeadTag
  for (lane <- 0 until config.decodeWidth) {
    firstFault.io.candidates(lane) := dispatch.io.faultCandidate(lane)
  }
  firstFault.io.candidates(config.decodeWidth) := execution.io.e0Fault
  for (endpoint <- 0 until 3) {
    firstFault.io.candidates(config.decodeWidth + 1 + endpoint) :=
      io.otherFault(endpoint)
  }
  firstFault.io.clear := io.firstFaultClear
  firstFault.io.flush := io.globalFlush
  firstFault.io.squash := recovery.io.squash
  io.firstFault.valid := firstFault.io.valid
  io.firstFault.bits := firstFault.io.record

  io.renameFreeCount := rename.io.freeCount
  io.robHead := execution.io.robHead
  io.robCount := execution.io.robCount
  io.intCount := execution.io.intCount
  io.branchDataCount := recovery.io.count
  io.e0Start := execution.io.e0Start
  io.e1Start := execution.io.e1Start
  io.e1Completion := execution.io.e1Completion
  io.e2Completion := execution.io.e2Completion

  when(io.globalFlush) {
    assert(!dispatch.io.acceptedCount.orR,
      "dispatch accepted work during a global flush")
    assert(!io.frontendRecovery.valid && !io.squash.valid,
      "execute-time recovery escaped a global flush")
  }
  when(recovery.io.dispatchBlocked) {
    assert(!dispatch.io.acceptedCount.orR,
      "dispatch accepted work during branch recovery")
  }
  for (lane <- 0 until config.commitWidth) {
    when(io.renameCommit(lane).valid) {
      val matchingRetirement = io.commit(lane).fire &&
        io.commit(lane).bits.entry.allocatesPhysical &&
        io.renameCommit(lane).architectural ===
          io.commit(lane).bits.entry.architecturalDestination &&
        io.renameCommit(lane).oldPhysical ===
          io.commit(lane).bits.entry.oldPhysicalDestination &&
        io.renameCommit(lane).newPhysical ===
          io.commit(lane).bits.entry.newPhysicalDestination
      assert(matchingRetirement,
        "rename commit did not match the same-lane ROB retirement")
    }
  }
  when(io.branchCommit.fire) {
    val matchesRetirement = (0 until config.commitWidth).map { lane =>
      io.commit(lane).fire && io.commit(lane).bits.entry.hasBranchData &&
        io.branchCommit.bits.robTag === io.commit(lane).bits.robTag &&
        io.branchCommit.bits.index ===
          io.commit(lane).bits.entry.branchDataIndex
    }.reduce(_ || _)
    assert(matchesRetirement,
      "BDB commit did not match a same-cycle branch retirement")
  }
}
