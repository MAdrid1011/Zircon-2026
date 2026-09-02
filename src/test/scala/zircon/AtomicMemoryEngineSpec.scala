package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.IntOperation
import zircon.memory.AtomicMemoryEngine

class AtomicMemoryEngineSpec extends AnyFunSpec with ChiselSim {
  private val WordAddress = BigInt("80001000", 16)

  private def clear(dut: AtomicMemoryEngine): Unit = {
    dut.io.effect.valid.poke(false)
    dut.io.effect.bits.robTag.poke(0)
    dut.io.effect.bits.operation.poke(IntOperation.LrW.asUInt.litValue)
    dut.io.effect.bits.address.poke(0)
    dut.io.effect.bits.writeData.poke(0)
    dut.io.effect.bits.writeMask.poke(15)
    dut.io.effect.bits.destinationPhysical.poke(32)
    dut.io.effect.bits.writesInteger.poke(true)
    dut.io.effect.bits.aq.poke(false)
    dut.io.effect.bits.rl.poke(false)
    dut.io.result.ready.poke(true)
    dut.io.invalidate.valid.poke(false)
    dut.io.invalidate.bits.poke(0)
    dut.io.flush.poke(false)
    dut.io.ar.ready.poke(true)
    dut.io.r.valid.poke(false)
    dut.io.r.bits.id.poke(7)
    dut.io.r.bits.data.poke(0)
    dut.io.r.bits.resp.poke(0)
    dut.io.r.bits.last.poke(true)
    dut.io.aw.ready.poke(true)
    dut.io.w.ready.poke(true)
    dut.io.b.valid.poke(false)
    dut.io.b.bits.id.poke(7)
    dut.io.b.bits.resp.poke(0)
  }

  private def offer(
      dut: AtomicMemoryEngine,
      operation: IntOperation.Type,
      tag: Int,
      address: BigInt = WordAddress,
      writeData: BigInt = 0,
      destination: Int = 32
  ): Unit = {
    dut.io.effect.bits.robTag.poke(tag)
    dut.io.effect.bits.operation.poke(operation.asUInt.litValue)
    dut.io.effect.bits.address.poke(address)
    dut.io.effect.bits.writeData.poke(writeData)
    dut.io.effect.bits.writeMask.poke(15)
    dut.io.effect.bits.destinationPhysical.poke(destination)
    dut.io.effect.bits.writesInteger.poke(true)
    dut.io.effect.valid.poke(true)
    dut.io.effect.ready.expect(true)
    dut.clock.step()
    dut.io.effect.valid.poke(false)
  }

  private def acceptRead(dut: AtomicMemoryEngine, data: BigInt, resp: Int = 0): Unit = {
    dut.io.ar.valid.expect(true)
    dut.io.ar.bits.id.expect(7)
    dut.io.ar.bits.addr.expect(WordAddress)
    dut.io.ar.bits.len.expect(0)
    dut.io.ar.bits.size.expect(2)
    dut.clock.step()
    dut.io.r.valid.poke(true)
    dut.io.r.bits.id.poke(7)
    dut.io.r.bits.data.poke(data)
    dut.io.r.bits.resp.poke(resp)
    dut.io.r.bits.last.poke(true)
    dut.io.r.ready.expect(true)
    dut.clock.step()
    dut.io.r.valid.poke(false)
  }

  private def acceptWrite(dut: AtomicMemoryEngine, expected: BigInt, resp: Int = 0): Unit = {
    dut.io.aw.valid.expect(true)
    dut.io.aw.bits.id.expect(7)
    dut.io.aw.bits.addr.expect(WordAddress)
    dut.io.aw.bits.len.expect(0)
    dut.io.aw.bits.size.expect(2)
    dut.io.w.valid.expect(true)
    dut.io.w.bits.data.expect(expected)
    dut.io.w.bits.strb.expect(15)
    dut.io.w.bits.last.expect(true)
    dut.clock.step()
    // AW and W may handshake independently; this deterministic slave accepts both.
    dut.clock.step()
    dut.io.b.valid.poke(true)
    dut.io.b.bits.id.poke(7)
    dut.io.b.bits.resp.poke(resp)
    dut.io.b.ready.expect(true)
    dut.clock.step()
    dut.io.b.valid.poke(false)
  }

