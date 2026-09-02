package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FloatingResultBridge

class FloatingResultBridgeSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: FloatingResultBridge): Unit = {
    dut.io.input.valid.poke(false)
    dut.io.input.bits.robTag.poke(0)
    dut.io.input.bits.writesInteger.poke(false)
    dut.io.input.bits.integerDestinationPhysical.poke(0)
    dut.io.input.bits.integerData.poke(0)
    dut.io.input.bits.writesFloat.poke(false)
    dut.io.input.bits.floatDestination.poke(0)
    dut.io.input.bits.floatData.poke(0)
    dut.io.input.bits.flags.poke(0)
    dut.io.completion.ready.poke(false)
    dut.io.floatingResult.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def floatWrite(dut: FloatingResultBridge, tag: Int, destination: Int,
      data: BigInt): Unit = {
    dut.io.input.valid.poke(true)
    dut.io.input.bits.robTag.poke(tag)
    dut.io.input.bits.writesInteger.poke(false)
    dut.io.input.bits.integerDestinationPhysical.poke(0)
    dut.io.input.bits.integerData.poke(0)
    dut.io.input.bits.writesFloat.poke(true)
    dut.io.input.bits.floatDestination.poke(destination)
    dut.io.input.bits.floatData.poke(data)
    dut.io.input.bits.flags.poke(0)
  }

  describe("FloatingResultBridge") {
    it("retains a float completion only after its FPR queue record is accepted") {
      simulate(new FloatingResultBridge) { dut =>
        clear(dut)
        floatWrite(dut, tag = 5, destination = 8, data = BigInt("7fc00001", 16))
        dut.io.completion.valid.expect(false)
        dut.io.floatingResult.valid.expect(true)
        dut.io.input.ready.expect(false)

        dut.io.completion.ready.poke(true)
        dut.io.input.ready.expect(false)
        dut.clock.step(2)
        dut.io.completion.valid.expect(false)
        dut.io.floatingResult.valid.expect(true)

        dut.io.floatingResult.ready.poke(true)
        dut.io.input.ready.expect(true)
        dut.io.floatingResult.valid.expect(true)
        dut.io.floatingResult.bits.robTag.expect(5)
        dut.io.floatingResult.bits.fprAddress.expect(8)
        dut.io.floatingResult.bits.fprData.expect(BigInt("7fc00001", 16))
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(5)
        dut.io.completion.bits.writesInteger.expect(false)
        dut.io.floatingResult.valid.expect(false)
        dut.clock.step()
        dut.io.completion.valid.expect(false)
      }
    }

    it("routes an integer-only F result through the normal completion path") {
      simulate(new FloatingResultBridge) { dut =>
        clear(dut)
        dut.io.input.valid.poke(true)
        dut.io.input.bits.robTag.poke(7)
        dut.io.input.bits.writesInteger.poke(true)
        dut.io.input.bits.integerDestinationPhysical.poke(38)
        dut.io.input.bits.integerData.poke(BigInt("40490fdb", 16))
        dut.io.input.bits.writesFloat.poke(false)
        dut.io.input.bits.floatDestination.poke(0)
        dut.io.input.bits.floatData.poke(0)
        dut.io.input.bits.flags.poke(0)
        dut.io.floatingResult.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.input.ready.expect(false)
        dut.io.completion.ready.poke(true)
        dut.io.input.ready.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.writesInteger.expect(true)
        dut.io.completion.bits.destinationPhysical.expect(38)
        dut.io.completion.bits.data.expect(BigInt("40490fdb", 16))
        dut.clock.step()
      }
    }

    it("removes a pending float completion on squash or flush") {
      simulate(new FloatingResultBridge) { dut =>
        clear(dut)
        floatWrite(dut, tag = 9, destination = 2, data = 3)
        dut.io.floatingResult.ready.poke(true)
        dut.io.input.ready.expect(true)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(8)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.completion.valid.expect(false)

        floatWrite(dut, tag = 7, destination = 2, data = 4)
        dut.io.input.ready.expect(true)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.completion.valid.expect(false)
      }
    }
  }
}
