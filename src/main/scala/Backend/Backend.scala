import chisel3._
import chisel3.util._
import ZirconConfig._

class ArithCommitIO extends Bundle {
    val rob = new ArithRobIO
    val branch = new ArithBranchContextIO
}

class BackendMiddleendIO(p: BackendParams, issue: IssueParams) extends Bundle {
    val enqueue = Input(Vec(IssueQueueIndex.Count, new IssueEnqueueGroup(p, issue.dispatchWidth)))
    val freeCount = Output(Vec(IssueQueueIndex.Count, UInt(issue.countWidth.W)))
    val wakeup = Output(Vec(issue.wakeupPorts, new BackendWakeup(p)))
    val memoryWakeup = Output(Vec(issue.wakeupPorts, new BackendWakeup(p)))
    val speculation = Output(new SpeculationResolution(p))
}

class BackendIO(
    p: BackendParams,
    issue: IssueParams,
    load: LoadPipelineParams,
    dcache: DCacheParams,
    tlbEnabled: Boolean,
    observe: Boolean,
) extends Bundle {
    val middleend = new BackendMiddleendIO(p, issue)
    val commit = new BackendCommitIO(p, load)
    val l2 = new DCacheL2IO
    val dtlb = if (tlbEnabled) Some(new TLBManagementIO) else None
    val dtlbMiss = if (tlbEnabled) Some(Output(Vec(2, Valid(new DTLBMissRequest)))) else None
    val dcacheIdle = Output(Bool())
    val maintenance = Flipped(new CacheMaintenanceIO)
    val performance = if (observe) Some(Output(new BackendPerformanceCounters)) else None
}

/**
  * Six compact issue queues, five execution pipes, shared PRFs, bypass and DCache.
  *
  * The ROB, SQ, SB and ReadyBoard remain outside this module. Load retry is closed
  * locally: the corresponding IQ entry stays at its original age and is re-armed
  * directly instead of being appended to the dispatch tail.
  */
