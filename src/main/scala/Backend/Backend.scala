import chisel3._
import chisel3.util._
import ZirconConfig._

class BackendLoadCommitIO(p: LoadPipelineParams, withStore: Boolean) extends Bundle {
    val rob = Valid(new LoadROBIO(p))
    val sqQuery = Valid(new LoadSQQuery(p))
    val sqResult = Flipped(Valid(new DForwardResult(DCacheParams(p.entries))))
    val sbQuery = Valid(new DForwardQuery(DCacheParams(p.entries)))
    val sbResult = Flipped(Valid(new DForwardResult(DCacheParams(p.entries))))
    val storeAddress = if (withStore) Some(Decoupled(new StoreAddressResult(p))) else None
    val storeData = if (withStore) Some(Decoupled(new StoreDataResult(p))) else None
}

class BackendMiddleendIO(p: BackendParams, issue: IssueParams) extends Bundle {
    val arith0 = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val arith1 = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val mixArith = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val load = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val loadStoreAddress = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val storeData = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val freeCount = Output(Vec(IssueQueueIndex.Count, UInt(issue.countWidth.W)))
    val wakeup = Output(Vec(issue.wakeupPorts, new BackendWakeup(p)))
    val memoryWakeup = Output(Vec(issue.wakeupPorts, new BackendWakeup(p)))
    val speculation = Output(new SpeculationResolution(p))
}

class BackendPerformanceCounters extends Bundle {
    val issueQueueFullCycles = Vec(IssueQueueIndex.Count, UInt(64.W))
    val pipelineIssueCycles = Vec(6, UInt(64.W))
    val pipelineOperandWaitCycles = Vec(6, UInt(64.W))
    val pipelineReplayBlockedCycles = Vec(6, UInt(64.W))
    val pipelineExecutionBlockedCycles = Vec(6, UInt(64.W))
    val divideBusyCycles = UInt(64.W)
    val dcache = new DCachePerformanceCounters
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
    val flush = Input(Bool())
    val csrGrant = Flipped(Valid(new CsrIssueGrant(p)))

    val arith0 = new Bundle {
        val rob = new ArithRobIO
        val branch = new ArithBranchContextIO
    }
    val arith1 = new Bundle {
        val rob = new ArithRobIO
        val branch = new ArithBranchContextIO
    }
    val mixRob = new MixArithRobIO
    val mixCSR = new CSRExecutionPort
    val ls0 = new BackendLoadCommitIO(load, withStore = false)
    val ls1 = new BackendLoadCommitIO(load, withStore = true)

    val store = new Bundle {
        val req = Flipped(Decoupled(new DStoreRequest))
        val rsp = Decoupled(new DStoreResponse)
    }
    val l2 = new DCacheL2IO
    val dtlb = if (tlbEnabled) Some(new TLBManagementIO) else None
    val dtlbMiss = if (tlbEnabled) Some(Output(Vec(2, Valid(new DTLBMissRequest)))) else None
    val dcacheIdle = Output(Bool())
    val performance = if (observe) Some(Output(new BackendPerformanceCounters)) else None
    val maintenance = new Bundle {
        val request = Input(Bool())
        val done = Output(Bool())
    }
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

    val arith0IQ = Module(new IssueQueue(p, issueParams.arith0))
    val arith1IQ = Module(new IssueQueue(p, issueParams.arith1))
    val mixArithIQ = Module(new IssueQueue(p, issueParams.mixArith))
    val loadIQ = Module(new IssueQueue(p, issueParams.load))
    val loadStoreAddressIQ = Module(new IssueQueue(p, issueParams.loadStoreAddress))
    val storeDataIQ = Module(new IssueQueue(p, issueParams.storeData))
    val queues = Seq(
        arith0IQ -> IssueQueueIndex.Arith0,
        arith1IQ -> IssueQueueIndex.Arith1,
        mixArithIQ -> IssueQueueIndex.MixArith,
        loadIQ -> IssueQueueIndex.Load,
        loadStoreAddressIQ -> IssueQueueIndex.LoadStoreAddress,
        storeDataIQ -> IssueQueueIndex.StoreData,
    )

    val arith0 = Module(new ArithBranch)
    val arith1 = Module(new ArithBranch)
    val arithPipes = Seq(arith0, arith1)
    val mixArith = Module(new MixArithPipeline)
    val ls0 = Module(new LoadPipeline(loadParams))
    val ls1 = Module(new LoadStorePipeline(loadParams, tlbEnabled))
    val dcache = Module(new DCache(ramBackend, dcacheParams, tlbEnabled, observe))

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

