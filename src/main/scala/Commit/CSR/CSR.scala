import chisel3._
import chisel3.util._
import ZirconConfig.{CSRAddress, SystemOp}

class CSRRequest extends Bundle {
    val addr = UInt(12.W)
    // SystemOp encoding, not the instruction's funct3.
    val op = UInt(5.W)
    // Original architectural rs1 index or zimm; zero detection must precede renaming.
    val source = UInt(5.W)
    val data = UInt(32.W)
    val rdZero = Bool()
}

class CSRResponse extends Bundle {
    val data = UInt(32.W)
    val illegal = Bool()
    val read = Bool()
    val write = Bool()
}

/** Already selected, architecturally accepted trap; selection and redirects belong to Commit. */
class CSRTrap extends Bundle {
    val supervisor = Bool()
    val pc = UInt(32.W)
    val cause = UInt(32.W)
    val tval = UInt(32.W)
}

class CSRState extends Bundle {
    val mstatus = UInt(32.W)
    val mie = UInt(32.W)
    val mip = UInt(32.W)
    val medeleg = UInt(32.W)
    val mideleg = UInt(32.W)
    val mtvec = UInt(32.W)
    val mepc = UInt(32.W)
    val mcause = UInt(32.W)
    val mtval = UInt(32.W)
    val stvec = UInt(32.W)
    val sepc = UInt(32.W)
    val scause = UInt(32.W)
    val stval = UInt(32.W)
    val satp = UInt(32.W)
    val frm = UInt(3.W)
    val fflags = UInt(5.W)
}

class CSRIO extends Bundle {
    val req = Flipped(Valid(new CSRRequest))
    val rsp = Valid(new CSRResponse)
    // One ordered access completes at this edge; the caller samples the pre-edge response.
    val commit = Input(Bool())
    val privilege = Input(UInt(2.W))
    val retired = Input(UInt(2.W))
    val time = Input(UInt(64.W))
    val interrupt = Input(new Bundle {
        val software = Bool()
        val timer = Bool()
        val external = Bool()
        val supervisorExternal = Bool()
    })
    val fp = Flipped(Valid(new Bundle {
        val flags = UInt(5.W)
        val dirty = Bool()
    }))
    val trap = Flipped(Valid(new CSRTrap))
    // False is an accepted MRET, true is an accepted SRET. No instruction decoding occurs here.
    val xret = Flipped(Valid(Bool()))
    val state = Output(new CSRState)
}

/** RV32 CSR state and one ordered access port; no instruction pipeline, interrupt arbiter or flush. */
class CSR extends Module {
    val io = IO(new CSRIO)
    val status = RegInit(0.U(32.W))
    val nextStatus = WireDefault(status)
    val mie = RegInit(0.U(32.W))
    val pending = RegInit(0.U(32.W))
    val medeleg = RegInit(0.U(32.W))
    val mideleg = RegInit(0.U(32.W))
    val mtvec = RegInit(0.U(32.W))
    val mscratch = RegInit(0.U(32.W))
    val mepc = RegInit(0.U(32.W))
    val mcause = RegInit(0.U(32.W))
    val mtval = RegInit(0.U(32.W))
    val stvec = RegInit(0.U(32.W))
    val sscratch = RegInit(0.U(32.W))
    val sepc = RegInit(0.U(32.W))
    val scause = RegInit(0.U(32.W))
    val stval = RegInit(0.U(32.W))
    val satp = RegInit(0.U(32.W))
    val mcounteren = RegInit(0.U(3.W))
    val scounteren = RegInit(0.U(3.W))
    val inhibit = RegInit(0.U(3.W))
    val cycleLow = RegInit(0.U(32.W))
    val cycleHigh = RegInit(0.U(32.W))
    val instretLow = RegInit(0.U(32.W))
    val instretHigh = RegInit(0.U(32.W))
    val flags = RegInit(0.U(5.W))
    val rounding = RegInit(0.U(3.W))
    val cycleLowIncrement = BLevelPAdder32(cycleLow, 1.U, 0.U)
    val cycleHighIncrement = BLevelPAdder32(cycleHigh, 1.U, 0.U)
    val instretLowIncrement = BLevelPAdder32(instretLow, io.retired, 0.U)
    val instretHighIncrement = BLevelPAdder32(instretHigh, 1.U, 0.U)
    val cycleWrite = WireDefault(false.B)
    val instretWrite = WireDefault(false.B)

