package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.IntOperation

class CommitSideEffect extends Bundle {
  val csrWrite = Bool()
  val csrAddress = UInt(12.W)
  val csrData = UInt(32.W)
  val serializingReady = Bool()
}

object CommitRedirectReason extends ChiselEnum {
  val Exception, Interrupt, Mret, FenceI = Value
}

class CommitRedirect extends Bundle {
  val target = UInt(32.W)
  val reason = CommitRedirectReason()
}

/** Stateless policy for precise retirement, traps, and serializing operations.
  * All state mutations remain in the ROB, rename map, CSR file, and frontend.
  */
class CommitController(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val rob = Flipped(Vec(config.commitWidth, Decoupled(new ROBCommit(config))))
    val sideEffect = Input(Vec(config.commitWidth, new CommitSideEffect))

    val firstFault = Input(Valid(new FirstFaultRecord(config)))
    val eligibleInterrupt = Input(new EligibleInterrupt)
    val interruptHead = Input(Valid(new ROBCommit(config)))
    val interruptBlocked = Input(Bool())
    val trapVector = Input(UInt(32.W))
    val mretTarget = Input(UInt(32.W))

    val retired = Output(Vec(config.commitWidth, Valid(new ROBCommit(config))))
    val renameCommit = Output(Vec(config.commitWidth, new RenameCommit(physicalWidth)))
    val retiredInstructions = Output(UInt(log2Ceil(config.commitWidth + 1).W))

    val csrWrite = Output(Valid(new CSRCommitWrite))
    val trapCommit = Output(Valid(new TrapCommit))
    val trapEntry = Output(Valid(new ROBCommit(config)))
    val trapLane = Output(UInt(1.W))
    val mretCommit = Output(Bool())
    val firstFaultClear = Output(Bool())
    val flush = Output(Bool())
    val redirect = Output(Valid(new CommitRedirect))
    val fenceICommit = Output(Bool())
    val wfiCommit = Output(Bool())
  })

  require(config.commitWidth == 2, "the frozen commit policy has two lanes")

  val lane0Serialized = io.rob(0).bits.entry.decoded.uopClass === UopClass.Csr ||
    io.rob(0).bits.entry.decoded.uopClass === UopClass.System
  val lane1Serialized = io.rob(1).bits.entry.decoded.uopClass === UopClass.Csr ||
    io.rob(1).bits.entry.decoded.uopClass === UopClass.System

  val lane0Fault = io.firstFault.valid && io.rob(0).valid &&
    io.firstFault.bits.robTag === io.rob(0).bits.robTag
  val lane1Fault = io.firstFault.valid && io.rob(1).valid &&
    io.firstFault.bits.robTag === io.rob(1).bits.robTag
  val interruptAccepted = io.eligibleInterrupt.valid && !io.interruptBlocked &&
    io.interruptHead.valid && !lane0Fault

  val retireLane = WireDefault(VecInit.fill(config.commitWidth)(false.B))
  val exceptionValid = WireDefault(false.B)
  val exceptionLane = WireDefault(0.U(1.W))

  when(lane0Fault) {
    exceptionValid := true.B
    exceptionLane := 0.U
  }.elsewhen(interruptAccepted) {
    // Interrupts occur before the next unretired instruction.
  }.elsewhen(lane1Fault && !lane0Serialized) {
    retireLane(0) := true.B
    exceptionValid := true.B
    exceptionLane := 1.U
  }.otherwise {
    val lane0CanRetire = io.rob(0).valid &&
      (!lane0Serialized || io.sideEffect(0).serializingReady)
    when(lane0CanRetire) {
      retireLane(0) := true.B
      when(io.rob(1).valid && !lane0Serialized && !lane1Serialized) {
        retireLane(1) := true.B
      }
    }
  }

  for (lane <- 0 until config.commitWidth) {
    io.rob(lane).ready := retireLane(lane)
    io.retired(lane).valid := retireLane(lane)
    io.retired(lane).bits := io.rob(lane).bits

    io.renameCommit(lane).valid := retireLane(lane) &&
      io.rob(lane).bits.entry.allocatesPhysical
    io.renameCommit(lane).architectural :=
      io.rob(lane).bits.entry.architecturalDestination
    io.renameCommit(lane).oldPhysical :=
      io.rob(lane).bits.entry.oldPhysicalDestination
    io.renameCommit(lane).newPhysical :=
      io.rob(lane).bits.entry.newPhysicalDestination

    when(io.rob(lane).valid && io.sideEffect(lane).csrWrite) {
      assert(io.rob(lane).bits.entry.decoded.uopClass === UopClass.Csr,
        "a CSR side effect must belong to a CSR uop")
    }
  }
  io.retiredInstructions := PopCount(retireLane)

  val lane0CsrRetires = retireLane(0) &&
    io.rob(0).bits.entry.decoded.uopClass === UopClass.Csr
  io.csrWrite.valid := lane0CsrRetires && io.sideEffect(0).csrWrite
  io.csrWrite.bits.address := io.sideEffect(0).csrAddress
  io.csrWrite.bits.data := io.sideEffect(0).csrData

  val lane0Operation = io.rob(0).bits.entry.decoded.operation
  io.mretCommit := retireLane(0) && lane0Operation === IntOperation.Mret
  io.fenceICommit := retireLane(0) && lane0Operation === IntOperation.FenceI
  io.wfiCommit := retireLane(0) && lane0Operation === IntOperation.Wfi

  io.trapCommit.valid := exceptionValid || interruptAccepted
  io.trapCommit.bits.interrupt := interruptAccepted
  io.trapCommit.bits.cause := Mux(interruptAccepted,
    io.eligibleInterrupt.cause, io.firstFault.bits.cause(30, 0))
  val exceptionPc = Mux(exceptionLane === 0.U,
    io.rob(0).bits.entry.pc, io.rob(1).bits.entry.pc)
  io.trapCommit.bits.exceptionPc := Mux(interruptAccepted,
    io.interruptHead.bits.entry.pc, exceptionPc)
  io.trapCommit.bits.trapValue := Mux(interruptAccepted,
    0.U, io.firstFault.bits.trapValue)

  io.trapEntry.valid := io.trapCommit.valid && Mux(interruptAccepted,
    io.interruptHead.valid, exceptionValid)
  io.trapEntry.bits := Mux(interruptAccepted, io.interruptHead.bits,
    Mux(exceptionLane === 0.U, io.rob(0).bits, io.rob(1).bits))
  io.trapLane := Mux(interruptAccepted || exceptionLane === 0.U, 0.U, 1.U)

  io.firstFaultClear := exceptionValid

  val controlRedirect = io.trapCommit.valid || io.mretCommit || io.fenceICommit
  io.flush := controlRedirect
  io.redirect.valid := controlRedirect
  io.redirect.bits.target := Mux(io.trapCommit.valid, io.trapVector,
    Mux(io.mretCommit, io.mretTarget, io.rob(0).bits.entry.pc + 4.U))
  io.redirect.bits.reason := Mux(io.trapCommit.valid,
    Mux(interruptAccepted, CommitRedirectReason.Interrupt,
      CommitRedirectReason.Exception),
    Mux(io.mretCommit, CommitRedirectReason.Mret,
      CommitRedirectReason.FenceI))

  assert(!io.rob(1).valid || io.rob(0).valid,
    "ROB commit lane 1 cannot be valid when lane 0 is a bubble")
  assert(!retireLane(1) || retireLane(0),
    "commit lane 1 cannot retire without lane 0")
  when(retireLane(0) && lane0Serialized) {
    assert(!retireLane(1), "a lane-0 CSR/system uop must retire alone")
  }
  when(retireLane(1)) {
    assert(!lane1Serialized, "a CSR/system uop cannot retire from lane 1")
  }
  when(lane0Fault) {
    assert(!retireLane(0), "a faulting lane-0 instruction cannot retire")
  }
  when(interruptAccepted) {
    assert(!retireLane.asUInt.orR,
      "an accepted interrupt occurs before all currently unretired instructions")
    assert(io.interruptHead.valid,
      "an interrupt must name a live ROB head for its EPC")
  }
  when(exceptionValid) {
    assert(!io.firstFault.bits.cause(31),
      "FirstFaultRecord only carries synchronous exception causes")
  }
  when(io.trapCommit.valid) {
    assert(!io.trapCommit.bits.exceptionPc(1, 0).orR,
      "a precise trap EPC must satisfy the frozen IALIGN=32 contract")
  }
  when(io.trapEntry.valid && !io.trapCommit.bits.interrupt) {
    assert(io.trapEntry.bits.entry.pc === io.trapCommit.bits.exceptionPc,
      "synchronous trap metadata did not name the faulting ROB entry")
  }
  when(io.mretCommit) {
    assert(!io.mretTarget(1, 0).orR,
      "MRET target must satisfy the frozen IALIGN=32 contract")
  }
  assert(PopCount(Seq(io.csrWrite.valid, io.trapCommit.valid, io.mretCommit)) <= 1.U,
    "CSR write, trap commit, and MRET commit must be mutually exclusive")
  when(io.redirect.valid) {
    assert(io.flush, "every commit-stage redirect must flush speculative state")
  }
}
