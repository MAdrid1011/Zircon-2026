package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.L1DLoadCache

class L1DLoadCacheSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: L1DLoadCache): Unit = {
    dut.io.request.valid.poke(false)
    dut.io.request.bits.robTag.poke(0)
    dut.io.request.bits.address.poke(0)
    dut.io.request.bits.readMask.poke(0)
    dut.io.request.bits.forwardMask.poke(0)
    dut.io.request.bits.forwardData.poke(0)
    dut.io.request.bits.requiresCache.poke(false)
    dut.io.request.bits.cacheable.poke(true)
    dut.io.completion.ready.poke(false)
    dut.io.dataRequest.ready.poke(false)
    dut.io.dataResponse.valid.poke(false)
    dut.io.dataResponse.bits.mshr.poke(0)
    dut.io.dataResponse.bits.lineData.foreach(_.poke(0))
    dut.io.dataResponse.bits.accessFault.poke(false)
    dut.io.storeAccept.valid.poke(false)
    dut.io.storeAccept.bits.robTag.poke(0)
    dut.io.storeAccept.bits.address.poke(0)
    dut.io.storeAccept.bits.accessSize.poke(2)
    dut.io.storeAccept.bits.writeMask.poke(0)
    dut.io.storeAccept.bits.writeData.poke(0)
    dut.io.storeAccept.bits.isAtomic.poke(false)
    dut.io.storeAccept.bits.pmaKind.poke(PMARegionKind.Memory.code)
    dut.io.storeAccept.bits.aq.poke(false)
    dut.io.storeAccept.bits.rl.poke(false)
    dut.io.storeCommit.valid.poke(false)
    dut.io.storeCommit.bits.robTag.poke(0)
    dut.io.storeCommit.bits.address.poke(0)
    dut.io.storeCommit.bits.accessSize.poke(2)
    dut.io.storeCommit.bits.writeMask.poke(0)
    dut.io.storeCommit.bits.writeData.poke(0)
    dut.io.storeCommit.bits.isAtomic.poke(false)
    dut.io.storeCommit.bits.pmaKind.poke(PMARegionKind.Memory.code)
    dut.io.storeCommit.bits.aq.poke(false)
    dut.io.storeCommit.bits.rl.poke(false)
    dut.io.activeStore.valid.poke(false)
    dut.io.activeStore.bits.robTag.poke(0)
    dut.io.activeStore.bits.address.poke(0)
    dut.io.activeStore.bits.accessSize.poke(2)
    dut.io.activeStore.bits.writeMask.poke(0)
    dut.io.activeStore.bits.writeData.poke(0)
    dut.io.activeStore.bits.isAtomic.poke(false)
    dut.io.activeStore.bits.pmaKind.poke(PMARegionKind.Memory.code)
    dut.io.activeStore.bits.aq.poke(false)
    dut.io.activeStore.bits.rl.poke(false)
    dut.io.atomicAccept.valid.poke(false)
    dut.io.atomicAccept.bits.robTag.poke(0)
    dut.io.atomicAccept.bits.operation.poke(0)
    dut.io.atomicAccept.bits.address.poke(0)
    dut.io.atomicAccept.bits.writeData.poke(0)
    dut.io.atomicAccept.bits.writeMask.poke(15)
    dut.io.atomicAccept.bits.destinationPhysical.poke(0)
    dut.io.atomicAccept.bits.writesInteger.poke(false)
    dut.io.atomicAccept.bits.aq.poke(false)
    dut.io.atomicAccept.bits.rl.poke(false)
    dut.io.atomicInvalidate.valid.poke(false)
    dut.io.atomicInvalidate.bits.poke(0)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def submit(
      dut: L1DLoadCache,
      tag: Int,
      address: BigInt,
      requiresCache: Boolean = true
  ): Unit = {
    dut.io.request.valid.poke(true)
    dut.io.request.bits.robTag.poke(tag)
    dut.io.request.bits.address.poke(address)
    dut.io.request.bits.readMask.poke(15)
    dut.io.request.bits.forwardMask.poke(if (requiresCache) 0 else 15)
    dut.io.request.bits.forwardData.poke(0)
    dut.io.request.bits.requiresCache.poke(requiresCache)
    dut.io.request.bits.cacheable.poke(true)
    dut.io.request.ready.expect(true)
    dut.clock.step()
    dut.io.request.valid.poke(false)
  }

  private def issueRefill(
      dut: L1DLoadCache,
      words: Seq[BigInt],
      fault: Boolean = false,
      lineAddress: BigInt = BigInt("80001000", 16)
  ): Unit = {
    dut.io.dataRequest.valid.expect(true)
    dut.io.dataRequest.bits.lineAddress.expect(lineAddress)
    dut.io.dataRequest.ready.poke(true)
    dut.clock.step()
    dut.io.dataRequest.ready.poke(false)
    dut.io.dataResponse.valid.poke(true)
    dut.io.dataResponse.bits.mshr.poke(0)
    dut.io.dataResponse.bits.accessFault.poke(fault)
    for ((word, index) <- words.zipWithIndex) {
      dut.io.dataResponse.bits.lineData(index).poke(word)
    }
    dut.io.dataResponse.ready.expect(true)
    dut.clock.step()
    dut.io.dataResponse.valid.poke(false)
  }

