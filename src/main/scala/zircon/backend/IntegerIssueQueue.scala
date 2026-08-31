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
  })

  val entryValid = RegInit(VecInit.fill(entries)(false.B))
  val entryUop = Reg(Vec(entries, new UopRef(config)))
  val count = RegInit(0.U(countWidth.W))

  private def ageFromHead(tag: UInt): UInt =
    ROBTagOrder.ageFromHead(tag, io.robHeadTag, config)

  private def selectOldest(candidates: Seq[Bool]): (Bool, UInt) = {
    var selectedValid: Bool = false.B
    var selectedIndex: UInt = 0.U(indexWidth.W)
    var selectedAge: UInt = 0.U((config.robIndexWidth + 1).W)
    for (index <- 0 until entries) {
      val candidateAge = ageFromHead(entryUop(index).robTag)
      val take = candidates(index) && (!selectedValid || candidateAge < selectedAge)
      selectedIndex = Mux(take, index.U, selectedIndex)
      selectedAge = Mux(take, candidateAge, selectedAge)
      selectedValid = selectedValid || candidates(index)
    }
    (selectedValid, selectedIndex)
  }

  private def allSourcesReady(uop: UopRef): Bool = uop.sourceReady.asUInt.andR
  private def allows(uop: UopRef, endpointBit: Int): Bool =
    uop.allowedEndpoints(endpointBit)

  val ready = entryUop.map(allSourcesReady)
  val e0ExclusiveCandidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) &&
      allows(entryUop(index), 0) && !allows(entryUop(index), 1))
  val (exclusiveE0Valid, exclusiveE0Index) = selectOldest(e0ExclusiveCandidates)

  val e1AfterExclusiveCandidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) && allows(entryUop(index), 1) &&
      !(exclusiveE0Valid && exclusiveE0Index === index.U))
  val (e1AfterExclusiveValid, e1AfterExclusiveIndex) =
    selectOldest(e1AfterExclusiveCandidates)

  val normalE1Candidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) && allows(entryUop(index), 1))
  val (normalE1Valid, normalE1Index) = selectOldest(normalE1Candidates)
  val normalE0Candidates = (0 until entries).map(index =>
    entryValid(index) && ready(index) && allows(entryUop(index), 0) &&
      !(normalE1Valid && normalE1Index === index.U))
  val (normalE0Valid, normalE0Index) = selectOldest(normalE0Candidates)

  val selectedE0Valid = Mux(exclusiveE0Valid, true.B, normalE0Valid)
  val selectedE0Index = Mux(exclusiveE0Valid, exclusiveE0Index, normalE0Index)
  val selectedE1Valid = Mux(exclusiveE0Valid, e1AfterExclusiveValid, normalE1Valid)
  val selectedE1Index = Mux(exclusiveE0Valid, e1AfterExclusiveIndex, normalE1Index)

  val recoveryBlocked = io.flush || io.squash.valid
  io.issueE0.valid := selectedE0Valid && !recoveryBlocked
  io.issueE0.bits := entryUop(selectedE0Index)
  io.issueE1.valid := selectedE1Valid && !recoveryBlocked
  io.issueE1.bits := entryUop(selectedE1Index)
  when(io.issueE0.valid) {
    assert(io.issueE0.bits.allowedEndpoints(0), "IntIQ sent an ineligible uop to E0")
  }
  when(io.issueE1.valid) {
    assert(io.issueE1.bits.allowedEndpoints(1), "IntIQ sent an ineligible uop to E1")
  }
  when(io.issueE0.valid && io.issueE1.valid) {
    assert(selectedE0Index =/= selectedE1Index,
      "IntIQ issue ports must select distinct entries")
  }

  val issuedMask = Mux(io.issueE0.fire, UIntToOH(selectedE0Index, entries), 0.U) |
    Mux(io.issueE1.fire, UIntToOH(selectedE1Index, entries), 0.U)
  val reusableMask = (~entryValid.asUInt).asUInt | issuedMask
  val requestedEnqueueCount = PopCount(io.enqueue.map(_.valid))
  val enqueueBundleReady = !recoveryBlocked &&
    PopCount(reusableMask) >= requestedEnqueueCount
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

  private def withWakeup(uop: UopRef): UopRef = {
    val updated = WireDefault(uop)
    for (source <- 0 until 2) {
      val wakes = io.wakeup.map(wakeup =>
        wakeup.valid && wakeup.physical === uop.sourcePhysical(source)).reduce(_ || _)
      updated.sourceReady(source) := uop.sourceReady(source) || wakes
    }
    updated
  }

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
        entryUop(index) := withWakeup(entryUop(index))
      }
    }
    when(io.issueE0.fire) { entryValid(selectedE0Index) := false.B }
    when(io.issueE1.fire) { entryValid(selectedE1Index) := false.B }
    for (lane <- 0 until 2) {
      when(io.enqueue(lane).fire) {
        entryValid(allocationIndex(lane)) := true.B
        entryUop(allocationIndex(lane)) := withWakeup(io.enqueue(lane).bits)
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
