import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.BackendParams

class ReadyBoardSpec extends AnyFreeSpec with ChiselSim {
    private val p = BackendParams()
    private def tag(isFp: Boolean, index: Int): Int =
        (if (isFp) 1 << p.physWidth else 0) | index

    private def initialize(dut: ReadyBoard): Unit = {
        for (lane <- 0 until dut.width; source <- 0 until 3) {
            dut.io.query(lane).prs(source).poke(0)
            dut.io.query(lane).valid(source).poke(false)
        }
        dut.io.allocate.foreach { entry => entry.valid.poke(false); entry.bits.poke(0) }
        dut.io.wakeup.foreach { wakeup => wakeup.prd.poke(0); wakeup.specMask.poke(0) }
        dut.io.speculation.resolvedMask.poke(0)
        dut.io.speculation.failedMask.poke(0)
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    "allocation, wakeup, speculation resolution and flush preserve readiness" in {
        simulate(new ReadyBoard) { dut =>
            initialize(dut)
            val integer = tag(isFp = false, 33)
            val floatingZero = tag(isFp = true, 0)
            dut.io.query(0).valid(0).poke(true)
            dut.io.query(0).prs(0).poke(integer)
            dut.io.query(0).valid(1).poke(true)
            dut.io.query(0).prs(1).poke(floatingZero)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).ready(1).expect(true)

            dut.io.allocate(0).valid.poke(true)
            dut.io.allocate(0).bits.poke(integer)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.state(0).ready(0).expect(false)

            dut.io.wakeup(0).prd.poke(integer)
            dut.io.wakeup(0).specMask.poke(4)
            dut.io.state(0).ready(0).expect(false)
            dut.io.state(0).specMask(0).expect(0)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(4)

            dut.io.speculation.resolvedMask.poke(4)
            dut.io.speculation.failedMask.poke(4)
            dut.io.state(0).ready(0).expect(false)
            dut.io.state(0).specMask(0).expect(0)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.state(0).ready(0).expect(false)

            // Flush has final state priority over a coincident wakeup and failed
            // speculation result, so producers need not mask either event.
            dut.io.wakeup(0).prd.poke(integer)
            dut.io.wakeup(0).specMask.poke(8)
            dut.io.speculation.resolvedMask.poke(8)
            dut.io.speculation.failedMask.poke(8)
            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(0)

            dut.io.wakeup(0).prd.poke(integer)
            dut.io.wakeup(0).specMask.poke(2)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.speculation.resolvedMask.poke(2)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(0)
        }
    }

    "wakeup updates the registered query state at the clock edge" in {
        simulate(new ReadyBoard) { dut =>
            initialize(dut)
            val physical = tag(isFp = true, 39)
            dut.io.query(1).valid(2).poke(true)
            dut.io.query(1).prs(2).poke(physical)
            dut.io.allocate(1).valid.poke(true)
            dut.io.allocate(1).bits.poke(physical)
            dut.clock.step()
            dut.io.allocate(1).valid.poke(false)
            dut.io.state(1).ready(2).expect(false)
            dut.io.wakeup(6).prd.poke(physical)
            dut.io.state(1).ready(2).expect(false)
            dut.clock.step()
            dut.io.state(1).ready(2).expect(true)
        }
    }
}
