package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.core.InterruptInputs

/** Commit arbitration plus architectural M-mode CSR state.
  *
  * CSR/System execution side effects, memory serialization state, interrupt
  * EPC selection, and the frontend redirect consumer remain external. This
  * module owns architectural retirement selection, CSR mutation, trap/MRET
  * transitions, and the single-port BDB commit schedule.
  */
class CommitCSRSubsystem(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val rob = Flipped(Vec(config.commitWidth,
      Decoupled(new ROBCommit(config))))
    val robControl = Input(Vec(config.commitWidth, new ROBControlInfo))
    val sideEffect = Input(Vec(config.commitWidth, new CommitSideEffect))
    val firstFault = Input(Valid(new FirstFaultRecord(config)))

    val csrAccess = Input(new CSRAccessRequest)
    val csrAccessData = Output(UInt(32.W))
    val csrAccessLegal = Output(Bool())
    val interrupts = Input(new InterruptInputs)
    val interruptHead = Input(Valid(new ROBCommit(config)))
    val interruptBlocked = Input(Bool())
    val fpCommit = Input(Valid(new FloatingStateCommit))

    val branchCommit = Decoupled(new BranchDataReference(config))
    val retired = Output(Vec(config.commitWidth,
      Valid(new ROBCommit(config))))
    val renameCommit = Output(Vec(config.commitWidth,
      new RenameCommit(physicalWidth)))
    val retiredInstructions = Output(UInt(
      log2Ceil(config.commitWidth + 1).W))

    val csrWrite = Output(Valid(new CSRCommitWrite))
    val trapCommit = Output(Valid(new TrapCommit))
    val trapEntry = Output(Valid(new ROBCommit(config)))
    val trapLane = Output(UInt(1.W))
    val mretCommit = Output(Bool())
    val firstFaultClear = Output(Bool())
    val globalFlush = Output(Bool())
    val redirect = Output(Valid(new CommitRedirect))
    val fenceICommit = Output(Bool())
    val wfiCommit = Output(Bool())

    val eligibleInterrupt = Output(new EligibleInterrupt)
    val mstatusMie = Output(Bool())
    val mstatusFs = Output(UInt(2.W))
    val currentFflags = Output(UInt(5.W))
    val currentFrm = Output(UInt(3.W))
  })

  require(config.commitWidth == 2,
    "CommitCSRSubsystem is frozen for two-wide retirement")

  val controller = Module(new CommitController(config))
  val csr = Module(new MachineCSRFile(config))

  val laneHasBranch = VecInit((0 until config.commitWidth).map { lane =>
    io.rob(lane).bits.entry.hasBranchData
  })
  val laneVisible = Wire(Vec(config.commitWidth, Bool()))
  laneVisible(0) := io.rob(0).valid &&
    (!laneHasBranch(0) || io.branchCommit.ready)
  laneVisible(1) := io.rob(1).valid && laneVisible(0) &&
    (!laneHasBranch(1) ||
      (io.branchCommit.ready && !laneHasBranch(0)))

  for (lane <- 0 until config.commitWidth) {
    controller.io.rob(lane).valid := laneVisible(lane)
    controller.io.rob(lane).bits := io.rob(lane).bits
    io.rob(lane).ready := controller.io.rob(lane).ready
    controller.io.sideEffect(lane) := io.sideEffect(lane)
  }
  controller.io.firstFault := io.firstFault
  controller.io.robControl := io.robControl
  controller.io.eligibleInterrupt := csr.io.eligibleInterrupt
  controller.io.interruptHead := io.interruptHead
  controller.io.interruptBlocked := io.interruptBlocked
  controller.io.trapVector := csr.io.trapTarget
  controller.io.mretTarget := csr.io.mretTarget

  val retiredBranch = VecInit((0 until config.commitWidth).map { lane =>
    controller.io.retired(lane).valid &&
      controller.io.retired(lane).bits.entry.hasBranchData
  })
  io.branchCommit.valid := retiredBranch.asUInt.orR
  val branchLane = Mux(retiredBranch(0), 0.U, 1.U)
  io.branchCommit.bits.robTag :=
    controller.io.retired(branchLane).bits.robTag
  io.branchCommit.bits.index :=
    controller.io.retired(branchLane).bits.entry.branchDataIndex

  csr.io.access := io.csrAccess
  io.csrAccessData := csr.io.accessData
  io.csrAccessLegal := csr.io.accessLegal
  csr.io.commitWrite := controller.io.csrWrite
  csr.io.trapCommit := controller.io.trapCommit
  csr.io.mretCommit := controller.io.mretCommit
  csr.io.retiredInstructions := controller.io.retiredInstructions
  csr.io.fpCommit := io.fpCommit
  csr.io.interrupts := io.interrupts

  io.retired := controller.io.retired
  io.renameCommit := controller.io.renameCommit
  io.retiredInstructions := controller.io.retiredInstructions
  io.csrWrite := controller.io.csrWrite
  io.trapCommit := controller.io.trapCommit
  io.trapEntry := controller.io.trapEntry
  io.trapLane := controller.io.trapLane
  io.mretCommit := controller.io.mretCommit
  io.firstFaultClear := controller.io.firstFaultClear
  io.globalFlush := controller.io.flush
  io.redirect := controller.io.redirect
  io.fenceICommit := controller.io.fenceICommit
  io.wfiCommit := controller.io.wfiCommit
  io.eligibleInterrupt := csr.io.eligibleInterrupt
  io.mstatusMie := csr.io.mstatusMie
  io.mstatusFs := csr.io.mstatusFs
  io.currentFflags := csr.io.currentFflags
  io.currentFrm := csr.io.currentFrm

  assert(PopCount(retiredBranch) <= 1.U,
    "the single-port BDB schedule retired two branches")
  when(io.branchCommit.valid) {
    assert(io.branchCommit.ready,
      "a branch retired without an available BDB commit port")
  }
  when(io.rob(0).valid && io.rob(1).valid && laneHasBranch.asUInt.andR) {
    assert(!io.rob(1).ready,
      "two adjacent branches attempted to retire through one BDB port")
  }
  when(io.globalFlush) {
    assert(!io.rob.exists(_.ready) ||
      io.retired.map(_.valid).reduce(_ || _),
      "flush acknowledged a ROB lane without a matching retirement")
  }
}
