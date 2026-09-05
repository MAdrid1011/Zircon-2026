package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Non-fall-through boundary for the LSQ forward result consumed by L1D.
  *
  * The input side deliberately does not observe output.ready. This keeps cache
  * bank/MSHR backpressure from returning through the wide memory-uop payload
  * into the LSQ write-enable cone in the same cycle.
  */
class LoadForwardBoundary(config: ZirconCoreConfig) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new LoadStoreForward(config)))
    val output = Decoupled(new LoadStoreForward(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val occupied = RegInit(false.B)
  val payload = Reg(new LoadStoreForward(config))
  val killed = occupied && io.squash.valid && ROBTagOrder.isYounger(
    payload.robTag, io.squash.bits, io.robHeadTag, config)
  val recoveryBlocked = io.flush || io.squash.valid

  io.input.ready := !occupied && !recoveryBlocked
  io.output.valid := occupied && !io.flush && !killed
  io.output.bits := payload

  when(io.flush) {
    occupied := false.B
  }.elsewhen(io.squash.valid) {
    when(killed) { occupied := false.B }
  }.otherwise {
    when(io.input.fire) {
      payload := io.input.bits
      occupied := true.B
    }.elsewhen(io.output.fire) {
      occupied := false.B
    }
  }

  when(io.squash.valid) {
    assert(!io.input.fire && !io.output.fire,
      "load-forward boundary transferred work during selective squash")
  }
}

/** Non-fall-through boundary for a commit-authorized store effect.
  *
  * Store metadata is wide and its consumer's ready signal depends on L1D
  * victim/MSHR state.  Holding the effect in this register prevents that
  * state from reaching the LSQ tag/effect arbiter in the same cycle.
  */
class StoreEffectBoundary(config: ZirconCoreConfig) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new StoreEffect(config)))
    val output = Decoupled(new StoreEffect(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val occupied = RegInit(false.B)
  val payload = Reg(new StoreEffect(config))
  val killed = occupied && io.squash.valid && ROBTagOrder.isYounger(
    payload.robTag, io.squash.bits, io.robHeadTag, config)
  val blocked = io.flush || io.squash.valid

  io.input.ready := !occupied && !blocked
  io.output.valid := occupied && !io.flush && !killed
  io.output.bits := payload

  when(io.flush) {
    occupied := false.B
  }.elsewhen(io.squash.valid) {
    when(killed) { occupied := false.B }
  }.otherwise {
    when(io.input.fire) {
      payload := io.input.bits
      occupied := true.B
    }.elsewhen(io.output.fire) {
      occupied := false.B
    }
  }

  when(io.squash.valid) {
    assert(!io.input.fire && !io.output.fire,
      "store-effect boundary transferred work during selective squash")
  }
}
