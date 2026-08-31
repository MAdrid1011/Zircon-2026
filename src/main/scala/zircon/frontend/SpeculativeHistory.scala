package zircon.frontend

import chisel3._
import chisel3.util.{Cat, Valid}
import zircon.ZirconCoreConfig

/** Four-slot scan and checkpoint generator for 64-bit speculative history. */
class SpeculativeGlobalHistory(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val historyWidth = 64

  val io = IO(new Bundle {
    val slotValid = Input(Vec(config.fetchWidth, Bool()))
    val conditional = Input(Vec(config.fetchWidth, Bool()))
    val predictedTaken = Input(Vec(config.fetchWidth, Bool()))
    val acceptedMask = Input(UInt(config.fetchWidth.W))
    val advance = Input(Bool())
    val recover = Input(Valid(UInt(historyWidth.W)))
    val clear = Input(Bool())
    val historyBefore = Output(Vec(config.fetchWidth, UInt(historyWidth.W)))
    val historyAfter = Output(UInt(historyWidth.W))
    val current = Output(UInt(historyWidth.W))
  })

  require(config.fetchWidth == 4,
    "the speculative history scan is frozen for four-wide fetch")

  val history = RegInit(0.U(historyWidth.W))
  val scan = Wire(Vec(config.fetchWidth + 1, UInt(historyWidth.W)))
  scan(0) := history
  for (slot <- 0 until config.fetchWidth) {
    io.historyBefore(slot) := scan(slot)
    val append = io.slotValid(slot) && io.acceptedMask(slot) &&
      io.conditional(slot)
    scan(slot + 1) := Mux(append,
      Cat(scan(slot)(historyWidth - 2, 0), io.predictedTaken(slot)),
      scan(slot))
  }
  io.historyAfter := scan(config.fetchWidth)
  io.current := history

  when(io.clear) {
    history := 0.U
  }.elsewhen(io.recover.valid) {
    history := io.recover.bits
  }.elsewhen(io.advance) {
    history := io.historyAfter
  }

  private def isPrefix(mask: UInt): Bool =
    (0 to config.fetchWidth).map(length =>
      mask === ((BigInt(1) << length) - 1).U(config.fetchWidth.W)
    ).reduce(_ || _)

  assert(isPrefix(io.slotValid.asUInt),
    "fetch slot validity must be a low-order prefix")
  assert(isPrefix(io.acceptedMask),
    "accepted fetch slots must be a low-order prefix")
  assert((io.acceptedMask & ~io.slotValid.asUInt) === 0.U,
    "history accepted a fetch slot that was not valid")
}
