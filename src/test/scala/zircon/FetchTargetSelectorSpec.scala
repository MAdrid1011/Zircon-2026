package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.FetchTargetSelector

class FetchTargetSelectorSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: FetchTargetSelector): Unit = {
    dut.io.fetchBase.poke(BigInt("80000000", 16))
    dut.io.predictorsReady.poke(true)
    dut.io.rasTopValid.poke(false)
    dut.io.rasTop.poke(0)
    for (slot <- 0 until 4) {
      dut.io.slotValid(slot).poke(true)
      dut.io.predecode(slot).control.poke(false)
      dut.io.predecode(slot).conditional.poke(false)
      dut.io.predecode(slot).direct.poke(false)
      dut.io.predecode(slot).indirect.poke(false)
      dut.io.predecode(slot).directTarget.poke(0)
      dut.io.predecode(slot).call.poke(false)
      dut.io.predecode(slot).ret.poke(false)
      dut.io.btb(slot).hit.poke(false)
      dut.io.btb(slot).way.poke(0)
      dut.io.btb(slot).target.poke(0)
      dut.io.btb(slot).conditional.poke(false)
      dut.io.btb(slot).call.poke(false)
      dut.io.btb(slot).ret.poke(false)
      dut.io.directionTaken(slot).poke(false)
    }
  }

  private def candidate(dut: FetchTargetSelector, slot: Int,
      target: BigInt, conditional: Boolean = false,
      taken: Boolean = true, call: Boolean = false,
      ret: Boolean = false, indirect: Boolean = false,
      btbHit: Boolean = true, way: Int = 0): Unit = {
    dut.io.predecode(slot).control.poke(true)
    dut.io.predecode(slot).conditional.poke(conditional)
    dut.io.predecode(slot).direct.poke(!indirect)
    dut.io.predecode(slot).indirect.poke(indirect)
    dut.io.predecode(slot).directTarget.poke(
      if (indirect) BigInt(0) else target)
    dut.io.predecode(slot).call.poke(call)
    dut.io.predecode(slot).ret.poke(ret)
    dut.io.btb(slot).hit.poke(btbHit)
    dut.io.btb(slot).way.poke(way)
    dut.io.btb(slot).target.poke(target)
    dut.io.btb(slot).conditional.poke(conditional)
    dut.io.btb(slot).call.poke(call)
    dut.io.btb(slot).ret.poke(ret)
    dut.io.directionTaken(slot).poke(taken)
  }

  describe("FetchTargetSelector") {
    it("skips a not-taken conditional and selects the next control instruction") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        candidate(dut, 0, 0x8100, conditional = true, taken = false)
        candidate(dut, 1, 0x8200, call = true, way = 1)
        dut.io.redirect.valid.expect(true)
        dut.io.redirect.bits.slot.expect(1)
        dut.io.redirect.bits.target.expect(0x8200)
        dut.io.redirect.bits.btbWay.expect(1)
        dut.io.acceptedMask.expect(3)
        dut.io.rasAction.valid.expect(true)
        dut.io.rasAction.bits.push.expect(true)
        dut.io.rasAction.bits.pop.expect(false)
        dut.io.rasAction.bits.returnAddress.expect(BigInt("80000008", 16))
      }
    }

    it("gives the earliest taken candidate exclusive redirect ownership") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        candidate(dut, 0, 0x8100, conditional = true, taken = true)
        candidate(dut, 1, 0x8200)
        candidate(dut, 2, 0x8300, ret = true, indirect = true)
        dut.io.redirect.valid.expect(true)
        dut.io.redirect.bits.slot.expect(0)
        dut.io.redirect.bits.target.expect(0x8100)
        dut.io.acceptedMask.expect(1)
        dut.io.rasAction.valid.expect(false)
      }
    }

    it("redirects a direct BTB-miss branch from predecode") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        candidate(dut, 0, 0x8100, conditional = true, taken = true,
          btbHit = false)
        dut.io.redirect.valid.expect(true)
        dut.io.redirect.bits.target.expect(0x8100)
        dut.io.redirect.bits.btbHit.expect(false)
      }
    }

    it("uses RAS for returns and falls back to BTB when the stack is empty") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        candidate(dut, 2, 0x8300, ret = true, indirect = true)
        dut.io.redirect.bits.target.expect(0x8300)
        dut.io.redirect.bits.rasUsed.expect(false)
        dut.io.rasAction.bits.pop.expect(true)

        dut.io.rasTopValid.poke(true)
        dut.io.rasTop.poke(0x9004)
        dut.io.redirect.bits.target.expect(0x9004)
        dut.io.redirect.bits.rasUsed.expect(true)
      }
    }

    it("turns a targetless JALR into a barrier before younger redirects") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        candidate(dut, 1, 0, indirect = true, btbHit = false)
        candidate(dut, 2, 0x8300)
        dut.io.redirect.valid.expect(false)
        dut.io.unresolvedIndirect.valid.expect(true)
        dut.io.unresolvedIndirect.bits.expect(1)
        dut.io.acceptedMask.expect(3)
        dut.io.rasAction.valid.expect(false)
      }
    }

    it("ignores stale BTB hits on non-control instruction words") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        dut.io.btb(0).hit.poke(true)
        dut.io.btb(0).target.poke(0x8100)
        dut.io.redirect.valid.expect(false)
        dut.io.unresolvedIndirect.valid.expect(false)
        dut.io.acceptedMask.expect(15)
      }
    }

    it("suppresses all frontend actions while predictors are stalled") {
      simulate(new FetchTargetSelector) { dut =>
        clearInputs(dut)
        candidate(dut, 0, 0x8100)
        dut.io.predictorsReady.poke(false)
        dut.io.redirect.valid.expect(false)
        dut.io.unresolvedIndirect.valid.expect(false)
        dut.io.rasAction.valid.expect(false)
        dut.io.acceptedMask.expect(0)
      }
    }
  }
}