class Backend(
    val p: BackendParams = BackendParams(),
    val issueParams: IssueParams = IssueParams(),
    val loadParams: LoadPipelineParams = LoadPipelineParams(numIntPhys = 72, numFpPhys = 48),
    val ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
    val tlbEnabled: Boolean = true,
    val observe: Boolean = false,
) extends Module {
    require(p == BackendParams(), "The current execution pipeline interfaces use the default BackendParams")
    require(p == loadParams.backend, "Backend and LoadPipeline parameters must describe the same physical machine")
    require(issueParams.wakeupPorts == 8, "Backend wakeup routing has eight fixed producer positions")

    private val dcacheParams = DCacheParams(loadParams.entries)
    val io = IO(new BackendIO(p, issueParams, loadParams, dcacheParams, tlbEnabled, observe))

    /* IssueQueueIndex is the shared ordering contract for dispatch, counters and execution pipes. */
    val queues = issueParams.queueParams.map(params => Module(new IssueQueue(p, params)))
    val arith0IQ = queues(IssueQueueIndex.Arith0)
    val arith1IQ = queues(IssueQueueIndex.Arith1)
    val mixArithIQ = queues(IssueQueueIndex.MixArith)
    val loadIQ = queues(IssueQueueIndex.Load)
    val loadStoreAddressIQ = queues(IssueQueueIndex.LoadStoreAddress)
    val storeDataIQ = queues(IssueQueueIndex.StoreData)

    /* Five execution pipes consume six queues because LS1 owns independent STA and STD inputs. */
    val arith0 = Module(new ArithBranch)
    val arith1 = Module(new ArithBranch)
    val arithPipes = Seq(arith0, arith1)
    val mixArith = Module(new MixArithPipeline)
    val ls0 = Module(new LoadPipeline(loadParams))
    val ls1 = Module(new LoadStorePipeline(loadParams, tlbEnabled))
    val dcache = Module(new DCache(ramBackend, dcacheParams, tlbEnabled, observe))
    val atomic = Module(new AtomicUnit(p, dcacheParams))

    /* Central PRFs provide stable held reads while a downstream execution stage is stalled. */
    val intRfParams = RegfileParams(
        numEntries = p.numIntPhys,
        numReadPorts = 9,
        numWritePorts = 5,
        holdReads = true,
    )
    val fpRfParams = RegfileParams(
        numEntries = p.numFpPhys,
        numReadPorts = 4,
        numWritePorts = 3,
        hasZeroReg = false,
        holdReads = true,
    )
    val intRf = Module(new Regfile(intRfParams))
    val fpRf = Module(new Regfile(fpRfParams))
    val bypass = Module(new Bypass(BypassParams.twoArithBackend(p)))
    val wakeupRouter = Module(new WakeupRouter(p))
    val loadSpeculation = Module(new LoadSpeculationTracker(p))

    /* The indexed Middleend boundary connects homogeneously to the six issue queues. */
    queues.zip(io.middleend.enqueue).foreach { case (queue, enqueue) => queue.io.enq := enqueue }
    io.middleend.freeCount := VecInit(queues.map(_.io.freeCount))
    queues.foreach { queue =>
        queue.io.flush := io.commit.flush
        queue.io.speculation := loadSpeculation.io.resolution
    }
    mixArithIQ.io.csrGrant.get := io.commit.csrGrant
    mixArithIQ.io.mixAvailable.get := mixArith.io.available

    /* Queue issue ports map directly to their owning execution resources. */
    arith0.io.iq <> arith0IQ.io.issue
    arith1.io.iq <> arith1IQ.io.issue
    mixArith.io.iq.valid := mixArithIQ.io.issue.valid
    mixArith.io.iq.bits := MixArithIssue.fromBackend(mixArithIQ.io.issue.bits)
    mixArithIQ.io.issue.ready := mixArith.io.iq.ready
    ls0.io.iq.instPkg <> loadIQ.io.issue
    ls1.io.iq.instPkg <> loadStoreAddressIQ.io.issue
    ls1.io.iq.std.get <> storeDataIQ.io.issue
    ls0.io.blockIssue := io.commit.atomic.blockMemoryIssue
    ls1.io.blockIssue := io.commit.atomic.blockMemoryIssue

    /* WakeupRouter publishes stage-specific producers to compute and memory readiness domains. */
    arithPipes.foreach(_.io.speculation := loadSpeculation.io.resolution)
    arithPipes.zipWithIndex.foreach { case (pipe, lane) =>
        wakeupRouter.io.arithIssue(lane) := pipe.io.wakeup.wakeIssue
        wakeupRouter.io.arithRF(lane) := pipe.io.wakeup.wakeRF
    }
    def certainWake(valid: Bool, prd: UInt): BackendWakeup = {
        val wakeup = Wire(new BackendWakeup(p))
        wakeup.prd := Mux(valid, prd, 0.U)
        wakeup.specMask := 0.U
        wakeup
    }
    wakeupRouter.io.mixEX2 := certainWake(mixArith.io.wakeEX2.valid, mixArith.io.wakeEX2.bits)
    wakeupRouter.io.mixEX3 := certainWake(mixArith.io.wakeEX3.valid, mixArith.io.wakeEX3.bits)
    wakeupRouter.io.mixWB := certainWake(mixArith.io.wakeup.valid, mixArith.io.wakeup.bits)
    Seq(ls0, ls1).zipWithIndex.foreach { case (pipe, lane) =>
        wakeupRouter.io.loadD1(lane) := pipe.io.wk.wakeD1
        wakeupRouter.io.loadWB(lane) := certainWake(pipe.io.wk.wakeWB.valid, pipe.io.wk.wakeWB.bits)
    }

    Seq(arith0IQ, arith1IQ, mixArithIQ).foreach(_.io.wakeup := wakeupRouter.io.compute)
    Seq(loadIQ, loadStoreAddressIQ, storeDataIQ).foreach(_.io.wakeup := wakeupRouter.io.memory)
    io.middleend.wakeup := wakeupRouter.io.compute
    io.middleend.memoryWakeup := wakeupRouter.io.memory
    io.middleend.speculation := loadSpeculation.io.resolution

    /* Loads reserve speculation tags before early wakeup and resolve them at cache response. */
    loadSpeculation.io.request(0) := ls0.io.wk.request
    loadSpeculation.io.request(1) := ls1.io.wk.request
    ls0.io.wk.grant := loadSpeculation.io.grant(0)
    ls1.io.wk.grant := loadSpeculation.io.grant(1)
    loadSpeculation.io.allocate(0) := ls0.io.wk.wakeRF.prd =/= 0.U
    loadSpeculation.io.allocate(1) := ls1.io.wk.wakeRF.prd =/= 0.U
    loadSpeculation.io.result(0) := ls0.io.wk.result
    loadSpeculation.io.result(1) := ls1.io.wk.result
    loadSpeculation.io.flush := io.commit.flush

    // Load completion and Retry address the retained issue entry directly.
    def connectLoadFeedback(queue: IssueQueue, pipe: LoadPipeline): Unit = {
        queue.io.completed.get.valid := pipe.io.cmt.rob.valid
        queue.io.completed.get.bits.robIdx := pipe.io.cmt.rob.bits.robIdx
        queue.io.retry.get.valid := pipe.io.wk.replay.valid
        queue.io.retry.get.bits.robIdx := pipe.io.wk.replay.bits.robIdx
    }
    connectLoadFeedback(loadIQ, ls0)
    connectLoadFeedback(loadStoreAddressIQ, ls1)

    // Integer PRF reads: two Arith pipes, Mix(2), LS0 address, LS1 address/data.
    for ((pipe, pipeIndex) <- arithPipes.zipWithIndex; source <- 0 until 2) {
        val port = pipeIndex * 2 + source
        intRf.io.read(port).addr := pipe.io.rf.read(source).addr
        pipe.io.rf.read(source).data := intRf.io.read(port).data
    }
    for (source <- 0 until 2) {
        intRf.io.read(4 + source).addr := mixArith.io.rf.intRead(source).addr
        mixArith.io.rf.intRead(source).data := intRf.io.read(4 + source).data
    }
    intRf.io.read(6).addr := ls0.io.rf.rd.prj
    ls0.io.rf.rd.prjData := intRf.io.read(6).data
    intRf.io.read(7).addr := ls1.io.rf.rd.prj
    ls1.io.rf.rd.prjData := intRf.io.read(7).data
    intRf.io.read(8).addr := ls1.io.rf.std.get.intAddr
    ls1.io.rf.std.get.intData := intRf.io.read(8).data
    intRf.io.readHold.get := VecInit(Seq.fill(6)(false.B) ++ Seq(
        ls0.io.rf.rd.hold,
        ls1.io.rf.rd.hold,
        ls1.io.rf.std.get.hold,
    ))

    for (source <- 0 until 3) {
        fpRf.io.read(source).addr := mixArith.io.rf.fpRead(source).addr
        mixArith.io.rf.fpRead(source).data := fpRf.io.read(source).data
    }
    fpRf.io.read(3).addr := ls1.io.rf.std.get.fpAddr
    ls1.io.rf.std.get.fpData := fpRf.io.read(3).data
    fpRf.io.readHold.get := VecInit(Seq(false.B, false.B, false.B, ls1.io.rf.std.get.hold))

    /* Writeback ports are statically assigned; LS1 and Atomic share the final integer port. */
    def intWrite(port: Int, valid: Bool, address: UInt, data: UInt): Unit = {
        intRf.io.write(port).we := valid
        intRf.io.write(port).addr := address
        intRf.io.write(port).data := data
    }
    for ((pipe, port) <- arithPipes.zipWithIndex) {
        intWrite(port, pipe.io.rf.write.valid, pipe.io.rf.write.bits.prd, pipe.io.rf.write.bits.data)
    }
    intWrite(2, mixArith.io.rf.intWrite.valid, mixArith.io.rf.intWrite.bits.addr, mixArith.io.rf.intWrite.bits.data)
    intWrite(
        3,
        ls0.io.rf.wr.valid && !ls0.io.rf.wr.bits.prd(p.tagWidth - 1),
        ls0.io.rf.wr.bits.prd(p.physWidth - 1, 0),
        ls0.io.rf.wr.bits.data,
    )
    val atomicIntWrite = atomic.io.response.fire && !atomic.io.response.bits.exception.orR &&
        atomic.io.response.bits.prd =/= 0.U
    val ls1IntWrite = ls1.io.rf.wr.valid && !ls1.io.rf.wr.bits.prd(p.tagWidth - 1)
    intWrite(
        4,
        atomicIntWrite || ls1IntWrite,
        Mux(atomicIntWrite, atomic.io.response.bits.prd, ls1.io.rf.wr.bits.prd)(p.physWidth - 1, 0),
        Mux(atomicIntWrite, atomic.io.response.bits.data, ls1.io.rf.wr.bits.data),
    )
    assert(!(atomicIntWrite && ls1IntWrite), "Atomic completion and LS1 must not share a write cycle")
    fpRf.io.write(0).we := mixArith.io.rf.fpWrite.valid
    fpRf.io.write(0).addr := mixArith.io.rf.fpWrite.bits.addr
    fpRf.io.write(0).data := mixArith.io.rf.fpWrite.bits.data
    for ((pipe, port) <- Seq(ls0 -> 1, ls1 -> 2)) {
        fpRf.io.write(port).we := pipe.io.rf.wr.valid && pipe.io.rf.wr.bits.prd(p.tagWidth - 1)
        fpRf.io.write(port).addr := pipe.io.rf.wr.bits.prd(p.physWidth - 1, 0)
        fpRf.io.write(port).data := pipe.io.rf.wr.bits.data
    }

    /* Arithmetic and load results feed the common bypass network before PRF visibility. */
    for ((pipe, port) <- arithPipes.zipWithIndex) {
        bypass.io.consumer(port) <> pipe.io.bypass.consumer
        bypass.io.producer(port) := pipe.io.bypass.producer
    }
    bypass.io.consumer(2) <> mixArith.io.bypass.consumer
    bypass.io.producer(2) := mixArith.io.bypass.producer
    bypass.io.producer(3) := ls0.io.bypass
    bypass.io.producer(4) := ls1.io.bypass

    /* Commit owns recovery and all architecturally ordered completion interfaces. */
    arithPipes.foreach(_.io.cmt.flush := io.commit.flush)
    mixArith.io.cmt.flush := io.commit.flush
    ls0.io.cmt.flush := io.commit.flush
    ls1.io.cmt.flush := io.commit.flush

    io.commit.arith.zip(arithPipes).foreach { case (port, pipe) =>
        port.rob <> pipe.io.cmt.rob
        port.branch <> pipe.io.cmt.branch
    }
    io.commit.mixRob <> mixArith.io.cmt.rob
    io.commit.mixCSR <> mixArith.io.csr

    def connectLoadCommit(external: LoadCompletionIO, pipe: LoadPipeline): Unit = {
        external.rob <> pipe.io.cmt.rob
        external.sqQuery <> pipe.io.cmt.sqQuery
        pipe.io.cmt.sqResult <> external.sqResult
        external.sbQuery <> pipe.io.cmt.sbQuery
        pipe.io.cmt.sbResult <> external.sbResult
    }
    connectLoadCommit(io.commit.ls0, ls0)
    connectLoadCommit(io.commit.ls1, ls1)
    io.commit.ls1.storeAddress.get <> ls1.io.cmt.storeAddress.get
    io.commit.ls1.storeData.get <> ls1.io.cmt.storeData.get

    /* LS0 owns DCache load port 0; LS1 shares port 1 with the serialized atomic unit. */
    ls0.connectCache(dcache.io)
    dcache.io.load(1).req.valid := Mux(atomic.io.busy, atomic.io.load.request.valid, ls1.io.cache.req.valid)
    dcache.io.load(1).req.bits := Mux(atomic.io.busy, atomic.io.load.request.bits, ls1.io.cache.req.bits)
    atomic.io.load.request.ready := atomic.io.busy && dcache.io.load(1).req.ready
    ls1.io.cache.req.ready := !atomic.io.busy && dcache.io.load(1).req.ready
    ls1.io.cache.wbSelect.valid := !atomic.io.busy && dcache.io.load(1).wbSelect.valid
    ls1.io.cache.wbSelect.bits := dcache.io.load(1).wbSelect.bits
    atomic.io.load.response.valid := atomic.io.busy && dcache.io.load(1).rsp.valid
    atomic.io.load.response.bits.data := dcache.io.load(1).rsp.bits.data
    atomic.io.load.response.bits.exception := dcache.io.load(1).rsp.bits.exception
    ls1.io.cache.rsp.valid := !atomic.io.busy && dcache.io.load(1).rsp.valid
    ls1.io.cache.rsp.bits := dcache.io.load(1).rsp.bits
    ls1.io.cache.fixedLatency := !atomic.io.busy && dcache.io.load(1).fixedLatency
    atomic.io.load.forwardQuery.valid := atomic.io.busy && dcache.io.forward(1).query.valid
    atomic.io.load.forwardQuery.bits := dcache.io.forward(1).query.bits
    ls1.io.cache.forward.query.valid := !atomic.io.busy && dcache.io.forward(1).query.valid
    ls1.io.cache.forward.query.bits := dcache.io.forward(1).query.bits
    dcache.io.forward(1).result.valid := Mux(
        atomic.io.busy,
        atomic.io.load.forwardResult.valid,
        ls1.io.cache.forward.result.valid,
    )
    dcache.io.forward(1).result.bits := Mux(
        atomic.io.busy,
        atomic.io.load.forwardResult.bits,
        ls1.io.cache.forward.result.bits,
    )
    if (tlbEnabled) {
        dcache.io.storeTranslation.get.request := ls1.io.cache.storeTranslation.get.request
        ls1.io.cache.storeTranslation.get.response := dcache.io.storeTranslation.get.response
    }
    dcache.io.flush := io.commit.flush
    if (tlbEnabled) {
        dcache.io.tlb.get.control := io.dtlb.get.control
        dcache.io.tlb.get.refill := io.dtlb.get.refill
        dcache.io.tlb.get.flush := io.dtlb.get.flush
        io.dtlbMiss.get := dcache.io.tlbMiss.get
    }
    dcache.io.store.req.valid := Mux(atomic.io.store.request.valid, true.B, io.commit.store.request.valid)
    dcache.io.store.req.bits := Mux(
        atomic.io.store.request.valid,
        atomic.io.store.request.bits,
        io.commit.store.request.bits,
    )
    atomic.io.store.request.ready := dcache.io.store.req.ready
    io.commit.store.request.ready := !atomic.io.store.request.valid && dcache.io.store.req.ready
    atomic.io.store.response.valid := atomic.io.busy && dcache.io.store.rsp.valid
    atomic.io.store.response.bits := dcache.io.store.rsp.bits
    io.commit.store.response.valid := !atomic.io.busy && dcache.io.store.rsp.valid
    io.commit.store.response.bits := dcache.io.store.rsp.bits
    dcache.io.store.rsp.ready := Mux(
        atomic.io.busy,
        atomic.io.store.response.ready,
        io.commit.store.response.ready,
    )
    atomic.io.clearReservation := io.commit.store.request.fire
    atomic.io.request <> io.commit.atomic.request
    io.commit.atomic.response <> atomic.io.response
    io.l2 <> dcache.io.l2
    io.dcacheIdle := dcache.io.idle && !atomic.io.busy
    dcache.io.maintenance.request := io.maintenance.request
    io.maintenance.done := dcache.io.maintenance.done

    for (event <- wakeupRouter.io.compute.toSeq ++ wakeupRouter.io.memory.toSeq) {
        when(event.prd === 0.U) {
            assert(event.specMask === 0.U, "An empty wakeup cannot carry speculation state")
        }
    }

    /* Simulation-only counters stay after the functional datapath. */
    if (observe) {
        val issueQueueFullCycles = RegInit(VecInit.fill(IssueQueueIndex.Count)(0.U(64.W)))
        val pipelineIssueCycles = RegInit(VecInit.fill(IssueQueueIndex.Count)(0.U(64.W)))
        val pipelineOperandWaitCycles = RegInit(VecInit.fill(IssueQueueIndex.Count)(0.U(64.W)))
        val pipelineReplayBlockedCycles = RegInit(VecInit.fill(IssueQueueIndex.Count)(0.U(64.W)))
        val pipelineExecutionBlockedCycles = RegInit(VecInit.fill(IssueQueueIndex.Count)(0.U(64.W)))
        val divideBusyCycles = RegInit(0.U(64.W))
        for ((queue, index) <- queues.zipWithIndex) {
            when(queue.io.freeCount === 0.U) {
                issueQueueFullCycles(index) := issueQueueFullCycles(index) + 1.U
            }
        }
        for ((queue, index) <- queues.zipWithIndex) {
            val issued = queue.io.issue.fire
            val replayBlocked = !issued && queue.io.replayBlocked
            val executionBlocked = !issued && !replayBlocked &&
                (queue.io.resourceBlocked || (queue.io.issue.valid && !queue.io.issue.ready))
            val operandBlocked = !issued && !replayBlocked && !executionBlocked && queue.io.operandBlocked
            when(issued) { pipelineIssueCycles(index) := pipelineIssueCycles(index) + 1.U }
            when(operandBlocked) {
                pipelineOperandWaitCycles(index) := pipelineOperandWaitCycles(index) + 1.U
            }
            when(replayBlocked) {
                pipelineReplayBlockedCycles(index) := pipelineReplayBlockedCycles(index) + 1.U
            }
            when(executionBlocked) {
                pipelineExecutionBlockedCycles(index) := pipelineExecutionBlockedCycles(index) + 1.U
            }
        }
        when(mixArith.io.divideBusy) { divideBusyCycles := divideBusyCycles + 1.U }
        io.performance.get.issueQueueFullCycles := issueQueueFullCycles
        io.performance.get.pipelineIssueCycles := pipelineIssueCycles
        io.performance.get.pipelineOperandWaitCycles := pipelineOperandWaitCycles
        io.performance.get.pipelineReplayBlockedCycles := pipelineReplayBlockedCycles
        io.performance.get.pipelineExecutionBlockedCycles := pipelineExecutionBlockedCycles
        io.performance.get.divideBusyCycles := divideBusyCycles
        io.performance.get.dcache := dcache.io.performance.get
    }
}

class BackendPerformanceCounters extends Bundle {
    val issueQueueFullCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val pipelineIssueCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val pipelineOperandWaitCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val pipelineReplayBlockedCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val pipelineExecutionBlockedCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val divideBusyCycles = UInt(64.W)
    val dcache = new DCachePerformanceCounters
}
