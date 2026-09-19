import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class MiddleendTestInput(p: FrontendParams) extends Bundle {
    val fetchToken = UInt(32.W)
    val ftqIdx = UInt(p.ftqBits.W)
    val mask = UInt(p.fetchWidth.W)
    val pc = UInt(32.W)
    val instructions = Vec(p.fetchWidth, UInt(32.W))
}

class MiddleendTestHarness(
    val fp: FrontendParams = FrontendParams(),
    val bp: BackendParams = BackendParams(),
    val issue: IssueParams = IssueParams(),
) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new MiddleendTestInput(fp)))
        val freeCount = Input(Vec(IssueQueueIndex.Count, UInt(issue.countWidth.W)))
        val resourcePrefix = Input(UInt(issue.dispatchWidth.W))
        val allocation = Input(Vec(issue.dispatchWidth, new BackendAllocation(bp)))
        val memoryWakeup = Input(new BackendWakeup(bp))
        val flush = Input(Bool())
        val restore = Input(Bool())
        val request = Output(new MiddleendCommitRequest(fp, issue.dispatchWidth))
        val enqueue = Output(new MiddleendCommitEnqueue(fp, bp, issue.dispatchWidth))
        val ftqAllocate = Output(Vec(issue.dispatchWidth, Valid(new FrontendFtqAllocation(fp))))
        val arith0 = Output(new IssueEnqueueGroup(bp, issue.dispatchWidth))
        val arith1 = Output(new IssueEnqueueGroup(bp, issue.dispatchWidth))
        val loadStoreAddress = Output(new IssueEnqueueGroup(bp, issue.dispatchWidth))
        val storeData = Output(new IssueEnqueueGroup(bp, issue.dispatchWidth))
    })
    val middleend = Module(new Middleend(fp, bp, issue))
    val fetchQueue = Module(new FetchQueue(fp, issue.dispatchWidth))
    val packet = WireDefault(0.U.asTypeOf(new FrontendPackage(fp)))
    packet.fetchToken := io.in.bits.fetchToken
    packet.ftqIdx := io.in.bits.ftqIdx
    packet.startPc := io.in.bits.pc
    packet.mask := io.in.bits.mask
    val fields = Seq.fill(fp.fetchWidth)(Module(new PredecodeFields))
    val registers = Seq.fill(fp.fetchWidth)(Module(new RegisterInfoDecoder))
    for (slot <- 0 until fp.fetchWidth) {
        fields(slot).io.inst := io.in.bits.instructions(slot)
        registers(slot).io.inst := io.in.bits.instructions(slot)
        registers(slot).io.fields := fields(slot).io.fields
        packet.instructions(slot).inst := io.in.bits.instructions(slot)
        packet.instructions(slot).pc := io.in.bits.pc + (slot * 4).U
        packet.instructions(slot).rinfo := registers(slot).io.rinfo
    }
    for (slot <- 0 until fp.fetchWidth) {
        val earlier = if (slot == 0) false.B else packet.mask(slot - 1, 0).orR
        val later = if (slot + 1 == fp.fetchWidth) false.B else packet.mask(fp.fetchWidth - 1, slot + 1).orR
        fetchQueue.io.enq(slot).valid := io.in.valid && packet.mask(slot)
        fetchQueue.io.enq(slot).bits.fetchToken := packet.fetchToken
        fetchQueue.io.enq(slot).bits.slot := slot.U
        fetchQueue.io.enq(slot).bits.packetStart := !earlier
        fetchQueue.io.enq(slot).bits.packetEnd := !later
        fetchQueue.io.enq(slot).bits.instruction := packet.instructions(slot)
        fetchQueue.io.enq(slot).bits.record := packet.record
    }
    fetchQueue.io.flush := io.flush
    middleend.io.frontend.out <> fetchQueue.io.out
    io.in.ready := fetchQueue.io.enq(0).ready

    middleend.io.backend.freeCount := io.freeCount
    middleend.io.backend.wakeup.foreach { wakeup => wakeup.prd := 0.U; wakeup.specMask := 0.U }
    middleend.io.backend.memoryWakeup.zipWithIndex.foreach { case (wakeup, index) =>
        wakeup := Mux(index.U === 0.U, io.memoryWakeup, 0.U.asTypeOf(new BackendWakeup(bp)))
    }
    middleend.io.backend.speculation.resolvedMask := 0.U
    middleend.io.backend.speculation.failedMask := 0.U
    middleend.io.fpState := 0.U
    middleend.io.commit.resourcePrefix := io.resourcePrefix
    middleend.io.commit.allocation := io.allocation
    middleend.io.commit.ftqIdx.zipWithIndex.foreach { case (index, lane) => index := (4 + lane).U }
    middleend.io.commit.retire.foreach { retire => retire.valid := false.B; retire.bits := DontCare }
    middleend.io.commit.flush := io.flush
    middleend.io.commit.restore := io.restore

    io.request := middleend.io.commit.request
    io.enqueue := middleend.io.commit.enqueue
    io.ftqAllocate := middleend.io.commit.ftqAllocate
    io.arith0 := middleend.io.backend.enqueue(IssueQueueIndex.Arith0)
    io.arith1 := middleend.io.backend.enqueue(IssueQueueIndex.Arith1)
    io.loadStoreAddress := middleend.io.backend.enqueue(IssueQueueIndex.LoadStoreAddress)
    io.storeData := middleend.io.backend.enqueue(IssueQueueIndex.StoreData)
}