    def bits(indices: Int*): BigInt = indices.foldLeft(BigInt(0))((mask, bit) => mask.setBit(bit))
    val statusMask = bits(1, 3, 5, 7, 8, 11, 12, 13, 14, 17, 18, 19, 20, 21, 22)
    val sstatusMask = bits(1, 5, 8, 13, 14, 18, 19)
    val irqMask = bits(1, 3, 5, 7, 9, 11)
    val statusRead = status | Mux(status(14, 13).andR, (BigInt(1) << 31).U(32.W), 0.U)
    val mip = pending | (io.interrupt.software.asUInt << 3) | (io.interrupt.timer.asUInt << 7) |
        (io.interrupt.external.asUInt << 11) | (io.interrupt.supervisorExternal.asUInt << 9)

    /* Access intent depends on instruction fields, never on the value read from the PRF. */
    val rw = io.req.bits.op === SystemOp.CSRRW.U || io.req.bits.op === SystemOp.CSRRWI.U
    val rs = io.req.bits.op === SystemOp.CSRRS.U || io.req.bits.op === SystemOp.CSRRSI.U
    val rc = io.req.bits.op === SystemOp.CSRRC.U || io.req.bits.op === SystemOp.CSRRCI.U
    val immediate = io.req.bits.op === SystemOp.CSRRWI.U || io.req.bits.op === SystemOp.CSRRSI.U ||
        io.req.bits.op === SystemOp.CSRRCI.U
    val read = !rw || !io.req.bits.rdZero
    val write = rw || io.req.bits.source.orR
    val operand = Mux(immediate, io.req.bits.source.pad(32), Mux(io.req.bits.source.orR, io.req.bits.data, 0.U))
    val selected = collection.mutable.ArrayBuffer.empty[(Bool, UInt, UInt)]
    val writeData = Wire(UInt(32.W))
    val writeFire = Wire(Bool())

    def csr(address: Int, value: UInt, modify: Option[UInt] = None)(update: UInt => Unit): Unit = {
        val hit = io.req.bits.addr === address.U
        selected += ((hit, value.pad(32), modify.getOrElse(value).pad(32)))
        when(writeFire && hit) { update(writeData) }
    }
    def constant(address: Int, value: BigInt): Unit = csr(address, value.U(32.W))(_ => ())
    def constantRange(first: Int, last: Int, value: BigInt): Unit = {
        val hit = io.req.bits.addr >= first.U && io.req.bits.addr <= last.U
        selected += ((hit, value.U(32.W), value.U(32.W)))
    }
    def vector(w: UInt): UInt = Cat(w(31, 2), 0.U(1.W), w(1, 0) === 1.U)

    /* Counters use the old inhibit state. An explicit write overrides this cycle's increment. */
    when(!inhibit(0) && !cycleWrite) {
        cycleLow := cycleLowIncrement.io.res
        when(cycleLowIncrement.io.cout.asBool) { cycleHigh := cycleHighIncrement.io.res }
    }
    when(!inhibit(2) && !instretWrite) {
        instretLow := instretLowIncrement.io.res
        when(instretLowIncrement.io.cout.asBool) { instretHigh := instretHighIncrement.io.res }
    }

    csr(CSRAddress.mstatus, statusRead, Some(status)) { w =>
        val mpp = Mux(w(12, 11) === 2.U, 0.U, w(12, 11))
        nextStatus := (w & (statusMask & ~bits(11, 12)).U(32.W)) | (mpp << 11)
    }
    constant(CSRAddress.mstatush, 0)
    constant(
        CSRAddress.misa,
        bits(30, 20, 18, 12, 8, 5, 0)
    )
    csr(CSRAddress.mie, mie)(w => mie := w & irqMask.U(32.W))
    // The external SEIP level is returned to rd but must never latch into the software pending bit.
    csr(CSRAddress.mip, mip, Some(pending))(w => pending := w & (irqMask & bits(1, 5, 9)).U(32.W))
    csr(CSRAddress.mtvec, mtvec)(w => mtvec := vector(w))
    csr(CSRAddress.mscratch, mscratch)(w => mscratch := w)
    csr(CSRAddress.mepc, mepc)(w => mepc := w & "hfffffffc".U)
    csr(CSRAddress.mcause, mcause)(w => mcause := w & "h8000001f".U)
    csr(CSRAddress.mtval, mtval)(w => mtval := w)
    csr(CSRAddress.mcounteren, mcounteren)(w => mcounteren := w(2, 0))
    csr(CSRAddress.mcountinhibit, inhibit)(w => inhibit := w(2, 0) & 5.U)
    constantRange(CSRAddress.mhpmevent3, CSRAddress.mhpmevent31, 0)
    csr(CSRAddress.mcycle, cycleLow) { w =>
        cycleWrite := true.B
        cycleLow := w
    }
    csr(CSRAddress.mcycleh, cycleHigh) { w =>
        cycleWrite := true.B
        cycleHigh := w
    }
    csr(CSRAddress.minstret, instretLow) { w =>
        instretWrite := true.B
        instretLow := w
    }
    csr(CSRAddress.minstreth, instretHigh) { w =>
        instretWrite := true.B
        instretHigh := w
    }
    constantRange(CSRAddress.mhpmcounter3, CSRAddress.mhpmcounter31, 0)
    constantRange(CSRAddress.mhpmcounter3h, CSRAddress.mhpmcounter31h, 0)
    csr(CSRAddress.cycle, cycleLow)(_ => ())
    csr(CSRAddress.cycleh, cycleHigh)(_ => ())
    csr(CSRAddress.instret, instretLow)(_ => ())
    csr(CSRAddress.instreth, instretHigh)(_ => ())
    csr(CSRAddress.time, io.time(31, 0))(_ => ())
    csr(CSRAddress.timeh, io.time(63, 32))(_ => ())
    constantRange(CSRAddress.hpmcounter3, CSRAddress.hpmcounter31, 0)
    constantRange(CSRAddress.hpmcounter3h, CSRAddress.hpmcounter31h, 0)
    constant(CSRAddress.mvendorid, 0)
    constant(CSRAddress.marchid, 0)
    constant(CSRAddress.mimpid, 0)
    constant(CSRAddress.mhartid, 0)
    constant(CSRAddress.mconfigptr, 0)

