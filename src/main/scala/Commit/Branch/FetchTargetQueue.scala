import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FrontendFtqRecord(p: FrontendParams) extends Bundle {
    val train = new FrontendTrainingRecord(p)
    val nextPc = UInt(32.W)
}

class FrontendFtqAllocation(p: FrontendParams) extends Bundle {
    val writeValid = Bool()
    val record = new FrontendFtqRecord(p)
}

class FrontendFtqEntry(p: FrontendParams) extends Bundle {
    val record = new FrontendFtqRecord(p)
    val resolved = UInt(p.fetchWidth.W)
    val taken = UInt(p.fetchWidth.W)
    val targets = Vec(p.fetchWidth, UInt(32.W))

    def enqueue(data: Data): Unit = {
        val incoming = data.asInstanceOf[FrontendFtqEntry]
        record := incoming.record
        resolved := 0.U
        taken := 0.U
        // A target is observed only after its slot has been resolved.
    }

    def write(data: Data): Unit = {
        val incoming = data.asInstanceOf[FrontendFtqEntry]
        val mask = incoming.resolved
        resolved := resolved | mask
        taken := (taken & ~mask) | (incoming.taken & mask)
        for (slot <- 0 until p.fetchWidth) {
            when(mask(slot)) { targets(slot) := incoming.targets(slot) }
        }
    }
}

class FtqBranchUpdate(p: FrontendParams) extends Bundle {
    val ftqIdxOH = UInt(p.ftqDepth.W)
    val slot = UInt(p.slotBits.W)
    val taken = Bool()
    val target = UInt(32.W)
}

/** ROB supplies an authorized retirement and any same-cycle global recovery. */
class FtqCommitIO(p: FrontendParams, width: Int) extends Bundle {
    val flush = Input(Bool())
    val branch = Flipped(Vec(2, Valid(new FtqBranchUpdate(p))))
    val pop = Input(UInt(width.W))
    val head = Output(Vec(width, Valid(new FrontendFtqEntry(p))))
    val headIdx = Output(Vec(width, UInt(p.ftqBits.W)))
}

class FetchTargetQueueIO(p: FrontendParams, allocateWidth: Int, commitWidth: Int) extends Bundle {
    val allocate = Flipped(Vec(allocateWidth, Valid(new FrontendFtqAllocation(p))))
    val allocateIdx = Output(Vec(allocateWidth, UInt(p.ftqBits.W)))
    val commit = new FtqCommitIO(p, commitWidth)
    val used = Output(UInt(log2Ceil(p.ftqDepth + 1).W))
}

/** Packet metadata allocated at Dispatch and retained until architectural retirement. */
class FetchTargetQueue(p: FrontendParams, allocateWidth: Int = 3, commitWidth: Int = 3) extends Module {
    require(allocateWidth > 0 && allocateWidth <= 4)
    require(commitWidth > 0 && commitWidth <= 4)
    require(p.ftqDepth >= 4 && p.ftqDepth % 4 == 0)
    val io = IO(new FetchTargetQueueIO(p, allocateWidth, commitWidth))

    private val bankCount = 4
    private val bankDepth = p.ftqDepth / bankCount
    val queue = Module(new ClusterIndexFIFO(
        new FrontendFtqEntry(p),
        p.ftqDepth,
        bankCount,
        commitWidth,
        0,
        2,
        compactEnq = true,
        exposeDeqIndex = true,
        writePayloadOnFlush = true,
        separateEnqWrite = true,
        externallyGuardedCapacity = true,
        resetPayload = false,
    ))
    val used = RegInit(0.U(log2Ceil(p.ftqDepth + 1).W))
    val flush = io.commit.flush
    val popCount = PopCount(io.commit.pop)
    val pushCount = PopCount(io.allocate.map(_.valid))
    val writeAllocations = Wire(Vec(allocateWidth, Bool()))

    def binaryIndex(index: ClusterEntry): UInt =
        (OHToUInt(index.offset) * bankCount.U + OHToUInt(index.qidx))(p.ftqBits - 1, 0)

    def clusterIndex(indexOH: UInt): ClusterEntry = {
        val result = Wire(new ClusterEntry(bankDepth, bankCount))
        result.qidx := VecInit((0 until bankCount).map { bank =>
            (bank until p.ftqDepth by bankCount).map(indexOH(_)).reduce(_ || _)
        }).asUInt
        result.offset := VecInit((0 until bankDepth).map { row =>
            indexOH(row * bankCount + bankCount - 1, row * bankCount).orR
        }).asUInt
        result.high := 0.U
        result
    }

