package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.BranchProvider
import zircon.frontend.MiniTagePredictor

class MiniTagePredictorSpec extends AnyFunSpec with ChiselSim {
  private val Pc = BigInt("80000000", 16)

  private def clear(dut: MiniTagePredictor): Unit = {
    dut.io.fetchBase.poke(Pc)
    dut.io.historyBefore.foreach(_.poke(0))
    dut.io.train.valid.poke(false)
    dut.io.train.bits.pc.poke(0)
    dut.io.train.bits.historyBefore.poke(0)
    dut.io.train.bits.actualTaken.poke(false)
    dut.io.train.bits.provider.poke(BranchProvider.Base)
    dut.io.train.bits.alternateProvider.poke(BranchProvider.Base)
    dut.io.train.bits.providerPrediction.poke(false)
    dut.io.train.bits.alternatePrediction.poke(false)
  }

  private def finishScrub(dut: MiniTagePredictor): Unit = {
    dut.io.ready.expect(false)
    dut.clock.step(128)
    dut.io.ready.expect(true)
  }

  private def train(dut: MiniTagePredictor, pc: BigInt, history: BigInt,
      taken: Boolean, provider: BranchProvider.Type,
      prediction: Boolean): Unit = {
    dut.io.train.valid.poke(true)
    dut.io.train.bits.pc.poke(pc)
    dut.io.train.bits.historyBefore.poke(history)
    dut.io.train.bits.actualTaken.poke(taken)
    dut.io.train.bits.provider.poke(provider)
    dut.io.train.bits.alternateProvider.poke(BranchProvider.Base)
    dut.io.train.bits.providerPrediction.poke(prediction)
    dut.io.train.bits.alternatePrediction.poke(false)
    dut.io.ready.expect(false)
    dut.clock.step()
    dut.io.train.valid.poke(false)
    dut.io.ready.expect(true)
  }

  describe("MiniTagePredictor") {
    it("starts from the deterministic weak-not-taken Base provider") {
      simulate(new MiniTagePredictor) { dut =>
        clear(dut)
        finishScrub(dut)
        dut.io.predictions(0).provider.expect(BranchProvider.Base)
        dut.io.predictions(0).alternateProvider.expect(BranchProvider.Base)
        dut.io.predictions(0).taken.expect(false)
      }
    }

    it("allocates a tagged provider only from committed misprediction training") {
      simulate(new MiniTagePredictor) { dut =>
        clear(dut)
        finishScrub(dut)
        train(dut, Pc, 0, true, BranchProvider.Base, false)
        dut.io.predictions(0).provider.expect(BranchProvider.Tagged0)
        dut.io.predictions(0).providerPrediction.expect(true)
        dut.io.predictions(0).taken.expect(true)

        dut.io.historyBefore.foreach(_.poke(1))
        dut.io.predictions(0).provider.expect(BranchProvider.Base)
      }
    }

    it("does not overwrite table zero after all tagged tables already hit") {
      simulate(new MiniTagePredictor) { dut =>
        clear(dut)
        finishScrub(dut)

        // Allocate T0, raise its counter to 5, then allocate T1 and T2.
        train(dut, Pc, 0, taken = true, BranchProvider.Base, prediction = false)
        train(dut, Pc, 0, taken = true, BranchProvider.Tagged0, prediction = true)
        train(dut, Pc, 0, taken = true, BranchProvider.Base, prediction = false)
        train(dut, Pc, 0, taken = true, BranchProvider.Base, prediction = false)

        // All three tables hit.  This is a misprediction from T2, but there
        // is no legal unhit allocation target; T0 must retain its strong-taken
        // counter instead of being reinitialized to weak-taken.
        train(dut, Pc, 0, taken = true, BranchProvider.Tagged2, prediction = false)
        train(dut, Pc, 0, taken = false, BranchProvider.Tagged0, prediction = false)
        dut.io.predictions(0).provider.expect(BranchProvider.Tagged2)
        dut.io.predictions(0).taken.expect(true)
      }
    }
  }
}