    csr(CSRAddress.medeleg, medeleg)(w => medeleg := w & "h0000b3ff".U)
    csr(CSRAddress.mideleg, mideleg)(w => mideleg := w & "h00000222".U)
    csr(CSRAddress.sstatus, statusRead & (sstatusMask | bits(31)).U(32.W), Some(status & sstatusMask.U(32.W))) {
        w =>
            nextStatus := (status & (~sstatusMask & BigInt("ffffffff", 16)).U) | (w & sstatusMask.U(32.W))
    }
    csr(CSRAddress.sie, mie & mideleg) { w => mie := (mie & ~mideleg) | (w & mideleg) }
    csr(CSRAddress.sip, mip & mideleg, Some(pending & mideleg)) { w =>
        val mask = mideleg & 2.U(32.W)
        pending := (pending & ~mask) | (w & mask)
    }
    csr(CSRAddress.stvec, stvec)(w => stvec := vector(w))
    csr(CSRAddress.sscratch, sscratch)(w => sscratch := w)
    csr(CSRAddress.sepc, sepc)(w => sepc := w & "hfffffffc".U)
    csr(CSRAddress.scause, scause)(w => scause := w & "h8000001f".U)
    csr(CSRAddress.stval, stval)(w => stval := w)
    csr(CSRAddress.scounteren, scounteren)(w => scounteren := w(2, 0))
    val fpEnabled = status(14, 13) =/= 0.U
    val satpWritable = !(io.privilege === 1.U && status(20))
    csr(CSRAddress.satp, satp) { w =>
        when(satpWritable) { satp := w }
    }

    csr(CSRAddress.fflags, flags) { w =>
        when(fpEnabled) { flags := w(4, 0) }
    }
    csr(CSRAddress.frm, rounding) { w =>
        when(fpEnabled) { rounding := w(2, 0) }
    }
    csr(CSRAddress.fcsr, Cat(rounding, flags)) { w =>
        when(fpEnabled) {
            flags := w(4, 0)
            rounding := w(7, 5)
        }
    }
    when(io.fp.valid) { flags := flags | io.fp.bits.flags }

