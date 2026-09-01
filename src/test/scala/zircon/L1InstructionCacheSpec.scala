package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.{L1InstructionCache, L2DemandClient}

class L1InstructionCacheSpec extends AnyFunSpec with ChiselSim {
  private val ResetVector = BigInt("80000000", 16)

  private def clear(dut: L1InstructionCache, nextPc: BigInt = ResetVector): Unit = {
    dut.io.enable.poke(false)
    dut.io.redirect.valid.poke(false)
    dut.io.redirect.bits.poke(0)
    dut.io.invalidate.poke(false)
    dut.io.response.ready.poke(false)
    dut.io.responseNextPc.poke(nextPc)
    dut.io.continueAfterResponse.poke(false)
    dut.io.lookaheadEnable.poke(false)
    dut.io.l2Lookup.ready.poke(false)
    dut.io.l2LookupResponse.valid.poke(false)
    dut.io.l2LookupResponse.bits.hit.poke(false)
    dut.io.l2LookupResponse.bits.lineAddress.poke(0)
    for (word <- 0 until 8) dut.io.l2LookupResponse.bits.lineData(word).poke(0)
    dut.io.l2Request.ready.poke(false)
    dut.io.l2Response.valid.poke(false)
    dut.io.l2Response.bits.client.poke(L2DemandClient.Instruction)
    dut.io.l2Response.bits.clientMshr.poke(0)
    dut.io.l2Response.bits.accessFault.poke(false)
    for (word <- 0 until 8) dut.io.l2Response.bits.lineData(word).poke(0)
  }

  private def start(dut: L1InstructionCache, pc: BigInt): Unit = {
    dut.io.currentPc.expect(pc)
    dut.io.enable.poke(true)
    dut.clock.step()
    dut.io.enable.poke(false)
    dut.clock.step()
  }

  private def acceptL2Miss(dut: L1InstructionCache, line: BigInt): Unit = {
    dut.io.l2Lookup.valid.expect(true)
    dut.io.l2Lookup.bits.expect(line)
    dut.io.l2Lookup.ready.poke(true)
    dut.clock.step()
    dut.io.l2Lookup.ready.poke(false)
    dut.io.l2LookupResponse.ready.expect(true)
    dut.io.l2LookupResponse.valid.poke(true)
    dut.io.l2LookupResponse.bits.hit.poke(false)
    dut.io.l2LookupResponse.bits.lineAddress.poke(line)
    dut.io.l2LookupResponse.bits.lineData.foreach(_.poke(0))
    dut.clock.step()
    dut.io.l2LookupResponse.valid.poke(false)
  }

  private def acceptDemand(dut: L1InstructionCache, line: BigInt): Unit = {
    acceptL2Miss(dut, line)
    dut.io.l2Request.valid.expect(true)
    dut.io.l2Request.bits.client.expect(L2DemandClient.Instruction)
    dut.io.l2Request.bits.clientMshr.expect(0)
    dut.io.l2Request.bits.lineAddress.expect(line)
    dut.io.l2Request.ready.poke(true)
    dut.clock.step()
    dut.io.l2Request.ready.poke(false)
    dut.io.l2Response.ready.expect(true)
  }

