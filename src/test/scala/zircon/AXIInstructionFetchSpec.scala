package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.AXIInstructionFetch

class AXIInstructionFetchSpec extends AnyFunSpec with ChiselSim {
  private val ResetVector = BigInt("80000000", 16)

  private def clearInputs(dut: AXIInstructionFetch, nextPc: BigInt = ResetVector): Unit = {
    dut.io.enable.poke(false)
    dut.io.redirect.valid.poke(false)
    dut.io.redirect.bits.poke(0)
    dut.io.ar.ready.poke(false)
    dut.io.r.valid.poke(false)
    dut.io.r.bits.id.poke(0)
    dut.io.r.bits.data.poke(0)
    dut.io.r.bits.resp.poke(0)
    dut.io.r.bits.last.poke(false)
    dut.io.response.ready.poke(false)
    dut.io.responseNextPc.poke(nextPc)
  }

  private def launchRequest(dut: AXIInstructionFetch, pc: BigInt): Unit = {
    dut.io.currentPc.expect(pc)
    dut.io.enable.poke(true)
    dut.clock.step()
    dut.io.enable.poke(false)
    dut.io.ar.valid.expect(true)
  }

  private def acceptAddress(dut: AXIInstructionFetch, base: BigInt, beats: Int): Unit = {
    dut.io.ar.valid.expect(true)
    dut.io.ar.bits.id.expect(0)
    dut.io.ar.bits.addr.expect(base)
    dut.io.ar.bits.len.expect(beats - 1)
    dut.io.ar.bits.size.expect(2)
    dut.io.ar.bits.burst.expect(1)
    dut.io.ar.bits.lock.expect(false)
    dut.io.ar.bits.cache.expect(3)
    dut.io.ar.bits.prot.expect(5)
    dut.io.ar.bits.qos.expect(0)
    dut.io.ar.ready.poke(true)
    dut.clock.step()
    dut.io.ar.ready.poke(false)
    dut.io.r.ready.expect(true)
  }

  private def sendRead(dut: AXIInstructionFetch, data: BigInt, last: Boolean,
      resp: Int = 0, id: Int = 0): Unit = {
    dut.io.r.valid.poke(true)
    dut.io.r.bits.id.poke(id)
    dut.io.r.bits.data.poke(data)
    dut.io.r.bits.resp.poke(resp)
    dut.io.r.bits.last.poke(last)
    dut.io.r.ready.expect(true)
    dut.clock.step()
    dut.io.r.valid.poke(false)
  }