  private def consumeResult(
      dut: AtomicMemoryEngine,
      tag: Int,
      data: BigInt,
      fault: Boolean = false,
      storePerformed: Boolean = false
  ): Unit = {
    dut.io.result.valid.expect(true)
    dut.io.result.bits.robTag.expect(tag)
    dut.io.result.bits.data.expect(data)
    dut.io.result.bits.accessFault.expect(fault)
    dut.io.result.bits.storePerformed.expect(storePerformed)
    dut.clock.step()
  }

  describe("AtomicMemoryEngine") {
    it("installs an LR reservation only after the exact one-beat AXI read completes") {
      simulate(new AtomicMemoryEngine) { dut =>
        clear(dut)
        offer(dut, IntOperation.LrW, tag = 3)
        dut.io.reservationLive.expect(false)
        acceptRead(dut, BigInt("deadc0de", 16))
        consumeResult(dut, tag = 3, data = BigInt("deadc0de", 16))
        dut.io.reservationLive.expect(true)
      }
    }

    it("returns SC failure without AXI traffic and consumes the reservation") {
      simulate(new AtomicMemoryEngine) { dut =>
        clear(dut)
        offer(dut, IntOperation.LrW, tag = 1)
        acceptRead(dut, BigInt("11112222", 16))
        consumeResult(dut, tag = 1, data = BigInt("11112222", 16))
        offer(dut, IntOperation.ScW, tag = 2, address = WordAddress + 4,
          writeData = BigInt("a5a5a5a5", 16), destination = 33)
        dut.io.ar.valid.expect(false)
        dut.io.aw.valid.expect(false)
        consumeResult(dut, tag = 2, data = 1)
        dut.io.reservationLive.expect(false)
      }
    }

    it("waits for SC B before reporting a successful store") {
      simulate(new AtomicMemoryEngine) { dut =>
        clear(dut)
        offer(dut, IntOperation.LrW, tag = 1)
        acceptRead(dut, BigInt("01020304", 16))
        consumeResult(dut, tag = 1, data = BigInt("01020304", 16))
        offer(dut, IntOperation.ScW, tag = 2, writeData = BigInt("cafebabe", 16),
          destination = 33)
        acceptWrite(dut, BigInt("cafebabe", 16))
        consumeResult(dut, tag = 2, data = 0, storePerformed = true)
      }
    }

    it("accepts AW before a separately scheduled W beat") {
      simulate(new AtomicMemoryEngine) { dut =>
        clear(dut)
        offer(dut, IntOperation.LrW, tag = 1)
        acceptRead(dut, BigInt("01020304", 16))
        consumeResult(dut, tag = 1, data = BigInt("01020304", 16))

        // AXI4 has no WID. The top-level scheduler can legitimately accept
        // AW first, then grant the matching W beat in the following cycle.
        dut.io.w.ready.poke(false)
        offer(dut, IntOperation.ScW, tag = 2, writeData = BigInt("cafebabe", 16),
          destination = 33)
        dut.io.aw.valid.expect(true)
        dut.io.w.valid.expect(true)
        dut.io.aw.ready.expect(true)
        dut.clock.step()
        dut.io.aw.valid.expect(false)
        dut.io.w.valid.expect(true)
        dut.io.w.bits.data.expect(BigInt("cafebabe", 16))
        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.clock.step()

        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(7)
        dut.io.b.bits.resp.poke(0)
        dut.io.b.ready.expect(true)
        dut.clock.step()
        dut.io.b.valid.poke(false)
        consumeResult(dut, tag = 2, data = 0, storePerformed = true)
      }
    }

    it("computes every AMO word result before issuing its response-gated write") {
      val oldValue = BigInt("80000003", 16)
      val operand = BigInt("00000005", 16)
      val cases = Seq(
        IntOperation.AmoSwapW -> operand,
        IntOperation.AmoAddW -> BigInt("80000008", 16),
        IntOperation.AmoXorW -> BigInt("80000006", 16),
        IntOperation.AmoAndW -> BigInt("00000001", 16),
        IntOperation.AmoOrW -> BigInt("80000007", 16),
        IntOperation.AmoMinW -> oldValue,
        IntOperation.AmoMaxW -> operand,
        IntOperation.AmoMinuW -> operand,
        IntOperation.AmoMaxuW -> oldValue
      )
      cases.zipWithIndex.foreach { case ((operation, expected), index) =>
        simulate(new AtomicMemoryEngine) { dut =>
          clear(dut)
          offer(dut, operation, tag = index + 1, writeData = operand)
          acceptRead(dut, oldValue)
          acceptWrite(dut, expected)
          consumeResult(dut, tag = index + 1, data = oldValue, storePerformed = true)
        }
      }
    }

    it("turns RRESP and BRESP failures into one exact atomic fault and drains flushes") {
      simulate(new AtomicMemoryEngine) { dut =>
        clear(dut)
        offer(dut, IntOperation.LrW, tag = 4)
        acceptRead(dut, BigInt("12345678", 16), resp = 2)
        consumeResult(dut, tag = 4, data = 0, fault = true)

        offer(dut, IntOperation.AmoAddW, tag = 5, writeData = 1)
        acceptRead(dut, BigInt("12345678", 16))
        acceptWrite(dut, BigInt("12345679", 16), resp = 2)
        consumeResult(dut, tag = 5, data = BigInt("12345678", 16), fault = true,
          storePerformed = true)

        offer(dut, IntOperation.LrW, tag = 6)
        dut.io.ar.valid.expect(true)
        dut.clock.step()
        dut.io.flush.poke(true)
        dut.io.r.valid.poke(true)
        dut.io.r.bits.id.poke(7)
        dut.io.r.bits.data.poke(BigInt("abcdef01", 16))
        dut.io.r.bits.resp.poke(0)
        dut.io.r.bits.last.poke(true)
        dut.io.r.ready.expect(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.r.valid.poke(false)
        dut.io.result.valid.expect(false)
        dut.io.busy.expect(false)
      }
    }

    it("clears partial read/write owners and LR reservation across reset") {
      simulate(new AtomicMemoryEngine) { dut =>
        clear(dut)

        // Reset while ID 7 is waiting for an accepted AR response.
        offer(dut, IntOperation.LrW, tag = 1)
        dut.io.ar.valid.expect(true)
        dut.clock.step()
        dut.io.busy.expect(true)
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
        dut.clock.step()
        dut.io.busy.expect(false)
        dut.io.result.valid.expect(false)
        dut.io.effect.ready.expect(true)

        // Establish a new reservation, then reset after AW but before W/B.
        offer(dut, IntOperation.LrW, tag = 2)
        acceptRead(dut, BigInt("01020304", 16))
        consumeResult(dut, tag = 2, data = BigInt("01020304", 16))
        dut.io.reservationLive.expect(true)
        dut.io.w.ready.poke(false)
        offer(dut, IntOperation.ScW, tag = 3,
          writeData = BigInt("cafebabe", 16), destination = 33)
        dut.io.aw.valid.expect(true)
        dut.io.w.valid.expect(true)
        dut.clock.step()
        dut.io.aw.valid.expect(false)
        dut.io.w.valid.expect(true)
        dut.io.busy.expect(true)
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.io.busy.expect(false)
        dut.io.reservationLive.expect(false)
        dut.io.result.valid.expect(false)
        dut.io.effect.ready.expect(true)

        offer(dut, IntOperation.LrW, tag = 4)
        acceptRead(dut, BigInt("55667788", 16))
        consumeResult(dut, tag = 4, data = BigInt("55667788", 16))
      }
    }
  }
}
