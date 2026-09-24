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
) extends Bundle {
    val frontend = Flipped(new FrontendMiddleIO(fp, issue.dispatchWidth))
    val backend = Flipped(new BackendMiddleendIO(bp, issue))
    val commit = new CommitMiddleendIO(fp, bp, issue.dispatchWidth, commitParams.width)
    val fpState = Input(UInt(2.W))
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
    val io = IO(new MiddleendIO(frontendParams, backendParams, issueParams, commitParams))

    private def unifiedTag(isFp: Boolean, index: UInt): UInt =
        Cat(isFp.B, index.pad(backendParams.physWidth))

    /* This compacting register is the only Decode/Rename-to-Dispatch state boundary. */
    val renameStageEntries = Reg(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    val renameStageValid = RegInit(0.U(width.W))
    val accepted = Wire(UInt(width.W))

    val instructions = io.frontend.out.map(_.bits.instruction)
    val decoders = Seq.fill(width)(Module(new Decoder(frontendParams)))
    decoders.zip(instructions).foreach { case (decoder, instruction) => decoder.io.in := instruction }

    /* Decode and Rename run in parallel before the shared buffer boundary. */
    val integerRename = Module(new Rename(intParams))
    val floatingRename = Module(new Rename(fpParams))
    val renames = Seq(integerRename -> false, floatingRename -> true)
    for ((rename, isFp) <- renames; lane <- 0 until width) {
        val instruction = instructions(lane)
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
    io.commit.request.store := VecInit(renameStageEntries.map(_.dispatchClass.storeOrAtomic)).asUInt
    io.commit.request.packetStart := VecInit(renameStageEntries.map(_.context.packetStart)).asUInt
    FIFOUtil.assertPrefix(io.commit.resourcePrefix.asBools, "Commit resource permission must be a prefix")

    /* Admission remains an ordered prefix across FQ space and both physical-register domains. */
    val decodeCandidate = VecInit(io.frontend.out.map(_.valid))
    val decodeNeedsInt = VecInit((0 until width).map { lane =>
        decodeCandidate(lane) && instructions(lane).rinfo.dest.valid &&
            !instructions(lane).rinfo.dest.isFp
    })
    val decodeNeedsFp = VecInit((0 until width).map { lane =>
        decodeCandidate(lane) && instructions(lane).rinfo.dest.valid &&
            instructions(lane).rinfo.dest.isFp
    })
    val acceptedCount = PopCount(accepted)
    val retainedCount = PopCount(renameStageValid) - acceptedCount
    val renameStageFree = width.U - retainedCount
    val laneRenameReady = VecInit((0 until width).map { lane =>
        (!decodeNeedsInt(lane) || integerRename.io.freePrefix(lane)) &&
            (!decodeNeedsFp(lane) || floatingRename.io.freePrefix(lane))
    })
    val decodeGrant = VecInit((0 until width).map { lane =>
        val requested = PopCount(decodeCandidate.take(lane + 1))
        decodeCandidate(lane) && renameStageFree >= requested && !io.commit.flush && !io.commit.restore &&
            laneRenameReady.take(lane + 1).reduce(_ && _)
    }).asUInt
    integerRename.io.prepare := decodeGrant
    floatingRename.io.prepare := decodeGrant
    integerRename.io.allocate := decodeGrant
    floatingRename.io.allocate := decodeGrant
    integerRename.io.restore := io.commit.restore
    floatingRename.io.restore := io.commit.restore

    /* Assemble the registered payload once Decode and the two rename domains have completed. */
    val renameIncoming = Wire(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    for (lane <- 0 until width) {
        val instruction = instructions(lane)
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
        renameIncoming(lane).dispatchClass.noIssue := decoded.exception.valid || commitSystem
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
    val readyBoard = Module(new ReadyBoard(backendParams, width, issueParams.wakeupPorts))
    val memoryReadyBoard = Module(new ReadyBoard(backendParams, width, issueParams.wakeupPorts, numSources = 2))
    readyBoard.io.wakeup := io.backend.wakeup
    memoryReadyBoard.io.wakeup := io.backend.memoryWakeup
    readyBoard.io.speculation := io.backend.speculation
    memoryReadyBoard.io.speculation := io.backend.speculation
    readyBoard.io.flush := io.commit.flush
    memoryReadyBoard.io.flush := io.commit.flush
    val physicalInfo = Wire(Vec(width, new BackendRenameInfo(backendParams)))
    val memoryPhysicalInfo = Wire(Vec(width, new BackendRenameInfo(backendParams)))
    for (lane <- 0 until width) {
        val instruction = renameStageEntries(lane).context.instruction
        val stored = renameStageEntries(lane).physical
        for (source <- 0 until 3) {
            val operand = instruction.rinfo.src(source)
            readyBoard.io.query(lane).prs(source) := stored.prs(source)
            // A same-group producer may finish before its consumer reaches
            // Dispatch, so every live source observes the current ReadyBoard state.
            readyBoard.io.query(lane).valid(source) := renameStageValid(lane) && operand.valid
        }
        for (source <- 0 until 2) {
            memoryReadyBoard.io.query(lane).prs(source) := stored.prs(source)
            memoryReadyBoard.io.query(lane).valid(source) := readyBoard.io.query(lane).valid(source)
        }
        physicalInfo(lane).prs := stored.prs
        physicalInfo(lane).sourceReady := readyBoard.io.state(lane).ready
        physicalInfo(lane).sourceSpecMask := readyBoard.io.state(lane).specMask
        physicalInfo(lane).prd := stored.prd
        memoryPhysicalInfo(lane).prs := stored.prs
        memoryPhysicalInfo(lane).sourceReady := VecInit(
            memoryReadyBoard.io.state(lane).ready :+ true.B
        )
        memoryPhysicalInfo(lane).sourceSpecMask := VecInit(
            memoryReadyBoard.io.state(lane).specMask :+ 0.U(backendParams.specWidth.W)
        )
        memoryPhysicalInfo(lane).prd := stored.prd
    }

    /* Dispatch sees complete backend packages and emits one indexed group per issue queue. */
    val dispatcher = Module(new Dispatcher(backendParams, issueParams))
    dispatcher.io.in.valid := renameStageValid
    dispatcher.io.dispatchClass := VecInit(renameStageEntries.map(_.dispatchClass))
    for (lane <- 0 until width) {
        dispatcher.io.in.entries(lane) := BackendPackage.fromFrontend(
            renameStageEntries(lane).context.instruction,
            physicalInfo(lane),
            io.commit.allocation(lane),
            backendParams,
        )
        dispatcher.io.memoryEntries(lane) := BackendPackage.fromFrontend(
            renameStageEntries(lane).context.instruction,
            memoryPhysicalInfo(lane),
            io.commit.allocation(lane),
            backendParams,
        )
    }
    dispatcher.io.resourcePrefix := io.commit.resourcePrefix
    dispatcher.io.freeCount := io.backend.freeCount
    dispatcher.io.clearPreference := io.commit.flush
    io.backend.enqueue := dispatcher.io.enqueue

    /* A fetch packet may span dispatch groups, so continuation lanes reuse its allocated FTQ index. */
    accepted := dispatcher.io.accepted
    val openPacketValid = RegInit(false.B)
    val openPacketFtqIdx = RegInit(0.U(frontendParams.ftqBits.W))
    val dispatchedFtqIdx = Wire(Vec(width, UInt(frontendParams.ftqBits.W)))
    var precedingFtqIdx = openPacketFtqIdx
    for (lane <- 0 until width) {
        val context = renameStageEntries(lane).context
        dispatchedFtqIdx(lane) := Mux(context.packetStart, io.commit.ftqIdx(lane), precedingFtqIdx)
        precedingFtqIdx = Mux(renameStageValid(lane) && context.packetStart, io.commit.ftqIdx(lane), precedingFtqIdx)
    }
    /* Accepted lanes update readiness and enter Commit atomically with their backend issue tasks. */
    for (lane <- 0 until width) {
        val destination = renameStageEntries(lane).context.instruction.rinfo.dest
        val incomingDestination = instructions(lane).rinfo.dest
        readyBoard.io.allocate(lane).valid := decodeGrant(lane) && incomingDestination.valid
        readyBoard.io.allocate(lane).bits := renameIncoming(lane).physical.prd
        memoryReadyBoard.io.allocate(lane) := readyBoard.io.allocate(lane)
        io.commit.enqueue.entries(lane).context := renameStageEntries(lane).context
        io.commit.enqueue.entries(lane).context.ftqIdx := dispatchedFtqIdx(lane)
        io.commit.enqueue.entries(lane).destination.rd := destination.index
        io.commit.enqueue.entries(lane).destination.isFp := destination.isFp
        io.commit.enqueue.entries(lane).destination.prd := physicalInfo(lane).prd
        io.commit.enqueue.entries(lane).destination.pprd := renameStageEntries(lane).physical.pprd
        io.commit.enqueue.entries(lane).allocation := io.commit.allocation(lane)
        io.commit.ftqAllocate(lane).valid := accepted(lane) && renameStageEntries(lane).context.packetStart &&
            !io.commit.flush
        io.commit.ftqAllocate(lane).bits.record := renameStageEntries(lane).context.ftqRecord
    }
    io.commit.enqueue.valid := accepted

    val acceptedAny = accepted.orR
    val lastAccepted = VecInit.tabulate(width)(lane => acceptedAny && acceptedCount === (lane + 1).U).asUInt
    val lastAcceptedEnd = Mux1H(lastAccepted.asBools, renameStageEntries.map(_.context.packetEnd))
    val lastAcceptedFtqIdx = Mux1H(lastAccepted.asBools, dispatchedFtqIdx)
    when(io.commit.flush) {
        openPacketValid := false.B
    }.elsewhen(acceptedAny) {
        openPacketValid := !lastAcceptedEnd
        openPacketFtqIdx := lastAcceptedFtqIdx
    }

    /* Rename-to-Dispatch is one compacting pipeline register. */
    val nextRenameEntries = Wire(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    val grantCount = PopCount(decodeGrant)
    for (position <- 0 until width) {
        val retained = WireDefault(0.U.asTypeOf(new MiddleendRenameEntry(frontendParams, backendParams)))
        when(position.U < retainedCount) {
            retained := Mux1H((0 until width).map { source =>
                (acceptedCount + position.U === source.U) -> renameStageEntries(source)
            })
        }
        val incomingSelect = VecInit((0 until width).map { lane =>
            val rank = if (lane == 0) 0.U else PopCount(decodeGrant.take(lane))
            decodeGrant(lane) && retainedCount + rank === position.U
        })
        nextRenameEntries(position) := Mux(
            position.U < retainedCount,
            retained,
            Mux1H(incomingSelect, renameIncoming),
        )
    }
    when(io.commit.flush) {
        renameStageValid := 0.U
    }.otherwise {
        renameStageValid := VecInit((0 until width).map(position =>
            position.U < retainedCount + grantCount
        )).asUInt
        renameStageEntries := nextRenameEntries
    }

    /* FQ dequeue follows the same prefix grant that enters the rename-stage register. */
    for (lane <- 0 until width) {
        io.frontend.out(lane).ready := decodeGrant(lane) && !io.commit.flush
    }
    assert((accepted & ~renameStageValid) === 0.U, "Dispatch cannot consume an invalid renamed instruction")
    FIFOUtil.assertPrefix(accepted.asBools, "Middleend acceptance must remain an ordered prefix")
    FIFOUtil.assertPrefix(decodeGrant.asBools, "Middleend decode must consume an ordered prefix")
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
