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
    val fastBtbIndexOHIF1 = Reg(UInt(p.fastBtbSets.W))
    val fastBtbTagIF1 = Reg(UInt((32 - p.blockBits - log2Ceil(p.fastBtbSets)).W))
    val mainBtbIndexIF1 = Reg(UInt(log2Ceil(p.btbSets).W))
    val directionPcHashesIF1 = RegInit(0.U.asTypeOf(new MorslPcHashes(p)))
    val validIF1 = RegInit(false.B)
    npc.io.cmt <> io.commit.rob.redirect
    npc.io.pr.valid := if1Fire
    npc.io.pr.bits.pc := pr.io.fc.out.predict.early.nextPc
    npc.io.space := !validIF1 || if1Fire || flushYounger
    ic.io.pp.request <> npc.io.request
    instPkgPF.startPc := npc.io.request.bits.pc
    when(if1Fire || flushYounger) { validIF1 := false.B }
    when(ic.io.pp.request.fire) {
        instPkgIF1 := instPkgPF
        fastBtbIndexOHIF1 := UIntToOH(
            npc.io.request.bits.pc(p.blockBits + log2Ceil(p.fastBtbSets) - 1, p.blockBits),
            p.fastBtbSets,
        )
        fastBtbTagIF1 := npc.io.request.bits.pc(31, p.blockBits + log2Ceil(p.fastBtbSets))
        mainBtbIndexIF1 := npc.io.request.bits.pc(p.blockBits + log2Ceil(p.btbSets) - 1, p.blockBits)
        directionPcHashesIF1 := MorslPcHashes.fromPcWord(npc.io.request.bits.pc(31, 2), p)
        validIF1 := true.B
    }

    /* Fetch Stage 1 */
    pr.io.fc.instPkg := instPkgIF1
    pr.io.fc.fastBtbIndexOH := fastBtbIndexOHIF1
    pr.io.fc.fastBtbTag := fastBtbTagIF1
    pr.io.fc.mainBtbIndex := mainBtbIndexIF1
    pr.io.fc.directionPcHashes := directionPcHashesIF1
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
    instPkgPDIn.predict.main := pr.io.lookup.prediction
    instPkgPDIn.predict.directions := pr.io.lookup.directions
    instPkgPDIn.predict.meta := pr.io.lookup.meta
    instPkgPDIn.instructions.zipWithIndex.foreach { case (inst, i) =>
        inst.pc := FrontendMath.slotPc(instPkgIF2.startPc, i, p)
        inst.inst := ic.io.pp.response.bits.inst(i)
        inst.fault := ic.io.pp.response.bits.fault(i)
    }
    instPkgPDIn.predict.fields := ic.io.pp.response.bits.fields
    pd.io.in := instPkgPDIn
    val instPkgPD = Reg(new FrontendPackage(p))
    val pdCfiClass = Reg(Vec(p.fetchWidth, UInt(2.W)))
    val pdPredictedImmediates = Reg(Vec(p.fetchWidth, UInt(32.W)))
    val pdPredictedTargets = Reg(Vec(p.fetchWidth, UInt(32.W)))
    val pdChanged = Reg(Bool())
    val pdRepair = Reg(new FrontendStateRepair(p))
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
    // Invalid payload is unobservable; a live PD entry holds until it enters FQ.
    // The ICache response still controls validity, not every payload register D.
    when(!validPD || pdFire) {
        instPkgPD := pd.io.out
        for (slot <- 0 until p.fetchWidth) {
            instPkgPD.instructions(slot).predictedValue := 0.U
            instPkgPD.instructions(slot).rinfo := 0.U.asTypeOf(new FrontendRegisterInfo)
            pdCfiClass(slot) := instPkgPDIn.predict.fields(slot).cfiClass
            pdPredictedImmediates(slot) := instPkgPDIn.predict.fields(slot).immediate
            pdPredictedTargets(slot) := pd.io.prediction.targets(slot)
        }
        pdChanged := pd.io.changed
        pdRepair := pd.io.repair
    }
    when(if2Fire) {
        validPD := true.B
        pdRepairApplied := false.B
        pdRepairAllowed := !ic.io.pp.response.bits.fault.orR
    }

    /* Previous Decode Stage */
    val instPkgFQIn = WireDefault(instPkgPD)
    instPkgFQIn.ftqIdx := 0.U
    for (slot <- 0 until p.fetchWidth) {
        val instruction = instPkgPD.instructions(slot)
        val registerInfo = Module(new RegisterInfoDecoder)
        registerInfo.io.inst := instruction.inst
        registerInfo.io.fields.cfiClass := pdCfiClass(slot)
        registerInfo.io.fields.immediate := 0.U
        instPkgFQIn.instructions(slot).rinfo := Mux(
            instruction.fault, 0.U.asTypeOf(new FrontendRegisterInfo), registerInfo.io.rinfo)
        instPkgFQIn.instructions(slot).predictedValue := Mux(
            FrontendCfi.indirect(instruction.kind),
            pdPredictedTargets(slot),
            Mux(instruction.predictedTaken, pdPredictedImmediates(slot), 4.U),
        )
    }
    pdFlush := validPD && pdRepairAllowed && pdChanged && !pdRepairApplied && !cmtFlush
    npc.io.pd.valid := pdFlush
    npc.io.pd.bits.pc := instPkgPD.nextPc
    pr.io.pd.valid := pdFlush
    pr.io.pd.bits := pdRepair
    when(pdFlush && !pdFire) { pdRepairApplied := true.B }
    for (slot <- 0 until p.fetchWidth) {
        val earlier = if (slot == 0) false.B else instPkgFQIn.mask(slot - 1, 0).orR
        val later = if (slot + 1 == p.fetchWidth) false.B else instPkgFQIn.mask(p.fetchWidth - 1, slot + 1).orR
        val payloadValid = validPD && instPkgFQIn.mask(slot)
        // Flush or maintenance can suppress occupancy without suppressing an
        // unobservable prewrite into the current free slot.
        fq.io.enq(slot).valid := payloadValid && !cmtFlush && !io.maintenance.request
        fq.io.enqPayloadWrite(slot) := instPkgFQIn.mask(slot)
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
    pr.io.cmt.recovery <> io.commit.ftq.recovery
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
