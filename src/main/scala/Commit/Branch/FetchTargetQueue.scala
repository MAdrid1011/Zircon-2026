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
        targets := VecInit.fill(p.fetchWidth)(0.U)
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
    /** Direct packet reads selected by the one-hot FTQ identity retained in each ROB entry. */
    val readIndexOH = Input(Vec(width, UInt(p.ftqDepth.W)))
    val read = Output(Vec(width, new FrontendFtqEntry(p)))
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
    val io = IO(new FetchTargetQueueIO(p, allocateWidth, commitWidth))

    val entries = RegInit(VecInit.fill(p.ftqDepth)(0.U.asTypeOf(new FrontendFtqEntry(p))))
    val head = RegInit(0.U(p.ftqBits.W))
    val headOH = RegInit(1.U(p.ftqDepth.W))
    val tail = RegInit(0.U(p.ftqBits.W))
    val used = RegInit(0.U(log2Ceil(p.ftqDepth + 1).W))
    val flush = io.commit.flush
    val popCount = PopCount(io.commit.pop)
    val pushCount = PopCount(io.allocate.map(_.valid))
    val writeAllocations = Wire(Vec(allocateWidth, Bool()))

    io.used := used

    for (lane <- 0 until allocateWidth) {
        val writeRank = if (lane == 0) 0.U else PopCount(io.allocate.take(lane).map(_.bits.writeValid))
        val writeIndex = (tail + writeRank)(p.ftqBits - 1, 0)
        val writeHasSpace = used + writeRank < p.ftqDepth.U
        // Flush clears visibility below; payload writes into invalid rows are harmless.
        writeAllocations(lane) := io.allocate(lane).bits.writeValid && writeHasSpace
        val allocation = WireDefault(0.U.asTypeOf(new FrontendFtqEntry(p)))
        allocation.record := io.allocate(lane).bits.record
        io.allocateIdx(lane) := writeIndex
        // Prewrite held Dispatch packets whenever this queue has space. Global
        // admission still controls allocation.valid and therefore visibility.
        when(writeAllocations(lane)) {
            entries(writeIndex) := allocation
        }
    }

    for (port <- 0 until commitWidth) {
        val index = (head + port.U)(p.ftqBits - 1, 0)
        val select = FIFOUtil.rotate(headOH, port)
        io.commit.head(port).valid := used > port.U
        io.commit.head(port).bits := Mux1H(select.asBools, entries)
        io.commit.headIdx(port) := index
        io.commit.read(port) := Mux1H(io.commit.readIndexOH(port).asBools, entries)
    }

    when(flush) {
        head := 0.U
        headOH := 1.U
        tail := 0.U
        used := 0.U
    }.otherwise {
        when(pushCount.orR) {
            tail := tail + pushCount
        }
        when(popCount.orR) {
            head := head + popCount
            headOH := FIFOUtil.advance(headOH, popCount, commitWidth)
        }
        when(pushCount.orR || popCount.orR) {
            used := used + pushCount - popCount
        }
    }

    for (row <- 0 until p.ftqDepth) {
        val hits = (0 until 2).map { port =>
            io.commit.branch(port).valid && io.commit.branch(port).bits.ftqIdxOH(row)
        }
        val slotMasks = (0 until 2).map(port => UIntToOH(io.commit.branch(port).bits.slot, p.fetchWidth))
        val resolved = VecInit(hits.zip(slotMasks).map { case (hit, mask) => Mux(hit, mask, 0.U) }).reduce(_ | _)
        val taken = VecInit((0 until 2).map { port =>
            Mux(hits(port) && io.commit.branch(port).bits.taken, slotMasks(port), 0.U)
        }).reduce(_ | _)
        when(resolved.orR) {
            entries(row).resolved := entries(row).resolved | resolved
            entries(row).taken := (entries(row).taken & ~resolved) | taken
            for (slot <- 0 until p.fetchWidth; port <- 0 until 2) {
                when(hits(port) && io.commit.branch(port).bits.slot === slot.U) {
                    entries(row).targets(slot) := io.commit.branch(port).bits.target
                }
            }
        }
    }

    FIFOUtil.assertPrefix(io.commit.pop.asBools, "FTQ releases must be an ordered prefix")
    when(!flush) {
        assert(PopCount(headOH) === 1.U)
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
    for (port <- 0 until commitWidth) {
        assert(PopCount(io.commit.readIndexOH(port)) <= 1.U)
    }

    for (port <- 0 until 2) {
        when(io.commit.branch(port).valid && !flush) {
            assert(PopCount(io.commit.branch(port).bits.ftqIdxOH) === 1.U)
            assert(io.commit.branch(port).bits.slot < p.fetchWidth.U)
            for (lane <- 0 until allocateWidth) {
                assert(
                    !(writeAllocations(lane) &&
                        io.commit.branch(port).bits.ftqIdxOH(
                            (tail + PopCount(io.allocate.take(lane).map(_.bits.writeValid)))(p.ftqBits - 1, 0)
                        )),
                    "FTQ allocation and branch write collided",
                )
            }
        }
    }
    for (lane <- 0 until allocateWidth) {
        assert(!io.allocate(lane).valid || io.allocate(lane).bits.writeValid)
    }
}
