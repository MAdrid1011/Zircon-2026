package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.DualMemoryLoadCompletion

class DualMemoryLoadCompletionSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: DualMemoryLoadCompletion): Unit = {
    dut.io.loadResult.valid.poke(false)
    dut.io.loadResult.bits.robTag.poke(0)
    dut.io.loadResult.bits.destinationPhysical.poke(32)
    dut.io.loadResult.bits.writesInteger.poke(true)
    dut.io.loadResult.bits.m1Owner.poke(false)
    dut.io.loadResult.bits.accessSize.poke(2)
    dut.io.loadResult.bits.unsignedLoad.poke(false)
    dut.io.loadResult.bits.data.poke(0)
    dut.io.loadFault.valid.poke(false)
    dut.io.loadFault.bits.robTag.poke(0)
    dut.io.loadFault.bits.m1Owner.poke(false)
    dut.io.loadFault.bits.trapValue.poke(0)
    dut.io.storeResult.valid.poke(false)
    dut.io.storeResult.bits.robTag.poke(0)
    dut.io.storeResult.bits.address.poke(0)
    dut.io.storeResult.bits.accessFault.poke(false)
    dut.io.fault.foreach { fault =>
      fault.valid.poke(false)
      fault.bits.robTag.poke(0)
      fault.bits.cause.poke(0)
      fault.bits.trapValue.poke(0)
    }
    dut.io.m0Completion.ready.poke(false)
    dut.io.m1Completion.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def result(
      dut: DualMemoryLoadCompletion,
      tag: Int,
      m1Owner: Boolean,
      data: BigInt
  ): Unit = {
    dut.io.loadResult.valid.poke(true)
    dut.io.loadResult.bits.robTag.poke(tag)
    dut.io.loadResult.bits.destinationPhysical.poke(32 + tag)
    dut.io.loadResult.bits.writesInteger.poke(true)
    dut.io.loadResult.bits.m1Owner.poke(m1Owner)
    dut.io.loadResult.bits.accessSize.poke(2)
    dut.io.loadResult.bits.unsignedLoad.poke(false)
    dut.io.loadResult.bits.data.poke(data)
  }

  describe("DualMemoryLoadCompletion") {
    it("routes M0 and M1 results to separate two-entry completion buffers") {
      simulate(new DualMemoryLoadCompletion) { dut =>
        clear(dut)
        result(dut, tag = 1, m1Owner = true, 0x11111111L)
        dut.io.loadResult.ready.expect(true)
        dut.clock.step()
        result(dut, tag = 2, m1Owner = false, 0x22222222L)
        dut.io.loadResult.ready.expect(true)
        dut.clock.step()
        dut.io.loadResult.valid.poke(false)
        dut.io.m0Completion.valid.expect(true)
        dut.io.m0Completion.bits.robTag.expect(2)
        dut.io.m0Completion.bits.data.expect(BigInt("22222222", 16))
        dut.io.m1Completion.valid.expect(true)
        dut.io.m1Completion.bits.robTag.expect(1)
        dut.io.m1Completion.bits.data.expect(BigInt("11111111", 16))
      }
    }

    it("backpressures a response when its selected owner buffer is full") {
      simulate(new DualMemoryLoadCompletion) { dut =>
        clear(dut)
        result(dut, tag = 1, m1Owner = true, 1)
        dut.clock.step()
        result(dut, tag = 2, m1Owner = true, 2)
        dut.clock.step()
        result(dut, tag = 3, m1Owner = true, 3)
        dut.io.loadResult.ready.expect(false)

        result(dut, tag = 4, m1Owner = false, 4)
        dut.io.loadResult.ready.expect(true)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(0)
        dut.io.loadResult.ready.expect(false)
      }
    }

    it("completes an accepted fault without fabricating an integer result") {
      simulate(new DualMemoryLoadCompletion) { dut =>
        clear(dut)
        dut.io.fault(0).valid.poke(true)
        dut.io.fault(0).bits.robTag.poke(5)
        dut.io.fault(0).bits.cause.poke(5)
        dut.io.fault(0).bits.trapValue.poke(0)
        dut.io.fault(0).ready.expect(true)
        dut.io.faultAccepted(0).valid.expect(true)
        dut.clock.step()
        dut.io.fault(0).valid.poke(false)
        dut.io.m0Completion.valid.expect(true)
        dut.io.m0Completion.bits.robTag.expect(5)
        dut.io.m0Completion.bits.writesInteger.expect(false)
        dut.io.m0Completion.bits.data.expect(0)
      }
    }

    it("routes an exact store B result through M0, including cause-7 faults") {
      simulate(new DualMemoryLoadCompletion) { dut =>
        clear(dut)
        dut.io.storeResult.valid.poke(true)
        dut.io.storeResult.bits.robTag.poke(6)
        dut.io.storeResult.bits.address.poke(BigInt("80001004", 16))
        dut.io.storeResult.bits.accessFault.poke(false)
        dut.io.storeResult.ready.expect(true)
        dut.clock.step()
        dut.io.storeResult.valid.poke(false)
        dut.io.m0Completion.valid.expect(true)
        dut.io.m0Completion.bits.robTag.expect(6)
        dut.io.m0Completion.bits.writesInteger.expect(false)
      }

      simulate(new DualMemoryLoadCompletion) { dut =>
        clear(dut)
        dut.io.storeResult.valid.poke(true)
        dut.io.storeResult.bits.robTag.poke(7)
        dut.io.storeResult.bits.address.poke(BigInt("80001008", 16))
        dut.io.storeResult.bits.accessFault.poke(true)
        dut.io.storeResult.ready.expect(true)
        dut.io.faultAccepted(0).valid.expect(true)
        dut.io.faultAccepted(0).record.robTag.expect(7)
        dut.io.faultAccepted(0).record.cause.expect(7)
        dut.io.faultAccepted(0).record.trapValue.expect(BigInt("80001008", 16))
        dut.clock.step()
        dut.io.storeResult.valid.poke(false)
        dut.io.m0Completion.valid.expect(true)
        dut.io.m0Completion.bits.robTag.expect(7)
        dut.io.m0Completion.bits.writesInteger.expect(false)
      }
    }
  }
}
