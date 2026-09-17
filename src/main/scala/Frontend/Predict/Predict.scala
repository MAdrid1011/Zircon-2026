import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class PredictIO(p: FrontendParams) extends Bundle {
    val fc = new Bundle {
        val instPkg = Input(new FrontendPackage(p))
        val out = Output(new FrontendPackage(p))
    }
    val lookup = new Bundle {
        val in = Input(new FrontendPackage(p))
        val prediction = Output(new FrontendPrediction(p))
        val directions = Output(UInt(p.fetchWidth.W))
        val biasDirections = Output(UInt(p.fetchWidth.W))
    }
    val pd = Flipped(Valid(new FrontendStateRepair(p)))
    val cmt = new Bundle {
        val train = Flipped(Valid(new FrontendTraining(p)))
        val retire = Flipped(Vec(3, Valid(new FrontendStateEvent(p))))
        val flush = Input(Bool())
    }
    val fte = new Bundle {
        val accept = Input(Bool())
        val flush = Input(Bool())
    }
    val dbg = if (p.observe) Some(new Bundle {
        val aheadValid = Output(Bool())
        val history = Output(UInt(32.W))
        val loop = Output(UInt(8.W))
        val rasTop = Output(UInt(32.W))
        val rasCount = Output(UInt((p.rasBits + 1).W))
        val btbReadSkipped = Output(Bool())
    })
    else None
}

/** Direction/target prediction and speculative state, with lookup latency exposed at the frontend boundary. */
class Predict(p: FrontendParams) extends Module {
    val io = IO(new PredictIO(p))

    /* Modules */
    val state = Module(new SpeculativeState(p))
    val direction = Module(new MorslDirection(p))
    val fastBtb = Module(new BlockBTB(p, p.fastBtbSets, 1))
    val mainBtb = Module(new MainBTB(p))
    val fastLookup = Module(new BlockBTBLookup(p, p.fastBtbSets, 1))
    val earlySelect = Module(new FrontendPredictionSelect(p))
    val mainLookup = Module(new BlockBTBLookup(p, p.btbSets, p.btbWays))
    val corrector = Module(new TaggedCorrector(p))
    val mainSelect = Module(new FrontendPredictionSelect(p))

    /* IF1: Fast Prediction */
    // Match saved ahead candidates while reading candidates for the next accepted block.
    val currentPc = io.fc.instPkg.startPc
    fastBtb.io.pc := currentPc
    mainBtb.io.query.bits := currentPc
    mainBtb.io.query.valid := io.fte.accept
    fastLookup.io.pc := currentPc
    fastLookup.io.raw := fastBtb.io.raw
    direction.io.query.pc := currentPc
    direction.io.query.folds := state.io.folds
    direction.io.query.loop := state.io.snapshot.loop
    earlySelect.io.pc := currentPc
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

    // Keep one common package; fill only the prediction fields produced here.
    io.fc.out := io.fc.instPkg
    io.fc.out.predict.range := earlySelect.io.range
    io.fc.out.predict.early := earlySelect.io.prediction
    io.fc.out.predict.earlyDirections := direction.io.directions
    io.fc.out.predict.meta := direction.io.meta
    io.fc.out.predict.tcRead := direction.io.tcRead
    io.fc.out.predict.before := state.io.snapshot

    /* IF2: Main Prediction */
    // These inputs cross the explicit IF1/IF2 register in Frontend.
    corrector.io.earlyDirections := io.lookup.in.predict.earlyDirections
    corrector.io.meta := io.lookup.in.predict.meta
    corrector.io.read := io.lookup.in.predict.tcRead
    mainLookup.io.pc := io.lookup.in.startPc
    mainLookup.io.raw := mainBtb.io.raw
    mainSelect.io.pc := io.lookup.in.startPc
    mainSelect.io.range := io.lookup.in.predict.range
    mainSelect.io.directions := corrector.io.directions
    mainSelect.io.backward := VecInit((0 until p.fetchWidth).map(i =>
        Mux(mainLookup.io.line.valid(i), mainLookup.io.line.backward(i), io.lookup.in.predict.early.backward(i))
    )).asUInt
    val indirectTargetHit = io.lookup.in.predict.tcRead.indirectValid &&
        io.lookup.in.predict.tcRead.indirectRow.tag === io.lookup.in.predict.meta.tcHistoryTag
    val indirectTarget = Cat(io.lookup.in.predict.tcRead.indirectRow.target, 0.U(2.W))
    for (i <- 0 until p.fetchWidth) {
        // A missing main-BTB slot keeps its fast prediction.
        val mainHit = mainLookup.io.line.valid(i)
        val kind = Mux(mainHit, mainLookup.io.line.kinds(i), io.lookup.in.predict.early.kinds(i))
        mainSelect.io.kinds(i) := kind
        mainSelect.io.control(i) := kind =/= 0.U
        mainSelect.io.conditional(i) := FrontendCfi.conditional(kind)
        mainSelect.io.targets(i) := Mux(
            FrontendCfi.pop(kind) && io.lookup.in.predict.before.count =/= 0.U,
            FrontendMath.rasTop(io.lookup.in.predict.before),
            Mux(
                FrontendCfi.indirect(kind) && !FrontendCfi.pop(kind) && indirectTargetHit,
                indirectTarget,
                Mux(mainHit, Cat(mainLookup.io.line.targets(i), 0.U(2.W)), io.lookup.in.predict.early.targets(i))
            )
        )
    }

    io.lookup.prediction := mainSelect.io.prediction
    io.lookup.directions := corrector.io.directions
    io.lookup.biasDirections := corrector.io.biasDirections

    /* Speculative Update and Recovery */
    // Advance once per accepted block; a flush invalidates the ahead context.
    direction.io.query.fire := io.fte.accept
    direction.io.query.invalidate := io.fte.flush
    state.io.early.valid := io.fte.accept
    state.io.early.bits.pc := io.fc.instPkg.startPc
    state.io.early.bits.prediction := earlySelect.io.prediction
    state.io.repair <> io.pd
    state.io.retire <> io.cmt.retire
    state.io.flush := io.cmt.flush

    /* Commit Training */
    direction.io.train <> io.cmt.train
    fastBtb.io.train <> io.cmt.train
    mainBtb.io.train <> io.cmt.train

    /* Observation */
    if (p.observe) {
        io.dbg.get.aheadValid := direction.io.meta.aheadValid
        io.dbg.get.history := FrontendMath.fold(state.io.snapshot.history, 32)
        io.dbg.get.loop := state.io.snapshot.loop
        io.dbg.get.rasTop := Mux(state.io.snapshot.count =/= 0.U, FrontendMath.rasTop(state.io.snapshot), 0.U)
        io.dbg.get.rasCount := state.io.snapshot.count
        io.dbg.get.btbReadSkipped := mainBtb.io.readSkipped.get
    }
}
