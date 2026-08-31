package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.BankedBranchTargetBuffer

class BranchTargetBufferSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: BankedBranchTargetBuffer,
      fetchBase: BigInt): Unit = {
    dut.io.fetchBase.poke(fetchBase)
    dut.io.invalidate.poke(false)
    dut.io.train.valid.poke(false)
    dut.io.train.bits.pc.poke(0)
    dut.io.train.bits.target.poke(0)
    dut.io.train.bits.conditional.poke(false)
    dut.io.train.bits.call.poke(false)
    dut.io.train.bits.ret.poke(false)
  }

  private def finishScrub(dut: BankedBranchTargetBuffer): Unit = {
    dut.io.ready.expect(false)
    dut.clock.step(8)
    dut.io.ready.expect(true)
  }

  private def train(dut: BankedBranchTargetBuffer, pc: BigInt,
      target: BigInt, conditional: Boolean = false,
      call: Boolean = false, ret: Boolean = false): Unit = {
    dut.io.train.valid.poke(true)
    dut.io.train.bits.pc.poke(pc)
    dut.io.train.bits.target.poke(target)
    dut.io.train.bits.conditional.poke(conditional)
    dut.io.train.bits.call.poke(call)
    dut.io.train.bits.ret.poke(ret)
    dut.io.ready.expect(false)
    dut.clock.step()
    dut.io.train.valid.poke(false)
    dut.io.ready.expect(true)
  }

  describe("BankedBranchTargetBuffer") {
    it("starts with all 64 entries invalid after deterministic scrub") {
      simulate(new BankedBranchTargetBuffer) { dut =>
        clearInputs(dut, BigInt("80000000", 16))
        dut.io.predictions.foreach(_.hit.expect(false))
        finishScrub(dut)
        dut.io.predictions.foreach(_.hit.expect(false))
      }
    }

    it("queries four banks and preserves target and control attributes") {
      simulate(new BankedBranchTargetBuffer) { dut =>
        val base = BigInt("80000000", 16)
        clearInputs(dut, base)
        finishScrub(dut)
        train(dut, base, base + 0x100, conditional = true)
        train(dut, base + 4, base + 0x200, call = true)
        train(dut, base + 8, base + 0x300, ret = true)
        train(dut, base + 12, base + 0x400, call = true, ret = true)

        for (slot <- 0 until 4) {
          dut.io.predictions(slot).hit.expect(true)
          dut.io.predictions(slot).target.expect(base + (slot + 1) * 0x100)
        }
        dut.io.predictions(0).conditional.expect(true)
        dut.io.predictions(1).call.expect(true)
        dut.io.predictions(2).ret.expect(true)
        dut.io.predictions(3).call.expect(true)
        dut.io.predictions(3).ret.expect(true)
      }
    }

    it("rotates banks correctly across a 16-byte boundary") {
      simulate(new BankedBranchTargetBuffer) { dut =>
        val base = BigInt("80000000", 16)
        clearInputs(dut, base)
        finishScrub(dut)
        for (slot <- 0 until 4) {
          train(dut, base + slot * 4, base + 0x100 + slot * 4)
        }

        dut.io.fetchBase.poke(base + 4)
        for (slot <- 0 until 3) {
          dut.io.predictions(slot).hit.expect(true)
          dut.io.predictions(slot).target.expect(base + 0x104 + slot * 4)
        }
        dut.io.predictions(3).hit.expect(false)
      }
    }

    it("uses invalid ways first and updates replacement only on training") {
      simulate(new BankedBranchTargetBuffer) { dut =>
        val first = BigInt("80000000", 16)
        val second = first + 0x80
        val third = first + 0x100
        clearInputs(dut, first)
        finishScrub(dut)
        train(dut, first, first + 4)
        train(dut, second, second + 4)

        dut.io.fetchBase.poke(first)
        dut.io.predictions(0).hit.expect(true)
        dut.io.fetchBase.poke(second)
        dut.io.predictions(0).hit.expect(true)

        // A committed hit makes the other way the next replacement victim.
        train(dut, first, first + 8)
        train(dut, third, third + 4)
        dut.io.fetchBase.poke(first)
        dut.io.predictions(0).hit.expect(true)
        dut.io.predictions(0).target.expect(first + 8)
        dut.io.fetchBase.poke(second)
        dut.io.predictions(0).hit.expect(false)
        dut.io.fetchBase.poke(third)
        dut.io.predictions(0).hit.expect(true)
      }
    }

    it("invalidates every way and stalls queries for the eight-row scrub") {
      simulate(new BankedBranchTargetBuffer) { dut =>
        val base = BigInt("80000000", 16)
        clearInputs(dut, base)
        finishScrub(dut)
        train(dut, base, base + 0x100)
        dut.io.predictions(0).hit.expect(true)

        dut.io.invalidate.poke(true)
        dut.io.ready.expect(false)
        dut.io.predictions.foreach(_.hit.expect(false))
        dut.clock.step()
        dut.io.invalidate.poke(false)
        dut.clock.step(8)
        dut.io.ready.expect(true)
        dut.io.predictions.foreach(_.hit.expect(false))
      }
    }
  }
}
