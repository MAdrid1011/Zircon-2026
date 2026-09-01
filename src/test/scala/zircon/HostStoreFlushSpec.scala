package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.HostStoreFlush

class HostStoreFlushSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: HostStoreFlush): Unit = {
    dut.io.input.valid.poke(false)
    dut.io.input.bits.robTag.poke(0)
    dut.io.input.bits.address.poke(0)
    dut.io.input.bits.accessFault.poke(false)
    dut.io.output.ready.poke(false)
    dut.io.enabled.poke(false)
    dut.io.address.poke(0)
    dut.io.l1dFlush.ready.poke(false)
    dut.io.l2Flush.ready.poke(false)
    dut.io.writebackComplete.valid.poke(false)
    dut.io.writebackComplete.bits.poke(0)
  }

  private def offerStore(
      dut: HostStoreFlush,
      tag: Int,
      address: BigInt,
      accessFault: Boolean = false
  ): Unit = {
    dut.io.input.valid.poke(true)
    dut.io.input.bits.robTag.poke(tag)
    dut.io.input.bits.address.poke(address)
    dut.io.input.bits.accessFault.poke(accessFault)
  }

  describe("HostStoreFlush") {
    it("holds a matching cacheable result until the exact ID-5 completion") {
      simulate(new HostStoreFlush) { dut =>
        clear(dut)
        val address = BigInt("8000100c", 16)
        val line = BigInt("80001000", 16)
        dut.io.enabled.poke(true)
        dut.io.address.poke(address)
        offerStore(dut, tag = 7, address)
        dut.io.input.ready.expect(true)
        dut.io.output.valid.expect(false)
        dut.clock.step()
        dut.io.input.valid.poke(false)

        dut.io.l1dFlush.valid.expect(true)
        dut.io.l1dFlush.bits.expect(line)
        dut.io.l1dFlush.ready.poke(true)
        dut.clock.step()
        dut.io.l1dFlush.ready.poke(false)
        dut.io.l2Flush.valid.expect(true)
        dut.io.l2Flush.bits.expect(line)
        dut.io.l2Flush.ready.poke(true)
        dut.clock.step()
        dut.io.l2Flush.ready.poke(false)

        dut.io.writebackComplete.valid.poke(true)
        dut.io.writebackComplete.bits.poke(line + 32)
        dut.clock.step()
        dut.io.writebackComplete.valid.poke(false)
        dut.io.output.valid.expect(false)

        dut.io.writebackComplete.valid.poke(true)
        dut.io.writebackComplete.bits.poke(line)
        dut.clock.step()
        dut.io.writebackComplete.valid.poke(false)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.robTag.expect(7)
        dut.io.output.bits.address.expect(address)
        dut.io.output.bits.accessFault.expect(false)
        dut.io.output.ready.poke(true)
        dut.clock.step()
        dut.io.output.ready.poke(false)
        dut.io.output.valid.expect(false)
      }
    }

    it("passes nonmatching and faulting results without a cache flush") {
      simulate(new HostStoreFlush) { dut =>
        clear(dut)
        dut.io.enabled.poke(true)
        dut.io.address.poke(BigInt("80002000", 16))
        dut.io.output.ready.poke(true)
        offerStore(dut, tag = 1, BigInt("80002004", 16))
        dut.io.input.ready.expect(true)
        dut.io.output.valid.expect(true)
        dut.io.l1dFlush.valid.expect(false)
        dut.clock.step()

        offerStore(dut, tag = 2, BigInt("80002000", 16), accessFault = true)
        dut.io.input.ready.expect(true)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.accessFault.expect(true)
        dut.io.l1dFlush.valid.expect(false)
      }
    }
  }
}
