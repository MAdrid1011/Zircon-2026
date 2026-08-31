package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.IntOperation

class E0ResultSlotEntry(config: ZirconCoreConfig) extends Bundle {
  val completion = new CompletionResult(config)
  val needsBranchResolution = Bool()
  val branchResolution = new BranchResolutionRequest(config)
}

/** One-entry E0 slot that resolves a branch before exposing its completion. */
class E0ResultSlot(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val enqueue = Flipped(Decoupled(new E0ResultSlotEntry(config)))
    val completion = Decoupled(new CompletionResult(config))
    val branchResolve = Decoupled(new BranchResolutionRequest(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val recoveryActive = Input(Bool())
    val flush = Input(Bool())
    val occupied = Output(Bool())
  })

  val valid = RegInit(false.B)
  val entry = Reg(new E0ResultSlotEntry(config))
  val resolutionSent = RegInit(false.B)

  io.branchResolve.valid := valid && entry.needsBranchResolution &&
    !resolutionSent && !io.recoveryActive && !io.flush
  io.branchResolve.bits := entry.branchResolution
  val branchResolveFire = io.branchResolve.fire

  val recoveryBlocked = io.flush || io.recoveryActive || io.squash.valid
  io.completion.valid := valid &&
    (!entry.needsBranchResolution || resolutionSent) && !recoveryBlocked
  io.completion.bits := entry.completion
  val completionFire = io.completion.fire

  io.enqueue.ready := !recoveryBlocked && (!valid || completionFire)
  val enqueueFire = io.enqueue.fire

  val slotYounger = ROBTagOrder.isYounger(entry.completion.robTag,
    io.squash.bits, io.robHeadTag, config)

  when(io.flush) {
    valid := false.B
    resolutionSent := false.B
  }.elsewhen(io.squash.valid) {
    when(valid && slotYounger) {
      valid := false.B
      resolutionSent := false.B
    }.elsewhen(branchResolveFire) {
      resolutionSent := true.B
    }
  }.otherwise {
    when(completionFire && !enqueueFire) {
      valid := false.B
      resolutionSent := false.B
    }
    when(branchResolveFire) {
      resolutionSent := true.B
    }
    when(enqueueFire) {
      valid := true.B
      entry := io.enqueue.bits
      resolutionSent := false.B
    }
  }

  when(valid && entry.needsBranchResolution) {
    assert(entry.branchResolution.reference.robTag === entry.completion.robTag,
      "E0 branch resolution and completion tags diverged")
  }
  when(io.recoveryActive) {
    assert(!io.completion.fire && !io.enqueue.fire,
      "E0 transferred data during active recovery")
  }
  io.occupied := valid
}

