package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.IntegerReadyTable

class IntegerReadyTableSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: IntegerReadyTable): Unit = {
    dut.io.allocate.foreach { allocation =>
      allocation.valid.poke(false)
      allocation.bits.poke(0)
    }
    dut.io.complete.foreach { completion =>
      completion.valid.poke(false)
      completion.bits.poke(0)
    }
  }

  describe("IntegerReadyTable") {
    it("tracks dual allocation and completion with same-cycle forwarding") {
      simulate(new IntegerReadyTable) { dut =>
        clearInputs(dut)
        val allReady = (BigInt(1) << 56) - 1
        dut.io.ready.expect(allReady)

        dut.io.allocate(0).valid.poke(true)
        dut.io.allocate(0).bits.poke(32)
        dut.io.allocate(1).valid.poke(true)
        dut.io.allocate(1).bits.poke(33)
        val p32p33Busy = allReady & ~(BigInt(1) << 32) & ~(BigInt(1) << 33)
        dut.io.ready.expect(p32p33Busy)
        dut.clock.step()
        clearInputs(dut)
        dut.io.ready.expect(p32p33Busy)

        dut.io.complete(0).valid.poke(true)
        dut.io.complete(0).bits.poke(32)
        val p33Busy = allReady & ~(BigInt(1) << 33)
        dut.io.ready.expect(p33Busy)
        dut.clock.step()
        clearInputs(dut)
        dut.io.ready.expect(p33Busy)

        dut.io.complete(1).valid.poke(true)
        dut.io.complete(1).bits.poke(33)
        dut.io.allocate(0).valid.poke(true)
        dut.io.allocate(0).bits.poke(34)
        val p34Busy = allReady & ~(BigInt(1) << 34)
        dut.io.ready.expect(p34Busy)
        dut.clock.step()
        clearInputs(dut)
        dut.io.ready.expect(p34Busy)
      }
    }
  }
}
