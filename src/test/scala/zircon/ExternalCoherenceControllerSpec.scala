package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.{ExternalCoherenceController, ExternalCoherenceKind}

class ExternalCoherenceControllerSpec extends AnyFunSpec with ChiselSim {
  private val Line = BigInt("80001000", 16)
  private val SecondLine = BigInt("80002000", 16)

  private def clear(dut: ExternalCoherenceController): Unit = {
    dut.io.request.valid.poke(false)
    dut.io.request.bits.kind.poke(ExternalCoherenceKind.WriteInvalidate)
    dut.io.request.bits.lineAddress.poke(0)
    dut.io.response.ready.poke(false)
    dut.io.l1dCleanup.ready.poke(false)
    dut.io.instructionDrained.poke(true)
    dut.io.l2Cleanup.ready.poke(false)
    dut.io.l2CleanupDirty.poke(false)
    dut.io.writebackComplete.valid.poke(false)
    dut.io.writebackComplete.bits.poke(0)
  }

  private def offer(
      dut: ExternalCoherenceController,
      kind: BigInt,
      line: BigInt = Line
  ): Unit = {
    dut.io.request.valid.poke(true)
    dut.io.request.bits.kind.poke(kind)
    dut.io.request.bits.lineAddress.poke(line)
  }

  private def advanceClean(
      dut: ExternalCoherenceController,
      l2Dirty: Boolean
  ): Unit = {
    dut.io.l1dCleanup.valid.expect(true)
    dut.io.l1dCleanup.bits.expect(Line)
    dut.io.l1dCleanup.ready.poke(true)
    dut.clock.step()
    dut.io.l1dCleanup.ready.poke(false)
    dut.io.l2Cleanup.valid.expect(true)
    dut.io.l2Cleanup.bits.expect(Line)
    dut.io.l2CleanupDirty.poke(l2Dirty)
    dut.io.l2Cleanup.ready.poke(true)
    dut.clock.step()
    dut.io.l2Cleanup.ready.poke(false)
    dut.io.l2CleanupDirty.poke(false)
  }

