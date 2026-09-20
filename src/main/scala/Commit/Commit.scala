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
            io.middleend.request.valid(previous) && io.middleend.request.packetStart(previous)
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
    ): Valid[ROBWrite] = {
        val result = Wire(Valid(new ROBWrite(CommitIndex.addressWidth(cp.robEntries))))
        result.valid := valid
        result.bits.address := robIdx(CommitIndex.addressWidth(cp.robEntries) - 1, 0)
        result.bits.data := data
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
        )
    }

    /* Resolved branches update their FTQ packet before retirement produces predictor training. */
    val branchInputs = io.backend.arith.map(_.branch.update)
    for (port <- branchInputs.indices) {
        val input = branchInputs(port)
        ftq.io.commit.branch(port).valid := input.valid
        ftq.io.commit.branch(port).bits.ftqIdx := rob.io.readEntry(3 + port).ftqIdx
        ftq.io.commit.branch(port).bits.slot := rob.io.readEntry(3 + port).slot
        ftq.io.commit.branch(port).bits.taken := input.bits.taken
        ftq.io.commit.branch(port).bits.target := input.bits.target
        ftq.io.commit.branch(port).bits.mispredicted := input.bits.predFail
        when(input.valid) { assert(io.backend.arith(port).rob.complete.valid) }
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
    val delayedRecovery = RegInit(false.B)
    val trainQueue = Module(new ClusterIndexFIFO(new FrontendTraining(fp), 6, cp.width, 1, 0, 0))
    val pendingRecoveryTrain = Reg(new FrontendTraining(fp))
    val pendingRecoveryTrainValid = RegInit(false.B)
    trainQueue.io.flush := false.B
    trainQueue.io.deq(0).ready := true.B
    io.frontend.ftq.train.valid := trainQueue.io.deq(0).valid
    io.frontend.ftq.train.bits := trainQueue.io.deq(0).bits
    val trainingSpace = trainQueue.io.enq(0).ready && !pendingRecoveryTrainValid
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
    val cacheMaintenance = fenceI || sfence
    when(!cacheMaintenance || delayedRecovery) {
        maintenanceStarted := false.B
    }.elsewhen(memoryDrained) {
        maintenanceStarted := true.B
    }
    io.maintenance.request := cacheMaintenance && maintenanceStarted && !delayedRecovery
    io.maintenance.invalidate := fenceI
    val systemReady = !headSystem || Mux(
        cacheMaintenance,
        maintenanceStarted && io.maintenance.done,
        Mux(fence, memoryDrained, true.B),
    )
    /* Each ROB head must identify one of the ordered FTQ heads before it can retire. */
    val headMatchesFtq = VecInit((0 until cp.width).map { lane =>
        VecInit((0 until cp.width).map { packet =>
            rob.io.head(lane).valid && ftq.io.commit.head(packet).valid &&
                rob.io.head(lane).bits.fetchToken === ftq.io.commit.head(packet).bits.fetchToken &&
                rob.io.head(lane).bits.ftqIdx === ftq.io.commit.headIdx(packet)
        })
    })
    val selectedFtq = Wire(Vec(cp.width, new FrontendFtqEntry(fp)))
    val selectedFtqIdx = Wire(Vec(cp.width, UInt(fp.ftqBits.W)))
    for (lane <- 0 until cp.width) {
        selectedFtq(lane) := Mux1H(headMatchesFtq(lane), ftq.io.commit.head.map(_.bits))
        selectedFtqIdx(lane) := Mux1H(headMatchesFtq(lane), ftq.io.commit.headIdx)
    }
    val retire = Wire(Vec(cp.width, Bool()))
    val recovery = Wire(Vec(cp.width, Bool()))
    val packetEnd = Wire(Vec(cp.width, Bool()))
    def writesSatp(entry: ROBEntry): Bool = {
        val unconditional = entry.systemOp === SystemOp.CSRRW.U || entry.systemOp === SystemOp.CSRRWI.U
        val conditional = entry.systemOp === SystemOp.CSRRS.U || entry.systemOp === SystemOp.CSRRC.U ||
            entry.systemOp === SystemOp.CSRRSI.U || entry.systemOp === SystemOp.CSRRCI.U
        entry.isSystem && entry.instruction(31, 20) === CSRAddress.satp.U &&
            (unconditional || (conditional && entry.instruction(19, 15).orR))
    }
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
        val mispredicted = Mux1H(
            VecInit.tabulate(fp.fetchWidth)(slot => entry.slot === slot.U),
            selectedFtq(lane).mispredicted.asBools,
        )
        val commitSystem = entry.isSystem &&
            (entry.systemOp <= SystemOp.FENCE_I.U || entry.systemOp >= SystemOp.ECALL.U)
        val ecall = entry.isSystem && entry.systemOp === SystemOp.ECALL.U
        val ebreak = entry.isSystem && entry.systemOp === SystemOp.EBREAK.U
        val mretIllegal = entry.isSystem && entry.systemOp === SystemOp.MRET.U && privilege =/= 3.U
        val sretIllegal = entry.isSystem && entry.systemOp === SystemOp.SRET.U &&
            !(privilege === 3.U || (privilege === 1.U && !csr.io.state.mstatus(22)))
        val systemException = ecall || ebreak || mretIllegal || sretIllegal
        val recoveryCandidate = entry.exception.valid || mispredicted || commitSystem || writesExecutionState(entry) ||
            entry.isAtomic || systemException
        val needsFeedback = entry.packetEnd || recoveryCandidate
        val completed = rob.io.head(lane).valid && entry.complete && headMatchesFtq(lane).asUInt.orR &&
            (if (lane == 0) systemReady else !headSystem && !entry.isSystem) &&
            (!needsFeedback || trainingSpace)
        val recover = completed && recoveryCandidate
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
    takeInterrupt := interruptEligible.asUInt.orR && rob.io.head(0).valid &&
        !recovery.asUInt.orR && !delayedRecovery && !headSystem && !headAtomic
    val interruptCause = Mux1H(interruptEligible, interruptCandidates.map(_.U(5.W)))
    val recoveryValid = (recovery.asUInt.orR || takeInterrupt) && !delayedRecovery
    val recoveryEntry = Mux1H(recovery, rob.io.head.map(_.bits))
    val recoveryFtq = Mux1H(recovery, selectedFtq)
    val recoveryFtqIdx = Mux1H(recovery, selectedFtqIdx)
    val recoverySlotOH = UIntToOH(recoveryEntry.slot, fp.fetchWidth)
    val recoveryTaken = (recoveryFtq.taken & recoverySlotOH).orR
    val recoveryTarget = Mux1H(recoverySlotOH, recoveryFtq.targets)
    val recoveryRetirement = Wire(new FrontendRetirement(fp))
    val recoveryMask = recoveryFtq.record.train.mask & VecInit.tabulate(fp.fetchWidth) { slot =>
        slot.U <= recoveryEntry.slot
    }.asUInt
    recoveryRetirement.fetchToken := recoveryFtq.fetchToken
    recoveryRetirement.ftqIdx := recoveryFtqIdx
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
    val selectedEntry = Mux(takeInterrupt, rob.io.head(0).bits, recoveryEntry)
    val selectedEcall = selectedEntry.isSystem && selectedEntry.systemOp === SystemOp.ECALL.U
    val selectedEbreak = selectedEntry.isSystem && selectedEntry.systemOp === SystemOp.EBREAK.U
    val selectedMret = selectedEntry.isSystem && selectedEntry.systemOp === SystemOp.MRET.U
    val selectedSret = selectedEntry.isSystem && selectedEntry.systemOp === SystemOp.SRET.U
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
            Mux(selectedEbreak, 3.U, Mux(selectedMretIllegal || selectedSretIllegal, 2.U, selectedEntry.exception.cause)),
        ),
    )
    val trap = takeInterrupt || selectedEntry.exception.valid || selectedSystemException
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
    delayedRetireFtq := recoveryValid && !trap && !takeInterrupt
    when(recoveryValid) {
        delayedRecoveryBase := Mux(
            trap,
            trapTarget,
            Mux(
                selectedMret || selectedSret,
                returnTarget,
                Mux(recoveryTaken, recoveryTarget, selectedEntry.pc),
            ),
        )
        delayedRecoveryAddFour := !trap && !selectedMret && !selectedSret && !recoveryTaken
        delayedRetirement := recoveryRetirement
        delayedFtq := recoveryFtq
    }
    val delayedSequentialPc = BLevelPAdder32.sum(delayedRecoveryBase, 4.U, 0.U)
    val delayedRecoveryPc = Mux(delayedRecoveryAddFour, delayedSequentialPc, delayedRecoveryBase)

    /* Completed FTQ packets generate normal feedback; recovery feedback is delayed with redirect. */
    val completedPackets = Wire(Vec(cp.width, Bool()))
    val recoveryPackets = Wire(Vec(cp.width, Bool()))
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
    for (packet <- 0 until cp.width) {
        val ftqEntry = ftq.io.commit.head(packet).bits
        completedPackets(packet) := VecInit((0 until cp.width).map { lane =>
            retireFire(lane) && packetEnd(lane) && headMatchesFtq(lane)(packet)
        }).asUInt.orR
        recoveryPackets(packet) := VecInit((0 until cp.width).map { lane =>
            recovery(lane) && headMatchesFtq(lane)(packet)
        }).asUInt.orR
        normalRetirements(packet).fetchToken := ftqEntry.fetchToken
        normalRetirements(packet).ftqIdx := ftq.io.commit.headIdx(packet)
        normalRetirements(packet).mask := ftqEntry.record.train.mask
        normalRetirements(packet).taken := ftqEntry.taken & normalRetirements(packet).mask
        normalRetirements(packet).nextPc := ftqEntry.record.nextPc
        for (slot <- 0 until fp.fetchWidth) {
            normalRetirements(packet).targets(slot) := Mux(
                ftqEntry.resolved(slot),
                ftqEntry.targets(slot),
                0.U,
            )
        }
        connectFeedback(
            normalFeedback(packet),
            completedPackets(packet) && !recoveryPackets(packet),
            normalRetirements(packet),
            ftqEntry,
        )
        ftqPop(packet) := completedPackets(packet) && !recoveryPackets(packet)
    }
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

    for (port <- 0 until cp.width) {
        io.frontend.ftq.retire(port).valid := Mux(
            delayedRecovery,
            if (port == 0) delayedFeedback.io.state.valid else false.B,
            normalFeedback(port).io.state.valid,
        )
        io.frontend.ftq.retire(port).bits := Mux(
            delayedRecovery,
            if (port == 0) delayedFeedback.io.state.bits else 0.U.asTypeOf(new FrontendStateEvent(fp)),
            normalFeedback(port).io.state.bits,
        )
    }

    val recoveryTrain = delayedFeedback.io.train.valid
    val enqueuePendingTrain = pendingRecoveryTrainValid && trainQueue.io.enq(0).ready
    for (port <- 0 until cp.width) {
        trainQueue.io.enq(port).valid := Mux(
            pendingRecoveryTrainValid,
            if (port == 0) true.B else false.B,
            Mux(recoveryTrain, if (port == 0) true.B else false.B, normalFeedback(port).io.train.valid),
        )
        trainQueue.io.enq(port).bits := Mux(
            pendingRecoveryTrainValid,
            pendingRecoveryTrain,
            Mux(recoveryTrain, delayedFeedback.io.train.bits, normalFeedback(port).io.train.bits),
        )
    }
    when(enqueuePendingTrain) {
        pendingRecoveryTrainValid := false.B
    }
    when(recoveryTrain && !trainQueue.io.enq(0).ready) {
        pendingRecoveryTrain := delayedFeedback.io.train.bits
        pendingRecoveryTrainValid := true.B
    }

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
    io.middleend.flush := delayedRecovery
    io.middleend.restore := delayedRecovery
    io.backend.flush := delayedRecovery

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
    csr.io.trap.bits.pcWord := selectedEntry.pc(31, 2)
    csr.io.trap.bits.cause := trapCause
    csr.io.trap.bits.tval := Mux(
        takeInterrupt || selectedEcall,
        0.U,
        Mux(
            selectedEbreak,
            selectedEntry.pc,
            Mux(selectedMretIllegal || selectedSretIllegal, selectedEntry.instruction, selectedEntry.exception.tval),
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
    io.csr.tlbFlush := recoveryValid && (sfence || (writesSatp(selectedEntry) && !trap))
    io.csr.currentPrivilege := privilege
    io.csr.state := csr.io.state

    when(recoveryValid && !takeInterrupt) {
        assert(PopCount(recovery) === 1.U)
    }
    when(csr.io.trap.valid) {
        assert(!retireFire.asUInt.orR, "Trap acceptance and instruction retirement must not share a cycle")
    }
    for (lane <- 0 until cp.width) {
        when(retireFire(lane) || recovery(lane)) {
            assert(headMatchesFtq(lane).asUInt.orR, "ROB and FTQ retirement order must match")
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
    val retiredPredictionFail = VecInit(retireFire.zip(rob.io.head).zipWithIndex.map {
        case ((fire, entry), lane) =>
            val selected = Mux1H(
                VecInit.tabulate(fp.fetchWidth)(slot => entry.bits.slot === slot.U),
                selectedFtq(lane).mispredicted.asBools,
            )
            fire && selected
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
