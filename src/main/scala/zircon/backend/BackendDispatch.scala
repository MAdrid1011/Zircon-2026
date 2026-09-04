package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.{FetchQueueEntry, FloatingAdmission, FloatingOperation,
  IntOperation, RV32IDecoder}

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
    val mstatusFs = Input(UInt(2.W))
    val currentFrm = Input(UInt(3.W))

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
    val floatingCapacity = Input(UInt(2.W))
    val intEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val longEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val memEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val floatingEnqueue = Vec(config.decodeWidth, Decoupled(new UopRef(config)))
    val floatingAllocate = Output(Vec(config.decodeWidth,
      Valid(new FloatingScoreboardAllocation(config))))
    val floatingScoreboardEmpty = Input(Bool())
    val floatingAdmissionBlocked = Input(Bool())
    val floatingControlWriteAccepted = Output(Valid(UInt(config.robTagWidth.W)))

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
  val floatingAdmissions = Seq.fill(config.decodeWidth)(Module(new FloatingAdmission))
  val decoded = Wire(Vec(config.decodeWidth,
    chiselTypeOf(decoders.head.io.decoded)))
  for (lane <- 0 until config.decodeWidth) {
    decoders(lane).io.instruction := io.input(lane).bits.instruction
    decoded(lane) := decoders(lane).io.decoded
    floatingAdmissions(lane).io.instruction := io.input(lane).bits.instruction
    floatingAdmissions(lane).io.mstatusFs := io.mstatusFs
    floatingAdmissions(lane).io.currentFrm := io.currentFrm
  }

  val fetchFault = VecInit(io.input.map(_.bits.fault.valid))
  val floatingOpcode = VecInit((0 until config.decodeWidth).map(lane =>
    io.input(lane).valid && !fetchFault(lane) &&
      floatingAdmissions(lane).io.floatingOpcode))
  val liveFloating = VecInit((0 until config.decodeWidth).map(lane =>
    io.input(lane).valid && !fetchFault(lane) && floatingAdmissions(lane).io.live))
  val floatingMemory = VecInit((0 until config.decodeWidth).map(lane =>
    liveFloating(lane) && floatingAdmissions(lane).io.decoded.isMemory))
  val executes = VecInit((0 until config.decodeWidth).map(lane =>
    io.input(lane).valid && !fetchFault(lane) &&
      (decoded(lane).legal || floatingAdmissions(lane).io.live)))
  val needsPhysical = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.writesIntegerRd &&
        floatingAdmissions(lane).io.decoded.rd =/= 0.U,
      decoded(lane).writesRd && decoded(lane).rd =/= 0.U)))
  val needsBdb = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).uopClass === UopClass.Branch))
  val needsInt = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).allowedEndpoints(1, 0).orR))
  val needsLong = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && decoded(lane).allowedEndpoints(2)))
  val needsMem = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && (floatingMemory(lane) ||
      (!liveFloating(lane) && decoded(lane).allowedEndpoints(4, 3).orR))))
  val needsFloating = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && liveFloating(lane) && !floatingMemory(lane)))
  val needsFloatingState = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && liveFloating(lane)))
  val floatingControlWrite = VecInit((0 until config.decodeWidth).map(lane =>
    executes(lane) && !liveFloating(lane) &&
      decoded(lane).uopClass === UopClass.Csr && decoded(lane).csrWrite &&
      (decoded(lane).csrAddress === "h300".U ||
        decoded(lane).csrAddress === MachineCSRAddress.Frm.U ||
        decoded(lane).csrAddress === MachineCSRAddress.Fcsr.U)))

  private def countFor(mask: Seq[Bool], needs: Vec[Bool]): UInt =
    PopCount(mask.zip(needs).map { case (selected, needed) => selected && needed })

  private def fits(mask: Seq[Bool], instructionCount: Int): Bool = {
    val physicalCount = countFor(mask, needsPhysical)
    val bdbCount = countFor(mask, needsBdb)
    val intCount = countFor(mask, needsInt)
    val longCount = countFor(mask, needsLong)
    val memCount = countFor(mask, needsMem)
    val floatingCount = countFor(mask, needsFloating)
    val floatingStateCount = countFor(mask, needsFloatingState)
    val floatingOpcodeCount = countFor(mask, floatingOpcode)
    val floatingControlWriteCount = countFor(mask, floatingControlWrite)
    val noFloatingStateDependency = floatingOpcodeCount === 0.U &&
      floatingControlWriteCount === 0.U
    val controlWriteAllowed = !io.floatingAdmissionBlocked &&
      floatingOpcodeCount === 0.U && floatingControlWriteCount <= 1.U
    val floatingOpcodeAllowed = !io.floatingAdmissionBlocked &&
      floatingControlWriteCount === 0.U &&
      (floatingStateCount === 0.U || io.floatingScoreboardEmpty)
    io.robCapacity >= instructionCount.U &&
      io.renameFreeCount >= physicalCount &&
      io.intCapacity >= intCount &&
      io.longCapacity >= longCount &&
      io.memCapacity >= memCount &&
      io.floatingCapacity >= floatingCount &&
      bdbCount <= 1.U && floatingCount <= 1.U &&
      floatingStateCount <= 1.U &&
      (bdbCount === 0.U || io.bdbAllocate.ready) &&
      // F instructions observe only committed FS/frm state. A control write
      // is isolated from F dispatch and only one may remain in flight.
      (noFloatingStateDependency || controlWriteAllowed || floatingOpcodeAllowed)
  }

  val oneMask = Seq(true.B, false.B)
  val twoMask = Seq(true.B, true.B)
  val oneEligible = io.input(0).valid
  val twoEligible = io.input(0).valid && io.input(1).valid
  val selectTwo = !io.blocked && twoEligible && fits(twoMask, 2)
  val selectOne = !io.blocked && !selectTwo && oneEligible && fits(oneMask, 1)
  val selected = VecInit(selectOne || selectTwo, selectTwo)
  val selectedCount = Mux(selectTwo, 2.U, Mux(selectOne, 1.U, 0.U))

  // Endpoint ingress is an architectural part of dispatch acceptance.  The
  // production core places a recoverable register boundary in front of each
  // non-integer queue; requiring that boundary to accept the selected prefix
  // prevents ROB/rename state from advancing while an endpoint is blocked.
  // The helper mirrors driveQueue's compact-prefix mapping: lane zero is the
  // first selected uop for an endpoint, and lane one is present only when both
  // selected uops target that endpoint.
  private def endpointReady(
      output: Vec[DecoupledIO[UopRef]],
      needed: Vec[Bool]
  ): Bool = {
    val matches = VecInit((0 until config.decodeWidth).map(lane =>
      selected(lane) && needed(lane)))
    !matches.asUInt.orR ||
      (output(0).ready && (!matches.asUInt.andR || output(1).ready))
  }

  // Integer IQ already exposes its registered capacity promise through
  // `intCapacity`; including its lane-ready feedback here would recreate its
  // existing two-lane valid/ready dependency. The three non-integer ingress
  // boundaries have occupancy-only ready signals, so they can be checked
  // directly without forming a combinational loop.
  val selectedOutputsReady = if (config.enableM2Observation) {
    // Observation builds intentionally retain the zero-latency endpoint
    // contract used by the cycle-accurate start-mask tests.
    true.B
  } else endpointReady(io.longEnqueue, needsLong) &&
    endpointReady(io.memEnqueue, needsMem) &&
    endpointReady(io.floatingEnqueue, needsFloating)

  for (lane <- 0 until config.decodeWidth) {
    val requestExecutes = selected(lane) && !fetchFault(lane) &&
      (decoded(lane).legal || floatingAdmissions(lane).io.live)
    io.renameRequest(lane).valid := selected(lane)
    io.renameRequest(lane).rs1 := Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.rs1, decoded(lane).rs1)
    io.renameRequest(lane).rs2 := Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.rs2, decoded(lane).rs2)
    io.renameRequest(lane).rd := Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.rd, decoded(lane).rd)
    io.renameRequest(lane).readsRs1 := requestExecutes && Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.readsIntegerRs1, decoded(lane).readsRs1)
    io.renameRequest(lane).readsRs2 := requestExecutes && Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.readsIntegerRs2, decoded(lane).readsRs2)
    io.renameRequest(lane).writesRd := requestExecutes && Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.writesIntegerRd, decoded(lane).writesRd)
  }

  val dispatchFire = if (config.enableM2Observation) {
    selectedCount =/= 0.U && io.renameReady
  } else {
    selectedCount =/= 0.U && io.renameReady && selectedOutputsReady
  }
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
    val readsIntegerRs1 = Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.readsIntegerRs1, decoded(lane).readsRs1)
    val readsIntegerRs2 = Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.readsIntegerRs2, decoded(lane).readsRs2)
    val source0Ready = Mux(readsIntegerRs1,
      io.integerReady(response.sourcePhysical1), true.B)
    val source1Ready = Mux(readsIntegerRs2,
      io.integerReady(response.sourcePhysical2), true.B)
    val lane1DependsOnLane0Source0 = if (lane == 1)
      io.renameResponse(0).allocates && readsIntegerRs1 &&
        response.sourcePhysical1 === io.renameResponse(0).newDestinationPhysical
    else false.B
    val lane1DependsOnLane0Source1 = if (lane == 1)
      io.renameResponse(0).allocates && readsIntegerRs2 &&
        response.sourcePhysical2 === io.renameResponse(0).newDestinationPhysical
    else false.B

    laneUop(lane).robTag := io.robTags(lane).bits
    laneUop(lane).allowedEndpoints := Mux(floatingMemory(lane),
      Mux(floatingAdmissions(lane).io.decoded.memoryWrite,
        EndpointMask.M0.U(EndpointMask.Width.W),
        EndpointMask.CacheableLoadCandidate.U(EndpointMask.Width.W)),
      Mux(liveFloating(lane),
        EndpointMask.E2.U(EndpointMask.Width.W), decoded(lane).allowedEndpoints))
    laneUop(lane).uopClass := Mux(floatingMemory(lane),
      Mux(floatingAdmissions(lane).io.decoded.memoryWrite,
        UopClass.Store, UopClass.Load),
      Mux(liveFloating(lane), UopClass.Floating, decoded(lane).uopClass))
    laneUop(lane).operation := Mux(liveFloating(lane), 0.U,
      decoded(lane).operation.asUInt)
    laneUop(lane).sourceKind(0) := Mux(liveFloating(lane),
      Mux(floatingAdmissions(lane).io.decoded.readsIntegerRs1,
        SourceKind.IntegerRegister,
        Mux(floatingAdmissions(lane).io.decoded.readsFloatRs1,
          SourceKind.FloatingRegister, SourceKind.None)),
      Mux(decoded(lane).readsRs1,
      SourceKind.IntegerRegister,
      Mux(decoded(lane).operation === IntOperation.Auipc,
        SourceKind.ProgramCounter, SourceKind.None)))
    laneUop(lane).sourceKind(1) := Mux(liveFloating(lane),
      Mux(floatingAdmissions(lane).io.decoded.readsIntegerRs2,
        SourceKind.IntegerRegister,
        Mux(floatingAdmissions(lane).io.decoded.readsFloatRs2,
          SourceKind.FloatingRegister, SourceKind.None)),
      Mux(decoded(lane).readsRs2,
      SourceKind.IntegerRegister,
      Mux(decoded(lane).operandBImmediate,
        SourceKind.Immediate, SourceKind.None)))
    laneUop(lane).sourceKind(2) := Mux(liveFloating(lane) &&
      floatingAdmissions(lane).io.decoded.readsFloatRs3,
      SourceKind.FloatingRegister, SourceKind.None)
    laneUop(lane).sourcePhysical(0) := response.sourcePhysical1
    laneUop(lane).sourcePhysical(1) := response.sourcePhysical2
    laneUop(lane).sourceReady(0) := source0Ready &&
      !lane1DependsOnLane0Source0
    laneUop(lane).sourceReady(1) := source1Ready &&
      !lane1DependsOnLane0Source1
    laneUop(lane).sourceReady(2) := true.B
    laneUop(lane).destinationPhysical := response.newDestinationPhysical
    laneUop(lane).writesInteger := response.allocates
    laneUop(lane).writesFloat := liveFloating(lane) &&
      floatingAdmissions(lane).io.decoded.writesFloatRd
    laneUop(lane).floatingOperation := Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.operation, FloatingOperation.Invalid)
    laneUop(lane).floatingSource(0) := floatingAdmissions(lane).io.decoded.rs1
    laneUop(lane).floatingSource(1) := floatingAdmissions(lane).io.decoded.rs2
    laneUop(lane).floatingSource(2) := floatingAdmissions(lane).io.decoded.rs3
    laneUop(lane).floatingDestination := Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.rd, 0.U)
    laneUop(lane).floatingRoundingMode :=
      floatingAdmissions(lane).io.effectiveRoundingMode
    laneUop(lane).immediate := Mux(floatingMemory(lane),
      floatingAdmissions(lane).io.decoded.immediate, decoded(lane).immediate)

    val entry = io.robEnqueue(lane).bits.entry
    io.robEnqueue(lane).valid := dispatchFire && selected(lane)
    io.robEnqueue(lane).bits.initiallyComplete :=
      fetchFault(lane) ||
        (!decoded(lane).legal && !floatingAdmissions(lane).io.live)
    entry.pc := io.input(lane).bits.prediction.pc
    entry.instruction := io.input(lane).bits.instruction
    entry.privilege := io.input(lane).bits.privilege
    entry.decoded := decoded(lane)
    when(floatingMemory(lane)) {
      entry.decoded.uopClass := Mux(
        floatingAdmissions(lane).io.decoded.memoryWrite,
        UopClass.Store, UopClass.Load)
      entry.decoded.isMemory := true.B
    }
    entry.floating := Mux(floatingAdmissions(lane).io.floatingOpcode,
      floatingAdmissions(lane).io.decoded,
      0.U.asTypeOf(new zircon.frontend.FloatingDecodedInstruction))
    entry.architecturalDestination := Mux(liveFloating(lane),
      floatingAdmissions(lane).io.decoded.rd, decoded(lane).rd)
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

    val hasFault = fetchFault(lane) ||
      (!decoded(lane).legal && !floatingAdmissions(lane).io.live)
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
  driveQueue(io.floatingEnqueue, needsFloating)

  val selectedFloating = VecInit((0 until config.decodeWidth).map(lane =>
    selected(lane) && needsFloating(lane)))
  val selectedFloatingState = VecInit((0 until config.decodeWidth).map(lane =>
    selected(lane) && needsFloatingState(lane)))
  val selectedFloatingControlWrite = VecInit((0 until config.decodeWidth).map(lane =>
    selected(lane) && floatingControlWrite(lane)))
  val floatingControlLane = Mux(selectedFloatingControlWrite(0), 0.U, 1.U)
  io.floatingControlWriteAccepted.valid := dispatchFire &&
    selectedFloatingControlWrite.asUInt.orR
  io.floatingControlWriteAccepted.bits := io.robTags(floatingControlLane).bits
  val floatingLane = Mux(selectedFloatingState(0), 0.U, 1.U)
  val floatingDecoded = Mux(selectedFloatingState(0),
    floatingAdmissions(0).io.decoded, floatingAdmissions(1).io.decoded)
  for (lane <- 0 until config.decodeWidth) {
    io.floatingAllocate(lane).valid := lane.U === 0.U && dispatchFire &&
      (selectedFloating.asUInt.orR ||
        (selected.asUInt & floatingMemory.asUInt &
          VecInit((0 until config.decodeWidth).map(index =>
            floatingAdmissions(index).io.decoded.writesFloatRd)).asUInt).orR)
    io.floatingAllocate(lane).bits.robTag := io.robTags(floatingLane).bits
    io.floatingAllocate(lane).bits.sourceValid(0) := floatingDecoded.readsFloatRs1
    io.floatingAllocate(lane).bits.sourceValid(1) := floatingDecoded.readsFloatRs2
    io.floatingAllocate(lane).bits.sourceValid(2) := floatingDecoded.readsFloatRs3
    io.floatingAllocate(lane).bits.source(0) := floatingDecoded.rs1
    io.floatingAllocate(lane).bits.source(1) := floatingDecoded.rs2
    io.floatingAllocate(lane).bits.source(2) := floatingDecoded.rs3
    io.floatingAllocate(lane).bits.destinationValid := floatingDecoded.writesFloatRd
    io.floatingAllocate(lane).bits.destination := floatingDecoded.rd
  }

  assert(!io.input(1).valid || io.input(0).valid,
    "dispatch lane 1 cannot be valid when lane 0 is a bubble")
  for (lane <- 0 until config.decodeWidth) {
    when(executes(lane)) {
      assert(PopCount(Seq(needsInt(lane), needsLong(lane), needsMem(lane),
        needsFloating(lane))) === 1.U,
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
  for (queue <- Seq(io.intEnqueue, io.longEnqueue, io.memEnqueue,
      io.floatingEnqueue)) {
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
  when(io.floatingAllocate(0).valid) {
    assert(io.floatingScoreboardEmpty,
      "floating dispatch admitted work while an FPR reservation was live")
  }
}
