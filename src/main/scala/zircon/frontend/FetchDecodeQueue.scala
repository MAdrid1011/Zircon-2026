package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.BranchPredictionMetadata

class FetchFault extends Bundle {
  val valid = Bool()
  val cause = UInt(6.W)
  val tval = UInt(32.W)
}

class FetchQueueEntry(config: ZirconCoreConfig) extends Bundle {
  val instruction = UInt(32.W)
  val prediction = new BranchPredictionMetadata
  val privilege = UInt(2.W)
  val fault = new FetchFault
}

class FetchQueueEnqueue(config: ZirconCoreConfig) extends Bundle {
  val count = UInt(log2Ceil(config.fetchWidth + 1).W)
  val entries = Vec(config.fetchWidth, new FetchQueueEntry(config))
}

/** Minimal four-entry FIFO between four-wide fetch and two-wide decode. */
class FetchDecodeQueue(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val depth = 4
  private val pointerWidth = log2Ceil(depth)
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Decoupled(new FetchQueueEnqueue(config)))
    val dequeue = Vec(config.decodeWidth,
      Decoupled(new FetchQueueEntry(config)))
    val flush = Input(Bool())
    val count = Output(UInt(countWidth.W))
  })

  require(config.fetchWidth == 4 && config.decodeWidth == 2,
    "the fetch/decode queue is frozen for 4-wide fetch and 2-wide decode")

  val entryData = Reg(Vec(depth, new FetchQueueEntry(config)))
  val head = RegInit(0.U(pointerWidth.W))
  val tail = RegInit(0.U(pointerWidth.W))
  val count = RegInit(0.U(countWidth.W))

  private def advance(pointer: UInt, amount: UInt): UInt =
    (pointer + amount)(pointerWidth - 1, 0)

  val secondHead = advance(head, 1.U)
  io.dequeue(0).valid := !io.flush && count =/= 0.U
  io.dequeue(0).bits := entryData(head)
  io.dequeue(1).valid := !io.flush && count > 1.U
  io.dequeue(1).bits := entryData(secondHead)

  assert(!io.dequeue(1).ready || io.dequeue(0).ready,
    "fetch queue lane 1 cannot be ready while lane 0 is blocked")
  val dequeueCount = PopCount(io.dequeue.map(_.fire))
  val enqueueCount = io.enqueue.bits.count
  val availableAfterDequeue = depth.U - count + dequeueCount
  val legalEnqueueCount = enqueueCount >= 1.U && enqueueCount <= depth.U
  io.enqueue.ready := !io.flush && legalEnqueueCount &&
    availableAfterDequeue >= enqueueCount
  val enqueueFire = io.enqueue.fire

  when(io.flush) {
    head := 0.U
    tail := 0.U
    count := 0.U
  }.otherwise {
    head := advance(head, dequeueCount)
    tail := advance(tail, Mux(enqueueFire, enqueueCount, 0.U))
    count := count + Mux(enqueueFire, enqueueCount, 0.U) - dequeueCount

    for (lane <- 0 until config.fetchWidth) {
      when(enqueueFire && lane.U < enqueueCount) {
        entryData(advance(tail, lane.U)) := io.enqueue.bits.entries(lane)
      }
    }
  }

  when(io.enqueue.valid) {
    assert(legalEnqueueCount,
      "a fetch enqueue bundle must contain one to four entries")
  }
  assert(count <= depth.U,
    "fetch/decode queue occupancy exceeded four entries")
  assert(!(io.dequeue(1).fire && !io.dequeue(0).fire),
    "fetch/decode queue consumed lane 1 without lane 0")

  io.count := count
}
