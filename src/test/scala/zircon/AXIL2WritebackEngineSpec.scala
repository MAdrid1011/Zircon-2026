package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.AXIL2WritebackEngine

class AXIL2WritebackEngineSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: AXIL2WritebackEngine): Unit = {
    dut.io.victim.valid.poke(false)
    dut.io.victim.bits.lineAddress.poke(0)
    dut.io.victim.bits.dirty.poke(false)
    for (word <- dut.io.victim.bits.lineData.indices) {
      dut.io.victim.bits.lineData(word).poke(0)
    }
    dut.io.aw.ready.poke(false)
    dut.io.w.ready.poke(false)
    dut.io.b.valid.poke(false)
    dut.io.b.bits.id.poke(5)
    dut.io.b.bits.resp.poke(0)
  }

  private def offerDirtyVictim(
      dut: AXIL2WritebackEngine,
      address: BigInt,
      words: Seq[BigInt]
  ): Unit = {
    require(words.size == 8)
    dut.io.victim.valid.poke(true)
    dut.io.victim.bits.lineAddress.poke(address)
    dut.io.victim.bits.dirty.poke(true)
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.victim.bits.lineData(index).poke(word)
    }
    dut.io.victim.ready.expect(true)
    dut.clock.step()
    dut.io.victim.valid.poke(false)
  }

  private def acceptBurst(
      dut: AXIL2WritebackEngine,
      address: BigInt,
      words: Seq[BigInt]
  ): Unit = {
    dut.io.aw.valid.expect(true)
    dut.io.aw.bits.id.expect(5)
    dut.io.aw.bits.addr.expect(address)
    dut.io.aw.bits.len.expect(7)
    dut.io.aw.bits.size.expect(2)
    dut.io.aw.bits.burst.expect(1)
    dut.io.aw.bits.cache.expect(3)
    dut.io.aw.ready.poke(true)
    dut.clock.step()
    dut.io.aw.ready.poke(false)

    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.w.valid.expect(true)
      dut.io.w.bits.data.expect(word)
      dut.io.w.bits.strb.expect(15)
      dut.io.w.bits.last.expect(index == words.size - 1)
      dut.io.w.ready.poke(true)
      dut.clock.step()
      dut.io.w.ready.poke(false)
    }
  }

  describe("AXIL2WritebackEngine") {
    it("drains one dirty 32-byte L2 victim as an ID-5 AXI burst") {
      simulate(new AXIL2WritebackEngine) { dut =>
        clear(dut)
        val words = Seq.tabulate(8)(index => BigInt("cafe0000", 16) + index)
        offerDirtyVictim(dut, BigInt("80001fe0", 16), words)

        // The address channel holds its packet under backpressure. W is also
        // stable here; the top-level AXI scheduler later enforces AW-before-W.
        dut.io.aw.valid.expect(true)
        dut.io.aw.bits.addr.expect(BigInt("80001fe0", 16))
        dut.io.w.valid.expect(true)
        dut.io.w.bits.data.expect(words.head)
        dut.clock.step(2)
        dut.io.aw.valid.expect(true)
        dut.io.aw.bits.addr.expect(BigInt("80001fe0", 16))
        dut.io.w.bits.data.expect(words.head)

        acceptBurst(dut, BigInt("80001fe0", 16), words)
        dut.io.b.ready.expect(true)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(5)
        dut.io.b.bits.resp.poke(0)
        dut.clock.step()
        dut.io.b.valid.poke(false)
        dut.io.busy.expect(false)
        dut.io.victim.ready.expect(true)
      }
    }

    it("retries a retained dirty line after a failing BRESP") {
      simulate(new AXIL2WritebackEngine) { dut =>
        clear(dut)
        val words = Seq.tabulate(8)(index => BigInt("deaf0000", 16) + index)
        offerDirtyVictim(dut, BigInt("80002000", 16), words)
        acceptBurst(dut, BigInt("80002000", 16), words)

        dut.io.b.ready.expect(true)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(5)
        dut.io.b.bits.resp.poke(2)
        dut.io.completed.valid.expect(false)
        dut.clock.step()
        dut.io.b.valid.poke(false)
        dut.io.busy.expect(true)
        dut.io.retryObserved.expect(true)
        dut.io.victim.ready.expect(false)

        // The response did not release or mutate the sole dirty copy.
        acceptBurst(dut, BigInt("80002000", 16), words)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(5)
        dut.io.b.bits.resp.poke(0)
        dut.io.b.ready.expect(true)
        dut.io.completed.valid.expect(true)
        dut.io.completed.bits.expect(BigInt("80002000", 16))
        dut.clock.step()
        dut.io.b.valid.poke(false)
        dut.io.busy.expect(false)
      }
    }

    it("drops an errored partial retry and accepts a new owner after reset") {
      simulate(new AXIL2WritebackEngine) { dut =>
        clear(dut)
        val firstWords = Seq.tabulate(8)(index => BigInt("aa000000", 16) + index)
        offerDirtyVictim(dut, BigInt("80003000", 16), firstWords)
        acceptBurst(dut, BigInt("80003000", 16), firstWords)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.resp.poke(2)
        dut.io.b.ready.expect(true)
        dut.clock.step()
        dut.io.b.valid.poke(false)
        dut.io.retryObserved.expect(true)

        // Accept the retry AW and a prefix of W before reset starts a new
        // external ownership epoch.
        dut.io.aw.ready.poke(true)
        dut.io.w.ready.poke(true)
        dut.clock.step(3)
        dut.io.aw.ready.poke(false)
        dut.io.w.ready.poke(false)
        dut.io.busy.expect(true)
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
        dut.clock.step()
        dut.io.busy.expect(false)
        dut.io.retryObserved.expect(false)
        dut.io.completed.valid.expect(false)
        dut.io.victim.ready.expect(true)

        val nextWords = Seq.tabulate(8)(index => BigInt("bb000000", 16) + index)
        offerDirtyVictim(dut, BigInt("80004000", 16), nextWords)
        acceptBurst(dut, BigInt("80004000", 16), nextWords)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.resp.poke(0)
        dut.io.b.ready.expect(true)
        dut.io.completed.valid.expect(true)
        dut.io.completed.bits.expect(BigInt("80004000", 16))
        dut.clock.step()
        dut.io.b.valid.poke(false)
        dut.io.busy.expect(false)
      }
    }
  }
}
