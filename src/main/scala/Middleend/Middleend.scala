import chisel3._
import chisel3.util._
import ZirconConfig._

class MiddleendInstruction(p: FrontendParams) extends Bundle {
    val ftqIdx = UInt(p.ftqBits.W)
    val slot = UInt(p.slotBits.W)
    val packetStart = Bool()
    val packetEnd = Bool()
    val ftqRecord = new FrontendFtqRecord(p)
    val instruction = new FrontendInstruction(p)
}

class MiddleendPhysicalInfo(p: BackendParams) extends Bundle {
    val prs = Vec(3, UInt(p.tagWidth.W))
    val prd = UInt(p.tagWidth.W)
    val pprd = UInt(p.tagWidth.W)
}

class MiddleendRenameEntry(fp: FrontendParams, bp: BackendParams) extends Bundle {
    val context = new MiddleendInstruction(fp)
    val physical = new MiddleendPhysicalInfo(bp)
    val dispatchClass = new DispatchClass
}

class MiddleendCommitDestination(p: BackendParams) extends Bundle {
    val rd = UInt(5.W)
    val isFp = Bool()
    val prd = UInt(p.physWidth.W)
    val pprd = UInt(p.physWidth.W)

    /** Integer physical zero is fixed, while floating-point physical zero is an ordinary register. */
    def writesPhysical: Bool = isFp || prd.orR
}

class MiddleendCommitEntry(fp: FrontendParams, bp: BackendParams) extends Bundle {
    val context = new MiddleendInstruction(fp)
    val destination = new MiddleendCommitDestination(bp)
    val allocation = new BackendAllocation(bp)
}

class MiddleendCommitRequest(fp: FrontendParams, width: Int) extends Bundle {
    val valid = UInt(width.W)
    val store = UInt(width.W)
    val packetStart = UInt(width.W)
}

class MiddleendCommitEnqueue(fp: FrontendParams, bp: BackendParams, width: Int) extends Bundle {
    val valid = UInt(width.W)
    // Payload may be written into reserved-but-not-yet-visible Commit slots
    // before backend issue capacity permits the matching occupancy update.
    val writeValid = UInt(width.W)
    val entries = Vec(width, new MiddleendCommitEntry(fp, bp))
}

/** Ordered-resource exchange between the middle end and the future Commit block. */
class CommitMiddleendIO(
    fp: FrontendParams,
    bp: BackendParams,
    dispatchWidth: Int,
    commitWidth: Int,
) extends Bundle {
    val request = Output(new MiddleendCommitRequest(fp, dispatchWidth))
    // Bit n permits the ordered prefix through instruction n.
    val resourcePrefix = Input(UInt(dispatchWidth.W))
    val allocation = Input(Vec(dispatchWidth, new BackendAllocation(bp)))
    val ftqAllocate = Output(Vec(dispatchWidth, Valid(new FrontendFtqAllocation(fp))))
    val ftqIdx = Input(Vec(dispatchWidth, UInt(fp.ftqBits.W)))
    val enqueue = Output(new MiddleendCommitEnqueue(fp, bp, dispatchWidth))
    val retire = Input(Vec(commitWidth, Valid(new MiddleendCommitDestination(bp))))
    val flush = Input(Bool())
    // Restore occurs after the last retirement update belonging to a flush.
    val restore = Input(Bool())
}

class MiddleendIO(
    fp: FrontendParams,
    bp: BackendParams,
    issue: IssueParams,
    commitParams: CommitParams,
    vectorEnabled: Boolean = false,
) extends Bundle {
    val frontend = Flipped(new FrontendMiddleIO(fp, issue.dispatchWidth))
    val backend = Flipped(new BackendMiddleendIO(bp, issue))
    val commit = new CommitMiddleendIO(fp, bp, issue.dispatchWidth, commitParams.width)
    val fpState = Input(UInt(2.W))
    val vector = if (vectorEnabled) Some(new VectorMiddleendIO(bp)) else None
    val performance = if (fp.observe) Some(Output(new MiddleendPerformanceCounters)) else None
}

