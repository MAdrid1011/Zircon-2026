package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.DualLoadForwardArbiter

class DualLoadForwardArbiterSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: DualLoadForwardArbiter): Unit = {
    for (input <- dut.io.in) {
      input.valid.poke(false)
      input.bits.robTag.poke(0)
      input.bits.address.poke(0)
      input.bits.readMask.poke(15)
      input.bits.forwardMask.poke(0)
      input.bits.forwardData.poke(0)
      input.bits.requiresCache.poke(true)
      input.bits.cacheable.poke(true)
    }
    dut.io.out.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(dut: DualLoadForwardArbiter, lane: Int, tag: Int,
      address: BigInt): Unit = {
    val input = dut.io.in(lane)
    input.valid.poke(true)
    input.bits.robTag.poke(tag)
    input.bits.address.poke(address)
  }

  describe("DualLoadForwardArbiter") {
    it("selects the older load across ROB wrap and backpressures the other") {
      simulate(new DualLoadForwardArbiter) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, lane = 0, tag = 0, BigInt("80001000", 16))
        drive(dut, lane = 1, tag = 21, BigInt("80002000", 16))
        dut.io.out.valid.expect(true)
        dut.io.out.bits.robTag.expect(21)
        dut.io.out.ready.poke(true)
        dut.io.in(0).ready.expect(false)
        dut.io.in(1).ready.expect(true)
      }
    }

    it("locks the selected payload while the L1D request is backpressured") {
      simulate(new DualLoadForwardArbiter) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(1)
        drive(dut, lane = 0, tag = 4, BigInt("80001000", 16))
        dut.io.out.valid.expect(true)
        dut.io.out.bits.robTag.expect(4)
        dut.clock.step()

        drive(dut, lane = 1, tag = 3, BigInt("80002000", 16))
        dut.io.out.valid.expect(true)
        dut.io.out.bits.robTag.expect(4)
        dut.io.out.ready.poke(true)
        dut.io.in(0).ready.expect(true)
        dut.io.in(1).ready.expect(false)
        dut.clock.step()
        dut.io.in(0).valid.poke(false)
        dut.io.out.valid.expect(true)
        dut.io.out.bits.robTag.expect(3)
      }
    }

    it("suppresses all grants during recovery") {
      simulate(new DualLoadForwardArbiter) { dut =>
        clear(dut)
        drive(dut, lane = 0, tag = 4, BigInt("80001000", 16))
        dut.io.out.ready.poke(true)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.io.out.valid.expect(false)
        dut.io.in.foreach(_.ready.expect(false))
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.flush.poke(true)
        dut.io.out.valid.expect(false)
        dut.io.in.foreach(_.ready.expect(false))
      }
    }

    it("clears a backpressured selection before accepting post-squash work") {
      simulate(new DualLoadForwardArbiter) { dut =>
        clear(dut)
        drive(dut, lane = 0, tag = 4, BigInt("80001000", 16))
        dut.io.out.valid.expect(true)
        dut.clock.step()

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.io.out.valid.expect(false)
        dut.clock.step()

        dut.io.squash.valid.poke(false)
        dut.io.in(0).valid.poke(false)
        drive(dut, lane = 1, tag = 5, BigInt("80002000", 16))
        dut.io.out.ready.poke(true)
        dut.io.out.valid.expect(true)
        dut.io.out.bits.robTag.expect(5)
        dut.io.in(1).ready.expect(true)
      }
    }
  }
}