  describe("ExternalCoherenceController") {
    it("holds an acknowledgement under backpressure before accepting a replacement") {
      simulate(new ExternalCoherenceController) { dut =>
        clear(dut)
        offer(dut, ExternalCoherenceKind.AtomicInvalidate.litValue)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        advanceClean(dut, l2Dirty = false)

        // The invalidate cycle precedes the retained response state.
        dut.clock.step()
        dut.io.response.valid.expect(true)
        dut.io.response.bits.kind.expect(ExternalCoherenceKind.AtomicInvalidate)
        dut.io.response.bits.lineAddress.expect(Line)
        dut.io.cacheableIngressBlocked.expect(true)

        // A correct replacement request remains blocked while the first
        // response is held, and cannot perturb the retained acknowledgement.
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue,
          BigInt("80002000", 16))
        dut.io.request.ready.expect(false)
        dut.clock.step(2)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.kind.expect(ExternalCoherenceKind.AtomicInvalidate)
        dut.io.response.bits.lineAddress.expect(Line)
        dut.io.cacheableIngressBlocked.expect(true)

        // Remove the pending offer before consuming the response so this
        // cycle demonstrates the actual idle window. If it remained valid,
        // `cacheableIngressBlocked` intentionally includes its next-cycle
        // request fire.
        dut.io.request.valid.poke(false)
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)
        dut.io.request.ready.expect(true)
        dut.io.cacheableIngressBlocked.expect(false)

        // Re-offering the replacement now accepts it as a distinct request.
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue,
          BigInt("80002000", 16))
        dut.io.request.ready.expect(true)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        dut.io.l1dCleanup.valid.expect(true)
        dut.io.l1dCleanup.bits.expect(BigInt("80002000", 16))
      }
    }

    it("waits for an accepted instruction owner before touching data state") {
      simulate(new ExternalCoherenceController) { dut =>
        clear(dut)
        dut.io.instructionDrained.poke(false)
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        dut.io.l1dCleanup.valid.expect(false)
        dut.io.cacheableIngressBlocked.expect(true)
        dut.io.instructionDrained.poke(true)
        dut.io.l1dCleanup.valid.expect(true)
      }
    }

    it("serializes a clean write-invalidate before its exact response") {
      simulate(new ExternalCoherenceController) { dut =>
        clear(dut)
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue)
        dut.io.request.ready.expect(true)
        dut.io.cacheableIngressBlocked.expect(true)
        dut.clock.step()
        dut.io.request.valid.poke(false)

        advanceClean(dut, l2Dirty = false)
        dut.io.l1iInvalidate.expect(true)
        dut.io.reservationInvalidateLine.valid.expect(true)
        dut.io.reservationInvalidateLine.bits.expect(Line)
        dut.io.response.valid.expect(false)
        dut.clock.step()

        dut.io.response.valid.expect(true)
        dut.io.response.bits.kind.expect(ExternalCoherenceKind.WriteInvalidate)
        dut.io.response.bits.lineAddress.expect(Line)
        dut.io.cacheableIngressBlocked.expect(true)
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)
        dut.io.request.ready.expect(true)
        dut.io.cacheableIngressBlocked.expect(false)
      }
    }

    it("waits for the matching dirty-line writeback and retains one request") {
      simulate(new ExternalCoherenceController) { dut =>
        clear(dut)
        offer(dut, ExternalCoherenceKind.AtomicInvalidate.litValue)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        advanceClean(dut, l2Dirty = true)

        dut.io.response.valid.expect(false)
        dut.io.l1iInvalidate.expect(false)
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue,
          BigInt("80002000", 16))
        dut.io.request.ready.expect(false)
        dut.io.writebackComplete.valid.poke(true)
        dut.io.writebackComplete.bits.poke(Line + 32)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        dut.io.writebackComplete.valid.poke(false)
        dut.io.l1iInvalidate.expect(false)
        dut.io.response.valid.expect(false)

        dut.io.writebackComplete.valid.poke(true)
        dut.io.writebackComplete.bits.poke(Line)
        dut.clock.step()
        dut.io.writebackComplete.valid.poke(false)
        dut.io.l1iInvalidate.expect(true)
        dut.io.reservationInvalidateLine.valid.expect(true)
        dut.clock.step()
        dut.io.response.valid.expect(true)
        dut.io.response.bits.kind.expect(ExternalCoherenceKind.AtomicInvalidate)
        dut.io.response.bits.lineAddress.expect(Line)
      }
    }

    it("drops a dirty cleanup epoch on reset before accepting a fresh request") {
      simulate(new ExternalCoherenceController) { dut =>
        clear(dut)
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        advanceClean(dut, l2Dirty = true)
        dut.io.response.valid.expect(false)

        // A reset between cleanup and the ID-5 completion discards the held
        // request. A stale completion from that epoch must not authorize the
        // next modifier.
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
        dut.io.response.valid.expect(false)
        dut.io.request.ready.expect(true)
        dut.io.writebackComplete.valid.poke(true)
        dut.io.writebackComplete.bits.poke(Line)
        dut.clock.step()
        dut.io.writebackComplete.valid.poke(false)
        dut.io.response.valid.expect(false)

        offer(dut, ExternalCoherenceKind.AtomicInvalidate.litValue, SecondLine)
        dut.io.request.ready.expect(true)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        dut.io.l1dCleanup.valid.expect(true)
        dut.io.l1dCleanup.bits.expect(SecondLine)
        dut.io.l1dCleanup.ready.poke(true)
        dut.clock.step()
        dut.io.l1dCleanup.ready.poke(false)
        dut.io.l2Cleanup.valid.expect(true)
        dut.io.l2Cleanup.bits.expect(SecondLine)
        dut.io.l2CleanupDirty.poke(false)
        dut.io.l2Cleanup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Cleanup.ready.poke(false)
        dut.clock.step()
        dut.io.response.valid.expect(true)
        dut.io.response.bits.kind.expect(ExternalCoherenceKind.AtomicInvalidate)
        dut.io.response.bits.lineAddress.expect(SecondLine)
      }
    }
  }
}
