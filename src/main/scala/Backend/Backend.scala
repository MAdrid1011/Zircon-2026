import chisel3._
import chisel3.util._
import ZirconConfig._

class ArithCommitIO extends Bundle {
    val rob = new ArithRobIO
    val branch = new ArithBranchContextIO
}

class BackendMiddleendIO(p: BackendParams, issue: IssueParams) extends Bundle {
    val enqueue = Input(Vec(IssueQueueIndex.Count, new RoutedIssueEnqueueGroup(p, issue.dispatchWidth)))
    val freePrefix = Output(Vec(IssueQueueIndex.Count, UInt(issue.dispatchWidth.W)))
    val wakeup = Output(Vec(issue.wakeupPorts, new BackendWakeup(p)))
    val memoryWakeup = Output(Vec(issue.wakeupPorts, new BackendWakeup(p)))
    val loadWakeupBeforeD1 = Output(Vec(2, UInt(p.specWidth.W)))
    val speculation = Output(new SpeculationResolution(p))
}

class BackendIO(
    p: BackendParams,
    issue: IssueParams,
    load: LoadPipelineParams,
    dcache: DCacheParams,
    tlbEnabled: Boolean,
    observe: Boolean,
    vectorEnabled: Boolean = false,
    scratchpadEnabled: Boolean = false,
) extends Bundle {
    val middleend = new BackendMiddleendIO(p, issue)
    val commit = new BackendCommitIO(p, load)
    val l2 = new DCacheL2IO
    val scratchpad = if (scratchpadEnabled) Some(new PeScratchpadPort) else None
    val dtlb = if (tlbEnabled) Some(new TLBManagementIO) else None
    val dtlbMiss = if (tlbEnabled) Some(Output(Vec(2, Valid(new DTLBMissRequest)))) else None
    val dcacheIdle = Output(Bool())
    val maintenance = Flipped(new CacheMaintenanceIO)
    val performance = if (observe) Some(Output(new BackendPerformanceCounters)) else None
    val debug = if (observe) Some(Output(new BackendDebugIO)) else None
    // Physical resource service events, independent of algorithm and VL.
    // Pipelined resources count accepted service slots; nonpipelined divide
    // and vector-serial resources count occupied cycles of their sole server.
    val throughput = if (observe) Some(Output(UInt(11.W))) else None
    val vector = if (vectorEnabled) Some(new VectorBackendIO(p)) else None
    val vectorObservation = if (vectorEnabled && observe) Some(Output(Vec(64, UInt(256.W)))) else None
    val vectorPerformance = if (vectorEnabled && observe) Some(Output(Vec(8,UInt(64.W)))) else None
}

/**
  * Six fixed-slot issue queues, five execution pipes, shared PRFs, bypass and DCache.
  *
  * The ROB, SQ, SB and ReadyBoard remain outside this module. Load retry is closed
  * locally: the corresponding IQ entry retains its age and is re-armed
  * at that position for the next eligible issue.
  */
