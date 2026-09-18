import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class PreDecoders(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val in = Input(new FrontendPackage(p))
        val out = Output(new FrontendPackage(p))
        val prediction = Output(new FrontendPrediction(p))
        val changed = Output(Bool())
        val repair = Output(new FrontendStateRepair(p))
        val record = Output(new FrontendFtqRecord(p))
    })
    /* Modules */
    val finalSelect = Module(new FrontendPredictionSelect(p))
    val decoders = Seq.fill(p.fetchWidth)(Module(new RegisterInfoDecoder))

    /* Registered Prediction Context */
    val predInfo = io.in.predict
    val instPkgOut = WireDefault(io.in)
    // FTQ owns training context after PD; the common FQ package carries zeros here.
    instPkgOut.predict := 0.U.asTypeOf(new FrontendPredictInfo(p))
    finalSelect.io.pc := io.in.startPc
    finalSelect.io.range := predInfo.range & predInfo.returned
    finalSelect.io.directions := predInfo.directions
    finalSelect.io.backward := VecInit(predInfo.fields.map(f => f.immediate(31) || !f.immediate.orR)).asUInt
    val targetMismatch = Wire(Vec(p.fetchWidth, Bool()))
    val earlyNextPc = predInfo.early.nextPc
    // Check the increment in parallel instead of waiting for a full carry chain and a comparator.
    val sequentialMismatch = !FrontendMath.sumMatches(
        FrontendMath.blockBase(io.in.startPc, p),
        (p.fetchWidth * 4).U(32.W),
        earlyNextPc
    )
    def addressMismatch(target: UInt): Bool =
        Mux(target(1, 0) === 0.U, target =/= earlyNextPc, sequentialMismatch)

    /* Per-Instruction Decode and Target Check */
    for (i <- 0 until p.fetchWidth) {
        val decoder = decoders(i)
        val fields = predInfo.fields(i)
        decoder.io.inst := io.in.instructions(i).inst
        decoder.io.fields := fields
        val pc = FrontendMath.slotPc(io.in.startPc, i, p)
        val kind = Mux(io.in.instructions(i).fault, 0.U, decoder.io.kind)

        // Direct targets use PC; returns use the saved RAS top plus the JALR offset.
        val directTarget = BLevelPAdder32(pc, fields.immediate, 0.U(1.W)).io.res
        val returnOffset =
            Cat(Fill(20, fields.immediate(11)), fields.immediate(11, 0))
        val returnTarget = BLevelPAdder32(FrontendMath.rasTop(predInfo.before), returnOffset, 0.U(1.W)).io.res &
            "hfffffffe".U
        val sameKind = predInfo.main.kinds(i) === kind
        val isDirect = fields.cfiClass === FrontendCfiClass.Branch.U || fields.cfiClass === FrontendCfiClass.Jal.U
        val useRas = FrontendCfi.pop(kind) && predInfo.before.count =/= 0.U
        finalSelect.io.kinds(i) := kind
        finalSelect.io.control(i) := fields.cfiClass =/= FrontendCfiClass.None.U && !io.in.instructions(i).fault
        finalSelect.io.conditional(i) := fields.cfiClass === FrontendCfiClass.Branch.U && !io.in.instructions(i).fault
        finalSelect.io.targets(i) := Mux(
            isDirect,
            directTarget,
            Mux(useRas, returnTarget, Mux(sameKind, predInfo.main.targets(i), pc + 4.U))
        )
        // Check candidates before selecting their full addresses. The repair decision needs only one bit.
        val directMismatch = Mux(
            fields.immediate(1, 0) === 0.U,
            !FrontendMath.sumMatches(pc, fields.immediate, earlyNextPc),
            sequentialMismatch
        )
        val returnMismatch = Mux(
            !returnOffset(1),
            !FrontendMath.sumMatches(FrontendMath.rasTop(predInfo.before), returnOffset & "hfffffffe".U, earlyNextPc),
            sequentialMismatch
        )
        val predictedMismatch = Mux(
            sameKind,
            addressMismatch(predInfo.main.targets(i)),
            !FrontendMath.sumMatches(pc, 4.U(32.W), earlyNextPc)
        )
        targetMismatch(i) := Mux(isDirect, directMismatch, Mux(useRas, returnMismatch, predictedMismatch))

        // Preserve offset predictions for direct branches and full addresses for indirects.
        instPkgOut.instructions(i).pc := pc
        instPkgOut.instructions(i).rinfo := decoder.io.rinfo
        when(io.in.instructions(i).fault) { instPkgOut.instructions(i).rinfo := 0.U.asTypeOf(new FrontendRegisterInfo) }
        instPkgOut.instructions(i).kind := kind
        instPkgOut.instructions(i).predictedTaken := finalSelect.io.prediction.taken(i)
        instPkgOut.instructions(i).predictedValue := Mux(
            FrontendCfi.indirect(kind),
            finalSelect.io.targets(i),
            Mux(finalSelect.io.prediction.taken(i), fields.immediate, 4.U)
        )
    }

    /* Block Selection and Repair */
    instPkgOut.mask := finalSelect.io.prediction.mask
    instPkgOut.nextPc := finalSelect.io.prediction.nextPc
    val kindChanged = (0 until p.fetchWidth).map { i =>
        (instPkgOut.mask(i) || predInfo.early.mask(i)) && finalSelect.io.kinds(i) =/= predInfo.early.kinds(i)
    }.reduce(_ || _)
    val targetChanged = (finalSelect.io.prediction.taken & targetMismatch.asUInt).orR
    io.changed := targetChanged ||
        ((finalSelect.io.prediction.backward ^ predInfo.early.backward) & finalSelect.io.prediction.taken).orR ||
        instPkgOut.mask =/= predInfo.early.mask ||
        finalSelect.io.prediction.taken =/= predInfo.early.taken || kindChanged
    io.out := instPkgOut
    io.prediction := finalSelect.io.prediction

    // Recovery replays this block from its prediction-time snapshot.
    io.repair.before := predInfo.before
    io.repair.event.pc := io.in.startPc
    io.repair.event.prediction := finalSelect.io.prediction

    /* Commit Training Record */
    io.record.nextPc := instPkgOut.nextPc
    io.record.train.pc := io.in.startPc
    io.record.train.mask := instPkgOut.mask
    io.record.train.kinds := finalSelect.io.kinds
    io.record.train.taken := finalSelect.io.prediction.taken
    io.record.train.meta.tageIndices := predInfo.meta.tageIndices
    io.record.train.meta.tageTags := predInfo.meta.tageTags
    io.record.train.meta.tageProviders := predInfo.meta.tageProviders
    io.record.train.meta.alternateDirections := predInfo.meta.alternateDirections
    io.record.train.meta.aheadValid := predInfo.meta.aheadValid
    io.record.train.meta.scIndices := predInfo.meta.scIndices
    io.record.train.meta.scThresholdIndex := predInfo.meta.scThresholdIndex
    io.record.train.meta.scPredictions := predInfo.meta.scPredictions
    io.record.train.meta.scLowMargin := predInfo.meta.scLowMargin
    io.record.train.meta.loopIndex := predInfo.meta.loopIndex
    io.record.train.meta.loopValid := predInfo.meta.loopValid
    io.record.train.meta.loopPredictions := predInfo.meta.loopPredictions
    io.record.train.meta.ittage := predInfo.meta.ittage
    io.record.train.earlyDirections := predInfo.meta.tageDirections
    instPkgOut.record := io.record
}
