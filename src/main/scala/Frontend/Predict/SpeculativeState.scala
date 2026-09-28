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
    val recovery = Flipped(Valid(new FrontendStateEvent(p)))
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
    // Committed RAS updates can retire three fetch packets in one cycle. Keep
    // the small circular pointer and saturating count one-hot internally so
    // that each transition is wiring plus local gating, rather than a binary
    // add/compare/decode chain feeding the RAS array write selects.
    val committedPointerOH = RegInit(1.U(p.rasDepth.W))
    val committedCountOH = RegInit(1.U((p.rasDepth + 1).W))

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

    /** Advance only the RAS control state; stack rows are written once per cycle. */
    def advanceCommittedControl(
        pointerOH: UInt,
        countOH: UInt,
        event: FrontendStateEvent,
    ): (UInt, UInt, Bool, Bool) = {
        val selected = event.prediction.taken.asBools
        val popRequested = selected.zip(event.prediction.kinds).map { case (taken, kind) =>
            taken && FrontendCfi.pop(kind)
        }.reduce(_ || _)
        val pop = popRequested && !countOH(0)
        val push = selected.zip(event.prediction.kinds).map { case (taken, kind) =>
            taken && FrontendCfi.push(kind)
        }.reduce(_ || _)
        val pushOnly = push && !pop
        val popOnly = pop && !push

        val pointerForward = Cat(pointerOH(p.rasDepth - 2, 0), pointerOH(p.rasDepth - 1))
        val pointerBackward = Cat(pointerOH(0), pointerOH(p.rasDepth - 1, 1))
        val nextPointerOH = Mux(pushOnly, pointerForward, Mux(popOnly, pointerBackward, pointerOH))
        val countIncremented = Cat(countOH(p.rasDepth - 1, 0), false.B)
        val countDecremented = Cat(false.B, countOH(p.rasDepth, 1))
        val nextCountOH = Mux(
            pushOnly && !countOH(p.rasDepth),
            countIncremented,
            Mux(popOnly, countDecremented, countOH),
        )
        (nextPointerOH, nextCountOH, pop, push)
    }

    def committedRasRowWrite(
        pointerOH: UInt,
        countOH: UInt,
        event: FrontendStateEvent,
        returnPc: UInt,
        row: Int,
    ): (Bool, UInt) = {
        val writeCurrent = (0 until p.fetchWidth).map { slot =>
            val kind = event.prediction.kinds(slot)
            event.prediction.taken(slot) && FrontendCfi.push(kind) &&
                (!FrontendCfi.pop(kind) || countOH(0))
        }
        val replacePrevious = (0 until p.fetchWidth).map { slot =>
            val kind = event.prediction.kinds(slot)
            event.prediction.taken(slot) && FrontendCfi.push(kind) &&
                FrontendCfi.pop(kind) && !countOH(0)
        }
        // The row enable owns whether this value is observed; select the PC
        // independently of the row's pointer/kind decode.
        (pointerOH(row) && VecInit(writeCurrent).asUInt.orR ||
            pointerOH((row + 1) % p.rasDepth) && VecInit(replacePrevious).asUInt.orR, returnPc)
    }

    def returnPcWord(event: FrontendStateEvent): UInt = {
        val slotBits = log2Ceil(p.fetchWidth)
        if (slotBits == 0) event.pcWord + 1.U
        else {
            val block = event.pcWord(29, slotBits)
            val nextBlock = (block + 1.U)(block.getWidth - 1, 0)
            val selected = event.prediction.taken.asBools
            val low = Mux1H(selected.dropRight(1),
                (1 until p.fetchWidth).map(_.U(slotBits.W)))
            Cat(Mux(selected.last, nextBlock, block), low)
        }
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
        val returnPcs = (0 until p.fetchWidth).map(slot =>
            FrontendMath.slotPc(Cat(event.pcWord, 0.U(2.W)), slot, p)(31, 2) + 1.U
        )
        val pushOnly = event.prediction.kinds.map(kind =>
            FrontendCfi.push(kind) && (!FrontendCfi.pop(kind) || before.count === 0.U)
        )
        val replaceAfterPop = event.prediction.kinds.map(kind =>
            FrontendCfi.push(kind) && FrontendCfi.pop(kind) && before.count =/= 0.U
        )
        // Select the return PC locally so taken does not fan through a full snapshot mux.
        for (row <- 0 until p.rasDepth) {
            val writeSlots = (0 until p.fetchWidth).map(slot =>
                taken(slot) && (
                    pointerOH(row) && pushOnly(slot) ||
                    pointerOH((row + 1) % p.rasDepth) && replaceAfterPop(slot)
                )
            )
            val writeAny = VecInit(writeSlots).asUInt.orR
            next.ras(row) := Mux(writeAny, Mux1H(writeSlots, returnPcs), before.ras(row))
        }
        next.top := Mux1H(selectors, candidates.map(_.top))
        next.pointer := Mux1H(selectors, candidates.map(_.pointer))
        next.count := Mux1H(selectors, candidates.map(_.count))
        next
    }

    /* Commit and Recovery Priority */
    // A global flush includes this cycle's retirement; PD repair overrides younger speculation.
    val retireValid = VecInit(io.retire.map(_.valid))
    val retireReturnPcs = (0 until 3).map(port => returnPcWord(io.retire(port).bits))
    val recoveryReturnPc = returnPcWord(io.recovery.bits)
    val normalTop = Wire(Vec(4, UInt(30.W)))
    val normalPointerOH = Wire(Vec(4, UInt(p.rasDepth.W)))
    val normalCountOH = Wire(Vec(4, UInt((p.rasDepth + 1).W)))
    val normalPop = Wire(Vec(3, Bool()))
    val normalPush = Wire(Vec(3, Bool()))
    normalTop(0) := committed.top
    normalPointerOH(0) := committedPointerOH
    normalCountOH(0) := committedCountOH
    for (port <- 0 until 3) {
        val (pointer, count, pop, push) = advanceCommittedControl(
            normalPointerOH(port), normalCountOH(port), io.retire(port).bits,
        )
        normalPointerOH(port + 1) := Mux(retireValid(port), pointer, normalPointerOH(port))
        normalCountOH(port + 1) := Mux(retireValid(port), count, normalCountOH(port))
        normalPop(port) := pop
        normalPush(port) := push
    }
    val normalWrites = (0 until 3).map { port =>
        (0 until p.rasDepth).map { row =>
            committedRasRowWrite(normalPointerOH(port), normalCountOH(port),
                io.retire(port).bits, retireReturnPcs(port), row)
        }
    }
    val (recoveryPointerOH, recoveryCountOH, recoveryPop, recoveryPush) = advanceCommittedControl(
        normalPointerOH(2), normalCountOH(2), io.recovery.bits,
    )
    val recoveryWrites = (0 until p.rasDepth).map { row =>
        committedRasRowWrite(normalPointerOH(2), normalCountOH(2),
            io.recovery.bits, recoveryReturnPc, row)
    }
    // A later return reads the committed stack with only earlier same-cycle
    // row writes forwarded. No intermediate full-stack copies are needed.
    def previousCommittedTop(pointerOH: UInt, earlierPorts: Seq[Int]): UInt = {
        val rowSelect = (0 until p.rasDepth).map(row => pointerOH((row + 2) % p.rasDepth))
        val base = Mux1H(rowSelect, committed.ras)
        earlierPorts.foldLeft(base) { (value, port) =>
            val hit = rowSelect.indices.map(row => rowSelect(row) && normalWrites(port)(row)._1)
                .reduce(_ || _) && retireValid(port)
            Mux(hit, retireReturnPcs(port), value)
        }
    }
    def committedTopAfter(
        before: UInt, countOH: UInt, pop: Bool, push: Bool, returnPc: UInt, previousTop: UInt,
    ): UInt = Mux(push, returnPc,
        Mux(pop, Mux(!countOH(0) && !countOH(1), previousTop, 0.U), before))

    for (port <- 0 until 3) {
        val advanced = committedTopAfter(normalTop(port), normalCountOH(port),
            normalPop(port), normalPush(port), retireReturnPcs(port),
            previousCommittedTop(normalPointerOH(port), 0 until port))
        normalTop(port + 1) := Mux(retireValid(port), advanced, normalTop(port))
    }
    // Recovery replaces the third normal retirement, after at most two packets.
    val recoveryTop = committedTopAfter(normalTop(2), normalCountOH(2),
        recoveryPop, recoveryPush, recoveryReturnPc,
        previousCommittedTop(normalPointerOH(2), 0 until 2))
    // History is independent of RAS pointer/count. Compute each legal event
    // prefix without valid muxes, then select once at the register input.
    val historyPrefixes = Wire(Vec(4, new FrontendStateSnapshot(p)))
    historyPrefixes(0) := committed
    for (port <- 0 until 3) {
        historyPrefixes(port + 1) := advanceHistory(
            historyPrefixes(port), io.retire(port).bits, io.retire(port).bits.prediction.taken,
        )
    }
    val recoveryHistories = (0 until 3).map { count =>
        advanceHistory(historyPrefixes(count), io.recovery.bits, io.recovery.bits.prediction.taken)
    }
    val normalHistorySelect = Seq(
        !retireValid(0),
        retireValid(0) && !retireValid(1),
        retireValid(1) && !retireValid(2),
        retireValid(2),
    ).map(_ && !io.recovery.valid)
    val recoveryHistorySelect = Seq(
        !retireValid(0),
        retireValid(0) && !retireValid(1),
        retireValid(1),
    ).map(_ && io.recovery.valid)
    val historySelect = normalHistorySelect ++ recoveryHistorySelect
    assert(PopCount(historySelect) === 1.U)
    val historyChoices = historyPrefixes.toSeq ++ recoveryHistories
    val committedNext = WireDefault(committed)
    committedNext.top := Mux(io.recovery.valid, recoveryTop, normalTop(3))
    val committedPointerOHNext = Mux(io.recovery.valid, recoveryPointerOH, normalPointerOH(3))
    val committedCountOHNext = Mux(io.recovery.valid, recoveryCountOH, normalCountOH(3))
    committedNext.pointer := OHToUInt(committedPointerOHNext)
    committedNext.count := OHToUInt(committedCountOHNext)
    committedNext.history := Mux1H(historySelect, historyChoices.map(_.history))
    for (fold <- 0 until p.tageCount) {
        committedNext.folds(fold) := Mux1H(historySelect, historyChoices.map(_.folds(fold)))
    }
    // The three normal packets and optional recovery have independent return
    // PCs. Only their row-write controls depend on earlier pointer transitions.
    for (row <- 0 until p.rasDepth) {
        val w0 = retireValid(0) && normalWrites(0)(row)._1
        val w1 = retireValid(1) && normalWrites(1)(row)._1
        val w2 = retireValid(2) && !io.recovery.valid && normalWrites(2)(row)._1
        val wr = io.recovery.valid && recoveryWrites(row)._1
        val selected = Seq(
            !(w0 || w1 || w2 || wr),
            w0 && !w1 && !w2 && !wr,
            w1 && !w2 && !wr,
            w2 && !wr,
            wr,
        )
        committedNext.ras(row) := Mux1H(
            selected,
            Seq(committed.ras(row)) ++ normalWrites.map(_(row)._2) :+ recoveryWrites(row)._2,
        )
    }
    // Keep flush as the final selector. It must not be decoded through every
    // normal speculative candidate before reaching the state registers.
    val repaired = advance(io.repair.bits.before, io.repair.bits.event)
    val earlyNext = advanceEarly(speculative, io.early.bits)
    val nonFlushNext = Mux(io.repair.valid, repaired, Mux(io.early.valid, earlyNext, speculative))
    val speculativeNext = WireDefault(Mux(io.flush, committedNext, nonFlushNext))
    val speculativeSelect = Seq(
        io.flush,
        !io.flush && io.repair.valid,
        !io.flush && !io.repair.valid && io.early.valid,
        !io.flush && !io.repair.valid && !io.early.valid,
    )
    speculativeNext.top := Mux1H(speculativeSelect,
        Seq(committedNext.top, repaired.top, earlyNext.top, speculative.top))
    for (row <- 0 until p.rasDepth) {
        speculativeNext.ras(row) := Mux1H(speculativeSelect,
            Seq(committedNext.ras(row), repaired.ras(row), earlyNext.ras(row), speculative.ras(row)))
    }
    assert(PopCount(VecInit(speculativeSelect)) === 1.U)
    when(retireValid.asUInt.orR || io.recovery.valid) {
        committed := committedNext
        committedPointerOH := committedPointerOHNext
        committedCountOH := committedCountOHNext
    }
    speculative := speculativeNext

    /* Current Prediction State */
    io.snapshot := speculative
    io.folds := speculative.folds
    assert(!retireValid(1) || retireValid(0))
    assert(!retireValid(2) || retireValid(1))
    assert(!io.recovery.valid || !retireValid(2))
    for (port <- 0 until 3) {
        when(retireValid(port)) { assert(PopCount(io.retire(port).bits.prediction.taken) <= 1.U) }
    }
    when(io.recovery.valid) { assert(PopCount(io.recovery.bits.prediction.taken) <= 1.U) }
    assert(PopCount(committedPointerOH) === 1.U)
    assert(PopCount(committedCountOH) === 1.U)
    when(io.early.valid) { assert(PopCount(io.early.bits.prediction.taken) <= 1.U) }
    assert(speculative.count <= p.rasDepth.U && committed.count <= p.rasDepth.U)
}
