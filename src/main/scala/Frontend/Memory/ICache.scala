// Adapted from Zircon-2024 b9f7b2b for the current frontend and memory hierarchy.
import chisel3._
import chisel3.util._
import ZirconConfig.{FrontendParams, ICacheParams}

class IStage1Signal extends Bundle {
    val rreq = Bool()
    val vaddr = UInt(32.W)
    val token = UInt(32.W)
}

class IStage2Signal(p: FrontendParams, c: ICacheParams) extends IStage1Signal {
    val rdata = Vec(c.ways, UInt((32 * p.fetchWidth).W))
    val victimWay = UInt(c.ways.W)
    val victimData = UInt(c.lineBits.W)
    val victimTag = UInt(c.tagBits.W)
    val victimValid = Bool()
    val hit = UInt(c.ways.W)
    val paddr = UInt(34.W)
    val uncache = Bool()
    val fault = Bool()
}

class ICache(p: FrontendParams = FrontendParams(), c: ICacheParams = ICacheParams()) extends Module {
    val io = IO(new ICacheIO(p, c))
    require(p.fetchWidth * 4 <= c.lineBytes)

    /* Memory Arrays */
    // Preserve per-way synchronous Tag/Data and small asynchronous Valid/LRU tables.
    val tagTab = VecInit.fill(c.ways)(Module(new SinglePortMaskedRam(c.sets, 1, c.tagBits)).io)
    val vldTab = VecInit.fill(c.ways)(Module(new AsyncRegRam(Bool(), c.sets, 1, 1, Some(false.B))).io)
    val dataTab = VecInit.fill(c.ways)(Module(new SinglePortMaskedRam(c.sets, 1, c.lineBits)).io)
    val lruTab = Module(new AsyncRegRam(UInt(2.W), c.sets, 1, 1, Some(1.U(2.W)))).io

    def index(addr: UInt): UInt = addr(c.indexBits + c.offsetBits - 1, c.offsetBits)
    def tag(addr: UInt): UInt = addr(33, c.indexBits + c.offsetBits)
    def fetchOffset(addr: UInt): UInt = addr(c.offsetBits - 1, 0) &
        (~(p.fetchWidth * 4 - 1).U(c.offsetBits.W)).asUInt
    def fragment(line: UInt, addr: UInt): UInt = (line >> (fetchOffset(addr) << 3))(32 * p.fetchWidth - 1, 0)

    /* Control and Return Buffers */
    val fsm = Module(new ICacheFSM)
    val itlb = Module(new InstructionTLB)
    val missC1 = RegInit(false.B)
    val rbuf = Reg(UInt(c.lineBits.W))
    val dbuf = Reg(Vec(p.fetchWidth, UInt(32.W)))
    val dbufFault = Reg(UInt(p.fetchWidth.W))
    val readSlot = Reg(UInt(p.slotBits.W))
    io.miss := missC1
    val invalidateActive = RegInit(false.B)
    val invalidateDone = RegInit(false.B)
    val invalidateSet = RegInit(0.U(c.indexBits.W))
    val invalidateStart = io.maintenance.request && !invalidateActive && !invalidateDone &&
        fsm.io.cc.ready && !missC1
    when(invalidateStart) {
        invalidateActive := true.B
        invalidateSet := 0.U
    }
    when(invalidateActive) {
        when(invalidateSet === (c.sets - 1).U) {
            invalidateActive := false.B
            invalidateDone := true.B
        }.otherwise {
            invalidateSet := invalidateSet + 1.U
        }
    }
    when(invalidateDone && !io.maintenance.request) { invalidateDone := false.B }
    io.maintenance.done := invalidateDone

    /* Stage 1: Request / PF */
    val c1s1 = Wire(new IStage1Signal)
    c1s1.rreq := io.pp.request.fire
    c1s1.vaddr := io.pp.request.bits.pc
    c1s1.token := io.pp.request.bits.token

