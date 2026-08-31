package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class CompletionResult(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val writesInteger = Bool()
  val destinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val data = UInt(32.W)
}

class CompletionBuffer(config: ZirconCoreConfig, depth: Int) extends Module {
  require(depth == 1 || depth == 2,
    "architectural completion buffers are restricted to one or two entries")
  private val pointerWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Decoupled(new CompletionResult(config)))
    val dequeue = Decoupled(new CompletionResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(countWidth.W))
  })

  val entries = Reg(Vec(depth, new CompletionResult(config)))
  val head = RegInit(0.U(pointerWidth.W))
  val tail = RegInit(0.U(pointerWidth.W))
  val count = RegInit(0.U(countWidth.W))

  private def advance(pointer: UInt): UInt = {
    if (depth == 1) 0.U(pointerWidth.W)
    else Mux(pointer === (depth - 1).U, 0.U, pointer + 1.U)
  }

  val recoveryBlocked = io.flush || io.squash.valid
  io.dequeue.valid := count =/= 0.U && !recoveryBlocked
  io.dequeue.bits := (if (depth == 1) entries(0) else entries(head))
  io.enqueue.ready := !recoveryBlocked &&
    (count < depth.U || (io.dequeue.valid && io.dequeue.ready))

  val enqueueFire = io.enqueue.fire
  val dequeueFire = io.dequeue.fire
  val firstEntry = if (depth == 1) entries(0) else entries(head)
  val secondEntry = if (depth == 1) entries(0) else entries(advance(head))
  val keepFirst = count >= 1.U && !ROBTagOrder.isYounger(
    firstEntry.robTag, io.squash.bits, io.robHeadTag, config)
  val keepSecond = count >= 2.U && !ROBTagOrder.isYounger(
    secondEntry.robTag, io.squash.bits, io.robHeadTag, config)
  val squashCount = PopCount(Seq(keepFirst, keepSecond))

  when(io.flush) {
    head := 0.U
    tail := 0.U
    count := 0.U
  }.elsewhen(io.squash.valid) {
    head := 0.U
    tail := squashCount(pointerWidth - 1, 0)
    count := squashCount(countWidth - 1, 0)
    if (depth == 1) {
      when(keepFirst) { entries(0) := firstEntry }
    } else {
      when(keepFirst) {
        entries(0) := firstEntry
        when(keepSecond) { entries(1) := secondEntry }
      }.elsewhen(keepSecond) {
        entries(0) := secondEntry
      }
    }
  }.otherwise {
    count := count + enqueueFire - dequeueFire
    when(dequeueFire) { head := advance(head) }
    when(enqueueFire) {
      if (depth == 1) entries(0) := io.enqueue.bits
      else entries(tail) := io.enqueue.bits
      tail := advance(tail)
    }
  }

  assert(count <= depth.U, "completion buffer occupancy exceeded its depth")
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "completion buffer squash boundary ROB index out of range")
    assert(!io.enqueue.fire && !io.dequeue.fire,
      "completion buffer transferred data during selective squash")
  }
  io.count := count
}

class UnifiedCompletionArbiter(
    config: ZirconCoreConfig,
    endpointCount: Int = 5
) extends Module {
  require(endpointCount == 5, "Zircon-2026 has five architectural execution endpoints")
  private val endpointIndexWidth = log2Ceil(endpointCount)

  val io = IO(new Bundle {
    val inputs = Flipped(Vec(endpointCount, Decoupled(new CompletionResult(config))))
    val outputs = Vec(2, Decoupled(new CompletionResult(config)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  private def ageFromHead(tag: UInt): UInt =
    ROBTagOrder.ageFromHead(tag, io.robHeadTag, config)

  private def selectOldest(candidates: Seq[Bool]): (Bool, UInt) = {
    var selectedValid: Bool = false.B
    var selectedIndex: UInt = 0.U(endpointIndexWidth.W)
    var selectedAge: UInt = 0.U((config.robIndexWidth + 1).W)
    for (index <- 0 until endpointCount) {
      val candidateAge = ageFromHead(io.inputs(index).bits.robTag)
      val take = candidates(index) && (!selectedValid || candidateAge < selectedAge)
      selectedIndex = Mux(take, index.U, selectedIndex)
      selectedAge = Mux(take, candidateAge, selectedAge)
      selectedValid = selectedValid || candidates(index)
    }
    (selectedValid, selectedIndex)
  }

  val (firstValid, firstIndex) = selectOldest(io.inputs.map(_.valid))
  val secondCandidates = (0 until endpointCount).map(index =>
    io.inputs(index).valid && !(firstValid && firstIndex === index.U))
  val (secondValid, secondIndex) = selectOldest(secondCandidates)

  io.inputs.foreach(_.ready := false.B)
  val recoveryBlocked = io.flush || io.squash.valid
  io.outputs(0).valid := firstValid && !recoveryBlocked
  io.outputs(0).bits := io.inputs(firstIndex).bits
  io.outputs(1).valid := secondValid && !recoveryBlocked
  io.outputs(1).bits := io.inputs(secondIndex).bits
  when(io.outputs(0).valid) {
    io.inputs(firstIndex).ready := io.outputs(0).ready
  }
  when(io.outputs(1).valid) {
    io.inputs(secondIndex).ready := io.outputs(1).ready
  }

  io.inputs.foreach { input =>
    when(input.valid) {
      assert(input.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
        "completion arbiter received an out-of-range ROB tag")
    }
  }
  when(io.outputs(0).valid && io.outputs(1).valid) {
    assert(io.outputs(0).bits.robTag =/= io.outputs(1).bits.robTag,
      "completion outputs must carry distinct ROB tags")
    assert(!(io.outputs(0).bits.writesInteger && io.outputs(1).bits.writesInteger &&
      io.outputs(0).bits.destinationPhysical === io.outputs(1).bits.destinationPhysical),
      "completion outputs must not write the same physical register")
  }
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "completion arbiter squash boundary ROB index out of range")
    assert(!io.inputs.exists(_.ready),
      "completion arbiter accepted an input during selective squash")
  }
}