    queue.io.flush := flush
    io.used := used
    queue.io.enqWrite.get := VecInit((0 until bankCount).map { lane =>
        if (lane < allocateWidth) writeAllocations(lane) else false.B
    })
    for (lane <- 0 until allocateWidth) {
        val writeRank = if (lane == 0) 0.U else PopCount(io.allocate.take(lane).map(_.bits.writeValid))
        val writeHasSpace = used + writeRank < p.ftqDepth.U
        writeAllocations(lane) := io.allocate(lane).bits.writeValid && writeHasSpace
        val allocation = WireDefault(0.U.asTypeOf(new FrontendFtqEntry(p)))
        allocation.record := io.allocate(lane).bits.record
        queue.io.enq(lane).valid := io.allocate(lane).valid
        queue.io.enq(lane).bits := allocation
        io.allocateIdx(lane) := binaryIndex(queue.io.enqWriteIdx.get(lane))
    }
    for (lane <- allocateWidth until bankCount) {
        queue.io.enq(lane).valid := false.B
        queue.io.enq(lane).bits := 0.U.asTypeOf(new FrontendFtqEntry(p))
    }
    for (port <- 0 until commitWidth) {
        queue.io.deq(port).ready := io.commit.pop(port)
        io.commit.head(port).valid := queue.io.deq(port).valid
        io.commit.head(port).bits := queue.io.deq(port).bits
        io.commit.headIdx(port) := binaryIndex(queue.io.deqIdx.get(port))
    }

    when(flush) {
        used := 0.U
    }.otherwise {
        when(pushCount.orR || popCount.orR) {
            used := used + pushCount - popCount
        }
    }

    val sameBranchRow = io.commit.branch(0).valid && io.commit.branch(1).valid &&
        io.commit.branch(0).bits.ftqIdxOH === io.commit.branch(1).bits.ftqIdxOH
    for (port <- 0 until 2) {
        val branch = io.commit.branch(port)
        val slotMask = UIntToOH(branch.bits.slot, p.fetchWidth)
        val mergeOther = if (port == 0) sameBranchRow else false.B
        val otherSlotMask = UIntToOH(io.commit.branch(1).bits.slot, p.fetchWidth)
        val update = WireDefault(0.U.asTypeOf(new FrontendFtqEntry(p)))
        update.resolved := slotMask | Mux(mergeOther, otherSlotMask, 0.U)
        update.taken := Mux(branch.bits.taken, slotMask, 0.U) |
            Mux(mergeOther && io.commit.branch(1).bits.taken, otherSlotMask, 0.U)
        for (slot <- 0 until p.fetchWidth) {
            update.targets(slot) := Mux(
                mergeOther && io.commit.branch(1).bits.slot === slot.U,
                io.commit.branch(1).bits.target,
                branch.bits.target,
            )
        }
        queue.io.wen(port) := branch.valid && (if (port == 0) true.B else !sameBranchRow)
        queue.io.widx(port) := clusterIndex(branch.bits.ftqIdxOH)
        queue.io.wdata(port) := update
    }

    FIFOUtil.assertPrefix(io.commit.pop.asBools, "FTQ releases must be an ordered prefix")
    when(!flush) {
        assert(popCount <= used, "FTQ cannot release more packets than it contains")
        assert(used + pushCount <= p.ftqDepth.U, "FTQ allocation exceeded available capacity")
    }
    for (left <- 0 until 2; right <- left + 1 until 2) {
        when(io.commit.branch(left).valid && io.commit.branch(right).valid && !flush) {
            assert(
                !(io.commit.branch(left).bits.ftqIdxOH & io.commit.branch(right).bits.ftqIdxOH).orR ||
                    io.commit.branch(left).bits.slot =/= io.commit.branch(right).bits.slot,
                "Two Branch pipes cannot resolve the same FTQ slot",
            )
        }
    }
    for (port <- 0 until 2) {
        when(io.commit.branch(port).valid && !flush) {
            assert(PopCount(io.commit.branch(port).bits.ftqIdxOH) === 1.U)
            assert(io.commit.branch(port).bits.slot < p.fetchWidth.U)
            for (lane <- 0 until allocateWidth) {
                assert(
                    !(writeAllocations(lane) &&
                        io.commit.branch(port).bits.ftqIdxOH(io.allocateIdx(lane))),
                    "FTQ allocation and branch write collided",
                )
            }
        }
    }
    for (lane <- 0 until allocateWidth) {
        assert(!io.allocate(lane).valid || io.allocate(lane).bits.writeValid)
    }
}
