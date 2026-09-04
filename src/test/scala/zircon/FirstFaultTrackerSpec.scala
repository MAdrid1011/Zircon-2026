package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FirstFaultTracker

class FirstFaultTrackerSpec extends AnyFunSpec with ChiselSim {
  describe("FirstFaultTracker") {
    it("retains the oldest fault independent of detection order") {
      simulate(new FirstFaultTracker(2)) { dut =>
        dut.io.robHeadTag.poke(8)
        dut.io.headAdvance.poke(0)
        dut.io.clear.poke(false)
        dut.io.flush.poke(false)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        for (candidate <- dut.io.candidates) {
          candidate.valid.poke(false)
          candidate.record.robTag.poke(0)
          candidate.record.cause.poke(0)
          candidate.record.trapValue.poke(0)
        }

        dut.io.candidates(0).valid.poke(true)
        dut.io.candidates(0).record.robTag.poke(12)
        dut.io.candidates(0).record.cause.poke(5)
        dut.io.candidates(1).valid.poke(true)
        dut.io.candidates(1).record.robTag.poke(9)
        dut.io.candidates(1).record.cause.poke(2)
        dut.clock.step()

        dut.io.valid.expect(true)
        dut.io.record.robTag.expect(9)
        dut.io.record.cause.expect(2)

        // Index 2 is younger than index 22 when head is 20, despite numeric order.
        dut.io.robHeadTag.poke(20)
        dut.io.headAdvance.poke(0)
        dut.io.clear.poke(true)
        dut.io.candidates.foreach(_.valid.poke(false))
        dut.clock.step()
        dut.io.clear.poke(false)
        dut.io.candidates(0).valid.poke(true)
        dut.io.candidates(0).record.robTag.poke(2)
        dut.io.candidates(0).record.cause.poke(5)
        dut.io.candidates(1).valid.poke(true)
        dut.io.candidates(1).record.robTag.poke(22)
        dut.io.candidates(1).record.cause.poke(3)
        dut.clock.step()
        dut.io.record.robTag.expect(22)
        dut.io.record.cause.expect(3)

        dut.io.candidates(0).valid.poke(false)
        dut.io.candidates(1).valid.poke(false)
        dut.io.clear.poke(true)
        dut.io.valid.expect(true)
        dut.clock.step()
        dut.io.valid.expect(false)
      }
    }

    it("removes only faults younger than the resolving branch") {
      simulate(new FirstFaultTracker(2)) { dut =>
        dut.io.robHeadTag.poke(20)
        dut.io.clear.poke(false)
        dut.io.flush.poke(false)
        dut.io.squash.valid.poke(false)
        dut.io.squash.bits.poke(0)
        dut.io.candidates.foreach { candidate =>
          candidate.valid.poke(false)
          candidate.record.robTag.poke(0)
          candidate.record.cause.poke(0)
          candidate.record.trapValue.poke(0)
        }

        dut.io.candidates(0).valid.poke(true)
        dut.io.candidates(0).record.robTag.poke(2)
        dut.io.candidates(0).record.cause.poke(5)
        dut.clock.step()
        dut.io.candidates(0).valid.poke(false)
        dut.io.valid.expect(true)

        // Tag 2 is younger than wrapped branch tag 23. Visibility is kept
        // register-only during the launch cycle and removed on the squash edge.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.valid.expect(true)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.valid.expect(false)

        // An older stored fault survives, while a same-cycle younger candidate dies.
        dut.io.candidates(0).valid.poke(true)
        dut.io.candidates(0).record.robTag.poke(21)
        dut.io.candidates(0).record.cause.poke(1)
        dut.clock.step()
        dut.io.candidates(0).valid.poke(false)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.candidates(1).valid.poke(true)
        dut.io.candidates(1).record.robTag.poke(1)
        dut.io.candidates(1).record.cause.poke(7)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.candidates(1).valid.poke(false)
        dut.io.valid.expect(true)
        dut.io.record.robTag.expect(21)
        dut.io.record.cause.expect(1)
      }
    }
  }
}
