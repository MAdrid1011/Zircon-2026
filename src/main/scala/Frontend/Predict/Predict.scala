import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class PredictFetchIO(p: FrontendParams) extends Bundle {
    val instPkg = Input(new FrontendPackage(p))
    val directionPcHashes = Input(new MorslPcHashes(p))
    val fastBtbIndexOH = Input(UInt(p.fastBtbSets.W))
    val fastBtbTag = Input(UInt((32 - p.blockBits - log2Ceil(p.fastBtbSets)).W))
    val mainBtbIndex = Input(UInt(log2Ceil(p.btbSets).W))
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
    val recovery = Flipped(Valid(new FrontendStateEvent(p)))
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
    val fastBtb = Module(new BlockBTB(p, p.fastBtbSets, 1, ramBackend, synchronousPayload = true))
    val mainBtb = Module(new MainBTB(p, ramBackend))
    // Fast-BTB and RAS targets are word aligned, so IF1 does not need a post-selection alignment check.
    val earlySelect = Module(new FrontendPredictionSelect(
        p, assumeAlignedTargets = true, directionsAreSlots = true,
    ))

    /* IF1: Fast Prediction */
    // Match saved ahead candidates while reading candidates for the next accepted block.
    val currentPc = io.fc.instPkg.startPc
    fastBtb.io.indexOH := io.fc.fastBtbIndexOH
    fastBtb.io.lookupIndex.get := (if (p.fastBtbSets <= p.btbSets) {
        io.fc.mainBtbIndex(log2Ceil(p.fastBtbSets) - 1, 0)
    } else {
        currentPc(p.blockBits + log2Ceil(p.fastBtbSets) - 1, p.blockBits)
    })
    fastBtb.io.lookupTag := io.fc.fastBtbTag
    fastBtb.io.prefetch.get.valid := io.fc.prefetch.valid
    fastBtb.io.prefetch.get.bits := io.fc.prefetch.bits(
        p.blockBits + log2Ceil(p.fastBtbSets) - 3, p.blockBits - 2,
    )
    mainBtb.io.query.bits := io.fc.mainBtbIndex
    mainBtb.io.queryTag := currentPc(31, p.blockBits + log2Ceil(p.btbSets))
    mainBtb.io.query.valid := io.fte.accept
    val fastLine = WireDefault(fastBtb.io.raw.lines(0))
    fastLine.valid := Mux(fastBtb.io.hits(0), fastBtb.io.raw.lines(0).valid, 0.U)
    direction.io.query.pcHashes := io.fc.directionPcHashes
    direction.io.query.prefetchPcWord := io.fc.prefetch.bits
    direction.io.query.prefetch := io.fc.prefetch.valid
    direction.io.query.folds := state.io.folds
    earlySelect.io.pcBlock := currentPc(31, p.blockBits)
    earlySelect.io.range := FrontendMath.range(currentPc, p)
    // Derive rank from the selected raw row in parallel with its tag match.
    // A miss still clears every candidate through fastLine.valid below.
    val rawLine = fastBtb.io.raw.lines(0)
    val rawConditionals = (0 until p.fetchWidth).map(i =>
        rawLine.valid(i) && FrontendCfi.conditional(rawLine.kinds(i))
    )
    val slotRankOH = Wire(Vec(p.fetchWidth, UInt(p.fetchWidth.W)))
    for (slot <- 0 until p.fetchWidth) {
        val rankBefore = if (slot == 0) 0.U else PopCount((0 until slot).map(i =>
            earlySelect.io.range(i) && rawConditionals(i)
        ))
        slotRankOH(slot) := Mux(
            earlySelect.io.range(slot) && rawConditionals(slot),
            1.U(p.fetchWidth.W) << rankBefore,
            0.U,
        )
    }
    direction.io.query.slotRankOH := slotRankOH
    earlySelect.io.directions := direction.io.phtSlotDirections
    when(io.fte.accept) {
        for (slot <- 0 until p.fetchWidth) {
            assert(PopCount(slotRankOH(slot)) <= 1.U)
            when(slotRankOH(slot).orR) {
                assert(direction.io.phtSlotDirections(slot) ===
                    Mux1H(slotRankOH(slot).asBools, direction.io.phtDirections.asBools))
            }
        }
    }
    earlySelect.io.backward := fastLine.backward.asUInt
    for (i <- 0 until p.fetchWidth) {
        val kind = Mux(fastLine.valid(i), fastLine.kinds(i), 0.U)
        earlySelect.io.kinds(i) := kind
        earlySelect.io.control(i) := kind =/= 0.U
        earlySelect.io.conditional(i) := FrontendCfi.conditional(kind)
        earlySelect.io.targets(i) := Mux(
            FrontendCfi.pop(kind) && state.io.snapshot.count =/= 0.U,
            FrontendMath.rasTop(state.io.snapshot),
            Cat(fastLine.targets(i), 0.U(2.W))
        )
    }
    val fastConditionals = VecInit((0 until p.fetchWidth).map(i =>
        fastLine.valid(i) && FrontendCfi.conditional(fastLine.kinds(i))
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
    io.fc.out.predict.earlyDirections := direction.io.phtDirections
    io.fc.out.predict.meta := direction.io.meta
    io.fc.out.predict.meta.scBiasTag := 0.U
    io.fc.out.predict.meta.scIndices := 0.U.asTypeOf(io.fc.out.predict.meta.scIndices)
    io.fc.out.predict.meta.scThresholdIndex := 0.U
    io.fc.out.predict.meta.scPredictions := 0.U
    io.fc.out.predict.meta.scLowMargin := 0.U
    io.fc.out.predict.meta.loopIndex := 0.U
    io.fc.out.predict.meta.loopValid := 0.U
    io.fc.out.predict.meta.loopPredictions := 0.U
    io.fc.out.predict.meta.ittage := 0.U.asTypeOf(new FrontendIndirectMeta(p))
    io.fc.out.predict.scRead := 0.U.asTypeOf(new FrontendCorrectorRead(p))
    io.fc.out.predict.before := state.io.snapshot

    /* IF2: Main Prediction */
    // These inputs cross the explicit IF1/IF2 register in Frontend.
    val mainLine = Wire(new FrontendBtbLine(p))
    mainLine := Mux1H(mainBtb.io.hits.asBools, mainBtb.io.raw.lines)
    mainLine.valid := Mux1H(mainBtb.io.hits.asBools, mainBtb.io.raw.lines.map(_.valid))
    val lookupMeta = WireDefault(io.lookup.in.predict.meta)
    lookupMeta.ittage := 0.U.asTypeOf(new FrontendIndirectMeta(p))
    val mainKinds = Wire(Vec(p.fetchWidth, UInt(3.W)))
    val mainTargets = Wire(Vec(p.fetchWidth, UInt(32.W)))
    for (i <- 0 until p.fetchWidth) {
        // A missing main-BTB slot keeps its fast prediction.
        val mainHit = mainLine.valid(i)
        val kind = Mux(mainHit, mainLine.kinds(i), io.lookup.in.predict.early.kinds(i))
        mainKinds(i) := kind
        val baseTarget = Mux(mainHit, Cat(mainLine.targets(i), 0.U(2.W)), io.lookup.in.predict.early.targets(i))
        mainTargets(i) := Mux(
            FrontendCfi.pop(kind) && io.lookup.in.predict.before.count =/= 0.U,
            FrontendMath.rasTop(io.lookup.in.predict.before),
            baseTarget
        )
    }

    io.lookup.prediction.kinds := mainKinds
    io.lookup.prediction.targets := mainTargets
    io.lookup.directions := io.lookup.in.predict.meta.tageDirections
    io.lookup.meta := lookupMeta

    /* Speculative Update and Recovery */
    // Advance once per accepted block; a flush invalidates the ahead context.
    direction.io.query.fire := io.fte.accept
    direction.io.query.invalidate := io.fte.flush
    state.io.early.valid := io.fte.accept
    state.io.early.bits.pcWord := io.fc.instPkg.startPc(31, 2)
    state.io.early.bits.prediction := earlySelect.io.prediction
    state.io.repair <> io.pd
    state.io.retire <> io.cmt.retire
    state.io.recovery <> io.cmt.recovery
    state.io.flush := io.cmt.flush

    /* Commit Training */
    // Training is not on the fetch-accept path. Stage the queue head before
    // driving predictor update-read ports, keeping that queue's head mux out
    // of every table address and update-data cone.
    val trainReadValid = RegNext(io.cmt.train.valid, false.B)
    val trainCommitValid = RegNext(trainReadValid, false.B)
    val pendingTrain = Reg(new FrontendTraining(p))
    val trainCommit = Reg(new FrontendTraining(p))
    io.cmt.train.ready := true.B
    val acceptTrain = io.cmt.train.valid
    when(acceptTrain) {
        pendingTrain := io.cmt.train.bits
    }
    when(trainReadValid) {
        trainCommit := pendingTrain
    }
    def prepareBtbTraining(source: FrontendTraining, sets: Int): BtbTraining = {
        val pc = Cat(source.pcWord, 0.U(2.W))
        val indexBits = log2Ceil(sets)
        val index = pc(p.blockBits + indexBits - 1, p.blockBits)
        val prepared = Wire(new BtbTraining(p, sets))
        prepared.index := index
        prepared.indexOH := UIntToOH(index, sets)
        prepared.tag := pc(31, p.blockBits + indexBits)
        prepared.mask := source.mask
        prepared.cfi := VecInit((0 until p.fetchWidth).map(slot =>
            source.mask(slot) && source.kinds(slot) =/= 0.U
        )).asUInt
        prepared.kinds := source.kinds
        for (slot <- 0 until p.fetchWidth) {
            prepared.targetWords(slot) := source.targets(slot)(31, 2)
        }
        prepared.backward := VecInit((0 until p.fetchWidth).map(slot =>
            FrontendCfi.conditional(source.kinds(slot)) &&
                FrontendMath.backwardBranch(FrontendMath.slotPc(pc, slot, p), source.targets(slot))
        )).asUInt
        prepared
    }
    // pendingTrain already owns this cycle boundary. Rebuild the two BTB views
    // from its Q output instead of registering the same targets and kinds twice.
    val pendingFastBtbTrain = prepareBtbTraining(pendingTrain, p.fastBtbSets)
    val pendingMainBtbTrain = prepareBtbTraining(pendingTrain, p.btbSets)
    val trainFastBtb = Reg(new BtbTraining(p, p.fastBtbSets))
    val trainMainBtb = Reg(new BtbTraining(p, p.btbSets))
    val fastTagGroups = Reg(Vec(math.min(8, p.fastBtbSets), UInt((32 - p.blockBits - log2Ceil(p.fastBtbSets)).W)))
    val mainTagGroups = Reg(Vec(math.min(8, p.btbSets), UInt((32 - p.blockBits - log2Ceil(p.btbSets)).W)))
    for ((pending, tags, sets) <- Seq(
        (pendingFastBtbTrain, fastTagGroups, p.fastBtbSets),
        (pendingMainBtbTrain, mainTagGroups, p.btbSets),
    )) {
        val rowsPerGroup = sets / tags.length
        for (group <- 0 until tags.length) {
            when(trainReadValid && pending.indexOH((group + 1) * rowsPerGroup - 1, group * rowsPerGroup).orR) {
                tags(group) := pending.tag
            }
        }
    }
    when(trainReadValid) {
        trainFastBtb := pendingFastBtbTrain
        trainMainBtb := pendingMainBtbTrain
    }
    direction.io.trainRead.valid := trainReadValid
    direction.io.trainRead.bits.pcWord := pendingTrain.pcWord
    direction.io.trainRead.bits.mask := pendingTrain.mask
    direction.io.trainRead.bits.kinds := pendingTrain.kinds
    direction.io.trainRead.bits.taken := pendingTrain.taken
    direction.io.trainRead.bits.meta := pendingTrain.meta
    direction.io.trainRead.bits.earlyDirections := pendingTrain.earlyDirections
    direction.io.train.valid := trainCommitValid
    direction.io.train.bits.pcWord := trainCommit.pcWord
    direction.io.train.bits.mask := trainCommit.mask
    direction.io.train.bits.kinds := trainCommit.kinds
    direction.io.train.bits.taken := trainCommit.taken
    direction.io.train.bits.meta := trainCommit.meta
    direction.io.train.bits.earlyDirections := trainCommit.earlyDirections
    for (slot <- 0 until p.fetchWidth) {
        direction.io.trainRead.bits.targetLow(slot) := pendingTrain.targets(slot)(12, 0)
        direction.io.train.bits.targetLow(slot) := trainCommit.targets(slot)(12, 0)
    }
    fastBtb.io.train.valid := trainCommitValid
    fastBtb.io.train.bits := trainFastBtb
    fastBtb.io.trainTagGroups := fastTagGroups
    mainBtb.io.train.valid := trainCommitValid
    mainBtb.io.train.bits := trainMainBtb
    mainBtb.io.trainTagGroups := mainTagGroups

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