    /* Stage 2: RAM Output, Translation and Hit Check / IF1 */
    val c1s2 = RegInit(0.U.asTypeOf(new IStage1Signal))
    val c1s3 = RegInit(0.U.asTypeOf(new IStage2Signal(p, c)))
    when(invalidateStart) {
        c1s2.rreq := false.B
        c1s3.rreq := false.B
    }
    val responseMatches = c1s2.rreq && io.mmu.response.bits.token === c1s2.token
    val localTranslation = WireDefault(false.B)
    val translationValid = WireDefault(io.mmu.response.valid && responseMatches)
    val translatedPaddr = WireDefault(io.mmu.response.bits.paddr)
    val translatedUncache = WireDefault(io.mmu.response.bits.uncache)
    val translatedFault = WireDefault(io.mmu.response.bits.fault)

    itlb.io.lookup(0).valid := false.B
    itlb.io.lookup(0).bits.vaddr := c1s2.vaddr
    itlb.io.scopeUpdate.valid := false.B
    itlb.io.scopeUpdate.bits.asid := 0.U
    itlb.io.refill.valid := false.B
    itlb.io.refill.bits := 0.U.asTypeOf(new TLBRefill(itlb.p))
    itlb.io.flush := false.B
    if (c.tlbEnabled) {
        val manage = io.tlb.get
        val activeAsid = RegInit(0.U(9.W))
        val asidChanged = activeAsid =/= manage.control.asid
        val direct = !manage.control.enabled || manage.control.privilege === 3.U
        val tlbHit = itlb.io.response(0).hit && !asidChanged && !manage.flush
        itlb.io.lookup(0).valid := c1s2.rreq && !direct && !asidChanged && !io.flush && !manage.flush
        itlb.io.refill := manage.refill
        itlb.io.scopeUpdate.valid := asidChanged
        itlb.io.scopeUpdate.bits.asid := manage.control.asid
        itlb.io.flush := manage.flush
        when(asidChanged) {
            activeAsid := manage.control.asid
        }
        localTranslation := direct || tlbHit
        translationValid := localTranslation || (io.mmu.response.valid && responseMatches)
        translatedPaddr := Mux(
            direct,
            Cat(0.U(2.W), c1s2.vaddr),
            Mux(tlbHit, itlb.io.response(0).paddr, io.mmu.response.bits.paddr)
        )
        translatedUncache := Mux(
            direct,
            PMA.attribute(Cat(0.U(2.W), c1s2.vaddr)) =/= PMAAttribute.cached,
            Mux(tlbHit, itlb.io.response(0).pma =/= PMAAttribute.cached, io.mmu.response.bits.uncache)
        )
        translatedFault := Mux(
            direct,
            !PMA.executable(Cat(0.U(2.W), c1s2.vaddr)),
            Mux(
                tlbHit,
                !MMUPermission.instruction(itlb.io.response(0), manage.control) ||
                    itlb.io.response(0).pma === PMAAttribute.invalid,
                io.mmu.response.bits.fault
            )
        )
    }
    val c1s2Go = c1s2.rreq && translationValid &&
        !missC1 && fsm.io.cc.ready && (!c1s3.rreq || io.pp.response.fire) && !io.flush
    io.pp.request.ready := !reset.asBool && !io.maintenance.request && !invalidateActive && !missC1 && fsm.io.cc.ready &&
        (!c1s2.rreq || c1s2Go || io.flush)
    io.mmu.request.valid := c1s2.rreq && !localTranslation && !io.flush
    io.mmu.request.bits.pc := c1s2.vaddr
    io.mmu.request.bits.token := c1s2.token
    // A response to a flushed token is drained without using its address or permissions.
    io.mmu.response.ready := !responseMatches || c1s2Go || io.flush
    val faultC1s2 = translatedFault || c1s2.vaddr(1, 0).orR
    val hitC1s2 = VecInit(tagTab.zip(vldTab).map { case (t, v) =>
        t.dataOut === tag(translatedPaddr) && v.rdata(0)
    }).asUInt
    val c1s3In = Wire(new IStage2Signal(p, c))
    c1s3In.rreq := c1s2.rreq
    c1s3In.vaddr := c1s2.vaddr
    c1s3In.token := c1s2.token
    c1s3In.paddr := translatedPaddr
    c1s3In.uncache := translatedUncache
    c1s3In.fault := faultC1s2
    c1s3In.hit := hitC1s2
    c1s3In.rdata := VecInit(dataTab.map(t => fragment(t.dataOut, c1s2.vaddr)))
    c1s3In.victimWay := lruTab.rdata(0)
    c1s3In.victimData := Mux1H(lruTab.rdata(0), dataTab.map(_.dataOut))
    c1s3In.victimTag := Mux1H(lruTab.rdata(0), tagTab.map(_.dataOut))
    c1s3In.victimValid := Mux1H(lruTab.rdata(0), vldTab.map(_.rdata(0)))

