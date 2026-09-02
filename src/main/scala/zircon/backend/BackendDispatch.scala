package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.{FetchQueueEntry, IntOperation, RV32IDecoder}

/** Stateless two-wide longest-prefix rename/dispatch coordinator. */
class BackendDispatch(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)
  private val freeCountWidth = log2Ceil(config.intPhysicalRegisters + 1)

  val io = IO(new Bundle {
    val input = Flipped(Vec(config.decodeWidth,
      Decoupled(new FetchQueueEntry(config))))
    val blocked = Input(Bool())

    val renameRequest = Output(Vec(config.decodeWidth, new RenameRequest))
    val renameResponse = Input(Vec(config.decodeWidth,
      new RenameResponse(physicalWidth)))
    val renameFreeCount = Input(UInt(freeCountWidth.W))
    val renameReady = Input(Bool())
    val renameAccept = Output(Bool())

    val robCapacity = Input(UInt(2.W))
    val robEnqueue = Vec(config.decodeWidth,
      Decoupled(new ROBEnqueue(config)))
    val robTags = Input(Vec(config.decodeWidth,
      Valid(UInt(config.robTagWidth.W))))

    val intCapacity = Input(UInt(2.W))
    val longCapacity = Input(UInt(2.W))
    val memCapacity = Input(UInt(2.W))
    val intEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val longEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val memEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))

    val bdbAllocate = Decoupled(new BranchDataAllocation(config))
    val bdbAllocatedIndex = Input(Valid(
      UInt(log2Ceil(config.branchDataEntries).W)))

    val integerReady = Input(UInt(config.intPhysicalRegisters.W))
    val readyAllocation = Output(Vec(config.decodeWidth,
      Valid(UInt(physicalWidth.W))))
    val faultCandidate = Output(Vec(config.decodeWidth,
      new FaultCandidate(config)))
    val acceptedCount = Output(UInt(2.W))
  })

  require(config.decodeWidth == 2,
    "BackendDispatch is frozen for two-wide decode")

  val decoders = Seq.fill(config.decodeWidth)(Module(new RV32IDecoder))
  val decoded = Wire(Vec(config.decodeWidth,
    chiselTypeOf(decoders.head.io.decoded)))
  for (lane <- 0 until config.decodeWidth) {
    decoders(lane).io.instruction := io.input(lane).bits.instruction
    decoded(lane) := decoders(lane).io.decoded
  }

  val fetchFault = VecInit(io.input.map(_.bits.fault.valid))
  val executes = VecInit((0 until config.decodeWidth).map(lane =>
    io.input(lane).valid && !fetchFault(lane) && decoded(lane).legal))
  val needsPhysical = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).writesRd && decoded(lane).rd =/= 0.U))
  val needsBdb = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).uopClass === UopClass.Branch))
  val needsInt = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).allowedEndpoints(1, 0).orR))
  val needsLong = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).allowedEndpoints(2)))
  val needsMem = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).allowedEndpoints(4, 3).orR))

  private def countFor(mask: Seq[Bool], needs: Vec[Bool]): UInt =
    PopCount(mask.zip(needs).map { case (selected, needed) => selected && needed })

  private def fits(mask: Seq[Bool], instructionCount: Int): Bool = {
    val physicalCount = countFor(mask, needsPhysical)
    val bdbCount = countFor(mask, needsBdb)
    val intCount = countFor(mask, needsInt)
    val longCount = countFor(mask, needsLong)
    val memCount = countFor(mask, needsMem)
    io.robCapacity >= instructionCount.U &&
      io.renameFreeCount >= physicalCount &&
      io.intCapacity >= intCount &&
      io.longCapacity >= longCount &&
      io.memCapacity >= memCount &&
      bdbCount <= 1.U && (bdbCount === 0.U || io.bdbAllocate.ready)
  }

  val oneMask = Seq(true.B, false.B)
  val twoMask = Seq(true.B, true.B)
  val oneEligible = io.input(0).valid
  val twoEligible = io.input(0).valid && io.input(1).valid
  val selectTwo = !io.blocked && twoEligible && fits(twoMask, 2)
  val selectOne = !io.blocked && !selectTwo && oneEligible && fits(oneMask, 1)
  val selected = VecInit(selectOne || selectTwo, selectTwo)
  val selectedCount = Mux(selectTwo, 2.U, Mux(selectOne, 1.U, 0.U))

  for (lane <- 0 until config.decodeWidth) {
    val requestExecutes = selected(lane) && !fetchFault(lane) &&
      decoded(lane).legal
    io.renameRequest(lane).valid := selected(lane)
    io.renameRequest(lane).rs1 := decoded(lane).rs1
    io.renameRequest(lane).rs2 := decoded(lane).rs2
    io.renameRequest(lane).rd := decoded(lane).rd
    io.renameRequest(lane).readsRs1 := requestExecutes && decoded(lane).readsRs1
    io.renameRequest(lane).readsRs2 := requestExecutes && decoded(lane).readsRs2
    io.renameRequest(lane).writesRd := requestExecutes && decoded(lane).writesRd
  }

  val dispatchFire = selectedCount =/= 0.U && io.renameReady
  io.renameAccept := dispatchFire
  io.input(0).ready := dispatchFire
  io.input(1).ready := dispatchFire && selectTwo
  io.acceptedCount := Mux(dispatchFire, selectedCount, 0.U)

  val bdbLane = Mux(needsBdb(0) && selected(0), 0.U, 1.U)
  val selectedNeedsBdb = (selected.asUInt & needsBdb.asUInt).orR
  io.bdbAllocate.valid := dispatchFire && selectedNeedsBdb
  io.bdbAllocate.bits.robTag := io.robTags(bdbLane).bits
  io.bdbAllocate.bits.metadata := io.input(bdbLane).bits.prediction

  val laneUop = Wire(Vec(config.decodeWidth, new UopRef(config)))
  for (lane <- 0 until config.decodeWidth) {
    val response = io.renameResponse(lane)
    val source0Ready = Mux(decoded(lane).readsRs1,
      io.integerReady(response.sourcePhysical1), true.B)
    val source1Ready = Mux(decoded(lane).readsRs2,
      io.integerReady(response.sourcePhysical2), true.B)
    val lane1DependsOnLane0Source0 = if (lane == 1)
      io.renameResponse(0).allocates && decoded(1).readsRs1 &&
        response.sourcePhysical1 === io.renameResponse(0).newDestinationPhysical
    else false.B
    val lane1DependsOnLane0Source1 = if (lane == 1)
      io.renameResponse(0).allocates && decoded(1).readsRs2 &&
        response.sourcePhysical2 === io.renameResponse(0).newDestinationPhysical
    else false.B

    laneUop(lane).robTag := io.robTags(lane).bits
    laneUop(lane).allowedEndpoints := decoded(lane).allowedEndpoints
    laneUop(lane).uopClass := decoded(lane).uopClass
    laneUop(lane).operation := decoded(lane).operation.asUInt
    laneUop(lane).sourceKind(0) := Mux(decoded(lane).readsRs1,
      SourceKind.IntegerRegister,
      Mux(decoded(lane).operation === IntOperation.Auipc,
        SourceKind.ProgramCounter, SourceKind.None))
    laneUop(lane).sourceKind(1) := Mux(decoded(lane).readsRs2,
      SourceKind.IntegerRegister,
      Mux(decoded(lane).operandBImmediate,
        SourceKind.Immediate, SourceKind.None))
    laneUop(lane).sourceKind(2) := SourceKind.None
    laneUop(lane).sourcePhysical(0) := response.sourcePhysical1
    laneUop(lane).sourcePhysical(1) := response.sourcePhysical2
    laneUop(lane).sourceReady(0) := source0Ready &&
      !lane1DependsOnLane0Source0
    laneUop(lane).sourceReady(1) := source1Ready &&
      !lane1DependsOnLane0Source1
    laneUop(lane).sourceReady(2) := true.B
    laneUop(lane).destinationPhysical := response.newDestinationPhysical
    laneUop(lane).writesInteger := response.allocates
    laneUop(lane).writesFloat := false.B
    laneUop(lane).immediate := decoded(lane).immediate

    val entry = io.robEnqueue(lane).bits.entry
    io.robEnqueue(lane).valid := dispatchFire && selected(lane)
    io.robEnqueue(lane).bits.initiallyComplete :=
      fetchFault(lane) || !decoded(lane).legal
    entry.pc := io.input(lane).bits.prediction.pc
    entry.instruction := io.input(lane).bits.instruction
    entry.privilege := io.input(lane).bits.privilege
    entry.decoded := decoded(lane)
    entry.floating := 0.U.asTypeOf(new zircon.frontend.FloatingDecodedInstruction)
    entry.architecturalDestination := decoded(lane).rd
    entry.oldPhysicalDestination := Mux(response.allocates,
      response.oldDestinationPhysical, 0.U)
    entry.newPhysicalDestination := Mux(response.allocates,
      response.newDestinationPhysical, 0.U)
    entry.allocatesPhysical := response.allocates
    entry.hasBranchData := selected(lane) && needsBdb(lane)
    entry.branchDataIndex := Mux(selected(lane) && needsBdb(lane),
      io.bdbAllocatedIndex.bits, 0.U)

    io.readyAllocation(lane).valid := dispatchFire && selected(lane) &&
      response.allocates
    io.readyAllocation(lane).bits := response.newDestinationPhysical

    val hasFault = fetchFault(lane) || !decoded(lane).legal
    io.faultCandidate(lane).valid := dispatchFire && selected(lane) && hasFault
    io.faultCandidate(lane).record.robTag := io.robTags(lane).bits
    io.faultCandidate(lane).record.cause := Mux(fetchFault(lane),
      io.input(lane).bits.fault.cause, 2.U)
    io.faultCandidate(lane).record.trapValue := Mux(fetchFault(lane),
      io.input(lane).bits.fault.tval, io.input(lane).bits.instruction)
  }

  private def driveQueue(
      output: Vec[DecoupledIO[UopRef]],
      needed: Vec[Bool]
  ): Unit = {
    val matches = VecInit((0 until config.decodeWidth).map(lane =>
      selected(lane) && needed(lane)))
    output(0).valid := dispatchFire && matches.asUInt.orR
    output(0).bits := Mux(matches(0), laneUop(0), laneUop(1))
    output(1).valid := dispatchFire && matches.asUInt.andR
    output(1).bits := laneUop(1)
  }

  driveQueue(io.intEnqueue, needsInt)
  driveQueue(io.longEnqueue, needsLong)
  driveQueue(io.memEnqueue, needsMem)

  assert(!io.input(1).valid || io.input(0).valid,
    "dispatch lane 1 cannot be valid when lane 0 is a bubble")
  for (lane <- 0 until config.decodeWidth) {
    when(executes(lane)) {
      assert(PopCount(Seq(needsInt(lane), needsLong(lane), needsMem(lane))) === 1.U,
        "a legal dispatched uop must select exactly one issue queue")
    }
    when(dispatchFire && selected(lane)) {
      assert(io.robEnqueue(lane).ready,
        "ROB capacity promise disagreed with enqueue ready")
      assert(io.robTags(lane).valid,
        "ROB did not return a tag for a dispatched instruction")
      assert(io.renameResponse(lane).valid,
        "rename did not return a response for a dispatched instruction")
    }
    when(io.faultCandidate(lane).valid) {
      assert(!io.readyAllocation(lane).valid,
        "dispatch-time fault allocated an integer destination")
    }
  }
  for (queue <- Seq(io.intEnqueue, io.longEnqueue, io.memEnqueue)) {
    assert(!queue(1).valid || queue(0).valid,
      "dispatch queue lane 1 escaped without lane 0")
    queue.foreach { lane =>
      when(lane.valid) {
        assert(lane.ready,
          "issue-queue capacity promise disagreed with enqueue ready")
      }
    }
  }
  when(io.bdbAllocate.valid) {
    assert(io.bdbAllocate.ready,
      "BDB capacity promise disagreed with allocation ready")
    assert(io.bdbAllocatedIndex.valid,
      "BDB did not return an index for a dispatched branch")
  }
  when(dispatchFire) {
    assert(io.renameFreeCount >= PopCount(io.readyAllocation.map(_.valid)),
      "dispatch exceeded rename free-list capacity")
  }
}
