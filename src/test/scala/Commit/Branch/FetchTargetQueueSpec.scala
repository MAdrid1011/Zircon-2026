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
        dut.io.commit.readIdx.foreach(_.poke(0))
        dut.io.commit.branch.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FtqBranchUpdate(p)))
        }
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def allocation(dut: FetchTargetQueue, lane: Int, nextPc: Int): Unit = {
        dut.io.allocate(lane).valid.poke(true)
        dut.io.allocate(lane).bits.record.nextPc.poke(nextPc)
    }

    "sparse multi-allocation is compact, ordered and flushable" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            allocation(dut, 0, 0x1100)
            allocation(dut, 2, 0x2200)
            dut.io.allocate(0).valid.expect(true)
            dut.io.allocateIdx(0).expect(0)
            dut.io.allocateIdx(2).expect(1)
            dut.clock.step()
            dut.io.allocate.foreach(_.valid.poke(false))

            dut.io.used.expect(2)
            dut.io.commit.head(0).valid.expect(true)
            dut.io.commit.head(0).bits.record.nextPc.expect(0x1100)
            dut.io.commit.head(1).valid.expect(true)
            dut.io.commit.head(1).bits.record.nextPc.expect(0x2200)

            dut.io.commit.pop.poke(1)
            allocation(dut, 1, 0x3300)
            dut.io.allocateIdx(1).expect(2)
            dut.clock.step()
            dut.io.commit.pop.poke(0)
            dut.io.allocate(1).valid.poke(false)
            dut.io.used.expect(2)
            dut.io.commit.head(0).bits.record.nextPc.expect(0x2200)
            dut.io.commit.head(1).bits.record.nextPc.expect(0x3300)

            dut.io.commit.flush.poke(true)
            dut.clock.step()
            dut.io.commit.flush.poke(false)
            dut.io.used.expect(0)
            dut.io.commit.head.foreach(_.valid.expect(false))
            dut.io.allocateIdx(0).expect(0)
        }
    }

    "three-port allocations select distinct rows across ring wraparound" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)

            for (lane <- 0 until 3) {
                allocation(dut, lane, 0x4000 + lane * 4)
                dut.io.allocate(lane).bits.record.train.meta.ittage.predictedTargets(3)
                    .poke((0x10000000L + lane).U)
                dut.io.allocateIdx(lane).expect(lane)
            }
            dut.clock.step()
            dut.io.allocate.foreach(_.valid.poke(false))
            for (lane <- 0 until 3) {
                dut.io.commit.head(lane).bits.record.nextPc.expect(0x4000 + lane * 4)
                dut.io.commit.head(lane).bits.record.train.meta.ittage.predictedTargets(3)
                    .expect((0x10000000L + lane).U)
            }

            dut.io.commit.pop.poke("b111".U)
            dut.clock.step()
            dut.io.commit.pop.poke(0.U)
            dut.io.used.expect(0.U)

            for (lane <- 0 until 3) {
                allocation(dut, lane, 0x5000 + lane * 4)
                dut.io.allocate(lane).bits.record.train.meta.ittage.predictedTargets(3)
                    .poke((0x20000000L + lane).U)
            }
            dut.io.allocateIdx(0).expect(3.U)
            dut.io.allocateIdx(1).expect(0.U)
            dut.io.allocateIdx(2).expect(1.U)
            dut.clock.step()
            dut.io.allocate.foreach(_.valid.poke(false))
            for (lane <- 0 until 3) {
                dut.io.commit.head(lane).bits.record.nextPc.expect(0x5000 + lane * 4)
                dut.io.commit.head(lane).bits.record.train.meta.ittage.predictedTargets(3)
                    .expect((0x20000000L + lane).U)
            }
            dut.io.commit.readIdx(0).poke(0.U)
            dut.io.commit.readIdx(1).poke(3.U)
            dut.io.commit.readIdx(2).poke(1.U)
            dut.io.commit.read(0).record.nextPc.expect(0x5004.U)
            dut.io.commit.read(1).record.nextPc.expect(0x5000.U)
            dut.io.commit.read(2).record.nextPc.expect(0x5008.U)
        }
    }

    "flush invalidates a coincident allocation regardless of payload writes" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            allocation(dut, 0, 0x6000)
            dut.io.commit.flush.poke(true)
            dut.clock.step()

            dut.io.allocate(0).valid.poke(false)
            dut.io.commit.flush.poke(false)
            dut.io.used.expect(0.U)
            dut.io.commit.head(0).valid.expect(false)

            allocation(dut, 0, 0x7000)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.used.expect(1.U)
            dut.io.commit.head(0).valid.expect(true)
            dut.io.commit.head(0).bits.record.nextPc.expect(0x7000.U)
        }
    }
}
