import chisel3._
import chisel3.util._
import ZirconConfig._

/** Backend-facing subset owned by Commit. Its directions match BackendIO. */
class BackendCommitIO(bp: BackendParams, load: LoadPipelineParams) extends Bundle {
    val flush = Input(Bool())
    val csrGrant = Flipped(Valid(new CsrIssueGrant(bp)))
    val loadGrant = Flipped(Valid(new LoadIssueGrant(bp)))
    val arith = Vec(2, new ArithCommitIO)
    val mixRob = new MixArithRobIO
    val mixCSR = new CSRExecutionPort
    val ls0 = new LoadCompletionIO(load, withStore = false)
    val ls1 = new LoadCompletionIO(load, withStore = true)
    val store = Flipped(new StoreBufferCacheIO)
    val atomic = new AtomicBackendIO(bp)
}

/** Architectural CSR inputs and state exported by Commit to the core shell. */
class CommitCSRIO extends Bundle {
    val currentPrivilege = Output(UInt(2.W))
    val time = Input(UInt(64.W))
    val interrupt = Input(new CSRInterrupts)
    val state = Output(new CSRState)
    val tlbFlush = Output(Bool())
}

class CommitIO(
    fp: FrontendParams,
    bp: BackendParams,
    issue: IssueParams,
    load: LoadPipelineParams,
    cp: CommitParams,
) extends Bundle {
    /* Ordered execution and allocation interfaces are grouped by their owner. */
    val frontend = Flipped(new FrontendCommitIO(fp))
    val middleend = Flipped(new CommitMiddleendIO(fp, bp, issue.dispatchWidth, cp.width))
    val backend = Flipped(new BackendCommitIO(bp, load))

    /* CSR state is owned by Commit; the shell supplies only time and interrupts. */
    val csr = new CommitCSRIO

    /* This aggregate includes cache/L2 state and is therefore computed by the shell. */
    val memoryIdle = Input(Bool())

    /* Commit starts cache maintenance required by FENCE.I and SFENCE.VMA. */
    val maintenance = new CacheMaintenanceIO
    val debug = new CommitDebugIO(bp, cp.width)
}

/**
  * Retirement owner for ROB, FTQ, SQ, committed Store buffer and CSR state.
  *
  * A recovery is selected from the three-entry ROB head window, captured, and
  * broadcast one cycle later. The extra cycle removes the ROB-head decision from
  * the Frontend, Middleend, Backend and queue-clear fanout paths.
  */
