import chisel3._
import ZirconConfig._

class ZirconInterrupts extends Bundle {
    val meip = Bool()
    val msip = Bool()
    val mtip = Bool()
    val seip = Bool()
}

class ZirconRetireTrace extends Bundle {
    val valid = Bool()
    val pc = UInt(32.W)
    val instruction = UInt(32.W)
    val mispredicted = Bool()
    val rd = UInt(5.W)
    val isFp = Bool()
    val writeValid = Bool()
    val value = UInt(32.W)
}

class ZirconDebugIO extends Bundle {
    val retire = Output(Vec(CommitParams().width, new ZirconRetireTrace))
    val robHeadValid = Output(Bool())
    val robHeadComplete = Output(Bool())
    val robHeadPc = Output(UInt(32.W))
    val privilege = Output(UInt(2.W))
    val csr = Output(new CSRState)
    val performance = Output(new ZirconPerformanceCounters)
}

class ZirconPerformanceCounters extends Bundle {
    val icacheVisit = UInt(64.W)
    val icacheHit = UInt(64.W)
    val icacheMissCycles = UInt(64.W)
    val fqBlockedCycles = UInt(64.W)
    val fqEmptyCycles = UInt(64.W)
    val ftqBlockedCycles = UInt(64.W)
    val integerFreeListBlockedCycles = UInt(64.W)
    val floatingFreeListBlockedCycles = UInt(64.W)
    val dispatchBlockedCycles = UInt(64.W)
    val branch = UInt(64.W)
    val branchFail = UInt(64.W)
    val directJump = UInt(64.W)
    val directJumpFail = UInt(64.W)
    val call = UInt(64.W)
    val callFail = UInt(64.W)
    val ret = UInt(64.W)
    val retFail = UInt(64.W)
    val indirect = UInt(64.W)
    val indirectFail = UInt(64.W)
    val loopTraining = UInt(64.W)
    val loopProvider = UInt(64.W)
    val loopCorrect = UInt(64.W)
    val robFullCycles = UInt(64.W)
    val storeBufferFullCycles = UInt(64.W)
    val storeBufferBusyCycles = UInt(64.W)
    val issueQueueFullCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val pipelineIssueCycles = Vec(6, UInt(64.W))
    val pipelineOperandWaitCycles = Vec(6, UInt(64.W))
    val pipelineReplayBlockedCycles = Vec(6, UInt(64.W))
    val pipelineExecutionBlockedCycles = Vec(6, UInt(64.W))
    val divideBusyCycles = UInt(64.W)
    val dcacheLoadVisits = Vec(2, UInt(64.W))
    val dcacheLoadHits = Vec(2, UInt(64.W))
    val dcacheLoadMisses = Vec(2, UInt(64.W))
    val dcacheLoadRetries = Vec(2, UInt(64.W))
    val dcacheLoadRetryTranslation = Vec(2, UInt(64.W))
    val dcacheLoadRetryForwardBlocked = Vec(2, UInt(64.W))
    val dcacheLoadRetryUncachedOrder = Vec(2, UInt(64.W))
    val dcacheLoadRetryStaleLookup = Vec(2, UInt(64.W))
    val dcacheLoadRetryMissBusy = Vec(2, UInt(64.W))
    val dcacheLoadRetryStoreConflict = Vec(2, UInt(64.W))
    val dcacheLoadRetryLaneConflict = Vec(2, UInt(64.W))
    val dcacheStoreVisits = UInt(64.W)
    val dcacheStoreHits = UInt(64.W)
    val dcacheStoreMisses = UInt(64.W)
    val dcacheMissBusyCycles = UInt(64.W)
    val l2InstructionVisits = UInt(64.W)
    val l2InstructionHits = UInt(64.W)
    val l2InstructionMisses = UInt(64.W)
    val l2DataVisits = UInt(64.W)
    val l2DataHits = UInt(64.W)
    val l2DataMisses = UInt(64.W)
    val l2InstructionVictimInsertions = UInt(64.W)
    val l2DataVictimInsertions = UInt(64.W)
    val lowerMemoryReads = UInt(64.W)
    val lowerMemoryWrites = UInt(64.W)
    val l2EngineBusyCycles = UInt(64.W)
}

class ZirconCoreIO(simulationDebug: Boolean) extends Bundle {
    val axi = new AXI4MasterIO
    val interrupts = Input(new ZirconInterrupts)
    val debug = if (simulationDebug) Some(new ZirconDebugIO) else None
}

/** Integration top level. Architectural behavior belongs to the instantiated blocks. */
class ZirconCore(val simulationDebug: Boolean = false) extends Module {
    override val desiredName = "ZirconCore"
    val io = IO(new ZirconCoreIO(simulationDebug))