/** Decode, rename, readiness lookup and parallel dispatch.
  *
  * A Rename-to-Dispatch pipeline register keeps RAT lookup and physical
  * allocation out of the dispatch path. FQ supplies an ordered instruction group
  * directly; backpressure retains every renamed instruction until Dispatch accepts it.
  */
class Middleend(
    val frontendParams: FrontendParams = FrontendParams(),
    val backendParams: BackendParams = BackendParams(),
    val issueParams: IssueParams = IssueParams(),
    val commitParams: CommitParams = CommitParams(),
    val privileged: Boolean = true,
    val vectorEnabled: Boolean = false,
) extends Module {
    require(frontendParams.fetchWidth >= issueParams.dispatchWidth)

    private val width = issueParams.dispatchWidth
    private val intParams = RenameParams(
        backendParams.numIntPhys,
        width,
        commitParams.width,
        numSources = 3,
        hasZeroReg = true,
    )
    private val fpParams = RenameParams(
        backendParams.numFpPhys,
        width,
        commitParams.width,
        numSources = 3,
        hasZeroReg = false,
    )
    require(!vectorEnabled || !privileged)
    val io = IO(new MiddleendIO(frontendParams, backendParams, issueParams, commitParams, vectorEnabled))

    private def unifiedTag(isFp: Boolean, index: UInt): UInt =
        Cat(isFp.B, index.pad(backendParams.physWidth))
    /* The held Q-side group separates Rename from Dispatch. */
    val renameStageEntries = Reg(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    val renameStageValid = RegInit(0.U(width.W))
    val accepted = Wire(UInt(width.W))

    val instructions = io.frontend.out.map(_.bits.instruction)
    val decoders = Seq.fill(width)(Module(new Decoder(frontendParams,
        if (privileged) DecodeSupport.current else if (vectorEnabled) DecodeSupport.performance
        else DecodeSupport.performance.copy(units = DecodeSupport.performance.units - DecodeUnit.Vector))))
    decoders.zip(instructions).foreach { case (decoder, instruction) => decoder.io.in := instruction }
    // PD classifies scalar and RVV operands before FQ stores their domain bits.
    val renameInstructions = instructions
    val vectorStage = if (vectorEnabled) Some(Reg(new VectorInstructionMetadata)) else None
    val vectorRename = if (vectorEnabled) Some(Module(new VectorRename)) else None
    val vectorState = if (vectorEnabled) Some(RegInit({
        val initial = WireDefault(0.U.asTypeOf(new VectorCSRState))
        initial.vtype := "h80000000".U
        initial
    })) else None
    val configurationPending = if (vectorEnabled) Some(RegInit(false.B)) else None
    val vectorCandidates = VecInit(decoders.zipWithIndex.map { case (decoder, lane) =>
        io.frontend.out(lane).valid && vectorEnabled.B && VectorDecoder.recognized(instructions(lane).inst)
    })
    val vectorSelection = PriorityEncoderOH(vectorCandidates.asUInt)
    val vectorCandidateGroups = if (vectorEnabled) instructions.map { instruction =>
        new VectorGroupCounts(instruction.inst, vectorState.get.vtype)
    } else Seq.empty
    val vectorCapacityReady = vectorRename.map { rename =>
        // Decode narrow allocation counts per FQ lane in parallel with the
        // selected instruction's full decode and RAT reads. Capacity depends
        // only on the existing bank FreeLists and the held Dispatch preview.
        val candidateBlocked = instructions.zip(vectorCandidateGroups).map { case (instruction, groups) =>
            (0 until 8).map { bank =>
                val distance = (bank.U(3.W) - instruction.inst(9, 7))(2, 0)
                distance < groups.allocationCount && !rename.io.bankAvailable(bank)
            }.reduce(_ || _)
        }
        // A zero-hot vector selection selects no blocked candidate.
        !Mux1H(vectorSelection, candidateBlocked)
    }.getOrElse(true.B)
    val vectorInstruction = Mux1H(vectorSelection, instructions.map(_.inst))
    val vectorControl = if (vectorEnabled) Some(Module(new VectorDecoder)) else None
    vectorControl.foreach(_.io.instruction := vectorInstruction)
    val vectorGroups = if (vectorEnabled) Some(Module(new VectorRegisterGroups)) else None
    vectorGroups.foreach { groups =>
        groups.io.instruction := vectorInstruction
        groups.io.control := vectorControl.get.io.control
        groups.io.vtype := vectorState.get.vtype
        groups.io.vl := vectorState.get.vl
        groups.io.vstart := vectorState.get.vstart
    }

    /* Decode and Rename run in parallel before the shared buffer boundary. */
    val integerRename = Module(new Rename(intParams))
    val floatingRename = Module(new Rename(fpParams))
    val renames = Seq(integerRename -> false, floatingRename -> true)
    for ((rename, isFp) <- renames; lane <- 0 until width) {
        val instruction = renameInstructions(lane)
        val rinfo = rename.io.rinfo(lane)
        val candidate = io.frontend.out(lane).valid
        rinfo.request := candidate && instruction.rinfo.dest.valid &&
            instruction.rinfo.dest.isFp === isFp.B
        rinfo.rd := instruction.rinfo.dest.index
        for (source <- 0 until 3) {
            rinfo.src(source) := instruction.rinfo.src(source).index
            rinfo.srcValid(source) := candidate && instruction.rinfo.src(source).valid &&
                instruction.rinfo.src(source).isFp === isFp.B
        }
    }

    /* Commit reserves ROB, SQ and FTQ resources for the same ordered prefix Dispatch may accept. */
    io.commit.request.valid := renameStageValid
    io.commit.request.store := VecInit(renameStageEntries.map(entry => entry.dispatchClass.storeOrAtomic ||
        vectorEnabled.B && entry.context.instruction.fu === DecodeUnit.Vector.U &&
            entry.context.instruction.inst(6, 0) === "h27".U)).asUInt
    io.commit.request.packetStart := VecInit(renameStageEntries.map(_.context.packetStart)).asUInt
    FIFOUtil.assertPrefix(io.commit.resourcePrefix.asBools, "Commit resource permission must be a prefix")

    /* Admission moves one complete FQ group into the fixed pipeline register. */
    val decodeCandidate = VecInit((0 until width).map { lane =>
        // Stop before a second vector operation or after a configuration change.
        // This preserves a continuous FQ prefix and one group RAT request.
        val earlierVector = if (lane == 0) false.B else vectorCandidates.take(lane).reduce(_ || _)
        val earlierConfiguration = if (lane == 0) false.B else (0 until lane).map { previous =>
            vectorCandidates(previous) && instructions(previous).inst(14, 12) === 7.U
        }.reduce(_ || _)
        io.frontend.out(lane).valid && !(earlierVector && vectorCandidates(lane)) && !earlierConfiguration
    })
    // Once a lane is removed, younger lanes are also removed.
    val decodePrefix = VecInit((0 until width).map(lane => decodeCandidate.take(lane + 1).reduce(_ && _)))
    val decodeNeedsInt = VecInit((0 until width).map { lane =>
        decodePrefix(lane) && renameInstructions(lane).rinfo.dest.valid &&
            !renameInstructions(lane).rinfo.dest.isFp
    })
    val decodeNeedsFp = VecInit((0 until width).map { lane =>
        decodePrefix(lane) && renameInstructions(lane).rinfo.dest.valid &&
            renameInstructions(lane).rinfo.dest.isFp
    })
    val stageCanLoad = !renameStageValid.orR || accepted.orR
    val laneRenameReady = VecInit((0 until width).map { lane =>
        (!decodeNeedsInt(lane) || integerRename.io.freePrefix(lane)) &&
            (!decodeNeedsFp(lane) || floatingRename.io.freePrefix(lane))
    })
    val scalarRenameReady = laneRenameReady.reduce(_ && _)
    val groupRenameReady = scalarRenameReady && vectorCapacityReady &&
        configurationPending.map(pending => !pending).getOrElse(true.B)
    val decodeGrant = Mux(
        stageCanLoad && groupRenameReady && !io.commit.flush && !io.commit.restore,
        decodePrefix.asUInt,
        0.U(width.W),
    )
    // Prepare tags and same-group bypasses from the FQ candidates in parallel
    // with Dispatch admission. Only the fixed stage register uses decodeGrant.
    integerRename.io.prepare := decodePrefix.asUInt
    floatingRename.io.prepare := decodePrefix.asUInt
    integerRename.io.restore := io.commit.restore
    floatingRename.io.restore := io.commit.restore

    /* Assemble the registered payload once Decode and the two rename domains have completed. */
    val renameIncoming = Wire(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    for (lane <- 0 until width) {
        val instruction = renameInstructions(lane)
        val intInfo = integerRename.io.pinfo(lane)
        val fpInfo = floatingRename.io.pinfo(lane)
        renameIncoming(lane).context.ftqIdx := 0.U
        renameIncoming(lane).context.slot := io.frontend.out(lane).bits.slot
        renameIncoming(lane).context.packetStart := io.frontend.out(lane).bits.packetStart
        renameIncoming(lane).context.packetEnd := io.frontend.out(lane).bits.packetEnd
        renameIncoming(lane).context.ftqRecord := io.frontend.out(lane).bits.record
        val decoderOut = decoders(lane).io.out
        val decoded = WireDefault(decoderOut)
        val usesFp = decoderOut.rinfo.dest.valid && decoderOut.rinfo.dest.isFp ||
            decoderOut.rinfo.src.map(source => source.valid && source.isFp).reduce(_ || _)
        when(!decoderOut.exception.valid && usesFp && io.fpState === 0.U) {
            decoded.fu := DecodeUnit.None.U
            decoded.exception.valid := true.B
            decoded.exception.cause := DecodeException.IllegalInstruction.U
            decoded.exception.tval := decoded.inst
        }
        // An invalid exception payload is unobservable; bypass legality decode on its wide value.
        decoded.exception.tval := Mux(
            instruction.fault,
            instruction.pc,
            Mux(instruction.exception.valid, instruction.exception.tval, instruction.inst),
        )
        renameIncoming(lane).context.instruction := decoded
        val system = decoded.fu === DecodeUnit.System.U
        val csr = system && decoded.op >= SystemOp.CSRRW.U && decoded.op <= SystemOp.CSRRCI.U
        val commitSystem = system && (decoded.op <= SystemOp.FENCE_I.U || decoded.op >= SystemOp.ECALL.U)
        renameIncoming(lane).dispatchClass.shortArith :=
            decoded.fu === DecodeUnit.ALU.U || decoded.fu === DecodeUnit.Branch.U
        renameIncoming(lane).dispatchClass.mixArith :=
            decoded.fu === DecodeUnit.Multiply.U || decoded.fu === DecodeUnit.Divide.U ||
                decoded.fu === DecodeUnit.FpMisc.U || csr
        renameIncoming(lane).dispatchClass.load := decoded.fu === DecodeUnit.Load.U
        renameIncoming(lane).dispatchClass.storeOrAtomic :=
            decoded.fu === DecodeUnit.Store.U || decoded.fu === DecodeUnit.Atomic.U
        renameIncoming(lane).dispatchClass.noIssue := decoded.exception.valid || commitSystem ||
            decoded.fu === DecodeUnit.Vector.U
        for (source <- 0 until 3) {
            val operand = instruction.rinfo.src(source)
            renameIncoming(lane).physical.prs(source) := Mux(
                operand.valid,
                Mux(
                    operand.isFp,
                    unifiedTag(isFp = true, fpInfo.prs(source)),
                    unifiedTag(isFp = false, intInfo.prs(source)),
                ),
                0.U,
            )
        }
        val destination = instruction.rinfo.dest
        renameIncoming(lane).physical.prd := Mux(
            destination.valid,
            Mux(
                destination.isFp,
                unifiedTag(isFp = true, fpInfo.prd),
                unifiedTag(isFp = false, intInfo.prd),
            ),
            0.U,
        )
        renameIncoming(lane).physical.pprd := Mux(
            destination.valid,
            Mux(
                destination.isFp,
                unifiedTag(isFp = true, fpInfo.pprd),
                unifiedTag(isFp = false, intInfo.pprd),
            ),
            0.U,
        )
    }

    /* Commit retirement updates each rename domain independently. */
    for ((rename, isFp) <- renames; lane <- 0 until commitParams.width) {
        val retire = io.commit.retire(lane)
        val local = rename.io.commit(lane)
        local.valid := retire.valid && retire.bits.isFp === isFp.B
        local.bits.rd := retire.bits.rd
        local.bits.prd := retire.bits.prd(rename.p.indexWidth - 1, 0)
        local.bits.pprd := retire.bits.pprd(rename.p.indexWidth - 1, 0)
    }

    /* ReadyBoard lookup and backend package construction. */
    val readyBoard = Module(new ReadyBoard(
        backendParams, width, issueParams.wakeupPorts,
        dualMemory = true, replicateLoadMasks = true,
    ))
    readyBoard.io.wakeup := io.backend.wakeup
    readyBoard.io.loadWakeupBeforeD1.get := io.backend.loadWakeupBeforeD1
    readyBoard.io.memoryWakeup.get := io.backend.memoryWakeup(2)
    readyBoard.io.speculation := io.backend.speculation
    readyBoard.io.flush := io.commit.flush
    for (port <- 0 until issueParams.wakeupPorts if port != 2) {
        assert(io.backend.wakeup(port).asUInt === io.backend.memoryWakeup(port).asUInt)
    }
    val physicalInfo = Wire(Vec(width, new BackendRenameInfo(backendParams)))
    for (lane <- 0 until width) {
        val instruction = renameStageEntries(lane).context.instruction
        val stored = renameStageEntries(lane).physical
        val memoryClass = renameStageEntries(lane).dispatchClass.load ||
            renameStageEntries(lane).dispatchClass.storeOrAtomic
        for (source <- 0 until 3) {
            val operand = instruction.rinfo.src(source)
            readyBoard.io.query(lane).prs(source) := stored.prs(source)
            // A same-group producer may finish before its consumer reaches
            // Dispatch, so every live source observes the current ReadyBoard state.
            readyBoard.io.query(lane).valid(source) := renameStageValid(lane) && operand.valid
        }
        physicalInfo(lane).prs := stored.prs
        for (source <- 0 until 3) {
            val memoryReady = if (source == 2) true.B else readyBoard.io.memoryState.get(lane).ready(source)
            val memoryMask = if (source == 2) 0.U(backendParams.specWidth.W) else
                readyBoard.io.memoryState.get(lane).specMask(source)
            physicalInfo(lane).sourceReady(source) := Mux(
                memoryClass, memoryReady, readyBoard.io.state(lane).ready(source))
            physicalInfo(lane).sourceSpecMask(source) := Mux(
                memoryClass, memoryMask, readyBoard.io.state(lane).specMask(source))
        }
        physicalInfo(lane).prd := stored.prd
    }

    /* A fetch packet may span dispatch groups, so continuation lanes reuse its allocated FTQ index. */
    val openPacketValid = RegInit(false.B)
    val openPacketFtqIdx = RegInit(0.U(frontendParams.ftqBits.W))
    val dispatchedFtqIdx = Wire(Vec(width, UInt(frontendParams.ftqBits.W)))
    var precedingFtqIdx = openPacketFtqIdx
    for (lane <- 0 until width) {
        val context = renameStageEntries(lane).context
        dispatchedFtqIdx(lane) := Mux(context.packetStart, io.commit.ftqIdx(lane), precedingFtqIdx)
        precedingFtqIdx = Mux(context.packetStart, io.commit.ftqIdx(lane), precedingFtqIdx)
    }

    /* Dispatch sees complete backend packages and emits one indexed group per issue queue. */
    val dispatcher = Module(new Dispatcher(backendParams, issueParams))
    dispatcher.io.in.valid := renameStageValid
    dispatcher.io.dispatchClass := VecInit(renameStageEntries.map(_.dispatchClass))
    for (lane <- 0 until width) {
        val packageForDispatch = BackendPackage.fromFrontend(
            renameStageEntries(lane).context.instruction,
            physicalInfo(lane),
            io.commit.allocation(lane),
            backendParams,
        )
        packageForDispatch.ftqIdx := dispatchedFtqIdx(lane)
        packageForDispatch.ftqSlot := renameStageEntries(lane).context.slot
        dispatcher.io.in.entries(lane) := packageForDispatch
    }
    val vectorStageSelection = VecInit(renameStageEntries.zipWithIndex.map { case (entry, lane) =>
        renameStageValid(lane) && entry.context.instruction.fu === DecodeUnit.Vector.U
    }).asUInt
    val vectorStageMemory = vectorStage.map(stage =>
        stage.control.execution === VectorExecution.Load.U ||
            stage.control.execution === VectorExecution.Store.U).getOrElse(false.B)
    val vectorDispatchReady = io.vector.map(port => !vectorStageSelection.orR ||
        Mux(vectorStageMemory, port.availableMemory, port.availableCompute)).getOrElse(true.B)
    dispatcher.io.resourcePrefix := Mux(vectorDispatchReady, io.commit.resourcePrefix, 0.U)
    dispatcher.io.freePrefix := io.backend.freePrefix
    dispatcher.io.clearPreference := io.commit.flush
    io.backend.enqueue := dispatcher.io.enqueue

    /* Flush owns the edge and discards the Q-side group before any dispatch-side effects. */
    accepted := Mux(io.commit.flush, 0.U, dispatcher.io.accepted)
    for ((rename, isFp) <- renames; lane <- 0 until width) {
        val destination = renameStageEntries(lane).context.instruction.rinfo.dest
        val writesDomain = destination.valid && destination.isFp === isFp.B &&
            (isFp.B || destination.index =/= 0.U)
        rename.io.preview(lane).valid := renameStageValid(lane) && writesDomain
        rename.io.preview(lane).bits.rd := destination.index
        rename.io.preview(lane).bits.prd := renameStageEntries(lane).physical.prd(rename.p.indexWidth - 1, 0)
        rename.io.writeback(lane).valid := accepted(lane) && writesDomain
        rename.io.writeback(lane).bits.rd := destination.index
        rename.io.writeback(lane).bits.prd := rename.io.preview(lane).bits.prd
    }

    /* Accepted lanes update readiness and enter Commit atomically with their backend issue tasks. */
    // Commit queues guard prewrites with their own free space. Stage validity
    // controls visibility only, so it need not fan out to every payload bit.
    for (lane <- 0 until width) {
        val destination = renameStageEntries(lane).context.instruction.rinfo.dest
        val incomingDestination = renameInstructions(lane).rinfo.dest
        readyBoard.io.allocate(lane).valid := decodeGrant(lane) && incomingDestination.valid
        readyBoard.io.allocate(lane).bits := renameIncoming(lane).physical.prd
        io.commit.enqueue.entries(lane).context := renameStageEntries(lane).context
        io.commit.enqueue.entries(lane).context.ftqIdx := dispatchedFtqIdx(lane)
        io.commit.enqueue.entries(lane).destination.rd := destination.index
        io.commit.enqueue.entries(lane).destination.isFp := destination.isFp
        io.commit.enqueue.entries(lane).destination.prd := physicalInfo(lane).prd
        io.commit.enqueue.entries(lane).destination.pprd := renameStageEntries(lane).physical.pprd
        io.commit.enqueue.entries(lane).allocation := io.commit.allocation(lane)
        io.commit.ftqAllocate(lane).valid := accepted(lane) && renameStageEntries(lane).context.packetStart &&
            !io.commit.flush
        // Flush clears FTQ occupancy, so a coincident payload prewrite remains
        // invisible and must not put recovery on the wide FTQ payload D cone.
        io.commit.ftqAllocate(lane).bits.writeValid := renameStageEntries(lane).context.packetStart
        io.commit.ftqAllocate(lane).bits.record := renameStageEntries(lane).context.ftqRecord
    }
    io.commit.enqueue.valid := accepted
    io.commit.enqueue.writeValid := Fill(width, true.B)

    vectorRename.foreach { rename =>
        val groups = vectorGroups.get.io.groups
        val port = io.vector.get
        // Compute only the narrow architectural bases before the late FQ
        // selector; group-count decode stays parallel with the shared RAT read.
        rename.io.sourceBase := Mux1H(vectorSelection, vectorCandidateGroups.map(_.sourceBases))
        rename.io.sourceCount := groups.sourceCount
        rename.io.destinationBase := groups.destinationBase
        rename.io.destinationCount := groups.destinationCount
        rename.io.restore := io.commit.restore
        rename.io.retire := port.retire
        rename.io.preview.valid := vectorStageSelection.orR && vectorStage.get.destination.count.orR
        rename.io.preview.bits := vectorStage.get.destination
        rename.io.accept := (accepted & vectorStageSelection).orR && vectorStage.get.destination.count.orR
        val metadata = Wire(new VectorInstructionMetadata)
        metadata.control := vectorControl.get.io.control
        metadata.groups := groups
        metadata.source := rename.io.source
        metadata.mask := rename.io.mask
        metadata.destination := rename.io.destination
        metadata.state := port.committed
        metadata.state.vl := vectorState.get.vl
        metadata.state.vtype := vectorState.get.vtype
        metadata.state.vstart := vectorState.get.vstart
        val vectorDecodeFire = (decodeGrant & vectorCandidates.asUInt).orR
        port.allocate.valid := vectorDecodeFire && groups.destinationCount.orR
        port.allocate.bits := rename.io.destination
        when(stageCanLoad) { vectorStage.get := metadata }
        port.enqueue.valid := (accepted & vectorStageSelection).orR
        port.enqueue.bits.vector := vectorStage.get
        port.enqueue.bits.scalar := Mux1H(vectorStageSelection, dispatcher.io.in.entries)
        val configurationDecode = vectorDecodeFire &&
            metadata.control.execution === VectorExecution.Configuration.U
        when(io.commit.flush || io.commit.restore) {
            configurationPending.get := false.B
        }.elsewhen(configurationDecode) {
            configurationPending.get := true.B
        }.elsewhen(port.configuration.valid) {
            configurationPending.get := false.B
        }
        when(io.commit.restore) {
            vectorState.get := port.committed
        }.elsewhen(port.configuration.valid && !io.commit.flush) {
            vectorState.get := port.configuration.bits
        }
        when(vectorDecodeFire && !io.commit.restore) { vectorState.get.vstart := 0.U }
        when(vectorDecodeFire) {
            assert(rename.io.sourceBase.asUInt === groups.sourceBase.asUInt)
            assert(rename.io.available, "Parallel vector capacity must grant every requested bank")
            assert(metadata.control.legal, "Vector instruction is not implemented in the current execution path")
            assert(groups.legal, "Vector register group or configuration is outside the computation contract")
        }
        assert(PopCount(vectorStageSelection) <= 1.U)
    }

    val acceptedAny = accepted(0)
    val lastAccepted = VecInit.tabulate(width) { lane =>
        val nextAccepted = if (lane + 1 == width) false.B else accepted(lane + 1)
        accepted(lane) && !nextAccepted
    }.asUInt
    val lastAcceptedEnd = Mux1H(lastAccepted.asBools, renameStageEntries.map(_.context.packetEnd))
    val lastAcceptedFtqIdx = Mux1H(lastAccepted.asBools, dispatchedFtqIdx)
    when(io.commit.flush) {
        openPacketValid := false.B
    }.elsewhen(acceptedAny) {
        openPacketValid := !lastAcceptedEnd
        openPacketFtqIdx := lastAcceptedFtqIdx
    }

    /* Fixed stage: retain Q intact under backpressure or replace it as one group. */
    when(io.commit.flush) {
        renameStageValid := 0.U
    }.elsewhen(stageCanLoad) {
        renameStageValid := decodeGrant
        renameStageEntries := renameIncoming
    }

    /* FQ dequeue follows the same prefix grant that enters the rename-stage register. */
    for (lane <- 0 until width) {
        io.frontend.out(lane).ready := decodeGrant(lane) && !io.commit.flush
    }
    assert((accepted & ~renameStageValid) === 0.U, "Dispatch cannot consume an invalid renamed instruction")
    FIFOUtil.assertPrefix(decodePrefix.toSeq, "Middleend frontend candidates must be an ordered prefix")
    FIFOUtil.assertPrefix(accepted.asBools, "Middleend acceptance must remain an ordered prefix")
    FIFOUtil.assertPrefix(decodeGrant.asBools, "Middleend decode must consume an ordered prefix")
    when(accepted.orR) {
        assert(accepted === renameStageValid, "Fixed Rename stage must dispatch atomically")
    }
    when(accepted(0) && !renameStageEntries(0).context.packetStart) {
        assert(openPacketValid, "A continued fetch packet must retain its FTQ mapping")
    }
    for (lane <- 0 until commitParams.width) {
        when(io.commit.retire(lane).valid) {
            val destination = io.commit.retire(lane).bits
            val prdIndex = destination.prd
            val pprdIndex = destination.pprd
            val limit = Mux(destination.isFp, backendParams.numFpPhys.U, backendParams.numIntPhys.U)
            assert(prdIndex < limit && pprdIndex < limit, "Retired physical index is out of range")
            when(!destination.isFp) {
                assert(
                    destination.rd =/= 0.U && prdIndex =/= 0.U && pprdIndex =/= 0.U,
                    "Integer zero register must not enter the retirement rename stream",
                )
            }
        }
    }

    /* Simulation-only counters stay after the decode, rename and dispatch datapath. */
    if (frontendParams.observe) {
        val integerFreeListBlockedCycles = RegInit(0.U(64.W))
        val floatingFreeListBlockedCycles = RegInit(0.U(64.W))
        val dispatchBlockedCycles = RegInit(0.U(64.W))
        val ftqBlockedCycles = RegInit(0.U(64.W))
        val integerBlocked = VecInit((0 until width).map { lane =>
            decodeNeedsInt(lane) && !integerRename.io.freePrefix(lane)
        }).asUInt.orR
        val floatingBlocked = VecInit((0 until width).map { lane =>
            decodeNeedsFp(lane) && !floatingRename.io.freePrefix(lane)
        }).asUInt.orR
        when(integerBlocked) { integerFreeListBlockedCycles := integerFreeListBlockedCycles + 1.U }
        when(floatingBlocked) { floatingFreeListBlockedCycles := floatingFreeListBlockedCycles + 1.U }
        val dispatchBlocked = renameStageValid.orR && accepted =/= renameStageValid
        when(dispatchBlocked) { dispatchBlockedCycles := dispatchBlockedCycles + 1.U }
        val ftqBlocked = io.commit.request.valid.orR && io.commit.request.packetStart.orR &&
            (io.commit.resourcePrefix & io.commit.request.valid) =/= io.commit.request.valid
        when(ftqBlocked && !io.commit.flush) {
            ftqBlockedCycles := ftqBlockedCycles + 1.U
        }
        io.performance.get.integerFreeListBlockedCycles := integerFreeListBlockedCycles
        io.performance.get.floatingFreeListBlockedCycles := floatingFreeListBlockedCycles
        io.performance.get.dispatchBlockedCycles := dispatchBlockedCycles
        io.performance.get.ftqBlockedCycles := ftqBlockedCycles
    }
}

class MiddleendPerformanceCounters extends Bundle {
    val integerFreeListBlockedCycles = UInt(64.W)
    val floatingFreeListBlockedCycles = UInt(64.W)
    val dispatchBlockedCycles = UInt(64.W)
    val ftqBlockedCycles = UInt(64.W)
}
