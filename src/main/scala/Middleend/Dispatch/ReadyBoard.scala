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

class ReadyBoardIO(p: BackendParams, width: Int, wakeupPorts: Int, numSources: Int) extends Bundle {
    val query = Input(Vec(width, new ReadyBoardQuery(p, numSources)))
    val allocate = Input(Vec(width, Valid(UInt(p.tagWidth.W))))
    val wakeup = Input(Vec(wakeupPorts, new BackendWakeup(p)))
    val speculation = Input(new SpeculationResolution(p))
    val flush = Input(Bool())
    val state = Output(Vec(width, new ReadyBoardState(p, numSources)))
}

/** Readiness of integer and floating-point physical registers.
  *
  * Queries read registered state. IssueQueue applies current-cycle wakeups to
  * incoming entries, while accepted destinations take priority at this boundary.
  */
class ReadyBoard(
    val p: BackendParams = BackendParams(),
    val width: Int = 2,
    val wakeupPorts: Int = 7,
    val numSources: Int = 3,
) extends Module {
    require(width > 0 && wakeupPorts > 0 && numSources > 0)

    val io = IO(new ReadyBoardIO(p, width, wakeupPorts, numSources))
    private val intReady = RegInit(VecInit(Seq.fill(p.numIntPhys)(true.B)))
    private val fpReady = RegInit(VecInit(Seq.fill(p.numFpPhys)(true.B)))
    private val intSpec = RegInit(VecInit(Seq.fill(p.numIntPhys)(0.U(p.specWidth.W))))
    private val fpSpec = RegInit(VecInit(Seq.fill(p.numFpPhys)(0.U(p.specWidth.W))))

    private def updateDomain(
        ready: Vec[Bool],
        spec: Vec[UInt],
        isFp: Boolean,
    ): Unit = {
        val indexWidth = log2Ceil(if (isFp) p.numFpPhys else p.numIntPhys)
        // Maintain every live speculation tag, then update only the dynamically
        // indexed wakeup and allocation destinations as in Zircon-2024.
        for (index <- ready.indices) {
            ready(index) := ready(index) && !(spec(index) & io.speculation.failedMask).orR
            spec(index) := spec(index) & ~io.speculation.resolvedMask
        }

        for (wakeup <- io.wakeup) {
            val valid = wakeup.prd =/= 0.U && wakeup.prd(p.tagWidth - 1) === isFp.B
            val index = wakeup.prd(indexWidth - 1, 0)
            when(valid) {
                ready(index) := !(wakeup.specMask & io.speculation.failedMask).orR
                spec(index) := wakeup.specMask & ~io.speculation.resolvedMask
            }
        }

        for (entry <- io.allocate) {
            val valid = entry.valid && entry.bits(p.tagWidth - 1) === isFp.B
            val index = entry.bits(indexWidth - 1, 0)
            when(valid) {
                ready(index) := false.B
                spec(index) := 0.U
            }
        }

        when(io.flush) {
            ready := VecInit.fill(ready.length)(true.B)
            spec := VecInit.fill(spec.length)(0.U(p.specWidth.W))
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
        // The IssueQueue applies the current-cycle wakeup again when it writes
        // an incoming entry. Keep this query on registered ReadyBoard state so
        // wakeup does not form a combinational ReadyBoard-to-dispatch path.
        io.state(lane).ready(source) :=
            !query.valid(source) || storedReady && !(storedMask & io.speculation.failedMask).orR
        io.state(lane).specMask(source) := Mux(
            query.valid(source),
            storedMask & ~io.speculation.resolvedMask,
            0.U,
        )
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
