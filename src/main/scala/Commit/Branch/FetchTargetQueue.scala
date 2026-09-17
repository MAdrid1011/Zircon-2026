import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FrontendFtqRecord(p: FrontendParams) extends Bundle {
    val train = new FrontendTrainingRecord(p)
    val nextPc = UInt(32.W)
}

class FrontendFtqAllocation(p: FrontendParams) extends Bundle {
    val fetchToken = UInt(32.W)
    val record = new FrontendFtqRecord(p)
}

class FrontendFtqEntry(p: FrontendParams) extends Bundle {
    val fetchToken = UInt(32.W)
    val record = new FrontendFtqRecord(p)
    val resolved = UInt(p.fetchWidth.W)
    val taken = UInt(p.fetchWidth.W)
    val targets = Vec(p.fetchWidth, UInt(32.W))
    val mispredicted = UInt(p.fetchWidth.W)

    def enqueue(data: Data): Unit = {
        val incoming = data.asInstanceOf[FrontendFtqEntry]
        fetchToken := incoming.fetchToken
        record := incoming.record
        resolved := 0.U
        taken := 0.U
        targets := VecInit.fill(p.fetchWidth)(0.U)
        mispredicted := 0.U
    }

    def write(data: Data): Unit = {
        val incoming = data.asInstanceOf[FrontendFtqEntry]
        val mask = incoming.resolved
        resolved := resolved | mask
        taken := (taken & ~mask) | (incoming.taken & mask)
        mispredicted := (mispredicted & ~mask) | (incoming.mispredicted & mask)
        for (slot <- 0 until p.fetchWidth) {
            when(mask(slot)) { targets(slot) := incoming.targets(slot) }
        }
    }
}

class FtqBranchUpdate(p: FrontendParams) extends Bundle {
    val ftqIdx = UInt(p.ftqBits.W)
    val slot = UInt(p.slotBits.W)
    val taken = Bool()
    val target = UInt(32.W)
    val mispredicted = Bool()
}

/** ROB supplies an authorized retirement and any same-cycle global recovery. */
class FtqCommitIO(p: FrontendParams, width: Int) extends Bundle {
    val flush = Input(Bool())
    val branch = Flipped(Vec(2, Valid(new FtqBranchUpdate(p))))
    val pop = Input(UInt(width.W))
    val head = Output(Vec(width, Valid(new FrontendFtqEntry(p))))
    val headIdx = Output(Vec(width, UInt(p.ftqBits.W)))
}

/** Packet metadata allocated after FQ and retained until architectural retirement. */
class FetchTargetQueue(p: FrontendParams, width: Int = 3) extends Module {
    val io = IO(new Bundle {
        val allocate = Flipped(Decoupled(new FrontendFtqAllocation(p)))
        val allocateIdx = Output(UInt(p.ftqBits.W))
        val commit = new FtqCommitIO(p, width)
        val used = Output(UInt(log2Ceil(p.ftqDepth + 1).W))
    })

    val entries = RegInit(VecInit.fill(p.ftqDepth)(0.U.asTypeOf(new FrontendFtqEntry(p))))
    val head = RegInit(0.U(p.ftqBits.W))
    val tail = RegInit(0.U(p.ftqBits.W))
    val used = RegInit(0.U(log2Ceil(p.ftqDepth + 1).W))
    val flush = io.commit.flush
    val popCount = PopCount(io.commit.pop)
    val push = io.allocate.fire

    io.allocate.ready := used =/= p.ftqDepth.U && !flush
    io.allocateIdx := tail
    io.used := used

    val allocation = WireDefault(0.U.asTypeOf(new FrontendFtqEntry(p)))
    allocation.fetchToken := io.allocate.bits.fetchToken
    allocation.record := io.allocate.bits.record

    for (port <- 0 until width) {
        val index = (head + port.U)(p.ftqBits - 1, 0)
        io.commit.head(port).valid := used > port.U
        io.commit.head(port).bits := entries(index)
        io.commit.headIdx(port) := index
    }

    when(flush) {
        head := 0.U
        tail := 0.U
        used := 0.U
    }.otherwise {
        when(push) {
            entries(tail) := allocation
            tail := tail + 1.U
        }
        when(popCount.orR) {
            head := head + popCount
        }
        when(push || popCount.orR) {
            used := used + push.asUInt - popCount
        }
    }

    for (row <- 0 until p.ftqDepth) {
        val hits = (0 until 2).map { port =>
            io.commit.branch(port).valid && io.commit.branch(port).bits.ftqIdx === row.U && !flush
        }
        val slotMasks = (0 until 2).map(port => UIntToOH(io.commit.branch(port).bits.slot, p.fetchWidth))
        val resolved = VecInit(hits.zip(slotMasks).map { case (hit, mask) => Mux(hit, mask, 0.U) }).reduce(_ | _)
        val taken = VecInit((0 until 2).map { port =>
            Mux(hits(port) && io.commit.branch(port).bits.taken, slotMasks(port), 0.U)
        }).reduce(_ | _)
        val mispredicted = VecInit((0 until 2).map { port =>
            Mux(hits(port) && io.commit.branch(port).bits.mispredicted, slotMasks(port), 0.U)
        }).reduce(_ | _)
        when(resolved.orR) {
            entries(row).resolved := entries(row).resolved | resolved
            entries(row).taken := (entries(row).taken & ~resolved) | taken
            entries(row).mispredicted := (entries(row).mispredicted & ~resolved) | mispredicted
            for (slot <- 0 until p.fetchWidth; port <- 0 until 2) {
                when(hits(port) && io.commit.branch(port).bits.slot === slot.U) {
                    entries(row).targets(slot) := io.commit.branch(port).bits.target
                }
            }
        }
    }

    FIFOUtil.assertPrefix(io.commit.pop.asBools, "FTQ releases must be an ordered prefix")
    when(!flush) {
        assert(popCount <= used, "FTQ cannot release more packets than it contains")
    }
    for (left <- 0 until 2; right <- left + 1 until 2) {
        when(io.commit.branch(left).valid && io.commit.branch(right).valid && !flush) {
            assert(
                io.commit.branch(left).bits.ftqIdx =/= io.commit.branch(right).bits.ftqIdx ||
                    io.commit.branch(left).bits.slot =/= io.commit.branch(right).bits.slot,
                "Two Branch pipes cannot resolve the same FTQ slot",
            )
        }
    }

    for (port <- 0 until 2) {
        when(io.commit.branch(port).valid && !flush) {
            assert(io.commit.branch(port).bits.slot < p.fetchWidth.U)
            assert(!(push && io.commit.branch(port).bits.ftqIdx === tail), "FTQ allocation and branch write collided")
        }
    }
}
