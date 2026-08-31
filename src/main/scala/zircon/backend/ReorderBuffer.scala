package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.DecodedInstruction

class ROBEntry(config: ZirconCoreConfig) extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val privilege = UInt(2.W)
  val decoded = new DecodedInstruction

  val architecturalDestination = UInt(5.W)
  val oldPhysicalDestination = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val newPhysicalDestination = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val allocatesPhysical = Bool()

  val hasBranchData = Bool()
  val branchDataIndex = UInt(log2Ceil(config.branchDataEntries).W)
}

class ROBEnqueue(config: ZirconCoreConfig) extends Bundle {
  val entry = new ROBEntry(config)
  val initiallyComplete = Bool()
}

class ROBCompletion(config: ZirconCoreConfig) extends Bundle {
  val valid = Bool()
  val robTag = UInt(config.robTagWidth.W)
}

class ROBCommit(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val entry = new ROBEntry(config)
}

/** Narrow context read by E0/E1 after issue; large ROB-only fields stay out of IQ. */
class ROBExecutionContext(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val pc = UInt(32.W)
  val privilege = UInt(2.W)
  val csrAddress = UInt(12.W)
  val csrImmediate = UInt(5.W)
  val csrRead = Bool()
  val csrWrite = Bool()
  val hasBranchData = Bool()
  val branchDataIndex = UInt(log2Ceil(config.branchDataEntries).W)
}

class ROBRollbackRecord(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val architecturalDestination = UInt(5.W)
  val oldPhysicalDestination = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val newPhysicalDestination = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val allocatesPhysical = Bool()
}

class ROBRollbackBundle(config: ZirconCoreConfig) extends Bundle {
  val count = UInt(2.W)
  val records = Vec(2, new ROBRollbackRecord(config))
}