  private def completeResponse(dut: AXIInstructionFetch, base: BigInt,
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

  private def redirect(dut: AXIInstructionFetch, target: BigInt): Unit = {
    dut.io.redirect.valid.poke(true)
    dut.io.redirect.bits.poke(target)
    dut.clock.step()
    dut.io.redirect.valid.poke(false)
    dut.io.currentPc.expect(target)
  }

  describe("AXIInstructionFetch") {
    it("issues a normal four-beat INCR request and returns an ordered packet") {
      simulate(new AXIInstructionFetch) { dut =>
        clearInputs(dut)
        launchRequest(dut, ResetVector)
        acceptAddress(dut, ResetVector, 4)
        val words = Seq.tabulate(4)(index => BigInt("10000000", 16) + index)
        words.zipWithIndex.foreach { case (word, index) =>
          sendRead(dut, word, last = index == words.length - 1)
        }
        completeResponse(dut, ResetVector, words, ResetVector + 16)

        launchRequest(dut, ResetVector + 16)
        acceptAddress(dut, ResetVector + 16, 4)
      }
    }

    it("shortens bursts to one, two, or three beats before a 4 KiB boundary") {
      Seq(
        (BigInt("80000ff4", 16), 3),
        (BigInt("80000ff8", 16), 2),
        (BigInt("80000ffc", 16), 1)
      ).foreach { case (base, beats) =>
        simulate(new AXIInstructionFetch(ZirconCoreConfig.default.copy(resetVector = base))) { dut =>
          clearInputs(dut, base)
          launchRequest(dut, base)
          acceptAddress(dut, base, beats)
          val words = Seq.tabulate(beats)(index => BigInt("20000000", 16) + index)
          words.zipWithIndex.foreach { case (word, index) =>
            sendRead(dut, word, last = index == beats - 1)
          }
          completeResponse(dut, base, words, base + beats * 4)
        }
      }
    }

    it("holds an unaccepted AR stable through backpressure and drains it after redirect") {
      val redirected = ResetVector + 0x100
      simulate(new AXIInstructionFetch) { dut =>
        clearInputs(dut)
        launchRequest(dut, ResetVector)
        dut.io.ar.bits.addr.expect(ResetVector)
        dut.io.ar.bits.len.expect(3)

        redirect(dut, redirected)
        dut.io.draining.expect(true)
        for (_ <- 0 until 3) {
          dut.io.ar.valid.expect(true)
          dut.io.ar.bits.addr.expect(ResetVector)
          dut.io.ar.bits.len.expect(3)
          dut.clock.step()
        }

        acceptAddress(dut, ResetVector, 4)
        dut.io.draining.expect(true)
        for (index <- 0 until 4) {
          sendRead(dut, BigInt("30000000", 16) + index, last = index == 3)
        }
        dut.io.response.valid.expect(false)

        launchRequest(dut, redirected)
        acceptAddress(dut, redirected, 4)
      }
    }

    it("drains an accepted request when redirect arrives during receive or drain") {
      val firstRedirect = ResetVector + 0x80
      val finalRedirect = ResetVector + 0x180
      simulate(new AXIInstructionFetch) { dut =>
        clearInputs(dut)
        launchRequest(dut, ResetVector)
        acceptAddress(dut, ResetVector, 4)
        sendRead(dut, BigInt("40000000", 16), last = false)

        redirect(dut, firstRedirect)
        dut.io.draining.expect(true)
        sendRead(dut, BigInt("40000001", 16), last = false)
        redirect(dut, finalRedirect)
        dut.io.draining.expect(true)
        sendRead(dut, BigInt("40000002", 16), last = false)
        sendRead(dut, BigInt("40000003", 16), last = true)
        dut.io.response.valid.expect(false)

        launchRequest(dut, finalRedirect)
        acceptAddress(dut, finalRedirect, 4)
      }
    }

    it("suppresses a complete but unconsumed packet when redirect arrives in present") {
      val redirected = ResetVector + 0x40
      simulate(new AXIInstructionFetch) { dut =>
        clearInputs(dut)
        launchRequest(dut, ResetVector)
        acceptAddress(dut, ResetVector, 4)
        for (index <- 0 until 4) {
          sendRead(dut, BigInt("50000000", 16) + index, last = index == 3)
        }
        dut.io.response.valid.expect(true)
        dut.io.redirect.valid.poke(true)
        dut.io.redirect.bits.poke(redirected)
        dut.io.response.valid.expect(false)
        dut.clock.step()
        dut.io.redirect.valid.poke(false)
        dut.io.response.valid.expect(false)
        dut.io.currentPc.expect(redirected)

        launchRequest(dut, redirected)
        acceptAddress(dut, redirected, 4)
      }
    }

    it("converts each failing RRESP into an instruction access fault at its word address") {
      simulate(new AXIInstructionFetch) { dut =>
        clearInputs(dut)
        launchRequest(dut, ResetVector)
        acceptAddress(dut, ResetVector, 4)
        sendRead(dut, BigInt("60000000", 16), last = false, resp = 0)
        sendRead(dut, BigInt("60000001", 16), last = false, resp = 1)
        sendRead(dut, BigInt("60000002", 16), last = false, resp = 2)
        sendRead(dut, BigInt("60000003", 16), last = true, resp = 3)

        dut.io.response.valid.expect(true)
        dut.io.response.bits.words(0).fault.valid.expect(false)
        dut.io.response.bits.words(1).fault.valid.expect(false)
        dut.io.response.bits.words(2).instruction.expect(0)
        dut.io.response.bits.words(2).fault.valid.expect(true)
        dut.io.response.bits.words(2).fault.cause.expect(1)
        dut.io.response.bits.words(2).fault.tval.expect(ResetVector + 8)
        dut.io.response.bits.words(3).instruction.expect(0)
        dut.io.response.bits.words(3).fault.valid.expect(true)
        dut.io.response.bits.words(3).fault.cause.expect(1)
        dut.io.response.bits.words(3).fault.tval.expect(ResetVector + 12)
      }
    }

    it("asserts on an unknown AXI read ID") {
      assertThrows[Throwable] {
        simulate(new AXIInstructionFetch) { dut =>
          clearInputs(dut)
          launchRequest(dut, ResetVector)
          acceptAddress(dut, ResetVector, 4)
          sendRead(dut, 0, last = false, id = 1)
        }
      }
    }

    it("asserts on early and late RLAST") {
      assertThrows[Throwable] {
        simulate(new AXIInstructionFetch) { dut =>
          clearInputs(dut)
          launchRequest(dut, ResetVector)
          acceptAddress(dut, ResetVector, 4)
          sendRead(dut, 0, last = true)
        }
      }
      assertThrows[Throwable] {
        simulate(new AXIInstructionFetch) { dut =>
          clearInputs(dut)
          launchRequest(dut, ResetVector)
          acceptAddress(dut, ResetVector, 4)
          for (_ <- 0 until 3) sendRead(dut, 0, last = false)
          sendRead(dut, 0, last = false)
        }
      }
    }

    it("rejects misaligned reset vectors and redirect targets") {
      assertThrows[IllegalArgumentException] {
        ZirconCoreConfig.default.copy(resetVector = ResetVector + 2)
      }
      assertThrows[Throwable] {
        simulate(new AXIInstructionFetch) { dut =>
          clearInputs(dut)
          dut.io.redirect.valid.poke(true)
          dut.io.redirect.bits.poke(ResetVector + 2)
          dut.clock.step()
        }
      }
    }
  }
}
