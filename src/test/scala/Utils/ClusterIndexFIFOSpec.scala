import chisel3._
import chisel3.simulator.Exceptions.AssertionFailed
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

class FIFOTestFields extends Bundle {
    val metadata = UInt(8.W)
    val complete = Bool()
    val result = UInt(8.W)
    def enqueue(data: Data): Unit = {
        val incoming = data.asInstanceOf[FIFOTestFields]
        metadata := incoming.metadata
        complete := false.B
    }
    def write(data: Data): Unit = {
        val incoming = data.asInstanceOf[FIFOTestFields]
        complete := incoming.complete
        result := incoming.result
    }
}

class ClusterIndexFIFOSpec extends AnyFreeSpec with ChiselSim {
    private def init(d: ClusterIndexFIFO[UInt]): Unit = {
        d.io.flush.poke(false)
        d.io.enq.foreach { p => p.valid.poke(false); p.bits.poke(0) }
        d.io.deq.foreach(_.ready.poke(false))
        d.io.ridx.foreach { p => p.qidx.poke(0); p.offset.poke(0); p.high.poke(0) }
        d.io.widx.foreach { p => p.qidx.poke(0); p.offset.poke(0); p.high.poke(0) }
        d.io.wen.foreach(_.poke(false))
        d.io.wdata.foreach(_.poke(0))
        d.reset.poke(true)
        d.clock.step()
        d.reset.poke(false)
    }

    private def index(p: ClusterEntry, position: Int, banks: Int, high: Int = 0): Unit = {
        p.qidx.poke(BigInt(1) << (position % banks))
        p.offset.poke(BigInt(1) << (position / banks))
        p.high.poke(high)
    }
    private def checkIndex(p: ClusterEntry, sequence: Int, num: Int, banks: Int): Unit = {
        p.qidx.expect(BigInt(1) << (sequence % banks))
        p.offset.expect(BigInt(1) << ((sequence % num) / banks))
        p.high.expect((sequence / num) % 2)
    }

    private val configs = Seq((8, 4, 2), (30, 2, 2), (12, 2, 1), (12, 3, 2), (16, 3, 4), (1, 1, 1), (2, 2, 2))
    for ((num, ew, dw) <- configs; compact <- Seq(false, true)) {
        s"$num entries $ew enqueue $dw dequeue compact=$compact preserve data, indices, indexed writes and flush" in {
            simulate(new ClusterIndexFIFO(
                UInt(16.W),
                num,
                ew,
                dw,
                2,
                3,
                compactEnq = compact,
                exposeDeqIndex = true,
            )) { d =>
                init(d)
                val banks = math.max(ew, dw)
                val rows = num / banks
                val random = new Random(num * 100 + ew * 10 + dw + (if (compact) 1234 else 0))
                val stored = Array.fill(num)(0)
                val live = mutable.Queue.empty[Int]
                var head = 0
                var tail = 0
                val acceptedMasks = mutable.Set.empty[Int]
                for (cycle <- 0 until 768) {
                    val requested = random.nextInt(ew + 1)
                    val mask = if (compact) random.nextInt(1 << ew) else (1 << requested) - 1
                    val pops = random.nextInt(dw + 1)
                    val flush = cycle % 89 == 88
                    d.io.flush.poke(flush)
                    d.io.enq.zipWithIndex.foreach { case (p, i) =>
                        p.valid.poke((mask & (1 << i)) != 0)
                        p.bits.poke(cycle * ew + i + 1)
                    }
                    d.io.deq.zipWithIndex.foreach { case (p, i) => p.ready.poke(i < pops) }
                    val readAddresses = Seq.fill(2)(random.nextInt(num))
                    d.io.ridx.zip(readAddresses).foreach { case (p, addr) => index(p, addr, banks, random.nextInt(2)) }
                    val writes = random.shuffle((0 until num).toList).take(random.nextInt(math.min(num, 3) + 1))
                    for (w <- 0 until 3) {
                        d.io.wen(w).poke(w < writes.size)
                        index(d.io.widx(w), writes.lift(w).getOrElse(0), banks, random.nextInt(2))
                        d.io.wdata(w).poke(40000 + cycle * 3 + w)
                    }
                    val bankUsed = (0 until banks).map(bank => live.count(_ % banks == bank))
                    val ready = bankUsed.forall(_ < rows)
                    d.io.enq.foreach(_.ready.expect(ready))
                    for (i <- 0 until dw) {
                        d.io.deq(i).valid.expect(i < live.size)
                        if (i < live.size) {
                            d.io.deq(i).bits.expect(stored(live(i)))
                            checkIndex(d.io.deqIdx.get(i), head + i, num, banks)
                        }
                    }
                    d.io.rdata.zip(readAddresses).foreach { case (p, addr) => p.expect(stored(addr)) }
                    val accepted = (0 until ew).filter(i => ready && (mask & (1 << i)) != 0)
                    accepted.zipWithIndex.foreach { case (lane, rank) =>
                        checkIndex(d.io.enqIdx(lane), tail + rank, num, banks)
                    }
                    if (flush) {
                        live.clear()
                        head = 0
                        tail = 0
                    } else {
                        val removed = math.min(pops, live.size)
                        for (_ <- 0 until removed) live.dequeue()
                        head += removed
                        accepted.foreach { lane =>
                            stored(tail % num) = cycle * ew + lane + 1
                            live.enqueue(tail % num)
                            tail += 1
                        }
                        writes.zipWithIndex.foreach { case (addr, w) => stored(addr) = 40000 + cycle * 3 + w }
                        if (ready) acceptedMasks += mask
                    }
                    d.clock.step()
                    // Random reads remain pre-write until the edge and see storage afterward.
                    d.io.rdata.zip(readAddresses).foreach { case (p, addr) => p.expect(stored(addr)) }
                }
                if (compact) assert(acceptedMasks.size == (1 << ew), s"missing accepted sparse masks: $acceptedMasks")
                info(
                    "768 cycles checked against software storage and queue, including allocation tags and simultaneous updates"
                )
            }
        }
    }