    /* Decode, permissions and the shared CSR read-modify-write datapath. */
    val entries = selected.toSeq
    val fpAddress = io.req.bits.addr === CSRAddress.fflags.U || io.req.bits.addr === CSRAddress.frm.U ||
        io.req.bits.addr === CSRAddress.fcsr.U
    val counter = io.req.bits.addr === CSRAddress.cycle.U || io.req.bits.addr === CSRAddress.cycleh.U ||
        io.req.bits.addr === CSRAddress.time.U || io.req.bits.addr === CSRAddress.timeh.U ||
        io.req.bits.addr === CSRAddress.instret.U || io.req.bits.addr === CSRAddress.instreth.U
    val counterMask = UIntToOH(io.req.bits.addr(1, 0), 3)
    val deniedCounter = counter && io.privilege =/= 3.U &&
        (!(mcounteren & counterMask).orR || (io.privilege === 0.U && !(scounteren & counterMask).orR))
    val implemented = VecInit(entries.map(_._1)).asUInt.orR
    val basePermitted = (rw || rs || rc) && io.privilege =/= 2.U &&
        io.privilege >= io.req.bits.addr(9, 8) &&
        !(write && io.req.bits.addr(11, 10).andR)
    val permitted = basePermitted && !deniedCounter && !(fpAddress && !fpEnabled) &&
        !(io.req.bits.addr === CSRAddress.satp.U && !satpWritable)
    val illegal = !implemented || !permitted
    writeData := Mux(
        rw,
        operand,
        Mux1H(entries.map(x => x._1 -> x._3)) &
            Mux(rc, ~operand, "hffffffff".U)
    ) | Mux(rs, operand, 0.U)
    // A per-register hit already proves that the address is implemented. Keeping
    // the global address-table reduction out of state write enables shortens every
    // CSR input-to-register path without changing the illegal response.
    // Address-specific FP, SATP and counter permissions affect their own state
    // only. Other CSR write enables avoid those unrelated comparisons.
    writeFire := io.req.valid && io.commit && basePermitted && write && !io.trap.valid && !io.xret.valid
    io.rsp.valid := io.req.valid
    io.rsp.bits.illegal := io.req.valid && illegal
    io.rsp.bits.read := io.req.valid && !illegal && read
    io.rsp.bits.write := io.req.valid && !illegal && write
    io.rsp.bits.data := Mux(io.rsp.bits.read, Mux1H(entries.map(x => x._1 -> x._2)), 0.U)

    /* Accepted hardware events update state, but never select traps or change the current privilege. */
    when(io.trap.valid) {
        when(io.trap.bits.supervisor) {
            sepc := io.trap.bits.pc & "hfffffffc".U
            scause := io.trap.bits.cause & "h8000001f".U
            stval := io.trap.bits.tval
            nextStatus := (status & (~bits(1, 5, 8) & BigInt("ffffffff", 16)).U) |
                (status(1).asUInt << 5) | ((io.privilege === 1.U).asUInt << 8)
        }.otherwise {
            mepc := io.trap.bits.pc & "hfffffffc".U
            mcause := io.trap.bits.cause & "h8000001f".U
            mtval := io.trap.bits.tval
            nextStatus := (status & (~bits(3, 7, 11, 12) & BigInt("ffffffff", 16)).U) |
                (status(3).asUInt << 7) | (io.privilege << 11)
        }
    }
    when(io.xret.valid) {
        when(io.xret.bits) {
            nextStatus := (status & (~bits(1, 5, 8, 17) & BigInt("ffffffff", 16)).U) |
                (status(5).asUInt << 1) | (1.U << 5)
        }.otherwise {
            nextStatus :=
                (status & (~bits(3, 7, 11, 12) & BigInt("ffffffff", 16)).U &
                    Mux(status(12, 11) === 3.U, "hffffffff".U, "hfffdffff".U)) |
                    (status(7).asUInt << 3) | (1.U << 7)
        }
    }
    status := Mux(
        (writeFire && fpAddress && fpEnabled) || (io.fp.valid && (io.fp.bits.dirty || io.fp.bits.flags.orR)),
        (nextStatus & "hffff9fff".U) | "h00006000".U,
        nextStatus
    )

    io.state.mstatus := statusRead
    io.state.mie := mie
    io.state.mip := mip
    io.state.medeleg := medeleg
    io.state.mideleg := mideleg
    io.state.mtvec := mtvec
    io.state.mepc := mepc
    io.state.mcause := mcause
    io.state.mtval := mtval
    io.state.stvec := stvec
    io.state.sepc := sepc
    io.state.scause := scause
    io.state.stval := stval
    io.state.satp := satp
    io.state.frm := rounding
    io.state.fflags := flags

    assert(io.retired <= 3.U)
    assert(!io.commit || io.req.valid, "CSR commit requires an access request")
    assert(PopCount(Seq(io.commit, io.trap.valid, io.xret.valid)) <= 1.U, "Architectural events must be serialized")
    when(io.commit) {
        assert(!io.fp.valid, "Older FP retirement must finish before the CSR access commits")
        assert(io.retired === Mux(illegal, 0.U, 1.U), "CSR retirement uses its own commit cycle")
    }
    when(io.fp.valid) { assert(status(14, 13) =/= 0.U) }
    when(io.trap.valid) {
        assert(io.privilege === 0.U || io.privilege === 1.U || io.privilege === 3.U)
        when(io.trap.bits.supervisor) { assert(io.privilege <= 1.U) }
    }
    when(io.xret.valid) {
        when(io.xret.bits) {
            assert(io.privilege === 3.U || (io.privilege === 1.U && !status(22)))
        }.otherwise { assert(io.privilege === 3.U) }
    }
}
