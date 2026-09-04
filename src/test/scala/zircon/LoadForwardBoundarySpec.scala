package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.LoadForwardBoundary

class LoadForwardBoundarySpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: LoadForwardBoundary): Unit = {
    dut.io.input.valid.poke(false)
    dut.io.input.bits.robTag.poke(0)
    dut.io.input.bits.address.poke(0)
    dut.io.input.bits.readMask.poke(15)
    dut.io.input.bits.forwardMask.poke(0)
    dut.io.input.bits.forwardData.poke(0)
    dut.io.input.bits.requiresCache.poke(true)
    dut.io.input.bits.cacheable.poke(true)
    dut.io.output.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(dut: LoadForwardBoundary, tag: Int, data: BigInt): Unit = {
    dut.io.input.valid.poke(true)
    dut.io.input.bits.robTag.poke(tag)
    dut.io.input.bits.forwardData.poke(data)
  }

  describe("LoadForwardBoundary") {
    it("does not fall through and holds while the consumer is stalled") {
      simulate(new LoadForwardBoundary(ZirconCoreConfig.default)) { dut =>
        clear(dut)
        drive(dut, 4, BigInt("cafebabe", 16))
        dut.io.input.ready.expect(true)
        dut.io.output.valid.expect(false)
        dut.clock.step()
        dut.io.input.ready.expect(false)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.robTag.expect(4)
        dut.io.output.bits.forwardData.expect(BigInt("cafebabe", 16))
        dut.io.output.ready.poke(true)
        dut.clock.step()
        dut.io.output.valid.expect(false)
      }
    }

    it("drops a younger held request on squash and clears on flush") {
      simulate(new LoadForwardBoundary(ZirconCoreConfig.default)) { dut =>
        clear(dut)
        drive(dut, 7, 1)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.robHeadTag.poke(0)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.io.output.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.output.valid.expect(false)

        drive(dut, 5, 2)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.flush.poke(true)
        dut.io.output.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.output.valid.expect(false)
      }
    }
  }
}
