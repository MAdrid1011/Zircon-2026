import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FrontendStateSnapshot(p: FrontendParams) extends Bundle {
    val history = UInt(p.historyBits.W)
    val folds = Vec(p.tageCount, UInt(p.hashBits.W))
    val ras = Vec(p.rasDepth, UInt(30.W))
    val top = UInt(30.W)
    val pointer = UInt(p.rasBits.W)
    val count = UInt((p.rasBits + 1).W)
}

class FrontendStateEvent(p: FrontendParams) extends Bundle {
    val pcWord = UInt(30.W)
    val prediction = new FrontendPrediction(p)
}

class FrontendStateRepair(p: FrontendParams) extends Bundle {
    val before = new FrontendStateSnapshot(p)
    val event = new FrontendStateEvent(p)
}

class SpeculativeStateIO(p: FrontendParams) extends Bundle {
    val early = Flipped(Valid(new FrontendStateEvent(p)))
    val repair = Flipped(Valid(new FrontendStateRepair(p)))
    val retire = Flipped(Vec(3, Valid(new FrontendStateEvent(p))))
    val flush = Input(Bool())
    val snapshot = Output(new FrontendStateSnapshot(p))
    val folds = Output(Vec(p.tageCount, UInt(p.hashBits.W)))
}

/** A complete PD snapshot includes overwritten RAS data, not only its pointer. */
class SpeculativeState(p: FrontendParams) extends Module {
    val io = IO(new SpeculativeStateIO(p))

    /* Speculative and Committed State */
    val speculative = RegInit(0.U.asTypeOf(new FrontendStateSnapshot(p)))
    val committed = RegInit(0.U.asTypeOf(new FrontendStateSnapshot(p)))

    /* One Block's RAS Transition */
    def advanceRas(
        before: FrontendStateSnapshot,
        event: FrontendStateEvent,
        taken: UInt,
        pointerOH: UInt,
        previousTop: UInt,
    ): FrontendStateSnapshot = {
        val next = WireDefault(before)
        val prediction = event.prediction
        val selected = taken.asBools
        val pc = Cat(event.pcWord, 0.U(2.W))

        // Decode each slot before selecting the RAS operation.
        val returnPc = Mux1H(
            selected,
            (0 until p.fetchWidth).map(i =>
                FrontendMath.slotPc(pc, i, p)(31, 2) + 1.U
            )
        )
        val pop = selected.zip(prediction.kinds).map { case (taken, kind) =>
            taken && FrontendCfi.pop(kind)
        }.reduce(_ || _) && before.count =/= 0.U
        val push =
            selected.zip(prediction.kinds).map { case (taken, kind) => taken && FrontendCfi.push(kind) }.reduce(_ || _)
        // Coroutines pop first, then push. Empty pops and full pushes do not wrap the count.
        val pushOnly = push && !pop
        val popOnly = pop && !push
        next.top := Mux(push, returnPc, Mux(pop, Mux(before.count > 1.U, previousTop, 0.U), before.top))
        when(pushOnly) { next.pointer := before.pointer + 1.U }
        when(popOnly) { next.pointer := before.pointer - 1.U }
        when(pushOnly && before.count < p.rasDepth.U) { next.count := before.count + 1.U }
        when(popOnly) { next.count := before.count - 1.U }
        for (i <- 0 until p.rasDepth) {
            val pushHere = pushOnly && pointerOH(i)
            val replaceHere = push && pop && pointerOH((i + 1) % p.rasDepth)
            when(pushHere || replaceHere) { next.ras(i) := returnPc }
        }
        next
    }

