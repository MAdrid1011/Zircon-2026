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
    // Stable operation-class predicate captured at enqueue and moved with the
    // compacted entry. MixArith uses it for system-operation age serialization.
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
    val loadGrant = if (q.profile == IssueQueueProfile.Load || q.profile == IssueQueueProfile.LoadStoreAddress) {
        Some(Flipped(Valid(new LoadIssueGrant(p))))
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

    // Payload validity is a registered prefix. Keep count only for occupancy reporting.
    val entries = Reg(Vec(q.entries, new IQEntry(p)))
    val count = RegInit(0.U(log2Ceil(q.entries + 1).W))
    // Dispatcher consumes capacity established at the previous clock edge.
    // Maintain that view directly so count does not pass through a subtractor
    // before driving every dispatch route and destination payload.
    val freeCountState = RegInit(q.entries.U(log2Ceil(q.entries + 1).W))
    val validPrefix = RegInit(0.U(q.entries.W))
    // One-hot next-free position avoids a binary subtract/add/decode chain on
    // the compact queue's enqueue write enables.
    private val tailWidth = q.entries + 1
    val tailOH = RegInit(1.U(tailWidth.W))
    private val replayDepth = math.max(q.replayEntries, 1)
    val replayEntries = Reg(Vec(replayDepth, new IQEntry(p)))
    val replayValid = RegInit(VecInit.fill(replayDepth)(false.B))
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

    // Decoupled requires issue.bits to remain stable while the consumer is stalled.
    val selectionLocked = RegInit(false.B)
    val lockedItem = Reg(new BackendPackage(p))
    val lockedMainPosition = RegInit(0.U(q.entries.W))
    val lockedReplayPosition = RegInit(0.U(replayDepth.W))

    io.occupancy := count
    io.replayOccupancy := (if (q.replayEntries > 0) PopCount(replayValid.take(q.replayEntries)) else 0.U)

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
                val hits = io.wakeup.map(wakeup => wakeup.prd =/= 0.U && wakeup.prd === item.prs(source))
                val wake = balancedOr(hits.map(_.asUInt)).orR
                val selectedWakeMasks = io.wakeup.zip(hits).map { case (event, hit) =>
                    Mux(hit, event.specMask, 0.U)
                }
                val wakeMask = balancedOr(selectedWakeMasks)
                val wakeFailed = balancedOr(io.wakeup.zip(hits).map { case (event, hit) =>
                    (hit && (event.specMask & maintenanceFailedMask).orR).asUInt
                }).orR
                val failed = (item.sourceSpecMask(source) & maintenanceFailedMask).orR
                val remainingMask = item.sourceSpecMask(source) & ~maintenanceResolvedMask
                val wakeRemainingMask = wakeMask & ~maintenanceResolvedMask
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

    // The replay array has priority. Main IQ entries still use physical order as program age.
    val candidates = Wire(Vec(q.entries, Bool()))
    val nonSpeculativeCandidates = Wire(Vec(q.entries, Bool()))
    val replayBlocked = Wire(Vec(q.entries, Bool()))
    val operandBlocked = Wire(Vec(q.entries, Bool()))
    val resourceBlocked = Wire(Vec(q.entries, Bool()))
    for (position <- 0 until q.entries) {
        val item = entries(position).item
        val live = validPrefix(position)
        val olderCSR = if (q.profile == IssueQueueProfile.MixArith && position > 0) {
            balancedBoolOr((0 until position).map { older =>
                validPrefix(older) && entries(older).isSystem
            })
        } else {
            false.B
        }
        val notIssued = q.profile match {
            case IssueQueueProfile.Load | IssueQueueProfile.LoadStoreAddress => !entries(position).issued
            case _ => true.B
        }
        val issueEligible = live && !olderCSR && notIssued && operandsReady(item) &&
            resourceReady(entries(position))
        candidates(position) := issueEligible
        nonSpeculativeCandidates(position) := issueEligible && !entries(position).speculative
        replayBlocked(position) := issueEligible && entries(position).speculative && !replayAvailable
        operandBlocked(position) := live && !olderCSR && notIssued && !operandsReady(item)
        resourceBlocked(position) := live && !olderCSR && notIssued && operandsReady(item) &&
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
            replayAvailable,
            oldestOneHot(candidates),
            oldestOneHot(nonSpeculativeCandidates),
        )
    } else {
        oldestOneHot(candidates)
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
    // clear from entering every compacted entry D-input.
    val payloadIssueFire = io.issue.ready && !lockedFailed && selectedExists

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
    val incoming = Wire(Vec(q.enqueueWidth, new IQEntry(p)))
    for (lane <- 0 until q.enqueueWidth) {
        incoming(lane).item := updateReady(io.enq.entries(lane))
        incoming(lane).isSystem := (if (q.profile == IssueQueueProfile.MixArith) {
            io.enq.entries(lane).fu === DecodeUnit.System.U
        } else false.B)
        incoming(lane).speculative := speculationMask(incoming(lane).item).orR
        incoming(lane).issued := false.B
        incoming(lane).csrAuthorized := false.B
    }

    // Loads keep their physical position until the corresponding pipeline reports
    // completion. Retry only re-arms the retained entry, preserving its age.
    val retained = WireDefault(awakened)
    val issueRemoval = Wire(Vec(q.entries, Bool()))
    val completionRemoval = Wire(Vec(q.entries, Bool()))
    for (position <- 0 until q.entries) {
        val live = validPrefix(position)
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
        // is also asserted to remain within validPrefix below.
        issueRemoval(position) := selectedHere && !issuedLoad
        completionRemoval(position) := live && completionHit
        when(live && (completionHit || retryHit)) {
            assert(entries(position).issued, "Load feedback must name an issued IQ entry")
        }
    }

    // Speculative arithmetic leaves the compact IQ immediately. A non-compacting
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
    val selectedMainSpeculative = Mux1H(selectedMain.asBools, entries.map(_.speculative))
    val checkpointFire = issueFire && selectedMain.orR && selectedMainSpeculative
    // A certain wakeup can coincide with the failure of the speculative wake
    // that originally made this source ready. Preserve only that exact-PRS
    // event; unrelated and speculative wakeups stay out of the replay D-input.
    val checkpointItem = WireDefault(selectedItem)
    for (source <- usedSources) {
        val certainWake = balancedBoolOr(io.wakeup.map { event =>
            selectedItem.sourceValid(source) && event.prd =/= 0.U &&
                event.prd === selectedItem.prs(source) && !event.specMask.orR
        })
        val failed = (selectedItem.sourceSpecMask(source) & maintenanceFailedMask).orR
        checkpointItem.sourceSpecMask(source) :=
            selectedItem.sourceSpecMask(source) & ~maintenanceResolvedMask
        when(certainWake) {
            checkpointItem.sourceReady(source) := true.B
            checkpointItem.sourceSpecMask(source) := 0.U
        }.elsewhen(failed) {
            checkpointItem.sourceReady(source) := false.B
            checkpointItem.sourceSpecMask(source) := 0.U
        }
    }
    val checkpointFailed = (speculationMask(selectedItem) & maintenanceFailedMask).orR
    val checkpointSpeculative = speculationMask(checkpointItem).orR
    val checkpointSelect = oldestOneHot(replayFree)
    for (position <- 0 until replayDepth) {
        when(checkpointFire && checkpointSelect(position) && (checkpointSpeculative || checkpointFailed)) {
            nextReplay(position).item := checkpointItem
            nextReplay(position).isSystem := false.B
            nextReplay(position).speculative := checkpointSpeculative
            nextReplay(position).issued := !checkpointFailed
            nextReplay(position).csrAuthorized := true.B
            nextReplayValid(position) := true.B
        }
    }

    // Stable packing handles a normal issue and an independent Load completion in
    // the same cycle without adding another selector to the issue path.
    val enqueueCount = PopCount(io.enq.valid)
    val issueRemoveAny = issueRemoval.asUInt.orR
    val completionRemoveAny = completionRemoval.asUInt.orR
    // Both removal classes are one-hot and never overlap. Their two-bit sum is
    // the exact removal count without rebuilding an adder tree from every slot.
    val removeCount = issueRemoveAny +& completionRemoveAny
    // Dispatch sees only capacity established at the previous clock edge. Do not
    // feed same-cycle issue/completion decisions back into IQ admission.
    io.freeCount := freeCountState
    val countAfterRemove = count - removeCount
    val nextCount = countAfterRemove + enqueueCount
    val nextFreeCount = freeCountState + removeCount - enqueueCount
    // One issue removes at most one entry. Only LS1 can also remove an older
    // completed Load in the same cycle. Specialize those fixed cases instead
    // of rebuilding a survivor rank and comparison for every candidate.
    private val maxRemoveCount = q.profile match {
        case IssueQueueProfile.LoadStoreAddress => 2
        case _ => 1
    }
    val shifted = Wire(Vec(q.entries, new IQEntry(p)))
    val shiftedLockedPosition = Wire(Vec(q.entries, Bool()))
    val selectedMainAfterCompletion = Wire(Vec(q.entries, Bool()))
    for (destination <- 0 until q.entries) {
        val completionInPrefix = balancedBoolOr(completionRemoval.take(destination + 1))
        // Reuse the final one-hot removal decision. Reconstructing priority from
        // candidates here duplicated the full readiness/speculation cone on every
        // compacted entry D input.
        val issueInPrefix = balancedBoolOr(issueRemoval.take(destination + 1))
        val shiftOne = issueInPrefix || completionInPrefix
        val sourceOne = if (destination + 1 < q.entries) retained(destination + 1) else retained(destination)
        val lockedSourceOne = if (destination + 1 < q.entries) lockedMainPosition(destination + 1) else false.B
        val selectedSourceOne = if (destination + 1 < q.entries) selectedMain(destination + 1) else false.B
        selectedMainAfterCompletion(destination) :=
            Mux(completionInPrefix, selectedSourceOne, selectedMain(destination))
        if (q.profile == IssueQueueProfile.LoadStoreAddress) {
            val prefixThroughNext = math.min(destination + 2, q.entries)
            val issueThroughNext = balancedBoolOr(issueRemoval.take(prefixThroughNext))
            val completionThroughNext = balancedBoolOr(completionRemoval.take(prefixThroughNext))
            val shiftTwo = issueThroughNext && completionThroughNext
            val sourceTwo = if (destination + 2 < q.entries) retained(destination + 2) else sourceOne
            val lockedSourceTwo = if (destination + 2 < q.entries) lockedMainPosition(destination + 2) else false.B
            shifted(destination) := Mux(shiftTwo, sourceTwo, Mux(shiftOne, sourceOne, retained(destination)))
            shiftedLockedPosition(destination) :=
                Mux(shiftTwo, lockedSourceTwo, Mux(shiftOne, lockedSourceOne, lockedMainPosition(destination)))
            assert(!shiftTwo || shiftOne)
        } else {
            shifted(destination) := Mux(shiftOne, sourceOne, retained(destination))
            shiftedLockedPosition(destination) := Mux(shiftOne, lockedSourceOne, lockedMainPosition(destination))
        }
    }

    assert(PopCount(issueRemoval) <= 1.U)
    assert(PopCount(completionRemoval) <= 1.U)
    assert(!(issueRemoval.asUInt & completionRemoval.asUInt).orR)
    assert(removeCount <= maxRemoveCount.U)
    val tailAfterRemove = MuxLookup(removeCount, tailOH)(Seq(
        1.U -> (tailOH >> 1),
        2.U -> (tailOH >> 2),
    ))
    val tailAfterEnqueue = MuxLookup(enqueueCount, tailAfterRemove)(
        (1 to q.enqueueWidth).map(width =>
            width.U -> (tailAfterRemove << width)(tailWidth - 1, 0)
        )
    )
    val validAfterRemove = MuxLookup(removeCount, validPrefix)(Seq(
        1.U -> (validPrefix >> 1),
        2.U -> (validPrefix >> 2),
    ))
    val enqueuePositions = (0 until q.enqueueWidth).map { lane =>
        Mux(io.enq.valid(lane), (tailAfterRemove << lane)(q.entries - 1, 0), 0.U(q.entries.W))
    }
    val nextValidPrefix = validAfterRemove | enqueuePositions.reduce(_ | _)

    // The valid-prefix contract makes each enqueue lane's tail position
    // deterministic. Fixed one-hot shifts replace countAfterRemove + laneRank.
    val installedRaw = WireDefault(shifted)
    for (lane <- 0 until q.enqueueWidth; position <- 0 until q.entries) {
        val lanePosition = (tailAfterRemove << lane)(q.entries, 0)
        when(io.enq.valid(lane) && lanePosition(position)) {
            installedRaw(position) := incoming(lane)
        }
    }

    // CSR authorization remains a narrow per-entry update after movement. It
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
            val grantMatch = grant.valid &&
                installedRaw(position).item.fu === DecodeUnit.System.U &&
                grant.bits.robIdx === installedRaw(position).item.robIdx
            installed(position).csrAuthorized := installedRaw(position).csrAuthorized || grantMatch
        }
        if (q.profile == IssueQueueProfile.Load || q.profile == IssueQueueProfile.LoadStoreAddress) {
            val grant = io.loadGrant.get
            val grantMatch = grant.valid && installedRaw(position).item.uncache &&
                grant.bits.robIdx === installedRaw(position).item.robIdx
            installed(position).item.ioAuthorized := installedRaw(position).item.ioAuthorized || grantMatch
        }
    }

    // Payload contents are unobservable once their valid state is cleared. Keep
    // flush out of every wide entry D-input by updating payloads unconditionally.
    entries := installed
    replayEntries := nextReplay
    when(io.flush) {
        count := 0.U
        freeCountState := q.entries.U
        validPrefix := 0.U
        tailOH := 1.U
        replayValid := VecInit.fill(replayDepth)(false.B)
        selectionLocked := false.B
        lockedMainPosition := 0.U
        lockedReplayPosition := 0.U
    }.otherwise {
        count := nextCount
        freeCountState := nextFreeCount
        validPrefix := nextValidPrefix
        tailOH := tailAfterEnqueue
        replayValid := nextReplayValid
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
            lockedMainPosition := selectedMainAfterCompletion.asUInt
            lockedReplayPosition := selectedReplay
        }.elsewhen(selectionLocked) {
            lockedMainPosition := shiftedLockedPosition.asUInt
        }
    }

    assert(tailOH === UIntToOH(count, tailWidth))
    assert(PopCount(validPrefix) === count)
    assert(freeCountState +& count === q.entries.U)

    // Interface assertions document the assumptions that keep the compact update small.
    for (lane <- 1 until q.enqueueWidth) {
        assert(!io.enq.valid(lane) || io.enq.valid(lane - 1), "Issue enqueue group must be a valid prefix")
    }
    when(!io.flush) {
        assert(enqueueCount <= io.freeCount, "Dispatch exceeded the issue queue's registered free space")
        for (lane <- 0 until q.enqueueWidth) {
            when(io.enq.valid(lane)) {
                assert(allowed(io.enq.entries(lane)), "Dispatch routed an operation to an incompatible queue")
            }
        }
    }
    assert(count <= q.entries.U)
    assert(!checkpointFire || replayAvailable, "Speculative issue requires a free replay slot")
    assert(!selectionLocked || PopCount(lockedMainPosition) +& PopCount(lockedReplayPosition) === 1.U)
    assert((lockedMainPosition & ~validPrefix) === 0.U)
    assert((lockedReplayPosition & ~replayValid.asUInt) === 0.U)
    for (left <- 0 until replayDepth; right <- left + 1 until replayDepth) {
        when(replayValid(left) && replayValid(right)) {
            assert(
                replayEntries(left).item.robIdx =/= replayEntries(right).item.robIdx,
                "Replay queue contains a duplicate instruction",
            )
        }
    }
}
