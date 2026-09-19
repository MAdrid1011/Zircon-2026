import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FetchQueueSpec extends AnyFreeSpec with ChiselSim {
    private val p = FrontendParams(fqDepth = 8)

    private def initialize(dut: FetchQueue): Unit = {
        dut.io.enq.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FetchQueueEntry(p)))
        }
        dut.io.out.foreach(_.ready.poke(false))
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def enqueue(dut: FetchQueue, token: Int, mask: Int, words: Seq[Int], nextPc: Int): Unit = {
        dut.io.enq.zip(words.padTo(p.fetchWidth, 0)).zipWithIndex.foreach { case ((entry, word), slot) =>
            entry.bits.poke(0.U.asTypeOf(new FetchQueueEntry(p)))
            entry.valid.poke((mask & (1 << slot)) != 0)
            entry.bits.fetchToken.poke(token)
            entry.bits.slot.poke(slot)
            entry.bits.packetStart.poke((mask & ((1 << slot) - 1)) == 0)
            entry.bits.packetEnd.poke((mask >> (slot + 1)) == 0)
            entry.bits.instruction.inst.poke(word)
            entry.bits.record.nextPc.poke(nextPc)
        }
        while (!dut.io.enq(0).ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.enq.foreach(_.valid.poke(false))
    }

    "four-in three-out compaction crosses packet boundaries without losing metadata" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 10, 0xf, Seq(100, 101, 102, 103), 0x1100)
            enqueue(dut, 11, 0xb, Seq(200, 201, 0, 203), 0x2200)
            dut.io.out.foreach(_.ready.poke(true))

            dut.io.out.foreach(_.valid.expect(true))
            dut.io.out.zipWithIndex.foreach { case (entry, lane) =>
                entry.bits.fetchToken.expect(10)
                entry.bits.slot.expect(lane)
                entry.bits.instruction.inst.expect(100 + lane)
                entry.bits.packetStart.expect(lane == 0)
                entry.bits.packetEnd.expect(false)
            }
            dut.io.out(0).bits.record.nextPc.expect(0x1100)
            dut.clock.step()

            val second = Seq((10, 3, 103), (11, 0, 200), (11, 1, 201))
            dut.io.out.zip(second).zipWithIndex.foreach { case ((entry, (token, slot, word)), lane) =>
                entry.valid.expect(true)
                entry.bits.fetchToken.expect(token)
                entry.bits.slot.expect(slot)
                entry.bits.instruction.inst.expect(word)
                entry.bits.packetStart.expect(lane == 1)
                entry.bits.packetEnd.expect(lane == 0)
            }
            dut.io.out(1).bits.record.nextPc.expect(0x2200)
            dut.clock.step()

            dut.io.out(0).valid.expect(true)
            dut.io.out(0).bits.fetchToken.expect(11)
            dut.io.out(0).bits.slot.expect(3)
            dut.io.out(0).bits.instruction.inst.expect(203)
            dut.io.out(0).bits.packetStart.expect(false)
            dut.io.out(0).bits.packetEnd.expect(true)
            dut.io.out(1).valid.expect(false)
            dut.io.out(2).valid.expect(false)
            dut.clock.step()
            dut.io.out.foreach(_.valid.expect(false))
        }
    }

    "flush discards both instructions and packet metadata" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 12, 0xf, Seq(1, 2, 3, 4), 0x3300)
            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.out.foreach(_.valid.expect(false))
        }
    }
}
