import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class PredictFetchIO(p: FrontendParams) extends Bundle {
    val instPkg = Input(new FrontendPackage(p))
    val prefetch = Flipped(Valid(UInt(30.W)))
    val out = Output(new FrontendPackage(p))
}

class PredictLookupIO(p: FrontendParams) extends Bundle {
    val in = Input(new FrontendPackage(p))
    val mainTag = Input(UInt((32 - p.blockBits - log2Ceil(p.btbSets)).W))
    val prediction = Output(new FrontendTargetPrediction(p))
    val directions = Output(UInt(p.fetchWidth.W))
    val meta = Output(new FrontendDirectionMeta(p))
}

class PredictCommitIO(p: FrontendParams) extends Bundle {
    val train = Flipped(Decoupled(new FrontendTraining(p)))
    val retire = Flipped(Vec(3, Valid(new FrontendStateEvent(p))))
    val flush = Input(Bool())
}

class PredictAdvanceIO extends Bundle {
    val accept = Input(Bool())
    val flush = Input(Bool())
}

class PredictIO(p: FrontendParams) extends Bundle {
    val fc = new PredictFetchIO(p)
    val lookup = new PredictLookupIO(p)
    val pd = Flipped(Valid(new FrontendStateRepair(p)))
    val cmt = new PredictCommitIO(p)
    val fte = new PredictAdvanceIO
    val dbg = if (p.observe) Some(new PredictDebugIO) else None
}

/** Direction/target prediction and speculative state, with lookup latency exposed at the frontend boundary. */
class Predict(p: FrontendParams, ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado) extends Module {
    val io = IO(new PredictIO(p))

    /* Modules */
    val state = Module(new SpeculativeState(p))
    val direction = Module(new MorslDirection(p, ramBackend))
    val indirect = Module(new IndirectTargetPredictor(p, ramBackend))
    val fastBtb = Module(new BlockBTB(p, p.fastBtbSets, 1))
    val mainBtb = Module(new MainBTB(p))
    val fastLookup = Module(new BlockBTBLookup(p, p.fastBtbSets, 1))
    // Fast-BTB and RAS targets are word aligned, so IF1 does not need a post-selection alignment check.
    val earlySelect = Module(new FrontendPredictionSelect(p, assumeAlignedTargets = true))
    val mainLookup = Module(new BlockBTBLookup(p, p.btbSets, p.btbWays))
    val corrector = Module(new StatisticalCorrector(p))

