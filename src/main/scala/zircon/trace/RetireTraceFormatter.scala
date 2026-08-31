package zircon.trace

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{CSRCommitWrite, ROBCommit, TrapCommit}

/** Formats true commit and trap metadata into the simulation retire boundary.
  *
  * This module is instantiated only by a trace-enabled top level. Its order
  * counter therefore cannot enter the synthesized default configuration.
  */
class RetireTraceFormatter(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val retired = Input(Vec(config.commitWidth, Valid(new ROBCommit(config))))
    val gprData = Input(Vec(config.commitWidth, UInt(32.W)))
    val csrWrite = Input(Valid(new CSRCommitWrite))
    val trapCommit = Input(Valid(new TrapCommit))
    val trapEntry = Input(Valid(new ROBCommit(config)))
    val trapLane = Input(UInt(1.W))
    val currentFflags = Input(UInt(5.W))
    val events = Output(Vec(config.commitWidth, new RetireEvent))
  })

  require(config.commitWidth == 2,
    "RetireTraceFormatter is frozen for two commit lanes")

  val nextOrder = RegInit(0.U(64.W))
  val trapValid = io.trapCommit.valid && io.trapEntry.valid
  val trapInLane = VecInit((0 until config.commitWidth).map(lane =>
    trapValid && io.trapLane === lane.U))
  val eventValid = VecInit((0 until config.commitWidth).map(lane =>
    io.retired(lane).valid || trapInLane(lane)))

  for (lane <- 0 until config.commitWidth) {
    val event = WireDefault(0.U.asTypeOf(new RetireEvent))
    val retired = io.retired(lane)
    val entry = retired.bits.entry
    val trapEntry = io.trapEntry.bits.entry
    val isTrap = trapInLane(lane)

    event.valid := eventValid(lane)
    event.order := nextOrder + PopCount(eventValid.take(lane))
    event.pc := Mux(isTrap, trapEntry.pc, entry.pc)
    event.instruction := Mux(isTrap, trapEntry.instruction, entry.instruction)
    event.privilege := Mux(isTrap, trapEntry.privilege, entry.privilege)

    event.gprWrite := retired.valid && entry.allocatesPhysical && !isTrap
    event.gprAddress := entry.architecturalDestination
    event.gprData := io.gprData(lane)
    event.fprWrite := false.B
    event.fprAddress := 0.U
    event.fprData := 0.U

    event.csrWrite := retired.valid && lane.U === 0.U && io.csrWrite.valid &&
      !isTrap
    event.csrAddress := io.csrWrite.bits.address
    event.csrData := io.csrWrite.bits.data
    event.memoryAddress := 0.U
    event.memoryReadMask := 0.U
    event.memoryWriteMask := 0.U
    event.memoryReadData := 0.U
    event.memoryWriteData := 0.U
    event.trap := isTrap
    event.interrupt := isTrap && io.trapCommit.bits.interrupt
    event.cause := Mux(isTrap,
      Cat(io.trapCommit.bits.interrupt, io.trapCommit.bits.cause), 0.U)
    event.trapValue := Mux(isTrap, io.trapCommit.bits.trapValue, 0.U)
    event.fflags := io.currentFflags
    io.events(lane) := event
  }

  nextOrder := nextOrder + PopCount(eventValid)

  assert(!io.retired(1).valid || io.retired(0).valid,
    "retire trace lane 1 cannot be valid without lane 0")
  assert(!trapValid || io.trapLane < config.commitWidth.U,
    "retire trace trap lane is out of range")
  for (lane <- 0 until config.commitWidth) {
    assert(!(io.retired(lane).valid && trapInLane(lane)),
      "one trace lane cannot retire and trap for different instructions")
  }
  assert(!eventValid(1) || eventValid(0),
    "retire trace lane 1 event cannot exist without a lane 0 event")
}
