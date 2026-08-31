package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class RenameRequest extends Bundle {
  val valid = Bool()
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val readsRs1 = Bool()
  val readsRs2 = Bool()
  val writesRd = Bool()
}

class RenameResponse(physicalWidth: Int) extends Bundle {
  val valid = Bool()
  val sourcePhysical1 = UInt(physicalWidth.W)
  val sourcePhysical2 = UInt(physicalWidth.W)
  val oldDestinationPhysical = UInt(physicalWidth.W)
  val newDestinationPhysical = UInt(physicalWidth.W)
  val allocates = Bool()
}

class RenameCommit(physicalWidth: Int) extends Bundle {
  val valid = Bool()
  val architectural = UInt(5.W)
  val oldPhysical = UInt(physicalWidth.W)
  val newPhysical = UInt(physicalWidth.W)
}

class IntegerRename(config: ZirconCoreConfig) extends Module {
  private val physicalRegisters = config.intPhysicalRegisters
  private val physicalWidth = log2Ceil(physicalRegisters)
  private val initialFreeMask =
    ((BigInt(1) << physicalRegisters) - 1) & ~((BigInt(1) << 32) - 1)

  val io = IO(new Bundle {
    val request = Input(Vec(2, new RenameRequest))
    val accept = Input(Bool())
    val canAllocate = Output(Bool())
    val response = Output(Vec(2, new RenameResponse(physicalWidth)))

    val commit = Input(Vec(2, new RenameCommit(physicalWidth)))
    val flushToCommitted = Input(Bool())

    val speculativeMap = Output(Vec(32, UInt(physicalWidth.W)))
    val committedMap = Output(Vec(32, UInt(physicalWidth.W)))
    val speculativeFree = Output(UInt(physicalRegisters.W))
    val committedFree = Output(UInt(physicalRegisters.W))
    val freeCount = Output(UInt(log2Ceil(physicalRegisters + 1).W))
  })

  val speculativeMap = RegInit(VecInit.tabulate(32)(index => index.U(physicalWidth.W)))
  val committedMap = RegInit(VecInit.tabulate(32)(index => index.U(physicalWidth.W)))
  val speculativeFree = RegInit(initialFreeMask.U(physicalRegisters.W))
  val committedFree = RegInit(initialFreeMask.U(physicalRegisters.W))

  val allocate = VecInit(io.request.map(request =>
    request.valid && request.writesRd && request.rd =/= 0.U))
  val requiredPhysical = PopCount(allocate)
  io.canAllocate := PopCount(speculativeFree) >= requiredPhysical

  val allocation0OH = PriorityEncoderOH(speculativeFree)
  val freeAfterAllocation0 = speculativeFree &
    ~Mux(allocate(0), allocation0OH, 0.U(physicalRegisters.W))
  val allocation1OH = PriorityEncoderOH(freeAfterAllocation0)
  val newPhysical0 = OHToUInt(allocation0OH)
  val newPhysical1 = OHToUInt(allocation1OH)

  val lane1Source1 = Mux(
    allocate(0) && io.request(1).rs1 === io.request(0).rd,
    newPhysical0,
    speculativeMap(io.request(1).rs1)
  )
  val lane1Source2 = Mux(
    allocate(0) && io.request(1).rs2 === io.request(0).rd,
    newPhysical0,
    speculativeMap(io.request(1).rs2)
  )
  val lane1OldDestination = Mux(
    allocate(0) && io.request(1).rd === io.request(0).rd,
    newPhysical0,
    speculativeMap(io.request(1).rd)
  )

  io.response(0).valid := io.request(0).valid && io.canAllocate
  io.response(0).sourcePhysical1 := Mux(io.request(0).readsRs1,
    speculativeMap(io.request(0).rs1), 0.U)
  io.response(0).sourcePhysical2 := Mux(io.request(0).readsRs2,
    speculativeMap(io.request(0).rs2), 0.U)
  io.response(0).oldDestinationPhysical := speculativeMap(io.request(0).rd)
  io.response(0).newDestinationPhysical := Mux(allocate(0), newPhysical0, 0.U)
  io.response(0).allocates := allocate(0) && io.canAllocate

