package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Selects the oldest of two LQ cacheable-load forwards for the current
  * one-request L1D slice. A selection is locked under L1D backpressure so the
  * Decoupled output cannot change payload after valid is asserted.
  */
class DualLoadForwardArbiter(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(config.decodeWidth, Decoupled(new LoadStoreForward(config))))
    val out = Decoupled(new LoadStoreForward(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  require(config.decodeWidth == 2,
    "the frozen M3 load-forward boundary has exactly M0 and M1 candidates")

  val recoveryBlocked = io.flush || io.squash.valid
  val locked = RegInit(false.B)
  val lockedLane = RegInit(false.B)
  val firstAge = ROBTagOrder.ageFromHead(io.in(0).bits.robTag, io.robHeadTag, config)
  val secondAge = ROBTagOrder.ageFromHead(io.in(1).bits.robTag, io.robHeadTag, config)
  val chooseFirst = io.in(0).valid &&
    (!io.in(1).valid || firstAge <= secondAge)
  val selectedLane = Mux(locked, lockedLane, chooseFirst)
  val selectedValid = Mux(selectedLane, io.in(0).valid, io.in(1).valid)

  io.out.valid := selectedValid && !recoveryBlocked
  io.out.bits := Mux(selectedLane, io.in(0).bits, io.in(1).bits)
  for (lane <- 0 until config.decodeWidth) {
    val laneSelected = if (lane == 0) selectedLane else !selectedLane
    io.in(lane).ready := !recoveryBlocked && selectedValid &&
      laneSelected && io.out.ready
  }

  when(io.flush || io.squash.valid) {
    locked := false.B
  }.otherwise {
    when(io.out.fire) {
      locked := false.B
    }.elsewhen(io.out.valid && !io.out.ready) {
      locked := true.B
      lockedLane := selectedLane
    }
  }

  when(io.in(0).valid && io.in(1).valid) {
    assert(io.in(0).bits.robTag =/= io.in(1).bits.robTag,
      "dual load-forward candidates cannot carry the same ROB tag")
  }
  when(locked && !recoveryBlocked) {
    assert(selectedValid,
      "dual load-forward arbiter lost a locked candidate before handshake")
  }
  when(io.squash.valid) {
    assert(!io.out.valid && !io.in.exists(_.ready),
      "dual load-forward arbiter transferred work during selective recovery")
  }
}
