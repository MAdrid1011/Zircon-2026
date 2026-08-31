package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Lossless bridge from a BDB misprediction result to backend rollback and
  * frontend recovery.
  *
  * The squash and frontend-recovery outputs are one-cycle events at resolution
  * acceptance. A temporarily blocked ROB request is retained until accepted;
  * dispatch remains blocked until the ROB reports that tail walking finished.
  */
class BranchRecoveryController(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val resolution = Flipped(Decoupled(new BranchResolutionResult(config)))
    val robRollback = Decoupled(UInt(config.robTagWidth.W))
    val robRollbackDone = Input(Bool())
    val globalFlush = Input(Bool())

    val squash = Output(Valid(UInt(config.robTagWidth.W)))
    val frontendRecovery = Output(Valid(new BranchResolutionResult(config)))
    val dispatchBlocked = Output(Bool())
    val recoveryActive = Output(Bool())
  })

  val pendingRollback = RegInit(false.B)
  val pendingTag = Reg(UInt(config.robTagWidth.W))
  val waitingForDone = RegInit(false.B)

  val idle = !pendingRollback && !waitingForDone
  io.resolution.ready := idle && !io.globalFlush
  val acceptedMispredict = io.resolution.fire && io.resolution.bits.mispredict

  io.robRollback.valid := !io.globalFlush &&
    (pendingRollback || acceptedMispredict)
  io.robRollback.bits := Mux(pendingRollback,
    pendingTag, io.resolution.bits.reference.robTag)
  val rollbackFire = io.robRollback.fire

  io.squash.valid := acceptedMispredict && !io.globalFlush
  io.squash.bits := io.resolution.bits.reference.robTag
  io.frontendRecovery.valid := acceptedMispredict && !io.globalFlush
  io.frontendRecovery.bits := io.resolution.bits

  io.dispatchBlocked := pendingRollback || waitingForDone ||
    acceptedMispredict
  io.recoveryActive := pendingRollback || waitingForDone

  when(io.globalFlush) {
    pendingRollback := false.B
    waitingForDone := false.B
  }.otherwise {
    when(acceptedMispredict) {
      pendingRollback := true.B
      pendingTag := io.resolution.bits.reference.robTag
    }
    when(rollbackFire) {
      pendingRollback := false.B
      waitingForDone := !io.robRollbackDone
    }.elsewhen(waitingForDone && io.robRollbackDone) {
      waitingForDone := false.B
    }
  }

  when(io.resolution.fire && !io.resolution.bits.mispredict) {
    assert(!io.squash.valid && !io.frontendRecovery.valid,
      "a correctly predicted branch started recovery")
  }
  when(io.squash.valid) {
    assert(io.frontendRecovery.valid,
      "backend squash and frontend recovery must be atomic")
    assert(io.squash.bits === io.frontendRecovery.bits.reference.robTag,
      "backend and frontend recovery tags diverged")
    assert(io.dispatchBlocked,
      "dispatch was not blocked in the recovery launch cycle")
  }
  when(io.robRollbackDone) {
    assert(rollbackFire || waitingForDone,
      "ROB rollback completed without an outstanding recovery")
  }
  when(pendingRollback) {
    assert(io.dispatchBlocked,
      "dispatch was not blocked by a pending ROB rollback request")
  }
}
