package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Commit-time architectural state produced by an F operation. */
class FloatingResult(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val writesFloat = Bool()
  val fprAddress = UInt(5.W)
  val fprData = UInt(32.W)
  val flags = UInt(5.W)
}

/** Four retained F results selected by ROB head rather than completion order. */
class FloatingResultQueue(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val entries = 4
  private val indexWidth = log2Ceil(entries)

  val io = IO(new Bundle {
    val enqueue = Flipped(Decoupled(new FloatingResult(config)))
    val commitTag = Input(UInt(config.robTagWidth.W))
    val commit = Decoupled(new FloatingResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(log2Ceil(entries + 1).W))
  })

  val valid = RegInit(VecInit.fill(entries)(false.B))
  val result = Reg(Vec(entries, new FloatingResult(config)))
  val recoveryBlocked = io.flush || io.squash.valid
  val matchCommit = VecInit((0 until entries).map(index =>
    valid(index) && result(index).robTag === io.commitTag))
  val commitIndex = PriorityEncoder(matchCommit.asUInt)
  val freeIndex = PriorityEncoder((~valid.asUInt).asUInt)

  io.commit.valid := matchCommit.asUInt.orR && !recoveryBlocked
  io.commit.bits := result(commitIndex)
  io.enqueue.ready := !recoveryBlocked && !valid.asUInt.andR
  io.count := PopCount(valid)

  when(io.enqueue.valid) {
    assert(io.enqueue.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "floating result queue received an out-of-range ROB tag")
    for (index <- 0 until entries) {
      assert(!(valid(index) && result(index).robTag === io.enqueue.bits.robTag),
        "floating result queue received a duplicate ROB tag")
    }
  }
  when(io.commit.valid) {
    assert(PopCount(matchCommit) === 1.U,
      "floating result queue found duplicate entries for one commit tag")
  }
  when(io.squash.valid) {
    assert(!io.enqueue.fire && !io.commit.fire,
      "floating result queue transferred work during selective squash")
  }

  when(io.flush) {
    valid.foreach(_ := false.B)
  }.elsewhen(io.squash.valid) {
    for (index <- 0 until entries) {
      when(valid(index) && ROBTagOrder.isYounger(
          result(index).robTag, io.squash.bits, io.robHeadTag, config)) {
        valid(index) := false.B
      }
    }
  }.otherwise {
    when(io.commit.fire) {
      valid(commitIndex) := false.B
    }
    when(io.enqueue.fire) {
      valid(freeIndex) := true.B
      result(freeIndex) := io.enqueue.bits
    }
  }

  assert(PopCount(valid) <= entries.U,
    "floating result queue occupancy exceeded four entries")
}
