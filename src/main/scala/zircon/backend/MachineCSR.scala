package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.core.InterruptInputs
import zircon.frontend.IntOperation

object MachineCSRAddress {
  val Fflags = 0x001
  val Frm = 0x002
  val Fcsr = 0x003

  val Mstatus = 0x300
  val Misa = 0x301
  val Mie = 0x304
  val Mtvec = 0x305
  val Mscratch = 0x340
  val Mepc = 0x341
  val Mcause = 0x342
  val Mtval = 0x343
  val Mip = 0x344

  val Mcycle = 0xb00
  val Minstret = 0xb02
  val Mcycleh = 0xb80
  val Minstreth = 0xb82

  val Mvendorid = 0xf11
  val Marchid = 0xf12
  val Mimpid = 0xf13
  val Mhartid = 0xf14

  val Implemented: Seq[Int] = Seq(
    Fflags, Frm, Fcsr,
    Mstatus, Misa, Mie, Mtvec, Mscratch, Mepc, Mcause, Mtval, Mip,
    Mcycle, Minstret, Mcycleh, Minstreth,
    Mvendorid, Marchid, Mimpid, Mhartid
  )

  val ReadOnly: Seq[Int] = Seq(Mvendorid, Marchid, Mimpid, Mhartid)
  val Floating: Seq[Int] = Seq(Fflags, Frm, Fcsr)
}

object MachineInterruptCause {
  val Software = 3
  val Timer = 7
  val External = 11
}

class CSRAccessRequest extends Bundle {
  val address = UInt(12.W)
  val write = Bool()
}

class CSRCommitWrite extends Bundle {
  val address = UInt(12.W)
  val data = UInt(32.W)
}

class TrapCommit extends Bundle {
  val interrupt = Bool()
  val cause = UInt(31.W)
  val exceptionPc = UInt(32.W)
  val trapValue = UInt(32.W)
}

class FloatingStateCommit extends Bundle {
  val flags = UInt(5.W)
  val dirty = Bool()
}

class EligibleInterrupt extends Bundle {
  val valid = Bool()
  val cause = UInt(31.W)
}

class CSRInstructionRequest extends Bundle {
  val operation = IntOperation()
  val source = UInt(32.W)
  val currentValue = UInt(32.W)
  val accessLegal = Bool()
  val writeIntent = Bool()
}

class CSRInstructionResponse extends Bundle {
  val illegal = Bool()
  val readData = UInt(32.W)
  val writeValid = Bool()
  val writeData = UInt(32.W)
}

/** Combinational Zicsr read/modify/write semantics.
  *
  * `writeIntent` comes from the architectural register specifier, not from the
  * runtime source value: CSRRS/CSRRC with rs1=x0 suppress a write, while a
  * non-x0 register containing zero still performs a write access.
  */
class CSRInstructionUnit extends Module {
  val io = IO(new Bundle {
    val request = Input(new CSRInstructionRequest)
    val response = Output(new CSRInstructionResponse)
  })

  val isCsrOperation = WireDefault(false.B)
  val writeData = WireDefault(io.request.currentValue)

  switch(io.request.operation) {
    is(IntOperation.Csrrw, IntOperation.Csrrwi) {
      isCsrOperation := true.B
      writeData := io.request.source
    }
    is(IntOperation.Csrrs, IntOperation.Csrrsi) {
      isCsrOperation := true.B
      writeData := io.request.currentValue | io.request.source
    }
    is(IntOperation.Csrrc, IntOperation.Csrrci) {
      isCsrOperation := true.B
      writeData := io.request.currentValue & ~io.request.source
    }
  }

  io.response.illegal := !isCsrOperation || !io.request.accessLegal
  io.response.readData := io.request.currentValue
  io.response.writeValid := isCsrOperation && io.request.accessLegal &&
    io.request.writeIntent
  io.response.writeData := writeData
}

/** Architectural M-mode CSR state.
  *
  * Pipeline rollback never reaches this module: all mutating inputs represent
  * commit-point events. The commit controller must serialize CSR writes and
  * system state transitions as specified in docs/architecture/csr-and-traps.md.
  */
