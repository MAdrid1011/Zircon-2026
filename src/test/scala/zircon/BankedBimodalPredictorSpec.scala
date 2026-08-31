package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.BankedBimodalPredictor

class BankedBimodalPredictorSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: BankedBimodalPredictor, base: BigInt): Unit = {
    dut.io.fetchBase.poke(base)
    dut.io.train.valid.poke(false)
    dut.io.train.bits.pc.poke(0)
    dut.io.train.bits.taken.poke(false)
  }

  private def finishScrub(dut: BankedBimodalPredictor): Unit = {
    dut.io.ready.expect(false)
    dut.clock.step(128)
    dut.io.ready.expect(true)
  }

  private def train(
      dut: BankedBimodalPredictor,
      pc: BigInt,
      taken: Boolean
  ): Unit = {
    dut.io.train.valid.poke(true)
    dut.io.train.bits.pc.poke(pc)
    dut.io.train.bits.taken.poke(taken)
    dut.io.ready.expect(false)
    dut.clock.step()
    dut.io.train.valid.poke(false)
    dut.io.ready.expect(true)
  }

  describe("BankedBimodalPredictor") {
    it("scrubs all 512 counters to deterministic weak-not-taken state") {
      simulate(new BankedBimodalPredictor) { dut =>
        clearInputs(dut, BigInt("80000000", 16))
        dut.io.predictions.foreach { prediction =>
          prediction.counter.expect(1)
          prediction.taken.expect(false)
        }
        finishScrub(dut)
        dut.io.predictions.foreach { prediction =>
          prediction.counter.expect(1)
          prediction.taken.expect(false)
        }
      }
    }

    it("saturates taken and not-taken training at both two-bit boundaries") {
      simulate(new BankedBimodalPredictor) { dut =>
        val pc = BigInt("80000008", 16)
        clearInputs(dut, pc)
        finishScrub(dut)

        train(dut, pc, taken = true)
        dut.io.predictions(0).counter.expect(2)
        dut.io.predictions(0).taken.expect(true)
        train(dut, pc, taken = true)
        train(dut, pc, taken = true)
        dut.io.predictions(0).counter.expect(3)

        train(dut, pc, taken = false)
        train(dut, pc, taken = false)
        dut.io.predictions(0).counter.expect(1)
        dut.io.predictions(0).taken.expect(false)
        train(dut, pc, taken = false)
        train(dut, pc, taken = false)
        dut.io.predictions(0).counter.expect(0)
      }
    }

    it("maps four consecutive PCs to distinct banks") {
      simulate(new BankedBimodalPredictor) { dut =>
        val base = BigInt("80000000", 16)
        clearInputs(dut, base)
        finishScrub(dut)
        for (slot <- 0 until 4) {
          train(dut, base + slot * 4, taken = true)
        }
        for (slot <- 0 until 4) {
          dut.io.predictions(slot).counter.expect(2)
          dut.io.predictions(slot).taken.expect(true)
        }
      }
    }

    it("rotates banks correctly when a fetch group crosses a 16-byte boundary") {
      simulate(new BankedBimodalPredictor) { dut =>
        val alignedBlock = BigInt("80000000", 16)
        clearInputs(dut, alignedBlock)
        finishScrub(dut)
        for (slot <- 0 until 4) {
          train(dut, alignedBlock + slot * 4, taken = true)
        }

        dut.io.fetchBase.poke(alignedBlock + 4)
        for (slot <- 0 until 3) {
          dut.io.predictions(slot).taken.expect(true)
        }
        dut.io.predictions(3).counter.expect(1)
        dut.io.predictions(3).taken.expect(false)
      }
    }
  }
}
