import chisel3._
import chisel3.util._
import ZirconConfig._

class ReadyBoardQuery(p: BackendParams, numSources: Int) extends Bundle {
    val prs = Vec(numSources, UInt(p.tagWidth.W))
    val valid = Vec(numSources, Bool())
}

class ReadyBoardState(p: BackendParams, numSources: Int) extends Bundle {
    val ready = Vec(numSources, Bool())
    val specMask = Vec(numSources, UInt(p.specWidth.W))
}

class ReadyBoardIO(
    p: BackendParams, width: Int, wakeupPorts: Int, numSources: Int,
    dualMemory: Boolean, replicateLoadMasks: Boolean,
) extends Bundle {
    val query = Input(Vec(width, new ReadyBoardQuery(p, numSources)))
    val allocate = Input(Vec(width, Valid(UInt(p.tagWidth.W))))
    val wakeup = Input(Vec(wakeupPorts, new BackendWakeup(p)))
    val memoryWakeup = if (dualMemory) Some(Input(new BackendWakeup(p))) else None
    val loadWakeupBeforeD1 = if (replicateLoadMasks) Some(Input(Vec(2, UInt(p.specWidth.W)))) else None
    val speculation = Input(new SpeculationResolution(p))
    val flush = Input(Bool())
    val state = Output(Vec(width, new ReadyBoardState(p, numSources)))
    val memoryState = if (dualMemory) Some(Output(Vec(width, new ReadyBoardState(p, numSources)))) else None
}

/** Readiness of integer and floating-point physical registers.
  *
  * Queries read registered state. IssueQueue applies current-cycle wakeups to
  * incoming entries; a wakeup wins if it targets a delayed allocation at this edge.
  */
