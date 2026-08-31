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

class ReorderBuffer(config: ZirconCoreConfig) extends Module {
  private val entries = config.robEntries
  private val indexWidth = config.robIndexWidth
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(2, Decoupled(new ROBEnqueue(config))))
    val enqueueTag = Output(Vec(2, Valid(UInt(config.robTagWidth.W))))
    val completion = Input(Vec(2, new ROBCompletion(config)))
    val completionAccepted = Output(Vec(2, Bool()))
    val commit = Vec(2, Decoupled(new ROBCommit(config)))
    val flush = Input(Bool())

    val headTag = Output(UInt(config.robTagWidth.W))
    val count = Output(UInt(countWidth.W))
  })

  val entryData = Reg(Vec(entries, new ROBEntry(config)))
  val entryValid = RegInit(VecInit.fill(entries)(false.B))
  val entryComplete = RegInit(VecInit.fill(entries)(false.B))
  val entryGeneration = RegInit(VecInit.fill(entries)(false.B))

  val headIndex = RegInit(0.U(indexWidth.W))
  val headGeneration = RegInit(false.B)
  val tailIndex = RegInit(0.U(indexWidth.W))
  val tailGeneration = RegInit(false.B)
  val count = RegInit(0.U(countWidth.W))

  private def advance(index: UInt, generation: Bool, amount: UInt): (UInt, Bool) = {
    val sum = index +& amount
    val wraps = sum >= entries.U
    val normalized = Mux(wraps, sum - entries.U, sum)
    (normalized(indexWidth - 1, 0), generation ^ wraps)
  }

  private def tag(generation: Bool, index: UInt): UInt = generation ## index

  val (secondHeadIndex, secondHeadGeneration) = advance(headIndex, headGeneration, 1.U)
  val headMatches = entryValid(headIndex) && entryGeneration(headIndex) === headGeneration
  val secondHeadMatches = entryValid(secondHeadIndex) &&
    entryGeneration(secondHeadIndex) === secondHeadGeneration

  io.commit(0).valid := count =/= 0.U && headMatches && entryComplete(headIndex)
  io.commit(0).bits.robTag := tag(headGeneration, headIndex)
  io.commit(0).bits.entry := entryData(headIndex)
  io.commit(1).valid := count > 1.U && io.commit(0).valid &&
    secondHeadMatches && entryComplete(secondHeadIndex)
  io.commit(1).bits.robTag := tag(secondHeadGeneration, secondHeadIndex)
  io.commit(1).bits.entry := entryData(secondHeadIndex)

  assert(!io.commit(1).ready || io.commit(0).ready,
    "ROB commit lane 1 cannot be ready when lane 0 is blocked")
  assert(!io.enqueue(1).valid || io.enqueue(0).valid,
    "ROB enqueue lane 1 cannot be valid when lane 0 is a bubble")

  val commitCount = PopCount(io.commit.map(_.fire))
  val requestedEnqueueCount = PopCount(io.enqueue.map(_.valid))
  val freeSlots = entries.U - count
  val enqueueBundleReady = !io.flush &&
    freeSlots + commitCount >= requestedEnqueueCount
  io.enqueue.foreach(_.ready := enqueueBundleReady)
  val enqueueCount = PopCount(io.enqueue.map(_.fire))

  val (secondTailIndex, secondTailGeneration) = advance(tailIndex, tailGeneration, 1.U)
  io.enqueueTag(0).valid := io.enqueue(0).fire
  io.enqueueTag(0).bits := tag(tailGeneration, tailIndex)
  io.enqueueTag(1).valid := io.enqueue(1).fire
  io.enqueueTag(1).bits := tag(secondTailGeneration, secondTailIndex)

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
      completionMatch(port) && !io.flush
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

  val (headAfterCommitIndex, headAfterCommitGeneration) =
    advance(headIndex, headGeneration, commitCount)
  val (tailAfterEnqueueIndex, tailAfterEnqueueGeneration) =
    advance(tailIndex, tailGeneration, enqueueCount)

  when(io.flush) {
    entryValid.foreach(_ := false.B)
    entryComplete.foreach(_ := false.B)
    count := 0.U
    headIndex := headAfterCommitIndex
    tailIndex := headAfterCommitIndex
    headGeneration := !headAfterCommitGeneration
    tailGeneration := !headAfterCommitGeneration
  }.otherwise {
    count := count + enqueueCount - commitCount
    headIndex := headAfterCommitIndex
    headGeneration := headAfterCommitGeneration
    tailIndex := tailAfterEnqueueIndex
    tailGeneration := tailAfterEnqueueGeneration

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
      entryGeneration(tailIndex) := tailGeneration
    }
    when(io.enqueue(1).fire) {
      entryData(secondTailIndex) := io.enqueue(1).bits.entry
      entryValid(secondTailIndex) := true.B
      entryComplete(secondTailIndex) := io.enqueue(1).bits.initiallyComplete
      entryGeneration(secondTailIndex) := secondTailGeneration
    }
  }

  assert(count <= entries.U, "ROB occupancy exceeded its configured depth")
  assert(PopCount(entryValid) === count,
    "ROB occupancy credit must equal the number of valid entries")
  when(count =/= 0.U) {
    assert(headMatches, "ROB head tag must reference a valid entry")
  }

  io.headTag := tag(headGeneration, headIndex)
  io.count := count
}
