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
    // Stable operation-class predicate captured at enqueue. MixArith uses it for
    // system-operation age serialization.
    val isSystem = Bool()
    // Registered predecode used by replay-capacity arbitration.
    val speculative = Bool()
    // Loads use this bit in the main queue; replay slots use it for an outstanding attempt.
    val issued = Bool()
    // CSR authorization is captured locally so the global grant cannot enter
    // selection, removal and every compacted payload bit in the same cycle.
    val csrAuthorized = Bool()
}

/** Dispatch provides a packed prefix so accepted instructions retain program order. */
class IssueEnqueueGroup(p: BackendParams, width: Int) extends Bundle {
    val valid = UInt(width.W)
    val entries = Vec(width, new BackendPackage(p))
}

/** A packed enqueue group with pre-compaction candidates and one-hot sources. */
class RoutedIssueEnqueueGroup(p: BackendParams, width: Int) extends Bundle {
    val valid = UInt(width.W)
    val candidates = Vec(width, new BackendPackage(p))
    val selection = Vec(width, UInt(width.W))
}

/** Authorize one CSR instruction after older instructions have become harmless. */
class CsrIssueGrant(p: BackendParams) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
}

/** Authorize one ordered uncached Load after it reaches the ROB head. */
class LoadIssueGrant(p: BackendParams) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
}

class IssueQueueFeedback(p: BackendParams) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
    val uncache = Bool()
}

/** One possible same-cycle arithmetic wakeup before its selected tag is muxed. */
class BackendWakeupCandidate(p: BackendParams) extends Bundle {
    val valid = Bool()
    val prd = UInt(p.tagWidth.W)
    val specMask = UInt(p.specWidth.W)
}

class IssueQueueIO(p: BackendParams, q: IssueQueueParams, directWakeupCandidates: Int) extends Bundle {
    val enq = Input(new RoutedIssueEnqueueGroup(p, q.enqueueWidth))
    val issue = Decoupled(new BackendPackage(p))
    val wakeup = Input(Vec(q.wakeupPorts, new BackendWakeup(p)))
    val directWakeup = if (directWakeupCandidates > 0) {
        Some(Input(Vec(directWakeupCandidates, new BackendWakeupCandidate(p))))
    } else None
    val issueWakeup = if (q.profile == IssueQueueProfile.ArithBranch) {
        Some(Output(new BackendWakeupCandidate(p)))
    } else None
    val speculation = Input(new SpeculationResolution(p))
    val flush = Input(Bool())
    val freeCount = Output(UInt(log2Ceil(q.entries + 1).W))
    // Bit n means that at least n + 1 entries can be accepted. Dispatch uses
    // this registered threshold view instead of decoding the binary count.
    val freePrefix = Output(UInt(q.enqueueWidth.W))
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
    val loadGrant = if (q.profile == IssueQueueProfile.Load || q.profile == IssueQueueProfile.LoadStoreAddress) {
        Some(Flipped(Valid(new LoadIssueGrant(p))))
    } else None
}

/**
  * Fixed-slot, oldest-ready issue queue.
  *
  * An age relation records dispatch order independently of physical placement. Issue
  * and Load completion clear only the selected slots, while dispatch allocates already
  * free slots. Arithmetic profiles use a small replay array for speculative recovery
  * copies, keeping confirmation off the main queue update.
  */