class ReadyBoard(
    val p: BackendParams = BackendParams(),
    val width: Int = 2,
    val wakeupPorts: Int = 7,
    val numSources: Int = 3,
    val dualMemory: Boolean = false,
    val replicateLoadMasks: Boolean = false,
) extends Module {
    require(width > 0 && wakeupPorts > 0 && numSources > 0)
    require(!dualMemory || wakeupPorts > 2)
    require(!replicateLoadMasks || wakeupPorts > 5)

    val io = IO(new ReadyBoardIO(p, width, wakeupPorts, numSources, dualMemory, replicateLoadMasks))
    private val loadMaskCopies = if (replicateLoadMasks) Some(Seq.tabulate(2) { lane =>
        Seq.fill(8) {
            val copy = RegNext(io.loadWakeupBeforeD1.get(lane), 0.U)
            dontTouch(copy)
            if (sys.env.get("ZIRCON_USE_EXTERNAL_VIVADO_RAM").contains("true")) {
                addAttribute(copy, "DONT_TOUCH = \"TRUE\"")
            }
            copy
        }
    }) else None
    private val intReady = RegInit(VecInit.fill(p.numIntPhys)(true.B))
    private val fpReady = RegInit(VecInit.fill(p.numFpPhys)(true.B))
    // MixArith wakes compute at EX2 and memory at EX3. Only the intervening
    // cycle needs a distinct memory view; the tag is held at this Q boundary.
    private val earlyMixTag = if (dualMemory) Some(RegInit(0.U(p.tagWidth.W))) else None
    earlyMixTag.foreach { tag =>
        tag := Mux(io.flush || io.wakeup(2).prd === io.memoryWakeup.get.prd,
            0.U, io.wakeup(2).prd)
        when(!io.flush && tag =/= 0.U) {
            assert(io.memoryWakeup.get.prd === tag,
                "Memory MixArith wakeup must follow the compute wakeup")
        }
    }
    private val intSpec = RegInit(VecInit.fill(p.numIntPhys)(0.U(p.specWidth.W)))
    private val fpSpec = RegInit(VecInit.fill(p.numFpPhys)(0.U(p.specWidth.W)))
    // Allocation is consumed one edge after Rename accepts it. Queries bypass
    // this narrow pending register, preserving the original externally visible
    // not-ready cycle without placing admission on every scoreboard D-input.
    private val delayedAllocate = RegInit(VecInit.fill(width)(
        0.U.asTypeOf(Valid(UInt(p.tagWidth.W)))
    ))
    val nextAllocate = WireDefault(io.allocate)
    when(io.flush) {
        nextAllocate.foreach(_.valid := false.B)
    }
    delayedAllocate := nextAllocate

    private def updateDomain(
        ready: Vec[Bool],
        spec: Vec[UInt],
        isFp: Boolean,
    ): Unit = {
        val rows = if (isFp) p.numFpPhys else p.numIntPhys
        val indexWidth = log2Ceil(rows)
        def belongs(tag: UInt): Bool = tag =/= 0.U && tag(p.tagWidth - 1) === isFp.B
        def index(tag: UInt): UInt = tag(indexWidth - 1, 0)
        def rowMask(tag: UInt): UInt = Mux(belongs(tag), UIntToOH(index(tag), rows), 0.U(rows.W))
        def storedMask(tag: UInt): UInt = spec(index(tag))

        val allocated = delayedAllocate.map { allocation =>
            Mux(allocation.valid, rowMask(allocation.bits), 0.U(rows.W))
        }.reduce(_ | _)
        val wakeRows = io.wakeup.map(wakeup => rowMask(wakeup.prd))
        for (row <- 0 until rows) {
            val region = (if (isFp) 4 else 0) + row * 4 / rows
            val rowWakeMasks = io.wakeup.zipWithIndex.map { case (wakeup, port) =>
                if (replicateLoadMasks && (port == 4 || port == 5))
                    loadMaskCopies.get(port - 4)(region)
                else wakeup.specMask
            }
            val hits = wakeRows.map(_(row))
            val wake = hits.reduce(_ || _)
            val wakeMask = Mux1H(hits, rowWakeMasks)
            val failed = (spec(row) & io.speculation.failedMask).orR
            val wakeReady = hits.zip(rowWakeMasks).map { case (hit, mask) =>
                hit && !(mask & io.speculation.failedMask).orR
            }.reduce(_ || _)
            ready(row) := Mux(io.flush, true.B,
                (ready(row) && !failed && !allocated(row) && !wake) || wakeReady)
            spec(row) := Mux(io.flush, 0.U,
                Mux(wake, wakeMask, Mux(allocated(row), 0.U, spec(row))) &
                    ~io.speculation.resolvedMask)
        }
        if (dualMemory) {
            when(!io.flush && belongs(io.memoryWakeup.get.prd)) {
                assert(storedMask(io.memoryWakeup.get.prd) === 0.U)
            }
            when(!io.flush && belongs(io.wakeup(2).prd)) {
                assert(storedMask(io.wakeup(2).prd) === 0.U)
            }
        }
    }

    updateDomain(intReady, intSpec, isFp = false)
    updateDomain(fpReady, fpSpec, isFp = true)

    for (lane <- 0 until width; source <- 0 until numSources) {
        val query = io.query(lane)
        val isFp = query.prs(source)(p.tagWidth - 1)
        val index = query.prs(source)(p.physWidth - 1, 0)
        val intIndex = index(p.intWidth - 1, 0)
        val fpIndex = index(log2Ceil(p.numFpPhys) - 1, 0)
        val storedReady = Mux(isFp, fpReady(fpIndex), intReady(intIndex))
        val storedMask = Mux(isFp, fpSpec(fpIndex), intSpec(intIndex))
        val pendingAllocation = delayedAllocate.map { entry =>
            entry.valid && entry.bits === query.prs(source)
        }.reduce(_ || _)
        // The IssueQueue applies the current-cycle wakeup again when it writes
        // an incoming entry. Keep this query on registered ReadyBoard state so
        // wakeup does not form a combinational ReadyBoard-to-dispatch path.
        io.state(lane).ready(source) :=
            !query.valid(source) || !pendingAllocation && storedReady &&
                !(storedMask & io.speculation.failedMask).orR
        io.state(lane).specMask(source) := Mux(
            query.valid(source) && !pendingAllocation,
            storedMask & ~io.speculation.resolvedMask,
            0.U,
        )
        io.memoryState.foreach { state =>
            val earlyMix = earlyMixTag.get =/= 0.U && earlyMixTag.get === query.prs(source)
            state(lane).ready(source) :=
                !query.valid(source) || !pendingAllocation && storedReady && !earlyMix &&
                    !(storedMask & io.speculation.failedMask).orR
            state(lane).specMask(source) := io.state(lane).specMask(source)
        }
        when(query.valid(source)) {
            assert(
                index < Mux(isFp, p.numFpPhys.U, p.numIntPhys.U),
                "ReadyBoard query physical index is out of range",
            )
            when(!isFp) {
                assert(index =/= 0.U, "Integer x0 must be filtered before ReadyBoard lookup")
            }
        }
    }

    for (entry <- io.allocate) {
        when(entry.valid) {
            val isFp = entry.bits(p.tagWidth - 1)
            val index = entry.bits(p.physWidth - 1, 0)
            val duplicates = io.allocate.map(other => other.valid && other.bits === entry.bits)
            assert(index < Mux(isFp, p.numFpPhys.U, p.numIntPhys.U), "Allocated physical index is out of range")
            assert(isFp || index =/= 0.U, "Integer physical zero must never be allocated")
            assert(PopCount(duplicates) <= 1.U, "A physical destination may only be allocated once per cycle")
        }
    }
    for (wakeup <- io.wakeup) {
        when(wakeup.prd =/= 0.U) {
            val isFp = wakeup.prd(p.tagWidth - 1)
            val index = wakeup.prd(p.physWidth - 1, 0)
            assert(index < Mux(isFp, p.numFpPhys.U, p.numIntPhys.U), "Wakeup physical index is out of range")
        }
    }
    io.memoryWakeup.foreach { wakeup =>
        assert(wakeup.specMask === 0.U)
        when(wakeup.prd =/= 0.U) {
            val isFp = wakeup.prd(p.tagWidth - 1)
            val index = wakeup.prd(p.physWidth - 1, 0)
            assert(index < Mux(isFp, p.numFpPhys.U, p.numIntPhys.U))
        }
    }
    for (port <- 0 until wakeupPorts) {
        val conflicts = (port + 1 until wakeupPorts).map { other =>
            io.wakeup(port).prd =/= 0.U && io.wakeup(port).prd === io.wakeup(other).prd
        }
        if (conflicts.nonEmpty) {
            when(!io.flush) {
                assert(!conflicts.reduce(_ || _), "Backend wakeups must have distinct physical destinations")
            }
        }
    }
}
