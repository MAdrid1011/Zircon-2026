package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.FetchControlPrediction

class FetchControlPredictionSpec extends AnyFunSpec with ChiselSim {
  private def branch(offset: Int, funct3: Int = 0): BigInt = {
    val immediate = offset & 0x1fff
    (BigInt((immediate >> 12) & 1) << 31) |
      (BigInt((immediate >> 5) & 0x3f) << 25) |
      (BigInt(2) << 20) | (BigInt(1) << 15) |
      (BigInt(funct3) << 12) |
      (BigInt((immediate >> 1) & 0xf) << 8) |
      (BigInt((immediate >> 11) & 1) << 7) | 0x63
  }

  private def clearInputs(dut: FetchControlPrediction): Unit = {
    dut.io.fetchBase.poke(BigInt("80000000", 16))
    dut.io.predictorsReady.poke(true)
    dut.io.rasTopValid.poke(false)
    dut.io.rasTop.poke(0)
    dut.io.accept.poke(false)
    dut.io.historyRecover.valid.poke(false)
    dut.io.historyRecover.bits.poke(0)
    dut.io.clearHistory.poke(false)
    for (slot <- 0 until 4) {
      dut.io.instructions(slot).poke(BigInt("00000013", 16))
      dut.io.slotValid(slot).poke(true)
      dut.io.directionTaken(slot).poke(false)
      dut.io.btb(slot).hit.poke(false)
      dut.io.btb(slot).way.poke(0)
      dut.io.btb(slot).target.poke(0)
      dut.io.btb(slot).conditional.poke(false)
      dut.io.btb(slot).call.poke(false)
      dut.io.btb(slot).ret.poke(false)
    }
  }

  describe("FetchControlPrediction") {
    it("redirects and records history for a taken BTB-miss branch") {
      simulate(new FetchControlPrediction) { dut =>
        clearInputs(dut)
        dut.io.instructions(0).poke(branch(16))
        dut.io.directionTaken(0).poke(true)
        dut.io.predecode(0).conditional.expect(true)
        dut.io.redirect.valid.expect(true)
        dut.io.redirect.bits.target.expect(BigInt("80000010", 16))
        dut.io.redirect.bits.btbHit.expect(false)
        dut.io.acceptedMask.expect(1)
        dut.io.historyBefore(0).expect(0)

        dut.io.accept.poke(true)
        dut.clock.step()
        dut.io.accept.poke(false)
        dut.io.currentHistory.expect(1)
      }
    }

    it("appends older conditionals but truncates at a targetless JALR") {
      simulate(new FetchControlPrediction) { dut =>
        clearInputs(dut)
        dut.io.historyRecover.valid.poke(true)
        dut.io.historyRecover.bits.poke(1)
        dut.clock.step()
        dut.io.historyRecover.valid.poke(false)

        dut.io.instructions(0).poke(branch(16))
        dut.io.directionTaken(0).poke(false)
        dut.io.instructions(1).poke(BigInt("000100e7", 16)) // jalr x1,0(x2)
        dut.io.unresolvedIndirect.valid.expect(true)
        dut.io.unresolvedIndirect.bits.expect(1)
        dut.io.acceptedMask.expect(3)
        dut.io.redirect.valid.expect(false)

        dut.io.accept.poke(true)
        dut.clock.step()
        dut.io.accept.poke(false)
        dut.io.currentHistory.expect(2)
      }
    }
  }
}
