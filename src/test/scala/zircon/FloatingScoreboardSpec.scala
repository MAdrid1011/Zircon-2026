package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FloatingScoreboard

class FloatingScoreboardSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: FloatingScoreboard): Unit = {
    dut.io.allocate.foreach { allocation =>
      allocation.valid.poke(false)
      allocation.bits.sourceValid.foreach(_.poke(false))
      allocation.bits.source.foreach(_.poke(0))
      allocation.bits.robTag.poke(0)
      allocation.bits.destinationValid.poke(false)
      allocation.bits.destination.poke(0)
    }
    dut.io.readRelease.valid.poke(false)
    dut.io.readRelease.bits.sourceValid.foreach(_.poke(false))
    dut.io.readRelease.bits.source.foreach(_.poke(0))
    dut.io.readRelease.bits.robTag.poke(0)
    dut.io.readRelease.bits.destinationValid.poke(false)
    dut.io.readRelease.bits.destination.poke(0)
    dut.io.complete.valid.poke(false)
    dut.io.complete.bits.robTag.poke(0)
    dut.io.complete.bits.destination.poke(0)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def allocation(
      dut: FloatingScoreboard,
      lane: Int,
      sources: Seq[Int],
      destination: Option[Int],
      tag: Int
  ): Unit = {
    val request = dut.io.allocate(lane)
    request.valid.poke(true)
    request.bits.robTag.poke(tag)
    request.bits.sourceValid.zipWithIndex.foreach { case (valid, index) =>
      valid.poke(index < sources.length)
      request.bits.source(index).poke(sources.lift(index).getOrElse(0))
    }
    request.bits.destinationValid.poke(destination.nonEmpty)
    request.bits.destination.poke(destination.getOrElse(0))
  }

  private def release(dut: FloatingScoreboard, sources: Seq[Int], tag: Int,
      destination: Option[Int] = None): Unit = {
    dut.io.readRelease.valid.poke(true)
    dut.io.readRelease.bits.robTag.poke(tag)
    dut.io.readRelease.bits.sourceValid.zipWithIndex.foreach { case (valid, index) =>
      valid.poke(index < sources.length)
      dut.io.readRelease.bits.source(index).poke(sources.lift(index).getOrElse(0))
    }
    dut.io.readRelease.bits.destinationValid.poke(destination.nonEmpty)
    dut.io.readRelease.bits.destination.poke(destination.getOrElse(0))
  }

  private def complete(dut: FloatingScoreboard, tag: Int, destination: Int): Unit = {
    dut.io.complete.valid.poke(true)
    dut.io.complete.bits.robTag.poke(tag)
    dut.io.complete.bits.destination.poke(destination)
  }

  describe("FloatingScoreboard") {
    it("blocks RAW, WAR, WAW, and duplicate FMA source hazards") {
      simulate(new FloatingScoreboard) { dut =>
        clear(dut)

        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(1), tag = 1)
        dut.io.allocateReady(0).expect(true)
        dut.clock.step()
        clear(dut)

        allocation(dut, lane = 0, sources = Seq(1), destination = None, tag = 2)
        dut.io.allocateReady(0).expect(false) // RAW
        clear(dut)
        release(dut, Seq.empty, tag = 1, destination = Some(1))
        dut.clock.step()
        clear(dut)
        complete(dut, tag = 1, destination = 1)
        dut.clock.step()
        clear(dut)

        allocation(dut, lane = 0, sources = Seq(1), destination = None, tag = 2)
        dut.io.allocateReady(0).expect(true)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(1), tag = 3)
        dut.io.allocateReady(0).expect(false) // WAR
        clear(dut)
        release(dut, Seq(1), tag = 2)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(1), tag = 3)
        dut.io.allocateReady(0).expect(true)
        dut.clock.step()
        clear(dut)

        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(2), tag = 4)
        allocation(dut, lane = 1, sources = Seq.empty, destination = Some(2), tag = 5)
        dut.io.allocateReady(0).expect(true)
        dut.io.allocateReady(1).expect(false) // same-cycle WAW
        dut.clock.step()
        clear(dut)
        release(dut, Seq.empty, tag = 4, destination = Some(2))
        dut.clock.step()
        clear(dut)
        complete(dut, tag = 4, destination = 2)
        dut.clock.step()
        clear(dut)

        allocation(dut, lane = 0, sources = Seq(5, 5, 5), destination = None, tag = 6)
        dut.io.allocateReady(0).expect(true)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(5), tag = 7)
        dut.io.allocateReady(0).expect(false)
        clear(dut)
        release(dut, Seq(5, 5, 5), tag = 6)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(5), tag = 7)
        dut.io.allocateReady(0).expect(true)
      }
    }

    it("retains older FPR reservations while recovering younger tagged work") {
      simulate(new FloatingScoreboard) { dut =>
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(1), tag = 2)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(2), tag = 3)
        dut.clock.step()
        clear(dut)

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(2)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq(1), destination = None, tag = 4)
        dut.io.allocateReady(0).expect(false)
        clear(dut)
        allocation(dut, lane = 1, sources = Seq(2), destination = None, tag = 5)
        dut.io.allocateReady(1).expect(true)
        dut.clock.step()
        clear(dut)

        release(dut, Seq.empty, tag = 2, destination = Some(1))
        dut.clock.step()
        clear(dut)
        complete(dut, tag = 2, destination = 1)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq(1), destination = None, tag = 4)
        dut.io.allocateReady(0).expect(true)

        dut.io.flush.poke(true)
        dut.clock.step()
        clear(dut)
        allocation(dut, lane = 0, sources = Seq.empty, destination = Some(1), tag = 6)
        dut.io.allocateReady(0).expect(true)
      }
    }
  }
}