  private def returnLine(dut: L1InstructionCache, words: Seq[BigInt],
      accessFault: Boolean = false): Unit = {
    require(words.length == 8)
    dut.io.l2Response.valid.poke(true)
    dut.io.l2Response.bits.client.poke(L2DemandClient.Instruction)
    dut.io.l2Response.bits.clientMshr.poke(0)
    dut.io.l2Response.bits.accessFault.poke(accessFault)
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.l2Response.bits.lineData(index).poke(word)
    }
    dut.io.l2Response.ready.expect(true)
    dut.clock.step()
    dut.io.l2Response.valid.poke(false)
  }

  private def acceptPacket(dut: L1InstructionCache, base: BigInt,
      words: Seq[BigInt], nextPc: BigInt): Unit = {
    dut.io.response.valid.expect(true)
    dut.io.response.bits.base.expect(base)
    dut.io.response.bits.count.expect(words.length)
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.response.bits.words(index).instruction.expect(word)
      dut.io.response.bits.words(index).fault.valid.expect(false)
    }
    dut.io.responseNextPc.poke(nextPc)
    dut.io.response.ready.poke(true)
    dut.clock.step()
    dut.io.response.ready.poke(false)
    dut.io.currentPc.expect(nextPc)
  }

  private def redirect(dut: L1InstructionCache, target: BigInt,
      invalidate: Boolean = false): Unit = {
    dut.io.redirect.valid.poke(true)
    dut.io.redirect.bits.poke(target)
    dut.io.invalidate.poke(invalidate)
    dut.clock.step()
    dut.io.redirect.valid.poke(false)
    dut.io.invalidate.poke(false)
    dut.io.currentPc.expect(target)
  }

  describe("L1InstructionCache") {
    it("fills L1I from a resident L2 instruction probe without an AXI demand") {
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        val line = Seq.tabulate(8)(index => BigInt("11000000", 16) + index)
        start(dut, ResetVector)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.expect(ResetVector)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)
        dut.io.l2LookupResponse.valid.poke(true)
        dut.io.l2LookupResponse.bits.hit.poke(true)
        dut.io.l2LookupResponse.bits.lineAddress.poke(ResetVector)
        line.zipWithIndex.foreach { case (word, index) =>
          dut.io.l2LookupResponse.bits.lineData(index).poke(word)
        }
        dut.io.l2LookupResponse.ready.expect(true)
        dut.clock.step()
        dut.io.l2LookupResponse.valid.poke(false)
        dut.io.l2Request.valid.expect(false)
        acceptPacket(dut, ResetVector, line.take(4), ResetVector + 16)
      }
    }

    it("fills a line through the instruction client and serves a later hit locally") {
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        val line = Seq.tabulate(8)(index => BigInt("10000000", 16) + index)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        returnLine(dut, line)
        acceptPacket(dut, ResetVector, line.take(4), ResetVector + 16)

        redirect(dut, ResetVector)
        start(dut, ResetVector)
        dut.io.l2Request.valid.expect(false)
        dut.io.response.valid.expect(true)
        acceptPacket(dut, ResetVector, line.take(4), ResetVector + 16)
      }
    }

    it("clips a packet at the end of a cache line") {
      val base = ResetVector + 24
      simulate(new L1InstructionCache(ZirconCoreConfig.default.copy(resetVector = base))) { dut =>
        clear(dut, base)
        val line = Seq.tabulate(8)(index => BigInt("20000000", 16) + index)
        start(dut, base)
        acceptDemand(dut, ResetVector)
        returnLine(dut, line)
        acceptPacket(dut, base, line.drop(6), ResetVector + 32)
      }
    }

    it("starts a cached continuation lookup without an idle cycle") {
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        val line = Seq.tabulate(8)(index => BigInt("21000000", 16) + index)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        returnLine(dut, line)

        dut.io.response.valid.expect(true)
        dut.io.responseNextPc.poke(ResetVector + 16)
        dut.io.continueAfterResponse.poke(true)
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)
        dut.io.continueAfterResponse.poke(false)
        dut.io.currentPc.expect(ResetVector + 16)
        dut.io.l2Request.valid.expect(false)

        dut.clock.step()
        dut.io.response.valid.expect(true)
        dut.io.response.bits.base.expect(ResetVector + 16)
        dut.io.response.bits.count.expect(4)
        for (slot <- 0 until 4) {
          dut.io.response.bits.words(slot).instruction.expect(line(slot + 4))
        }
      }
    }

    it("prefetches the sequential next line while the first packet waits") {
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        val firstLine = Seq.tabulate(8)(index => BigInt("22000000", 16) + index)
        val nextLine = Seq.tabulate(8)(index => BigInt("23000000", 16) + index)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        returnLine(dut, firstLine)

        dut.io.response.valid.expect(true)
        dut.io.response.ready.poke(false)
        dut.io.lookaheadEnable.poke(true)
        dut.io.l2Request.valid.expect(true)
        dut.io.l2Request.bits.lineAddress.expect(ResetVector + 32)
        dut.io.l2Request.ready.poke(true)
        dut.clock.step()
        dut.io.l2Request.ready.poke(false)
        dut.io.lookaheadEnable.poke(false)
        dut.io.l2Response.ready.expect(true)
        returnLine(dut, nextLine)

        dut.io.response.valid.expect(true)
        dut.io.response.bits.base.expect(ResetVector)
        dut.io.response.bits.words(0).instruction.expect(firstLine.head)
      }
    }

    it("drains an accepted sequential lookahead on redirect") {
      val target = ResetVector + 0x100
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        val line = Seq.tabulate(8)(index => BigInt("24000000", 16) + index)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        returnLine(dut, line)

        dut.io.lookaheadEnable.poke(true)
        dut.io.l2Request.valid.expect(true)
        dut.io.l2Request.bits.lineAddress.expect(ResetVector + 32)
        dut.io.l2Request.ready.poke(true)
        dut.clock.step()
        dut.io.l2Request.ready.poke(false)
        dut.io.lookaheadEnable.poke(false)

        redirect(dut, target)
        dut.io.draining.expect(true)
        returnLine(dut, Seq.tabulate(8)(index => BigInt("25000000", 16) + index))
        dut.io.response.valid.expect(false)
        start(dut, target)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.expect(target)
      }
    }

    it("turns a retained refill fault into exact selected-word fetch faults without filling") {
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        returnLine(dut, Seq.fill(8)(BigInt("deadbeef", 16)), accessFault = true)
        dut.io.response.valid.expect(true)
        for (slot <- 0 until 4) {
          dut.io.response.bits.words(slot).instruction.expect(0)
          dut.io.response.bits.words(slot).fault.valid.expect(true)
          dut.io.response.bits.words(slot).fault.cause.expect(1)
          dut.io.response.bits.words(slot).fault.tval.expect(ResetVector + slot * 4)
        }
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)

        redirect(dut, ResetVector)
        start(dut, ResetVector)
        dut.io.l2Lookup.valid.expect(true)
      }
    }

    it("holds an unaccepted demand stable and cancels it before shared ownership") {
      val target = ResetVector + 0x80
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        start(dut, ResetVector)
        acceptL2Miss(dut, ResetVector)
        dut.io.l2Request.valid.expect(true)
        dut.io.l2Request.bits.lineAddress.expect(ResetVector)
        dut.clock.step(2)
        dut.io.l2Request.valid.expect(true)
        dut.io.l2Request.bits.lineAddress.expect(ResetVector)

        redirect(dut, target)
        dut.io.l2Request.valid.expect(false)
        start(dut, target)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.expect(target)
      }
    }

    it("drains an accepted instruction demand after redirect before reusing the MSHR") {
      val target = ResetVector + 0x100
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        redirect(dut, target)
        dut.io.draining.expect(true)
        returnLine(dut, Seq.tabulate(8)(BigInt(_)))
        dut.io.response.valid.expect(false)
        start(dut, target)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.expect(target)
      }
    }

    it("invalidates a filled line with the FENCE.I redirect") {
      simulate(new L1InstructionCache) { dut =>
        clear(dut)
        val line = Seq.tabulate(8)(index => BigInt("30000000", 16) + index)
        start(dut, ResetVector)
        acceptDemand(dut, ResetVector)
        returnLine(dut, line)
        acceptPacket(dut, ResetVector, line.take(4), ResetVector + 16)

        redirect(dut, ResetVector, invalidate = true)
        start(dut, ResetVector)
        dut.io.l2Lookup.valid.expect(true)
      }
    }
  }
}
