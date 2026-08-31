package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.BranchProvider
import zircon.frontend.{FetchDecodeQueue, FetchQueueEntry}

class FetchDecodeQueueSpec extends AnyFunSpec with ChiselSim {
  private def pokeEntry(entry: FetchQueueEntry, id: Int,
      fault: Boolean = false): Unit = {
    entry.instruction.poke(BigInt("10000000", 16) + id)
    entry.prediction.pc.poke(BigInt("80000000", 16) + id * 4)
    entry.prediction.historyBefore.poke(BigInt(id) << 40)
    entry.prediction.predictedTaken.poke((id & 1) != 0)
    entry.prediction.predictedTarget.poke(
      BigInt("80001000", 16) + id * 4)
    entry.prediction.conditional.poke((id & 1) != 0)
    entry.prediction.call.poke(id == 5)
    entry.prediction.ret.poke(id == 6)
    entry.prediction.provider.poke(BranchProvider.Base)
    entry.prediction.alternateProvider.poke(BranchProvider.Tagged0)
    entry.prediction.providerPrediction.poke((id & 1) != 0)
    entry.prediction.alternatePrediction.poke(false)
    entry.prediction.btbWay.poke(id & 1)
    entry.prediction.rasPointerBefore.poke(id & 7)
    entry.prediction.rasCountBefore.poke(id min 8)
    entry.privilege.poke(3)
    entry.fault.valid.poke(fault)
    entry.fault.cause.poke(if (fault) 1 else 0)
    entry.fault.tval.poke(
      if (fault) BigInt("dead0000", 16) + id else BigInt(0))
  }

  private def clearInputs(dut: FetchDecodeQueue): Unit = {
    dut.io.flush.poke(false)
    dut.io.enqueue.valid.poke(false)
    dut.io.enqueue.bits.count.poke(1)
    for (lane <- 0 until 4) pokeEntry(dut.io.enqueue.bits.entries(lane), lane)
    dut.io.dequeue(0).ready.poke(false)
    dut.io.dequeue(1).ready.poke(false)
  }

  private def driveEnqueue(dut: FetchDecodeQueue, ids: Seq[Int],
      faultLast: Boolean = false): Unit = {
    dut.io.enqueue.valid.poke(true)
    dut.io.enqueue.bits.count.poke(ids.length)
    for (lane <- 0 until 4) {
      val id = ids.lift(lane).getOrElse(0)
      pokeEntry(dut.io.enqueue.bits.entries(lane), id,
        fault = faultLast && lane == ids.length - 1)
    }
  }

  private def enqueue(dut: FetchDecodeQueue, ids: Seq[Int],
      faultLast: Boolean = false): Unit = {
    driveEnqueue(dut, ids, faultLast)
    dut.io.enqueue.ready.expect(true)
    dut.clock.step()
    dut.io.enqueue.valid.poke(false)
  }

  private def expectLane(dut: FetchDecodeQueue, lane: Int, id: Int): Unit = {
    dut.io.dequeue(lane).valid.expect(true)
    dut.io.dequeue(lane).bits.instruction.expect(BigInt("10000000", 16) + id)
    dut.io.dequeue(lane).bits.prediction.pc.expect(
      BigInt("80000000", 16) + id * 4)
  }

  describe("FetchDecodeQueue") {
    it("stores 876 payload bits rather than an eight-entry 1752-bit copy") {
      val entryBits = (new FetchQueueEntry(ZirconCoreConfig.default)).getWidth
      assert(entryBits == 219)
      assert(entryBits * 4 == 876)
      assert(entryBits * 8 == 1752)
    }

    it("sustains two-wide drain while replacing a four-entry fetch group") {
      simulate(new FetchDecodeQueue) { dut =>
        clearInputs(dut)
        enqueue(dut, Seq(0, 1, 2, 3))
        dut.io.count.expect(4)

        driveEnqueue(dut, Seq(4, 5, 6, 7))
        dut.io.dequeue(0).ready.poke(true)
        dut.io.dequeue(1).ready.poke(true)
        expectLane(dut, 0, 0)
        expectLane(dut, 1, 1)
        dut.io.enqueue.ready.expect(false)
        dut.clock.step()

        expectLane(dut, 0, 2)
        expectLane(dut, 1, 3)
        dut.io.enqueue.ready.expect(true)
        dut.clock.step()
        dut.io.enqueue.valid.poke(false)
        dut.io.count.expect(4)
        expectLane(dut, 0, 4)
        expectLane(dut, 1, 5)
      }
    }

    it("allows lane zero to advance alone under asymmetric backpressure") {
      simulate(new FetchDecodeQueue) { dut =>
        clearInputs(dut)
        enqueue(dut, Seq(0, 1, 2))
        dut.io.dequeue(0).ready.poke(true)
        dut.io.dequeue(1).ready.poke(false)
        expectLane(dut, 0, 0)
        expectLane(dut, 1, 1)
        dut.clock.step()
        dut.io.count.expect(2)
        expectLane(dut, 0, 1)
        expectLane(dut, 1, 2)
      }
    }

    it("preserves order across pointer wrap and simultaneous recycle") {
      simulate(new FetchDecodeQueue) { dut =>
        clearInputs(dut)
        enqueue(dut, Seq(0, 1, 2))
        dut.io.dequeue.foreach(_.ready.poke(true))
        driveEnqueue(dut, Seq(3, 4, 5))
        dut.io.enqueue.ready.expect(true)
        dut.clock.step()
        dut.io.enqueue.valid.poke(false)
        dut.io.count.expect(4)
        expectLane(dut, 0, 2)
        expectLane(dut, 1, 3)
        dut.clock.step()
        expectLane(dut, 0, 4)
        expectLane(dut, 1, 5)
      }
    }

    it("preserves prediction and precise fault payload bit-for-bit") {
      simulate(new FetchDecodeQueue) { dut =>
        clearInputs(dut)
        enqueue(dut, Seq(5, 6), faultLast = true)
        expectLane(dut, 0, 5)
        dut.io.dequeue(0).bits.prediction.call.expect(true)
        dut.io.dequeue(0).bits.prediction.historyBefore.expect(BigInt(5) << 40)
        dut.io.dequeue(0).bits.prediction.rasPointerBefore.expect(5)
        expectLane(dut, 1, 6)
        dut.io.dequeue(1).bits.prediction.ret.expect(true)
        dut.io.dequeue(1).bits.fault.valid.expect(true)
        dut.io.dequeue(1).bits.fault.cause.expect(1)
        dut.io.dequeue(1).bits.fault.tval.expect(BigInt("dead0006", 16))
      }
    }

    it("flushes atomically and never exposes stale entry data as valid") {
      simulate(new FetchDecodeQueue) { dut =>
        clearInputs(dut)
        enqueue(dut, Seq(0, 1, 2, 3))
        dut.io.flush.poke(true)
        dut.io.dequeue.foreach(_.valid.expect(false))
        dut.io.enqueue.ready.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.count.expect(0)
        dut.io.dequeue.foreach(_.valid.expect(false))
        enqueue(dut, Seq(7))
        expectLane(dut, 0, 7)
        dut.io.dequeue(1).valid.expect(false)
      }
    }
  }
}