  describe("L1DLoadCache") {
    it("rejects a non-cacheable M0 forward without a refill or completion") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        dut.io.request.valid.poke(true)
        dut.io.request.bits.robTag.poke(3)
        dut.io.request.bits.address.poke(BigInt("a0000000", 16))
        dut.io.request.bits.readMask.poke(15)
        dut.io.request.bits.forwardMask.poke(0)
        dut.io.request.bits.forwardData.poke(0)
        dut.io.request.bits.requiresCache.poke(true)
        dut.io.request.bits.cacheable.poke(false)
        dut.io.request.ready.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step(2)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("blocks an atomic behind a same-line refill and invalidates its old L1D line") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val address = BigInt("80001000", 16)
        submit(dut, tag = 1, address)
        dut.io.atomicAccept.valid.poke(true)
        dut.io.atomicAccept.bits.address.poke(address)
        dut.io.atomicAcceptReady.expect(false)
        dut.io.atomicAccept.valid.poke(false)

        issueRefill(dut, Seq.tabulate(8)(word => BigInt("10000000", 16) + word))
        dut.io.completion.ready.poke(true)
        dut.io.completion.valid.expect(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.clock.step()
        dut.io.atomicAccept.valid.poke(true)
        dut.io.atomicAccept.bits.address.poke(address)
        dut.io.atomicAcceptReady.expect(true)
        dut.io.atomicAccept.valid.poke(false)

        dut.io.atomicInvalidate.valid.poke(true)
        dut.io.atomicInvalidate.bits.poke(address)
        dut.clock.step()
        dut.io.atomicInvalidate.valid.poke(false)
        submit(dut, tag = 2, address)
        dut.io.dataRequest.valid.expect(true)
      }
    }

    it("returns an eight-beat miss refill then a cache hit without a second data request") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val words = (0 until 8).map(index => BigInt("a0000000", 16) + index)
        submit(dut, tag = 1, BigInt("8000100c", 16))
        issueRefill(dut, words)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.bits.cacheData.expect(words(3))
        dut.io.completion.bits.accessFault.expect(false)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submit(dut, tag = 2, BigInt("80001008", 16))
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(2)
        dut.io.completion.bits.cacheData.expect(words(2))
      }
    }

    it("invalidates a reserved victim so an old line cannot hit during refill") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lineA = BigInt("80001000", 16)
        val lineB = BigInt("80001200", 16)
        val lineC = BigInt("80001400", 16)
        val words = Seq.fill(8)(BigInt("11111111", 16))

        submit(dut, tag = 1, lineA)
        issueRefill(dut, words, lineAddress = lineA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submit(dut, tag = 2, lineB)
        issueRefill(dut, words, lineAddress = lineB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submit(dut, tag = 3, lineC)
        dut.io.dataRequest.valid.expect(true)
        dut.io.dataRequest.bits.lineAddress.expect(lineC)
        dut.io.dataRequest.ready.poke(true)
        dut.clock.step()
        dut.io.dataRequest.ready.poke(false)

        submit(dut, tag = 4, lineA)
        dut.io.completion.valid.expect(false)
        dut.io.dataRequest.valid.expect(true)
        dut.io.dataRequest.bits.lineAddress.expect(lineA)
      }
    }

    it("merges same-line secondary misses and preserves their exact ROB tags") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val words = (0 until 8).map(index => BigInt("b0000000", 16) + index)
        submit(dut, tag = 1, BigInt("80001000", 16))
        dut.io.dataRequest.valid.expect(true)
        submit(dut, tag = 2, BigInt("80001014", 16))
        issueRefill(dut, words)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.bits.cacheData.expect(words(0))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(2)
        dut.io.completion.bits.cacheData.expect(words(5))
      }
    }

    it("completes full store forwarding locally and reports refill errors precisely") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        submit(dut, tag = 3, BigInt("80001000", 16), requiresCache = false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(0)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submit(dut, tag = 4, BigInt("8000101c", 16))
        issueRefill(dut, Seq.fill(8)(BigInt(0)), fault = true)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(4)
        dut.io.completion.bits.accessFault.expect(true)
        dut.io.completion.bits.faultAddress.expect(BigInt("8000101c", 16))
      }
    }

    it("invalidates a committed-store line and blocks same-line cache access while it drains") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80001000", 16)
        val words = (0 until 8).map(index => BigInt("c0000000", 16) + index)
        submit(dut, tag = 1, line)
        issueRefill(dut, words)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        dut.io.storeAccept.valid.poke(true)
        dut.io.storeAccept.bits.address.poke(line)
        dut.io.storeAcceptReady.expect(true)
        dut.io.storeCommit.valid.poke(true)
        dut.io.storeCommit.bits.address.poke(line)
        dut.clock.step()
        dut.io.storeAccept.valid.poke(false)
        dut.io.storeCommit.valid.poke(false)

        dut.io.activeStore.valid.poke(true)
        dut.io.activeStore.bits.address.poke(line)
        dut.io.request.valid.poke(true)
        dut.io.request.bits.robTag.poke(2)
        dut.io.request.bits.address.poke(line)
        dut.io.request.bits.readMask.poke(15)
        dut.io.request.bits.forwardMask.poke(0)
        dut.io.request.bits.forwardData.poke(0)
        dut.io.request.bits.requiresCache.poke(true)
        dut.io.request.ready.expect(false)
        dut.io.activeStore.valid.poke(false)
        dut.io.request.ready.expect(true)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        dut.io.dataRequest.valid.expect(true)
        dut.io.dataRequest.bits.lineAddress.expect(line)
      }
    }
  }
}
