package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.MemoryLoadCompletion

class MemoryLoadCompletionSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: MemoryLoadCompletion): Unit = {
    dut.io.loadResult.valid.poke(false)
    dut.io.loadResult.bits.robTag.poke(0)
    dut.io.loadResult.bits.destinationPhysical.poke(32)
    dut.io.loadResult.bits.writesInteger.poke(true)
    dut.io.loadResult.bits.m1Owner.poke(false)
    dut.io.loadResult.bits.accessSize.poke(2)
    dut.io.loadResult.bits.unsignedLoad.poke(false)
    dut.io.loadResult.bits.data.poke(0)
    dut.io.fault.valid.poke(false)
    dut.io.fault.bits.robTag.poke(0)
    dut.io.fault.bits.cause.poke(0)
    dut.io.fault.bits.trapValue.poke(0)
    dut.io.completion.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def result(
      dut: MemoryLoadCompletion,
      tag: Int,
      destination: Int,
      size: Int,
      unsigned: Boolean,
      data: BigInt
  ): Unit = {
    dut.io.loadResult.valid.poke(true)
    dut.io.loadResult.bits.robTag.poke(tag)
    dut.io.loadResult.bits.destinationPhysical.poke(destination)
    dut.io.loadResult.bits.writesInteger.poke(true)
    dut.io.loadResult.bits.m1Owner.poke(false)
    dut.io.loadResult.bits.accessSize.poke(size)
    dut.io.loadResult.bits.unsignedLoad.poke(unsigned)
    dut.io.loadResult.bits.data.poke(data)
  }

  describe("MemoryLoadCompletion") {
    it("formats signed and unsigned byte and halfword loads before completion") {
      simulate(new MemoryLoadCompletion) { dut =>
        clear(dut)
        result(dut, tag = 4, destination = 32, size = 0, unsigned = false, 0x80)
        dut.io.loadResult.ready.expect(true)
        dut.clock.step()
        dut.io.loadResult.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(4)
        dut.io.completion.bits.data.expect(BigInt("ffffff80", 16))
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        result(dut, tag = 5, destination = 33, size = 1, unsigned = true, 0x8001)
        dut.io.loadResult.ready.expect(true)
        dut.clock.step()
        dut.io.loadResult.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(5)
        dut.io.completion.bits.data.expect(BigInt("00008001", 16))
      }
    }

    it("backpressures a third response and removes only younger buffered work") {
      simulate(new MemoryLoadCompletion) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(0)
        result(dut, tag = 1, destination = 32, size = 2, unsigned = false, 1)
        dut.io.loadResult.ready.expect(true)
        dut.clock.step()
        result(dut, tag = 2, destination = 33, size = 2, unsigned = false, 2)
        dut.io.loadResult.ready.expect(true)
        dut.clock.step()
        result(dut, tag = 3, destination = 34, size = 2, unsigned = false, 3)
        dut.io.loadResult.ready.expect(false)

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(1)
        dut.io.loadResult.ready.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.loadResult.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.valid.expect(false)
      }
    }
  }
}