    for ((num, ew, dw) <- Seq((30, 2, 2), (12, 3, 2), (12, 2, 3), (1, 1, 1))) {
        s"FreeList $num $ew $dw restores the recycle ring including returns on flush" in {
            val banks = math.max(ew, dw)
            val rows = num / banks
            simulate(new ClusterIndexFIFO(
                UInt(16.W),
                num,
                ew,
                dw,
                0,
                0,
                isFlst = true,
                rstVal = Some((0 until num).map(i => (100 + i).U(16.W))),
                compactEnq = true
            )) { d =>
                init(d)
                val random = new Random(num * 31 + ew * 7 + dw)
                val stored = Array.tabulate(num)(pos => 100 + (pos % banks) * rows + pos / banks)
                var head = 0
                var tail = 0
                var count = num
                var simultaneousRecovery = 0
                for (cycle <- 0 until 512) {
                    val flush = cycle % 13 == 12
                    val returnCount = random.nextInt(math.min(ew, num - count) + 1)
                    val lanes = random.shuffle((0 until ew).toList).take(returnCount).sorted
                    val requested = random.nextInt(dw + 1)
                    d.io.flush.poke(flush)
                    d.io.enq.zipWithIndex.foreach { case (p, lane) =>
                        p.valid.poke(lanes.contains(lane))
                        p.bits.poke(1000 + cycle * ew + lane)
                    }
                    d.io.deq.zipWithIndex.foreach { case (p, lane) => p.ready.poke(lane < requested) }
                    val allBanksNonempty = (0 until banks).forall(bank =>
                        (0 until count).exists(i => (head + i) % num % banks == bank)
                    )
                    d.io.enq.foreach(_.ready.expect(true))
                    d.io.deq.zipWithIndex.foreach { case (p, lane) =>
                        p.valid.expect(allBanksNonempty)
                        if (allBanksNonempty) p.bits.expect(stored((head + lane) % num))
                    }
                    val allocated = if (allBanksNonempty) requested else 0
                    for (lane <- lanes) {
                        stored(tail) = 1000 + cycle * ew + lane
                        tail = (tail + 1) % num
                    }
                    if (flush) {
                        head = tail
                        count = num
                        if (returnCount > 0) simultaneousRecovery += 1
                    } else {
                        head = (head + allocated) % num
                        count += returnCount - allocated
                    }
                    d.clock.step()
                }
                assert(simultaneousRecovery > 0)
                info(s"512 cycles checked, including $simultaneousRecovery flush edges accepting returns")
            }
        }
    }

