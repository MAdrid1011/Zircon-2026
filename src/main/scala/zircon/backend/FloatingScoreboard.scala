package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class FloatingScoreboardAllocation(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val sourceValid = Vec(3, Bool())
  val source = Vec(3, UInt(5.W))
  val destinationValid = Bool()
  val destination = UInt(5.W)
}

class FloatingScoreboardCompletion(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val destination = UInt(5.W)
}

/** Hazard scoreboard for the unrenamed architectural floating register file. */
class FloatingScoreboard(
    config: ZirconCoreConfig = ZirconCoreConfig.default,
    maxOutstanding: Int = 4
) extends Module {
  require(maxOutstanding > 0)

  val io = IO(new Bundle {
    val allocate = Input(Vec(2, Valid(new FloatingScoreboardAllocation(config))))
    val allocateReady = Output(Vec(2, Bool()))
    val readRelease = Input(Valid(new FloatingScoreboardAllocation(config)))
    val complete = Input(Valid(new FloatingScoreboardCompletion(config)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val valid = RegInit(VecInit.fill(maxOutstanding)(false.B))
  val reservation = Reg(Vec(maxOutstanding, new FloatingScoreboardAllocation(config)))
  val sourceConsumed = RegInit(VecInit.fill(maxOutstanding)(false.B))
  val recoveryBlocked = io.flush || io.squash.valid

  def anyEntry(predicate: Int => Bool): Bool =
    (0 until maxOutstanding).map(predicate).reduce(_ || _)

  def sourceBusy(register: UInt): Bool = anyEntry { entry =>
    valid(entry) && reservation(entry).destinationValid &&
      reservation(entry).destination === register
  }

  def readBusy(register: UInt): Bool = anyEntry { entry =>
    valid(entry) && !sourceConsumed(entry) &&
      reservation(entry).sourceValid.zip(reservation(entry).source).map {
        case (present, source) => present && source === register
      }.reduce(_ || _)
  }

  def allocationSourceHazard(allocation: FloatingScoreboardAllocation,
      extraDestination: Valid[UInt]): Bool =
    allocation.sourceValid.zip(allocation.source).map { case (present, source) =>
      present && (sourceBusy(source) ||
        (extraDestination.valid && extraDestination.bits === source))
    }.reduce(_ || _)

  def allocationDestinationHazard(allocation: FloatingScoreboardAllocation,
      extraReads: Vec[Bool], extraDestination: Valid[UInt]): Bool =
    allocation.destinationValid && (readBusy(allocation.destination) ||
      sourceBusy(allocation.destination) || extraReads(allocation.destination) ||
      (extraDestination.valid && extraDestination.bits === allocation.destination))

  val free = VecInit((0 until maxOutstanding).map(entry => !valid(entry)))
  val hasFree = free.asUInt.orR
  val freeIndex = PriorityEncoder(free.asUInt)
  val noExtraDestination = Wire(Valid(UInt(5.W)))
  noExtraDestination.valid := false.B
  noExtraDestination.bits := 0.U
  val noExtraReads = Wire(Vec(32, Bool()))
  noExtraReads.foreach(_ := false.B)

  val lane0Allowed = hasFree && !allocationSourceHazard(io.allocate(0).bits,
    noExtraDestination) && !allocationDestinationHazard(io.allocate(0).bits,
    noExtraReads, noExtraDestination)
  val lane0Fire = io.allocate(0).valid && lane0Allowed && !recoveryBlocked
  val lane0Destination = Wire(Valid(UInt(5.W)))
  lane0Destination.valid := lane0Fire && io.allocate(0).bits.destinationValid
  lane0Destination.bits := io.allocate(0).bits.destination
  val lane0Reads = Wire(Vec(32, Bool()))
  for (register <- 0 until 32) {
    lane0Reads(register) := lane0Fire && io.allocate(0).bits.sourceValid.zip(
      io.allocate(0).bits.source).map { case (present, source) =>
        present && source === register.U
      }.reduce(_ || _)
  }
  val secondFree = VecInit((0 until maxOutstanding).map(entry =>
    !valid(entry) && entry.U =/= freeIndex))
  val hasSecondFree = secondFree.asUInt.orR
  val secondFreeIndex = PriorityEncoder(secondFree.asUInt)
  val lane1HasSlot = Mux(lane0Fire, hasSecondFree, hasFree)
  val lane1Index = Mux(lane0Fire, secondFreeIndex, freeIndex)
  val lane1Allowed = (!io.allocate(0).valid || lane0Fire) && lane1HasSlot &&
    !allocationSourceHazard(io.allocate(1).bits, lane0Destination) &&
    !allocationDestinationHazard(io.allocate(1).bits, lane0Reads, lane0Destination)
  val lane1Fire = io.allocate(1).valid && lane1Allowed && !recoveryBlocked
  io.allocateReady(0) := lane0Allowed && !recoveryBlocked
  io.allocateReady(1) := lane1Allowed && !recoveryBlocked

  val releaseMatch = VecInit((0 until maxOutstanding).map(entry =>
    valid(entry) && reservation(entry).robTag === io.readRelease.bits.robTag))
  val releaseIndex = PriorityEncoder(releaseMatch.asUInt)
  val completeMatch = VecInit((0 until maxOutstanding).map(entry =>
    valid(entry) && reservation(entry).destinationValid &&
      reservation(entry).robTag === io.complete.bits.robTag))
  val completeIndex = PriorityEncoder(completeMatch.asUInt)

  when(io.readRelease.valid) {
    assert(releaseMatch.asUInt.orR,
      "floating scoreboard released a non-live ROB-tagged reservation")
    assert(PopCount(releaseMatch) === 1.U,
      "floating scoreboard found duplicate reservations for one ROB tag")
    assert(!sourceConsumed(releaseIndex),
      "floating scoreboard released FPR operands more than once")
    assert(io.readRelease.bits.asUInt === reservation(releaseIndex).asUInt,
      "floating scoreboard release payload did not match its reservation")
  }
  when(io.complete.valid) {
    assert(completeMatch.asUInt.orR,
      "floating scoreboard completed a non-live FPR destination")
    assert(PopCount(completeMatch) === 1.U,
      "floating scoreboard found duplicate completion ROB tags")
    assert(sourceConsumed(completeIndex),
      "floating scoreboard completed an FPR destination before source consumption")
    assert(reservation(completeIndex).destination === io.complete.bits.destination,
      "floating scoreboard completion destination did not match its reservation")
  }
  when(recoveryBlocked) {
    assert(!io.readRelease.valid && !io.complete.valid,
      "floating scoreboard transferred a release or completion during recovery")
  }

  when(io.flush) {
    valid.foreach(_ := false.B)
    sourceConsumed.foreach(_ := false.B)
  }.elsewhen(io.squash.valid) {
    for (entry <- 0 until maxOutstanding) {
      when(valid(entry) && ROBTagOrder.isYounger(reservation(entry).robTag,
          io.squash.bits, io.robHeadTag, config)) {
        valid(entry) := false.B
        sourceConsumed(entry) := false.B
      }
    }
  }.otherwise {
    when(io.readRelease.valid) {
      sourceConsumed(releaseIndex) := true.B
      when(!reservation(releaseIndex).destinationValid) {
        valid(releaseIndex) := false.B
      }
    }
    when(io.complete.valid) {
      valid(completeIndex) := false.B
      sourceConsumed(completeIndex) := false.B
    }
    when(lane0Fire) {
      valid(freeIndex) := true.B
      reservation(freeIndex) := io.allocate(0).bits
      sourceConsumed(freeIndex) := false.B
    }
    when(lane1Fire) {
      valid(lane1Index) := true.B
      reservation(lane1Index) := io.allocate(1).bits
      sourceConsumed(lane1Index) := false.B
    }
  }

  assert(PopCount(valid) <= maxOutstanding.U,
    "floating scoreboard occupancy exceeded its configured reservation budget")
}
