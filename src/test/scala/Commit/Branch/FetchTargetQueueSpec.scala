import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FetchTargetQueueSpec extends AnyFreeSpec with ChiselSim {
    private val p = FrontendParams(ftqDepth = 4)

    private def initialize(dut: FetchTargetQueue, params: FrontendParams = p): Unit = {
        dut.io.allocate.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FrontendFtqAllocation(params)))
        }
        dut.io.commit.flush.poke(false)
        dut.io.commit.pop.poke(0)
        dut.io.commit.branch.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FtqBranchUpdate(params)))
        }
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def allocation(dut: FetchTargetQueue, lane: Int, nextPc: Int): Unit = {
        dut.io.allocate(lane).valid.poke(true)
        dut.io.allocate(lane).bits.writeValid.poke(true)
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
            dut.io.allocate.foreach { port =>
                port.valid.poke(false)
                port.bits.writeValid.poke(false)
            }

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
            dut.io.allocate(1).bits.writeValid.poke(false)
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
                dut.io.allocate(lane).bits.record.train.meta.ittage.predictedTarget
                    .poke((0x10000000L + lane).U)
                dut.io.allocateIdx(lane).expect(lane)
            }
            dut.clock.step()
            dut.io.allocate.foreach { port =>
                port.valid.poke(false)
                port.bits.writeValid.poke(false)
            }
            for (lane <- 0 until 3) {
                dut.io.commit.head(lane).bits.record.nextPc.expect(0x4000 + lane * 4)
                dut.io.commit.head(lane).bits.record.train.meta.ittage.predictedTarget
                    .expect((0x10000000L + lane).U)
            }

            dut.io.commit.pop.poke("b111".U)
            dut.clock.step()
            dut.io.commit.pop.poke(0.U)
            dut.io.used.expect(0.U)

            for (lane <- 0 until 3) {
                allocation(dut, lane, 0x5000 + lane * 4)
                dut.io.allocate(lane).bits.record.train.meta.ittage.predictedTarget
                    .poke((0x20000000L + lane).U)
            }
            dut.io.allocateIdx(0).expect(3.U)
            dut.io.allocateIdx(1).expect(0.U)
            dut.io.allocateIdx(2).expect(1.U)
            dut.clock.step()
            dut.io.allocate.foreach { port =>
                port.valid.poke(false)
                port.bits.writeValid.poke(false)
            }
            for (lane <- 0 until 3) {
                dut.io.commit.head(lane).bits.record.nextPc.expect(0x5000 + lane * 4)
                dut.io.commit.head(lane).bits.record.train.meta.ittage.predictedTarget
                    .expect((0x20000000L + lane).U)
            }
        }
    }

    "banked head reads preserve three consecutive records across a sixteen-entry wrap" in {
        val wide = FrontendParams(ftqDepth = 16)
        simulate(new FetchTargetQueue(wide, 3, 3)) { dut =>
            initialize(dut, wide)
            for (lane <- 0 until 3) allocation(dut, lane, 0x9000 + lane * 4)
            dut.clock.step()
            dut.io.allocate.foreach { port =>
                port.valid.poke(false)
                port.bits.writeValid.poke(false)
            }
            for (first <- 0 until 18) {
                for (lane <- 0 until 3) {
                    dut.io.commit.head(lane).valid.expect(true)
                    dut.io.commit.head(lane).bits.record.nextPc.expect((0x9000 + (first + lane) * 4).U)
                }
                dut.io.commit.pop.poke(1.U)
                allocation(dut, 0, 0x9000 + (first + 3) * 4)
                dut.clock.step()
                dut.io.commit.pop.poke(0.U)
                dut.io.allocate(0).valid.poke(false)
                dut.io.allocate(0).bits.writeValid.poke(false)
            }
        }
    }

    "flush invalidates a coincident allocation regardless of payload writes" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            allocation(dut, 0, 0x6000)
            dut.io.commit.flush.poke(true)
            dut.clock.step()

            dut.io.allocate(0).valid.poke(false)
            dut.io.allocate(0).bits.writeValid.poke(false)
            dut.io.commit.flush.poke(false)
            dut.io.used.expect(0.U)
            dut.io.commit.head(0).valid.expect(false)

            allocation(dut, 0, 0x7000)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.allocate(0).bits.writeValid.poke(false)
            dut.io.used.expect(1.U)
            dut.io.commit.head(0).valid.expect(true)
            dut.io.commit.head(0).bits.record.nextPc.expect(0x7000.U)
            dut.io.commit.head(0).bits.resolved.expect(0.U)
            dut.io.commit.head(0).bits.taken.expect(0.U)
        }
    }

    "payload prewrite remains invisible until allocation advances occupancy" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            dut.io.allocate(0).bits.writeValid.poke(true)
            dut.io.allocate(0).bits.record.nextPc.poke(0x8000)
            dut.clock.step()
            dut.io.used.expect(0.U)
            dut.io.commit.head(0).valid.expect(false)

            dut.io.allocate(0).valid.poke(true)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.allocate(0).bits.writeValid.poke(false)
            dut.io.used.expect(1.U)
            dut.io.commit.head(0).valid.expect(true)
            dut.io.commit.head(0).bits.record.nextPc.expect(0x8000.U)
        }
    }

    "two branch ports update distinct one-hot packet rows in the same cycle" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            allocation(dut, 0, 0x1000)
            allocation(dut, 1, 0x2000)
            dut.clock.step()
            dut.io.allocate.foreach { port =>
                port.valid.poke(false)
                port.bits.writeValid.poke(false)
            }

            dut.io.commit.branch(0).valid.poke(true)
            dut.io.commit.branch(0).bits.ftqIdxOH.poke("b0001".U)
            dut.io.commit.branch(0).bits.slot.poke(1)
            dut.io.commit.branch(0).bits.taken.poke(true)
            dut.io.commit.branch(0).bits.target.poke(0x1234)
            dut.io.commit.branch(1).valid.poke(true)
            dut.io.commit.branch(1).bits.ftqIdxOH.poke("b0010".U)
            dut.io.commit.branch(1).bits.slot.poke(2)
            dut.io.commit.branch(1).bits.taken.poke(false)
            dut.io.commit.branch(1).bits.target.poke(0x5678)
            dut.clock.step()
            dut.io.commit.branch.foreach(_.valid.poke(false))

            dut.io.commit.head(0).bits.resolved.expect("b0010".U)
            dut.io.commit.head(0).bits.taken.expect("b0010".U)
            dut.io.commit.head(0).bits.targets(1).expect(0x1234.U)
            dut.io.commit.head(1).bits.resolved.expect("b0100".U)
            dut.io.commit.head(1).bits.taken.expect(0.U)
            dut.io.commit.head(1).bits.targets(2).expect(0x5678.U)
        }
    }

    "all sixteen slots remain usable across a full wrap" in {
        val wide = FrontendParams(ftqDepth = 16)
        simulate(new FetchTargetQueue(wide, 3, 3)) { dut =>
            initialize(dut, wide)
            for (base <- 0 until 16 by 3) {
                val count = math.min(3, 16 - base)
                for (lane <- 0 until count) {
                    allocation(dut, lane, 0xa000 + (base + lane) * 4)
                    dut.io.allocateIdx(lane).expect((base + lane).U)
                }
                dut.clock.step()
                dut.io.allocate.foreach { port =>
                    port.valid.poke(false)
                    port.bits.writeValid.poke(false)
                }
            }
            dut.io.used.expect(16.U)
            for (base <- 0 until 16 by 3) {
                val count = math.min(3, 16 - base)
                for (lane <- 0 until count) {
                    dut.io.commit.head(lane).valid.expect(true)
                    dut.io.commit.head(lane).bits.record.nextPc.expect((0xa000 + (base + lane) * 4).U)
                }
                dut.io.commit.pop.poke(((1 << count) - 1).U)
                dut.clock.step()
            }
            dut.io.commit.pop.poke(0.U)
            dut.io.used.expect(0.U)
            allocation(dut, 0, 0xb000)
            dut.io.allocateIdx(0).expect(0.U)
            dut.clock.step()
            dut.io.commit.head(0).bits.record.nextPc.expect(0xb000.U)
        }
    }

    "two branch ports merge different slots of the same packet" in {
        simulate(new FetchTargetQueue(p, 3, 3)) { dut =>
            initialize(dut)
            allocation(dut, 0, 0x1000)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.allocate(0).bits.writeValid.poke(false)
            for (port <- 0 until 2) {
                dut.io.commit.branch(port).valid.poke(true)
                dut.io.commit.branch(port).bits.ftqIdxOH.poke(1.U)
                dut.io.commit.branch(port).bits.slot.poke((port + 1).U)
                dut.io.commit.branch(port).bits.taken.poke(port == 0)
                dut.io.commit.branch(port).bits.target.poke((0x1234 + port * 4).U)
            }
            dut.clock.step()
            dut.io.commit.branch.foreach(_.valid.poke(false))
            dut.io.commit.head(0).bits.resolved.expect("b0110".U)
            dut.io.commit.head(0).bits.taken.expect("b0010".U)
            dut.io.commit.head(0).bits.targets(1).expect(0x1234.U)
            dut.io.commit.head(0).bits.targets(2).expect(0x1238.U)
        }
    }
}
