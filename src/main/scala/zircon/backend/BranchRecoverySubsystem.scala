package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** BDB plus the lossless execute-time branch recovery controller. */
class BranchRecoverySubsystem(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val allocate = Flipped(Decoupled(new BranchDataAllocation(config)))
    val allocatedIndex = Output(Valid(
      UInt(log2Ceil(config.branchDataEntries).W)))
    val resolve = Flipped(Decoupled(new BranchResolutionRequest(config)))
    val commit = Flipped(Decoupled(new BranchDataReference(config)))
    val training = Output(Valid(new BranchTrainingRecord(config)))

    val robRollback = Decoupled(UInt(config.robTagWidth.W))
    val robRollbackDone = Input(Bool())
    val squash = Output(Valid(UInt(config.robTagWidth.W)))
    val frontendRecovery = Output(Valid(
      new BranchResolutionResult(config)))
    val dispatchBlocked = Output(Bool())
    val recoveryActive = Output(Bool())
    val globalFlush = Input(Bool())
    val count = Output(UInt(log2Ceil(config.branchDataEntries + 1).W))
  })

  val bdb = Module(new BranchDataBuffer(config))
  val controller = Module(new BranchRecoveryController(config))

  bdb.io.robHeadTag := io.robHeadTag
  bdb.io.allocate <> io.allocate
  io.allocatedIndex := bdb.io.allocatedIndex
  bdb.io.resolve <> io.resolve
  controller.io.resolution <> bdb.io.resolution
  bdb.io.commit <> io.commit
  io.training := bdb.io.training
  bdb.io.flushAll := io.globalFlush
  io.count := bdb.io.count

  io.robRollback <> controller.io.robRollback
  controller.io.robRollbackDone := io.robRollbackDone
  controller.io.globalFlush := io.globalFlush
  io.squash := controller.io.squash
  io.frontendRecovery := controller.io.frontendRecovery
  io.dispatchBlocked := controller.io.dispatchBlocked
  io.recoveryActive := controller.io.recoveryActive

  when(io.squash.valid) {
    assert(io.frontendRecovery.valid && io.dispatchBlocked,
      "branch recovery launch was not broadcast atomically")
    assert(io.robRollback.valid,
      "branch recovery launch did not create a ROB rollback request")
  }
  when(io.globalFlush) {
    assert(!io.robRollback.valid && !io.squash.valid,
      "branch recovery escaped a global flush")
  }
}
