package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{CompletionBuffer, UnifiedCompletionArbiter}

class CompletionNetworkSpec extends AnyFunSpec with ChiselSim {
  describe("CompletionBuffer") {
    it("provides one-entry skid backpressure, simultaneous pop/push, and flush") {
      simulate(new CompletionBuffer(ZirconCoreConfig.default, depth = 1)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(0)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.dequeue.ready.poke(false)
        dut.io.enqueue.valid.poke(true)
        dut.io.enqueue.bits.robTag.poke(1)
        dut.io.enqueue.bits.writesInteger.poke(true)
        dut.io.enqueue.bits.destinationPhysical.poke(32)
        dut.io.enqueue.bits.data.poke(BigInt("11111111", 16))
        dut.clock.step()
        dut.io.count.expect(1)
        dut.io.enqueue.ready.expect(false)
        dut.io.dequeue.valid.expect(true)
        dut.io.dequeue.bits.robTag.expect(1)

        dut.io.dequeue.ready.poke(true)
        dut.io.enqueue.bits.robTag.poke(2)
        dut.io.enqueue.bits.destinationPhysical.poke(33)
        dut.io.enqueue.bits.data.poke(BigInt("22222222", 16))
        dut.io.enqueue.ready.expect(true)
        dut.clock.step()
        dut.io.count.expect(1)
        dut.io.dequeue.bits.robTag.expect(2)

        dut.io.flush.poke(true)
        dut.io.dequeue.valid.expect(false)
        dut.io.enqueue.ready.expect(false)
        dut.clock.step()
        dut.io.count.expect(0)
      }
    }

    it("preserves FIFO order in a two-entry endpoint buffer") {
      simulate(new CompletionBuffer(ZirconCoreConfig.default, depth = 2)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(0)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.dequeue.ready.poke(false)
        dut.io.enqueue.valid.poke(true)
        dut.io.enqueue.bits.writesInteger.poke(false)
        dut.io.enqueue.bits.destinationPhysical.poke(0)
        dut.io.enqueue.bits.data.poke(0)
        dut.io.enqueue.bits.robTag.poke(4)
        dut.clock.step()
        dut.io.enqueue.bits.robTag.poke(5)
        dut.clock.step()
        dut.io.count.expect(2)
        dut.io.dequeue.bits.robTag.expect(4)
        dut.io.enqueue.ready.expect(false)

        dut.io.enqueue.valid.poke(false)
        dut.io.dequeue.ready.poke(true)
        dut.clock.step()
        dut.io.dequeue.bits.robTag.expect(5)
        dut.clock.step()
        dut.io.count.expect(0)
      }
    }

    it("compacts surviving results in FIFO order during selective squash") {
      simulate(new CompletionBuffer(ZirconCoreConfig.default, depth = 2)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(20)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.dequeue.ready.poke(false)
        dut.io.enqueue.valid.poke(true)
        dut.io.enqueue.bits.writesInteger.poke(true)
        dut.io.enqueue.bits.destinationPhysical.poke(32)
        dut.io.enqueue.bits.data.poke(0)

        // Advance the circular head to slot 1, then wrap the second enqueue.
        dut.io.enqueue.bits.robTag.poke(21)
        dut.clock.step()
        dut.io.dequeue.ready.poke(true)
        dut.io.enqueue.valid.poke(false)
        dut.clock.step()
        dut.io.dequeue.ready.poke(false)
        dut.io.enqueue.valid.poke(true)
        dut.io.enqueue.bits.robTag.poke(2)
        dut.clock.step()
        dut.io.enqueue.bits.robTag.poke(22)
        dut.clock.step()
        dut.io.count.expect(2)
        dut.io.dequeue.bits.robTag.expect(2)

        // Keep tag 22 and remove wrapped tag 2 relative to branch tag 23.
        dut.io.enqueue.valid.poke(false)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.dequeue.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.count.expect(1)
        dut.io.dequeue.valid.expect(true)
        dut.io.dequeue.bits.robTag.expect(22)
      }
    }

    it("clears a one-entry younger result without accepting a replacement") {
      simulate(new CompletionBuffer(ZirconCoreConfig.default, depth = 1)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(0)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.dequeue.ready.poke(false)
        dut.io.enqueue.valid.poke(true)
        dut.io.enqueue.bits.robTag.poke(7)
        dut.io.enqueue.bits.writesInteger.poke(true)
        dut.io.enqueue.bits.destinationPhysical.poke(33)
        dut.io.enqueue.bits.data.poke(1)
        dut.clock.step()

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(5)
        dut.io.enqueue.ready.expect(false)
        dut.io.dequeue.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.enqueue.valid.poke(false)
        dut.io.count.expect(0)
      }
    }
  }

