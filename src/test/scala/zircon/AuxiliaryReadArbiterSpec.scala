package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.core.AuxiliaryReadArbiter

class AuxiliaryReadArbiterSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: AuxiliaryReadArbiter): Unit = {
    for (candidate <- 0 until 3) {
      dut.io.candidate(candidate).valid.poke(false)
      dut.io.candidate(candidate).bits.robTag.poke(0)
      for (source <- 0 until 2) {
        dut.io.candidate(candidate).bits.sourcePhysical(source).poke(0)
        dut.io.candidate(candidate).bits.sourceRequired(source).poke(false)
      }
    }
    dut.io.traceReadRequired.poke(false)
    dut.io.startSlots.poke(3)
    dut.io.robHeadTag.poke(0)
    dut.io.readData(0).poke(BigInt("11111111", 16))
    dut.io.readData(1).poke(BigInt("22222222", 16))
  }

  private def candidate(
      dut: AuxiliaryReadArbiter,
      index: Int,
      tag: Int,
      sources: Seq[Int]
  ): Unit = {
    dut.io.candidate(index).valid.poke(true)
    dut.io.candidate(index).bits.robTag.poke(tag)
    for (source <- 0 until 2) {
      val required = source < sources.size
      dut.io.candidate(index).bits.sourceRequired(source).poke(required)
      dut.io.candidate(index).bits.sourcePhysical(source).poke(
        sources.lift(source).getOrElse(0))
    }
  }

  describe("AuxiliaryReadArbiter") {
    it("starts the two older one-read LSUs and compacts their sources") {
      simulate(new AuxiliaryReadArbiter) { dut =>
        clear(dut)
        candidate(dut, index = 0, tag = 8, sources = Seq(40, 41))
        candidate(dut, index = 1, tag = 2, sources = Seq(33))
        candidate(dut, index = 2, tag = 3, sources = Seq(34))

        dut.io.grant(0).expect(false)
        dut.io.grant(1).expect(true)
        dut.io.grant(2).expect(true)
        dut.io.readPhysical(0).expect(33)
        dut.io.readPhysical(1).expect(34)
        dut.io.candidateData(1)(0).expect(BigInt("11111111", 16))
        dut.io.candidateData(2)(0).expect(BigInt("22222222", 16))
      }
    }

    it("gives trace capture exclusive priority") {
      simulate(new AuxiliaryReadArbiter) { dut =>
        clear(dut)
        candidate(dut, index = 0, tag = 1, sources = Seq(32, 33))
        candidate(dut, index = 1, tag = 2, sources = Seq(34))
        dut.io.traceReadRequired.poke(true)

        dut.io.grant.foreach(_.expect(false))
        dut.io.readPhysical.foreach(_.expect(0))
      }
    }

    it("respects the last global start slot") {
      simulate(new AuxiliaryReadArbiter) { dut =>
        clear(dut)
        candidate(dut, index = 1, tag = 2, sources = Seq(33))
        candidate(dut, index = 2, tag = 3, sources = Seq(34))
        dut.io.startSlots.poke(1)

        dut.io.grant(1).expect(true)
        dut.io.grant(2).expect(false)
        dut.io.readPhysical(0).expect(33)
        dut.io.readPhysical(1).expect(0)
      }
    }

    it("serializes a two-read M0 store before a younger M1 load") {
      simulate(new AuxiliaryReadArbiter) { dut =>
        clear(dut)
        candidate(dut, index = 1, tag = 2, sources = Seq(35, 36))
        candidate(dut, index = 2, tag = 3, sources = Seq(37))

        dut.io.grant(1).expect(true)
        dut.io.grant(2).expect(false)
        dut.io.readPhysical(0).expect(35)
        dut.io.readPhysical(1).expect(36)
      }
    }
  }
}
