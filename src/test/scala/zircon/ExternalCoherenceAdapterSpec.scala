package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.ExternalCoherenceKind
import zircon.platform.ExternalCoherenceAdapter

class ExternalCoherenceAdapterSpec extends AnyFunSpec with ChiselSim {
  private val FirstLine = BigInt("80001000", 16)
  private val SecondLine = BigInt("80002000", 16)

  private def clear(dut: ExternalCoherenceAdapter): Unit = {
    dut.io.modifier.valid.poke(false)
    dut.io.modifier.bits.kind.poke(ExternalCoherenceKind.WriteInvalidate)
    dut.io.modifier.bits.lineAddress.poke(0)
    dut.io.core.request.ready.poke(false)
    dut.io.core.response.valid.poke(false)
    dut.io.core.response.bits.kind.poke(ExternalCoherenceKind.WriteInvalidate)
    dut.io.core.response.bits.lineAddress.poke(0)
    dut.io.authorized.ready.poke(false)
  }

  private def offer(dut: ExternalCoherenceAdapter, kind: BigInt, line: BigInt): Unit = {
    dut.io.modifier.valid.poke(true)
    dut.io.modifier.bits.kind.poke(kind)
    dut.io.modifier.bits.lineAddress.poke(line)
  }

  private def acceptCoreRequest(dut: ExternalCoherenceAdapter, kind: BigInt, line: BigInt): Unit = {
    dut.io.core.request.valid.expect(true)
    dut.io.core.request.bits.kind.expect(kind)
    dut.io.core.request.bits.lineAddress.expect(line)
    dut.io.core.request.ready.poke(true)
    dut.clock.step()
    dut.io.core.request.ready.poke(false)
  }

  private def respond(dut: ExternalCoherenceAdapter, kind: BigInt, line: BigInt): Unit = {
    dut.io.core.response.ready.expect(true)
    dut.io.core.response.valid.poke(true)
    dut.io.core.response.bits.kind.poke(kind)
    dut.io.core.response.bits.lineAddress.poke(line)
    dut.clock.step()
    dut.io.core.response.valid.poke(false)
  }

  describe("ExternalCoherenceAdapter") {
    it("holds an external modifier until the matching core acknowledgement") {
      simulate(new ExternalCoherenceAdapter) { dut =>
        clear(dut)
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue, FirstLine)
        dut.io.modifier.ready.expect(true)
        dut.io.authorized.valid.expect(false)
        dut.clock.step()
        dut.io.modifier.valid.poke(false)

        dut.io.core.request.valid.expect(true)
        dut.io.core.request.bits.lineAddress.expect(FirstLine)
        dut.io.authorized.valid.expect(false)
        dut.clock.step()
        dut.io.core.request.valid.expect(true)
        acceptCoreRequest(dut, ExternalCoherenceKind.WriteInvalidate.litValue, FirstLine)

        dut.io.authorized.valid.expect(false)
        respond(dut, ExternalCoherenceKind.WriteInvalidate.litValue, FirstLine)
        dut.io.authorized.valid.expect(true)
        dut.io.authorized.bits.kind.expect(ExternalCoherenceKind.WriteInvalidate)
        dut.io.authorized.bits.lineAddress.expect(FirstLine)
        dut.clock.step()
        dut.io.authorized.valid.expect(true)
        dut.io.authorized.ready.poke(true)
        dut.clock.step()
        dut.io.authorized.ready.poke(false)
        dut.io.modifier.ready.expect(true)
      }
    }

    it("drops an unacknowledged request on reset and accepts a fresh modifier") {
      simulate(new ExternalCoherenceAdapter) { dut =>
        clear(dut)
        offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue, FirstLine)
        dut.clock.step()
        dut.io.modifier.valid.poke(false)
        dut.io.core.request.valid.expect(true)

        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
        dut.io.core.request.valid.expect(false)
        dut.io.authorized.valid.expect(false)
        dut.io.modifier.ready.expect(true)

        offer(dut, ExternalCoherenceKind.AtomicInvalidate.litValue, SecondLine)
        dut.clock.step()
        dut.io.modifier.valid.poke(false)
        acceptCoreRequest(dut, ExternalCoherenceKind.AtomicInvalidate.litValue, SecondLine)
        respond(dut, ExternalCoherenceKind.AtomicInvalidate.litValue, SecondLine)
        dut.io.authorized.valid.expect(true)
        dut.io.authorized.bits.kind.expect(ExternalCoherenceKind.AtomicInvalidate)
        dut.io.authorized.bits.lineAddress.expect(SecondLine)
      }
    }

    it("rejects a core acknowledgement that does not match the held modifier") {
      assertThrows[Throwable] {
        simulate(new ExternalCoherenceAdapter) { dut =>
          clear(dut)
          offer(dut, ExternalCoherenceKind.WriteInvalidate.litValue, FirstLine)
          dut.clock.step()
          dut.io.modifier.valid.poke(false)
          acceptCoreRequest(dut, ExternalCoherenceKind.WriteInvalidate.litValue, FirstLine)

          // A response for another line must fail before the retained modifier
          // can become externally authorized.
          dut.io.core.response.valid.poke(true)
          dut.io.core.response.bits.kind.poke(ExternalCoherenceKind.WriteInvalidate)
          dut.io.core.response.bits.lineAddress.poke(SecondLine)
          dut.io.core.response.ready.expect(true)
          dut.clock.step()
        }
      }
    }
  }
}