    when(fsm.io.cc.cmiss) { missC1 := false.B }
    when(c1s2Go) { missC1 := !faultC1s2 && (translatedUncache || !hitC1s2.orR) }
    when(io.flush && fsm.io.cc.ready) { missC1 := false.B }

    /* Stage 3: Way/Return Selection and Miss Service / IF2 */
    val rline = Mux1H(fsm.io.cc.r1H, Seq(Mux1H(c1s3.hit, c1s3.rdata), dbuf.asUInt))
    io.pp.response.valid := c1s3.rreq && !missC1 && !io.flush
    io.pp.response.bits.token := c1s3.token
    io.pp.response.bits.mask := FrontendMath.range(c1s3.vaddr, p)
    io.pp.response.bits.inst := rline.asTypeOf(Vec(p.fetchWidth, UInt(32.W)))
    io.pp.response.bits.fault := Mux(
        c1s3.fault,
        io.pp.response.bits.mask,
        Mux(fsm.io.cc.r1H(1), dbufFault & io.pp.response.bits.mask, 0.U)
    )

    // Consumption and flush clear validity, while an active lower transaction retains its address.
    when(io.pp.response.fire) { c1s3.rreq := false.B }
    when(c1s2Go) { c1s3 := c1s3In; c1s2.rreq := false.B }
    when(io.flush) { c1s2.rreq := false.B; c1s3.rreq := false.B }
    when(io.pp.request.fire) { c1s2 := c1s1 }

    /* FSM Connections */
    fsm.io.cc.rreq := c1s3.rreq
    fsm.io.cc.uncache := c1s3.uncache
    fsm.io.cc.fault := c1s3.fault
    fsm.io.cc.hit := c1s3.hit
    fsm.io.cc.lru := c1s3.victimWay
    fsm.io.cc.flush := io.flush
    fsm.io.cc.stall := !io.pp.request.fire
    fsm.io.cc.consumed := io.pp.response.fire
    fsm.io.cc.responseReady := io.pp.response.ready
    fsm.io.l2.ready := io.l2.request.ready
    fsm.io.l2.rrsp := io.l2.response.fire
    fsm.io.l2.error := io.l2.response.bits.error
    fsm.io.l2.more := c1s3.uncache && readSlot =/= (p.fetchWidth - 1).U && c1s3.rreq && !io.flush