class Backend(
    val p: BackendParams = BackendParams(),
    val issueParams: IssueParams = IssueParams(),
    val loadParams: LoadPipelineParams = LoadPipelineParams(numIntPhys = 64, numFpPhys = 40),
    val ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
    val tlbEnabled: Boolean = true,
    val observe: Boolean = false,
    val atomicEnabled: Boolean = true,
    val dcacheSets: Int = 16,
    val directPhysical: Boolean = false,
    val vectorEnabled: Boolean = false,
    val vectorParams: VectorParams = VectorParams(),
    val scratchpadEnabled: Boolean = false,
) extends Module {
    require(!scratchpadEnabled || vectorEnabled)
    require(p == BackendParams(), "The current execution pipeline interfaces use the default BackendParams")
    require(p == loadParams.backend, "Backend and LoadPipeline parameters must describe the same physical machine")
    require(issueParams.wakeupPorts == 8, "Backend wakeup routing has eight fixed producer positions")

    // Vector LMUL groups reuse the cache request slot; scalar LS queues retain their size.
    private val dcacheParams = DCacheParams(
        if (vectorEnabled) math.max(loadParams.entries, 8) else loadParams.entries,
        sets = dcacheSets,
        directPhysical = directPhysical,
        scratchpadEnabled = scratchpadEnabled,
    )
    require(!vectorEnabled || (!atomicEnabled && directPhysical))
    val io = IO(new BackendIO(p, issueParams, loadParams, dcacheParams, tlbEnabled, observe, vectorEnabled, scratchpadEnabled))

    /* IssueQueueIndex is the shared ordering contract for dispatch, counters and execution pipes. */
    val queues = issueParams.queueParams.zipWithIndex.map { case (params, index) =>
        val directCandidates = if (index <= IssueQueueIndex.MixArith) 2 else 0
        Module(new IssueQueue(p, params, directCandidates, parallelSpeculationSummary = vectorEnabled))
    }
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
    val ls1 = Module(new LoadStorePipeline(loadParams, tlbEnabled, directPhysical, scratchpadEnabled))
    val dcache = Module(new DCache(ramBackend, dcacheParams, tlbEnabled, observe))
    val scratchpad = if (scratchpadEnabled) Some(Module(new PeScratchpadBridge(dcacheParams))) else None
    scratchpad.foreach(_.io.memory <> io.scratchpad.get)
    dcache.io.prefetch.foreach { hints =>
        hints(0) := arith0.io.prefetch
        hints(1) := arith1.io.prefetch
    }
    val atomic = if (atomicEnabled) Module(new AtomicUnit(p, dcacheParams, observe)).io else {
        val disabled = Wire(new AtomicUnitIO(p, dcacheParams, observe))
        disabled.request.ready := false.B
        disabled.response.valid := false.B
        disabled.response.bits := 0.U.asTypeOf(new AtomicResponse(p))
        disabled.writeResult := false.B
        disabled.busy := false.B
        disabled.load.request.valid := false.B
        disabled.load.request.bits := 0.U.asTypeOf(new DLoadRequest(dcacheParams))
        disabled.load.forwardResult := 0.U.asTypeOf(Valid(new DForwardResult(dcacheParams)))
        disabled.store.request.valid := false.B
        disabled.store.request.bits := 0.U.asTypeOf(new DStoreRequest)
        disabled.store.response.ready := false.B
        disabled.debug.foreach(_ := 0.U.asTypeOf(new AtomicUnitDebugIO))
        disabled
    }
    val sharedStore = Module(new SharedStorePort)

    /* Central PRFs provide stable held reads while a downstream execution stage is stalled. */
    val intRfParams = RegfileParams(
        numEntries = p.numIntPhys,
        numReadPorts = if (vectorEnabled) 10 else 9,
        numWritePorts = 5,
        holdReads = true,
    )
    val fpRfParams = RegfileParams(
        numEntries = p.numFpPhys,
        numReadPorts = if (vectorEnabled) 5 else 4,
        numWritePorts = 3,
        hasZeroReg = false,
        holdReads = true,
    )
    val intRf = Module(new Regfile(
        intRfParams,
        directWritePort = Some(2),
        readBypassOverridePorts = Seq(2, 3),
        overrideReadPorts = Set(6, 7),
        selectiveReadBypassPort = Some(4),
        selectiveReadPorts = Set(6, 7, 8),
    ))
    val fpRf = Module(new Regfile(fpRfParams, directWritePort = Some(0)))
    val bypass = Module(new Bypass(BypassParams.twoArithBackend(p)))
    val wakeupRouter = Module(new WakeupRouter(p))
    val loadSpeculation = Module(new LoadSpeculationTracker(p))
    val vectorScalarWrite = WireDefault(0.U.asTypeOf(Valid(new VectorScalarWrite(p))))
    // Vector-to-scalar writes share LS1's existing RF port. Publish their tag
    // from Q after the write, using an idle LS1 WB wakeup slot. One pending tag
    // is sufficient because completion is backpressured while the slot is held.
    val vectorScalarWake = if (vectorEnabled) Some(RegInit(0.U(p.tagWidth.W))) else None
    vectorScalarWake.foreach { pending =>
        when(io.commit.flush) { pending := 0.U }
        .elsewhen(vectorScalarWrite.valid) { pending := vectorScalarWrite.bits.destination }
        .elsewhen(ls1.io.wk.wakeWB.prd === 0.U) { pending := 0.U }
        when(vectorScalarWrite.valid) {
            assert(pending === 0.U || ls1.io.wk.wakeWB.prd === 0.U,
                "A vector scalar wakeup must not overwrite a retained tag")
        }
    }
    val vectorCompute = if (vectorEnabled) Some(Module(new VectorCompute(p, shareSerialMultiply = true))) else None
    val vectorMemory = if (vectorEnabled) Some(Module(new VectorMemory(p, dcacheParams))) else None
    val vectorSerial = if (vectorEnabled) Some(Module(new VectorSerial(p, externalMultiply = true))) else None
    io.throughput.foreach { events =>
        val vectorEvents = vectorCompute.map { compute =>
            val memory=vectorMemory.get
            VecInit(Seq(compute.io.activity(6),memory.io.writeback.valid,
                memory.io.store.fire && !memory.io.store.bits.addressOnly,
                vectorSerial.get.io.executionActive)).asUInt
        }.getOrElse(0.U(4.W))
        events := Cat(vectorEvents(3),mixArith.io.divideBusy,vectorEvents(2,0),
            VecInit(queues.map(_.io.issue.fire)).asUInt)
    }
    vectorCompute.foreach { compute =>
        val port = io.vector.get
        val iq = Module(new IssueQueue(p, IssueQueueParams(vectorParams.computeEntries, IssueQueueProfile.Vector,
            enqueueWidth = 1, wakeupPorts = issueParams.wakeupPorts)))
        val memory = vectorMemory.get
        val serial = vectorSerial.get
        compute.io.serialMultiply.get <> serial.io.multiply.get
        compute.io.serialActive.get := !serial.io.idle
        val memoryIQ = Module(new IssueQueue(p, IssueQueueParams(vectorParams.memoryEntries, IssueQueueProfile.Vector,
            enqueueWidth = 1, wakeupPorts = issueParams.wakeupPorts), orderedIssue = true))
        val ready = Module(new VectorReadyBoard(vectorParams.physicalRegisters))
        val file = Module(new VectorRegisterFile(vectorParams, observe = observe))
        io.vectorObservation.foreach(_ := file.io.observation.get)
        ready.io.allocate := port.allocate
        ready.io.flush := io.commit.flush
        compute.io.registerReady := ready.io.ready
        val arithmeticWrite = Mux(serial.io.writeback.valid, serial.io.writeback, compute.io.writeback)
        ready.io.wakeup(0).valid := arithmeticWrite.valid && arithmeticWrite.bits.ready
        ready.io.wakeup(0).bits := arithmeticWrite.bits.address
        ready.io.wakeup(1).valid := memory.io.writeback.valid
        ready.io.wakeup(1).bits := memory.io.writeback.bits.address
        iq.io.vector.get.ready := ready.io.ready
        iq.io.vector.get.wakeup := ready.io.wakeup
        iq.io.vector.get.enqueue := port.enqueue.bits.vector
        iq.io.enq.valid := port.enqueue.valid &&
            port.enqueue.bits.vector.control.execution =/= VectorExecution.Load.U &&
            port.enqueue.bits.vector.control.execution =/= VectorExecution.Store.U
        iq.io.enq.candidates(0) := port.enqueue.bits.scalar
        iq.io.enq.selection(0) := 1.U
        val memoryInstruction = port.enqueue.bits.vector.control.execution === VectorExecution.Load.U ||
            port.enqueue.bits.vector.control.execution === VectorExecution.Store.U
        memoryIQ.io.vector.get.ready := ready.io.ready
        memoryIQ.io.vector.get.wakeup := ready.io.wakeup
        memoryIQ.io.vector.get.enqueue := port.enqueue.bits.vector
        memoryIQ.io.enq.valid := port.enqueue.valid && memoryInstruction
        memoryIQ.io.enq.candidates(0) := port.enqueue.bits.scalar
        memoryIQ.io.enq.selection(0) := 1.U
        memoryIQ.io.flush := io.commit.flush
        memoryIQ.io.speculation := loadSpeculation.io.resolution
        iq.io.flush := io.commit.flush
        iq.io.speculation := loadSpeculation.io.resolution
        // Certain PRF writes reach this queue through a local Q boundary.
        // Vector collection has no scalar bypass or early-wakeup contract.
        val scalarWrites = Seq(
            arith0.io.rf.write.valid -> Cat(0.U(1.W), arith0.io.rf.write.bits.prd),
            arith1.io.rf.write.valid -> Cat(0.U(1.W), arith1.io.rf.write.bits.prd),
            mixArith.io.rf.intWrite.valid -> Cat(0.U(1.W), mixArith.io.rf.intWrite.bits.addr),
            mixArith.io.rf.fpWrite.valid -> Cat(1.U(1.W), mixArith.io.rf.fpWrite.bits.addr),
            ls0.io.rf.wr.valid -> ls0.io.rf.wr.bits.prd,
            ls1.io.rf.wr.valid -> ls1.io.rf.wr.bits.prd,
            vectorScalarWrite.valid -> vectorScalarWrite.bits.destination,
            false.B -> 0.U(p.tagWidth.W),
        )
        iq.io.wakeup := RegNext(VecInit(scalarWrites.map { case (valid, tag) =>
            val wake = Wire(new BackendWakeup(p))
            wake.prd := Mux(valid, tag, 0.U)
            wake.specMask := 0.U
            wake
        }), 0.U.asTypeOf(Vec(issueParams.wakeupPorts, new BackendWakeup(p))))
        memoryIQ.io.wakeup := iq.io.wakeup
        val serialIssue = iq.io.vector.get.issue.control.execution === VectorExecution.Serial.U
        compute.io.in.valid := iq.io.issue.valid && !serialIssue && serial.io.idle
        compute.io.in.bits.scalar := iq.io.issue.bits
        compute.io.in.bits.vector := iq.io.vector.get.issue
        serial.io.in.valid := iq.io.issue.valid && serialIssue && compute.io.idle
        serial.io.in.bits.scalar := iq.io.issue.bits
        serial.io.in.bits.vector := iq.io.vector.get.issue
        serial.io.flush := io.commit.flush
        serial.io.roundingMode := port.roundingMode
        iq.io.issue.ready := Mux(serialIssue, serial.io.in.ready && compute.io.idle,
            compute.io.in.ready && serial.io.idle)
        compute.io.flush := io.commit.flush
        compute.io.roundingMode := port.roundingMode
        memory.io.in.valid := memoryIQ.io.issue.valid && !io.commit.atomic.blockMemoryIssue
        memory.io.in.bits.scalar := memoryIQ.io.issue.bits
        memory.io.in.bits.vector := memoryIQ.io.vector.get.issue
        memoryIQ.io.issue.ready := memory.io.in.ready && !io.commit.atomic.blockMemoryIssue
        memory.io.flush := io.commit.flush
        memory.io.store <> port.store
        memory.io.ordering <> port.ordering
        // Both collectors reuse all three existing lane read ports. A memory
        // collection lasts one cycle; arithmetic holds its task while denied.
        val memoryReads = memory.io.readRequest
        val serialReads = serial.io.readRequest
        compute.io.readAvailable := !memoryReads && !serialReads
        serial.io.readAvailable := !memoryReads && !memory.io.scalarReadRequest
        memory.io.readAvailable := true.B
        val readOwners = VecInit(Seq(memoryReads, !memoryReads && serialReads,
            !memoryReads && !serialReads))
        for (read <- 0 until 3) {
            file.io.read(read).address := Mux1H(readOwners, Seq(memory.io.read(read).address,
                serial.io.read(read).address, compute.io.read(read).address))
            // Arbitrate six-bit tags before the shared RF decoder. Selecting
            // three 64-bit masks makes the read-owner control drive every row.
            file.io.read(read).oneHot := UIntToOH(file.io.read(read).address, vectorParams.physicalRegisters)
            compute.io.read(read).data := file.io.read(read).data
            memory.io.read(read).data := file.io.read(read).data
            serial.io.read(read).data := file.io.read(read).data
        }
        assert(PopCount(readOwners) === 1.U)
        file.io.write(0).valid := arithmeticWrite.valid
        file.io.write(0).address := arithmeticWrite.bits.address
        file.io.write(0).data := arithmeticWrite.bits.data
        file.io.write(0).bytes := arithmeticWrite.bits.bytes
        assert(!(compute.io.writeback.valid && serial.io.writeback.valid))
        file.io.write(1).valid := memory.io.writeback.valid
        file.io.write(1).address := memory.io.writeback.bits.address
        file.io.write(1).data := memory.io.writeback.bits.data
        file.io.write(1).bytes := memory.io.writeback.bits.bytes
        io.vectorPerformance.foreach { counters =>
            val events = Cat(!compute.io.activity(3) && !iq.io.issue.valid,
                !memory.io.idle,compute.io.activity(5,0))
            for (event <- 0 until 8) {
                val count = RegInit(0.U(64.W))
                when(events(event)) { count := count+1.U }
                counters(event) := count
            }
        }
        port.availableCompute := iq.io.freePrefix(0)
        port.availableMemory := memoryIQ.io.freePrefix(0)
        val scalarWakeAvailable = vectorScalarWake.get === 0.U || ls1.io.wk.wakeWB.prd === 0.U
        val scalarPortAvailable = !compute.io.completion.bits.scalar.valid ||
            (!ls1.io.rf.wr.valid && scalarWakeAvailable)
        val completion = Module(new Arbiter(new VectorCompletion(p), 3))
        completion.io.in(0).valid := compute.io.completion.valid && scalarPortAvailable
        completion.io.in(0).bits := compute.io.completion.bits
        compute.io.completion.ready := completion.io.in(0).ready && scalarPortAvailable
        completion.io.in(1) <> memory.io.completion
        val serialScalarAvailable = !serial.io.completion.bits.scalar.valid ||
            (!ls1.io.rf.wr.valid && scalarWakeAvailable)
        completion.io.in(2).valid := serial.io.completion.valid && serialScalarAvailable
        completion.io.in(2).bits := serial.io.completion.bits
        serial.io.completion.ready := completion.io.in(2).ready && serialScalarAvailable
        port.completion <> completion.io.out
        vectorScalarWrite.valid := port.completion.fire && port.completion.bits.scalar.valid
        vectorScalarWrite.bits := port.completion.bits.scalar.bits
        port.configuration := compute.io.configuration
        val integerScalarAddress = Mux(memory.io.scalarReadRequest, memory.io.scalarAddress,
            Mux(serialReads, serial.io.scalarAddress, compute.io.scalarAddress))
        val floatingScalarAddress = Mux(serialReads, serial.io.scalarAddress, compute.io.scalarAddress)
        intRf.io.read(9).addr := integerScalarAddress(p.physWidth - 1, 0)
        fpRf.io.read(4).addr := Mux(floatingScalarAddress(p.tagWidth - 1),
            floatingScalarAddress(p.physWidth - 1, 0), 0.U)
        compute.io.scalarAvailable := !serialReads &&
            (compute.io.scalarAddress(p.tagWidth - 1) || !memory.io.scalarReadRequest)
        compute.io.scalarData := Mux(compute.io.scalarAddress(p.tagWidth - 1),
            fpRf.io.read(4).data, intRf.io.read(9).data)
        memory.io.scalarData := intRf.io.read(9).data
        serial.io.scalarData := Mux(serial.io.scalarAddress(p.tagWidth - 1),
            fpRf.io.read(4).data, intRf.io.read(9).data)
    }

    /* The indexed Middleend boundary connects homogeneously to the six issue queues. */
    queues.zip(io.middleend.enqueue).foreach { case (queue, enqueue) => queue.io.enq := enqueue }
    io.middleend.freePrefix := VecInit(queues.map(_.io.freePrefix))
    queues.foreach { queue =>
        queue.io.flush := io.commit.flush
        queue.io.speculation := loadSpeculation.io.resolution
    }
    mixArithIQ.io.csrGrant.get := io.commit.csrGrant
    loadIQ.io.loadGrant.get := io.commit.loadGrant
    loadStoreAddressIQ.io.loadGrant.get := io.commit.loadGrant

    /* Queue issue ports map directly to their owning execution resources. */
    arith0.io.iq <> arith0IQ.io.issue
    arith1.io.iq <> arith1IQ.io.issue
    mixArith.io.iq.valid := mixArithIQ.io.issue.valid
    mixArith.io.iq.bits := MixArithIssue.fromBackend(mixArithIQ.io.issue.bits)
    when(mixArithIQ.io.issue.valid) {
        assert(!mixArithIQ.io.issue.bits.imm(31, 17).orR,
            "Decode must provide a 17-bit MixArith immediate")
    }
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
        wakeupRouter.io.loadWB(lane) := (if (lane == 1 && vectorEnabled) Mux(pipe.io.wk.wakeWB.prd.orR,
            pipe.io.wk.wakeWB, certainWake(true.B, vectorScalarWake.get))
            else pipe.io.wk.wakeWB)
    }

    val arithIssueCandidates = VecInit(Seq(
        arith0IQ.io.issueWakeup.get,
        arith1IQ.io.issueWakeup.get,
    ))
    val registeredComputeWakeup = WireDefault(wakeupRouter.io.compute)
    for (lane <- 0 until 2) {
        registeredComputeWakeup(lane) := arithPipes(lane).io.wakeup.wakeRF
    }
    Seq(arith0IQ, arith1IQ, mixArithIQ).foreach { queue =>
        queue.io.wakeup := registeredComputeWakeup
        queue.io.directWakeup.get := arithIssueCandidates
    }
    Seq(loadIQ, loadStoreAddressIQ, storeDataIQ).foreach(_.io.wakeup := wakeupRouter.io.memory)
    // Incoming IQ entries absorb the candidate-level issue wakeup directly.
    // The RF-stage copy preserves readiness for consumers that arrive later.
    io.middleend.wakeup := registeredComputeWakeup
    io.middleend.memoryWakeup := wakeupRouter.io.memory
    io.middleend.loadWakeupBeforeD1 := VecInit(Seq(ls0, ls1).map(_.io.wk.wakeRF.specMask))
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
        queue.io.completed.get.bits.uncache := false.B
        queue.io.retry.get.valid := pipe.io.wk.replay.valid
        queue.io.retry.get.bits.robIdx := pipe.io.wk.replay.bits.robIdx
        queue.io.retry.get.bits.uncache := pipe.io.wk.replay.bits.uncache
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
    ) ++ (if (vectorEnabled) Seq(false.B) else Seq.empty))

    for (source <- 0 until 3) {
        fpRf.io.read(source).addr := mixArith.io.rf.fpRead(source).addr
        mixArith.io.rf.fpRead(source).data := fpRf.io.read(source).data
    }
    fpRf.io.read(3).addr := ls1.io.rf.std.get.fpAddr
    ls1.io.rf.std.get.fpData := fpRf.io.read(3).data
    fpRf.io.readHold.get := VecInit(Seq(false.B, false.B, false.B, ls1.io.rf.std.get.hold) ++
        (if (vectorEnabled) Seq(false.B) else Seq.empty))

    /* Atomic shares LS1's integer write port; the other producers remain fixed. */
    def intWrite(port: Int, valid: Bool, address: UInt, data: UInt): Unit = {
        intRf.io.write(port).we := valid
        intRf.io.write(port).addr := address
        intRf.io.write(port).data := data
    }
    for ((pipe, port) <- arithPipes.zipWithIndex) {
        intWrite(port, pipe.io.rf.write.valid, pipe.io.rf.write.bits.prd, pipe.io.rf.write.bits.data)
    }
    intWrite(2, mixArith.io.rf.intWrite.valid, mixArith.io.rf.intWrite.bits.addr, mixArith.io.rf.intWrite.bits.data)
    intRf.io.readBypassOverride.get(0) := mixArith.io.rf.intReadBypassValid
    intRf.io.readBypassOverrideAddr.get(0) := mixArith.io.rf.intReadBypassAddr
    when(!io.commit.flush) {
        assert(mixArith.io.rf.intReadBypassValid === mixArith.io.rf.intWrite.valid)
        when(mixArith.io.rf.intReadBypassValid) {
            assert(mixArith.io.rf.intReadBypassAddr === mixArith.io.rf.intWrite.bits.addr)
        }
    }
    intRf.io.directWrite.get.oneHot := mixArith.io.rf.intWriteOneHot
    intRf.io.directWrite.get.data := mixArith.io.rf.intWriteData
    intWrite(
        3,
        ls0.io.rf.wr.valid && !ls0.io.rf.wr.bits.prd(p.tagWidth - 1),
        ls0.io.rf.wr.bits.prd(p.physWidth - 1, 0),
        ls0.io.rf.wr.bits.data,
    )
    // The WB tag is registered alongside the response. A flush cancels every
    // new memory request and clears its held-read state, so only the actual
    // PRF write needs flush suppression; the AGU's read bypass can use WB Q.
    val load0BypassTag = ls0.io.wk.wakeWB.prd
    val load0ReadBypass = ls0.io.cache.rsp.valid && load0BypassTag =/= 0.U &&
        !load0BypassTag(p.tagWidth - 1)
    intRf.io.readBypassOverride.get(1) := load0ReadBypass
    intRf.io.readBypassOverrideAddr.get(1) := load0BypassTag(p.physWidth - 1, 0)
    when(!io.commit.flush) {
        assert(load0ReadBypass === intRf.io.write(3).we)
        when(load0ReadBypass) {
            assert(load0BypassTag === ls0.io.rf.wr.bits.prd)
        }
    }
    // AtomicUnit captures the result-write decision alongside its response.
    val atomicIntWrite = atomic.writeResult
    val ls1IntWrite = ls1.io.rf.wr.valid && !ls1.io.rf.wr.bits.prd(p.tagWidth - 1)
    intRf.io.selectiveReadBypass.get.we := ls1IntWrite
    intRf.io.selectiveReadBypass.get.addr := ls1.io.rf.wr.bits.prd(p.physWidth - 1, 0)
    intRf.io.selectiveReadBypass.get.data := ls1.io.rf.wr.bits.data
    intWrite(
        4,
        ls1IntWrite || atomicIntWrite || vectorScalarWrite.valid && !vectorScalarWrite.bits.destination(p.tagWidth - 1),
        Mux(vectorScalarWrite.valid, vectorScalarWrite.bits.destination(p.physWidth - 1, 0), Mux(atomicIntWrite,
            atomic.response.bits.prd(p.physWidth - 1, 0),
            ls1.io.rf.wr.bits.prd(p.physWidth - 1, 0))),
        Mux(vectorScalarWrite.valid, vectorScalarWrite.bits.data,
            Mux(atomicIntWrite, atomic.response.bits.data, ls1.io.rf.wr.bits.data)),
    )
    assert(!(atomicIntWrite && ls1IntWrite), "Atomic completion and LS1 must not share a write cycle")
    when(atomicIntWrite) {
        assert(io.commit.atomic.blockMemoryIssue && ls0.io.idle && ls1.io.idle,
            "Atomic writeback must not overlap a memory operand read")
    }
    fpRf.io.write(0).we := mixArith.io.rf.fpWrite.valid
    fpRf.io.write(0).addr := mixArith.io.rf.fpWrite.bits.addr
    fpRf.io.write(0).data := mixArith.io.rf.fpWrite.bits.data
    fpRf.io.directWrite.get.oneHot := mixArith.io.rf.fpWriteOneHot
    fpRf.io.directWrite.get.data := mixArith.io.rf.fpWriteData
    for ((pipe, port) <- Seq(ls0 -> 1, ls1 -> 2)) {
        val vectorWrite = (port == 2).B && vectorScalarWrite.valid &&
            vectorScalarWrite.bits.destination(p.tagWidth - 1)
        fpRf.io.write(port).we := vectorWrite || pipe.io.rf.wr.valid && pipe.io.rf.wr.bits.prd(p.tagWidth - 1)
        fpRf.io.write(port).addr := Mux(vectorWrite, vectorScalarWrite.bits.destination(p.physWidth - 1, 0),
            pipe.io.rf.wr.bits.prd(p.physWidth - 1, 0))
        fpRf.io.write(port).data := Mux(vectorWrite, vectorScalarWrite.bits.data, pipe.io.rf.wr.bits.data)
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
    val scalarLoadRequest = Wire(new DLoadRequest(dcacheParams))
    scalarLoadRequest := ls1.io.cache.req.bits
    val nativeVector = vectorMemory.map(m =>
        scratchpadEnabled.B && PeScratchpadBridge.matches(m.io.request.bits.paddr)).getOrElse(false.B)
    val vectorRequest = vectorMemory.map(_.io.request.valid).getOrElse(false.B) && !nativeVector
    val preferVector = if (vectorEnabled) Some(RegInit(false.B)) else None
    val loadSelectionLocked = if (vectorEnabled) Some(RegInit(false.B)) else None
    val lockedVectorSelection = if (vectorEnabled) Some(Reg(Bool())) else None
    val vectorGrant = Mux(loadSelectionLocked.getOrElse(false.B),
        lockedVectorSelection.getOrElse(false.B),
        vectorRequest && (!ls1.io.cache.req.valid || preferVector.getOrElse(false.B)))
    preferVector.foreach { preference =>
        when(dcache.io.load(1).req.fire) { preference := !vectorGrant }
        when(io.commit.flush) { loadSelectionLocked.get := false.B }
            .elsewhen(dcache.io.load(1).req.fire) { loadSelectionLocked.get := false.B }
            .elsewhen(dcache.io.load(1).req.valid) {
                loadSelectionLocked.get := true.B
                lockedVectorSelection.get := vectorGrant
            }
    }
    dcache.io.load(1).req.valid := Mux(atomic.busy, atomic.load.request.valid,
        ls1.io.cache.req.valid || vectorRequest)
    dcache.io.load(1).req.bits := Mux(atomic.busy, atomic.load.request.bits,
        vectorMemory.map(memory => Mux(vectorGrant, memory.io.request.bits, scalarLoadRequest))
            .getOrElse(scalarLoadRequest))
    vectorMemory.foreach { memory =>
        val cachedResponse = dcache.io.load(1).rsp.valid && dcache.io.load(1).rsp.bits.wide
        if (scratchpadEnabled) {
            val native = scratchpad.get.io
            native.load.request.valid := memory.io.request.valid && nativeVector
            native.load.request.bits := memory.io.request.bits
            memory.io.request.ready := Mux(nativeVector, native.load.request.ready,
                vectorGrant && dcache.io.load(1).req.ready)
            assert(!(cachedResponse && native.load.response.valid))
            memory.io.response.valid := cachedResponse || native.load.response.valid
            memory.io.response.bits := Mux(native.load.response.valid,
                native.load.response.bits, dcache.io.load(1).rsp.bits)
            memory.io.cachePending := dcache.io.load(1).widePending || native.loadPending
        } else {
            memory.io.request.ready := vectorGrant && dcache.io.load(1).req.ready
            memory.io.response.valid := cachedResponse
            memory.io.response.bits := dcache.io.load(1).rsp.bits
            memory.io.cachePending := dcache.io.load(1).widePending
        }
    }
    atomic.load.request.ready := atomic.busy && dcache.io.load(1).req.ready
    ls1.io.cache.req.ready := !atomic.busy && !vectorGrant && dcache.io.load(1).req.ready
    ls1.io.cache.wbSelect.valid := !dcache.io.load(1).wbSelect.bits.atomic &&
        !dcache.io.load(1).wbSelect.bits.wide && dcache.io.load(1).wbSelect.valid
    ls1.io.cache.wbSelect.bits := dcache.io.load(1).wbSelect.bits
    atomic.load.response.valid := dcache.io.load(1).rsp.bits.atomic && dcache.io.load(1).rsp.valid
    atomic.load.response.bits.data := dcache.io.load(1).rsp.bits.data
    atomic.load.response.bits.exception := dcache.io.load(1).rsp.bits.exception
    atomic.load.response.bits.retry := dcache.io.load(1).rsp.bits.retry
    ls1.io.cache.rsp.valid := !dcache.io.load(1).rsp.bits.atomic &&
        !dcache.io.load(1).rsp.bits.wide && dcache.io.load(1).rsp.valid
    ls1.io.cache.rsp.bits := dcache.io.load(1).rsp.bits
    // Request ready already excludes Atomic ownership, so fixed-latency
    // qualification need not feed Atomic state into speculative wakeup.
    ls1.io.cache.fixedLatency := dcache.io.load(1).fixedLatency
    atomic.load.forwardQuery.valid := atomic.busy && dcache.io.forward(1).query.valid
    atomic.load.forwardQuery.bits := dcache.io.forward(1).query.bits
    ls1.io.cache.forward.query.valid := !atomic.busy && dcache.io.forward(1).query.valid
    ls1.io.cache.forward.query.bits := dcache.io.forward(1).query.bits
    dcache.io.forward(1).result.valid := Mux(
        atomic.busy,
        atomic.load.forwardResult.valid,
        ls1.io.cache.forward.result.valid,
    )
    val scalarForwardResult = Wire(new DForwardResult(dcacheParams))
    scalarForwardResult := ls1.io.cache.forward.result.bits
    dcache.io.forward(1).result.bits := Mux(
        atomic.busy,
        atomic.load.forwardResult.bits,
        scalarForwardResult,
    )
    if (tlbEnabled) {
        dcache.io.storeTranslation.get.request := ls1.io.cache.storeTranslation.get.request
        ls1.io.cache.storeTranslation.get.response := dcache.io.storeTranslation.get.response
        ls1.io.translationControl.get := io.dtlb.get.control
    }
    dcache.io.flush := io.commit.flush
    if (tlbEnabled) {
        dcache.io.tlb.get.control := io.dtlb.get.control
        dcache.io.tlb.get.refill := io.dtlb.get.refill
        dcache.io.tlb.get.flush := io.dtlb.get.flush
        io.dtlbMiss.get := dcache.io.tlbMiss.get
    }
    // Atomic launch requires an empty StoreBuffer, so no committed store can
    // request this port until the atomic unit becomes busy on the next edge.
    when(io.commit.atomic.request.valid && !atomic.busy) {
        assert(!io.commit.store.request.valid)
    }
    sharedStore.io.reserveAtomic := atomic.busy
    sharedStore.io.atomicRequest <> atomic.store.request
    atomic.store.response <> sharedStore.io.atomicResponse
    sharedStore.io.committedRequest <> io.commit.store.request
    io.commit.store.response <> sharedStore.io.committedResponse
    if (scratchpadEnabled) {
        val native = scratchpad.get.io
        val select = sharedStore.io.cacheRequest.bits.wide &&
            PeScratchpadBridge.matches(sharedStore.io.cacheRequest.bits.paddr)
        native.store.req.valid := sharedStore.io.cacheRequest.valid && select
        native.store.req.bits := sharedStore.io.cacheRequest.bits
        dcache.io.store.req.valid := sharedStore.io.cacheRequest.valid && !select
        dcache.io.store.req.bits := sharedStore.io.cacheRequest.bits
        sharedStore.io.cacheRequest.ready := Mux(select,native.store.req.ready,dcache.io.store.req.ready)
        assert(!(native.store.rsp.valid && dcache.io.store.rsp.valid))
        sharedStore.io.cacheResponse.valid := native.store.rsp.valid || dcache.io.store.rsp.valid
        sharedStore.io.cacheResponse.bits := Mux(native.store.rsp.valid,native.store.rsp.bits,dcache.io.store.rsp.bits)
        native.store.rsp.ready := sharedStore.io.cacheResponse.ready
        dcache.io.store.rsp.ready := sharedStore.io.cacheResponse.ready
    } else {
        dcache.io.store.req <> sharedStore.io.cacheRequest
        sharedStore.io.cacheResponse <> dcache.io.store.rsp
    }
    atomic.committedStore.valid := sharedStore.io.committedRequest.fire
    atomic.committedStore.bits := sharedStore.io.committedRequest.bits.paddr
    when(dcache.io.load(1).rsp.valid) {
        assert(
            PopCount(VecInit(Seq(atomic.load.response.valid, ls1.io.cache.rsp.valid,
                (dcache.io.load(1).rsp.bits.wide && vectorEnabled.B)))) === 1.U,
            "DCache lane 1 response must have exactly one owner",
        )
    }
    atomic.request <> io.commit.atomic.request
    io.commit.atomic.response <> atomic.response
    when(io.commit.flush) {
        assert(!atomic.busy, "Commit must not flush an active atomic operation")
    }
    io.l2 <> dcache.io.l2
    io.dcacheIdle := ls0.io.idle && ls1.io.idle && dcache.io.idle && !atomic.busy &&
        scratchpad.map(_.io.idle).getOrElse(true.B)
    dcache.io.maintenance.request := io.maintenance.request
    dcache.io.maintenance.invalidate := io.maintenance.invalidate
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
        io.debug.get.atomicState := atomic.debug.get.state
        io.debug.get.atomicRequestValid := io.commit.atomic.request.valid
        io.debug.get.atomicRequestReady := atomic.request.ready
        io.debug.get.atomicLoadRequestValid := atomic.load.request.valid
        io.debug.get.atomicLoadResponseValid := atomic.load.response.valid
        io.debug.get.atomicStoreRequestValid := atomic.store.request.valid
        io.debug.get.atomicStoreResponseValid := atomic.store.response.valid
        io.debug.get.dcacheIdle := dcache.io.idle
        io.debug.get.dcache := dcache.io.debug.get
    }
}

class BackendDebugIO extends Bundle {
    val atomicState = UInt(3.W)
    val atomicRequestValid = Bool()
    val atomicRequestReady = Bool()
    val atomicLoadRequestValid = Bool()
    val atomicLoadResponseValid = Bool()
    val atomicStoreRequestValid = Bool()
    val atomicStoreResponseValid = Bool()
    val dcacheIdle = Bool()
    val dcache = new DCacheDebugIO
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
