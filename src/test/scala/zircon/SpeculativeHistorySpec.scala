package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.SpeculativeGlobalHistory

class SpeculativeHistorySpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: SpeculativeGlobalHistory): Unit = {
    for (slot <- 0 until 4) {
      dut.io.slotValid(slot).poke(true)
      dut.io.conditional(slot).poke(false)
      dut.io.predictedTaken(slot).poke(false)
    }
    dut.io.acceptedMask.poke(15)
    dut.io.advance.poke(false)
    dut.io.recover.valid.poke(false)
    dut.io.recover.bits.poke(0)
    dut.io.clear.poke(false)
  }

  describe("SpeculativeGlobalHistory") {
    it("emits per-slot checkpoints and appends every accepted conditional") {
      simulate(new SpeculativeGlobalHistory) { dut =>
        clearInputs(dut)
        dut.io.conditional(0).poke(true)
        dut.io.predictedTaken(0).poke(true)
        dut.io.conditional(1).poke(true)
        dut.io.predictedTaken(1).poke(false)
        dut.io.conditional(3).poke(true)
        dut.io.predictedTaken(3).poke(true)
        dut.io.historyBefore(0).expect(0)
        dut.io.historyBefore(1).expect(1)
        dut.io.historyBefore(2).expect(2)
        dut.io.historyBefore(3).expect(2)
        dut.io.historyAfter.expect(5)

        dut.io.advance.poke(true)
        dut.clock.step()
        dut.io.advance.poke(false)
        dut.io.current.expect(5)
      }
    }

    it("does not append conditionals younger than the accepted prefix") {
      simulate(new SpeculativeGlobalHistory) { dut =>
        clearInputs(dut)
        dut.io.conditional.foreach(_.poke(true))
        dut.io.predictedTaken.foreach(_.poke(true))
        dut.io.acceptedMask.poke(3)
        dut.io.historyAfter.expect(3)
        dut.io.advance.poke(true)
        dut.clock.step()
        dut.io.current.expect(3)
      }
    }

    it("installs recovery history ahead of an ordinary advance") {
      simulate(new SpeculativeGlobalHistory) { dut =>
        clearInputs(dut)
        dut.io.conditional(0).poke(true)
        dut.io.predictedTaken(0).poke(true)
        dut.io.advance.poke(true)
        dut.io.recover.valid.poke(true)
        dut.io.recover.bits.poke(BigInt("8000000000000001", 16))
        dut.clock.step()
        dut.io.advance.poke(false)
        dut.io.recover.valid.poke(false)
        dut.io.current.expect(BigInt("8000000000000001", 16))
      }
    }

    it("shifts at the 64-bit boundary and gives clear highest priority") {
      simulate(new SpeculativeGlobalHistory) { dut =>
        clearInputs(dut)
        dut.io.recover.valid.poke(true)
        dut.io.recover.bits.poke(BigInt("8000000000000000", 16))
        dut.clock.step()
        dut.io.recover.valid.poke(false)
        dut.io.conditional(0).poke(true)
        dut.io.predictedTaken(0).poke(true)
        dut.io.advance.poke(true)
        dut.io.historyAfter.expect(1)

        dut.io.clear.poke(true)
        dut.io.recover.valid.poke(true)
        dut.io.recover.bits.poke(0x55)
        dut.clock.step()
        dut.io.clear.poke(false)
        dut.io.recover.valid.poke(false)
        dut.io.advance.poke(false)
        dut.io.current.expect(0)
      }
    }
  }
}
