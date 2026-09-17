import chisel3._
import chisel3.util._
import ZirconConfig._

/**
  * One physical slot in the compact issue queue.
  *
  * BackendPackage owns all instruction state, including operand readiness. IQEntry
  * marks the storage boundary and leaves room for queue-only state such as replay
  * metadata without changing the dispatch or pipeline interfaces.
  */
class IQEntry(p: BackendParams) extends Bundle {
    val item = new BackendPackage(p)
    // Loads use this bit in the main queue; replay slots use it for an outstanding attempt.
    val issued = Bool()
}

/** Dispatch provides a packed prefix so accepted instructions retain program order. */
class IssueEnqueueGroup(p: BackendParams, width: Int) extends Bundle {
    val valid = UInt(width.W)
    val entries = Vec(width, new BackendPackage(p))
}

/** Authorize one CSR instruction after older instructions have become harmless. */
class CsrIssueGrant(p: BackendParams) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
}

class IssueQueueFeedback(p: BackendParams) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
}

class IssueQueueIO(p: BackendParams, q: IssueQueueParams) extends Bundle {
    val enq = Input(new IssueEnqueueGroup(p, q.enqueueWidth))
    val issue = Decoupled(new BackendPackage(p))
    val wakeup = Input(Vec(q.wakeupPorts, new BackendWakeup(p)))
    val speculation = Input(new SpeculationResolution(p))
    val flush = Input(Bool())
    val freeCount = Output(UInt(log2Ceil(q.entries + 1).W))
    val occupancy = Output(UInt(log2Ceil(q.entries + 1).W))
    val replayOccupancy = Output(UInt(log2Ceil(math.max(q.replayEntries, 1) + 1).W))
    val replayBlocked = Output(Bool())
    val operandBlocked = Output(Bool())
    val resourceBlocked = Output(Bool())

    val completed = if (q.profile == IssueQueueProfile.Load || q.profile == IssueQueueProfile.LoadStoreAddress)
        Some(Flipped(Valid(new IssueQueueFeedback(p))))
    else None
    val retry = if (q.profile == IssueQueueProfile.Load || q.profile == IssueQueueProfile.LoadStoreAddress)
        Some(Flipped(Valid(new IssueQueueFeedback(p))))
    else None

    // These ports describe changing execution resources, so they do not belong in BackendPackage.
    val csrGrant = if (q.profile == IssueQueueProfile.MixArith) {
        Some(Flipped(Valid(new CsrIssueGrant(p))))
    } else None
    val mixAvailable = if (q.profile == IssueQueueProfile.MixArith) {
        Some(Input(Bool()))
    } else None
}

/**
  * Compact, oldest-ready issue queue.
  *
  * Entries are stored in program order. A priority encoder therefore selects the
  * oldest ready instruction without an age-comparison tree. Stable rank-based packing
  * removes normal issues and completed Loads, then appends the packed dispatch group
  * at the tail. Arithmetic profiles use a small non-compacting replay array for
  * speculative recovery copies, keeping confirmation off the compact queue update.
  */