    val middleendPorts = Seq(
        io.middleend.arith0 -> IssueQueueIndex.Arith0,
        io.middleend.arith1 -> IssueQueueIndex.Arith1,
        io.middleend.mixArith -> IssueQueueIndex.MixArith,
        io.middleend.load -> IssueQueueIndex.Load,
        io.middleend.loadStoreAddress -> IssueQueueIndex.LoadStoreAddress,
        io.middleend.storeData -> IssueQueueIndex.StoreData,
    )
    queues.zip(middleendPorts).foreach { case ((queue, queueIndex), (middleendPort, portIndex)) =>
        require(queueIndex == portIndex)
        queue.io.enq := middleendPort
    }
    io.middleend.freeCount := VecInit(Seq(
        arith0IQ.io.freeCount,
        arith1IQ.io.freeCount,
        mixArithIQ.io.freeCount,
        loadIQ.io.freeCount,
        loadStoreAddressIQ.io.freeCount,
        storeDataIQ.io.freeCount,
    ))
    queues.foreach { case (queue, _) =>
        queue.io.flush := io.flush
        queue.io.speculation := loadSpeculation.io.resolution
    }
    mixArithIQ.io.csrGrant.get := io.csrGrant
    mixArithIQ.io.mixAvailable.get := mixArith.io.available

    arith0.io.iq <> arith0IQ.io.issue
    arith1.io.iq <> arith1IQ.io.issue
    mixArith.io.iq <> mixArithIQ.io.issue
    ls0.io.iq.instPkg <> loadIQ.io.issue
    ls1.io.iq.instPkg <> loadStoreAddressIQ.io.issue
    ls1.io.iq.std.get <> storeDataIQ.io.issue

    arithPipes.foreach(_.io.speculation := loadSpeculation.io.resolution)
    wakeupRouter.io.arith0Issue := arith0.io.wakeup.wakeIssue
    wakeupRouter.io.arith0WB := arith0.io.wakeup.wakeWB
    wakeupRouter.io.arith1Issue := arith1.io.wakeup.wakeIssue
    wakeupRouter.io.arith1WB := arith1.io.wakeup.wakeWB
    def certainWake(valid: Bool, prd: UInt): BackendWakeup = {
        val wakeup = Wire(new BackendWakeup(p))
        wakeup.prd := Mux(valid, prd, 0.U)
        wakeup.specMask := 0.U
        wakeup
    }
    wakeupRouter.io.mixEX2 := certainWake(mixArith.io.wakeEX2.valid, mixArith.io.wakeEX2.bits)
    wakeupRouter.io.mixEX3 := certainWake(mixArith.io.wakeEX3.valid, mixArith.io.wakeEX3.bits)
    wakeupRouter.io.mixWB := certainWake(mixArith.io.wakeup.valid, mixArith.io.wakeup.bits)
    wakeupRouter.io.load0D1 := ls0.io.wk.wakeD1
    wakeupRouter.io.load1D1 := ls1.io.wk.wakeD1
    wakeupRouter.io.load0WB := certainWake(ls0.io.wk.wakeWB.valid, ls0.io.wk.wakeWB.bits)
    wakeupRouter.io.load1WB := certainWake(ls1.io.wk.wakeWB.valid, ls1.io.wk.wakeWB.bits)

    Seq(arith0IQ, arith1IQ, mixArithIQ).foreach(_.io.wakeup := wakeupRouter.io.compute)
    Seq(loadIQ, loadStoreAddressIQ, storeDataIQ).foreach(_.io.wakeup := wakeupRouter.io.memory)
    io.middleend.wakeup := wakeupRouter.io.compute
    io.middleend.memoryWakeup := wakeupRouter.io.memory
    io.middleend.speculation := loadSpeculation.io.resolution

