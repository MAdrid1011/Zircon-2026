package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.core.InterruptInputs
import zircon.frontend.FetchQueueEntry

/** Executable M1 backend boundary for RV32I, Zicsr, and M-mode traps.
  *
  * This composition closes the architectural loop between dispatch/rename,
  * integer execution, branch recovery, precise-fault tracking, retirement, and
  * machine CSR state. LongPipe and memory endpoints remain explicit milestone
  * ports, so this is not yet the final RV32IMAF core.
  */
class M1BackendSubsystem(
    config: ZirconCoreConfig = ZirconCoreConfig.default,
    registeredWakeup: Boolean = false
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val input = Flipped(Vec(config.decodeWidth,
      Decoupled(new FetchQueueEntry(config))))

    val longCapacity = Input(UInt(2.W))
    val memCapacity = Input(UInt(2.W))
    val floatingCapacity = Input(UInt(2.W))
    val longEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val memEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val floatingEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val floatingAllocate = Output(Vec(config.decodeWidth,
      Valid(new FloatingScoreboardAllocation(config))))
    val floatingScoreboardEmpty = Input(Bool())
    val otherCompletion = Flipped(Vec(3,
      Decoupled(new CompletionResult(config))))
    val otherFault = Input(Vec(3, new FaultCandidate(config)))

    val interrupts = Input(new InterruptInputs)
    val interruptBlocked = Input(Bool())
    val systemSerializingReady = Input(Bool())
    val fpCommit = Input(Valid(new FloatingStateCommit))

    val retired = Output(Vec(config.commitWidth,
      Valid(new ROBCommit(config))))
    val retiredInstructions = Output(UInt(
      log2Ceil(config.commitWidth + 1).W))
    val redirect = Output(Valid(new CommitRedirect))
    val globalFlush = Output(Bool())
    val csrWrite = Output(Valid(new CSRCommitWrite))
    val trapCommit = Output(Valid(new TrapCommit))
    val trapEntry = Output(Valid(new ROBCommit(config)))
    val trapLane = Output(UInt(1.W))
    val mretCommit = Output(Bool())
    val fenceICommit = Output(Bool())
    val wfiCommit = Output(Bool())
    val frontendRecovery = Output(Valid(
      new BranchResolutionResult(config)))
    val squash = Output(Valid(UInt(config.robTagWidth.W)))
    val branchTraining = Output(Valid(new BranchTrainingRecord(config)))

    val auxReadPhysical = Input(Vec(2, UInt(physicalWidth.W)))
    val auxReadData = Output(Vec(2, UInt(32.W)))
    val memoryExecutionRead = Input(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val memoryExecutionContext = Output(Vec(2,
      Valid(new ROBExecutionContext(config))))
    val integerReady = Output(UInt(config.intPhysicalRegisters.W))
    val speculativeMap = Output(Vec(32, UInt(physicalWidth.W)))
    val committedMap = Output(Vec(32, UInt(physicalWidth.W)))

    val eligibleInterrupt = Output(new EligibleInterrupt)
    val mstatusMie = Output(Bool())
    val mstatusFs = Output(UInt(2.W))
    val currentFflags = Output(UInt(5.W))
    val currentFrm = Output(UInt(3.W))

    val acceptedCount = Output(UInt(2.W))
    val renameFreeCount = Output(UInt(
      log2Ceil(config.intPhysicalRegisters + 1).W))
    val robHead = Output(Valid(new ROBCommit(config)))
    val robCount = Output(UInt(log2Ceil(config.robEntries + 1).W))
    val intCount = Output(UInt(log2Ceil(config.intIssueEntries + 1).W))
    val branchDataCount = Output(UInt(
      log2Ceil(config.branchDataEntries + 1).W))
    val recoveryActive = Output(Bool())
    val e0Start = Output(Bool())
    val e1Start = Output(Bool())
    val e1Completion = Output(Bool())
    val e2Completion = Output(Bool())
  })

  val backend = Module(new IntegerDispatchRecoveryBackend(config, registeredWakeup))
  val commit = Module(new CommitCSRSubsystem(config))

  for (lane <- 0 until config.decodeWidth) {
    backend.io.input(lane) <> io.input(lane)
    io.longEnqueue(lane) <> backend.io.longEnqueue(lane)
    io.memEnqueue(lane) <> backend.io.memEnqueue(lane)
    io.floatingEnqueue(lane) <> backend.io.floatingEnqueue(lane)
    io.floatingAllocate(lane) := backend.io.floatingAllocate(lane)
  }
  backend.io.longCapacity := io.longCapacity
  backend.io.memCapacity := io.memCapacity
  backend.io.floatingCapacity := io.floatingCapacity
  backend.io.floatingScoreboardEmpty := io.floatingScoreboardEmpty
  for (endpoint <- 0 until 3) {
    backend.io.otherCompletion(endpoint) <> io.otherCompletion(endpoint)
    backend.io.otherFault(endpoint) := io.otherFault(endpoint)
  }

  for (lane <- 0 until config.commitWidth) {
    commit.io.rob(lane) <> backend.io.commit(lane)
    backend.io.renameCommit(lane) := commit.io.renameCommit(lane)
  }
  commit.io.sideEffect := backend.io.commitSideEffect
  commit.io.firstFault := backend.io.firstFault
  backend.io.firstFaultClear := commit.io.firstFaultClear
  commit.io.branchCommit <> backend.io.branchCommit

  commit.io.csrAccess := backend.io.csrAccess
  backend.io.csrAccessData := commit.io.csrAccessData
  backend.io.csrAccessLegal := commit.io.csrAccessLegal
  backend.io.systemSerializingReady := io.systemSerializingReady

  commit.io.interrupts := io.interrupts
  commit.io.interruptHead := backend.io.robHead
  commit.io.interruptBlocked := io.interruptBlocked
  commit.io.fpCommit := io.fpCommit
  backend.io.globalFlush := commit.io.globalFlush
  backend.io.mstatusFs := commit.io.mstatusFs
  backend.io.currentFrm := commit.io.currentFrm

  backend.io.auxReadPhysical := io.auxReadPhysical
  io.auxReadData := backend.io.auxReadData
  backend.io.memoryExecutionRead := io.memoryExecutionRead
  io.memoryExecutionContext := backend.io.memoryExecutionContext
  io.integerReady := backend.io.integerReady
  io.speculativeMap := backend.io.speculativeMap
  io.committedMap := backend.io.committedMap

  io.retired := commit.io.retired
  io.retiredInstructions := commit.io.retiredInstructions
  io.redirect := commit.io.redirect
  io.globalFlush := commit.io.globalFlush
  io.csrWrite := commit.io.csrWrite
  io.trapCommit := commit.io.trapCommit
  io.trapEntry := commit.io.trapEntry
  io.trapLane := commit.io.trapLane
  io.mretCommit := commit.io.mretCommit
  io.fenceICommit := commit.io.fenceICommit
  io.wfiCommit := commit.io.wfiCommit
  io.frontendRecovery := backend.io.frontendRecovery
  io.squash := backend.io.squash
  io.branchTraining := backend.io.branchTraining
  io.eligibleInterrupt := commit.io.eligibleInterrupt
  io.mstatusMie := commit.io.mstatusMie
  io.mstatusFs := commit.io.mstatusFs
  io.currentFflags := commit.io.currentFflags
  io.currentFrm := commit.io.currentFrm

  io.acceptedCount := backend.io.acceptedCount
  io.renameFreeCount := backend.io.renameFreeCount
  io.robHead := backend.io.robHead
  io.robCount := backend.io.robCount
  io.intCount := backend.io.intCount
  io.branchDataCount := backend.io.branchDataCount
  io.recoveryActive := backend.io.recoveryActive
  io.e0Start := backend.io.e0Start
  io.e1Start := backend.io.e1Start
  io.e1Completion := backend.io.e1Completion
  io.e2Completion := backend.io.e2Completion

  when(commit.io.globalFlush) {
    assert(!backend.io.frontendRecovery.valid,
      "execute-time branch recovery escaped a commit-stage global flush")
  }
  assert(!(commit.io.redirect.valid && backend.io.frontendRecovery.valid),
    "commit and execute redirect sources must be mutually exclusive")
}