    /* IF1: Fast Prediction */
    // Match saved ahead candidates while reading candidates for the next accepted block.
    val currentPc = io.fc.instPkg.startPc
    fastBtb.io.index := currentPc(p.blockBits + log2Ceil(p.fastBtbSets) - 1, p.blockBits)
    mainBtb.io.query.bits := currentPc(p.blockBits + log2Ceil(p.btbSets) - 1, p.blockBits)
    mainBtb.io.query.valid := io.fte.accept
    fastLookup.io.tag := currentPc(31, p.blockBits + log2Ceil(p.fastBtbSets))
    fastLookup.io.raw := fastBtb.io.raw
    direction.io.query.pcWord := currentPc(31, 2)
    direction.io.query.prefetchPcWord := io.fc.prefetch.bits
    direction.io.query.prefetch := io.fc.prefetch.valid
    direction.io.query.folds := state.io.folds
    indirect.io.query.pcWord := currentPc(31, 2)
    indirect.io.query.folds := state.io.folds
    earlySelect.io.pcBlock := currentPc(31, p.blockBits)
    earlySelect.io.range := FrontendMath.range(currentPc, p)
    earlySelect.io.directions := direction.io.directions
    earlySelect.io.backward := fastLookup.io.line.backward.asUInt
    for (i <- 0 until p.fetchWidth) {
        val kind = Mux(fastLookup.io.line.valid(i), fastLookup.io.line.kinds(i), 0.U)
        earlySelect.io.kinds(i) := kind
        earlySelect.io.control(i) := kind =/= 0.U
        earlySelect.io.conditional(i) := FrontendCfi.conditional(kind)
        earlySelect.io.targets(i) := Mux(
            FrontendCfi.pop(kind) && state.io.snapshot.count =/= 0.U,
            FrontendMath.rasTop(state.io.snapshot),
            Cat(fastLookup.io.line.targets(i), 0.U(2.W))
        )
    }
    val fastConditionals = VecInit((0 until p.fetchWidth).map(i =>
        fastLookup.io.line.valid(i) && FrontendCfi.conditional(fastLookup.io.line.kinds(i))
    ))
    val loopAdvance = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        val active = (0 until p.fetchWidth).map { slot =>
            val rankBefore = if (slot == 0) 0.U else PopCount(fastConditionals.take(slot))
            fastConditionals(slot) && earlySelect.io.prediction.mask(slot) && rankBefore === rank.U
        }
        loopAdvance(rank) := active.reduce(_ || _)
    }
    direction.io.query.advance := loopAdvance.asUInt

    // Keep one common package; fill only the prediction fields produced here.
    io.fc.out := io.fc.instPkg
    io.fc.out.predict.range := earlySelect.io.range
    io.fc.out.predict.early := earlySelect.io.prediction
    io.fc.out.predict.earlyDirections := direction.io.directions
    io.fc.out.predict.meta := direction.io.meta
    // ITTAGE's wide target selection is completed after the IF1/IF2 boundary.
    io.fc.out.predict.meta.ittage := 0.U.asTypeOf(new FrontendIndirectMeta(p))
    io.fc.out.predict.scRead := direction.io.scRead
    io.fc.out.predict.before := state.io.snapshot

    /* IF2: Main Prediction */
    // These inputs cross the explicit IF1/IF2 register in Frontend.
    corrector.io.earlyDirections := io.lookup.in.predict.earlyDirections
    corrector.io.meta := io.lookup.in.predict.meta
    corrector.io.read := io.lookup.in.predict.scRead
    mainLookup.io.tag := io.lookup.mainTag
    mainLookup.io.raw := mainBtb.io.raw
    val lookupMeta = WireDefault(io.lookup.in.predict.meta)
    lookupMeta.ittage := indirect.io.lookupMeta
    val mainKinds = Wire(Vec(p.fetchWidth, UInt(3.W)))
    val mainTargets = Wire(Vec(p.fetchWidth, UInt(32.W)))
    lookupMeta.scPredictions := corrector.io.scPredictions
    lookupMeta.scLowMargin := corrector.io.scLowMargin
    for (i <- 0 until p.fetchWidth) {
        // A missing main-BTB slot keeps its fast prediction.
        val mainHit = mainLookup.io.line.valid(i)
        val kind = Mux(mainHit, mainLookup.io.line.kinds(i), io.lookup.in.predict.early.kinds(i))
        mainKinds(i) := kind
        val baseTarget = Mux(mainHit, Cat(mainLookup.io.line.targets(i), 0.U(2.W)), io.lookup.in.predict.early.targets(i))
        val ittage = indirect.io.lookupMeta
        val providerValid = ittage.aheadValid && ittage.providers(i) =/= 0.U
        val alternateTarget = Mux(ittage.alternateValid(i), ittage.alternateTargets(i), baseTarget)
        val ittageTarget = Mux(ittage.providerConfidence(i) =/= 0.U, ittage.providerTargets(i), alternateTarget)
        val useIttage = kind === FrontendCfi.Indirect.U && providerValid
        mainTargets(i) := Mux(
            FrontendCfi.pop(kind) && io.lookup.in.predict.before.count =/= 0.U,
            FrontendMath.rasTop(io.lookup.in.predict.before),
            Mux(useIttage, ittageTarget, baseTarget)
        )
        lookupMeta.ittage.alternateTargets(i) := alternateTarget
        lookupMeta.ittage.predictedTargets(i) := Mux(useIttage, ittageTarget, baseTarget)
    }

    io.lookup.prediction.kinds := mainKinds
    io.lookup.prediction.targets := mainTargets
    io.lookup.directions := corrector.io.directions
    io.lookup.meta := lookupMeta

    /* Speculative Update and Recovery */
    // Advance once per accepted block; a flush invalidates the ahead context.
    direction.io.query.fire := io.fte.accept
    direction.io.query.invalidate := io.fte.flush
    indirect.io.query.fire := io.fte.accept
    indirect.io.query.invalidate := io.fte.flush
    state.io.early.valid := io.fte.accept
    state.io.early.bits.pcWord := io.fc.instPkg.startPc(31, 2)
    state.io.early.bits.prediction := earlySelect.io.prediction
    state.io.repair <> io.pd
    state.io.retire <> io.cmt.retire
    state.io.flush := io.cmt.flush

    /* Commit Training */
    val trainValid = RegNext(io.cmt.train.fire, false.B)
    val pendingTrain = Reg(new FrontendTraining(p))
    io.cmt.train.ready := true.B
    val acceptTrain = io.cmt.train.fire
    when(acceptTrain) {
        pendingTrain := io.cmt.train.bits
    }
    direction.io.trainRead.valid := acceptTrain
    direction.io.trainRead.bits.pcWord := io.cmt.train.bits.pcWord
    direction.io.trainRead.bits.mask := io.cmt.train.bits.mask
    direction.io.trainRead.bits.kinds := io.cmt.train.bits.kinds
    direction.io.trainRead.bits.taken := io.cmt.train.bits.taken
    direction.io.trainRead.bits.meta := io.cmt.train.bits.meta
    direction.io.trainRead.bits.earlyDirections := io.cmt.train.bits.earlyDirections
    direction.io.train.valid := trainValid
    direction.io.train.bits.pcWord := pendingTrain.pcWord
    direction.io.train.bits.mask := pendingTrain.mask
    direction.io.train.bits.kinds := pendingTrain.kinds
    direction.io.train.bits.taken := pendingTrain.taken
    direction.io.train.bits.meta := pendingTrain.meta
    direction.io.train.bits.earlyDirections := pendingTrain.earlyDirections
    for (slot <- 0 until p.fetchWidth) {
        direction.io.trainRead.bits.targetLow(slot) := io.cmt.train.bits.targets(slot)(12, 0)
        direction.io.train.bits.targetLow(slot) := pendingTrain.targets(slot)(12, 0)
    }
    indirect.io.trainRead.valid := acceptTrain
    indirect.io.trainRead.bits := io.cmt.train.bits
    indirect.io.train.valid := trainValid
    indirect.io.train.bits := pendingTrain
    for (train <- Seq(fastBtb.io.train, mainBtb.io.train)) {
        train.valid := trainValid
        train.bits.pcBlock := pendingTrain.pcWord(29, p.blockBits - 2)
        train.bits.mask := pendingTrain.mask
        train.bits.kinds := pendingTrain.kinds
        for (slot <- 0 until p.fetchWidth) {
            train.bits.targetWords(slot) := pendingTrain.targets(slot)(31, 2)
        }
    }

    /* Observation */
    if (p.observe) {
        io.dbg.get.loopTraining := direction.io.dbg.get.loopTraining
        io.dbg.get.loopProvider := direction.io.dbg.get.loopProvider
        io.dbg.get.loopCorrect := direction.io.dbg.get.loopCorrect
    }
}

class PredictDebugIO extends Bundle {
    val loopTraining = Output(UInt(64.W))
    val loopProvider = Output(UInt(64.W))
    val loopCorrect = Output(UInt(64.W))
}