  io.response(1).valid := io.request(1).valid && io.canAllocate
  io.response(1).sourcePhysical1 := Mux(io.request(1).readsRs1, lane1Source1, 0.U)
  io.response(1).sourcePhysical2 := Mux(io.request(1).readsRs2, lane1Source2, 0.U)
  io.response(1).oldDestinationPhysical := lane1OldDestination
  io.response(1).newDestinationPhysical := Mux(allocate(1), newPhysical1, 0.U)
  io.response(1).allocates := allocate(1) && io.canAllocate

  assert(!io.request(1).valid || io.request(0).valid,
    "rename lane 1 cannot be valid when lane 0 is a bubble")
  assert(!io.commit(1).valid || io.commit(0).valid,
    "commit lane 1 cannot update rename state when lane 0 is a bubble")
  assert(!io.accept || io.canAllocate,
    "rename state cannot advance without enough free physical registers")

  val doRename = io.accept && io.canAllocate
  val allocationMask = Mux(doRename && allocate(0), allocation0OH, 0.U) |
    Mux(doRename && allocate(1), allocation1OH, 0.U)
  val releasedMask = io.commit.map { commit =>
    Mux(commit.valid && commit.oldPhysical =/= 0.U,
      UIntToOH(commit.oldPhysical, physicalRegisters), 0.U(physicalRegisters.W))
  }.reduce(_ | _)
  val speculativeFreeAfterNormalOperation =
    (speculativeFree | releasedMask) & ~allocationMask

  val speculativeMapAfterLane0 = WireDefault(speculativeMap)
  when(doRename && allocate(0)) {
    speculativeMapAfterLane0(io.request(0).rd) := newPhysical0
  }
  val speculativeMapAfterLane1 = WireDefault(speculativeMapAfterLane0)
  when(doRename && allocate(1)) {
    speculativeMapAfterLane1(io.request(1).rd) := newPhysical1
  }

  val committedMapAfterLane0 = WireDefault(committedMap)
  val committedFreeAfterLane0 = WireDefault(committedFree)
  when(io.commit(0).valid) {
    committedMapAfterLane0(io.commit(0).architectural) := io.commit(0).newPhysical
    committedFreeAfterLane0 :=
      (committedFree | UIntToOH(io.commit(0).oldPhysical, physicalRegisters)) &
        ~UIntToOH(io.commit(0).newPhysical, physicalRegisters)
  }

  val committedMapAfterLane1 = WireDefault(committedMapAfterLane0)
  val committedFreeAfterLane1 = WireDefault(committedFreeAfterLane0)
  when(io.commit(1).valid) {
    committedMapAfterLane1(io.commit(1).architectural) := io.commit(1).newPhysical
    committedFreeAfterLane1 :=
      (committedFreeAfterLane0 | UIntToOH(io.commit(1).oldPhysical, physicalRegisters)) &
        ~UIntToOH(io.commit(1).newPhysical, physicalRegisters)
  }

  io.commit.foreach { commit =>
    when(commit.valid) {
      assert(commit.architectural =/= 0.U,
        "a committed rename update cannot target x0")
      assert(commit.oldPhysical < physicalRegisters.U &&
        commit.newPhysical < physicalRegisters.U,
        "committed physical register out of range")
      assert(commit.newPhysical =/= 0.U,
        "a committed integer destination cannot be p0")
    }
  }

  committedMap := committedMapAfterLane1
  committedFree := committedFreeAfterLane1
  when(io.flushToCommitted) {
    speculativeMap := committedMapAfterLane1
    speculativeFree := committedFreeAfterLane1
  }.otherwise {
    speculativeMap := speculativeMapAfterLane1
    speculativeFree := speculativeFreeAfterNormalOperation
  }

  assert(speculativeMap(0) === 0.U && committedMap(0) === 0.U,
    "x0 must remain mapped to p0")
  speculativeMap.foreach(physical =>
    assert(!speculativeFree(physical),
      "speculative map cannot reference a free physical register"))
  committedMap.foreach(physical =>
    assert(!committedFree(physical),
      "committed map cannot reference a free physical register"))
  when(doRename && allocate(0) && allocate(1)) {
    assert(newPhysical0 =/= newPhysical1,
      "dual rename must allocate distinct physical registers")
  }

  io.speculativeMap := speculativeMap
  io.committedMap := committedMap
  io.speculativeFree := speculativeFree
  io.committedFree := committedFree
  io.freeCount := PopCount(speculativeFree)
}
