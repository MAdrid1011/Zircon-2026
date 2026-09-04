package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class PhysicalWakeup(config: ZirconCoreConfig) extends Bundle {
  val valid = Bool()
  val physical = UInt(log2Ceil(config.intPhysicalRegisters).W)
}

class IntegerIssueQueue(config: ZirconCoreConfig) extends Module {
  private val entries = config.intIssueEntries
  private val indexWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(2, Decoupled(new UopRef(config))))
    val wakeup = Input(Vec(2, new PhysicalWakeup(config)))
    val issueE0 = Decoupled(new UopRef(config))
    val issueE1 = Decoupled(new UopRef(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(countWidth.W))
    val enqueueCapacity = Output(UInt(2.W))
  })

  val entryValid = RegInit(VecInit.fill(entries)(false.B))
  val entryUop = Reg(Vec(entries, new UopRef(config)))
  // Readiness is dynamic state. Keep it in a narrow bank so wakeup updates
  // do not rewrite the full UopRef payload or route through its metadata.
  val entrySourceReady = Reg(Vec(entries, Vec(3, Bool())))
  // Endpoint eligibility is immutable for a retained uop.  Keep one local
  // copy per selector so the wide UopRef register does not fan out through
  // both oldest-candidate trees and the issue payloads.
  val entryAllowE0 = Reg(Vec(entries, Bool()))
  val entryAllowE1 = Reg(Vec(entries, Bool()))
  val count = RegInit(0.U(countWidth.W))

  // Compute each age once.  The selector is used for several endpoint
  // candidate sets; rebuilding the ROB-distance comparator in every call
  // creates a large, high-fanout cone in the integrated core.
  val entryAge = VecInit(entryUop.map(uop =>
    ROBTagOrder.ageFromHead(uop.robTag, io.robHeadTag, config)))

  private def selectOldest(candidates: Seq[Bool]): (Bool, UInt) = {
    // A linear fold makes the oldest-entry decision traverse every slot in
    // series.  Reduce adjacent candidates as a tree so the critical path is
    // logarithmic in queue depth; left wins ties to preserve slot order.
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

  // Compute wakeup only against the narrow readiness bank. The source
  // physical numbers remain static in entryUop, while sourceReady updates
  // stay local to this bank.
  val awakenedSourceReady = Wire(Vec(entries, Vec(3, Bool())))
  val awakenedEntries = Wire(Vec(entries, new UopRef(config)))
  for (index <- 0 until entries) {
    awakenedEntries(index) := entryUop(index)
    for (source <- 0 until 3) {
      val wakes = if (source < 2) {
        io.wakeup.map(wakeup =>
          wakeup.valid && wakeup.physical === entryUop(index).sourcePhysical(source))
          .reduce(_ || _)
      } else false.B
      awakenedSourceReady(index)(source) := entrySourceReady(index)(source) || wakes
      awakenedEntries(index).sourceReady(source) := awakenedSourceReady(index)(source)
    }
  }
  val ready = awakenedSourceReady.map(_.asUInt.andR)
  val e0ExclusiveCandidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) &&
      entryAllowE0(index) && !entryAllowE1(index))
  val (exclusiveE0Valid, exclusiveE0Index) = selectOldest(e0ExclusiveCandidates)

  val e1AfterExclusiveCandidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) && entryAllowE1(index) &&
      !(exclusiveE0Valid && exclusiveE0Index === index.U))
  val (e1AfterExclusiveValid, e1AfterExclusiveIndex) =
    selectOldest(e1AfterExclusiveCandidates)

  val normalE1Candidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) && entryAllowE1(index))
  val (normalE1Valid, normalE1Index) = selectOldest(normalE1Candidates)
  val normalE0Candidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) && entryAllowE0(index) &&
      !(normalE1Valid && normalE1Index === index.U))
  val (normalE0Valid, normalE0Index) = selectOldest(normalE0Candidates)

  val selectedE0Valid = Mux(exclusiveE0Valid, true.B, normalE0Valid)
  val selectedE0Index = Mux(exclusiveE0Valid, exclusiveE0Index, normalE0Index)
  val selectedE1Valid = Mux(exclusiveE0Valid, e1AfterExclusiveValid, normalE1Valid)
  val selectedE1Index = Mux(exclusiveE0Valid, e1AfterExclusiveIndex, normalE1Index)

  val recoveryBlocked = io.flush || io.squash.valid
  io.issueE0.valid := selectedE0Valid && !recoveryBlocked
  io.issueE0.bits := awakenedEntries(selectedE0Index)
  // Endpoint eligibility is already held in local one-bit state.  Replacing
  // the wide mask on the dynamic payload mux keeps it off the issue/LSU cone.
  io.issueE0.bits.allowedEndpoints := EndpointMask.E0.U(EndpointMask.Width.W)
  io.issueE1.valid := selectedE1Valid && !recoveryBlocked
  io.issueE1.bits := awakenedEntries(selectedE1Index)
  io.issueE1.bits.allowedEndpoints := EndpointMask.E1.U(EndpointMask.Width.W)
  when(io.issueE0.valid) {
    assert(entryAllowE0(selectedE0Index), "IntIQ sent an ineligible uop to E0")
  }
  when(io.issueE1.valid) {
    assert(entryAllowE1(selectedE1Index), "IntIQ sent an ineligible uop to E1")
  }
  when(io.issueE0.valid && io.issueE1.valid) {
    assert(selectedE0Index =/= selectedE1Index,
      "IntIQ issue ports must select distinct entries")
  }

  val issuedMask = Mux(io.issueE0.fire, UIntToOH(selectedE0Index, entries), 0.U) |
    Mux(io.issueE1.fire, UIntToOH(selectedE1Index, entries), 0.U)
  val reusableMask = (~entryValid.asUInt).asUInt | issuedMask
  val immediateReusable = PopCount(reusableMask)
  io.enqueueCapacity := Mux(recoveryBlocked, 0.U,
    Mux(immediateReusable >= 2.U, 2.U, immediateReusable(1, 0)))
  val requestedEnqueueCount = PopCount(io.enqueue.map(_.valid))
  val enqueueBundleReady = !recoveryBlocked &&
    immediateReusable >= requestedEnqueueCount
  io.enqueue.foreach(_.ready := enqueueBundleReady)
  assert(!io.enqueue(1).valid || io.enqueue(0).valid,
    "IntIQ enqueue lane 1 cannot be valid when lane 0 is a bubble")

  val allocation0OH = PriorityEncoderOH(reusableMask)
  val reusableAfter0 = reusableMask &
    ~Mux(io.enqueue(0).fire, allocation0OH, 0.U(entries.W))
  val allocation1OH = PriorityEncoderOH(reusableAfter0)
  val allocationIndex = VecInit(OHToUInt(allocation0OH), OHToUInt(allocation1OH))
  val enqueueCount = PopCount(io.enqueue.map(_.fire))
  val issueCount = PopCount(Seq(io.issueE0.fire, io.issueE1.fire))

  val squashSurvivor = VecInit((0 until entries).map(index =>
    entryValid(index) && !ROBTagOrder.isYounger(
      entryUop(index).robTag,
      io.squash.bits,
      io.robHeadTag,
      config)))

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
        entrySourceReady(index) := awakenedSourceReady(index)
      }
    }
    when(io.issueE0.fire) { entryValid(selectedE0Index) := false.B }
    when(io.issueE1.fire) { entryValid(selectedE1Index) := false.B }
    for (lane <- 0 until 2) {
      when(io.enqueue(lane).fire) {
        entryValid(allocationIndex(lane)) := true.B
        entryUop(allocationIndex(lane)) := io.enqueue(lane).bits
        for (source <- 0 until 3) {
          val wakes = if (source < 2) {
            io.wakeup.map(wakeup =>
              wakeup.valid && wakeup.physical ===
                io.enqueue(lane).bits.sourcePhysical(source)).reduce(_ || _)
          } else false.B
          entrySourceReady(allocationIndex(lane))(source) :=
            io.enqueue(lane).bits.sourceReady(source) || wakes
        }
        entryAllowE0(allocationIndex(lane)) :=
          io.enqueue(lane).bits.allowedEndpoints(0)
        entryAllowE1(allocationIndex(lane)) :=
          io.enqueue(lane).bits.allowedEndpoints(1)
      }
    }
  }

  assert(count <= entries.U, "IntIQ occupancy exceeded its configured depth")
  assert(PopCount(entryValid) === count,
    "IntIQ occupancy credit must equal the number of valid entries")
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "IntIQ squash boundary ROB index out of range")
    assert(!io.issueE0.fire && !io.issueE1.fire,
      "IntIQ issued while selective squash was active")
    assert(!io.enqueue.exists(_.fire),
      "IntIQ enqueued while selective squash was active")
  }
  io.count := count
}
