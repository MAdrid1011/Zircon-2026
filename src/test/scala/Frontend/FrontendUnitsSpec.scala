import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FrontendDecodeTestTop extends Module {
    val io = IO(new Bundle {
        val inst = Input(UInt(32.W))
        val fields = Output(new FrontendPredecodeFields)
        val kind = Output(UInt(3.W))
        val rinfo = Output(new FrontendRegisterInfo)
    })
    val fields = Module(new PredecodeFields)
    val decoder = Module(new RegisterInfoDecoder)
    fields.io.inst := io.inst
    decoder.io.inst := io.inst
    decoder.io.fields := fields.io.fields
    io.fields := fields.io.fields
    io.kind := decoder.io.kind
    io.rinfo := decoder.io.rinfo
}

class FrontendUnitsSpec extends AnyFreeSpec with ChiselSim {
    "predecode preserves mixed integer/FP operands, immediate signs, and RAS hints" in {
        simulate(new FrontendDecodeTestTop) { d =>
            def check(inst: Long, src: Seq[(Int, Boolean)], dest: Option[(Int, Boolean)], kind: Int = 0): Unit = {
                d.io.inst.poke(BigInt(inst & 0xffffffffL))
                d.io.kind.expect(kind)
                d.io.rinfo.src.zipWithIndex.foreach { case (s, i) =>
                    s.valid.expect(i < src.size && (src(i)._2 || src(i)._1 != 0))
                    if (i < src.size) { s.index.expect(src(i)._1); s.isFp.expect(src(i)._2) }
                }
                d.io.rinfo.dest.valid.expect(dest.exists(x => x._2 || x._1 != 0))
                dest.foreach { case (index, fp) =>
                    d.io.rinfo.dest.index.expect(index); d.io.rinfo.dest.isFp.expect(fp)
                }
            }
            def r(op: Int, f7: Int, f3: Int, rd: Int, rs1: Int, rs2: Int): Long =
                (f7.toLong << 25) | (rs2 << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | op
            check(r(0x33, 1, 0, 3, 1, 2), Seq(1 -> false, 2 -> false), Some(3 -> false))
            check(r(0x13, 0, 0, 0, 0, 0), Seq(0 -> false), Some(0 -> false))
            check(r(0x07, 0, 2, 0, 2, 0), Seq(2 -> false), Some(0 -> true))
            check(r(0x27, 0, 2, 0, 2, 0), Seq(2 -> false, 0 -> true), None)
            for (f7 <- Seq(0, 4, 8, 12, 16, 20))
                check(r(0x53, f7, 0, 0, 1, 2), Seq(1 -> true, 2 -> true), Some(0 -> true))
            check(r(0x53, 44, 0, 4, 0, 0), Seq(0 -> true), Some(4 -> true))
            check(r(0x53, 80, 2, 4, 0, 2), Seq(0 -> true, 2 -> true), Some(4 -> false))
            for (signed <- 0 to 1) {
                check(r(0x53, 96, 0, 4, 0, signed), Seq(0 -> true), Some(4 -> false))
                check(r(0x53, 104, 0, 0, 4, signed), Seq(4 -> false), Some(0 -> true))
            }
            check(r(0x53, 112, 1, 4, 0, 0), Seq(0 -> true), Some(4 -> false))
            check(r(0x53, 120, 0, 0, 4, 0), Seq(4 -> false), Some(0 -> true))
            for (op <- Seq(0x43, 0x47, 0x4b, 0x4f))
                check(r(op, 6 << 2, 0, 0, 1, 2), Seq(1 -> true, 2 -> true, 6 -> true), Some(0 -> true))
            check(r(0x73, 0, 5, 4, 7, 0), Seq.empty, Some(4 -> false))
            check(r(0x73, 0, 1, 4, 7, 0), Seq(7 -> false), Some(4 -> false))
            check(r(0x2f, 2 << 2, 2, 4, 7, 0), Seq(7 -> false), Some(4 -> false))
            for (rd <- Seq(0, 1, 5, 7); rs1 <- Seq(0, 1, 5, 7)) {
                val push = rd == 1 || rd == 5
                val pop = (rs1 == 1 || rs1 == 5) && (!push || rd != rs1)
                val kind = if (pop) { if (push) 7 else 6 }
                else { if (push) 5 else 4 }
                check(r(0x67, 0, 0, rd, rs1, 0), Seq(rs1 -> false), Some(rd -> false), kind)
            }
            check(0xffdff0efL, Seq.empty, Some(1 -> false), 3)
            d.io.fields.immediate.expect(BigInt("fffffffc", 16))
            check(0xfe208ee3L, Seq(1 -> false, 2 -> false), None, 1)
            d.io.fields.immediate.expect(BigInt("fffffffc", 16))
            check(0xffc08067L, Seq(1 -> false), Some(0 -> false), 6)
            d.io.fields.immediate.expect(BigInt("fffffffc", 16))
        }
    }

    "history and complete RAS restore once, including a same-cycle retirement" in {
        val p = FrontendParams(historyLengths = Seq(2, 4), rasDepth = 2)
        simulate(new SpeculativeState(p)) { d =>
            def event(e: FrontendStateEvent, pc: Long, kind: Int): Unit = {
                e.pc.poke(pc)
                e.prediction.kinds.zipWithIndex.foreach { case (x, i) => x.poke(if (i == 0) kind else 0) }
                e.prediction.targets.foreach(_.poke(0x80000100L))
                e.prediction.taken.poke(if (kind == 0) 0 else 1)
                e.prediction.mask.poke(if (kind == 0) 15 else 1)
                e.prediction.backward.poke(if (kind == 1) 1 else 0)
                e.prediction.nextPc.poke(if (kind == 0) pc + 16 else 0x80000100L)
            }
            d.io.early.valid.poke(false); d.io.repair.valid.poke(false); d.io.retire.valid.poke(false)
            d.io.flush.poke(false)
            event(d.io.early.bits, 0x80000000L, 3)
            event(d.io.retire.bits, 0x80000000L, 3)
            event(d.io.repair.bits.event, 0x80000000L, 0)
            d.reset.poke(true); d.clock.step(); d.reset.poke(false)
            d.io.early.valid.poke(true); d.io.retire.valid.poke(true); d.clock.step()
            d.io.retire.valid.poke(false)
            val history = d.io.snapshot.history.peek().litValue
            val folds = d.io.folds.map(_.peek().litValue)
            val ras = d.io.snapshot.ras.map(_.peek().litValue)
            val top = d.io.snapshot.top.peek().litValue
            d.io.snapshot.count.expect(1); d.io.snapshot.pointer.expect(1)
            d.io.early.valid.poke(false); d.clock.step(30)
            d.io.snapshot.history.expect(history); d.io.snapshot.ras(0).expect(0x20000001L)
            // Overflow deliberately overwrites the first committed return address.
            for (pc <- Seq(0x80000010L, 0x80000020L, 0x80000030L)) {
                event(d.io.early.bits, pc, 3); d.io.early.valid.poke(true); d.clock.step()
            }
            d.io.snapshot.count.expect(2)
            d.io.repair.bits.before.history.poke(history)
            d.io.repair.bits.before.folds.zip(folds).foreach { case (f, value) => f.poke(value) }
            d.io.repair.bits.before.loop.poke(0)
            d.io.repair.bits.before.top.poke(top)
            d.io.repair.bits.before.ras.zip(ras).foreach { case (s, value) => s.poke(value) }
            d.io.repair.bits.before.pointer.poke(1); d.io.repair.bits.before.count.poke(1)
            event(d.io.repair.bits.event, 0x80000010L, 6)
            d.io.repair.valid.poke(true); d.clock.step()
            d.io.early.valid.poke(false); d.io.repair.valid.poke(false)
            d.io.snapshot.count.expect(0); d.io.snapshot.pointer.expect(0)
            d.io.snapshot.ras(0).expect(ras(0)); d.io.snapshot.ras(1).expect(ras(1))
            val repaired = d.io.snapshot.history.peek().litValue
            d.clock.step(30); d.io.snapshot.history.expect(repaired)
            // Flush must include retirement on this edge and dominate both speculative events.
            event(d.io.retire.bits, 0x80000040L, 3)
            d.io.retire.valid.poke(true); d.io.flush.poke(true)
            d.io.early.valid.poke(true); d.io.repair.valid.poke(true); d.clock.step()
            d.io.snapshot.count.expect(2); d.io.snapshot.pointer.expect(0)
            d.io.snapshot.ras(0).expect(0x20000001L); d.io.snapshot.ras(1).expect(0x20000011L)
            d.io.retire.valid.poke(false); d.io.early.valid.poke(false); d.io.repair.valid.poke(false)
            d.clock.step(3)
            d.io.snapshot.count.expect(2); d.io.snapshot.ras(1).expect(0x20000011L)
            d.io.flush.poke(false)
            // Pop+push replaces only the top; empty pops do not underflow.
            event(d.io.early.bits, 0x80000050L, 7)
            d.io.early.valid.poke(true); d.clock.step()
            d.io.snapshot.count.expect(2); d.io.snapshot.ras(1).expect(0x20000015L)
            event(d.io.early.bits, 0x80000060L, 6); d.clock.step(4)
            d.io.snapshot.count.expect(0)
            event(d.io.early.bits, 0x80000060L, 1); d.clock.step(300)
            d.io.snapshot.loop.expect(255)
            d.io.early.valid.poke(false); d.clock.step(30)
            d.io.snapshot.loop.expect(255)
            d.io.flush.poke(true); d.clock.step()
            d.io.snapshot.loop.expect(0)
        }
    }
    "incremental folds match the complete history through stalls and recovery" in {
        val p = FrontendParams(rasDepth = 2)
        simulate(new SpeculativeState(p)) { d =>
            val rng = new scala.util.Random(20260910)
            d.io.early.valid.poke(false); d.io.repair.valid.poke(false); d.io.retire.valid.poke(false)
            d.io.flush.poke(false)
            d.reset.poke(true); d.clock.step(); d.reset.poke(false)
            for (cycle <- 0 until 1500) {
                for (event <- Seq(d.io.early.bits, d.io.retire.bits)) {
                    event.pc.poke(BigInt(rng.nextInt().toLong & 0xfffffffcL))
                    event.prediction.mask.poke(15)
                    event.prediction.backward.poke(rng.nextInt(16))
                    event.prediction.taken.poke(if (rng.nextBoolean()) 8 else 0)
                    event.prediction.nextPc.poke(0x80000000L)
                    event.prediction.kinds.foreach(_.poke(1))
                    event.prediction.targets.foreach(_.poke(0x80000000L))
                }
                d.io.early.valid.poke(rng.nextInt(4) != 0)
                d.io.retire.valid.poke(rng.nextInt(4) == 0)
                d.io.flush.poke(cycle % 29 == 0)
                d.clock.step()
                val history = d.io.snapshot.history.peek().litValue
                for (i <- p.historyLengths.indices) {
                    val bits = p.historyLengths(i) * p.historyStep
                    val truncated = history & ((BigInt(1) << bits) - 1)
                    val expected = (0 until bits by p.hashBits).map(shift =>
                        (truncated >> shift) & ((BigInt(1) << p.hashBits) - 1)
                    ).reduce(_ ^ _)
                    d.io.folds(i).expect(expected)
                }
            }
        }
    }

    for (depth <- Seq(2, 4, 16)) {
        s"RAS repair matches pop-then-push semantics at every pointer and occupancy with depth $depth" in {
            val p = FrontendParams(historyLengths = Seq(2, 4), rasDepth = depth)
            simulate(new SpeculativeState(p)) { d =>
                d.io.early.valid.poke(false)
                d.io.retire.valid.poke(false)
                d.io.repair.valid.poke(false)
                d.io.flush.poke(false)
                d.reset.poke(true); d.clock.step(); d.reset.poke(false)
                val before = d.io.repair.bits.before
                val event = d.io.repair.bits.event
                before.history.poke(0)
                before.folds.foreach(_.poke(0))
                before.loop.poke(0)
                val saved = Vector.tabulate(depth)(i => BigInt(0x100 + i))
                before.ras.zip(saved).foreach { case (entry, value) => entry.poke(value) }
                event.prediction.backward.poke(0)
                event.prediction.targets.foreach(_.poke(0x80000000L))
                event.prediction.nextPc.poke(0x80000000L)
                d.io.repair.valid.poke(true)
                for (
                    pointer <- 0 until depth; count <- 0 to depth; kind <- Seq(0, 3, 6, 7);
                    slot <- 0 until p.fetchWidth
                ) {
                    val pc = if ((pointer + count) % 2 == 0) 0xfffffff0L else 0x80000ff0L
                    before.pointer.poke(pointer)
                    before.count.poke(count)
                    before.top.poke(if (count == 0) BigInt(0) else saved((pointer + depth - 1) % depth))
                    event.pc.poke(pc)
                    event.prediction.kinds.zipWithIndex.foreach { case (entry, i) =>
                        entry.poke(if (i == slot) kind else 0)
                    }
                    event.prediction.taken.poke(if (kind == 0) 0 else 1 << slot)
                    event.prediction.mask.poke(if (kind == 0) 15 else (1 << (slot + 1)) - 1)

                    // Execute the architectural operations sequentially, including overwritten data.
                    var expected = saved
                    var nextPointer = pointer
                    var nextCount = count
                    var nextTop = if (count == 0) BigInt(0) else saved((pointer + depth - 1) % depth)
                    if ((kind == 6 || kind == 7) && nextCount != 0) {
                        nextPointer = (nextPointer + depth - 1) % depth
                        nextCount -= 1
                        nextTop = if (nextCount == 0) BigInt(0) else expected((nextPointer + depth - 1) % depth)
                    }
                    if (kind == 3 || kind == 7) {
                        nextTop = BigInt(((pc + 4 * slot + 4) & 0xffffffffL) >>> 2)
                        expected = expected.updated(nextPointer, nextTop)
                        nextPointer = (nextPointer + 1) % depth
                        nextCount = math.min(depth, nextCount + 1)
                    }
                    d.clock.step()
                    d.io.snapshot.pointer.expect(nextPointer)
                    d.io.snapshot.count.expect(nextCount)
                    d.io.snapshot.top.expect(nextTop)
                    d.io.snapshot.ras.zip(expected).foreach { case (entry, value) => entry.expect(value) }
                }
            }
        }
    }

}