/** RV32I integer/branch E0 and simple-integer E1 with one local result slot each. */
class IntegerShortPipes(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val e0 = Flipped(Decoupled(new IntegerPipeRequest(config)))
    val e1 = Flipped(Decoupled(new IntegerPipeRequest(config)))
    val e0Completion = Decoupled(new CompletionResult(config))
    val e1Completion = Decoupled(new CompletionResult(config))
    val branchResolve = Decoupled(new BranchResolutionRequest(config))
    val e0Fault = Output(new FaultCandidate(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val recoveryActive = Input(Bool())
    val flush = Input(Bool())
    val e0Occupied = Output(Bool())
    val e1Count = Output(UInt(1.W))
  })

  private def driveExecute(
      execute: IntegerExecute,
      request: IntegerPipeRequest
  ): Bool = {
    val (operation, operationValid) =
      IntOperation.safe(request.uop.operation(5, 0))
    execute.io.request.operation := operation
    execute.io.request.lhs := request.lhs
    execute.io.request.rhs := request.rhs
    execute.io.request.pc := request.context.pc
    execute.io.request.immediate := request.uop.immediate
    operationValid
  }

  val e0Execute = Module(new IntegerExecute)
  val e1Execute = Module(new IntegerExecute)
  val e0OperationValid = driveExecute(e0Execute, io.e0.bits)
  val e1OperationValid = driveExecute(e1Execute, io.e1.bits)

  val e0Slot = Module(new E0ResultSlot(config))
  e0Slot.io.robHeadTag := io.robHeadTag
  e0Slot.io.squash := io.squash
  e0Slot.io.recoveryActive := io.recoveryActive
  e0Slot.io.flush := io.flush
  io.e0Occupied := e0Slot.io.occupied

  val e0IsBranch = io.e0.bits.uop.uopClass === UopClass.Branch
  val e0Misaligned = e0IsBranch &&
    e0Execute.io.response.instructionAddressMisaligned
  e0Slot.io.enqueue.valid := io.e0.valid
  e0Slot.io.enqueue.bits.completion.robTag := io.e0.bits.uop.robTag
  e0Slot.io.enqueue.bits.completion.writesInteger :=
    io.e0.bits.uop.writesInteger
  e0Slot.io.enqueue.bits.completion.destinationPhysical :=
    io.e0.bits.uop.destinationPhysical
  e0Slot.io.enqueue.bits.completion.data := e0Execute.io.response.result
  e0Slot.io.enqueue.bits.needsBranchResolution := e0IsBranch && !e0Misaligned
  e0Slot.io.enqueue.bits.branchResolution.reference.index :=
    io.e0.bits.context.branchDataIndex
  e0Slot.io.enqueue.bits.branchResolution.reference.robTag :=
    io.e0.bits.uop.robTag
  e0Slot.io.enqueue.bits.branchResolution.actualTaken :=
    e0Execute.io.response.controlTaken
  e0Slot.io.enqueue.bits.branchResolution.actualTarget :=
    e0Execute.io.response.controlTarget
  io.e0.ready := e0Slot.io.enqueue.ready
  io.e0Completion <> e0Slot.io.completion
  io.branchResolve <> e0Slot.io.branchResolve

  io.e0Fault.valid := io.e0.fire && e0Misaligned
  io.e0Fault.record.robTag := io.e0.bits.uop.robTag
  io.e0Fault.record.cause := 0.U
  io.e0Fault.record.trapValue := e0Execute.io.response.controlTarget

  val e1Buffer = Module(new CompletionBuffer(config, depth = 1))
  e1Buffer.io.robHeadTag := io.robHeadTag
  e1Buffer.io.squash := io.squash
  e1Buffer.io.flush := io.flush
  e1Buffer.io.enqueue.valid := io.e1.valid
  e1Buffer.io.enqueue.bits.robTag := io.e1.bits.uop.robTag
  e1Buffer.io.enqueue.bits.writesInteger := io.e1.bits.uop.writesInteger
  e1Buffer.io.enqueue.bits.destinationPhysical :=
    io.e1.bits.uop.destinationPhysical
  e1Buffer.io.enqueue.bits.data := e1Execute.io.response.result
  io.e1.ready := e1Buffer.io.enqueue.ready
  io.e1Completion <> e1Buffer.io.dequeue
  io.e1Count := e1Buffer.io.count

  when(io.e0.valid && !io.flush) {
    assert(!io.e0.bits.uop.operation(6) && e0OperationValid,
      "E0 received an operation outside the RV32I operation namespace")
    assert(io.e0.bits.uop.allowedEndpoints(0),
      "E0 received an ineligible integer request")
    assert(io.e0.bits.uop.uopClass === UopClass.Integer || e0IsBranch,
      "current E0 short pipe only accepts integer and branch uops")
    assert(e0Execute.io.response.controlValid === e0IsBranch,
      "E0 uop class and RV32I operation control semantics diverged")
    when(e0IsBranch) {
      assert(io.e0.bits.context.hasBranchData,
        "E0 branch request did not carry a BDB reference")
    }
  }
  when(io.e1.valid && !io.flush) {
    assert(!io.e1.bits.uop.operation(6) && e1OperationValid,
      "E1 received an operation outside the RV32I operation namespace")
    assert(io.e1.bits.uop.allowedEndpoints(1),
      "E1 received an ineligible integer request")
    assert(io.e1.bits.uop.uopClass === UopClass.Integer,
      "E1 accepted a non-integer uop")
    assert(!e1Execute.io.response.controlValid,
      "E1 accepted an RV32I control operation")
  }
}
