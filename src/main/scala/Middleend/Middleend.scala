import chisel3._
import chisel3.util._
import ZirconConfig._

class MiddleendInstruction(p: FrontendParams) extends Bundle {
    val fetchToken = UInt(32.W)
    val ftqIdx = UInt(p.ftqBits.W)
    val slot = UInt(p.slotBits.W)
    val packetEnd = Bool()
    val instruction = new FrontendInstruction(p)
}

class MiddleendPhysicalInfo(p: BackendParams) extends Bundle {
    val prs = Vec(3, UInt(p.tagWidth.W))
    val sourceIndependent = Vec(3, Bool())
    val prd = UInt(p.tagWidth.W)
    val pprd = UInt(p.tagWidth.W)
}

class MiddleendRenameEntry(fp: FrontendParams, bp: BackendParams) extends Bundle {
    val context = new MiddleendInstruction(fp)
    val physical = new MiddleendPhysicalInfo(bp)
}

class MiddleendFetchPacket(p: FrontendParams) extends Bundle {
    val fetchToken = UInt(32.W)
    val ftqIdx = UInt(p.ftqBits.W)
    val mask = UInt(p.fetchWidth.W)
    val instructions = Vec(p.fetchWidth, new FrontendInstruction(p))
}

class MiddleendCommitDestination(p: BackendParams) extends Bundle {
    val rd = UInt(5.W)
    val isFp = Bool()
    val prd = UInt(p.tagWidth.W)
    val pprd = UInt(p.tagWidth.W)
}

class MiddleendCommitEntry(fp: FrontendParams, bp: BackendParams) extends Bundle {
    val context = new MiddleendInstruction(fp)
    val destination = new MiddleendCommitDestination(bp)
    val allocation = new BackendAllocation(bp)
}

class MiddleendCommitRequest(fp: FrontendParams, width: Int) extends Bundle {
    val valid = UInt(width.W)
    val store = UInt(width.W)
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
    val ftqAllocate = Decoupled(new FrontendFtqAllocation(fp))
    val ftqIdx = Input(UInt(fp.ftqBits.W))
    val enqueue = Output(new MiddleendCommitEnqueue(fp, bp, dispatchWidth))
    val retire = Input(Vec(commitWidth, Valid(new MiddleendCommitDestination(bp))))
    val flush = Input(Bool())
    // Restore occurs after the last retirement update belonging to a flush.
    val restore = Input(Bool())
}

class MiddleendPerformanceCounters extends Bundle {
    val integerFreeListBlockedCycles = UInt(64.W)
    val floatingFreeListBlockedCycles = UInt(64.W)
    val dispatchBlockedCycles = UInt(64.W)
    val ftqBlockedCycles = UInt(64.W)
}

class MiddleendIO(
    fp: FrontendParams,
    bp: BackendParams,
    issue: IssueParams,
    commitParams: CommitParams,
) extends Bundle {
    val frontend = Flipped(new FrontendMiddleIO(fp))
    val backend = Flipped(new BackendMiddleendIO(bp, issue))
    val commit = new CommitMiddleendIO(fp, bp, issue.dispatchWidth, commitParams.width)
    val csr = Input(new CSRState)
    val performance = if (fp.observe) Some(Output(new MiddleendPerformanceCounters)) else None
}

