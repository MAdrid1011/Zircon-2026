package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Eight-entry memory issue queue.
  *
  * M0 accepts all memory uops, while M1 preferentially accepts cacheable-load
  * candidates. PMA/admission failure and replay remain LSU responsibilities;
  * this queue only preserves compact uops, source readiness, ROB age, and
  * recovery ownership.
  */
class MemIssueQueue(
    config: ZirconCoreConfig = ZirconCoreConfig.default,
    allowIssueRecycle: Boolean = true
) extends Module {
  private val entries = config.memIssueEntries
  private val indexWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(config.decodeWidth, Decoupled(new UopRef(config))))
    val integerReady = Input(UInt(config.intPhysicalRegisters.W))
    val m0Issue = Decoupled(new UopRef(config))
    val m1Issue = Decoupled(new UopRef(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(countWidth.W))
    val enqueueCapacity = Output(UInt(2.W))
  })

  val entryValid = RegInit(VecInit.fill(entries)(false.B))
  val entryUop = Reg(Vec(entries, new UopRef(config)))
  // These eligibility bits are immutable while a uop is resident.  Local
  // copies keep the endpoint candidate trees off the wide UopRef registers.
  val entryAllowM0 = Reg(Vec(entries, Bool()))
  val entryAllowM1 = Reg(Vec(entries, Bool()))
  val count = RegInit(0.U(countWidth.W))

  private def sourceReady(uop: UopRef, source: Int): Bool = {
    if (source >= 2) uop.sourceReady(source)
    else {
      val inRange = uop.sourcePhysical(source) < config.intPhysicalRegisters.U
      val safePhysical = Mux(inRange, uop.sourcePhysical(source), 0.U)
      Mux(uop.sourceKind(source) === SourceKind.IntegerRegister,
        uop.sourceReady(source) || io.integerReady(safePhysical),
        uop.sourceReady(source))
    }
  }

  private def withReady(uop: UopRef): UopRef = {
    val updated = WireDefault(uop)
    for (source <- 0 until 3) {
      updated.sourceReady(source) := sourceReady(uop, source)
    }
    updated
  }

  private def allSourcesReady(uop: UopRef): Bool =
    (0 until 3).map(sourceReady(uop, _)).reduce(_ && _)

  // The same age ordering feeds atomic, M1, and M0 selection.  Materialize
  // it once per entry instead of rebuilding the ROB-distance comparator for
  // every candidate set.
  val entryAge = VecInit(entryUop.map(uop =>
    ROBTagOrder.ageFromHead(uop.robTag, io.robHeadTag, config)))

  private def selectOldest(candidates: Seq[Bool]): (Bool, UInt) = {
    // Balance the oldest-candidate reduction.  The previous serial fold put
    // every slot on one long ROB-age/mux path; this tree keeps the same
    // left-wins tie-break with logarithmic decision depth.
    var valid = candidates.toVector
    var indices = (0 until entries).map(_.U(indexWidth.W)).toVector
    var ages = entryAge.toVector
    while (valid.length > 1) {
      val nextValid = Vector.newBuilder[Bool]
      val nextIndices = Vector.newBuilder[UInt]
      val nextAges = Vector.newBuilder[UInt]
      var pair = 0
      while (pair < valid.length) {
        if (pair + 1 == valid.length) {
          nextValid += valid(pair)
          nextIndices += indices(pair)
          nextAges += ages(pair)
        } else {
          val rightWins = valid(pair + 1) &&
            (!valid(pair) || ages(pair + 1) < ages(pair))
          nextValid += (valid(pair) || valid(pair + 1))
          nextIndices += Mux(rightWins, indices(pair + 1), indices(pair))
          nextAges += Mux(rightWins, ages(pair + 1), ages(pair))
        }
        pair += 2
      }
      valid = nextValid.result()
      indices = nextIndices.result()
      ages = nextAges.result()
    }
    (valid.head, indices.head)
  }

  // Source wakeup is shared by both issue ports and by the retained entry
  // update, avoiding duplicate integer-ready comparator banks.
  val readyEntries = Wire(Vec(entries, new UopRef(config)))
  for (index <- 0 until entries) {
    readyEntries(index) := withReady(entryUop(index))
  }

  private def readyForIssue(index: Int): Bool =
    entryValid(index) && allSourcesReady(readyEntries(index))

  private def olderStoreInQueue(index: Int): Bool =
    (0 until entries).map(storeIndex =>
      entryValid(storeIndex) &&
        entryUop(storeIndex).uopClass === UopClass.Store &&
        !ROBTagOrder.isYounger(entryUop(storeIndex).robTag,
          entryUop(index).robTag, io.robHeadTag, config)).reduce(_ || _)

  // aq/rl remains authoritative in the ROB execution context. Before an
  // atomic reaches that context, however, its compact MemIQ record must stop
  // younger M0 and M1 work from escaping in the same issue window.
  // Conservatively serialize all live atomics here; this is stronger than a
  // non-aq atomic but preserves RVWMO without duplicating mutable ordering
  // metadata in UopRef.
  val atomicCandidates = (0 until entries).map(index =>
    entryValid(index) && entryUop(index).uopClass === UopClass.Atomic)
  val (oldestAtomicValid, oldestAtomicIndex) = selectOldest(atomicCandidates)
  val oldestAtomicTag = entryUop(oldestAtomicIndex).robTag

  val m1Candidates = (0 until entries).map(index =>
      readyForIssue(index) &&
      entryAllowM1(index) &&
      // A cacheable load cannot pass an older store that is still waiting for
      // its operands.  Such a store is not yet visible to the LSQ, so letting
      // M1 issue would make forwarding impossible and can expose stale data.
      !olderStoreInQueue(index) &&
      (!oldestAtomicValid || !ROBTagOrder.isYounger(
        entryUop(index).robTag, oldestAtomicTag, io.robHeadTag, config)))
  val (m1SelectedValid, m1SelectedIndex) = selectOldest(m1Candidates)

  val m0Candidates = (0 until entries).map(index =>
      readyForIssue(index) &&
      entryAllowM0(index) &&
      // M0 is also a legal endpoint for ordinary loads.  They obey the same
      // unresolved-older-store rule as M1; otherwise M0 fallback can bypass
      // the barrier that protects the cacheable-load path.
      !(entryUop(index).uopClass === UopClass.Load &&
        olderStoreInQueue(index)) &&
      (!oldestAtomicValid || !ROBTagOrder.isYounger(
        entryUop(index).robTag, oldestAtomicTag, io.robHeadTag, config)) &&
      !(m1SelectedValid && m1SelectedIndex === index.U))
  val (m0SelectedValid, m0SelectedIndex) = selectOldest(m0Candidates)

  val recoveryBlocked = io.flush || io.squash.valid
  io.m0Issue.valid := m0SelectedValid && !recoveryBlocked
  io.m0Issue.bits := readyEntries(m0SelectedIndex)
  io.m1Issue.valid := m1SelectedValid && !recoveryBlocked
  io.m1Issue.bits := readyEntries(m1SelectedIndex)

  when(io.m0Issue.valid) {
    assert(io.m0Issue.bits.allowedEndpoints(ExecutionEndpoint.M0General.asUInt),
      "MemIQ sent an ineligible uop to M0")
  }
  when(io.m1Issue.valid) {
    assert(io.m1Issue.bits.allowedEndpoints(ExecutionEndpoint.M1Load.asUInt),
      "MemIQ sent an ineligible uop to M1")
  }
  when(io.m0Issue.valid && io.m1Issue.valid) {
    assert(m0SelectedIndex =/= m1SelectedIndex,
      "MemIQ issue ports must select distinct entries")
  }

  val issuedMask = Mux(io.m0Issue.fire, UIntToOH(m0SelectedIndex, entries), 0.U) |
    Mux(io.m1Issue.fire, UIntToOH(m1SelectedIndex, entries), 0.U)
  // A direct dispatch-capacity loop through source wakeup is not admissible at
  // an unfinished top-level integration boundary. The standalone/full LSU
  // queue retains same-cycle recycle; an integration can deliberately use
  // free-only admission until its global issue arbiter owns that dependency.
  val reusableMask = if (allowIssueRecycle) {
    (~entryValid.asUInt).asUInt | issuedMask
  } else {
    (~entryValid.asUInt).asUInt
  }
  val immediateReusable = PopCount(reusableMask)
  io.enqueueCapacity := Mux(recoveryBlocked, 0.U,
    Mux(immediateReusable >= config.decodeWidth.U, config.decodeWidth.U,
      immediateReusable(1, 0)))
  val requestedEnqueueCount = PopCount(io.enqueue.map(_.valid))
  val enqueueReady = !recoveryBlocked && immediateReusable >= requestedEnqueueCount
  io.enqueue.foreach(_.ready := enqueueReady)
  assert(!io.enqueue(1).valid || io.enqueue(0).valid,
    "MemIQ enqueue lane 1 cannot be valid when lane 0 is a bubble")

  val allocation0OH = PriorityEncoderOH(reusableMask)
  val reusableAfter0 = reusableMask &
    ~Mux(io.enqueue(0).fire, allocation0OH, 0.U(entries.W))
  val allocation1OH = PriorityEncoderOH(reusableAfter0)
  val allocationIndex = VecInit(OHToUInt(allocation0OH), OHToUInt(allocation1OH))
  val enqueueCount = PopCount(io.enqueue.map(_.fire))
  val issueCount = PopCount(Seq(io.m0Issue.fire, io.m1Issue.fire))

  val squashSurvivor = VecInit((0 until entries).map(index =>
    entryValid(index) && !ROBTagOrder.isYounger(
      entryUop(index).robTag, io.squash.bits, io.robHeadTag, config)))

  when(io.flush) {
    entryValid.foreach(_ := false.B)
    count := 0.U
  }.elsewhen(io.squash.valid) {
    for (index <- 0 until entries) {
      entryValid(index) := squashSurvivor(index)
    }
    count := PopCount(squashSurvivor)
  }.otherwise {
    count := count + enqueueCount - issueCount
    for (index <- 0 until entries) {
      when(entryValid(index)) {
        entryUop(index) := readyEntries(index)
      }
    }
    when(io.m0Issue.fire) { entryValid(m0SelectedIndex) := false.B }
    when(io.m1Issue.fire) { entryValid(m1SelectedIndex) := false.B }
    for (lane <- 0 until config.decodeWidth) {
      when(io.enqueue(lane).fire) {
        entryValid(allocationIndex(lane)) := true.B
        entryUop(allocationIndex(lane)) := withReady(io.enqueue(lane).bits)
        entryAllowM0(allocationIndex(lane)) :=
          io.enqueue(lane).bits.allowedEndpoints(ExecutionEndpoint.M0General.asUInt)
        entryAllowM1(allocationIndex(lane)) :=
          io.enqueue(lane).bits.allowedEndpoints(ExecutionEndpoint.M1Load.asUInt)
      }
    }
  }

  assert(count <= entries.U, "MemIQ occupancy exceeded its configured depth")
  assert(PopCount(entryValid) === count,
    "MemIQ occupancy credit must equal the number of valid entries")
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "MemIQ squash boundary ROB index out of range")
    assert(!io.m0Issue.fire && !io.m1Issue.fire && !io.enqueue.exists(_.fire),
      "MemIQ transferred work during selective squash")
  }
  io.count := count
}