class MiddleendSpec extends AnyFreeSpec with ChiselSim {
    private def addi(rd: Int, rs1: Int = 0, immediate: Int = 0): BigInt =
        ((BigInt(immediate) & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(rd) << 7) | 0x13
    private def add(rd: Int, rs1: Int, rs2: Int): BigInt =
        (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(rd) << 7) | 0x33
    private def branch(rs1: Int, rs2: Int): BigInt =
        (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | 0x63
    private def store(rs1: Int, rs2: Int): BigInt =
        (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (2 << 12) | 0x23
    private def flw(rd: Int, rs1: Int = 0, immediate: Int = 0): BigInt =
        ((BigInt(immediate) & 0xfff) << 20) | (BigInt(rs1) << 15) | (2 << 12) | (BigInt(rd) << 7) | 0x07

    private def initialize(dut: MiddleendTestHarness): Unit = {
        dut.io.in.valid.poke(false)
        dut.io.in.bits.fetchToken.poke(0)
        dut.io.in.bits.ftqIdx.poke(0)
        dut.io.in.bits.mask.poke(0)
        dut.io.in.bits.pc.poke(0)
        dut.io.in.bits.instructions.foreach(_.poke(0))
        dut.io.freeCount.zip(dut.issue.queueParams).foreach { case (count, queue) => count.poke(queue.entries) }
        dut.io.resourcePrefix.poke((BigInt(1) << dut.issue.dispatchWidth) - 1)
        dut.io.memoryWakeup.prd.poke(0)
        dut.io.memoryWakeup.specMask.poke(0)
        dut.io.allocation.zipWithIndex.foreach { case (allocation, lane) =>
            allocation.robIdx.poke(10 + lane)
            allocation.sqTail.poke(30 + lane)
            allocation.sqIdx.poke(40 + lane)
        }
        dut.io.flush.poke(false)
        dut.io.restore.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def offer(dut: MiddleendTestHarness, token: Int, words: Seq[BigInt], mask: Int = 15): Unit = {
        dut.io.in.bits.fetchToken.poke(token)
        dut.io.in.bits.ftqIdx.poke(token & 15)
        dut.io.in.bits.mask.poke(mask)
        dut.io.in.bits.pc.poke(0x80000000L + token * 16L)
        dut.io.in.bits.instructions.zip(words.padTo(dut.fp.fetchWidth, BigInt(0))).foreach { case (port, word) =>
            port.poke(word)
        }
        dut.io.in.valid.poke(true)
        var waited = 0
        while (!dut.io.in.ready.peek().litToBoolean && waited <= dut.fp.fqDepth) {
            dut.clock.step()
            waited += 1
        }
        assert(dut.io.in.ready.peek().litToBoolean, s"Fetch input remained blocked for $waited cycles")
        dut.clock.step()
        dut.io.in.valid.poke(false)
    }

    "Rename and Dispatch are separated by a registered pipeline boundary" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            dut.io.in.bits.fetchToken.poke(6)
            dut.io.in.bits.ftqIdx.poke(6)
            dut.io.in.bits.mask.poke(3)
            dut.io.in.bits.pc.poke(0x80000060L)
            dut.io.in.bits.instructions.zip(Seq(addi(1), addi(2), BigInt(0), BigInt(0))).foreach {
                case (port, word) => port.poke(word)
            }
            dut.io.in.valid.poke(true)
            dut.io.in.ready.expect(true)
            dut.io.enqueue.valid.expect(0)

            dut.clock.step()
            dut.io.in.valid.poke(false)
            dut.io.enqueue.valid.expect(0)

            dut.clock.step()
            dut.io.enqueue.valid.expect(3)
            dut.io.enqueue.entries(0).context.fetchToken.expect(6)
            dut.io.enqueue.entries(1).context.fetchToken.expect(6)
        }
    }

    "a four-wide frontend feeds a parameterized three-wide middle end" in {
        val frontend = FrontendParams(fetchWidth = 4)
        val issue = IssueParams(dispatchWidth = 3)
        simulate(new MiddleendTestHarness(frontend, issue = issue)) { dut =>
            initialize(dut)
            val instructions = Seq(addi(1), flw(2), addi(3), flw(4))
            offer(dut, 16, instructions)
            val groups = scala.collection.mutable.ArrayBuffer.empty[(Int, Seq[(Int, Boolean)])]
            for (_ <- 0 until 8) {
                val valid = dut.io.enqueue.valid.peek().litValue
                if (valid != 0) {
                    val words = (0 until issue.dispatchWidth)
                        .filter(valid.testBit)
                        .map { lane =>
                            val entry = dut.io.enqueue.entries(lane)
                            val word = entry.context.instruction.inst.peek().litValue.toInt
                            if (word == flw(2).toInt || word == flw(4).toInt) {
                                entry.destination.isFp.expect(true)
                                val physical = entry.destination.prd.peek().litValue
                                assert(physical >= 32 && physical < dut.bp.numFpPhys)
                            }
                            word -> entry.context.packetEnd.peek().litToBoolean
                        }
                    groups += valid.toInt -> words
                }
                dut.clock.step()
            }
            assert(groups == Seq(
                7 -> instructions.take(3).map(_.toInt -> false),
                1 -> instructions.drop(3).map(_.toInt -> true),
            ))
        }
    }

    "a four-instruction fetch packet dispatches as two ordered groups" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            offer(dut, 7, Seq(addi(1), addi(2), addi(3), addi(4)))
            val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
            for (_ <- 0 until 8) {
                for (lane <- 0 until dut.issue.dispatchWidth if dut.io.enqueue.valid.peek().litValue.testBit(lane)) {
                    dut.io.enqueue.entries(lane).context.fetchToken.expect(7)
                    seen += dut.io.enqueue.entries(lane).context.instruction.inst.peek().litValue.toInt
                }
                dut.clock.step()
            }
            assert(seen == Seq(addi(1), addi(2), addi(3), addi(4)).map(_.toInt))
        }
    }