class IssueQueue(
    val p: BackendParams = BackendParams(),
    val q: IssueQueueParams = IssueParams().arith1,
    val directWakeupCandidates: Int = 0,
) extends Module {
    override def desiredName: String = s"${q.profile.label}IssueQueue"

    val io = IO(new IssueQueueIO(p, q, directWakeupCandidates))

    // Payload lives in fixed physical slots. A separate age matrix preserves
    // dispatch order without feeding issue selection back through a compacting
    // mux on every entry field.
    val entries = Reg(Vec(q.entries, new IQEntry(p)))
    val count = RegInit(0.U(log2Ceil(q.entries + 1).W))
    // Dispatcher consumes capacity established at the previous clock edge.
    // Maintain that view directly so count does not pass through a subtractor
    // before driving every dispatch route and destination payload.
    val freeCountState = RegInit(q.entries.U(log2Ceil(q.entries + 1).W))
    // Unary free-space state keeps the fixed-width admission thresholds at
    // register Q. Bit n is set exactly when freeCountState is greater than n.
    val freeAvailability = RegInit(((BigInt(1) << q.entries) - 1).U(q.entries.W))
    val valid = RegInit(0.U(q.entries.W))
    // For each physical slot, record the live slots older than it. This carries
    // the same total order as an age-ordered slot list, but issue selection can
    // test every physical candidate in parallel instead of selecting an age rank
    // and then muxing that rank back into a physical slot.
    val olderSlots = RegInit(VecInit.fill(q.entries)(0.U(q.entries.W)))
    private val replayDepth = math.max(q.replayEntries, 1)
    val replayEntries = Reg(Vec(replayDepth, new IQEntry(p)))
    val replayValid = RegInit(VecInit.fill(replayDepth)(false.B))
    // Arithmetic issues record only their physical slot in the issue cycle.
    // The payload remains there until the following edge, when replay takes its
    // snapshot without extending issue selection into every replay D-input.
    private val delayedCheckpoint = q.profile == IssueQueueProfile.ArithBranch
    val checkpointPendingValid = if (delayedCheckpoint) Some(RegInit(false.B)) else None
    val checkpointPendingPosition = if (delayedCheckpoint) {
        Some(RegInit(0.U(q.entries.W)))
    } else {
        None
    }
    val checkpointPendingFailed = if (delayedCheckpoint) Some(RegInit(false.B)) else None
    // Reserve replay capacity at the issue edge. The one-cycle pending snapshot
    // holds this reservation until its source masks are checked for replay.
    val checkpointPendingReserved = if (delayedCheckpoint) Some(RegInit(false.B)) else None
    val checkpointPendingMask = if (delayedCheckpoint) Some(RegInit(0.U(p.specWidth.W))) else None
    // ArithBranch kills a failed token at its own issue boundary. Delay only the
    // queue-maintenance view so Load resolution cannot feed selection, compaction,
    // and replay allocation in the same cycle.
    private val maintenanceFailedMask = if (q.profile == IssueQueueProfile.ArithBranch) {
        RegNext(Mux(io.flush, 0.U, io.speculation.failedMask), 0.U)
    } else {
        io.speculation.failedMask
    }
    private val maintenanceResolvedMask = if (q.profile == IssueQueueProfile.ArithBranch) {
        // Successful tokens clear immediately. A failed token remains identifiable
        // until the delayed failure update marks its source not-ready.
        (io.speculation.resolvedMask & ~io.speculation.failedMask) | maintenanceFailedMask
    } else {
        io.speculation.resolvedMask
    }

    private def balancedOr(values: Seq[UInt]): UInt = {
        require(values.nonEmpty)
        if (values.size == 1) values.head
        else {
            val (left, right) = values.splitAt(values.size / 2)
            balancedOr(left) | balancedOr(right)
        }
    }

    private def balancedBoolOr(values: Seq[Bool]): Bool =
        balancedOr(values.map(_.asUInt)).orR

    /** Select the lowest-index asserted input without a serial priority chain. */
    private def oldestOneHot(values: Seq[Bool]): UInt = {
        require(values.nonEmpty)
        VecInit(values.indices.map { position =>
            val olderExists = if (position == 0) false.B else balancedBoolOr(values.take(position))
            values(position) && !olderExists
        }).asUInt
    }

    /** Select the oldest physical slot through the registered age order. */
    private def oldestByAge(ready: Seq[Bool]): UInt = {
        require(ready.size == q.entries)
        // olderSlots only names live slots, so valid need not enter every
        // older-candidate comparison. It qualifies only the selected slot.
        val readyMask = VecInit(ready).asUInt
        VecInit(ready.indices.map { position =>
            valid(position) && ready(position) && !(olderSlots(position) & readyMask).orR
        }).asUInt
    }

    // Decoupled requires issue.bits to remain stable while the consumer is stalled.
    val selectionLocked = RegInit(false.B)
    val lockedItem = Reg(new BackendPackage(p))
    val lockedMainPosition = RegInit(0.U(q.entries.W))
    val lockedReplayPosition = RegInit(0.U(replayDepth.W))

    io.occupancy := count
    /** Normalize unused sources and absorb same-cycle wakeups into the stored package. */
    private val waitsForSpeculationResolution = q.profile != IssueQueueProfile.ArithBranch
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
                val ordinaryHits = io.wakeup.map(wakeup =>
                    wakeup.prd =/= 0.U && wakeup.prd === item.prs(source)
                )
                // The candidate tag comparisons run beside producer selection.
                // Only the final valid bit depends on which producer slot won.
                val directHits = io.directWakeup.toSeq.flatten.map(wakeup =>
                    wakeup.valid && wakeup.prd =/= 0.U && wakeup.prd === item.prs(source)
                )
                val hits = ordinaryHits ++ directHits
                val wakeMasks = io.wakeup.toSeq.map(_.specMask) ++
                    io.directWakeup.toSeq.flatten.map(_.specMask)
                val wake = balancedOr(hits.map(_.asUInt)).orR
                val selectedMask = balancedOr(wakeMasks.zip(hits).map { case (mask, hit) =>
                    Mux(hit, mask, 0.U)
                })
                val wakeRemainingMask = selectedMask & ~maintenanceResolvedMask
                val wakeFailed = (selectedMask &
                    (maintenanceFailedMask | io.speculation.failedMask)).orR
                val failed = (item.sourceSpecMask(source) & maintenanceFailedMask).orR
                val remainingMask = item.sourceSpecMask(source) & ~maintenanceResolvedMask
                updated.sourceSpecMask(source) := remainingMask
                when(!item.sourceValid(source)) {
                    updated.sourceReady(source) := true.B
                    updated.sourceSpecMask(source) := 0.U
                }.elsewhen(wake) {
                    updated.sourceReady(source) := !wakeFailed &&
                        (if (waitsForSpeculationResolution) !wakeRemainingMask.orR else true.B)
                    updated.sourceSpecMask(source) := wakeRemainingMask
                }.elsewhen(failed) {
                    updated.sourceReady(source) := false.B
                    updated.sourceSpecMask(source) := 0.U
                }.elsewhen(if (waitsForSpeculationResolution) remainingMask.orR else false.B) {
                    updated.sourceReady(source) := false.B
                }.elsewhen(if (waitsForSpeculationResolution) item.sourceSpecMask(source).orR else false.B) {
                    // Every token carried by this source resolved successfully.
                    updated.sourceReady(source) := true.B
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

    private def resourceReady(entry: IQEntry): Bool = q.profile match {
        case IssueQueueProfile.MixArith =>
            !entry.isSystem || entry.csrAuthorized
        case IssueQueueProfile.Load | IssueQueueProfile.LoadStoreAddress =>
            !entry.item.uncache || entry.item.ioAuthorized
        case IssueQueueProfile.StoreData => true.B
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
        updatedReplay(position).isSystem := replayEntries(position).isSystem
        updatedReplay(position).speculative := speculationMask(updatedReplay(position).item).orR
        updatedReplay(position).issued := replayEntries(position).issued
        updatedReplay(position).csrAuthorized := replayEntries(position).csrAuthorized
        val activePosition = (position < q.replayEntries).B
        replayFree(position) := activePosition && !replayValid(position)
        replayCandidates(position) := activePosition && replayValid(position) && !replayEntries(position).issued &&
            operandsReady(replayEntries(position).item) && resourceReady(replayEntries(position))
    }
    val replayAvailable = replayFree.asUInt.orR
    val replaySelect = oldestOneHot(replayCandidates)
    val pendingCheckpoint = if (delayedCheckpoint) {
        val rawItem = Mux1H(checkpointPendingPosition.get.asBools, entries.map(_.item))
        val updatedItem = updateReady(rawItem)
        // updateReady clears a failed token from the retained slot at the issue
        // edge. Preserve that edge's failure separately so the delayed snapshot
        // cannot mistake a killed issue for a confirmed one.
        val pendingMask = checkpointPendingMask.get
        val failed = checkpointPendingFailed.get || (pendingMask & maintenanceFailedMask).orR
        val remainingMask = pendingMask & ~maintenanceResolvedMask
        Some((updatedItem, failed, remainingMask.orR))
    } else {
        None
    }
    val pendingNeedsReplay = checkpointPendingValid.getOrElse(false.B) &&
        checkpointPendingReserved.getOrElse(false.B)
    io.replayOccupancy := (if (q.replayEntries > 0) {
        PopCount(replayValid.take(q.replayEntries)) + pendingNeedsReplay
    } else {
        0.U
    })
    val replayCapacityAvailable = if (delayedCheckpoint) {
        PopCount(replayFree.take(q.replayEntries)) > pendingNeedsReplay.asUInt
    } else {
        replayAvailable
    }

    // The replay array has priority. Main IQ entries use the registered age order.
    val candidates = Wire(Vec(q.entries, Bool()))
    val nonSpeculativeCandidates = Wire(Vec(q.entries, Bool()))
    val replayBlocked = Wire(Vec(q.entries, Bool()))
    val operandBlocked = Wire(Vec(q.entries, Bool()))
    val resourceBlocked = Wire(Vec(q.entries, Bool()))
    val csrAgeAllowed = if (q.profile == IssueQueueProfile.MixArith) {
        val systemMask = VecInit(entries.map(_.isSystem)).asUInt
        VecInit((0 until q.entries).map(position =>
            !(olderSlots(position) & systemMask).orR
        )).asUInt
    } else {
        Fill(q.entries, 1.U(1.W))
    }
    for (position <- 0 until q.entries) {
        val item = entries(position).item
        val live = valid(position)
        val ageAllowed = csrAgeAllowed(position)
        val notIssued = q.profile match {
            case IssueQueueProfile.Load | IssueQueueProfile.LoadStoreAddress => !entries(position).issued
            case _ => true.B
        }
        val issueReady = ageAllowed && notIssued && operandsReady(item) &&
            resourceReady(entries(position))
        val issueEligible = live && issueReady
        candidates(position) := issueReady
        nonSpeculativeCandidates(position) := issueReady && !entries(position).speculative
        replayBlocked(position) := issueEligible && entries(position).speculative && !replayCapacityAvailable
        operandBlocked(position) := live && ageAllowed && notIssued && !operandsReady(item)
        resourceBlocked(position) := live && ageAllowed && notIssued && operandsReady(item) &&
            !resourceReady(entries(position))
    }
    io.replayBlocked := replayBlocked.asUInt.orR
    io.operandBlocked := operandBlocked.asUInt.orR || VecInit((0 until replayDepth).map { position =>
        (position < q.replayEntries).B && replayValid(position) && !replayEntries(position).issued &&
            !operandsReady(replayEntries(position).item)
    }).asUInt.orR
    io.resourceBlocked := resourceBlocked.asUInt.orR || VecInit((0 until replayDepth).map { position =>
        (position < q.replayEntries).B && replayValid(position) && !replayEntries(position).issued &&
            operandsReady(replayEntries(position).item) && !resourceReady(replayEntries(position))
    }).asUInt.orR

    // Replay capacity is common to every speculative candidate. Build both age
    // selections in parallel, then select between their narrow one-hot results so
    // replayValid does not pass through every candidate and the priority tree.
    val mainSelect = if (q.profile == IssueQueueProfile.ArithBranch) {
        Mux(
            replayCapacityAvailable,
            oldestByAge(candidates),
            oldestByAge(nonSpeculativeCandidates),
        )
    } else {
        oldestByAge(candidates)
    }
    val selectedReplay = Mux(selectionLocked, lockedReplayPosition, replaySelect)
    val selectedMainRaw = Mux(selectionLocked, lockedMainPosition, mainSelect)
    // Replay wins architecturally, but its priority need not clear the main
    // selector before the payload mux. Keep the mutually-exclusive view only
    // for queue state updates so replay selection does not drive both one-hot
    // trees and every issued payload bit.
    val selectedMain = Mux(selectedReplay.orR, 0.U, selectedMainRaw)
    val selectedExists = selectedReplay.orR || selectedMain.orR
    val selectedItem = Mux(
        selectionLocked,
        lockedItem,
        Mux(
            selectedReplay.orR,
            Mux1H(selectedReplay.asBools, replayEntries.map(_.item)),
            Mux1H(selectedMainRaw.asBools, entries.map(_.item)),
        ),
    )
    val selectedFailed = selectedExists && (speculationMask(selectedItem) & maintenanceFailedMask).orR
    val lockedFailed = selectionLocked && selectedFailed
    // Unlocked candidates already exclude failed speculation. A locked item can fail while stalled.
    io.issue.valid := !io.flush && !lockedFailed && selectedExists
    io.issue.bits := selectedItem
    val issueFire = io.issue.fire
    // The execution inputs expose their ordinary capacity independent of flush.
    // On a flush this payload update is unobservable, while outside flush it is
    // exactly the real issue handshake. Keeping this view local prevents queue
    // clear from entering every resident entry D-input.
    val payloadIssueFire = io.issue.ready && !lockedFailed && selectedExists

    // Select only the wakeup fields, not the full issued payload, before
    // broadcasting one candidate per arithmetic producer queue.
    io.issueWakeup.foreach { output =>
        def candidate(item: BackendPackage): BackendWakeupCandidate = {
            val wakeup = Wire(new BackendWakeupCandidate(p))
            val mask = speculationMask(item)
            // Consumers test the mask against the current failure locally.
            // Keeping this candidate independent of failure cuts the cross-IQ
            // selection and wakeup feedback path.
            wakeup.valid := item.rdValid && !item.exception.valid
            wakeup.prd := item.prd
            wakeup.specMask := mask
            wakeup
        }
        val selections = (0 until q.entries).map(position =>
            !selectionLocked && selectedMain(position)) ++
            (0 until replayDepth).map(position =>
                !selectionLocked && selectedReplay(position)) :+
            (selectionLocked && selectedExists)
        val wakeups = entries.map(entry => candidate(entry.item)).toSeq ++
            replayEntries.map(entry => candidate(entry.item)).toSeq :+ candidate(lockedItem)
        assert(PopCount(VecInit(selections)) <= 1.U)
        output.valid := Mux1H(selections, wakeups.map(_.valid))
        output.prd := Mux1H(selections, wakeups.map(_.prd))
        output.specMask := Mux1H(selections, wakeups.map(_.specMask))
    }

    // Update each possible source before payload movement. Dispatch and removal
    // controls then select among completed results instead of feeding the PRS
    // compare and wakeup network after a wide entry mux.
    val awakened = Wire(Vec(q.entries, new IQEntry(p)))
    for (position <- 0 until q.entries) {
        awakened(position).item := updateReady(entries(position).item)
        awakened(position).isSystem := entries(position).isSystem
        awakened(position).speculative := speculationMask(awakened(position).item).orR
        awakened(position).issued := entries(position).issued
        awakened(position).csrAuthorized := entries(position).csrAuthorized
    }
    // Wakeup work is distributed over the original dispatch candidates. The
    // late capacity/route decision then selects an already-updated payload.
    val incomingCandidates = Wire(Vec(q.enqueueWidth, new IQEntry(p)))
    for (lane <- 0 until q.enqueueWidth) {
        incomingCandidates(lane).item := updateReady(io.enq.candidates(lane))
        // Decode exceptions complete in ROB and never enter an issue queue.
        // Execution exceptions are generated after issue, so storing the
        // dispatch exception payload in every queue entry is unnecessary.
        incomingCandidates(lane).item.exception := 0.U.asTypeOf(new BackendException)
        incomingCandidates(lane).isSystem := (if (q.profile == IssueQueueProfile.MixArith) {
            io.enq.candidates(lane).fu === DecodeUnit.System.U
        } else false.B)
        incomingCandidates(lane).speculative := speculationMask(incomingCandidates(lane).item).orR
        incomingCandidates(lane).issued := false.B
        incomingCandidates(lane).csrAuthorized := false.B
    }
    // Loads keep their physical position until the corresponding pipeline reports
    // completion. Retry only re-arms the retained entry, preserving its age.
    val retained = WireDefault(awakened)
    val issueRemoval = Wire(Vec(q.entries, Bool()))
    val completionRemoval = Wire(Vec(q.entries, Bool()))
    for (position <- 0 until q.entries) {
        val live = valid(position)
        val selectedHere = payloadIssueFire && selectedMain(position)
        val issuedLoad = q.profile match {
            case IssueQueueProfile.Load => selectedHere
            case IssueQueueProfile.LoadStoreAddress =>
                selectedHere && entries(position).item.fu === DecodeUnit.Load.U
            case _ => false.B
        }
        val completionHit = io.completed.map(feedback =>
            feedback.valid && feedback.bits.robIdx === entries(position).item.robIdx
        ).getOrElse(false.B)
        val retryHit = io.retry.map(feedback =>
            feedback.valid && feedback.bits.robIdx === entries(position).item.robIdx
        ).getOrElse(false.B)

        when(issuedLoad) {
            retained(position).issued := true.B
        }
        when(retryHit) {
            retained(position).issued := false.B
        }
        io.retry.foreach { feedback =>
            when(retryHit) {
                retained(position).item.uncache := feedback.bits.uncache
                retained(position).item.ioAuthorized := false.B
            }
        }
        // selectedMain is one-hot only among live candidates; a locked selection
        // is also asserted to remain within valid below.
        issueRemoval(position) := selectedHere && !issuedLoad
        completionRemoval(position) := live && completionHit
        when(live && (completionHit || retryHit)) {
            assert(entries(position).issued, "Load feedback must name an issued IQ entry")
        }
    }

    // Speculative arithmetic leaves the fixed-slot IQ immediately. A non-compacting
    // replay slot keeps its recovery copy until all tokens succeed or it reissues.
    val nextReplay = WireDefault(updatedReplay)
    val nextReplayValid = WireDefault(replayValid)
    for (position <- 0 until replayDepth) {
        val failed = (speculationMask(replayEntries(position).item) & maintenanceFailedMask).orR
        val confirmed = replayValid(position) && replayEntries(position).issued &&
            !speculationMask(updatedReplay(position).item).orR && !failed
        when(failed) {
            nextReplay(position).issued := false.B
        }.elsewhen(issueFire && selectedReplay(position)) {
            when(speculationMask(updatedReplay(position).item).orR) {
                nextReplay(position).issued := true.B
            }.otherwise {
                nextReplayValid(position) := false.B
            }
        }.elsewhen(confirmed) {
            nextReplayValid(position) := false.B
        }
    }
    val checkpointCapture = issueFire && selectedMain.orR
    // Pending checkpoint state is only consumed after a main-queue issue. Read
    // the raw main selection directly so replay priority and the replay payload
    // mux do not sit between source readiness and these narrow registers.
    val checkpointMainItem = Mux1H(selectedMainRaw.asBools, entries.map(_.item))
    val checkpointMainFailed =
        (speculationMask(checkpointMainItem) & maintenanceFailedMask).orR
    val checkpointMainSpeculative = Mux1H(selectedMainRaw.asBools, entries.map(_.speculative))
    val checkpointSelect = oldestOneHot(replayFree)
    pendingCheckpoint.foreach { case (checkpointItem, checkpointFailed, checkpointSpeculative) =>
        for (position <- 0 until replayDepth) {
            when(checkpointPendingValid.get && checkpointPendingReserved.get && checkpointSelect(position)) {
                nextReplay(position).item := checkpointItem
                nextReplay(position).isSystem := false.B
                nextReplay(position).speculative := checkpointSpeculative
                nextReplay(position).issued := !checkpointFailed
                nextReplay(position).csrAuthorized := true.B
                nextReplayValid(position) := checkpointSpeculative || checkpointFailed
            }
        }
    }

    // A normal issue and an independent Load completion may release two slots in
    // the same cycle. Dispatch allocates only slots that were already free at the
    // preceding edge, matching the registered freeCount contract.
    val enqueueCountOH = VecInit((0 to q.enqueueWidth).map { amount =>
        if (amount == 0) !io.enq.valid(0)
        else if (amount == q.enqueueWidth) io.enq.valid(q.enqueueWidth - 1)
        else io.enq.valid(amount - 1) && !io.enq.valid(amount)
    })
    val enqueueCount = Mux1H(enqueueCountOH, (0 to q.enqueueWidth).map(_.U))
    val issueRemoveAny = issueRemoval.asUInt.orR
    val completionRemoveAny = completionRemoval.asUInt.orR
    // Both removal classes are one-hot and never overlap. Their two-bit sum is
    // the exact removal count without rebuilding an adder tree from every slot.
    val removeCount = issueRemoveAny +& completionRemoveAny
    // Dispatch sees only capacity established at the previous clock edge. Do not
    // feed same-cycle issue/completion decisions back into IQ admission.
    io.freeCount := freeCountState
    io.freePrefix := freeAvailability(q.enqueueWidth - 1, 0)
    val countAfterRemove = count - removeCount
    val nextCount = countAfterRemove + enqueueCount
    val nextFreeCount = freeCountState + removeCount - enqueueCount
    private val maxRemoveCount = q.profile match {
        case IssueQueueProfile.LoadStoreAddress => 2
        case _ => 1
    }

    assert(PopCount(issueRemoval) <= 1.U)
    assert(PopCount(completionRemoval) <= 1.U)
    assert(!(issueRemoval.asUInt & completionRemoval.asUInt).orR)
    assert(removeCount <= maxRemoveCount.U)

    val allocation = Wire(Vec(q.enqueueWidth, UInt(q.entries.W)))
    val freeSlots = Wire(Vec(q.enqueueWidth, UInt(q.entries.W)))
    var remainingFree = ~valid
    for (lane <- 0 until q.enqueueWidth) {
        freeSlots(lane) := PriorityEncoderOH(remainingFree)
        remainingFree = remainingFree & ~freeSlots(lane)
        allocation(lane) := Mux(io.enq.valid(lane), freeSlots(lane), 0.U)
    }
    val allocationMask = allocation.reduce(_ | _)
    val removed = issueRemoval.asUInt | completionRemoval.asUInt
    val nextValid = (valid & ~removed) | allocationMask
    val freeAfterEnqueue = Mux1H(enqueueCountOH, (0 to q.enqueueWidth).map { amount =>
        if (amount == 0) freeAvailability else freeAvailability >> amount
    })
    val nextFreeAvailability = MuxLookup(removeCount, freeAfterEnqueue)(Seq(
        1.U -> Cat(freeAfterEnqueue(q.entries - 2, 0), 1.U(1.W)),
        2.U -> Cat(freeAfterEnqueue(q.entries - 3, 0), 3.U(2.W)),
    ))
    // Removal only deletes relations; it does not change survivor order. Every
    // allocation is younger than all survivors and younger than earlier lanes.
    // Updating the relation matrix this way avoids a rank-compaction network.
    val survivors = valid & ~removed
    val nextOlderSlots = Wire(Vec(q.entries, UInt(q.entries.W)))
    for (position <- 0 until q.entries) {
        nextOlderSlots(position) := Mux(survivors(position), olderSlots(position) & survivors, 0.U)
        for (lane <- 0 until q.enqueueWidth) {
            val earlierAllocations = if (lane == 0) {
                0.U(q.entries.W)
            } else {
                allocation.take(lane).reduce(_ | _)
            }
            when(allocation(lane)(position)) {
                nextOlderSlots(position) := survivors | earlierAllocations
            }
        }
    }

    val installedRaw = WireDefault(retained)
    for (position <- 0 until q.entries) {
        // Compose dispatch compaction and physical-slot allocation into one
        // selector. This keeps the late route decision out of a second full-entry
        // mux while preserving the same rank-to-free-slot mapping.
        val sourceSelect = VecInit((0 until q.enqueueWidth).map { source =>
            balancedBoolOr((0 until q.enqueueWidth).map { rank =>
                freeSlots(rank)(position) && io.enq.selection(rank)(source)
            })
        })
        assert(PopCount(sourceSelect) <= 1.U)
        // Only previously free slots can be allocated. Their payload may be
        // prewritten even when no source is selected; valid alone controls
        // whether a queue entry is observable.
        when(!valid(position)) {
            installedRaw(position) := Mux1H(sourceSelect, incomingCandidates)
        }
    }

    // CSR authorization remains a narrow per-entry update after allocation. It
    // compares only the registered identity and does not touch the payload mux.
    val installed = Wire(Vec(q.entries, new IQEntry(p)))
    for (position <- 0 until q.entries) {
        installed(position).item := installedRaw(position).item
        installed(position).isSystem := installedRaw(position).isSystem
        installed(position).speculative := installedRaw(position).speculative
        installed(position).issued := installedRaw(position).issued
        installed(position).csrAuthorized := installedRaw(position).csrAuthorized
        if (q.profile == IssueQueueProfile.MixArith) {
            val grant = io.csrGrant.get
            // A grant can only name a resident instruction. Compare the
            // registered identity so speculative prewrites into a free slot do
            // not put late dispatch selection on this state bit.
            val grantMatch = valid(position) && grant.valid &&
                entries(position).item.fu === DecodeUnit.System.U &&
                grant.bits.robIdx === entries(position).item.robIdx
            installed(position).csrAuthorized := installedRaw(position).csrAuthorized || grantMatch
        }
        if (q.profile == IssueQueueProfile.Load || q.profile == IssueQueueProfile.LoadStoreAddress) {
            val grant = io.loadGrant.get
            val grantMatch = valid(position) && grant.valid && entries(position).item.uncache &&
                grant.bits.robIdx === entries(position).item.robIdx
            installed(position).item.ioAuthorized := installedRaw(position).item.ioAuthorized || grantMatch
        }
    }

    // Payload contents are unobservable once their valid state is cleared. Keep
    // flush out of every wide entry D-input by updating payloads unconditionally.
    entries := installed
    olderSlots := nextOlderSlots
    replayEntries := nextReplay
    // Checkpoint payload is ignored whenever its valid bit is clear, including
    // the cycle after flush. Sampling it avoids a flush mux on these fields.
    checkpointPendingPosition.foreach(_ := selectedMainRaw)
    checkpointPendingFailed.foreach(_ := checkpointMainFailed)
    checkpointPendingReserved.foreach(_ := checkpointMainSpeculative || checkpointMainFailed)
    checkpointPendingMask.foreach(_ := speculationMask(checkpointMainItem) & ~maintenanceResolvedMask)
    when(io.flush) {
        count := 0.U
        freeCountState := q.entries.U
        freeAvailability := ((BigInt(1) << q.entries) - 1).U
        valid := 0.U
        replayValid := VecInit.fill(replayDepth)(false.B)
        checkpointPendingValid.foreach(_ := false.B)
        selectionLocked := false.B
    }.otherwise {
        count := nextCount
        freeCountState := nextFreeCount
        freeAvailability := nextFreeAvailability
        valid := nextValid
        replayValid := nextReplayValid
        checkpointPendingValid.foreach(_ := checkpointCapture)
        when(lockedFailed) {
            selectionLocked := false.B
            lockedMainPosition := 0.U
            lockedReplayPosition := 0.U
        }.elsewhen(issueFire) {
            selectionLocked := false.B
            lockedMainPosition := 0.U
            lockedReplayPosition := 0.U
        }.elsewhen(io.issue.valid && !io.issue.ready && !selectionLocked) {
            selectionLocked := true.B
            lockedItem := io.issue.bits
            lockedMainPosition := selectedMain
            lockedReplayPosition := selectedReplay
        }
    }

    assert(PopCount(valid) === count)
    assert(freeCountState +& count === q.entries.U)
    assert(PopCount(freeAvailability) === freeCountState)
    for (position <- 1 until q.entries) {
        assert(!freeAvailability(position) || freeAvailability(position - 1))
    }
    for (left <- 0 until q.enqueueWidth) {
        assert(PopCount(freeSlots(left)) === freeAvailability(left))
        assert((freeSlots(left) & valid) === 0.U)
        for (right <- left + 1 until q.enqueueWidth) {
            assert((freeSlots(left) & freeSlots(right)) === 0.U)
        }
    }

    // Interface assertions document the fixed-slot allocation contract.
    for (lane <- 1 until q.enqueueWidth) {
        assert(!io.enq.valid(lane) || io.enq.valid(lane - 1), "Issue enqueue group must be a valid prefix")
    }
    when(!io.flush) {
        assert(enqueueCount <= io.freeCount, "Dispatch exceeded the issue queue's registered free space")
        for (lane <- 0 until q.enqueueWidth) {
            assert(PopCount(io.enq.selection(lane)) <= 1.U, "Issue enqueue source must be one-hot or zero-hot")
            when(io.enq.valid(lane)) {
                assert(allocation(lane).orR, "Issue enqueue must allocate one free physical slot")
                assert(PopCount(io.enq.selection(lane)) === 1.U, "Issue enqueue source must be one-hot")
                for (source <- 0 until q.enqueueWidth) {
                    when(io.enq.selection(lane)(source)) {
                        assert(
                            allowed(incomingCandidates(source).item),
                            "Dispatch routed an operation to an incompatible queue",
                        )
                        assert(
                            !io.enq.candidates(source).exception.valid,
                            "Decode exceptions must complete in ROB without entering an issue queue",
                        )
                    }
                }
            }
        }
    }
    for (position <- 0 until q.entries) {
        assert(!olderSlots(position)(position), "An IQ slot cannot be older than itself")
        // Flush may leave stale age bits in invalid slots; their next update clears them.
        when(valid(position)) {
            assert((olderSlots(position) & ~valid) === 0.U, "IQ age relations must name live slots")
        }
        for (other <- position + 1 until q.entries) {
            when(valid(position) && valid(other)) {
                assert(
                    olderSlots(position)(other) ^ olderSlots(other)(position),
                    "Every pair of live IQ slots must have exactly one age relation",
                )
            }
        }
    }
    assert(count <= q.entries.U)
    assert(!pendingNeedsReplay || replayAvailable, "A pending speculative issue requires a free replay slot")
    assert(!selectionLocked || PopCount(lockedMainPosition) +& PopCount(lockedReplayPosition) === 1.U)
    when(selectionLocked) {
        assert((lockedMainPosition & ~valid) === 0.U)
        assert((lockedReplayPosition & ~replayValid.asUInt) === 0.U)
    }
    for (left <- 0 until replayDepth; right <- left + 1 until replayDepth) {
        when(replayValid(left) && replayValid(right)) {
            assert(
                replayEntries(left).item.robIdx =/= replayEntries(right).item.robIdx,
                "Replay queue contains a duplicate instruction",
            )
        }
    }
}