    loadSpeculation.io.request(0) := ls0.io.wk.request
    loadSpeculation.io.request(1) := ls1.io.wk.request
    ls0.io.wk.grant := loadSpeculation.io.grant(0)
    ls1.io.wk.grant := loadSpeculation.io.grant(1)
    loadSpeculation.io.allocate(0) := ls0.io.wk.wakeRF.prd =/= 0.U
    loadSpeculation.io.allocate(1) := ls1.io.wk.wakeRF.prd =/= 0.U
    loadSpeculation.io.result(0) := ls0.io.wk.result
    loadSpeculation.io.result(1) := ls1.io.wk.result
    loadSpeculation.io.flush := io.flush

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
    intWrite(
        4,
        ls1.io.rf.wr.valid && !ls1.io.rf.wr.bits.prd(p.tagWidth - 1),
        ls1.io.rf.wr.bits.prd(p.physWidth - 1, 0),
        ls1.io.rf.wr.bits.data,
    )
    fpRf.io.write(0).we := mixArith.io.rf.fpWrite.valid
    fpRf.io.write(0).addr := mixArith.io.rf.fpWrite.bits.addr
    fpRf.io.write(0).data := mixArith.io.rf.fpWrite.bits.data
    for ((pipe, port) <- Seq(ls0 -> 1, ls1 -> 2)) {
        fpRf.io.write(port).we := pipe.io.rf.wr.valid && pipe.io.rf.wr.bits.prd(p.tagWidth - 1)
        fpRf.io.write(port).addr := pipe.io.rf.wr.bits.prd(p.physWidth - 1, 0)
        fpRf.io.write(port).data := pipe.io.rf.wr.bits.data
    }

    for ((pipe, port) <- arithPipes.zipWithIndex) {
        bypass.io.consumer(port) <> pipe.io.bypass.consumer
        bypass.io.producer(port) := pipe.io.bypass.producer
    }
    bypass.io.consumer(2) <> mixArith.io.bypass.consumer
    bypass.io.producer(2) := mixArith.io.bypass.producer
    bypass.io.producer(3) := ls0.io.bypass
    bypass.io.producer(4) := ls1.io.bypass

    arithPipes.foreach(_.io.cmt.flush := io.flush)
    mixArith.io.cmt.flush := io.flush
    ls0.io.cmt.flush := io.flush
    ls1.io.cmt.flush := io.flush

    io.arith0.rob <> arith0.io.cmt.rob
    io.arith0.branch <> arith0.io.cmt.branch
    io.arith1.rob <> arith1.io.cmt.rob
    io.arith1.branch <> arith1.io.cmt.branch
    io.mixRob <> mixArith.io.cmt.rob
    io.mixCSR <> mixArith.io.csr

    def connectLoadCommit(external: BackendLoadCommitIO, pipe: LoadPipeline): Unit = {
        external.rob := pipe.io.cmt.rob
        external.sqQuery <> pipe.io.cmt.sq.query
        pipe.io.cmt.sq.result <> external.sqResult
        external.sbQuery <> pipe.io.cmt.sb.query
        pipe.io.cmt.sb.result <> external.sbResult
    }
    connectLoadCommit(io.ls0, ls0)
    connectLoadCommit(io.ls1, ls1)
    io.ls1.storeAddress.get <> ls1.io.cmt.sq.addr.get
    io.ls1.storeData.get <> ls1.io.cmt.sq.data.get

    ls0.connectCache(dcache.io)
    ls1.connectCache(dcache.io)
    dcache.io.flush := io.flush
    if (tlbEnabled) {
        dcache.io.tlb.get.control := io.dtlb.get.control
        dcache.io.tlb.get.refill := io.dtlb.get.refill
        dcache.io.tlb.get.flush := io.dtlb.get.flush
        io.dtlbMiss.get := dcache.io.tlbMiss.get
    }
    dcache.io.store <> io.store
    io.l2 <> dcache.io.l2
    io.dcacheIdle := dcache.io.idle
    if (observe) {
        val issueQueueFullCycles = RegInit(VecInit.fill(IssueQueueIndex.Count)(0.U(64.W)))
        val pipelineIssueCycles = RegInit(VecInit.fill(6)(0.U(64.W)))
        val pipelineOperandWaitCycles = RegInit(VecInit.fill(6)(0.U(64.W)))
        val pipelineReplayBlockedCycles = RegInit(VecInit.fill(6)(0.U(64.W)))
        val pipelineExecutionBlockedCycles = RegInit(VecInit.fill(6)(0.U(64.W)))
        val divideBusyCycles = RegInit(0.U(64.W))
        for ((queue, index) <- queues) {
            when(queue.io.freeCount === 0.U) {
                issueQueueFullCycles(index) := issueQueueFullCycles(index) + 1.U
            }
        }
        for (((queue, _), index) <- queues.zipWithIndex) {
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
    dcache.io.maintenance.request := io.maintenance.request
    io.maintenance.done := dcache.io.maintenance.done
}
