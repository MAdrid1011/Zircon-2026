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

    /* One Block's State Transition */
    def advance(before: FrontendStateSnapshot, event: FrontendStateEvent): FrontendStateSnapshot = {
        val next = WireDefault(before)
        val prediction = event.prediction
        val selected = prediction.taken.asBools
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
        val previousTop = FrontendMath.read(before.ras.toSeq, (before.pointer - 2.U)(p.rasBits - 1, 0))
        next.top := Mux(push, returnPc, Mux(pop, Mux(before.count > 1.U, previousTop, 0.U), before.top))
        when(pushOnly) { next.pointer := before.pointer + 1.U }
        when(popOnly) { next.pointer := before.pointer - 1.U }
        when(pushOnly && before.count < p.rasDepth.U) { next.count := before.count + 1.U }
        when(popOnly) { next.count := before.count - 1.U }
        for (i <- 0 until p.rasDepth) {
            // Decode both positions before the prediction arrives; a coroutine replaces the top.
            val pushHere = pushOnly && before.pointer === i.U
            val replaceHere = push && pop && before.pointer === ((i + 1) % p.rasDepth).U
            when(pushHere || replaceHere) { next.ras(i) := returnPc }
        }

        // Update the full history and its incremental folds from the same block signature.
        val signature = FrontendMath.signature(pc, prediction, p)
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

    /* Commit and Recovery Priority */
    // A global flush includes this cycle's retirement; PD repair overrides younger speculation.
    var committedNext = committed
    for (port <- 0 until 3) {
        committedNext = Mux(io.retire(port).valid, advance(committedNext, io.retire(port).bits), committedNext)
    }
    val repair = io.repair.valid && !io.flush
    val early = io.early.valid && !io.repair.valid && !io.flush
    val hold = !io.early.valid && !io.repair.valid && !io.flush
    // Select complete candidate states in parallel; recovery must not traverse chained data muxes.
    val speculativeNext = Mux1H(Seq(
        hold -> speculative,
        early -> advance(speculative, io.early.bits),
        repair -> advance(io.repair.bits.before, io.repair.bits.event),
        io.flush -> committedNext
    ))
    when(VecInit(io.retire.map(_.valid)).asUInt.orR) { committed := committedNext }
    speculative := speculativeNext

    /* Current Prediction State */
    io.snapshot := speculative
    io.folds := speculative.folds
    assert(speculative.count <= p.rasDepth.U && committed.count <= p.rasDepth.U)
}
