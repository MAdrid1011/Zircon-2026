package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FirstFaultTracker

class FirstFaultTrackerSpec extends AnyFunSpec with ChiselSim {
  describe("FirstFaultTracker") {
    it("retains the oldest fault independent of detection order") {
      simulate(new FirstFaultTracker(2)) { dut =>
        dut.io.clear.poke(false)
        dut.io.flush.poke(false)
        for (candidate <- dut.io.candidates) {
          candidate.valid.poke(false)
          candidate.record.order.poke(0)
          candidate.record.robTag.poke(0)
          candidate.record.cause.poke(0)
          candidate.record.trapValue.poke(0)
        }

        dut.io.candidates(0).valid.poke(true)
        dut.io.candidates(0).record.order.poke(12)
        dut.io.candidates(0).record.robTag.poke(12)
        dut.io.candidates(0).record.cause.poke(5)
        dut.io.candidates(1).valid.poke(true)
        dut.io.candidates(1).record.order.poke(9)
        dut.io.candidates(1).record.robTag.poke(9)
        dut.io.candidates(1).record.cause.poke(2)
        dut.clock.step()

        dut.io.valid.expect(true)
        dut.io.record.order.expect(9)
        dut.io.record.cause.expect(2)

        dut.io.candidates(0).valid.poke(false)
        dut.io.candidates(1).valid.poke(false)
        dut.io.clear.poke(true)
        dut.clock.step()
        dut.io.valid.expect(false)
      }
    }
  }
}