class Commit(
    val fp: FrontendParams = FrontendParams(),
    val bp: BackendParams = BackendParams(),
    val issue: IssueParams = IssueParams(),
    val load: LoadPipelineParams = LoadPipelineParams(numIntPhys = 72, numFpPhys = 48),
    val cp: CommitParams = CommitParams(),
    val simulationDebug: Boolean = false,
) extends Module {
    require(bp == load.backend)
    val io = IO(new CommitIO(fp, bp, issue, load, cp))

    /* Commit owns all structures whose allocation or removal follows program order. */
    val rob = Module(new ReorderBuffer(fp, bp, issue.dispatchWidth, cp, simulationDebug))
    val ftq = Module(new FetchTargetQueue(fp, issue.dispatchWidth, cp.width))
    val sq = Module(new StoreQueue(fp, bp, load, issue.dispatchWidth, cp))
    val sb = Module(new StoreBuffer(cp, DCacheParams(load.entries)))
    val csr = Module(new CSR)
    val privilege = RegInit(3.U(2.W))
    val delayedRecovery = RegInit(false.B)
    // The middleend clear uses the opposite register polarity so synthesis does
    // not merge its high-fanout source with the frontend/backend clear source.
    val delayedBackendRecovery = RegInit(false.B)
    val delayedMiddleendRecoveryN = RegInit(true.B)
    val delayedMiddleendRecovery = !delayedMiddleendRecoveryN
    val delayedMiddleendRestore = RegInit(false.B)
    val delayedTlbFlush = RegInit(false.B)

    /* Middleend may advance only through the common ROB/SQ/FTQ availability prefix. */
    ftq.io.allocate := io.middleend.ftqAllocate
    io.middleend.ftqIdx := ftq.io.allocateIdx
    if (fp.observe) {
        io.frontend.ftq.used.get := ftq.io.used
    }

    sq.io.request := io.middleend.request
    rob.io.enqueue := io.middleend.enqueue
    sq.io.enqueue := io.middleend.enqueue
    val ftqFree = fp.ftqDepth.U - ftq.io.used
    val ftqAvailable = VecInit((0 until issue.dispatchWidth).map { lane =>
        PopCount((0 to lane).map { previous =>
            io.middleend.request.packetStart(previous)
        }) <= ftqFree
    })
    io.middleend.resourcePrefix := rob.io.availablePrefix & sq.io.availablePrefix & ftqAvailable.asUInt
    for (lane <- 0 until issue.dispatchWidth) {
        io.middleend.allocation(lane).robIdx := rob.io.allocation(lane)
        io.middleend.allocation(lane).sqTail := sq.io.allocation(lane).tail
        io.middleend.allocation(lane).sqIdx := sq.io.allocation(lane).index
    }

    /* Execution pipes use indexed ROB read ports for PCs and branch metadata. */
    rob.io.readIdx(2) := io.backend.mixRob.readIdx
    io.backend.arith.zipWithIndex.foreach { case (port, index) =>
        rob.io.readIdx(index) := port.rob.readIdx
        rob.io.readIdx(3 + index) := port.branch.update.bits.robIdx
        port.rob.pc := rob.io.readPc(index)
    }
    io.backend.mixRob.pc := rob.io.readPc(2)

    /* Normalize heterogeneous execution results into the ROB completion format. */
    def completion(
        valid: Bool,
        robIdx: UInt,
        data: UInt,
        exception: BackendException,
        fflags: UInt = 0.U,
        fpFlagsValid: Bool = false.B,
        mispredicted: Bool = false.B,
    ): Valid[ROBWrite] = {
        val result = Wire(Valid(new ROBWrite(CommitIndex.addressWidth(cp.robEntries))))
        result.valid := valid
        result.bits.address := robIdx(CommitIndex.addressWidth(cp.robEntries) - 1, 0)
        result.bits.data := data
        result.bits.mispredicted := mispredicted
        result.bits.exception := exception
        result.bits.fflags := fflags
        result.bits.fpFlagsValid := fpFlagsValid
        result
    }

    for ((port, index) <- io.backend.arith.zipWithIndex) {
        rob.io.completion(index) := completion(
            port.rob.complete.valid,
            port.rob.complete.bits.robIdx,
            port.rob.complete.bits.data,
            port.rob.complete.bits.exception,
            mispredicted = port.branch.update.valid && port.branch.update.bits.predFail,
        )
    }

    /* Resolved branches update their FTQ packet before retirement produces predictor training. */
    val branchInputs = io.backend.arith.map(_.branch.update)
    for (port <- branchInputs.indices) {
        val input = branchInputs(port)
        ftq.io.commit.branch(port).valid := input.valid
        ftq.io.commit.branch(port).bits.ftqIdxOH := rob.io.readEntry(3 + port).ftqIdxOH
        ftq.io.commit.branch(port).bits.slot := rob.io.readEntry(3 + port).slot
        ftq.io.commit.branch(port).bits.taken := input.bits.taken
        ftq.io.commit.branch(port).bits.target := input.bits.target
        when(input.valid && !delayedBackendRecovery) {
            assert(io.backend.arith(port).rob.complete.valid)
            assert(input.bits.robIdx === io.backend.arith(port).rob.complete.bits.robIdx)
        }
    }
    /* Mix, Load, Store and Atomic producers occupy fixed ROB completion ports. */
    rob.io.completion(2) := completion(
        io.backend.mixRob.complete.valid,
        io.backend.mixRob.complete.bits.robIdx,
        io.backend.mixRob.complete.bits.data,
        io.backend.mixRob.complete.bits.exception,
        io.backend.mixRob.complete.bits.fflags,
        io.backend.mixRob.complete.bits.fpFlagsValid,
    )
    for ((loadPort, completionPort) <- Seq(io.backend.ls0 -> 3, io.backend.ls1 -> 4)) {
        val exception = WireDefault(0.U.asTypeOf(new BackendException))
        exception.valid := loadPort.rob.bits.exception.orR
        exception.cause := loadPort.rob.bits.exception
        exception.tval := loadPort.rob.bits.vaddr
        rob.io.completion(completionPort) := completion(
            loadPort.rob.valid,
            loadPort.rob.bits.robIdx,
            loadPort.rob.bits.data,
            exception,
        )
    }
    rob.io.completion(5) := sq.io.completion(0)
    rob.io.completion(6) := sq.io.completion(1)
    val atomicException = WireDefault(0.U.asTypeOf(new BackendException))
    atomicException.valid := io.backend.atomic.response.bits.exception.orR
    atomicException.cause := io.backend.atomic.response.bits.exception
    atomicException.tval := io.backend.atomic.response.bits.vaddr
    rob.io.completion(7) := completion(
        io.backend.atomic.response.fire,
        io.backend.atomic.response.bits.robIdx,
        io.backend.atomic.response.bits.data,
        atomicException,
    )
    io.backend.atomic.response.ready := true.B

    /* SQ collects speculative stores; SB drains committed stores and supplies byte-wise forwarding. */
    sq.io.address <> io.backend.ls1.storeAddress.get
    sq.io.data <> io.backend.ls1.storeData.get
    for ((loadPort, port) <- Seq(io.backend.ls0 -> 0, io.backend.ls1 -> 1)) {
        sq.io.query(port).request := loadPort.sqQuery
        loadPort.sqResult := sq.io.query(port).response
        sb.io.query(port).request := loadPort.sbQuery
        loadPort.sbResult := sb.io.query(port).response
    }
    sb.io.enqueue <> sq.io.drain
    io.backend.store <> sb.io.store
    /* Retirement stops at the first recovery event and delays its global redirect by one cycle. */
    val trainQueue = Module(new ClusterIndexFIFO(
        new FrontendTraining(fp),
        6,
        cp.width,
        1,
        0,
        0,
        compactEnq = true,
    ))
    val trainStage = Reg(Vec(cp.width, new FrontendTraining(fp)))
    val trainStageValid = RegInit(0.U(cp.width.W))
    val pendingRecoveryTrain = Reg(new FrontendTraining(fp))
    val pendingRecoveryTrainValid = RegInit(false.B)
    for (port <- 0 until cp.width) {
        trainQueue.io.enq(port).valid := Mux(
            pendingRecoveryTrainValid,
            if (port == 0) true.B else false.B,
            trainStageValid(port),
        )
        trainQueue.io.enq(port).bits := Mux(
            pendingRecoveryTrainValid,
            pendingRecoveryTrain,
            trainStage(port),
        )
    }
    val trainStageAccepted = !pendingRecoveryTrainValid &&
        trainStageValid.orR && trainQueue.io.enq(0).ready
    val trainStageReady = !trainStageValid.orR || trainStageAccepted
    val pendingRecoveryTrainAccepted = pendingRecoveryTrainValid && trainQueue.io.enq(0).ready
    trainQueue.io.flush := false.B
    trainQueue.io.deq(0).ready := io.frontend.ftq.train.ready
    io.frontend.ftq.train.valid := trainQueue.io.deq(0).valid
    io.frontend.ftq.train.bits := trainQueue.io.deq(0).bits
    val trainingSpace = trainStageReady && !pendingRecoveryTrainValid
    val headSystem = rob.io.head(0).valid && rob.io.head(0).bits.isSystem
    val headAtomic = rob.io.head(0).valid && rob.io.head(0).bits.isAtomic
    val headSystemOp = rob.io.head(0).bits.systemOp
    val fence = headSystem && headSystemOp === SystemOp.FENCE.U
    val fenceI = headSystem && headSystemOp === SystemOp.FENCE_I.U
    val sfence = headSystem && headSystemOp === SystemOp.SFENCE_VMA.U
    val memoryDrained = sq.io.committedEmpty && sb.io.empty && io.memoryIdle
    io.backend.loadGrant.valid := rob.io.head(0).valid && !rob.io.head(0).bits.complete &&
        memoryDrained && !delayedRecovery
    io.backend.loadGrant.bits.robIdx := rob.io.head(0).bits.robIdx
    sq.io.atomic.sqIdx.valid := headAtomic && !delayedRecovery
    sq.io.atomic.sqIdx.bits := rob.io.head(0).bits.sqIdx
    io.backend.atomic.request.valid := headAtomic && !rob.io.head(0).bits.complete &&
        sq.io.atomic.request.valid && sb.io.empty && io.memoryIdle && !delayedRecovery
    io.backend.atomic.request.bits.robIdx := sq.io.atomic.request.bits.robIdx
    io.backend.atomic.request.bits.prd := sq.io.atomic.request.bits.prd
    io.backend.atomic.request.bits.vaddr := sq.io.atomic.request.bits.vaddr
    io.backend.atomic.request.bits.paddr := sq.io.atomic.request.bits.paddr
    io.backend.atomic.request.bits.data := sq.io.atomic.request.bits.data
    io.backend.atomic.request.bits.op := sq.io.atomic.request.bits.op
    io.backend.atomic.request.bits.uncache := sq.io.atomic.request.bits.uncache
    io.backend.atomic.request.bits.exception := sq.io.atomic.request.bits.exception
    io.backend.atomic.blockMemoryIssue := headAtomic && sq.io.atomic.request.valid && !delayedRecovery
    val maintenanceStarted = RegInit(false.B)
    val maintenanceInvalidate = RegInit(false.B)
    val cacheMaintenance = fenceI || sfence
    when(!cacheMaintenance || delayedRecovery) {
        maintenanceStarted := false.B
        maintenanceInvalidate := false.B
    }.elsewhen(memoryDrained) {
        maintenanceStarted := true.B
        when(!maintenanceStarted) { maintenanceInvalidate := fenceI }
    }
    // Once maintenance starts, its registered kind owns the request until the
    // recovery edge. ROB head decode must not feed the cache/frontend flush tree.
    io.maintenance.request := maintenanceStarted && !delayedRecovery
    io.maintenance.invalidate := maintenanceInvalidate
    val systemReadyNow = Mux(
        cacheMaintenance,
        maintenanceStarted && io.maintenance.done,
        Mux(fence, memoryDrained, true.B),
    )
    val systemReadyValid = RegInit(false.B)
    val systemReadyRobIdx = Reg(UInt(bp.robWidth.W))
    when(delayedRecovery || !headSystem) {
        systemReadyValid := false.B
    }.otherwise {
        systemReadyValid := systemReadyNow
        when(systemReadyNow) { systemReadyRobIdx := rob.io.head(0).bits.robIdx }
    }
    val systemReady = !headSystem ||
        (systemReadyValid && systemReadyRobIdx === rob.io.head(0).bits.robIdx)
    /* ROB entries retain their FTQ identity; the ordered head window validates it. */
    val selectedFtq = Wire(Vec(cp.width, new FrontendFtqEntry(fp)))
    val selectedFtqIdx = Wire(Vec(cp.width, UInt(fp.ftqBits.W)))
    val selectedFtqValid = Wire(Vec(cp.width, Bool()))
    val headMatchesFtq = Wire(Vec(cp.width, Bool()))
    for (lane <- 0 until cp.width) {
        ftq.io.commit.readIndexOH(lane) := rob.io.head(lane).bits.ftqIdxOH
        val packetRank = if (lane == 0) 0.U else PopCount((0 until lane).map { previous =>
            rob.io.head(previous).valid && rob.io.head(previous).bits.packetEnd
        })
        val hits = (0 until cp.width).map(packet => packetRank === packet.U)
        selectedFtq(lane) := ftq.io.commit.read(lane)
        selectedFtqIdx(lane) := rob.io.head(lane).bits.ftqIdx
        val expectedFtqIdx = Mux1H(hits, ftq.io.commit.headIdx)
        selectedFtqValid(lane) := Mux1H(hits, ftq.io.commit.head.map(_.valid))
        headMatchesFtq(lane) := selectedFtqValid(lane) &&
            expectedFtqIdx === rob.io.head(lane).bits.ftqIdx
    }
    val retire = Wire(Vec(cp.width, Bool()))
    val recovery = Wire(Vec(cp.width, Bool()))
    val recoveryCandidate = Wire(Vec(cp.width, Bool()))
    val packetEnd = Wire(Vec(cp.width, Bool()))
    def writesSatpFields(isSystem: Bool, systemOp: UInt, instruction: UInt): Bool = {
        val unconditional = systemOp === SystemOp.CSRRW.U || systemOp === SystemOp.CSRRWI.U
        val conditional = systemOp === SystemOp.CSRRS.U || systemOp === SystemOp.CSRRC.U ||
            systemOp === SystemOp.CSRRSI.U || systemOp === SystemOp.CSRRCI.U
        isSystem && instruction(31, 20) === CSRAddress.satp.U &&
            (unconditional || (conditional && instruction(19, 15).orR))
    }
    def writesSatp(entry: ROBEntry): Bool = writesSatpFields(entry.isSystem, entry.systemOp, entry.instruction)
    def writesExecutionState(entry: ROBEntry): Bool = {
        val unconditional = entry.systemOp === SystemOp.CSRRW.U || entry.systemOp === SystemOp.CSRRWI.U
        val conditional = entry.systemOp === SystemOp.CSRRS.U || entry.systemOp === SystemOp.CSRRC.U ||
            entry.systemOp === SystemOp.CSRRSI.U || entry.systemOp === SystemOp.CSRRCI.U
        val writes = unconditional || (conditional && entry.instruction(19, 15).orR)
        val address = entry.instruction(31, 20)
        val affectsExecution = address === CSRAddress.fflags.U || address === CSRAddress.frm.U ||
            address === CSRAddress.fcsr.U || address === CSRAddress.sstatus.U ||
            address === CSRAddress.satp.U || address === CSRAddress.mstatus.U
        entry.isSystem && writes && affectsExecution
    }
    var continue = true.B
    for (lane <- 0 until cp.width) {
        val entry = rob.io.head(lane).bits
        val commitSystem = entry.isSystem &&
            (entry.systemOp <= SystemOp.FENCE_I.U || entry.systemOp >= SystemOp.ECALL.U)
        val ecall = entry.isSystem && entry.systemOp === SystemOp.ECALL.U
        val ebreak = entry.isSystem && entry.systemOp === SystemOp.EBREAK.U
        val mretIllegal = entry.isSystem && entry.systemOp === SystemOp.MRET.U && privilege =/= 3.U
        val sretIllegal = entry.isSystem && entry.systemOp === SystemOp.SRET.U &&
            !(privilege === 3.U || (privilege === 1.U && !csr.io.state.mstatus(22)))
        val systemException = ecall || ebreak || mretIllegal || sretIllegal
        recoveryCandidate(lane) := entry.exception.valid || entry.mispredicted || commitSystem ||
            writesExecutionState(entry) || entry.isAtomic || systemException
        val needsFeedback = entry.packetEnd || recoveryCandidate(lane)
        val completed = rob.io.head(lane).valid && entry.complete &&
            (if (lane == 0) systemReady else !headSystem && !entry.isSystem) &&
            (!needsFeedback || trainingSpace)
        val recover = completed && recoveryCandidate(lane)
        packetEnd(lane) := completed && entry.packetEnd
        recovery(lane) := continue && recover
        retire(lane) := continue && completed && !entry.exception.valid && !systemException
        continue = continue && completed && !recover && !entry.isSystem && !entry.isAtomic
    }
    val takeInterrupt = Wire(Bool())
    val retireFire = VecInit(retire.map(_ && !delayedRecovery && !takeInterrupt))
    rob.io.pop := retireFire.asUInt

    /* Interrupt selection shares the recovery path and obeys privilege delegation. */
    val interruptBits = csr.io.state.mip & csr.io.state.mie
    val machineInterruptEnabled = privilege =/= 3.U || csr.io.state.mstatus(3)
    val supervisorInterruptEnabled = privilege === 0.U || (privilege === 1.U && csr.io.state.mstatus(1))
    val interruptCandidates = Seq(11, 3, 7, 9, 1, 5)
    val interruptEligible = VecInit(interruptCandidates.map { cause =>
        val delegated = csr.io.state.mideleg(cause)
        interruptBits(cause) && Mux(
            delegated && privilege =/= 3.U,
            supervisorInterruptEnabled,
            machineInterruptEnabled && !delegated,
        )
    })
    // Interrupts are already excluded while a System instruction is at the
    // head. Recompute the non-System recovery window without Fence/atomic drain
    // readiness so those mutually exclusive conditions cannot select payload.
    val interruptRecovery = Wire(Vec(cp.width, Bool()))
    var interruptContinue = true.B
    for (lane <- 0 until cp.width) {
        val entry = rob.io.head(lane).bits
        val needsFeedback = entry.packetEnd || recoveryCandidate(lane)
        val completed = rob.io.head(lane).valid && entry.complete &&
            !entry.isSystem && (!needsFeedback || trainingSpace)
        val recover = completed && recoveryCandidate(lane)
        interruptRecovery(lane) := interruptContinue && recover
        interruptContinue = interruptContinue && completed && !recover && !entry.isAtomic
    }
    takeInterrupt := interruptEligible.asUInt.orR && rob.io.head(0).valid &&
        !interruptRecovery.asUInt.orR && !delayedRecovery && !headSystem && !headAtomic
    val interruptCause = Mux1H(interruptEligible, interruptCandidates.map(_.U(5.W)))
    val recoveryValid = (recovery.asUInt.orR || takeInterrupt) && !delayedRecovery
    // Payload selection is independent of readiness. recoveryValid qualifies its
    // use, so memory drain and atomic state need not enter every recovery data bit.
    val recoveryPayloadSelect = PriorityEncoderOH(
        recoveryCandidate.asUInt & VecInit(rob.io.head.map(_.valid)).asUInt
    )
    val recoverySlot = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.slot))
    val recoveryPc = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.pc))
    val recoveryIsSystem = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.isSystem))
    val recoverySystemOp = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.systemOp))
    val recoveryInstruction = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.instruction))
    val recoveryExceptionValid = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.exception.valid))
    val recoveryExceptionCause = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.exception.cause))
    val recoveryExceptionTval = Mux1H(recoveryPayloadSelect, rob.io.head.map(_.bits.exception.tval))
    val recoveryFtq = Mux1H(recoveryPayloadSelect, selectedFtq)
    val recoverySelectedFtqIdx = Mux1H(recoveryPayloadSelect, selectedFtqIdx)
    val recoverySlotOH = UIntToOH(recoverySlot, fp.fetchWidth)
    val candidateSlotOH = rob.io.head.map(head => UIntToOH(head.bits.slot, fp.fetchWidth))
    val candidateTaken = (0 until cp.width).map { lane =>
        (selectedFtq(lane).taken & candidateSlotOH(lane)).orR
    }
    val candidateTarget = (0 until cp.width).map { lane =>
        Mux1H(candidateSlotOH(lane).asBools, selectedFtq(lane).targets)
    }
    val recoveryTaken = Mux1H(recoveryPayloadSelect, candidateTaken)
    val recoveryTarget = Mux1H(recoveryPayloadSelect, candidateTarget)
    val recoveryRetirement = Wire(new FrontendRetirement(fp))
    val recoveryMask = recoveryFtq.record.train.mask & VecInit.tabulate(fp.fetchWidth) { slot =>
        slot.U <= recoverySlot
    }.asUInt
    recoveryRetirement.ftqIdx := recoverySelectedFtqIdx
    recoveryRetirement.mask := recoveryMask
    recoveryRetirement.taken := recoveryFtq.taken & recoveryMask
    for (slot <- 0 until fp.fetchWidth) {
        recoveryRetirement.targets(slot) := Mux(
            recoveryFtq.resolved(slot),
            recoveryFtq.targets(slot),
            0.U,
        )
    }
    recoveryRetirement.nextPc := 0.U

    val delayedRetireFtq = RegInit(false.B)
    val delayedRecoveryBase = Reg(UInt(32.W))
    val delayedRecoveryAddFour = Reg(Bool())
    val delayedRetirement = Reg(new FrontendRetirement(fp))
    val delayedFtq = Reg(new FrontendFtqEntry(fp))
    val selectedPc = Mux(takeInterrupt, rob.io.head(0).bits.pc, recoveryPc)
    val selectedIsSystem = Mux(takeInterrupt, rob.io.head(0).bits.isSystem, recoveryIsSystem)
    val selectedSystemOp = Mux(takeInterrupt, rob.io.head(0).bits.systemOp, recoverySystemOp)
    val selectedInstruction = Mux(takeInterrupt, rob.io.head(0).bits.instruction, recoveryInstruction)
    val selectedExceptionValid = Mux(takeInterrupt, rob.io.head(0).bits.exception.valid, recoveryExceptionValid)
    val selectedExceptionCause = Mux(takeInterrupt, rob.io.head(0).bits.exception.cause, recoveryExceptionCause)
    val selectedExceptionTval = Mux(takeInterrupt, rob.io.head(0).bits.exception.tval, recoveryExceptionTval)
    def selectedSystemKind(op: UInt): Bool = Mux(
        takeInterrupt,
        rob.io.head(0).bits.isSystem && rob.io.head(0).bits.systemOp === op,
        Mux1H(recoveryPayloadSelect, rob.io.head.map { head =>
            head.bits.isSystem && head.bits.systemOp === op
        }),
    )
    val selectedEcall = selectedSystemKind(SystemOp.ECALL.U)
    val selectedEbreak = selectedSystemKind(SystemOp.EBREAK.U)
    val selectedMret = selectedSystemKind(SystemOp.MRET.U)
    val selectedSret = selectedSystemKind(SystemOp.SRET.U)
    val selectedMretIllegal = selectedMret && privilege =/= 3.U
    val selectedSretIllegal = selectedSret &&
        !(privilege === 3.U || (privilege === 1.U && !csr.io.state.mstatus(22)))
    val selectedSystemException = selectedEcall || selectedEbreak || selectedMretIllegal || selectedSretIllegal
    val trapCause = Mux(
        takeInterrupt,
        Cat(1.U(1.W), 0.U(26.W), interruptCause),
        Mux(
            selectedEcall,
            Mux(privilege === 0.U, 8.U, Mux(privilege === 1.U, 9.U, 11.U)),
            Mux(selectedEbreak, 3.U, Mux(selectedMretIllegal || selectedSretIllegal, 2.U, selectedExceptionCause)),
        ),
    )
    val trap = takeInterrupt || selectedExceptionValid || selectedSystemException
    val delegatedInterrupt = Mux1H(
        VecInit.tabulate(32)(cause => interruptCause === cause.U),
        csr.io.state.mideleg.asBools,
    )
    val delegatedException = Mux1H(
        VecInit.tabulate(32)(cause => trapCause(4, 0) === cause.U),
        csr.io.state.medeleg.asBools,
    )
    val delegatedTrap = privilege =/= 3.U && Mux(takeInterrupt, delegatedInterrupt, delegatedException)
    val trapVector = Mux(delegatedTrap, csr.io.state.stvec, csr.io.state.mtvec)
    val vectorBase = Cat(trapVector(31, 2), 0.U(2.W))
    val vectorOffset = Cat(0.U(25.W), trapCause(4, 0), 0.U(2.W))
    val trapTarget = Mux(takeInterrupt && trapVector(1, 0) === 1.U, vectorBase + vectorOffset, vectorBase)
    val returnTarget = Mux(selectedSret, csr.io.state.sepc, csr.io.state.mepc)

    delayedRecovery := recoveryValid
    delayedBackendRecovery := recoveryValid
    delayedMiddleendRecoveryN := !recoveryValid
    delayedTlbFlush := recoveryValid &&
        (sfence || (writesSatpFields(selectedIsSystem, selectedSystemOp, selectedInstruction) && !trap))
    // Rename restoration follows the global clear so it sees the committed map
    // and free-list tail after the final registered retirement update.
    delayedMiddleendRestore := delayedMiddleendRecovery
    delayedRetireFtq := recoveryValid && !trap && !takeInterrupt
    delayedRecoveryBase := Mux(
        trap,
        trapTarget,
        Mux(
            selectedMret || selectedSret,
            returnTarget,
            Mux(recoveryTaken, recoveryTarget, selectedPc),
        ),
    )
    delayedRecoveryAddFour := !trap && !selectedMret && !selectedSret && !recoveryTaken
    delayedRetirement := recoveryRetirement
    delayedFtq := recoveryFtq
    val delayedSequentialPc = BLevelPAdder32.sum(delayedRecoveryBase, 4.U, 0.U)
    val delayedRecoveryPc = Mux(delayedRecoveryAddFour, delayedSequentialPc, delayedRecoveryBase)

    /* Completed packets use their ROB-owned FTQ index; predictor feedback crosses a register boundary. */
    val normalPacketEnd = Wire(Vec(cp.width, Bool()))
    val normalRetirements = Wire(Vec(cp.width, new FrontendRetirement(fp)))
    val ftqPop = Wire(Vec(cp.width, Bool()))
    val normalFeedback = Seq.fill(cp.width)(Module(new CommitRecovery(fp)))
    def connectFeedback(
        feedback: CommitRecovery,
        valid: Bool,
        retirement: FrontendRetirement,
        entry: FrontendFtqEntry,
    ): Unit = {
        feedback.io.valid := valid
        feedback.io.retire.mask := retirement.mask
        feedback.io.retire.taken := retirement.taken
        feedback.io.retire.targets := retirement.targets
        feedback.io.retire.nextPc := retirement.nextPc
        feedback.io.record.pcWord := entry.record.train.pcWord
        feedback.io.record.kinds := entry.record.train.kinds
        feedback.io.record.meta := entry.record.train.meta
        feedback.io.record.earlyDirections := entry.record.train.earlyDirections
    }
    for (lane <- 0 until cp.width) {
        val ftqEntry = selectedFtq(lane)
        normalPacketEnd(lane) := retireFire(lane) && packetEnd(lane) && !recovery(lane)
        normalRetirements(lane).ftqIdx := selectedFtqIdx(lane)
        normalRetirements(lane).mask := ftqEntry.record.train.mask
        normalRetirements(lane).taken := ftqEntry.taken & normalRetirements(lane).mask
        normalRetirements(lane).nextPc := ftqEntry.record.nextPc
        for (slot <- 0 until fp.fetchWidth) {
            normalRetirements(lane).targets(slot) := Mux(
                ftqEntry.resolved(slot),
                ftqEntry.targets(slot),
                0.U,
            )
        }
        connectFeedback(
            normalFeedback(lane),
            normalPacketEnd(lane),
            normalRetirements(lane),
            ftqEntry,
        )
    }
    val ftqPopCount = PopCount(normalPacketEnd)
    for (packet <- 0 until cp.width) { ftqPop(packet) := ftqPopCount > packet.U }
    ftq.io.commit.pop := ftqPop.asUInt

    val delayedFeedback = Module(new CommitRecovery(fp))
    val delayedFeedbackRetirement = WireDefault(delayedRetirement)
    delayedFeedbackRetirement.nextPc := delayedRecoveryPc
    connectFeedback(
        delayedFeedback,
        delayedRecovery && delayedRetireFtq,
        delayedFeedbackRetirement,
        delayedFtq,
    )

    /* Compact normal events before the register; recovery remains an independent event. */
    require(cp.width == 3, "frontend predictor state retirement is three packets wide")
    val normalStateValid = VecInit(normalFeedback.map(_.io.state.valid))
    val compactNormalState = Wire(Vec(cp.width, Valid(new FrontendStateEvent(fp))))
    compactNormalState(0).valid := normalStateValid.asUInt.orR
    compactNormalState(0).bits := Mux(
        normalStateValid(0),
        normalFeedback(0).io.state.bits,
        Mux(normalStateValid(1), normalFeedback(1).io.state.bits, normalFeedback(2).io.state.bits),
    )
    compactNormalState(1).valid :=
        (normalStateValid(0) && normalStateValid(1)) ||
        (normalStateValid(0) && normalStateValid(2)) ||
        (normalStateValid(1) && normalStateValid(2))
    compactNormalState(1).bits := Mux(
        normalStateValid(0) && normalStateValid(1),
        normalFeedback(1).io.state.bits,
        normalFeedback(2).io.state.bits,
    )
    compactNormalState(2).valid := normalStateValid.asUInt.andR
    compactNormalState(2).bits := normalFeedback(2).io.state.bits
    val delayedNormalStateValid = RegInit(0.U(cp.width.W))
    val delayedNormalState = Reg(Vec(cp.width, new FrontendStateEvent(fp)))
    delayedNormalStateValid := VecInit(compactNormalState.map(_.valid)).asUInt
    delayedNormalState := VecInit(compactNormalState.map(_.bits))
    val delayedNormalCount = PopCount(delayedNormalStateValid)
    for (port <- 0 until cp.width) {
        io.frontend.ftq.retire(port).valid := delayedNormalStateValid(port)
        io.frontend.ftq.retire(port).bits := delayedNormalState(port)
    }
    io.frontend.ftq.recovery := delayedFeedback.io.state
    when(delayedRecovery) {
        assert(delayedNormalCount + delayedFeedback.io.state.valid <= cp.width.U)
    }

    val recoveryTrain = delayedFeedback.io.train.valid
    val normalTrainValid = VecInit(normalFeedback.map(_.io.train.valid))
    when(trainStageReady) {
        trainStageValid := normalTrainValid.asUInt
        for (lane <- 0 until cp.width) {
            // Validity controls observability; an invalid payload may be overwritten.
            trainStage(lane) := normalFeedback(lane).io.train.bits
        }
    }
    when(pendingRecoveryTrainAccepted) {
        pendingRecoveryTrainValid := false.B
    }
    when(recoveryTrain) {
        pendingRecoveryTrain := delayedFeedback.io.train.bits
        pendingRecoveryTrainValid := true.B
    }
    assert(!(pendingRecoveryTrainValid && recoveryTrain))

    ftq.io.commit.flush := delayedRecovery
    io.frontend.rob.redirect.valid := delayedRecovery
    io.frontend.rob.redirect.bits.pc := delayedRecoveryPc

    /* Rename release is registered so recovery restores after the final retirement update. */
    val retireDestinations = RegInit(VecInit.fill(cp.width)(0.U.asTypeOf(Valid(new MiddleendCommitDestination(bp)))))
    for (lane <- 0 until cp.width) {
        val destination = rob.io.head(lane).bits.destination
        retireDestinations(lane).valid := retireFire(lane) && destination.writesPhysical
        when(retireFire(lane)) { retireDestinations(lane).bits := rob.io.head(lane).bits.destination }
        io.middleend.retire(lane) := retireDestinations(lane)
        sq.io.commit(lane).valid := retireFire(lane) &&
            (rob.io.head(lane).bits.isStore || rob.io.head(lane).bits.isAtomic)
        sq.io.commit(lane).bits := rob.io.head(lane).bits.sqIdx
    }

    rob.io.clear := delayedRecovery
    sq.io.flush := delayedRecovery
    io.middleend.flush := delayedMiddleendRecovery
    io.middleend.restore := delayedMiddleendRestore
    io.backend.flush := delayedBackendRecovery

    /* CSR execution is serialized at the ROB head; architectural CSR effects commit here. */
    val csrGrantValid = RegNext(
        rob.io.head(0).valid && !rob.io.head(0).bits.complete && rob.io.head(0).bits.isSystem && !delayedRecovery,
        false.B,
    )
    val csrGrantIndex = RegEnable(rob.io.head(0).bits.robIdx, rob.io.head(0).valid && rob.io.head(0).bits.isSystem)
    io.backend.csrGrant.valid := csrGrantValid && !delayedRecovery
    io.backend.csrGrant.bits.robIdx := csrGrantIndex
    io.backend.mixCSR.rsp := csr.io.rsp
    io.backend.mixCSR.frm := csr.io.state.frm
    csr.io.req := io.backend.mixCSR.req
    csr.io.commit := io.backend.mixCSR.commit
    csr.io.privilege := privilege
    val ordinaryRetired = PopCount(retireFire.zip(rob.io.head).map { case (fire, entry) =>
        fire && !entry.bits.isSystem
    })
    val delayedOrdinaryRetired = RegNext(ordinaryRetired, 0.U)
    csr.io.retired := Mux(
        io.backend.mixCSR.commit,
        Mux(csr.io.rsp.bits.illegal, 0.U, 1.U),
        delayedOrdinaryRetired,
    )
    csr.io.time := io.csr.time
    csr.io.interrupt := io.csr.interrupt
    csr.io.fp.valid := retireFire.zip(rob.io.head).map { case (fire, entry) =>
        fire && entry.bits.fpDirty
    }.reduce(_ || _)
    csr.io.fp.bits.flags := retireFire.zip(rob.io.head).map { case (fire, entry) =>
        Mux(fire && entry.bits.fpFlagsValid, entry.bits.fflags, 0.U)
    }.reduce(_ | _)
    csr.io.fp.bits.dirty := csr.io.fp.valid
    csr.io.trap.valid := recoveryValid && trap
    csr.io.trap.bits.supervisor := delegatedTrap
    csr.io.trap.bits.pcWord := selectedPc(31, 2)
    csr.io.trap.bits.cause := trapCause
    csr.io.trap.bits.tval := Mux(
        takeInterrupt || selectedEcall,
        0.U,
        Mux(
            selectedEbreak,
            selectedPc,
            Mux(selectedMretIllegal || selectedSretIllegal, selectedInstruction, selectedExceptionTval),
        ),
    )
    csr.io.xret.valid := recoveryValid && !trap && (selectedMret || selectedSret)
    csr.io.xret.bits := selectedSret
    when(csr.io.trap.valid) {
        privilege := Mux(delegatedTrap, 1.U, 3.U)
    }.elsewhen(csr.io.xret.valid) {
        privilege := Mux(
            selectedSret,
            Mux(csr.io.state.mstatus(8), 1.U, 0.U),
            csr.io.state.mstatus(12, 11),
        )
    }
    // SATP changes become visible only after all younger work is discarded and the
    // frontend redirects to the next instruction under the new translation regime.
    io.csr.tlbFlush := delayedTlbFlush
    io.csr.currentPrivilege := privilege
    io.csr.state := csr.io.state

    when(recoveryValid && !takeInterrupt) {
        assert(PopCount(recovery) === 1.U)
    }
    when(csr.io.trap.valid) {
        assert(!retireFire.asUInt.orR, "Trap acceptance and instruction retirement must not share a cycle")
    }
    for (lane <- 0 until cp.width) {
        when(rob.io.head(lane).valid && !delayedRecovery) {
            assert(headMatchesFtq(lane), "ROB and FTQ retirement order must match")
        }
    }
    /* Simulation trace and performance counters stay after all retirement logic. */
    val branchCount = RegInit(0.U(64.W))
    val branchFailCount = RegInit(0.U(64.W))
    val directJumpCount = RegInit(0.U(64.W))
    val directJumpFailCount = RegInit(0.U(64.W))
    val callCount = RegInit(0.U(64.W))
    val callFailCount = RegInit(0.U(64.W))
    val retCount = RegInit(0.U(64.W))
    val retFailCount = RegInit(0.U(64.W))
    val indirectCount = RegInit(0.U(64.W))
    val indirectFailCount = RegInit(0.U(64.W))
    val storeBufferFullCycles = RegInit(0.U(64.W))
    val storeBufferBusyCycles = RegInit(0.U(64.W))
    val retiredBranch = VecInit(retireFire.zip(rob.io.head).map { case (fire, entry) =>
        fire && entry.bits.instruction(6, 0) === "h63".U
    })
    val retiredCall = VecInit(retireFire.zip(rob.io.head).map { case (fire, entry) =>
        val instruction = entry.bits.instruction
        val rd = instruction(11, 7)
        fire && (instruction(6, 0) === "h6f".U || instruction(6, 0) === "h67".U) &&
            (rd === 1.U || rd === 5.U)
    })
    val retiredDirectJump = VecInit(retireFire.zip(rob.io.head).map { case (fire, entry) =>
        val instruction = entry.bits.instruction
        val rd = instruction(11, 7)
        fire && instruction(6, 0) === "h6f".U && rd =/= 1.U && rd =/= 5.U
    })
    val retiredRet = VecInit(retireFire.zip(rob.io.head).map { case (fire, entry) =>
        val instruction = entry.bits.instruction
        fire && instruction(6, 0) === "h67".U && instruction(11, 7) === 0.U &&
            (instruction(19, 15) === 1.U || instruction(19, 15) === 5.U)
    })
    val retiredIndirect = VecInit(retireFire.zip(rob.io.head).map { case (fire, entry) =>
        val instruction = entry.bits.instruction
        val rd = instruction(11, 7)
        val rs1 = instruction(19, 15)
        val call = rd === 1.U || rd === 5.U
        val ret = rd === 0.U && (rs1 === 1.U || rs1 === 5.U)
        fire && instruction(6, 0) === "h67".U && !call && !ret
    })
    val retiredPredictionFail = VecInit(retireFire.zip(rob.io.head).map {
        case (fire, entry) => fire && entry.bits.mispredicted
    })
    branchCount := branchCount + PopCount(retiredBranch)
    branchFailCount := branchFailCount + PopCount(retiredBranch.asUInt & retiredPredictionFail.asUInt)
    directJumpCount := directJumpCount + PopCount(retiredDirectJump)
    directJumpFailCount := directJumpFailCount + PopCount(retiredDirectJump.asUInt & retiredPredictionFail.asUInt)
    callCount := callCount + PopCount(retiredCall)
    callFailCount := callFailCount + PopCount(retiredCall.asUInt & retiredPredictionFail.asUInt)
    retCount := retCount + PopCount(retiredRet)
    retFailCount := retFailCount + PopCount(retiredRet.asUInt & retiredPredictionFail.asUInt)
    indirectCount := indirectCount + PopCount(retiredIndirect)
    indirectFailCount := indirectFailCount + PopCount(retiredIndirect.asUInt & retiredPredictionFail.asUInt)
    when(sb.io.enqueue.valid && !sb.io.enqueue.ready) { storeBufferFullCycles := storeBufferFullCycles + 1.U }
    when(!sb.io.empty) { storeBufferBusyCycles := storeBufferBusyCycles + 1.U }

    for (lane <- 0 until cp.width) {
        val entry = rob.io.head(lane).bits
        io.debug.retire(lane).valid := retireFire(lane)
        io.debug.retire(lane).pc := entry.pc
        io.debug.retire(lane).instruction := entry.instruction
        io.debug.retire(lane).mispredicted := retiredPredictionFail(lane)
        io.debug.retire(lane).rd := entry.destination.rd
        io.debug.retire(lane).isFp := entry.destination.isFp
        io.debug.retire(lane).writeValid := entry.destination.writesPhysical
        io.debug.retire(lane).value := (if (simulationDebug) rob.io.headData.get(lane) else 0.U)
    }
    io.debug.robHeadValid := rob.io.head(0).valid
    io.debug.robHeadComplete := rob.io.head(0).bits.complete
    io.debug.robHeadPc := rob.io.head(0).bits.pc
    io.debug.trap.valid := csr.io.trap.valid
    io.debug.trap.bits.epc := Cat(csr.io.trap.bits.pcWord, 0.U(2.W))
    io.debug.trap.bits.cause := csr.io.trap.bits.cause
    io.debug.trap.bits.tval := csr.io.trap.bits.tval
    io.debug.trap.bits.targetPrivilege := Mux(csr.io.trap.bits.supervisor, 1.U, 3.U)
    io.debug.performance.branch := branchCount
    io.debug.performance.branchFail := branchFailCount
    io.debug.performance.directJump := directJumpCount
    io.debug.performance.directJumpFail := directJumpFailCount
    io.debug.performance.call := callCount
    io.debug.performance.callFail := callFailCount
    io.debug.performance.ret := retCount
    io.debug.performance.retFail := retFailCount
    io.debug.performance.indirect := indirectCount
    io.debug.performance.indirectFail := indirectFailCount
    io.debug.performance.robFullCycles := rob.io.fullCycles
    io.debug.performance.storeBufferFullCycles := storeBufferFullCycles
    io.debug.performance.storeBufferBusyCycles := storeBufferBusyCycles

}

class CommitRetireTrace(bp: BackendParams) extends Bundle {
    val valid = Bool()
    val pc = UInt(32.W)
    val instruction = UInt(32.W)
    val mispredicted = Bool()
    val rd = UInt(5.W)
    val isFp = Bool()
    val writeValid = Bool()
    val value = UInt(32.W)
}

class CommitTrapTrace extends Bundle {
    val epc = UInt(32.W)
    val cause = UInt(32.W)
    val tval = UInt(32.W)
    val targetPrivilege = UInt(2.W)
}

class CommitPerformanceCounters extends Bundle {
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
    val robFullCycles = UInt(64.W)
    val storeBufferFullCycles = UInt(64.W)
    val storeBufferBusyCycles = UInt(64.W)
}

class CommitDebugIO(bp: BackendParams, width: Int) extends Bundle {
    val retire = Output(Vec(width, new CommitRetireTrace(bp)))
    val robHeadValid = Output(Bool())
    val robHeadComplete = Output(Bool())
    val robHeadPc = Output(UInt(32.W))
    val trap = Output(Valid(new CommitTrapTrace))
    val performance = Output(new CommitPerformanceCounters)
}
