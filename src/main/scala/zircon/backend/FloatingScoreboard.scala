package zircon.backend

import chisel3._
import chisel3.util._

class FloatingScoreboardAllocation extends Bundle {
  val sourceValid = Vec(3, Bool())
  val source = Vec(3, UInt(5.W))
  val destinationValid = Bool()
  val destination = UInt(5.W)
}

/** Hazard scoreboard for the unrenamed architectural floating register file. */
class FloatingScoreboard(maxOutstanding: Int = 4) extends Module {
  require(maxOutstanding > 0)
  private val readCountWidth = log2Ceil(maxOutstanding * 3 + 1)

  val io = IO(new Bundle {
    val allocate = Input(Vec(2, Valid(new FloatingScoreboardAllocation)))
    val allocateReady = Output(Vec(2, Bool()))
    /** Asserted when an issued F operation has consumed its source operands. */
    val readRelease = Input(Valid(new FloatingScoreboardAllocation))
    /** Asserted only by the commit-qualified F result queue. */
    val complete = Input(Valid(UInt(5.W)))
  })

  val writeBusy = RegInit(VecInit.fill(32)(false.B))
  val readCount = RegInit(VecInit.fill(32)(0.U(readCountWidth.W)))

  private def sourceHazard(allocation: FloatingScoreboardAllocation,
      busy: Vec[Bool]): Bool =
    allocation.sourceValid.zip(allocation.source).map { case (valid, source) =>
      valid && busy(source)
    }.reduce(_ || _)

  private def destinationHazard(allocation: FloatingScoreboardAllocation,
      reads: Vec[UInt], writes: Vec[Bool]): Bool =
    allocation.destinationValid &&
      (reads(allocation.destination).orR || writes(allocation.destination))

  val lane0Allowed = !sourceHazard(io.allocate(0).bits, writeBusy) &&
    !destinationHazard(io.allocate(0).bits, readCount, writeBusy)
  val lane0Fire = io.allocate(0).valid && lane0Allowed

  val writeBusyAfterLane0 = Wire(Vec(32, Bool()))
  val readCountAfterLane0 = Wire(Vec(32, UInt(readCountWidth.W)))
  for (register <- 0 until 32) {
    writeBusyAfterLane0(register) := writeBusy(register) ||
      (lane0Fire && io.allocate(0).bits.destinationValid &&
        io.allocate(0).bits.destination === register.U)
    val lane0Reads = PopCount(io.allocate(0).bits.sourceValid.zip(
      io.allocate(0).bits.source).map { case (valid, source) =>
      lane0Fire && valid && source === register.U
    })
    readCountAfterLane0(register) := readCount(register) + lane0Reads
  }

  val lane1Allowed = (!io.allocate(0).valid || lane0Fire) &&
    !sourceHazard(io.allocate(1).bits, writeBusyAfterLane0) &&
    !destinationHazard(io.allocate(1).bits, readCountAfterLane0, writeBusyAfterLane0)
  val lane1Fire = io.allocate(1).valid && lane1Allowed
  io.allocateReady(0) := lane0Allowed
  io.allocateReady(1) := lane1Allowed

  when(io.readRelease.valid) {
    io.readRelease.bits.sourceValid.zip(io.readRelease.bits.source).foreach {
      case (valid, source) => when(valid) {
        assert(readCount(source) =/= 0.U,
          "floating scoreboard released a source without a pending read")
      }
    }
  }
  when(io.complete.valid) {
    assert(writeBusy(io.complete.bits),
      "floating scoreboard completed an FPR without a pending write")
  }

  for (register <- 0 until 32) {
    val allocatedReads = PopCount(
      io.allocate(0).bits.sourceValid.zip(io.allocate(0).bits.source).map {
        case (valid, source) => lane0Fire && valid && source === register.U
      } ++ io.allocate(1).bits.sourceValid.zip(io.allocate(1).bits.source).map {
        case (valid, source) => lane1Fire && valid && source === register.U
      })
    val releasedReads = PopCount(io.readRelease.bits.sourceValid.zip(
      io.readRelease.bits.source).map { case (valid, source) =>
      io.readRelease.valid && valid && source === register.U
    })
    readCount(register) := readCount(register) + allocatedReads - releasedReads
    assert(readCount(register) + allocatedReads >= releasedReads,
      "floating scoreboard read count underflow")
    assert(readCount(register) + allocatedReads <= (maxOutstanding * 3).U,
      "floating scoreboard exceeded its configured read reservation budget")
    when(lane0Fire && io.allocate(0).bits.destinationValid &&
        io.allocate(0).bits.destination === register.U) {
      writeBusy(register) := true.B
    }.elsewhen(lane1Fire && io.allocate(1).bits.destinationValid &&
        io.allocate(1).bits.destination === register.U) {
      writeBusy(register) := true.B
    }.elsewhen(io.complete.valid && io.complete.bits === register.U) {
      writeBusy(register) := false.B
    }
  }
}
