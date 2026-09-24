import chisel3._
import chisel3.util._
import ZirconConfig.{FrontendParams, ICacheParams, IssueParams}

class FrontendMemoryIO(c: ICacheParams) extends Bundle {
    val l2 = new ICacheL2IO(c)
}

class FrontendIO(p: FrontendParams, c: ICacheParams, tlbEnabled: Boolean, dispatchWidth: Int) extends Bundle {
    val mmu = new IMMUIO(p)
    val tlb = if (tlbEnabled) Some(new TLBManagementIO) else None
    val mem = new FrontendMemoryIO(c)
    val middle = new FrontendMiddleIO(p, dispatchWidth)
    val commit = new FrontendCommitIO(p)
    val maintenance = Flipped(new CacheMaintenanceIO)
    val observe = if (p.observe) Some(new FrontendObserveIO) else None
}

class Frontend(
    p: FrontendParams = FrontendParams(),
    c: ICacheParams = ICacheParams(),
    tlbEnabled: Boolean = true,
    issue: IssueParams = IssueParams(),
    ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
) extends Module {
    val io = IO(new FrontendIO(p, c, tlbEnabled, issue.dispatchWidth))
    val npc = Module(new NPC(p))
    val pr = Module(new Predict(p, ramBackend))
    val ic = Module(new ICache(p, c.copy(tlbEnabled = tlbEnabled)))
    val fields = Seq.fill(p.fetchWidth)(Module(new PredecodeFields))
    val pd = Module(new PreDecoders(p))
    val fq = Module(new FetchQueue(p, issue.dispatchWidth))

    val if1Fire = Wire(Bool())
    val if2Fire = Wire(Bool())
    val pdFire = Wire(Bool())
    val pdFlush = Wire(Bool())
    val cmtFlush = io.commit.rob.redirect.valid
    val flushYounger = cmtFlush || pdFlush || io.maintenance.request
    ic.io.flush := flushYounger
    io.mmu <> ic.io.mmu
    if (tlbEnabled) {
        ic.io.tlb.get <> io.tlb.get
    }
    io.mem.l2 <> ic.io.l2
    ic.io.maintenance.request := io.maintenance.request
    ic.io.maintenance.invalidate := io.maintenance.invalidate
    io.maintenance.done := ic.io.maintenance.done

    /* Previous Fetch Stage */
    val instPkgPF = WireDefault(0.U.asTypeOf(new FrontendPackage(p)))
    val instPkgIF1 = Reg(new FrontendPackage(p))
    val validIF1 = RegInit(false.B)
    npc.io.cmt <> io.commit.rob.redirect
    npc.io.pd.valid := pdFlush
    npc.io.pd.bits.pc := pd.io.out.nextPc
    npc.io.pr.valid := if1Fire
    npc.io.pr.bits.pc := pr.io.fc.out.predict.early.nextPc
    npc.io.space := !validIF1 || if1Fire || flushYounger
    ic.io.pp.request <> npc.io.request
    instPkgPF.startPc := npc.io.request.bits.pc
    when(if1Fire || flushYounger) { validIF1 := false.B }
    when(ic.io.pp.request.fire) {
        instPkgIF1 := instPkgPF
        validIF1 := true.B
    }

    /* Fetch Stage 1 */
    pr.io.fc.instPkg := instPkgIF1
    pr.io.fc.prefetch.valid := ic.io.pp.request.fire
    pr.io.fc.prefetch.bits := npc.io.request.bits.pc(31, 2)
    pr.io.fte.accept := if1Fire
    pr.io.fte.flush := flushYounger
    val instPkgIF2 = Reg(new FrontendPackage(p))
    val validIF2 = RegInit(false.B)
    if1Fire := validIF1 && (!validIF2 || if2Fire) && !flushYounger
    when(if2Fire || flushYounger) { validIF2 := false.B }
    when(if1Fire) { instPkgIF2 := pr.io.fc.out; validIF2 := true.B }

    /* Fetch Stage 2 */
    val instPkgPDIn = WireDefault(instPkgIF2)
    pr.io.lookup.in := instPkgIF2
    pr.io.lookup.mainTag := instPkgIF2.startPc(31, p.blockBits + log2Ceil(p.btbSets))
    fields.zip(ic.io.pp.response.bits.inst).foreach { case (decoder, inst) => decoder.io.inst := inst }
    instPkgPDIn.predict.main := pr.io.lookup.prediction
    instPkgPDIn.predict.directions := pr.io.lookup.directions
    instPkgPDIn.predict.meta := pr.io.lookup.meta
    instPkgPDIn.predict.returned := ic.io.pp.response.bits.mask
    instPkgPDIn.instructions.zipWithIndex.foreach { case (inst, i) =>
        inst.pc := FrontendMath.slotPc(instPkgIF2.startPc, i, p)
        inst.inst := ic.io.pp.response.bits.inst(i)
        inst.fault := ic.io.pp.response.bits.fault(i)
    }
    instPkgPDIn.predict.fields := VecInit(fields.map(_.io.fields))
    val instPkgPD = Reg(new FrontendPackage(p))
    val validPD = RegInit(false.B)
    val pdRepairApplied = RegInit(false.B)
    val pdRepairAllowed = RegInit(false.B)
    val responseMatches = validIF2
    val responseEarly = validIF1
    // In-order IF1 responses wait until the matching frontend package reaches IF2.
    ic.io.pp.response.ready := !(responseMatches || responseEarly) ||
        (responseMatches && (!validPD || pdFire) && !flushYounger)
    if2Fire := ic.io.pp.response.fire && responseMatches && !flushYounger
    when(if2Fire) {
        assert(
            (ic.io.pp.response.bits.mask & instPkgIF2.predict.range) === instPkgIF2.predict.range,
            "Fetch response must provide every requested slot, using fault bits for exceptions"
        )
    }
    when(pdFire || cmtFlush || io.maintenance.request) {
        validPD := false.B
        pdRepairApplied := false.B
        pdRepairAllowed := false.B
    }
    when(if2Fire) {
        instPkgPD := instPkgPDIn
        validPD := true.B
        pdRepairApplied := false.B
        pdRepairAllowed := !ic.io.pp.response.bits.fault.orR
    }

    /* Previous Decode Stage */
    val instPkgFQIn = WireDefault(instPkgPD)
    pd.io.in := instPkgPD
    instPkgFQIn := pd.io.out
    instPkgFQIn.ftqIdx := 0.U
    pdFlush := validPD && pdRepairAllowed && pd.io.changed && !pdRepairApplied && !cmtFlush
    pr.io.pd.valid := pdFlush
    pr.io.pd.bits := pd.io.repair
    when(pdFlush && !pdFire) { pdRepairApplied := true.B }
    for (slot <- 0 until p.fetchWidth) {
        val earlier = if (slot == 0) false.B else instPkgFQIn.mask(slot - 1, 0).orR
        val later = if (slot + 1 == p.fetchWidth) false.B else instPkgFQIn.mask(p.fetchWidth - 1, slot + 1).orR
        fq.io.enq(slot).valid := validPD && instPkgFQIn.mask(slot) && !cmtFlush && !io.maintenance.request
        fq.io.enq(slot).bits.slot := slot.U
        fq.io.enq(slot).bits.packetStart := !earlier
        fq.io.enq(slot).bits.packetEnd := !later
        fq.io.enq(slot).bits.instruction := instPkgFQIn.instructions(slot)
        fq.io.enq(slot).bits.record := instPkgFQIn.record
    }
    fq.io.flush := cmtFlush
    pdFire := validPD && fq.io.enq(0).ready && !cmtFlush && !io.maintenance.request
    io.middle.out <> fq.io.out

    /* Commit Feedback */
    pr.io.cmt.retire <> io.commit.ftq.retire
    pr.io.cmt.flush := cmtFlush
    pr.io.cmt.train <> io.commit.ftq.train

    /* Observation */
    if (p.observe) {
        val observe = io.observe.get
        observe.icache := ic.io.dbg.get
        observe.loopTraining := pr.io.dbg.get.loopTraining
        observe.loopProvider := pr.io.dbg.get.loopProvider
        observe.loopCorrect := pr.io.dbg.get.loopCorrect

        val fqBlockedCycles = RegInit(0.U(64.W))
        val fqEmptyCycles = RegInit(0.U(64.W))
        when(validPD && !fq.io.enq(0).ready && !cmtFlush) { fqBlockedCycles := fqBlockedCycles + 1.U }
        when(!fq.io.out.map(_.valid).reduce(_ || _)) { fqEmptyCycles := fqEmptyCycles + 1.U }
        observe.fqBlockedCycles := fqBlockedCycles
        observe.fqEmptyCycles := fqEmptyCycles
    }
}