    "field updates preserve enqueue metadata and exclusive completion writes" in {
        simulate(new ClusterIndexFIFO(new FIFOTestFields, 4, 2, 2, 1, 2, compactEnq = true)) { d =>
            d.io.flush.poke(false)
            d.io.enq.foreach { p =>
                p.valid.poke(false); p.bits.metadata.poke(0); p.bits.complete.poke(false); p.bits.result.poke(0)
            }
            d.io.deq.foreach(_.ready.poke(false))
            d.io.wen.foreach(_.poke(false))
            d.io.widx.foreach(p => index(p, 0, 2))
            d.io.wdata.foreach { p => p.metadata.poke(0); p.complete.poke(false); p.result.poke(0) }
            index(d.io.ridx(0), 0, 2)
            d.reset.poke(true); d.clock.step(); d.reset.poke(false)
            d.io.enq(1).valid.poke(true)
            d.io.enq(1).bits.metadata.poke(42)
            d.io.enq(1).bits.result.poke(77)
            d.io.wen.foreach(_.poke(true))
            index(d.io.widx(0), 0, 2)
            index(d.io.widx(1), 2, 2) // Different rows of the same bank, both write ports active.
            d.io.wdata(0).metadata.poke(99)
            d.io.wdata(0).complete.poke(true)
            d.io.wdata(0).result.poke(123)
            d.io.wdata(1).complete.poke(true)
            d.io.wdata(1).result.poke(234)
            d.io.rdata(0).result.expect(0)
            d.clock.step()
            d.io.enq.foreach(_.valid.poke(false)); d.io.wen.foreach(_.poke(false))
            d.io.deq(0).valid.expect(true)
            d.io.deq(0).bits.metadata.expect(42)
            d.io.deq(0).bits.complete.expect(true)
            d.io.deq(0).bits.result.expect(123)
            index(d.io.ridx(0), 2, 2)
            d.io.rdata(0).metadata.expect(0)
            d.io.rdata(0).result.expect(234)
        }
    }

    "payload writes can be isolated from an occupancy flush" in {
        simulate(new ClusterIndexFIFO(
            UInt(8.W),
            4,
            2,
            2,
            2,
            1,
            writePayloadOnFlush = true,
        )) { d =>
            init(d)
            d.io.flush.poke(true)
            d.io.enq(0).valid.poke(true)
            d.io.enq(0).bits.poke(55)
            d.io.wen(0).poke(true)
            index(d.io.widx(0), 2, 2)
            d.io.wdata(0).poke(77)
            d.clock.step()
            d.io.flush.poke(false)
            d.io.enq(0).valid.poke(false)
            d.io.wen(0).poke(false)
            d.io.deq(0).valid.expect(false)
            index(d.io.ridx(0), 0, 2)
            index(d.io.ridx(1), 2, 2)
            d.io.rdata(0).expect(55)
            d.io.rdata(1).expect(77)
        }
    }

    for (violation <- Seq("enqueue", "dequeue", "duplicate writes", "write bank", "write offset")) {
        s"contract assertions reject $violation" in {
            val error = intercept[AssertionFailed] {
                simulate(new ClusterIndexFIFO(UInt(8.W), 6, 2, 2, 0, 2)) { d =>
                    init(d)
                    violation match {
                        case "enqueue" => d.io.enq(1).valid.poke(true)
                        case "dequeue" =>
                            d.io.enq.foreach(_.valid.poke(true)); d.clock.step()
                            d.io.enq.foreach(_.valid.poke(false)); d.io.deq(1).ready.poke(true)
                        case "duplicate writes" =>
                            d.io.wen.foreach(_.poke(true)); d.io.widx.foreach(p => index(p, 1, 2))
                        case "write bank" =>
                            d.io.wen(0).poke(true); index(d.io.widx(0), 0, 2); d.io.widx(0).qidx.poke(3)
                        case "write offset" =>
                            d.io.wen(0).poke(true); index(d.io.widx(0), 0, 2); d.io.widx(0).offset.poke(3)
                    }
                    d.clock.step()
                }
            }
            val expected = violation match {
                case "enqueue" => "enqueue must be a prefix"
                case "dequeue" => "dequeue transfers must be a prefix"
                case "duplicate writes" => "simultaneous random writes"
                case "write bank" => "write bank must be one-hot"
                case "write offset" => "write offset must be one-hot"
            }
            assert(error.getMessage.contains(expected))
        }
    }
}
