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
        dut.io.enqPayloadWrite.foreach(_.poke(false))
        dut.io.out.foreach(_.ready.poke(false))
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def enqueue(
        dut: FetchQueue,
        mask: Int,
        words: Seq[Int],
        nextPc: Int,
        startPc: Int = 0,
        kinds: Seq[Int] = Nil,
        taken: Int = 0,
    ): Unit = {
        dut.io.enq.zip(words.padTo(p.fetchWidth, 0)).zipWithIndex.foreach { case ((entry, word), slot) =>
            entry.bits.poke(0.U.asTypeOf(new FetchQueueEntry(p)))
            entry.valid.poke((mask & (1 << slot)) != 0)
            dut.io.enqPayloadWrite(slot).poke((mask & (1 << slot)) != 0)
            entry.bits.slot.poke(slot)
            entry.bits.packetStart.poke((mask & ((1 << slot) - 1)) == 0)
            entry.bits.packetEnd.poke((mask >> (slot + 1)) == 0)
            entry.bits.instruction.inst.poke(word)
            entry.bits.instruction.kind.poke(kinds.lift(slot).getOrElse(FrontendCfi.None))
            entry.bits.instruction.predictedTaken.poke((taken & (1 << slot)) != 0)
            entry.bits.instruction.predictedValue.poke(nextPc)
            entry.bits.instruction.pc.poke((startPc & ~0xf) | (slot << 2))
            entry.bits.record.train.pcWord.poke(startPc >>> 2)
            entry.bits.record.nextPc.poke(nextPc)
        }
        while (!dut.io.enq(0).ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.enq.foreach(_.valid.poke(false))
        dut.io.enqPayloadWrite.foreach(_.poke(false))
    }

    "four-in three-out compaction crosses packet boundaries without losing metadata" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 0xf, Seq(100, 101, 102, 103), 0x1100, 0x1000)
            enqueue(dut, 0xb, Seq(200, 201, 0, 203), 0x2200, 0x2000)
            dut.io.out.foreach(_.ready.poke(true))

            dut.io.out.foreach(_.valid.expect(true))
            dut.io.out.zipWithIndex.foreach { case (entry, lane) =>
                entry.bits.slot.expect(lane)
                entry.bits.instruction.inst.expect(100 + lane)
                entry.bits.instruction.pc.expect(0x1000 + lane * 4)
                entry.bits.packetStart.expect(lane == 0)
                entry.bits.packetEnd.expect(false)
            }
            dut.io.out(0).bits.record.nextPc.expect(0x1100)
            dut.clock.step()

            val second = Seq((3, 103), (0, 200), (1, 201))
            dut.io.out.zip(second).zipWithIndex.foreach { case ((entry, (slot, word)), lane) =>
                entry.valid.expect(true)
                entry.bits.slot.expect(slot)
                entry.bits.instruction.inst.expect(word)
                entry.bits.instruction.pc.expect((if (lane == 0) 0x1000 else 0x2000) + slot * 4)
                entry.bits.packetStart.expect(lane == 1)
                entry.bits.packetEnd.expect(lane == 0)
            }
            dut.io.out(1).bits.record.nextPc.expect(0x2200)
            dut.clock.step()

            dut.io.out(0).valid.expect(true)
            dut.io.out(0).bits.slot.expect(3)
            dut.io.out(0).bits.instruction.inst.expect(203)
            dut.io.out(0).bits.instruction.pc.expect(0x200c)
            dut.io.out(0).bits.packetStart.expect(false)
            dut.io.out(0).bits.packetEnd.expect(true)
            dut.io.out(1).valid.expect(false)
            dut.io.out(2).valid.expect(false)
            dut.clock.step()
            dut.io.out.foreach(_.valid.expect(false))
        }
    }

    "three consumed packet starts retain the newest context for a continuation" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 1, Seq(101), 0x1100, 0x1000)
            enqueue(dut, 1, Seq(201), 0x2200, 0x2000)
            enqueue(dut, 3, Seq(301, 302), 0x3300, 0x3000)
            dut.io.out.foreach(_.ready.poke(true))

            dut.io.out.foreach(_.valid.expect(true))
            dut.io.out(2).bits.instruction.pc.expect(0x3000)
            dut.clock.step()
            dut.io.out(0).valid.expect(true)
            dut.io.out(0).bits.packetStart.expect(false)
            dut.io.out(0).bits.instruction.inst.expect(302)
            dut.io.out(0).bits.instruction.pc.expect(0x3004)
        }
    }

    "flush discards both instructions and packet metadata" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 0xf, Seq(1, 2, 3, 4), 0x3300)
            dut.io.flush.poke(true)
            dut.io.enq.zipWithIndex.foreach { case (entry, slot) =>
                entry.valid.poke(true)
                dut.io.enqPayloadWrite(slot).poke(true)
                entry.bits.poke(0.U.asTypeOf(new FetchQueueEntry(p)))
                entry.bits.instruction.inst.poke(100 + slot)
            }
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.enq.foreach(_.valid.poke(false))
            dut.io.enqPayloadWrite.foreach(_.poke(false))
            dut.io.out.foreach(_.valid.expect(false))
        }
    }

    "invalid prewrites into free tail slots do not change visible entries" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 1, Seq(111), 0x1100)

            dut.io.enq(0).bits.poke(0.U.asTypeOf(new FetchQueueEntry(p)))
            dut.io.enq(0).bits.instruction.inst.poke(999)
            dut.io.enqPayloadWrite(0).poke(true)
            dut.io.enq(0).valid.expect(false)
            dut.clock.step()
            dut.io.enqPayloadWrite(0).poke(false)

            enqueue(dut, 1, Seq(222), 0x2200)
            dut.io.out.foreach(_.ready.poke(true))
            dut.io.out(0).valid.expect(true)
            dut.io.out(0).bits.instruction.inst.expect(111)
            dut.io.out(0).bits.record.nextPc.expect(0x1100)
            dut.io.out(1).valid.expect(true)
            dut.io.out(1).bits.instruction.inst.expect(222)
            dut.io.out(1).bits.record.nextPc.expect(0x2200)
        }
    }

    "output holds its payload while Rename stalls" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 0xf, Seq(10, 11, 12, 13), 0x1100)
            for (_ <- 0 until 4) {
                dut.io.out.foreach(_.ready.poke(false))
                dut.io.out.zipWithIndex.foreach { case (entry, lane) =>
                    entry.valid.expect(true)
                    entry.bits.instruction.inst.expect(10 + lane)
                }
                dut.clock.step()
            }
            dut.io.out.foreach(_.ready.poke(true))
            dut.clock.step()
            dut.io.out(0).bits.instruction.inst.expect(13)
            dut.io.out(1).valid.expect(false)
        }
    }

    "operand indices are reconstructed from the stored instruction" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            val instruction = BigInt("a74280d3", 16)
            dut.io.enq(0).valid.poke(true)
            dut.io.enqPayloadWrite(0).poke(true)
            dut.io.enq(0).bits.instruction.inst.poke(instruction.U)
            dut.io.enq(0).bits.instruction.rinfo.src(0).valid.poke(true)
            dut.io.enq(0).bits.instruction.rinfo.src(1).isFp.poke(true)
            dut.io.enq(0).bits.instruction.rinfo.src(2).valid.poke(true)
            dut.io.enq(0).bits.instruction.rinfo.dest.valid.poke(true)
            dut.io.enq(0).bits.packetStart.poke(true)
            dut.io.enq(0).bits.packetEnd.poke(true)
            dut.clock.step()
            dut.io.enq(0).valid.poke(false)
            dut.io.enqPayloadWrite(0).poke(false)
            dut.io.out(0).valid.expect(true)
            dut.io.out(0).bits.instruction.rinfo.src(0).index.expect(((instruction >> 15) & 31).U)
            dut.io.out(0).bits.instruction.rinfo.src(1).index.expect(((instruction >> 20) & 31).U)
            dut.io.out(0).bits.instruction.rinfo.src(2).index.expect(((instruction >> 27) & 31).U)
            dut.io.out(0).bits.instruction.rinfo.dest.index.expect(((instruction >> 7) & 31).U)
            dut.io.out(0).bits.instruction.rinfo.src(0).valid.expect(true)
            dut.io.out(0).bits.instruction.rinfo.src(1).isFp.expect(true)
            dut.io.out(0).bits.instruction.rinfo.src(2).valid.expect(true)
            dut.io.out(0).bits.instruction.rinfo.dest.valid.expect(true)
        }
    }

    "packet target reconstructs an indirect tail beside the next packet's direct branch" in {
        simulate(new FetchQueue(p, 3)) { dut =>
            initialize(dut)
            enqueue(dut, 0xf, Seq(0x13, 0x13, 0x13, 0x8067), 0x34567800, 0x1000,
                Seq(0, 0, 0, FrontendCfi.Return), 0x8)
            enqueue(dut, 0x1, Seq(0x208463), 0x2008, 0x2000,
                Seq(FrontendCfi.Conditional), 0x1)
            dut.io.out.foreach(_.ready.poke(true))
            dut.clock.step()
            dut.io.out(0).valid.expect(true)
            dut.io.out(0).bits.instruction.pc.expect(0x100c)
            dut.io.out(0).bits.instruction.predictedValue.expect(0x34567800L)
            dut.io.out(1).valid.expect(true)
            dut.io.out(1).bits.instruction.pc.expect(0x2000)
            dut.io.out(1).bits.instruction.predictedValue.expect(8)
        }
    }
}
