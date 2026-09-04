package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Four-entry compact E2 queue. Integer readiness is sampled from the shared
  * PRF-ready table so E2 does not require another wakeup or PRF-read network.
  */
class LongIssueQueue(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val entries = config.longIssueEntries
  private val indexWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(config.decodeWidth, Decoupled(new UopRef(config))))
    val integerReady = Input(UInt(config.intPhysicalRegisters.W))
    val issue = Decoupled(new UopRef(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val count = Output(UInt(countWidth.W))
    val enqueueCapacity = Output(UInt(2.W))
  })

  val entryValid = RegInit(VecInit.fill(entries)(false.B))
  val entryUop = Reg(Vec(entries, new UopRef(config)))
  val count = RegInit(0.U(countWidth.W))

  private def sourceReady(uop: UopRef, source: Int): Bool = {
    if (source < 2) {
      Mux(uop.sourceKind(source) === SourceKind.IntegerRegister,
        uop.sourceReady(source) || io.integerReady(uop.sourcePhysical(source)),
        uop.sourceReady(source))
    } else uop.sourceReady(source)
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

  val entryAge = VecInit(entryUop.map(uop =>
    ROBTagOrder.ageFromHead(uop.robTag, io.robHeadTag, config)))
  val readyEntries = Wire(Vec(entries, new UopRef(config)))
  for (index <- 0 until entries) {
    readyEntries(index) := withReady(entryUop(index))
  }

  val candidates = (0 until entries).map(index =>
    entryValid(index) && allSourcesReady(readyEntries(index)) &&
      entryUop(index).allowedEndpoints(ExecutionEndpoint.E2LongPipe.asUInt))
  var candidateValid = candidates.toVector
  var candidateIndex = (0 until entries).map(_.U(indexWidth.W)).toVector
  var candidateAge = entryAge.toVector
  while (candidateValid.length > 1) {
    val nextValid = Vector.newBuilder[Bool]
    val nextIndex = Vector.newBuilder[UInt]
    val nextAge = Vector.newBuilder[UInt]
    var pair = 0
    while (pair < candidateValid.length) {
      if (pair + 1 == candidateValid.length) {
        nextValid += candidateValid(pair)
        nextIndex += candidateIndex(pair)
        nextAge += candidateAge(pair)
      } else {
        val rightWins = candidateValid(pair + 1) &&
          (!candidateValid(pair) || candidateAge(pair + 1) < candidateAge(pair))
        nextValid += (candidateValid(pair) || candidateValid(pair + 1))
        nextIndex += Mux(rightWins, candidateIndex(pair + 1), candidateIndex(pair))
        nextAge += Mux(rightWins, candidateAge(pair + 1), candidateAge(pair))
      }
      pair += 2
    }
    candidateValid = nextValid.result()
    candidateIndex = nextIndex.result()
    candidateAge = nextAge.result()
  }
  val selectedValid = candidateValid.head
  val selectedIndex = candidateIndex.head

  val recoveryBlocked = io.flush || io.squash.valid
  io.issue.valid := selectedValid && !recoveryBlocked
  io.issue.bits := readyEntries(selectedIndex)
  io.issue.bits.allowedEndpoints := EndpointMask.E2.U(EndpointMask.Width.W)
  when(io.issue.valid) {
    assert(entryUop(selectedIndex).allowedEndpoints(ExecutionEndpoint.E2LongPipe.asUInt),
      "LongIQ issued a uop not eligible for E2")
    assert(io.issue.bits.uopClass === UopClass.Multiply ||
      io.issue.bits.uopClass === UopClass.Divide,
      "LongIQ issued a uop outside the RV32M classes")
  }

  // Admission intentionally uses registered free entries only. Including the
  // dynamically-ready issue fire here would feed PRF readiness through dispatch
  // capacity in the same cycle and create a top-level combinational loop.
  val reusableMask = (~entryValid.asUInt).asUInt
  val immediatelyFree = PopCount(reusableMask)
  io.enqueueCapacity := Mux(recoveryBlocked, 0.U,
    Mux(immediatelyFree >= config.decodeWidth.U, config.decodeWidth.U,
      immediatelyFree(1, 0)))
  val requestedEnqueues = PopCount(io.enqueue.map(_.valid))
  val enqueueReady = !recoveryBlocked && immediatelyFree >= requestedEnqueues
  io.enqueue.foreach(_.ready := enqueueReady)
  assert(!io.enqueue(1).valid || io.enqueue(0).valid,
    "LongIQ enqueue lane 1 cannot be valid when lane 0 is a bubble")

  val allocation0OH = PriorityEncoderOH(reusableMask)
  val reusableAfter0 = reusableMask &
    ~Mux(io.enqueue(0).fire, allocation0OH, 0.U(entries.W))
  val allocation1OH = PriorityEncoderOH(reusableAfter0)
  val allocationIndex = VecInit(OHToUInt(allocation0OH), OHToUInt(allocation1OH))
  val enqueueCount = PopCount(io.enqueue.map(_.fire))

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
    count := count + enqueueCount - io.issue.fire
    for (index <- 0 until entries) {
      when(entryValid(index)) {
        entryUop(index) := readyEntries(index)
      }
    }
    when(io.issue.fire) { entryValid(selectedIndex) := false.B }
    for (lane <- 0 until config.decodeWidth) {
      when(io.enqueue(lane).fire) {
        entryValid(allocationIndex(lane)) := true.B
        entryUop(allocationIndex(lane)) := withReady(io.enqueue(lane).bits)
      }
    }
  }

  assert(count <= entries.U, "LongIQ occupancy exceeded its configured depth")
  assert(PopCount(entryValid) === count,
    "LongIQ occupancy credit must equal the number of valid entries")
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "LongIQ squash boundary ROB index out of range")
    assert(!io.issue.fire && !io.enqueue.exists(_.fire),
      "LongIQ transferred work during selective squash")
  }
  io.count := count
}
