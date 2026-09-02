package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** One commit-qualified FPR write, including the retired ROB owner needed by
  * the simulation trace boundary. */
class FloatingRegisterWrite(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(5.W)
  val data = UInt(32.W)
}

/** Commit-qualified architectural state for retained floating results.
  *
  * This module deliberately owns no decode or execution path. A future E2 FPU
  * may enqueue results in any order, but only the result matching `commitTag`
  * can update FPR state, release a destination reservation, and accumulate
  * `fflags` in the same commit fire.
  */
class FloatingCommitState(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val enqueue = Flipped(Decoupled(new FloatingResult(config)))
    val commitTag = Input(UInt(config.robTagWidth.W))
    val commitEnable = Input(Bool())
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())

    val readAddress = Input(Vec(2, UInt(5.W)))
    val readData = Output(Vec(2, UInt(32.W)))
    val fprWrite = Output(Valid(new FloatingRegisterWrite(config)))
    val scoreboardComplete = Output(Valid(new FloatingScoreboardCompletion(config)))
    val fpCommit = Output(Valid(new FloatingStateCommit))
    val resultCount = Output(UInt(3.W))
  })

  val resultQueue = Module(new FloatingResultQueue(config))
  val fpr = Module(new FloatingRegisterFile)

  resultQueue.io.enqueue <> io.enqueue
  resultQueue.io.commitTag := io.commitTag
  resultQueue.io.robHeadTag := io.robHeadTag
  resultQueue.io.squash := io.squash
  resultQueue.io.flush := io.flush
  resultQueue.io.commit.ready := io.commitEnable
  io.resultCount := resultQueue.io.count

  fpr.io.readAddress := io.readAddress
  io.readData := fpr.io.readData
  fpr.io.write.valid := resultQueue.io.commit.fire && resultQueue.io.commit.bits.writesFloat
  fpr.io.write.bits.address := resultQueue.io.commit.bits.fprAddress
  fpr.io.write.bits.data := resultQueue.io.commit.bits.fprData
  io.fprWrite.valid := fpr.io.write.valid
  io.fprWrite.bits.robTag := resultQueue.io.commit.bits.robTag
  io.fprWrite.bits.address := fpr.io.write.bits.address
  io.fprWrite.bits.data := fpr.io.write.bits.data

  io.scoreboardComplete.valid := resultQueue.io.commit.fire &&
    resultQueue.io.commit.bits.writesFloat
  io.scoreboardComplete.bits.robTag := resultQueue.io.commit.bits.robTag
  io.scoreboardComplete.bits.destination := resultQueue.io.commit.bits.fprAddress
  io.fpCommit.valid := resultQueue.io.commit.fire
  io.fpCommit.bits.flags := resultQueue.io.commit.bits.flags
  io.fpCommit.bits.dirty := resultQueue.io.commit.fire

  when(resultQueue.io.commit.fire) {
    assert(io.commitEnable, "floating state changed without a commit grant")
  }
  when(io.squash.valid || io.flush) {
    assert(!resultQueue.io.commit.fire,
      "floating state changed during recovery")
  }
}
