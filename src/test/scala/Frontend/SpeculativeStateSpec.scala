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
}
