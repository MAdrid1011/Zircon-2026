import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class SpeculativeStateSpec extends AnyFreeSpec with ChiselSim {
    private val p = FrontendParams(fetchWidth = 4, rasDepth = 4)
    private val pointerMask = p.rasDepth - 1

    private case class RasState(ras: Vector[BigInt], top: BigInt, pointer: Int, count: Int)

    private def push(kind: Int): Boolean =
        kind == FrontendCfi.Call || kind == FrontendCfi.IndirectCall || kind == FrontendCfi.Coroutine

    private def pop(kind: Int): Boolean = kind == FrontendCfi.Return || kind == FrontendCfi.Coroutine

    private def advance(before: RasState, pcWord: BigInt, taken: Int, kinds: Seq[Int]): RasState = {
        val slot = (0 until p.fetchWidth).find(i => (taken & (1 << i)) != 0)
        val kind = slot.map(kinds).getOrElse(FrontendCfi.None)
        val doPop = slot.nonEmpty && pop(kind) && before.count != 0
        val doPush = slot.nonEmpty && push(kind)
        val pushOnly = doPush && !doPop
        val popOnly = doPop && !doPush
        val returnPc = slot.map(i => (pcWord & ~BigInt(pointerMask)) + i + 1).getOrElse(BigInt(0))
        val previousTop = before.ras((before.pointer - 2) & pointerMask)
        val nextTop = if (doPush) returnPc else if (doPop) {
            if (before.count > 1) previousTop else BigInt(0)
        } else before.top
        val nextPointer = if (pushOnly) (before.pointer + 1) & pointerMask
            else if (popOnly) (before.pointer - 1) & pointerMask else before.pointer
        val nextCount = if (pushOnly && before.count < p.rasDepth) before.count + 1
            else if (popOnly) before.count - 1 else before.count
        val nextRas = before.ras.toArray
        if (pushOnly) nextRas(before.pointer) = returnPc
        else if (doPush && doPop) nextRas((before.pointer - 1) & pointerMask) = returnPc
        RasState(nextRas.toVector, nextTop, nextPointer, nextCount)
    }

    private def clearInputs(d: SpeculativeState): Unit = {
        d.io.early.valid.poke(false)
        d.io.early.bits.poke(0.U.asTypeOf(new FrontendStateEvent(p)))
        d.io.repair.valid.poke(false)
        d.io.repair.bits.poke(0.U.asTypeOf(new FrontendStateRepair(p)))
        d.io.retire.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0.U.asTypeOf(new FrontendStateEvent(p)))
        }
        d.io.recovery.valid.poke(false)
        d.io.recovery.bits.poke(0.U.asTypeOf(new FrontendStateEvent(p)))
        d.io.flush.poke(false)
    }

    private def initialize(d: SpeculativeState): Unit = {
        clearInputs(d)
        d.reset.poke(true)
        d.clock.step(2)
        d.reset.poke(false)
    }

    private def pokeEvent(event: FrontendStateEvent, pcWord: BigInt, taken: Int, kinds: Seq[Int]): Unit = {
        event.poke(0.U.asTypeOf(new FrontendStateEvent(p)))
        event.pcWord.poke(pcWord.U)
        event.prediction.mask.poke(((1 << p.fetchWidth) - 1).U)
        event.prediction.taken.poke(taken.U)
        event.prediction.kinds.zip(kinds).foreach { case (port, kind) => port.poke(kind.U) }
    }

    private def pokeSnapshot(snapshot: FrontendStateSnapshot, state: RasState): Unit = {
        snapshot.poke(0.U.asTypeOf(new FrontendStateSnapshot(p)))
        snapshot.ras.zip(state.ras).foreach { case (port, value) => port.poke(value.U) }
        snapshot.top.poke(state.top.U)
        snapshot.pointer.poke(state.pointer.U)
        snapshot.count.poke(state.count.U)
    }

    private def expectState(d: SpeculativeState, state: RasState): Unit = {
        d.io.snapshot.ras.zip(state.ras).foreach { case (port, value) => port.expect(value.U) }
        d.io.snapshot.top.expect(state.top.U)
        d.io.snapshot.pointer.expect(state.pointer.U)
        d.io.snapshot.count.expect(state.count.U)
    }

    "early candidates match the RAS reference model across empty, full, and wraparound states" in {
        simulate(new SpeculativeState(p)) { d =>
            initialize(d)
            val rng = new scala.util.Random(20260923)
            var expected = RasState(Vector.fill(p.rasDepth)(BigInt(0)), 0, 0, 0)
            for (_ <- 0 until 300) {
                clearInputs(d)
                val pcWord = BigInt("20000000", 16) + rng.nextInt(4096)
                val selected = rng.nextInt(p.fetchWidth + 1) - 1
                val taken = if (selected < 0) 0 else 1 << selected
                val kinds = Vector.fill(p.fetchWidth)(rng.nextInt(8))
                pokeEvent(d.io.early.bits, pcWord, taken, kinds)
                d.io.early.valid.poke(true)
                expected = advance(expected, pcWord, taken, kinds)
                d.clock.step()
                expectState(d, expected)
            }
        }
    }

    "default-depth RAS retains each return address across pointer wraparound" in {
        val wide = FrontendParams(fetchWidth = 4, rasDepth = 16)
        simulate(new SpeculativeState(wide)) { d =>
            d.io.early.valid.poke(false)
            d.io.early.bits.poke(0.U.asTypeOf(new FrontendStateEvent(wide)))
            d.io.repair.valid.poke(false)
            d.io.repair.bits.poke(0.U.asTypeOf(new FrontendStateRepair(wide)))
            d.io.retire.foreach { port =>
                port.valid.poke(false)
                port.bits.poke(0.U.asTypeOf(new FrontendStateEvent(wide)))
            }
            d.io.recovery.valid.poke(false)
            d.io.recovery.bits.poke(0.U.asTypeOf(new FrontendStateEvent(wide)))
            d.io.flush.poke(false)
            d.reset.poke(true)
            d.clock.step(2)
            d.reset.poke(false)

            var expected = Vector.fill(wide.rasDepth)(BigInt(0))
            for (cycle <- 0 until wide.rasDepth + 4) {
                val slot = cycle % wide.fetchWidth
                val pcWord = BigInt("10000000", 16) + cycle * wide.fetchWidth
                val returnPc = pcWord + slot + 1
                d.io.early.bits.poke(0.U.asTypeOf(new FrontendStateEvent(wide)))
                d.io.early.bits.pcWord.poke(pcWord.U)
                d.io.early.bits.prediction.mask.poke(15.U)
                d.io.early.bits.prediction.taken.poke((1 << slot).U)
                d.io.early.bits.prediction.kinds(slot).poke(FrontendCfi.Call.U)
                d.io.early.valid.poke(true)
                d.clock.step()
                expected = expected.updated(cycle % wide.rasDepth, returnPc)
                d.io.snapshot.ras.zip(expected).foreach { case (row, value) => row.expect(value.U) }
                d.io.snapshot.top.expect(returnPc.U)
                d.io.snapshot.pointer.expect(((cycle + 1) % wide.rasDepth).U)
                d.io.snapshot.count.expect(math.min(cycle + 1, wide.rasDepth).U)
            }
        }
    }

    "repair and flush retain their priority and flush includes same-cycle retirement" in {
        simulate(new SpeculativeState(p)) { d =>
            initialize(d)
            val zero = RasState(Vector.fill(p.rasDepth)(BigInt(0)), 0, 0, 0)
            val call0 = Seq(FrontendCfi.Call, 0, 0, 0)
            val call2 = Seq(0, 0, FrontendCfi.IndirectCall, 0)

            pokeEvent(d.io.retire(0).bits, BigInt("20000010", 16), 1, call0)
            d.io.retire(0).valid.poke(true)
            pokeEvent(d.io.retire(1).bits, BigInt("20000020", 16), 4, call2)
            d.io.retire(1).valid.poke(true)
            pokeEvent(d.io.early.bits, BigInt("20000030", 16), 1, call0)
            d.io.early.valid.poke(true)
            val committed2 = advance(advance(zero, BigInt("20000010", 16), 1, call0),
                BigInt("20000020", 16), 4, call2)
            val earlyOnly = advance(zero, BigInt("20000030", 16), 1, call0)
            d.clock.step()
            expectState(d, earlyOnly)

            clearInputs(d)
            pokeEvent(d.io.retire(0).bits, BigInt("20000040", 16), 1, call0)
            d.io.retire(0).valid.poke(true)
            pokeEvent(d.io.early.bits, BigInt("20000050", 16), 4, call2)
            d.io.early.valid.poke(true)
            d.io.repair.valid.poke(true)
            d.io.flush.poke(true)
            val committed3 = advance(committed2, BigInt("20000040", 16), 1, call0)
            d.clock.step()
            expectState(d, committed3)

            clearInputs(d)
            val retireEvents = Seq(
                (BigInt("20000080", 16), 1, call0),
                (BigInt("20000090", 16), 4, call2),
                (BigInt("200000a0", 16), 1, call0),
            )
            retireEvents.zip(d.io.retire).foreach { case ((pc, taken, kinds), port) =>
                pokeEvent(port.bits, pc, taken, kinds)
                port.valid.poke(true)
            }
            d.io.flush.poke(true)
            val committed6 = retireEvents.foldLeft(committed3) {
                case (state, (pc, taken, kinds)) => advance(state, pc, taken, kinds)
            }
            d.clock.step()
            expectState(d, committed6)

            var committedWithRecovery = committed6
            for (normalCount <- 0 to 2) {
                clearInputs(d)
                val normalEvents = (0 until normalCount).map { lane =>
                    (BigInt("200000b0", 16) + normalCount * 16 + lane * 4, 1, call0)
                }
                normalEvents.zip(d.io.retire).foreach { case ((pc, taken, kinds), port) =>
                    pokeEvent(port.bits, pc, taken, kinds)
                    port.valid.poke(true)
                }
                val recoveryPc = BigInt("20000100", 16) + normalCount * 16
                pokeEvent(d.io.recovery.bits, recoveryPc, 4, call2)
                d.io.recovery.valid.poke(true)
                d.io.flush.poke(true)
                committedWithRecovery = normalEvents.foldLeft(committedWithRecovery) {
                    case (state, (pc, taken, kinds)) => advance(state, pc, taken, kinds)
                }
                committedWithRecovery = advance(committedWithRecovery, recoveryPc, 4, call2)
                d.clock.step()
                expectState(d, committedWithRecovery)
            }

            clearInputs(d)
            val repairBefore = RasState(Vector(11, 22, 33, 44).map(BigInt(_)), 22, 2, 2)
            val coroutine = Seq(0, FrontendCfi.Coroutine, 0, 0)
            pokeSnapshot(d.io.repair.bits.before, repairBefore)
            pokeEvent(d.io.repair.bits.event, BigInt("20000060", 16), 2, coroutine)
            d.io.repair.valid.poke(true)
            pokeEvent(d.io.early.bits, BigInt("20000070", 16), 1, call0)
            d.io.early.valid.poke(true)
            val repaired = advance(repairBefore, BigInt("20000060", 16), 2, coroutine)
            d.clock.step()
            expectState(d, repaired)

            clearInputs(d)
            d.clock.step()
            expectState(d, repaired)
        }
    }

    "committed history selects the exact retirement prefix before recovery" in {
        simulate(new SpeculativeState(p)) { d =>
            initialize(d)
            val historyMask = (BigInt(1) << p.historyBits) - 1
            val foldMask = (BigInt(1) << p.hashBits) - 1
            val stepMask = (BigInt(1) << p.historyStep) - 1
            def fold(value: BigInt, bits: Int, width: Int): BigInt =
                (0 until bits by width).map(offset =>
                    (value >> offset) & ((BigInt(1) << width) - 1)
                ).reduce(_ ^ _)
            def rotate(value: BigInt, amount: Int): BigInt = {
                val shift = amount % p.hashBits
                if (shift == 0) value else ((value << shift) | (value >> (p.hashBits - shift))) & foldMask
            }
            def advanceHistory(
                before: (BigInt, Vector[BigInt]),
                pcWord: BigInt,
                taken: Int,
                kinds: Seq[Int],
            ): (BigInt, Vector[BigInt]) = {
                val control = kinds.zipWithIndex.collect { case (kind, slot) if kind != 0 => 1 << slot }.foldLeft(0)(_ | _)
                val signature = (fold(pcWord, 30, p.historyStep) ^ control ^ taken) & stepMask
                val nextFolds = p.historyLengths.zipWithIndex.map { case (blocks, index) =>
                    val length = blocks * p.historyStep
                    val outgoing = (before._1 >> (length - p.historyStep)) & stepMask
                    rotate(before._2(index), p.historyStep) ^
                        rotate(fold(outgoing, p.historyStep, p.hashBits), length) ^
                        fold(signature, p.historyStep, p.hashBits)
                }.toVector
                (((before._1 << p.historyStep) | signature) & historyMask, nextFolds)
            }

            val rng = new scala.util.Random(20260926)
            var expected = (BigInt(0), Vector.fill(p.tageCount)(BigInt(0)))
            var expectedRas = RasState(Vector.fill(p.rasDepth)(BigInt(0)), 0, 0, 0)
            for (cycle <- 0 until 80) {
                clearInputs(d)
                val normalCount = cycle % 4
                val recovery = cycle % 5 == 0 && normalCount <= 2
                for (port <- 0 until 3) {
                    val pcWord = BigInt(rng.nextInt(1 << 28))
                    val selected = rng.nextInt(p.fetchWidth + 1) - 1
                    val taken = if (selected < 0) 0 else 1 << selected
                    val kinds = Vector.fill(p.fetchWidth)(rng.nextInt(8))
                    pokeEvent(d.io.retire(port).bits, pcWord, taken, kinds)
                    d.io.retire(port).valid.poke(port < normalCount)
                    if (port < normalCount) {
                        expected = advanceHistory(expected, pcWord, taken, kinds)
                        expectedRas = advance(expectedRas, pcWord, taken, kinds)
                    }
                }
                val recoveryPc = BigInt(rng.nextInt(1 << 28))
                val recoveryKinds = Vector.fill(p.fetchWidth)(rng.nextInt(8))
                pokeEvent(d.io.recovery.bits, recoveryPc, 1, recoveryKinds)
                d.io.recovery.valid.poke(recovery)
                if (recovery) {
                    expected = advanceHistory(expected, recoveryPc, 1, recoveryKinds)
                    expectedRas = advance(expectedRas, recoveryPc, 1, recoveryKinds)
                }
                d.io.flush.poke(true)
                d.clock.step()
                expectState(d, expectedRas)
                d.io.snapshot.history.expect(expected._1.U)
                d.io.snapshot.folds.zip(expected._2).foreach { case (actual, value) => actual.expect(value.U) }
            }
        }
    }
}