/** 24-entry ROB with dual enqueue/complete/commit and two-entry tail rollback. */
class ReorderBuffer(config: ZirconCoreConfig) extends Module {
  private val entries = config.robEntries
  private val indexWidth = config.robIndexWidth
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(2, Decoupled(new ROBEnqueue(config))))
    val enqueueTag = Output(Vec(2, Valid(UInt(config.robTagWidth.W))))
    val completion = Input(Vec(2, new ROBCompletion(config)))
    val completionAccepted = Output(Vec(2, Bool()))
    val completionDiscarded = Output(Vec(2, Bool()))
    val commit = Vec(2, Decoupled(new ROBCommit(config)))
    val flush = Input(Bool())

    val executionRead = Input(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val executionContext = Output(Vec(2,
      Valid(new ROBExecutionContext(config))))

    val rollback = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val rollbackUndo = Decoupled(new ROBRollbackBundle(config))
    val rollbackActive = Output(Bool())
    val rollbackDone = Output(Bool())

    val headTag = Output(UInt(config.robTagWidth.W))
    val count = Output(UInt(countWidth.W))
    val enqueueCapacity = Output(UInt(2.W))
  })

  val entryData = Reg(Vec(entries, new ROBEntry(config)))
  val entryValid = RegInit(VecInit.fill(entries)(false.B))
  val entryComplete = RegInit(VecInit.fill(entries)(false.B))
  // Initialize to one so the first allocation receives generation zero.
  val entryGeneration = RegInit(VecInit.fill(entries)(true.B))

  val headIndex = RegInit(0.U(indexWidth.W))
  val tailIndex = RegInit(0.U(indexWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val rollbackActive = RegInit(false.B)
  val rollbackStopIndex = RegInit(0.U(indexWidth.W))

  private def advance(index: UInt, amount: UInt): UInt = {
    val sum = index +& amount
    val normalized = Mux(sum >= entries.U, sum - entries.U, sum)
    normalized(indexWidth - 1, 0)
  }

  private def previous(index: UInt): UInt =
    Mux(index === 0.U, (entries - 1).U, index - 1.U)

  private def tag(generation: Bool, index: UInt): UInt = generation ## index

  val secondHeadIndex = advance(headIndex, 1.U)
  val headMatches = entryValid(headIndex)
  val secondHeadMatches = entryValid(secondHeadIndex)
  val normalBlocked = io.flush || rollbackActive || io.rollback.valid

  io.commit(0).valid := !normalBlocked && count =/= 0.U &&
    headMatches && entryComplete(headIndex)
  io.commit(0).bits.robTag := tag(entryGeneration(headIndex), headIndex)
  io.commit(0).bits.entry := entryData(headIndex)
  io.commit(1).valid := !normalBlocked && count > 1.U &&
    io.commit(0).valid && secondHeadMatches && entryComplete(secondHeadIndex)
  io.commit(1).bits.robTag :=
    tag(entryGeneration(secondHeadIndex), secondHeadIndex)
  io.commit(1).bits.entry := entryData(secondHeadIndex)

  assert(!io.commit(1).ready || io.commit(0).ready,
    "ROB commit lane 1 cannot be ready when lane 0 is blocked")
  assert(!io.enqueue(1).valid || io.enqueue(0).valid,
    "ROB enqueue lane 1 cannot be valid when lane 0 is a bubble")

  val commitCount = PopCount(io.commit.map(_.fire))
  val requestedEnqueueCount = PopCount(io.enqueue.map(_.valid))
  val freeSlots = entries.U - count
  val immediateFreeSlots = freeSlots + commitCount
  io.enqueueCapacity := Mux(normalBlocked, 0.U,
    Mux(immediateFreeSlots >= 2.U, 2.U, immediateFreeSlots(1, 0)))
  val enqueueBundleReady = !normalBlocked &&
    immediateFreeSlots >= requestedEnqueueCount
  io.enqueue.foreach(_.ready := enqueueBundleReady)
  val enqueueCount = PopCount(io.enqueue.map(_.fire))

  val secondTailIndex = advance(tailIndex, 1.U)
  val allocationGeneration = VecInit(
    !entryGeneration(tailIndex), !entryGeneration(secondTailIndex))
  io.enqueueTag(0).valid := io.enqueue(0).fire
  io.enqueueTag(0).bits := tag(allocationGeneration(0), tailIndex)
  io.enqueueTag(1).valid := io.enqueue(1).fire
  io.enqueueTag(1).bits := tag(allocationGeneration(1), secondTailIndex)

  val completionIndex = Wire(Vec(2, UInt(indexWidth.W)))
  val completionMatch = Wire(Vec(2, Bool()))
  for (port <- 0 until 2) {
    val rawIndex = io.completion(port).robTag(indexWidth - 1, 0)
    val inRange = rawIndex < entries.U
    val safeIndex = Mux(inRange, rawIndex, 0.U)
    val generation = io.completion(port).robTag(config.robTagWidth - 1)
    completionIndex(port) := safeIndex
    completionMatch(port) := inRange && entryValid(safeIndex) &&
      entryGeneration(safeIndex) === generation
    io.completionAccepted(port) := io.completion(port).valid &&
      completionMatch(port) && !normalBlocked
    io.completionDiscarded(port) := io.completion(port).valid &&
      !completionMatch(port) && !normalBlocked
    when(io.completion(port).valid) {
      assert(inRange, "ROB completion tag index out of range")
    }
    when(io.completionAccepted(port)) {
      assert(!entryComplete(safeIndex), "ROB received a duplicate completion")
    }
  }
  assert(!(io.completion(0).valid && io.completion(1).valid &&
    io.completion(0).robTag === io.completion(1).robTag),
    "completion ports must not carry the same ROB tag")

  for (port <- 0 until 2) {
    val rawIndex = io.executionRead(port).bits(indexWidth - 1, 0)
    val inRange = rawIndex < entries.U
    val safeIndex = Mux(inRange, rawIndex, 0.U)
    val generation = io.executionRead(port).bits(config.robTagWidth - 1)
    val matches = inRange && entryValid(safeIndex) &&
      entryGeneration(safeIndex) === generation
    val entry = entryData(safeIndex)

    io.executionContext(port).valid := io.executionRead(port).valid &&
      matches && !io.flush
    io.executionContext(port).bits.robTag := io.executionRead(port).bits
    io.executionContext(port).bits.pc := entry.pc
    io.executionContext(port).bits.privilege := entry.privilege
    io.executionContext(port).bits.csrAddress := entry.decoded.csrAddress
    io.executionContext(port).bits.csrImmediate := entry.decoded.csrImmediate
    io.executionContext(port).bits.csrRead := entry.decoded.csrRead
    io.executionContext(port).bits.csrWrite := entry.decoded.csrWrite
    io.executionContext(port).bits.hasBranchData := entry.hasBranchData
    io.executionContext(port).bits.branchDataIndex := entry.branchDataIndex

    when(io.executionRead(port).valid && !io.flush) {
      assert(inRange, "ROB execution-context tag index out of range")
      assert(matches, "ROB execution-context tag did not match a live entry")
    }
  }
  assert(!(io.executionRead(0).valid && io.executionRead(1).valid &&
    io.executionRead(0).bits === io.executionRead(1).bits),
    "execution-context ports requested the same ROB tag")

  val rollbackIndexRaw = io.rollback.bits(indexWidth - 1, 0)
  val rollbackIndexInRange = rollbackIndexRaw < entries.U
  val rollbackIndex = Mux(rollbackIndexInRange, rollbackIndexRaw, 0.U)
  val rollbackGeneration = io.rollback.bits(config.robTagWidth - 1)
  val rollbackTargetMatch = rollbackIndexInRange && entryValid(rollbackIndex) &&
    entryGeneration(rollbackIndex) === rollbackGeneration
  val requestedStopIndex = advance(rollbackIndex, 1.U)
  val requestHasYounger = tailIndex =/= requestedStopIndex
  io.rollback.ready := !io.flush && !rollbackActive
  val rollbackStart = io.rollback.fire

  val youngestIndex = previous(tailIndex)
  val nextYoungestIndex = previous(youngestIndex)
  val oneRollbackEntry = youngestIndex === rollbackStopIndex
  val rollbackCount = Mux(oneRollbackEntry, 1.U, 2.U)
  val tailAfterRollback = Mux(oneRollbackEntry,
    youngestIndex, nextYoungestIndex)

  io.rollbackUndo.valid := rollbackActive && !io.flush
  io.rollbackUndo.bits.count := rollbackCount
  val rollbackIndices = VecInit(youngestIndex, nextYoungestIndex)
  for (lane <- 0 until 2) {
    val index = rollbackIndices(lane)
    val laneActive = lane.U < rollbackCount
    io.rollbackUndo.bits.records(lane).robTag :=
      Mux(laneActive, tag(entryGeneration(index), index), 0.U)
    io.rollbackUndo.bits.records(lane).architecturalDestination :=
      Mux(laneActive, entryData(index).architecturalDestination, 0.U)
    io.rollbackUndo.bits.records(lane).oldPhysicalDestination :=
      Mux(laneActive, entryData(index).oldPhysicalDestination, 0.U)
    io.rollbackUndo.bits.records(lane).newPhysicalDestination :=
      Mux(laneActive, entryData(index).newPhysicalDestination, 0.U)
    io.rollbackUndo.bits.records(lane).allocatesPhysical :=
      laneActive && entryData(index).allocatesPhysical
  }
  val rollbackUndoFire = io.rollbackUndo.fire
  val rollbackFinishes = rollbackUndoFire &&
    tailAfterRollback === rollbackStopIndex
  io.rollbackDone := (rollbackStart && rollbackTargetMatch &&
    !requestHasYounger) || rollbackFinishes
  io.rollbackActive := rollbackActive

  when(rollbackStart) {
    assert(rollbackTargetMatch,
      "ROB rollback target did not match a live entry")
  }
  when(io.rollbackUndo.valid) {
    assert(entryValid(youngestIndex),
      "ROB rollback tail did not reference a live youngest entry")
    when(!oneRollbackEntry) {
      assert(entryValid(nextYoungestIndex),
        "ROB rollback second tail entry was not live")
    }
  }

  val headAfterCommit = advance(headIndex, commitCount)
  val tailAfterEnqueue = advance(tailIndex, enqueueCount)

  when(io.flush) {
    entryValid.foreach(_ := false.B)
    entryComplete.foreach(_ := false.B)
    count := 0.U
    tailIndex := headIndex
    rollbackActive := false.B
  }.elsewhen(rollbackActive) {
    when(rollbackUndoFire) {
      entryValid(youngestIndex) := false.B
      entryComplete(youngestIndex) := false.B
      when(!oneRollbackEntry) {
        entryValid(nextYoungestIndex) := false.B
        entryComplete(nextYoungestIndex) := false.B
      }
      tailIndex := tailAfterRollback
      count := count - rollbackCount
      when(rollbackFinishes) {
        rollbackActive := false.B
      }
    }
  }.elsewhen(rollbackStart) {
    when(rollbackTargetMatch && requestHasYounger) {
      rollbackActive := true.B
      rollbackStopIndex := requestedStopIndex
    }
  }.otherwise {
    count := count + enqueueCount - commitCount
    headIndex := headAfterCommit
    tailIndex := tailAfterEnqueue

    for (port <- 0 until 2) {
      when(io.completionAccepted(port)) {
        entryComplete(completionIndex(port)) := true.B
      }
    }

    when(io.commit(0).fire) {
      entryValid(headIndex) := false.B
      entryComplete(headIndex) := false.B
    }
    when(io.commit(1).fire) {
      entryValid(secondHeadIndex) := false.B
      entryComplete(secondHeadIndex) := false.B
    }

    when(io.enqueue(0).fire) {
      entryData(tailIndex) := io.enqueue(0).bits.entry
      entryValid(tailIndex) := true.B
      entryComplete(tailIndex) := io.enqueue(0).bits.initiallyComplete
      entryGeneration(tailIndex) := allocationGeneration(0)
    }
    when(io.enqueue(1).fire) {
      entryData(secondTailIndex) := io.enqueue(1).bits.entry
      entryValid(secondTailIndex) := true.B
      entryComplete(secondTailIndex) := io.enqueue(1).bits.initiallyComplete
      entryGeneration(secondTailIndex) := allocationGeneration(1)
    }
  }

  assert(count <= entries.U, "ROB occupancy exceeded its configured depth")
  assert(PopCount(entryValid) === count,
    "ROB occupancy credit must equal the number of valid entries")
  when(count =/= 0.U) {
    assert(headMatches, "ROB head tag must reference a valid entry")
  }
  when(rollbackActive || io.rollback.valid) {
    assert(!io.commit.exists(_.fire),
      "ROB committed during branch rollback")
    assert(!io.enqueue.exists(_.fire),
      "ROB enqueued during branch rollback")
    assert(!io.completionAccepted.asUInt.orR,
      "ROB accepted completion during branch rollback")
  }

  val emptyHeadGeneration = !entryGeneration(headIndex)
  io.headTag := Mux(count === 0.U,
    tag(emptyHeadGeneration, headIndex),
    tag(entryGeneration(headIndex), headIndex))
  io.count := count
}