/** Decode, rename, readiness lookup and parallel dispatch.
  *
  * A Rename-to-Dispatch pipeline register keeps RAT lookup and physical
  * allocation out of the dispatch path. An active packet and one skid packet keep
  * Frontend ready independent of the current dispatch decision. Backpressure
  * retains every renamed instruction until dispatch accepts it.
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

    /* Fetch packet holding and instruction selection. */
    val activePacket = Reg(new MiddleendFetchPacket(frontendParams))
    val activeValid = RegInit(false.B)
    val skidPacket = Reg(new MiddleendFetchPacket(frontendParams))
    val skidValid = RegInit(false.B)
    val incomingPacket = Wire(new MiddleendFetchPacket(frontendParams))
    incomingPacket.fetchToken := io.frontend.out.bits.fetchToken
    incomingPacket.ftqIdx := io.commit.ftqIdx
    incomingPacket.mask := io.frontend.out.bits.mask
    incomingPacket.instructions := io.frontend.out.bits.instructions

    val renameStageEntries = Reg(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    val renameStageValid = RegInit(0.U(width.W))
    val renameValid = renameStageValid
    val accepted = Wire(UInt(width.W))

    val selectedMasks = Wire(Vec(width, UInt(frontendParams.fetchWidth.W)))
    val selectedInstructions = Wire(Vec(width, new FrontendInstruction(frontendParams)))
    for (lane <- 0 until width) {
        selectedMasks(lane) := VecInit((0 until frontendParams.fetchWidth).map { slot =>
            val older = if (slot == 0) 0.U else PopCount(activePacket.mask(slot - 1, 0))
            activePacket.mask(slot) && older === lane.U
        }).asUInt
        selectedInstructions(lane) := Mux(
            activeValid && selectedMasks(lane).orR,
            Mux1H(selectedMasks(lane).asBools, activePacket.instructions),
            0.U.asTypeOf(new FrontendInstruction(frontendParams)),
        )
    }
    val decoders = Seq.fill(width)(Module(new Decoder(frontendParams)))
    for (lane <- 0 until width) {
        decoders(lane).io.in := selectedInstructions(lane)
    }

    /* Decode and Rename run in parallel before the shared buffer boundary. */
    val integerRename = Module(new Rename(intParams))
    val floatingRename = Module(new Rename(fpParams))
    val renames = Seq(integerRename -> false, floatingRename -> true)
    for ((rename, isFp) <- renames; lane <- 0 until width) {
        val instruction = selectedInstructions(lane)
        val rinfo = rename.io.rinfo(lane)
        val candidate = activeValid && selectedMasks(lane).orR
        rinfo.request := candidate && instruction.rinfo.dest.valid &&
            instruction.rinfo.dest.isFp === isFp.B
        rinfo.rd := instruction.rinfo.dest.index
        for (source <- 0 until 3) {
            rinfo.src(source) := instruction.rinfo.src(source).index
            rinfo.srcValid(source) := candidate && instruction.rinfo.src(source).valid &&
                instruction.rinfo.src(source).isFp === isFp.B
        }
    }

    io.commit.request.valid := Mux(io.commit.flush, 0.U, renameValid)
    io.commit.request.store := VecInit(renameStageEntries.map(
        _.context.instruction.fu === DecodeUnit.Store.U
    )).asUInt
    FIFOUtil.assertPrefix(io.commit.resourcePrefix.asBools, "Commit resource permission must be a prefix")

    val decodeCandidate = VecInit(selectedMasks.map(mask => activeValid && mask.orR))
    val decodeNeedsInt = VecInit((0 until width).map { lane =>
        decodeCandidate(lane) && selectedInstructions(lane).rinfo.dest.valid &&
            !selectedInstructions(lane).rinfo.dest.isFp
    })
    val decodeNeedsFp = VecInit((0 until width).map { lane =>
        decodeCandidate(lane) && selectedInstructions(lane).rinfo.dest.valid &&
            selectedInstructions(lane).rinfo.dest.isFp
    })
    val decodeAllowed = !io.commit.flush && !io.commit.restore
    val acceptedCount = PopCount(accepted)
    val retainedCount = PopCount(renameValid) - acceptedCount
    val renameStageFree = width.U - retainedCount
    val laneRenameReady = VecInit((0 until width).map { lane =>
        (!decodeNeedsInt(lane) || integerRename.io.freePrefix(lane)) &&
            (!decodeNeedsFp(lane) || floatingRename.io.freePrefix(lane))
    })
    val decodeGrant = VecInit((0 until width).map { lane =>
        val requested = PopCount(decodeCandidate.take(lane + 1))
        decodeCandidate(lane) && renameStageFree >= requested && decodeAllowed &&
            laneRenameReady.take(lane + 1).reduce(_ && _)
    }).asUInt
    integerRename.io.prepare := decodeGrant
    floatingRename.io.prepare := decodeGrant
    integerRename.io.allocate := decodeGrant
    floatingRename.io.allocate := decodeGrant
    integerRename.io.restore := io.commit.restore
    floatingRename.io.restore := io.commit.restore

    val renameIncoming = Wire(Vec(width, new MiddleendRenameEntry(frontendParams, backendParams)))
    for (lane <- 0 until width) {
        val instruction = selectedInstructions(lane)
        val intInfo = integerRename.io.pinfo(lane)
        val fpInfo = floatingRename.io.pinfo(lane)
        renameIncoming(lane).context.fetchToken := activePacket.fetchToken
        renameIncoming(lane).context.ftqIdx := activePacket.ftqIdx
        renameIncoming(lane).context.slot := OHToUInt(selectedMasks(lane))
        renameIncoming(lane).context.packetEnd := VecInit((0 until frontendParams.fetchWidth).map { slot =>
            val later = if (slot + 1 < frontendParams.fetchWidth) {
                activePacket.mask(frontendParams.fetchWidth - 1, slot + 1).orR
            } else {
                false.B
            }
            selectedMasks(lane)(slot) && !later
        }).asUInt.orR
        val decoderOut = decoders(lane).io.out
        val decoded = WireDefault(decoderOut)
        val usesFp = decoderOut.rinfo.dest.valid && decoderOut.rinfo.dest.isFp ||
            decoderOut.rinfo.src.map(source => source.valid && source.isFp).reduce(_ || _)
        val dynamicRounding = decoderOut.rm === 7.U
        val badRounding = dynamicRounding && io.csr.frm > 4.U
        when(!decoderOut.exception.valid && usesFp && (io.csr.mstatus(14, 13) === 0.U || badRounding)) {
            decoded.fu := DecodeUnit.None.U
            decoded.exception.valid := true.B
            decoded.exception.cause := DecodeException.IllegalInstruction.U
            decoded.exception.tval := decoded.inst
        }
        when(dynamicRounding && !badRounding) {
            decoded.rm := io.csr.frm
        }
        renameIncoming(lane).context.instruction := decoded
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
            renameIncoming(lane).physical.sourceIndependent(source) := !operand.valid || Mux(
                operand.isFp,
                fpInfo.sourceIndependent(source),
                intInfo.sourceIndependent(source),
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
    val memoryReadyBoard = Module(new ReadyBoard(backendParams, width, issueParams.wakeupPorts))
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
            physicalInfo(lane).prs(source) := stored.prs(source)
            physicalInfo(lane).sourceIndependent(source) := stored.sourceIndependent(source)
            readyBoard.io.query(lane).prs(source) := physicalInfo(lane).prs(source)
            // A same-group producer may finish before its consumer reaches
            // Dispatch, so every live source observes the current ReadyBoard state.
            readyBoard.io.query(lane).valid(source) := renameValid(lane) && operand.valid
            physicalInfo(lane).sourceReady(source) := readyBoard.io.state(lane).ready(source)
            physicalInfo(lane).sourceSpecMask(source) := readyBoard.io.state(lane).specMask(source)
            memoryPhysicalInfo(lane).prs(source) := physicalInfo(lane).prs(source)
            memoryPhysicalInfo(lane).sourceIndependent(source) := physicalInfo(lane).sourceIndependent(source)
            memoryPhysicalInfo(lane).sourceReady(source) := memoryReadyBoard.io.state(lane).ready(source)
            memoryPhysicalInfo(lane).sourceSpecMask(source) := memoryReadyBoard.io.state(lane).specMask(source)
        }
        memoryReadyBoard.io.query(lane) := readyBoard.io.query(lane)
        physicalInfo(lane).prd := stored.prd
        memoryPhysicalInfo(lane).prd := stored.prd
    }

    val dispatcher = Module(new Dispatcher(backendParams, issueParams))
    dispatcher.io.in.valid := renameValid
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
    dispatcher.io.resourcePrefix := io.commit.resourcePrefix &
        Fill(width, !io.commit.flush && !io.commit.restore)
    dispatcher.io.freeCount := io.backend.freeCount
    dispatcher.io.flush := io.commit.flush || io.commit.restore
    io.backend.arith0 := dispatcher.io.arith0
    io.backend.arith1 := dispatcher.io.arith1
    io.backend.mixArith := dispatcher.io.mixArith
    io.backend.load := dispatcher.io.load
    io.backend.loadStoreAddress := dispatcher.io.loadStoreAddress
    io.backend.storeData := dispatcher.io.storeData

    accepted := dispatcher.io.accepted
    for (lane <- 0 until width) {
        val destination = renameStageEntries(lane).context.instruction.rinfo.dest
        val incomingDestination = selectedInstructions(lane).rinfo.dest
        readyBoard.io.allocate(lane).valid := decodeGrant(lane) && incomingDestination.valid
        readyBoard.io.allocate(lane).bits := renameIncoming(lane).physical.prd
        memoryReadyBoard.io.allocate(lane) := readyBoard.io.allocate(lane)
        io.commit.enqueue.entries(lane).context := renameStageEntries(lane).context
        io.commit.enqueue.entries(lane).destination.rd := destination.index
        io.commit.enqueue.entries(lane).destination.isFp := destination.isFp
        io.commit.enqueue.entries(lane).destination.prd := physicalInfo(lane).prd
        io.commit.enqueue.entries(lane).destination.pprd := renameStageEntries(lane).physical.pprd
        io.commit.enqueue.entries(lane).allocation := io.commit.allocation(lane)
    }
    io.commit.enqueue.valid := accepted

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
        val issueQueueFull = io.backend.freeCount.map(_ === 0.U).reduce(_ || _)
        when(issueQueueFull) { dispatchBlockedCycles := dispatchBlockedCycles + 1.U }
        when(io.frontend.out.valid && !io.commit.ftqAllocate.ready && !io.commit.flush) {
            ftqBlockedCycles := ftqBlockedCycles + 1.U
        }
        io.performance.get.integerFreeListBlockedCycles := integerFreeListBlockedCycles
        io.performance.get.floatingFreeListBlockedCycles := floatingFreeListBlockedCycles
        io.performance.get.dispatchBlockedCycles := dispatchBlockedCycles
        io.performance.get.ftqBlockedCycles := ftqBlockedCycles
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

    val consumedMask = (0 until width).map { lane =>
        Mux(decodeGrant(lane), selectedMasks(lane), 0.U)
    }.reduce(_ | _)
    val remainingMask = activePacket.mask & ~consumedMask
    val activeFinishes = activeValid && consumedMask.orR && !remainingMask.orR
    io.frontend.out.ready := !skidValid && io.commit.ftqAllocate.ready && !io.commit.flush
    val frontendFire = io.frontend.out.fire
    io.commit.ftqAllocate.valid := io.frontend.out.valid && !skidValid && !io.commit.flush
    io.commit.ftqAllocate.bits.fetchToken := io.frontend.out.bits.fetchToken
    io.commit.ftqAllocate.bits.record := io.frontend.out.bits.record
    assert(frontendFire === io.commit.ftqAllocate.fire, "FQ transfer and FTQ allocation must be atomic")
    when(io.commit.flush) {
        activeValid := false.B
        skidValid := false.B
    }.otherwise {
        when(activeValid && consumedMask.orR) {
            activePacket.mask := remainingMask
        }
        when(activeFinishes) {
            when(skidValid) {
                activePacket := skidPacket
                activeValid := true.B
                skidValid := false.B
            }.otherwise {
                activeValid := false.B
            }
        }
        when(frontendFire) {
            when(!activeValid || (activeFinishes && !skidValid)) {
                activePacket := incomingPacket
                activeValid := true.B
            }.otherwise {
                skidPacket := incomingPacket
                skidValid := true.B
            }
        }
    }

    when(frontendFire) {
        assert(io.frontend.out.bits.mask.orR, "Frontend must not enqueue an empty fetch packet")
    }
    assert(!skidValid || activeValid, "A skid packet requires an active packet")
    assert((accepted & ~renameValid) === 0.U, "Dispatch cannot consume an invalid renamed instruction")
    FIFOUtil.assertPrefix(accepted.asBools, "Middleend acceptance must remain an ordered prefix")
    for (lane <- 0 until commitParams.width) {
        when(io.commit.retire(lane).valid) {
            val destination = io.commit.retire(lane).bits
            assert(
                destination.prd(backendParams.tagWidth - 1) === destination.isFp &&
                    destination.pprd(backendParams.tagWidth - 1) === destination.isFp,
                "Retired physical tags must match the architectural register domain",
            )
            val prdIndex = destination.prd(backendParams.physWidth - 1, 0)
            val pprdIndex = destination.pprd(backendParams.physWidth - 1, 0)
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
}
