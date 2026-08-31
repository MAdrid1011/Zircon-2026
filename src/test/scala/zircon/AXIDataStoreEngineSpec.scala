package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.AXIDataStoreEngine

class AXIDataStoreEngineSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: AXIDataStoreEngine): Unit = {
    dut.io.effect.valid.poke(false)
    dut.io.effect.bits.robTag.poke(0)
    dut.io.effect.bits.address.poke(0)
    dut.io.effect.bits.accessSize.poke(2)
    dut.io.effect.bits.writeMask.poke(0)
    dut.io.effect.bits.writeData.poke(0)
    dut.io.effect.bits.isAtomic.poke(false)
    dut.io.effect.bits.pmaKind.poke(PMARegionKind.Memory.code)
    dut.io.effect.bits.aq.poke(false)
    dut.io.effect.bits.rl.poke(false)
    dut.io.invalidateReady.poke(true)
    dut.io.result.ready.poke(false)
    dut.io.aw.ready.poke(false)
    dut.io.w.ready.poke(false)
    dut.io.b.valid.poke(false)
    dut.io.b.bits.id.poke(0)
    dut.io.b.bits.resp.poke(0)
  }

  private def offerStore(
      dut: AXIDataStoreEngine,
      tag: Int,
      address: BigInt,
      size: Int,
      mask: Int,
      data: BigInt
  ): Unit = {
    dut.io.effect.valid.poke(true)
    dut.io.effect.bits.robTag.poke(tag)
    dut.io.effect.bits.address.poke(address)
    dut.io.effect.bits.accessSize.poke(size)
    dut.io.effect.bits.writeMask.poke(mask)
    dut.io.effect.bits.writeData.poke(data)
    dut.io.effect.ready.expect(true)
    dut.clock.step()
    dut.io.effect.valid.poke(false)
  }

  private def completeWrite(dut: AXIDataStoreEngine, response: Int): Unit = {
    dut.io.b.valid.poke(true)
    dut.io.b.bits.id.poke(5)
    dut.io.b.bits.resp.poke(response)
    dut.io.b.ready.expect(true)
    dut.clock.step()
    dut.io.b.valid.poke(false)
  }

  describe("AXIDataStoreEngine") {
    it("holds AW and W independently then retains the exact B-error result") {
      simulate(new AXIDataStoreEngine) { dut =>
        clear(dut)
        offerStore(dut, tag = 9, BigInt("80001ffc", 16), size = 2,
          mask = 15, data = BigInt("cafebabe", 16))
        dut.io.aw.valid.expect(true)
        dut.io.aw.bits.id.expect(5)
        dut.io.aw.bits.addr.expect(BigInt("80001ffc", 16))
        dut.io.aw.bits.len.expect(0)
        dut.io.aw.bits.size.expect(2)
        dut.io.w.valid.expect(true)
        dut.io.w.bits.data.expect(BigInt("cafebabe", 16))
        dut.io.w.bits.strb.expect(15)
        dut.io.w.bits.last.expect(true)

        dut.io.aw.ready.poke(true)
        dut.clock.step()
        dut.io.aw.ready.poke(false)
        dut.io.aw.valid.expect(false)
        dut.io.w.valid.expect(true)

        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.io.w.ready.poke(false)
        completeWrite(dut, response = 2)
        dut.io.result.valid.expect(true)
        dut.io.result.bits.robTag.expect(9)
        dut.io.result.bits.address.expect(BigInt("80001ffc", 16))
        dut.io.result.bits.accessFault.expect(true)
        dut.clock.step(2)
        dut.io.result.valid.expect(true)
        dut.io.result.ready.poke(true)
        dut.clock.step()
        dut.io.result.ready.poke(false)
        dut.io.result.valid.expect(false)
      }
    }

    it("backpressures an effect until invalidation is safe and permits W before AW") {
      simulate(new AXIDataStoreEngine) { dut =>
        clear(dut)
        dut.io.invalidateReady.poke(false)
        dut.io.effect.valid.poke(true)
        dut.io.effect.bits.robTag.poke(3)
        dut.io.effect.bits.address.poke(BigInt("80001001", 16))
        dut.io.effect.bits.accessSize.poke(0)
        dut.io.effect.bits.writeMask.poke(2)
        dut.io.effect.bits.writeData.poke(BigInt("0000aa00", 16))
        dut.io.effect.ready.expect(false)
        dut.io.invalidateReady.poke(true)
        dut.io.effect.ready.expect(true)
        dut.clock.step()
        dut.io.effect.valid.poke(false)

        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.io.w.ready.poke(false)
        dut.io.aw.valid.expect(true)
        dut.io.w.valid.expect(false)
        dut.io.aw.ready.poke(true)
        dut.clock.step()
        dut.io.aw.ready.poke(false)
        completeWrite(dut, response = 0)
        dut.io.result.valid.expect(true)
        dut.io.result.bits.accessFault.expect(false)
      }
    }

    it("uses the reserved write ID and does not accept B before AW and W") {
      simulate(new AXIDataStoreEngine) { dut =>
        clear(dut)
        offerStore(dut, tag = 1, BigInt("80001000", 16), size = 2,
          mask = 15, data = 1)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(5)
        dut.io.b.bits.resp.poke(0)
        dut.io.b.ready.expect(false)
        dut.io.b.valid.poke(false)
        dut.io.aw.ready.poke(true)
        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.io.aw.ready.poke(false)
        dut.io.w.ready.poke(false)
        completeWrite(dut, response = 0)
        dut.io.result.valid.expect(true)
        dut.io.result.bits.robTag.expect(1)
      }
    }
  }
}