    "a same-group RAW uses the new tag and enters its issue queue unready" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            val dependent = add(6, 5, 0)
            offer(dut, 8, Seq(addi(5), dependent), mask = 3)
            while (dut.io.enqueue.valid.peek().litValue == 0) dut.clock.step()
            dut.io.enqueue.valid.expect(3)
            val queues = Seq(dut.io.arith0, dut.io.arith1)
            val selected = queues.find(_.entries(0).inst.peek().litValue == dependent).get
            selected.entries(0).sourceValid(0).expect(true)
            selected.entries(0).sourceReady(0).expect(false)
            selected.entries(0).sourceSpecMask(0).expect(0)
            selected.entries(0).prs(0).expect(dut.io.enqueue.entries(0).destination.prd.peek().litValue)
        }
    }

    "a buffered same-group RAW observes a wakeup before Dispatch" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.LoadStoreAddress).poke(0)
            offer(dut, 18, Seq(addi(5), store(5, 0)), mask = 3)
            while (dut.io.request.valid.peek().litValue == 0) dut.clock.step()

            val producer = Seq(dut.io.arith0, dut.io.arith1)
                .map(_.entries(0))
                .find(_.inst.peek().litValue == addi(5)).get
                .prd.peek().litValue
            dut.io.memoryWakeup.prd.poke(producer)
            dut.clock.step()
            dut.io.memoryWakeup.prd.poke(0)

            dut.io.freeCount(IssueQueueIndex.LoadStoreAddress).poke(dut.issue.loadStoreAddressEntries)
            while (dut.io.loadStoreAddress.valid.peek().litValue == 0) dut.clock.step()
            dut.io.loadStoreAddress.entries(0).prs(0).expect(producer)
            dut.io.loadStoreAddress.entries(0).sourceReady(0).expect(true)
        }
    }

    "issue backpressure retains renamed instructions and releases them exactly once" in {
        // A deeper FQ holds all three packets while every issue queue is blocked.
        simulate(new MiddleendTestHarness(fp = FrontendParams(fqDepth = 16))) { dut =>
            initialize(dut)
            dut.io.freeCount.foreach(_.poke(0))
            offer(dut, 9, Seq(addi(5), addi(6), addi(7), addi(8)))
            offer(dut, 10, Seq(addi(9), addi(10), addi(11), addi(12)))
            dut.clock.step(3)
            offer(dut, 12, Seq(addi(13), addi(14), addi(15), addi(16)))
            dut.io.enqueue.valid.expect(0)
            dut.io.in.ready.expect(true)

            dut.io.freeCount(IssueQueueIndex.Arith0).poke(6)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(6)
            val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
            for (_ <- 0 until 16) {
                for (lane <- 0 until dut.issue.dispatchWidth if dut.io.enqueue.valid.peek().litValue.testBit(lane)) {
                    seen += dut.io.enqueue.entries(lane).context.instruction.inst.peek().litValue.toInt
                }
                dut.clock.step()
            }
            assert(seen == (5 to 16).map(rd => addi(rd).toInt))
        }
    }

    "partial queue and Commit permissions accept only the oldest instruction" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(1)
            offer(dut, 11, Seq(branch(1, 2), branch(3, 4)), mask = 3)
            while (dut.io.request.valid.peek().litValue == 0) dut.clock.step()
            dut.io.enqueue.valid.expect(1)
            dut.io.enqueue.entries(0).context.instruction.inst.expect(branch(1, 2))
            dut.clock.step()
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.enqueue.valid.expect(0)

            dut.io.resourcePrefix.poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(2)
            dut.clock.step(3)
            dut.io.enqueue.valid.expect(0)
            dut.io.resourcePrefix.poke(1)
            dut.io.enqueue.valid.expect(1)
            dut.io.enqueue.entries(0).context.instruction.inst.expect(branch(3, 4))
        }
    }

    "dispatch groups compact across fetch packet boundaries and preserve FTQ mappings" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            val first = Seq(addi(1), addi(2), addi(3), addi(4))
            val second = Seq(addi(5), addi(6), addi(7), addi(8))
            offer(dut, 20, first)
            offer(dut, 21, second)

            val groups = scala.collection.mutable.ArrayBuffer.empty[Seq[(Int, Int)]]
            val allocationMasks = scala.collection.mutable.ArrayBuffer.empty[Int]
            for (_ <- 0 until 10) {
                val valid = dut.io.enqueue.valid.peek().litValue
                if (valid != 0) {
                    groups += (0 until dut.issue.dispatchWidth)
                        .filter(valid.testBit)
                        .map { lane =>
                            dut.io.enqueue.entries(lane).context.instruction.inst.peek().litValue.toInt ->
                                dut.io.enqueue.entries(lane).context.ftqIdx.peek().litValue.toInt
                        }
                    allocationMasks += dut.io.ftqAllocate.zipWithIndex.collect {
                        case (port, lane) if port.valid.peek().litToBoolean => 1 << lane
                    }.sum
                }
                dut.clock.step()
            }

            assert(groups == Seq(
                first.take(3).map(_.toInt -> 4),
                Seq(first(3).toInt -> 4, second(0).toInt -> 5, second(1).toInt -> 5),
                second.drop(2).map(_.toInt -> 5),
            ))
            assert(allocationMasks == Seq(1, 2, 0))
        }
    }

    "three single-instruction packets allocate three FTQ entries in one Dispatch group" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            dut.io.freeCount.foreach(_.poke(0))
            offer(dut, 30, Seq(addi(1)), mask = 1)
            offer(dut, 31, Seq(addi(2)), mask = 1)
            offer(dut, 32, Seq(addi(3)), mask = 1)
            dut.clock.step()
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(6)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(6)

            while (dut.io.enqueue.valid.peek().litValue == 0) dut.clock.step()
            dut.io.enqueue.valid.expect(7)
            dut.io.ftqAllocate.foreach(_.valid.expect(true))
            dut.io.enqueue.entries.zipWithIndex.foreach { case (entry, lane) =>
                entry.context.ftqIdx.expect(4 + lane)
                entry.context.packetStart.expect(true)
                entry.context.packetEnd.expect(true)
            }
            dut.clock.step()
            dut.io.ftqAllocate.foreach(_.valid.expect(false))
        }
    }

    "store tasks are duplicated atomically and flush discards blocked packets" in {
        simulate(new MiddleendTestHarness) { dut =>
            initialize(dut)
            offer(dut, 13, Seq(store(1, 2)), mask = 1)
            while (dut.io.enqueue.valid.peek().litValue == 0) dut.clock.step()
            dut.io.loadStoreAddress.valid.expect(1)
            dut.io.storeData.valid.expect(1)
            dut.clock.step()

            dut.io.freeCount.foreach(_.poke(0))
            offer(dut, 14, Seq(addi(10), addi(11)), mask = 3)
            dut.clock.step(3)
            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(6)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(6)
            for (_ <- 0 until 5) {
                dut.io.enqueue.valid.expect(0)
                dut.clock.step()
            }
        }
    }
}
