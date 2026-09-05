package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Small production boundary that coalesces staggered cacheable forwards.
  *
  * The LSQ may publish two independent loads on the same forward lane in
  * adjacent cycles. Retaining up to two owners here lets the two-port L1D see
  * them together without allowing L1D backpressure to reach LSQ allocation.
  */
class LoadForwardPairBuffer(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Vec(config.decodeWidth,
      Decoupled(new LoadStoreForward(config))))
    val output = Vec(config.decodeWidth,
      Decoupled(new LoadStoreForward(config)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  require(config.decodeWidth == 2,
    "the frozen M3 load-forward pair buffer is two-wide")

  val valid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val payload = Reg(Vec(2, new LoadStoreForward(config)))
  val killed = VecInit((0 until 2).map(slot => valid(slot) &&
    io.squash.valid && ROBTagOrder.isYounger(payload(slot).robTag,
      io.squash.bits, io.robHeadTag, config)))
  val incomingValid = io.input.map(_.valid).reduce(_ || _)
  val holdForSecond = valid(0) && !valid(1) && incomingValid

  for (slot <- 0 until 2) {
    io.output(slot).valid := valid(slot) && !killed(slot) && !io.flush &&
      (if (slot == 0) !holdForSecond else true.B)
    io.output(slot).bits := payload(slot)
  }

  val outputFire = io.output.map(_.fire).reduce(_ || _)
  val free0 = !valid(0)
  val free1 = !valid(1)
  val bothFree = free0 && free1
  // Do not replace an owner on the same edge it is consumed. This keeps the
  // compaction state one-hot and costs only a bubble after a completed pair.
  val accepting = !io.flush && !io.squash.valid && !outputFire
  val accept0 = accepting && io.input(0).valid && (free0 || free1)
  val accept1 = accepting && io.input(1).valid &&
    (bothFree || ((free0 || free1) && !accept0))
  io.input(0).ready := accept0
  io.input(1).ready := accept1

  when(io.flush) {
    valid.foreach(_ := false.B)
  }.elsewhen(io.squash.valid) {
    for (slot <- 0 until 2) {
      when(killed(slot)) { valid(slot) := false.B }
    }
    when(killed(0) && !killed(1)) {
      payload(0) := payload(1)
      valid(0) := true.B
      valid(1) := false.B
    }
  }.elsewhen(outputFire) {
    when(io.output(0).fire) {
      when(valid(1)) {
        payload(0) := payload(1)
        valid(0) := true.B
        valid(1) := false.B
      }.otherwise {
        valid(0) := false.B
      }
    }.elsewhen(io.output(1).fire) {
      valid(1) := false.B
    }
  }.otherwise {
    when(accept0) {
      when(free0) {
        payload(0) := io.input(0).bits
        valid(0) := true.B
      }.otherwise {
        payload(1) := io.input(0).bits
        valid(1) := true.B
      }
    }
    when(accept1) {
      when(bothFree) {
        payload(1) := io.input(1).bits
        valid(1) := true.B
      }.elsewhen(free0 && !accept0) {
        payload(0) := io.input(1).bits
        valid(0) := true.B
      }.otherwise {
        payload(1) := io.input(1).bits
        valid(1) := true.B
      }
    }
  }

  when(io.squash.valid) {
    assert(!io.input(0).fire && !io.input(1).fire &&
      !io.output(0).fire && !io.output(1).fire,
      "load-forward pair buffer transferred work during selective squash")
  }
}