  describe("UnifiedCompletionArbiter") {
    it("selects the two oldest results across ROB wrap") {
      simulate(new UnifiedCompletionArbiter(ZirconCoreConfig.default)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(20)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.outputs.foreach(_.ready.poke(false))
        dut.io.inputs.foreach { input =>
          input.valid.poke(false)
          input.bits.robTag.poke(0)
          input.bits.writesInteger.poke(false)
          input.bits.destinationPhysical.poke(0)
          input.bits.data.poke(0)
        }

        val tags = Seq(2, 5, 22)
        tags.zipWithIndex.foreach { case (tag, index) =>
          val input = dut.io.inputs(index)
          input.valid.poke(true)
          input.bits.robTag.poke(tag)
          input.bits.writesInteger.poke(true)
          input.bits.destinationPhysical.poke(32 + index)
          input.bits.data.poke(tag)
        }
        dut.io.outputs(0).valid.expect(true)
        dut.io.outputs(0).bits.robTag.expect(22)
        dut.io.outputs(1).valid.expect(true)
        dut.io.outputs(1).bits.robTag.expect(2)
      }
    }

    it("allows the second completion port to progress under independent backpressure") {
      simulate(new UnifiedCompletionArbiter(ZirconCoreConfig.default)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(0)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.inputs.foreach { input =>
          input.valid.poke(false)
          input.bits.robTag.poke(0)
          input.bits.writesInteger.poke(false)
          input.bits.destinationPhysical.poke(0)
          input.bits.data.poke(0)
        }
        for (index <- 0 until 2) {
          dut.io.inputs(index).valid.poke(true)
          dut.io.inputs(index).bits.robTag.poke(index)
          dut.io.inputs(index).bits.writesInteger.poke(true)
          dut.io.inputs(index).bits.destinationPhysical.poke(32 + index)
        }
        dut.io.outputs(0).ready.poke(false)
        dut.io.outputs(1).ready.poke(true)
        dut.io.inputs(0).ready.expect(false)
        dut.io.inputs(1).ready.expect(true)

        dut.io.flush.poke(true)
        dut.io.outputs(0).valid.expect(false)
        dut.io.outputs(1).valid.expect(false)
        dut.io.inputs(0).ready.expect(false)
        dut.io.inputs(1).ready.expect(false)
      }
    }

    it("blocks both completion ports while selective squash is active") {
      simulate(new UnifiedCompletionArbiter(ZirconCoreConfig.default)) { dut =>
        dut.io.flush.poke(false)
        dut.io.robHeadTag.poke(0)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(4)
        dut.io.outputs.foreach(_.ready.poke(true))
        dut.io.inputs.foreach { input =>
          input.valid.poke(true)
          input.bits.robTag.poke(6)
          input.bits.writesInteger.poke(false)
          input.bits.destinationPhysical.poke(0)
          input.bits.data.poke(0)
        }
        dut.io.outputs.foreach(_.valid.expect(false))
        dut.io.inputs.foreach(_.ready.expect(false))
      }
    }
  }
}
