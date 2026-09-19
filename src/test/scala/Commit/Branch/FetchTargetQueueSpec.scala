import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FetchTargetQueueSpec extends AnyFreeSpec with ChiselSim {
    private val p = FrontendParams(ftqDepth = 4)

    private def initialize(dut: FetchTargetQueue): Unit = {
        dut.io.allocate.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FrontendFtqAllocation(p)))
        }
        dut.io.commit.flush.poke(false)
        dut.io.commit.pop.poke(0)
        dut.io.commit.branch.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FtqBranchUpdate(p)))
        }
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def allocation(dut: FetchTargetQueue, lane: Int, token: Int, nextPc: Int): Unit = {
        dut.io.allocate(lane).valid.poke(true)
        dut.io.allocate(lane).bits.fetchToken.poke(token)
        dut.io.allocate(lane).bits.record.nextPc.poke(nextPc)
    }

    "sparse multi-allocation is compact, ordered and flushable" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            allocation(dut, 0, 10, 0x1100)
            allocation(dut, 2, 11, 0x2200)
            dut.io.allocate(0).valid.expect(true)
            dut.io.allocateIdx(0).expect(0)
            dut.io.allocateIdx(2).expect(1)
            dut.clock.step()
            dut.io.allocate.foreach(_.valid.poke(false))

            dut.io.used.expect(2)
            dut.io.commit.head(0).valid.expect(true)
            dut.io.commit.head(0).bits.fetchToken.expect(10)
            dut.io.commit.head(0).bits.record.nextPc.expect(0x1100)
            dut.io.commit.head(1).valid.expect(true)
            dut.io.commit.head(1).bits.fetchToken.expect(11)
            dut.io.commit.head(1).bits.record.nextPc.expect(0x2200)

            dut.io.commit.pop.poke(1)
            allocation(dut, 1, 12, 0x3300)
            dut.io.allocateIdx(1).expect(2)
            dut.clock.step()
            dut.io.commit.pop.poke(0)
            dut.io.allocate(1).valid.poke(false)
            dut.io.used.expect(2)
            dut.io.commit.head(0).bits.fetchToken.expect(11)
            dut.io.commit.head(1).bits.fetchToken.expect(12)

            dut.io.commit.flush.poke(true)
            dut.clock.step()
            dut.io.commit.flush.poke(false)
            dut.io.used.expect(0)
            dut.io.commit.head.foreach(_.valid.expect(false))
            dut.io.allocateIdx(0).expect(0)
        }
    }
}