class IssueQueue(
    val p: BackendParams = BackendParams(),
    val q: IssueQueueParams = IssueParams().arith1,
) extends Module {
    override def desiredName: String = s"${q.profile.label}IssueQueue"

    val io = IO(new IssueQueueIO(p, q))

    // count defines the valid prefix. IQEntry needs no duplicated valid bit.
    val entries = Reg(Vec(q.entries, new IQEntry(p)))
    val count = RegInit(0.U(log2Ceil(q.entries + 1).W))
    private val replayDepth = math.max(q.replayEntries, 1)
    val replayEntries = Reg(Vec(replayDepth, new IQEntry(p)))
    val replayValid = RegInit(VecInit.fill(replayDepth)(false.B))

    // Decoupled requires issue.bits to remain stable while the consumer is stalled.
    val selectionLocked = RegInit(false.B)
    val lockedItem = Reg(new BackendPackage(p))

    io.occupancy := count
    io.replayOccupancy := (if (q.replayEntries > 0) PopCount(replayValid.take(q.replayEntries)) else 0.U)

    /** Normalize unused sources and absorb same-cycle wakeups into the stored package. */
    private def updateReady(item: BackendPackage): BackendPackage = {
        val updated = WireDefault(item)
        for (source <- 0 until 3) {
            val usedByProfile = (q.profile.sourceMask & (1 << source)) != 0
            if (!usedByProfile) {
                updated.prs(source) := 0.U
                updated.sourceValid(source) := false.B
                updated.sourceReady(source) := true.B
                updated.sourceSpecMask(source) := 0.U
                updated.source(source) := 0.U
            } else {
                val hits = io.wakeup.map(wakeup => wakeup.prd =/= 0.U && wakeup.prd === item.prs(source))
                val wake = VecInit(hits).asUInt.orR
                val wakeMask = io.wakeup.zip(hits).map { case (event, hit) =>
                    Mux(hit, event.specMask, 0.U)
                }.reduce(_ | _)
                val failed = (item.sourceSpecMask(source) & io.speculation.failedMask).orR
                updated.sourceSpecMask(source) := item.sourceSpecMask(source) & ~io.speculation.resolvedMask
                when(!item.sourceValid(source)) {
                    updated.sourceReady(source) := true.B
                    updated.sourceSpecMask(source) := 0.U
                }.elsewhen(wake) {
                    updated.sourceReady(source) := !(wakeMask & io.speculation.failedMask).orR
                    updated.sourceSpecMask(source) := wakeMask & ~io.speculation.resolvedMask
                }.elsewhen(failed) {
                    updated.sourceReady(source) := false.B
                    updated.sourceSpecMask(source) := 0.U
                }
            }
        }
        updated
    }

    /** Check that dispatch routed the operation to a queue serving its unit. */
    private def allowed(item: BackendPackage): Bool = {
        val unitAllowed = VecInit(q.profile.allowedUnits.toSeq.sorted.map(item.fu === _.U)).asUInt.orR
        q.profile match {
            case IssueQueueProfile.MixArith =>
                val csr = item.fu === DecodeUnit.System.U
                val csrOp = item.op >= SystemOp.CSRRW.U && item.op <= SystemOp.CSRRCI.U
                unitAllowed && (!csr || csrOp)
            case _ => unitAllowed
        }
    }

    /** Gate an otherwise-ready instruction on the state of its destination unit. */
    private val usedSources = (0 until 3).filter(source => (q.profile.sourceMask & (1 << source)) != 0)
    private def speculationMask(item: BackendPackage): UInt =
        usedSources.map(item.sourceSpecMask(_)).reduce(_ | _)

    private def resourceReady(item: BackendPackage): Bool = q.profile match {
        case IssueQueueProfile.MixArith =>
            val csr = item.fu === DecodeUnit.System.U
            val grant = io.csrGrant.get
            val csrAuthorized = !csr || (grant.valid && grant.bits.robIdx === item.robIdx)
            io.mixAvailable.get && !speculationMask(item).orR && csrAuthorized
        case IssueQueueProfile.Load | IssueQueueProfile.LoadStoreAddress | IssueQueueProfile.StoreData =>
            !speculationMask(item).orR
        case _ => true.B
    }

    private def operandsReady(item: BackendPackage): Bool = (0 until 3).map { source =>
        val usedByProfile = (q.profile.sourceMask & (1 << source)) != 0
        if (usedByProfile) !item.sourceValid(source) || item.sourceReady(source) else true.B
    }.reduce(_ && _)

    val updatedReplay = Wire(Vec(replayDepth, new IQEntry(p)))
    val replayFree = Wire(Vec(replayDepth, Bool()))
    val replayCandidates = Wire(Vec(replayDepth, Bool()))
    for (position <- 0 until replayDepth) {
        updatedReplay(position).item := updateReady(replayEntries(position).item)
        updatedReplay(position).issued := replayEntries(position).issued
        val activePosition = (position < q.replayEntries).B
        val failed = (speculationMask(replayEntries(position).item) & io.speculation.failedMask).orR
        replayFree(position) := activePosition && !replayValid(position)
        replayCandidates(position) := activePosition && replayValid(position) && !replayEntries(position).issued &&
            operandsReady(replayEntries(position).item) && resourceReady(replayEntries(position).item) && !failed
    }
    val replayAvailable = replayFree.asUInt.orR
    val replaySelect = PriorityEncoderOH(replayCandidates.asUInt)

    // The replay array has priority. Main IQ entries still use physical order as program age.
    val candidates = Wire(Vec(q.entries, Bool()))
    val replayBlocked = Wire(Vec(q.entries, Bool()))
    val operandBlocked = Wire(Vec(q.entries, Bool()))
    val resourceBlocked = Wire(Vec(q.entries, Bool()))
    for (position <- 0 until q.entries) {
        val item = entries(position).item
        val olderCSR = if (q.profile == IssueQueueProfile.MixArith && position > 0) {
            (0 until position).map { older =>
                older.U < count && entries(older).item.fu === DecodeUnit.System.U
            }.reduce(_ || _)
        } else {
            false.B
        }
        val checkpointAvailable = !speculationMask(item).orR || replayAvailable
        val notIssued = q.profile match {
            case IssueQueueProfile.Load | IssueQueueProfile.LoadStoreAddress => !entries(position).issued
            case _ => true.B
        }
        candidates(position) := position.U < count && !olderCSR && notIssued && operandsReady(item) &&
            resourceReady(item) && checkpointAvailable
        replayBlocked(position) := position.U < count && !olderCSR && notIssued && operandsReady(item) &&
            resourceReady(item) && !checkpointAvailable
        operandBlocked(position) := position.U < count && !olderCSR && notIssued && !operandsReady(item)
        resourceBlocked(position) := position.U < count && !olderCSR && notIssued && operandsReady(item) &&
            !resourceReady(item)
    }
    io.replayBlocked := replayBlocked.asUInt.orR
    io.operandBlocked := operandBlocked.asUInt.orR || VecInit((0 until replayDepth).map { position =>
        (position < q.replayEntries).B && replayValid(position) && !replayEntries(position).issued &&
            !operandsReady(replayEntries(position).item)
    }).asUInt.orR
    io.resourceBlocked := resourceBlocked.asUInt.orR || VecInit((0 until replayDepth).map { position =>
        (position < q.replayEntries).B && replayValid(position) && !replayEntries(position).issued &&
            operandsReady(replayEntries(position).item) && !resourceReady(replayEntries(position).item)
    }).asUInt.orR

    val mainSelect = PriorityEncoderOH(candidates.asUInt)
    val lockedMainSelect = VecInit((0 until q.entries).map { position =>
        position.U < count && entries(position).item.robIdx === lockedItem.robIdx
    }).asUInt
    val lockedReplaySelect = VecInit((0 until replayDepth).map { position =>
        replayValid(position) && replayEntries(position).item.robIdx === lockedItem.robIdx
    }).asUInt
    val selectedReplay = Mux(selectionLocked, lockedReplaySelect, replaySelect)
    val selectedMain = Mux(selectionLocked, lockedMainSelect, Mux(replaySelect.orR, 0.U, mainSelect))
    val selectedExists = selectedReplay.orR || selectedMain.orR
    val selectedItem = Mux(
        selectionLocked,
        lockedItem,
        Mux(
            selectedReplay.orR,
            Mux1H(selectedReplay.asBools, replayEntries.map(_.item)),
            Mux(
                selectedMain.orR,
                Mux1H(selectedMain.asBools, entries.map(_.item)),
                0.U.asTypeOf(new BackendPackage(p)),
            ),
        ),
    )
    val selectedFailed = selectedExists && (speculationMask(selectedItem) & io.speculation.failedMask).orR
    val lockedFailed = selectionLocked && selectedFailed
    io.issue.valid := !io.flush && !selectedFailed && selectedExists
    io.issue.bits := selectedItem
    val issueFire = io.issue.fire

    // Wake stored entries before either normal compaction or flush packing.
    val awakened = Wire(Vec(q.entries, new IQEntry(p)))
    for (position <- 0 until q.entries) {
        awakened(position).item := updateReady(entries(position).item)
        awakened(position).issued := entries(position).issued
    }

    // New instructions also see wakeups generated in their enqueue cycle.
    val incoming = Wire(Vec(q.enqueueWidth, new IQEntry(p)))
    for (lane <- 0 until q.enqueueWidth) {
        incoming(lane).item := updateReady(io.enq.entries(lane))
        incoming(lane).issued := false.B
    }

    // Loads keep their physical position until the corresponding pipeline reports
    // completion. Retry only re-arms the retained entry, preserving its age.
    val retained = WireDefault(awakened)
    val remove = Wire(Vec(q.entries, Bool()))
    for (position <- 0 until q.entries) {
        val live = position.U < count
        val selectedHere = issueFire && selectedMain(position)
        val issuedLoad = q.profile match {
            case IssueQueueProfile.Load => selectedHere
            case IssueQueueProfile.LoadStoreAddress =>
                selectedHere && awakened(position).item.fu === DecodeUnit.Load.U
            case _ => false.B
        }
        val completionHit = io.completed.map(feedback =>
            feedback.valid && feedback.bits.robIdx === awakened(position).item.robIdx
        ).getOrElse(false.B)
        val retryHit = io.retry.map(feedback =>
            feedback.valid && feedback.bits.robIdx === awakened(position).item.robIdx
        ).getOrElse(false.B)

        when(issuedLoad) {
            retained(position).issued := true.B
        }
        when(retryHit) {
            retained(position).issued := false.B
        }
        remove(position) := live && ((selectedHere && !issuedLoad) || completionHit)
        when(completionHit || retryHit) {
            assert(entries(position).issued, "Load feedback must name an issued IQ entry")
        }
    }

    // Speculative arithmetic leaves the compact IQ immediately. A non-compacting
    // replay slot keeps its recovery copy until all tokens succeed or it reissues.
    val nextReplay = WireDefault(updatedReplay)
    val nextReplayValid = WireDefault(replayValid)
    for (position <- 0 until replayDepth) {
        val failed = (speculationMask(replayEntries(position).item) & io.speculation.failedMask).orR
        val confirmed = replayValid(position) && replayEntries(position).issued &&
            !speculationMask(updatedReplay(position).item).orR && !failed
        when(failed) {
            nextReplay(position).issued := false.B
        }.elsewhen(confirmed) {
            nextReplayValid(position) := false.B
        }
        when(issueFire && selectedReplay(position)) {
            when(speculationMask(updatedReplay(position).item).orR) {
                nextReplay(position).issued := true.B
            }.otherwise {
                nextReplayValid(position) := false.B
            }
        }
    }
    val checkpointFire = issueFire && selectedMain.orR && speculationMask(selectedItem).orR
    val checkpointItem = updateReady(selectedItem)
    val checkpointSelect = PriorityEncoderOH(replayFree.asUInt)
    for (position <- 0 until replayDepth) {
        when(checkpointFire && checkpointSelect(position) && speculationMask(checkpointItem).orR) {
            nextReplay(position).item := checkpointItem
            nextReplay(position).issued := true.B
            nextReplayValid(position) := true.B
        }
    }

    // Stable packing handles a normal issue and an independent Load completion in
    // the same cycle without adding another selector to the issue path.
    val enqueueCount = PopCount(io.enq.valid)
    val removeCount = PopCount(remove)
    io.freeCount := q.entries.U - count + removeCount
    val countAfterIssue = count - removeCount
    val nextCount = countAfterIssue + enqueueCount
    val emptyEntry = 0.U.asTypeOf(new IQEntry(p))
    val shifted = Wire(Vec(q.entries, new IQEntry(p)))
    for (destination <- 0 until q.entries) {
        val sourceHits = (0 until q.entries).map { source =>
            val survivorRank = if (source == 0) 0.U else PopCount(remove.take(source).map(!_))
            source.U < count && !remove(source) && survivorRank === destination.U
        }
        shifted(destination) := Mux(
            VecInit(sourceHits).asUInt.orR,
            Mux1H(sourceHits, retained),
            emptyEntry
        )
    }

    // The valid-prefix contract makes each enqueue lane's tail position deterministic.
    val installed = WireDefault(shifted)
    for (lane <- 0 until q.enqueueWidth; position <- 0 until q.entries) {
        val laneRank = if (lane == 0) 0.U else PopCount(io.enq.valid(lane - 1, 0))
        when(io.enq.valid(lane) && countAfterIssue + laneRank === position.U) {
            installed(position) := incoming(lane)
        }
    }

    when(io.flush) {
        count := 0.U
        replayValid := VecInit.fill(replayDepth)(false.B)
        selectionLocked := false.B
    }.otherwise {
        entries := installed
        count := nextCount
        replayEntries := nextReplay
        replayValid := nextReplayValid
        when(lockedFailed) {
            selectionLocked := false.B
        }.elsewhen(issueFire) {
            selectionLocked := false.B
        }.elsewhen(io.issue.valid && !io.issue.ready && !selectionLocked) {
            selectionLocked := true.B
            lockedItem := io.issue.bits
        }
    }

    // Interface assertions document the assumptions that keep the compact update small.
    for (lane <- 1 until q.enqueueWidth) {
        assert(!io.enq.valid(lane) || io.enq.valid(lane - 1), "Issue enqueue group must be a valid prefix")
    }
    when(!io.flush) {
        assert(enqueueCount <= io.freeCount + removeCount, "Dispatch exceeded the issue queue's available space")
        for (lane <- 0 until q.enqueueWidth) {
            when(io.enq.valid(lane)) {
                assert(allowed(io.enq.entries(lane)), "Dispatch routed an operation to an incompatible queue")
            }
        }
    }
    assert(count <= q.entries.U)
    assert(!checkpointFire || replayAvailable, "Speculative issue requires a free replay slot")
    assert(!selectionLocked || PopCount(lockedMainSelect) +& PopCount(lockedReplaySelect) <= 1.U)
    for (left <- 0 until replayDepth; right <- left + 1 until replayDepth) {
        when(replayValid(left) && replayValid(right)) {
            assert(
                replayEntries(left).item.robIdx =/= replayEntries(right).item.robIdx,
                "Replay queue contains a duplicate instruction",
            )
        }
    }
}