    /* One Block's History Transition */
    def advanceHistory(before: FrontendStateSnapshot, event: FrontendStateEvent, taken: UInt): FrontendStateSnapshot = {
        val next = WireDefault(before)
        val prediction = event.prediction
        val pc = Cat(event.pcWord, 0.U(2.W))
        // Update the full history and its incremental folds from the same block signature.
        val control = VecInit(prediction.kinds.zipWithIndex.map { case (kind, i) =>
            kind =/= 0.U && prediction.mask(i)
        }).asUInt
        val signature = FrontendMath.fold(pc(31, 2), p.historyStep) ^
            control.pad(p.historyStep) ^ taken.pad(p.historyStep)
        next.history := FrontendMath.append(before.history, signature, p)
        def rotate(value: UInt, shift: Int): UInt = {
            val amount = shift % p.hashBits
            if (amount == 0) value
            else Cat(value(p.hashBits - amount - 1, 0), value(p.hashBits - 1, p.hashBits - amount))
        }
        for (i <- 0 until p.tageCount) {
            val length = p.historyLengths(i) * p.historyStep
            val outgoing = before.history(length - 1, length - p.historyStep)
            next.folds(i) := rotate(before.folds(i), p.historyStep) ^
                rotate(FrontendMath.fold(outgoing, p.hashBits), length) ^ FrontendMath.fold(signature, p.hashBits)
        }
        next
    }

    /* One Block's State Transition */
    def advance(before: FrontendStateSnapshot, event: FrontendStateEvent): FrontendStateSnapshot = {
        val pointerOH = UIntToOH(before.pointer, p.rasDepth)
        val previousTop = Mux1H((0 until p.rasDepth).map(i =>
            pointerOH((i + 2) % p.rasDepth) -> before.ras(i)
        ))
        val rasNext = advanceRas(before, event, event.prediction.taken, pointerOH, previousTop)
        val historyNext = advanceHistory(before, event, event.prediction.taken)
        val next = WireDefault(rasNext)
        next.history := historyNext.history
        next.folds := historyNext.folds
        next
    }

    /* Early Prediction Transition */
    // TAGE arrives late. Build every legal RAS result first, then use taken only in the final one-hot selection.
    def advanceEarly(before: FrontendStateSnapshot, event: FrontendStateEvent): FrontendStateSnapshot = {
        val taken = event.prediction.taken
        val pointerOH = UIntToOH(before.pointer, p.rasDepth)
        val previousTop = Mux1H((0 until p.rasDepth).map(i =>
            pointerOH((i + 2) % p.rasDepth) -> before.ras(i)
        ))
        val selectors = !taken.orR +: taken.asBools
        val candidates = (0 to p.fetchWidth).map { candidate =>
            val selected = if (candidate == 0) 0.U(p.fetchWidth.W) else (1 << (candidate - 1)).U(p.fetchWidth.W)
            advanceRas(before, event, selected, pointerOH, previousTop)
        }
        val next = advanceHistory(before, event, taken)
        next.ras := Mux1H(selectors, candidates.map(_.ras))
        next.top := Mux1H(selectors, candidates.map(_.top))
        next.pointer := Mux1H(selectors, candidates.map(_.pointer))
        next.count := Mux1H(selectors, candidates.map(_.count))
        next
    }

    /* Commit and Recovery Priority */
    // A global flush includes this cycle's retirement; PD repair overrides younger speculation.
    var committedNext = committed
    for (port <- 0 until 3) {
        committedNext = Mux(io.retire(port).valid, advance(committedNext, io.retire(port).bits), committedNext)
    }
    // Keep flush as the final selector. It must not be decoded through every
    // normal speculative candidate before reaching the state registers.
    val nonFlushNext = Mux(
        io.repair.valid,
        advance(io.repair.bits.before, io.repair.bits.event),
        Mux(io.early.valid, advanceEarly(speculative, io.early.bits), speculative)
    )
    val speculativeNext = Mux(io.flush, committedNext, nonFlushNext)
    when(VecInit(io.retire.map(_.valid)).asUInt.orR) { committed := committedNext }
    speculative := speculativeNext

    /* Current Prediction State */
    io.snapshot := speculative
    io.folds := speculative.folds
    when(io.early.valid) { assert(PopCount(io.early.bits.prediction.taken) <= 1.U) }
    assert(speculative.count <= p.rasDepth.U && committed.count <= p.rasDepth.U)
}
