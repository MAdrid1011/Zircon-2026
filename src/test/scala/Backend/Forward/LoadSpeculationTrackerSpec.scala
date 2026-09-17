import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class LoadSpeculationTrackerSpec extends AnyFreeSpec with ChiselSim {
    "dual allocation is unique and results release tokens on the following cycle" in {
        simulate(new LoadSpeculationTracker) { dut =>
            dut.io.request.foreach(_.poke(false))
            dut.io.allocate.foreach(_.poke(false))
            dut.io.result.foreach { result =>
                result.valid.poke(false)
                result.bits.mask.poke(0)
                result.bits.failed.poke(false)
            }
            dut.io.flush.poke(false)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            dut.io.request.foreach(_.poke(true))
            dut.io.grant(0).expect(1)
            dut.io.grant(1).expect(2)
            dut.io.allocate.foreach(_.poke(true))
            dut.clock.step()
            dut.io.active.expect(3)

            dut.io.allocate.foreach(_.poke(false))
            dut.io.request.foreach(_.poke(false))
            dut.io.result(0).valid.poke(true)
            dut.io.result(0).bits.mask.poke(1)
            dut.io.result(0).bits.failed.poke(false)
            dut.io.result(1).valid.poke(true)
            dut.io.result(1).bits.mask.poke(2)
            dut.io.result(1).bits.failed.poke(true)
            dut.io.resolution.resolvedMask.expect(3)
            dut.io.resolution.failedMask.expect(2)
            dut.clock.step()
            dut.io.active.expect(0)

            dut.io.result.foreach(_.valid.poke(false))
            dut.io.request(1).poke(true)
            dut.io.grant(1).expect(1)
        }
    }
}