    /* LRU and Array Ports */
    lruTab.raddr(0) := index(c1s2.vaddr)
    lruTab.wen(0) := fsm.io.cc.lruUpd.orR
    lruTab.waddr(0) := index(c1s3.vaddr)
    lruTab.wdata(0) := fsm.io.cc.lruUpd
    val arrayAddress = Mux1H(fsm.io.cc.addrOH, Seq(index(c1s1.vaddr), index(c1s2.vaddr), index(c1s3.vaddr)))
    val arrayEnable = Mux1H(fsm.io.cc.addrOH, Seq(c1s1.rreq, c1s2.rreq, c1s3.rreq && !io.flush))
    for (way <- 0 until c.ways) {
        tagTab(way).clock := clock
        tagTab(way).address := arrayAddress
        tagTab(way).enable := arrayEnable
        tagTab(way).dataIn := tag(c1s3.paddr)
        tagTab(way).write := fsm.io.cc.tagvWe(way)
        tagTab(way).mask := 1.U
        // Preserve the constant mask port at the SRAM replacement boundary.
        dontTouch(tagTab(way).mask)
        dataTab(way).clock := clock
        dataTab(way).address := arrayAddress
        dataTab(way).enable := arrayEnable
        dataTab(way).dataIn := rbuf
        dataTab(way).write := fsm.io.cc.memWe(way)
        dataTab(way).mask := 1.U
        dontTouch(dataTab(way).mask)
        vldTab(way).raddr(0) := index(c1s2.vaddr)
        vldTab(way).wen(0) := invalidateActive || fsm.io.cc.tagvWe(way)
        vldTab(way).waddr(0) := Mux(invalidateActive, invalidateSet, index(c1s3.vaddr))
        vldTab(way).wdata(0) := !invalidateActive
    }

    /* Lower Request and Return Buffers */
    io.l2.request.valid := fsm.io.l2.rreq
    io.l2.request.bits.token := c1s3.token
    io.l2.request.bits.uncache := c1s3.uncache
    io.l2.request.bits.victimValid := !c1s3.uncache && c1s3.victimValid
    io.l2.request.bits.victimPaddr := Cat(
        c1s3.victimTag,
        index(c1s3.paddr),
        0.U(c.offsetBits.W)
    )
    io.l2.request.bits.victimData := c1s3.victimData
    io.l2.request.bits.paddr := Mux(
        c1s3.uncache,
        Cat(c1s3.paddr(33, p.blockBits), 0.U(p.blockBits.W)) | (readSlot << 2),
        Cat(c1s3.paddr(33, c.offsetBits), 0.U(c.offsetBits.W))
    )
    io.l2.response.ready := fsm.io.l2.pending
    when(fsm.io.cc.start) {
        readSlot := (if (p.fetchWidth == 1) 0.U else c1s3.vaddr(p.blockBits - 1, 2))
        dbuf := VecInit.fill(p.fetchWidth)(0.U(32.W))
        dbufFault := 0.U
    }
    when(io.l2.response.fire) {
        assert(io.l2.response.bits.token === c1s3.token, "ICache: lower response token mismatch")
        when(c1s3.uncache) {
            if (p.fetchWidth == 1) dbuf(0) := io.l2.response.bits.data(31, 0)
            else dbuf(readSlot) := io.l2.response.bits.data(31, 0)
            dbufFault := dbufFault | (io.l2.response.bits.error.asUInt << readSlot)
            readSlot := readSlot + 1.U
        }.otherwise {
            rbuf := io.l2.response.bits.data
            dbuf := fragment(io.l2.response.bits.data, c1s3.vaddr).asTypeOf(Vec(p.fetchWidth, UInt(32.W)))
            dbufFault := Fill(p.fetchWidth, io.l2.response.bits.error)
        }
    }

    /* Assertions and Optional Statistics */
    when(c1s2Go && !faultC1s2) {
        assert(PopCount(hitC1s2) <= 1.U, "ICache: multiple hits")
        assert(
            translatedPaddr(c.offsetBits + c.indexBits - 1, 0) ===
                c1s2.vaddr(c.offsetBits + c.indexBits - 1, 0),
            "ICache: translation changed page-offset index bits"
        )
    }
    if (p.observe) {
        val visitReg = RegInit(0.U(64.W))
        val hitReg = RegInit(0.U(64.W))
        val missCycleReg = RegInit(0.U(64.W))
        val visit = c1s2Go && !faultC1s2 && !translatedUncache
        when(visit) { visitReg := visitReg + 1.U }
        when(visit && hitC1s2.orR) { hitReg := hitReg + 1.U }
        when(missC1) { missCycleReg := missCycleReg + 1.U }
        io.dbg.get.visit := visitReg
        io.dbg.get.hit := hitReg
        io.dbg.get.missCycle := missCycleReg
    }
}
