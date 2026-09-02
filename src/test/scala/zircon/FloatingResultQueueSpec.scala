package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FloatingResultQueue

class FloatingResultQueueSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: FloatingResultQueue): Unit = {
    dut.io.enqueue.valid.poke(false)
    dut.io.enqueue.bits.robTag.poke(0)
    dut.io.enqueue.bits.writesFloat.poke(false)
    dut.io.enqueue.bits.fprAddress.poke(0)
    dut.io.enqueue.bits.fprData.poke(0)
    dut.io.enqueue.bits.flags.poke(0)
    dut.io.commitTag.poke(0)
    dut.io.commit.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def enqueue(
      dut: FloatingResultQueue,
      tag: Int,
      fpr: Int,
      data: BigInt,
      flags: Int
  ): Unit = {
    dut.io.enqueue.valid.poke(true)
    dut.io.enqueue.bits.robTag.poke(tag)
    dut.io.enqueue.bits.writesFloat.poke(true)
    dut.io.enqueue.bits.fprAddress.poke(fpr)
    dut.io.enqueue.bits.fprData.poke(data)
    dut.io.enqueue.bits.flags.poke(flags)
    dut.io.enqueue.ready.expect(true)
    dut.clock.step()
    dut.io.enqueue.valid.poke(false)
  }

  describe("FloatingResultQueue") {
    it("commits by ROB tag, retains backpressured data, and drops killed results") {
      simulate(new FloatingResultQueue) { dut =>
        clear(dut)
        enqueue(dut, tag = 7, fpr = 4, data = BigInt("7fc00000", 16), flags = 1)
        enqueue(dut, tag = 3, fpr = 2, data = BigInt("3f800000", 16), flags = 16)
        dut.io.count.expect(2)

        // Younger completion arrived first, but only the ROB-head tag may
        // reach commit and must remain stable while commit is backpressured.
        dut.io.commitTag.poke(3)
        dut.io.commit.valid.expect(true)
        dut.io.commit.bits.robTag.expect(3)
        dut.io.commit.bits.fprAddress.expect(2)
        dut.io.commit.bits.fprData.expect(BigInt("3f800000", 16))
        dut.io.commit.bits.flags.expect(16)
        dut.clock.step(2)
        dut.io.commit.valid.expect(true)
        dut.io.commit.bits.robTag.expect(3)
        dut.io.commit.ready.poke(true)
        dut.clock.step()
        dut.io.commit.ready.poke(false)

        dut.io.commitTag.poke(7)
        dut.io.commit.valid.expect(true)
        dut.io.commit.bits.robTag.expect(7)
        dut.io.commit.ready.poke(true)
        dut.clock.step()
        dut.io.commit.ready.poke(false)
        dut.io.count.expect(0)

        enqueue(dut, tag = 3, fpr = 1, data = 1, flags = 0)
        enqueue(dut, tag = 7, fpr = 5, data = 2, flags = 0)
        dut.io.robHeadTag.poke(3)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.io.commit.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.commitTag.poke(7)
        dut.io.commit.valid.expect(false)
        dut.io.commitTag.poke(3)
        dut.io.commit.valid.expect(true)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.commit.valid.expect(false)
      }
    }
  }
}