    private val frontendParams = FrontendParams(observe = simulationDebug)
    val frontend = Module(new Frontend(p = frontendParams))
    val middleend = Module(new Middleend(frontendParams = frontendParams))
    val backend = Module(new Backend(observe = simulationDebug))
    val commit = Module(new Commit(fp = frontendParams, simulationDebug = simulationDebug))
    val ptw = Module(new PageTableWalker)
    val l2 = Module(new L2Cache(observe = simulationDebug))
    val bridge = Module(new L2AXI4Bridge)

    frontend.io.middle <> middleend.io.frontend
    middleend.io.csr := commit.io.environment.csr
    middleend.io.backend <> backend.io.middleend
    frontend.io.commit <> commit.io.frontend
    middleend.io.commit <> commit.io.middleend
    backend.io.arith0 <> commit.io.backend.arith0
    backend.io.arith1 <> commit.io.backend.arith1
    backend.io.mixRob <> commit.io.backend.mixRob
    backend.io.mixCSR <> commit.io.backend.mixCSR
    backend.io.ls0 <> commit.io.backend.ls0
    backend.io.ls1 <> commit.io.backend.ls1
    backend.io.store <> commit.io.backend.store
    backend.io.atomic <> commit.io.backend.atomic
    backend.io.flush := commit.io.backend.flush
    backend.io.csrGrant <> commit.io.backend.csrGrant
    if (simulationDebug) {
        for (lane <- 0 until CommitParams().width) {
            io.debug.get.retire(lane) := commit.io.debug.retire(lane)
        }
        io.debug.get.robHeadValid := commit.io.debug.robHeadValid
        io.debug.get.robHeadComplete := commit.io.debug.robHeadComplete
        io.debug.get.robHeadPc := commit.io.debug.robHeadPc
        io.debug.get.privilege := commit.io.environment.currentPrivilege
        io.debug.get.csr := commit.io.environment.csr
        io.debug.get.performance.icacheVisit := frontend.io.observe.get.icache.visit
        io.debug.get.performance.icacheHit := frontend.io.observe.get.icache.hit
        io.debug.get.performance.icacheMissCycles := frontend.io.observe.get.icache.missCycle
        io.debug.get.performance.fqBlockedCycles := frontend.io.observe.get.fqBlockedCycles
        io.debug.get.performance.fqEmptyCycles := frontend.io.observe.get.fqEmptyCycles
        io.debug.get.performance.ftqBlockedCycles := middleend.io.performance.get.ftqBlockedCycles
        io.debug.get.performance.integerFreeListBlockedCycles := middleend.io.performance.get.integerFreeListBlockedCycles
        io.debug.get.performance.floatingFreeListBlockedCycles := middleend.io.performance.get.floatingFreeListBlockedCycles
        io.debug.get.performance.dispatchBlockedCycles := middleend.io.performance.get.dispatchBlockedCycles
        io.debug.get.performance.branch := commit.io.debug.performance.branch
        io.debug.get.performance.branchFail := commit.io.debug.performance.branchFail
        io.debug.get.performance.directJump := commit.io.debug.performance.directJump
        io.debug.get.performance.directJumpFail := commit.io.debug.performance.directJumpFail
        io.debug.get.performance.call := commit.io.debug.performance.call
        io.debug.get.performance.callFail := commit.io.debug.performance.callFail
        io.debug.get.performance.ret := commit.io.debug.performance.ret
        io.debug.get.performance.retFail := commit.io.debug.performance.retFail
        io.debug.get.performance.indirect := commit.io.debug.performance.indirect
        io.debug.get.performance.indirectFail := commit.io.debug.performance.indirectFail
        io.debug.get.performance.loopTraining := frontend.io.observe.get.loopTraining
        io.debug.get.performance.loopProvider := frontend.io.observe.get.loopProvider
        io.debug.get.performance.loopCorrect := frontend.io.observe.get.loopCorrect
        io.debug.get.performance.robFullCycles := commit.io.debug.performance.robFullCycles
        io.debug.get.performance.storeBufferFullCycles := commit.io.debug.performance.storeBufferFullCycles
        io.debug.get.performance.storeBufferBusyCycles := commit.io.debug.performance.storeBufferBusyCycles
        io.debug.get.performance.issueQueueFullCycles := backend.io.performance.get.issueQueueFullCycles
        io.debug.get.performance.pipelineIssueCycles := backend.io.performance.get.pipelineIssueCycles
        io.debug.get.performance.pipelineOperandWaitCycles := backend.io.performance.get.pipelineOperandWaitCycles
        io.debug.get.performance.pipelineReplayBlockedCycles := backend.io.performance.get.pipelineReplayBlockedCycles
        io.debug.get.performance.pipelineExecutionBlockedCycles := backend.io.performance.get.pipelineExecutionBlockedCycles
        io.debug.get.performance.divideBusyCycles := backend.io.performance.get.divideBusyCycles
        io.debug.get.performance.dcacheLoadVisits := backend.io.performance.get.dcache.loadVisits
        io.debug.get.performance.dcacheLoadHits := backend.io.performance.get.dcache.loadHits
        io.debug.get.performance.dcacheLoadMisses := backend.io.performance.get.dcache.loadMisses
        io.debug.get.performance.dcacheLoadRetries := backend.io.performance.get.dcache.loadRetries
        io.debug.get.performance.dcacheLoadRetryTranslation := backend.io.performance.get.dcache.loadRetryTranslation
        io.debug.get.performance.dcacheLoadRetryForwardBlocked := backend.io.performance.get.dcache.loadRetryForwardBlocked
        io.debug.get.performance.dcacheLoadRetryUncachedOrder := backend.io.performance.get.dcache.loadRetryUncachedOrder
        io.debug.get.performance.dcacheLoadRetryStaleLookup := backend.io.performance.get.dcache.loadRetryStaleLookup
        io.debug.get.performance.dcacheLoadRetryMissBusy := backend.io.performance.get.dcache.loadRetryMissBusy
        io.debug.get.performance.dcacheLoadRetryStoreConflict := backend.io.performance.get.dcache.loadRetryStoreConflict
        io.debug.get.performance.dcacheLoadRetryLaneConflict := backend.io.performance.get.dcache.loadRetryLaneConflict
        io.debug.get.performance.dcacheStoreVisits := backend.io.performance.get.dcache.storeVisits
        io.debug.get.performance.dcacheStoreHits := backend.io.performance.get.dcache.storeHits
        io.debug.get.performance.dcacheStoreMisses := backend.io.performance.get.dcache.storeMisses
        io.debug.get.performance.dcacheMissBusyCycles := backend.io.performance.get.dcache.missBusyCycles
        io.debug.get.performance.l2InstructionVisits := l2.io.performance.get.instructionVisits
        io.debug.get.performance.l2InstructionHits := l2.io.performance.get.instructionHits
        io.debug.get.performance.l2InstructionMisses := l2.io.performance.get.instructionMisses
        io.debug.get.performance.l2DataVisits := l2.io.performance.get.dataVisits
        io.debug.get.performance.l2DataHits := l2.io.performance.get.dataHits
        io.debug.get.performance.l2DataMisses := l2.io.performance.get.dataMisses
        io.debug.get.performance.l2InstructionVictimInsertions := l2.io.performance.get.instructionVictimInsertions
        io.debug.get.performance.l2DataVictimInsertions := l2.io.performance.get.dataVictimInsertions
        io.debug.get.performance.lowerMemoryReads := l2.io.performance.get.lowerMemoryReads
        io.debug.get.performance.lowerMemoryWrites := l2.io.performance.get.lowerMemoryWrites
        io.debug.get.performance.l2EngineBusyCycles := l2.io.performance.get.engineBusyCycles
    }

