package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.CacheFenceDrainController

class CacheFenceDrainControllerSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: CacheFenceDrainController): Unit = {
    dut.io.request.poke(false)
    dut.io.l1dDrained.poke(false)
    dut.io.l2Drained.poke(false)
    dut.io.writebackBusy.poke(false)
  }

  describe("CacheFenceDrainController") {
    it("requires L1D, L2, and the final ID-5 B response before completion") {
      simulate(new CacheFenceDrainController) { dut =>
        clear(dut)
        dut.io.complete.expect(false)
        dut.io.request.poke(true)
        dut.clock.step()
        dut.io.l1dDrain.expect(true)
        dut.io.l2Drain.expect(false)
        dut.io.l1dDrained.poke(true)
        dut.clock.step()
        dut.io.l1dDrained.poke(false)
        dut.io.l2Drain.expect(true)
        dut.io.l2Drained.poke(true)
        dut.io.writebackBusy.poke(true)
        dut.clock.step(2)
        dut.io.complete.expect(false)
        dut.io.writebackBusy.poke(false)
        dut.clock.step()
        dut.io.complete.expect(true)
        dut.io.request.poke(false)
        dut.clock.step()
        dut.io.complete.expect(false)
      }
    }
  }
}
