import chisel3._
import chisel3.util._
import ZirconConfig.{FrontendParams, ICacheParams}

class FrontendMemoryIO(c: ICacheParams) extends Bundle {
    val l2 = new ICacheL2IO(c)
}

class FrontendIO(p: FrontendParams, c: ICacheParams, tlbEnabled: Boolean) extends Bundle {
    val mmu = new IMMUIO(p)
    val tlb = if (tlbEnabled) Some(new TLBManagementIO) else None
    val mem = new FrontendMemoryIO(c)
    val middle = new FrontendMiddleIO(p)
    val commit = new FrontendCommitIO(p)
    val observe = if (p.observe) Some(new FrontendObserveIO(p)) else None
    val maintenance = new Bundle {
        val request = Input(Bool())
        val done = Output(Bool())
    }
}

class Frontend(
    p: FrontendParams = FrontendParams(),
    c: ICacheParams = ICacheParams(),
    tlbEnabled: Boolean = true,
) extends Module {
    val io = IO(new FrontendIO(p, c, tlbEnabled))
    val npc = Module(new NPC(p))
    val pr = Module(new Predict(p))
    val ic = Module(new ICache(p, c.copy(tlbEnabled = tlbEnabled)))
    val fields = Seq.fill(p.fetchWidth)(Module(new PredecodeFields))
    val pd = Module(new PreDecoders(p))
    val fq = Module(new FetchQueue(p))
    val pc = Reg(UInt(32.W))

    val if1Fire = Wire(Bool())
    val if2Fire = Wire(Bool())
    val pdFire = Wire(Bool())
    val pdFlush = Wire(Bool())
    val cmtFlush = io.commit.rob.redirect.valid
    val flushYounger = cmtFlush || pdFlush
    ic.io.flush := flushYounger
    io.mmu <> ic.io.mmu
    if (tlbEnabled) {
        ic.io.tlb.get <> io.tlb.get
    }
    io.mem.l2 <> ic.io.l2
    ic.io.maintenance.request := io.maintenance.request
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
    npc.io.fte.space := !validIF1 || if1Fire || flushYounger
    ic.io.pp.request <> npc.io.request
    instPkgPF.fetchToken := npc.io.request.bits.token
    instPkgPF.startPc := npc.io.request.bits.pc
    when(if1Fire || flushYounger) { validIF1 := false.B }
    when(ic.io.pp.request.fire) {
        pc := npc.io.request.bits.pc
        instPkgIF1 := instPkgPF
        validIF1 := true.B
    }

    /* Fetch Stage 1 */
    val instPkgIF2In = WireDefault(instPkgIF1)
    pr.io.fc.instPkg := instPkgIF1
    pr.io.fte.accept := if1Fire
    pr.io.fte.flush := flushYounger
    instPkgIF2In := pr.io.fc.out
    val instPkgIF2 = Reg(new FrontendPackage(p))
    val validIF2 = RegInit(false.B)
    if1Fire := validIF1 && (!validIF2 || if2Fire) && !flushYounger
    when(if2Fire || flushYounger) { validIF2 := false.B }
    when(if1Fire) { instPkgIF2 := instPkgIF2In; validIF2 := true.B }

    /* Fetch Stage 2 */
    val instPkgPDIn = WireDefault(instPkgIF2)
    pr.io.lookup.in := instPkgIF2
    fields.zip(ic.io.pp.response.bits.inst).foreach { case (decoder, inst) => decoder.io.inst := inst }
    instPkgPDIn.predict.main := pr.io.lookup.prediction
    instPkgPDIn.predict.directions := pr.io.lookup.directions
    instPkgPDIn.predict.biasDirections := pr.io.lookup.biasDirections
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
    val responseMatches = validIF2 && ic.io.pp.response.bits.token === instPkgIF2.fetchToken
    val responseEarly = validIF1 && ic.io.pp.response.bits.token === instPkgIF1.fetchToken
    // IF1 responses wait for IF2; cancelled or already consumed tokens are discarded.
    ic.io.pp.response.ready := !(responseMatches || responseEarly) ||
        (responseMatches && (!validPD || pdFire) && !flushYounger)
    if2Fire := ic.io.pp.response.fire && responseMatches && !flushYounger
    when(if2Fire) {
        assert(
            (ic.io.pp.response.bits.mask & instPkgIF2.predict.range) === instPkgIF2.predict.range,
            "Fetch response must provide every requested slot, using fault bits for exceptions"
        )
    }
    when(pdFire || cmtFlush) { validPD := false.B; pdRepairApplied := false.B }
    when(if2Fire) { instPkgPD := instPkgPDIn; validPD := true.B; pdRepairApplied := false.B }

    /* Previous Decode Stage */
    val instPkgFQIn = WireDefault(instPkgPD)
    pd.io.in := instPkgPD
    instPkgFQIn := pd.io.out
    instPkgFQIn.ftqIdx := 0.U
    pdFlush := validPD && pd.io.changed && !pdRepairApplied && !cmtFlush
    pr.io.pd.valid := pdFlush
    pr.io.pd.bits := pd.io.repair
    when(pdFlush && !pdFire) { pdRepairApplied := true.B }
    fq.io.enq.valid := validPD && !cmtFlush
    fq.io.enq.bits := instPkgFQIn
    fq.io.flush := cmtFlush
    pdFire := fq.io.enq.fire
    io.middle.out <> fq.io.out

    /* Commit Feedback */
    pr.io.cmt.retire <> io.commit.ftq.retire
    pr.io.cmt.flush := cmtFlush
    pr.io.cmt.train <> io.commit.ftq.train

    /* Observation */
    if (p.observe) {
        val observe = io.observe.get
        observe.fetchRequest.valid := ic.io.pp.request.valid
        observe.fetchRequest.bits := ic.io.pp.request.bits
        observe.fetchRequestReady := ic.io.pp.request.ready
        observe.fetchResponse.valid := ic.io.pp.response.valid
        observe.fetchResponse.bits := ic.io.pp.response.bits
        observe.fetchResponseReady := ic.io.pp.response.ready
        observe.icache := ic.io.dbg.get
        observe.icacheMiss := ic.io.miss
        val events = Seq(
            (if1Fire, instPkgIF1.fetchToken, pc, pr.io.fc.out.predict.early, pr.io.fc.out.predict.earlyDirections),
            (if2Fire, instPkgIF2.fetchToken, instPkgIF2.startPc, pr.io.lookup.prediction, pr.io.lookup.directions),
            (pdFire, instPkgPD.fetchToken, pd.io.out.startPc, pd.io.prediction, instPkgPD.predict.directions)
        )
        observe.stages.zip(events).foreach { case (out, (valid, token, pc, prediction, directions)) =>
            out.valid := valid
            out.bits.token := token
            out.bits.pc := pc
            out.bits.prediction := prediction
            out.bits.directions := directions
        }
        observe.cancel.valid := flushYounger
        observe.cancel.bits.global := cmtFlush
        observe.cancel.bits.boundaryToken := instPkgPD.fetchToken
        observe.cancel.bits.target := Mux(cmtFlush, io.commit.rob.redirect.bits.pc, instPkgFQIn.nextPc)
        observe.repair.valid := pdFlush
        observe.repair.bits.token := instPkgPD.fetchToken
        observe.repair.bits.pc := pd.io.out.startPc
        observe.repair.bits.prediction := pd.io.prediction
        observe.repair.bits.directions := instPkgPD.predict.directions
        observe.history := pr.io.dbg.get.history
        observe.loop := pr.io.dbg.get.loop
        observe.rasTop := pr.io.dbg.get.rasTop
        observe.rasCount := pr.io.dbg.get.rasCount
        observe.aheadValid := pr.io.dbg.get.aheadValid
        observe.ftqUsed := io.commit.ftq.used.get
        observe.ftqEnqIdx := 0.U
        observe.ftqBlocked := false.B
        observe.btbReadSkipped := pr.io.dbg.get.btbReadSkipped
        observe.training := io.commit.ftq.train.valid

        val fqBlockedCycles = RegInit(0.U(64.W))
        val fqEmptyCycles = RegInit(0.U(64.W))
        when(validPD && !fq.io.enq.ready && !cmtFlush) { fqBlockedCycles := fqBlockedCycles + 1.U }
        when(!fq.io.out.valid) { fqEmptyCycles := fqEmptyCycles + 1.U }
        observe.fqBlockedCycles := fqBlockedCycles
        observe.fqEmptyCycles := fqEmptyCycles
        observe.ftqBlockedCycles := 0.U
    }
}