    frontend.io.mem.l2 <> l2.io.icache
    backend.io.l2 <> l2.io.dcache
    l2.io.memory <> bridge.io.memory
    bridge.io.axi <> io.axi

    val time = RegInit(0.U(64.W))
    time := time + 1.U
    commit.io.environment.time := time
    commit.io.environment.privilege := 3.U
    commit.io.environment.interrupt.software := io.interrupts.msip
    commit.io.environment.interrupt.timer := io.interrupts.mtip
    commit.io.environment.interrupt.external := io.interrupts.meip
    commit.io.environment.interrupt.supervisorExternal := io.interrupts.seip
    commit.io.environment.memoryIdle := backend.io.dcacheIdle && l2.io.idle
    frontend.io.maintenance.request := commit.io.environment.maintenance.request && backend.io.maintenance.done
    backend.io.maintenance.request := commit.io.environment.maintenance.request
    commit.io.environment.maintenance.done := frontend.io.maintenance.done && backend.io.maintenance.done

    val translation = Wire(new AddressTranslationControl)
    translation.enabled := commit.io.environment.csr.satp(31) && commit.io.environment.currentPrivilege =/= 3.U
    translation.asid := commit.io.environment.csr.satp(30, 22)
    translation.privilege := commit.io.environment.currentPrivilege
    translation.mxr := commit.io.environment.csr.mstatus(19)
    translation.sum := commit.io.environment.csr.mstatus(18)
    frontend.io.tlb.get.control := translation
    frontend.io.tlb.get.refill := ptw.io.instruction.refill
    frontend.io.tlb.get.flush := commit.io.environment.tlbFlush
    backend.io.dtlb.get.control := translation
    backend.io.dtlb.get.refill := ptw.io.data.refill
    backend.io.dtlb.get.flush := commit.io.environment.tlbFlush

    ptw.io.control := translation
    ptw.io.satp := commit.io.environment.csr.satp
    ptw.io.flush := commit.io.environment.tlbFlush
    ptw.io.instruction.miss := frontend.io.mmu.request
    ptw.io.data.miss := backend.io.dtlbMiss.get
    frontend.io.mmu.response.valid := false.B
    frontend.io.mmu.response.bits := 0.U.asTypeOf(new ICacheTranslation)
    ptw.io.iptw <> l2.io.iptw
    ptw.io.dptw <> l2.io.dptw
}