class MachineCSRFile(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  val io = IO(new Bundle {
    val access = Input(new CSRAccessRequest)
    val accessData = Output(UInt(32.W))
    val accessLegal = Output(Bool())

    val commitWrite = Input(Valid(new CSRCommitWrite))
    val trapCommit = Input(Valid(new TrapCommit))
    val mretCommit = Input(Bool())
    val retiredInstructions = Input(UInt(log2Ceil(config.commitWidth + 1).W))
    val fpCommit = Input(Valid(new FloatingStateCommit))
    val interrupts = Input(new InterruptInputs)

    val eligibleInterrupt = Output(new EligibleInterrupt)
    val trapTarget = Output(UInt(32.W))
    val mretTarget = Output(UInt(32.W))
    val mstatusMie = Output(Bool())
    val mstatusFs = Output(UInt(2.W))
    val currentFflags = Output(UInt(5.W))
    val currentFrm = Output(UInt(3.W))
  })

  private val misaValue = "h40001121".U(32.W) // RV32 plus A/F/I/M

  val mstatusMie = RegInit(false.B)
  val mstatusMpie = RegInit(false.B)
  val mstatusFs = RegInit(0.U(2.W))

  val mieMeie = RegInit(false.B)
  val mieMsie = RegInit(false.B)
  val mieMtie = RegInit(false.B)

  val mtvecBase = RegInit(0.U(30.W))
  val mtvecMode = RegInit(0.U(1.W))
  val mscratch = RegInit(0.U(32.W))
  val mepc = RegInit(0.U(30.W))
  val mcause = RegInit(0.U(32.W))
  val mtval = RegInit(0.U(32.W))

  val mcycle = RegInit(0.U(64.W))
  val minstret = RegInit(0.U(64.W))

  val fflags = RegInit(0.U(5.W))
  val frm = RegInit(0.U(3.W))

  private def matchesAny(address: UInt, values: Seq[Int]): Bool =
    values.map(value => address === value.U).reduce(_ || _)

  private def implemented(address: UInt): Bool =
    matchesAny(address, MachineCSRAddress.Implemented)

  private def readOnly(address: UInt): Bool =
    matchesAny(address, MachineCSRAddress.ReadOnly)

  private def floating(address: UInt): Bool =
    matchesAny(address, MachineCSRAddress.Floating)

  val mstatusValue = (
    (mstatusMie.asUInt << 3) |
      (mstatusMpie.asUInt << 7) |
      (3.U << 11) | // MPP is WARL to the only supported privilege.
      (mstatusFs << 13) |
      ((mstatusFs === 3.U).asUInt << 31)
  )(31, 0)

  val mieValue = (
    (mieMeie.asUInt << 11) |
      (mieMsie.asUInt << 3) |
      (mieMtie.asUInt << 7)
  ).pad(32)

  val mipValue = (
    (io.interrupts.meip.asUInt << 11) |
      (io.interrupts.msip.asUInt << 3) |
      (io.interrupts.mtip.asUInt << 7)
  ).pad(32)

  val accessData = WireDefault(0.U(32.W))
  switch(io.access.address) {
    is(MachineCSRAddress.Fflags.U) { accessData := fflags }
    is(MachineCSRAddress.Frm.U) { accessData := frm }
    is(MachineCSRAddress.Fcsr.U) { accessData := Cat(frm, fflags) }
    is(MachineCSRAddress.Mstatus.U) { accessData := mstatusValue }
    is(MachineCSRAddress.Misa.U) { accessData := misaValue }
    is(MachineCSRAddress.Mie.U) { accessData := mieValue }
    is(MachineCSRAddress.Mtvec.U) {
      accessData := Cat(mtvecBase, 0.U(1.W), mtvecMode)
    }
    is(MachineCSRAddress.Mscratch.U) { accessData := mscratch }
    is(MachineCSRAddress.Mepc.U) { accessData := Cat(mepc, 0.U(2.W)) }
    is(MachineCSRAddress.Mcause.U) { accessData := mcause }
    is(MachineCSRAddress.Mtval.U) { accessData := mtval }
    is(MachineCSRAddress.Mip.U) { accessData := mipValue }
    is(MachineCSRAddress.Mcycle.U) { accessData := mcycle(31, 0) }
    is(MachineCSRAddress.Mcycleh.U) { accessData := mcycle(63, 32) }
    is(MachineCSRAddress.Minstret.U) { accessData := minstret(31, 0) }
    is(MachineCSRAddress.Minstreth.U) { accessData := minstret(63, 32) }
    is(MachineCSRAddress.Mvendorid.U) { accessData := 0.U }
    is(MachineCSRAddress.Marchid.U) { accessData := 0.U }
    is(MachineCSRAddress.Mimpid.U) { accessData := 0.U }
    is(MachineCSRAddress.Mhartid.U) { accessData := config.hartId.U(32.W) }
  }

  val accessImplemented = implemented(io.access.address)
  val accessWritable = !readOnly(io.access.address)
  val floatingEnabled = mstatusFs =/= 0.U
  io.accessData := accessData
  io.accessLegal := accessImplemented &&
    (!io.access.write || accessWritable) &&
    (!floating(io.access.address) || floatingEnabled)

  val commitWriteLegal = implemented(io.commitWrite.bits.address) &&
    !readOnly(io.commitWrite.bits.address) &&
    (!floating(io.commitWrite.bits.address) || floatingEnabled)

  val externalEligible = mstatusMie && mieMeie && io.interrupts.meip
  val softwareEligible = mstatusMie && mieMsie && io.interrupts.msip
  val timerEligible = mstatusMie && mieMtie && io.interrupts.mtip
  io.eligibleInterrupt.valid := externalEligible || softwareEligible || timerEligible
  io.eligibleInterrupt.cause := Mux(externalEligible, MachineInterruptCause.External.U,
    Mux(softwareEligible, MachineInterruptCause.Software.U,
      MachineInterruptCause.Timer.U))

  val trapBase = Cat(mtvecBase, 0.U(2.W))
  val vectoredOffset = (io.trapCommit.bits.cause << 2)(31, 0)
  io.trapTarget := Mux(io.trapCommit.bits.interrupt && mtvecMode.asBool,
    trapBase + vectoredOffset, trapBase)
  io.mretTarget := Cat(mepc, 0.U(2.W))
  io.mstatusMie := mstatusMie
  io.mstatusFs := mstatusFs
  io.currentFflags := fflags
  io.currentFrm := frm

  assert(PopCount(Seq(io.commitWrite.valid, io.trapCommit.valid, io.mretCommit)) <= 1.U,
    "CSR write, trap, and MRET commit events must be mutually exclusive")
  assert(!(io.commitWrite.valid && io.fpCommit.valid),
    "a serialized CSR write cannot share a commit cycle with an FP state update")
  when(io.commitWrite.valid) {
    assert(commitWriteLegal, "an illegal CSR write reached architectural commit")
  }
  assert(io.retiredInstructions <= config.commitWidth.U,
    "retired-instruction increment exceeds commit width")

  mcycle := mcycle + 1.U
  minstret := minstret + io.retiredInstructions

  when(io.fpCommit.valid) {
    fflags := fflags | io.fpCommit.bits.flags
    when(io.fpCommit.bits.dirty || io.fpCommit.bits.flags.orR) {
      mstatusFs := 3.U
    }
  }

  when(io.commitWrite.valid) {
    switch(io.commitWrite.bits.address) {
      is(MachineCSRAddress.Fflags.U) {
        fflags := io.commitWrite.bits.data(4, 0)
        mstatusFs := 3.U
      }
      is(MachineCSRAddress.Frm.U) {
        frm := io.commitWrite.bits.data(2, 0)
        mstatusFs := 3.U
      }
      is(MachineCSRAddress.Fcsr.U) {
        frm := io.commitWrite.bits.data(7, 5)
        fflags := io.commitWrite.bits.data(4, 0)
        mstatusFs := 3.U
      }
      is(MachineCSRAddress.Mstatus.U) {
        mstatusMie := io.commitWrite.bits.data(3)
        mstatusMpie := io.commitWrite.bits.data(7)
        mstatusFs := io.commitWrite.bits.data(14, 13)
      }
      is(MachineCSRAddress.Mie.U) {
        mieMeie := io.commitWrite.bits.data(11)
        mieMsie := io.commitWrite.bits.data(3)
        mieMtie := io.commitWrite.bits.data(7)
      }
      is(MachineCSRAddress.Mtvec.U) {
        mtvecBase := io.commitWrite.bits.data(31, 2)
        mtvecMode := io.commitWrite.bits.data(1, 0) === 1.U
      }
      is(MachineCSRAddress.Mscratch.U) { mscratch := io.commitWrite.bits.data }
      is(MachineCSRAddress.Mepc.U) { mepc := io.commitWrite.bits.data(31, 2) }
      is(MachineCSRAddress.Mcause.U) { mcause := io.commitWrite.bits.data }
      is(MachineCSRAddress.Mtval.U) { mtval := io.commitWrite.bits.data }
      is(MachineCSRAddress.Mcycle.U) {
        mcycle := Cat(mcycle(63, 32), io.commitWrite.bits.data)
      }
      is(MachineCSRAddress.Mcycleh.U) {
        mcycle := Cat(io.commitWrite.bits.data, mcycle(31, 0))
      }
      is(MachineCSRAddress.Minstret.U) {
        minstret := Cat(minstret(63, 32), io.commitWrite.bits.data)
      }
      is(MachineCSRAddress.Minstreth.U) {
        minstret := Cat(io.commitWrite.bits.data, minstret(31, 0))
      }
    }
  }

  when(io.trapCommit.valid) {
    mepc := io.trapCommit.bits.exceptionPc(31, 2)
    mcause := Cat(io.trapCommit.bits.interrupt, io.trapCommit.bits.cause)
    mtval := io.trapCommit.bits.trapValue
    mstatusMpie := mstatusMie
    mstatusMie := false.B
  }.elsewhen(io.mretCommit) {
    mstatusMie := mstatusMpie
    mstatusMpie := true.B
  }
}
