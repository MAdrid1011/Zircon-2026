package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.{L1DLoadCache, L2DemandClient}

class L1DLoadCacheSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: L1DLoadCache): Unit = {
    dut.io.request.foreach { request =>
      request.valid.poke(false)
      request.bits.robTag.poke(0)
      request.bits.address.poke(0)
      request.bits.readMask.poke(0)
      request.bits.forwardMask.poke(0)
      request.bits.forwardData.poke(0)
      request.bits.requiresCache.poke(false)
      request.bits.cacheable.poke(true)
    }
    dut.io.completion.ready.poke(false)
    dut.io.dataRequest.ready.poke(false)
    dut.io.dataResponse.valid.poke(false)
    dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
    dut.io.dataResponse.bits.clientMshr.poke(0)
    dut.io.dataResponse.bits.lineData.foreach(_.poke(0))
    dut.io.dataResponse.bits.accessFault.poke(false)
    dut.io.l2Insert.ready.poke(true)
    dut.io.l2Lookup.ready.poke(false)
    dut.io.l2Response.valid.poke(false)
    dut.io.l2Response.bits.hit.poke(false)
    dut.io.l2Response.bits.transfer.lineAddress.poke(0)
    dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
    dut.io.l2Response.bits.transfer.dirty.poke(false)
    dut.io.flushLine.valid.poke(false)
    dut.io.flushLine.bits.poke(0)
    dut.io.fenceDrain.poke(false)
    dut.io.storeRequest.valid.poke(false)
    dut.io.storeRequest.bits.robTag.poke(0)
    dut.io.storeRequest.bits.address.poke(0)
    dut.io.storeRequest.bits.accessSize.poke(2)
    dut.io.storeRequest.bits.writeMask.poke(0)
    dut.io.storeRequest.bits.writeData.poke(0)
    dut.io.storeRequest.bits.isAtomic.poke(false)
    dut.io.storeRequest.bits.pmaKind.poke(PMARegionKind.Memory.code)
    dut.io.storeRequest.bits.aq.poke(false)
    dut.io.storeRequest.bits.rl.poke(false)
    dut.io.storeResult.ready.poke(false)
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
    dut.io.atomicRequiresExternal.poke(true)
    dut.io.atomicInvalidate.valid.poke(false)
    dut.io.atomicInvalidate.bits.poke(0)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def presentLoad(
      dut: L1DLoadCache,
      lane: Int,
      tag: Int,
      address: BigInt,
      requiresCache: Boolean = true
  ): Unit = {
    val request = dut.io.request(lane)
    request.valid.poke(true)
    request.bits.robTag.poke(tag)
    request.bits.address.poke(address)
    request.bits.readMask.poke(15)
    request.bits.forwardMask.poke(if (requiresCache) 0 else 15)
    request.bits.forwardData.poke(0)
    request.bits.requiresCache.poke(requiresCache)
    request.bits.cacheable.poke(true)
  }

  private def submit(
      dut: L1DLoadCache,
      tag: Int,
      address: BigInt,
      requiresCache: Boolean = true,
      lane: Int = 0
  ): Unit = {
    presentLoad(dut, lane, tag, address, requiresCache)
    dut.io.request(lane).ready.expect(true)
    dut.clock.step()
    dut.io.request(lane).valid.poke(false)
  }

  private def submitStore(
      dut: L1DLoadCache,
      tag: Int,
      address: BigInt,
      mask: Int,
      data: BigInt
  ): Unit = {
    dut.io.storeRequest.valid.poke(true)
    dut.io.storeRequest.bits.robTag.poke(tag)
    dut.io.storeRequest.bits.address.poke(address)
    dut.io.storeRequest.bits.accessSize.poke(2)
    dut.io.storeRequest.bits.writeMask.poke(mask)
    dut.io.storeRequest.bits.writeData.poke(data)
    dut.io.storeRequest.bits.isAtomic.poke(false)
    dut.io.storeRequest.bits.pmaKind.poke(PMARegionKind.Memory.code)
    dut.io.storeRequest.bits.aq.poke(false)
    dut.io.storeRequest.bits.rl.poke(false)
    dut.io.storeRequest.ready.expect(true)
    dut.clock.step()
    dut.io.storeRequest.valid.poke(false)
  }

  private def consumeStoreResult(
      dut: L1DLoadCache,
      tag: Int,
      address: BigInt,
      fault: Boolean = false
  ): Unit = {
    dut.io.storeResult.valid.expect(true)
    dut.io.storeResult.bits.robTag.expect(tag)
    dut.io.storeResult.bits.address.expect(address)
    dut.io.storeResult.bits.accessFault.expect(fault)
    dut.io.storeResult.ready.poke(true)
    dut.clock.step()
    dut.io.storeResult.ready.poke(false)
  }

  private def issueRefill(
      dut: L1DLoadCache,
      words: Seq[BigInt],
      fault: Boolean = false,
      lineAddress: BigInt = BigInt("80001000", 16),
      mshrIndex: Int = 0
  ): Unit = {
    dut.io.l2Lookup.valid.expect(true)
    dut.io.l2Lookup.bits.lineAddress.expect(lineAddress)
    dut.io.l2Lookup.ready.poke(true)
    dut.clock.step()
    dut.io.l2Lookup.ready.poke(false)
    dut.io.l2Response.valid.poke(true)
    dut.io.l2Response.bits.hit.poke(false)
    dut.io.l2Response.bits.transfer.lineAddress.poke(lineAddress)
    dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
    dut.io.l2Response.bits.transfer.dirty.poke(false)
    dut.io.l2Response.ready.expect(true)
    dut.clock.step()
    dut.io.l2Response.valid.poke(false)
    dut.io.dataRequest.valid.expect(true)
    dut.io.dataRequest.bits.client.expect(L2DemandClient.Data)
    dut.io.dataRequest.bits.clientMshr.expect(mshrIndex)
    dut.io.dataRequest.bits.lineAddress.expect(lineAddress)
    dut.io.dataRequest.ready.poke(true)
    dut.clock.step()
    dut.io.dataRequest.ready.poke(false)
    dut.io.dataResponse.valid.poke(true)
    dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
    dut.io.dataResponse.bits.clientMshr.poke(mshrIndex)
    dut.io.dataResponse.bits.accessFault.poke(fault)
    for ((word, index) <- words.zipWithIndex) {
      dut.io.dataResponse.bits.lineData(index).poke(word)
    }
    dut.io.dataResponse.ready.expect(true)
    dut.clock.step()
    dut.io.dataResponse.valid.poke(false)
  }

  private def startAxiRefill(
      dut: L1DLoadCache,
      lineAddress: BigInt,
      mshrIndex: Int = 0
  ): Unit = {
    dut.io.l2Lookup.valid.expect(true)
    dut.io.l2Lookup.bits.lineAddress.expect(lineAddress)
    dut.io.l2Lookup.ready.poke(true)
    dut.clock.step()
    dut.io.l2Lookup.ready.poke(false)
    dut.io.l2Response.valid.poke(true)
    dut.io.l2Response.bits.hit.poke(false)
    dut.io.l2Response.bits.transfer.lineAddress.poke(lineAddress)
    dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
    dut.io.l2Response.bits.transfer.dirty.poke(false)
    dut.io.l2Response.ready.expect(true)
    dut.clock.step()
    dut.io.l2Response.valid.poke(false)
    dut.io.dataRequest.valid.expect(true)
    dut.io.dataRequest.bits.client.expect(L2DemandClient.Data)
    dut.io.dataRequest.bits.clientMshr.expect(mshrIndex)
    dut.io.dataRequest.bits.lineAddress.expect(lineAddress)
    dut.io.dataRequest.ready.poke(true)
    dut.clock.step()
    dut.io.dataRequest.ready.poke(false)
  }

  describe("L1DLoadCache") {
    it("rejects a non-cacheable M0 forward without a refill or completion") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        dut.io.request(0).valid.poke(true)
        dut.io.request(0).bits.robTag.poke(3)
        dut.io.request(0).bits.address.poke(BigInt("a0000000", 16))
        dut.io.request(0).bits.readMask.poke(15)
        dut.io.request(0).bits.forwardMask.poke(0)
        dut.io.request(0).bits.forwardData.poke(0)
        dut.io.request(0).bits.requiresCache.poke(true)
        dut.io.request(0).bits.cacheable.poke(false)
        dut.io.request(0).ready.expect(false)
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
        dut.io.l2Lookup.valid.expect(true)
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
        dut.io.l2Lookup.valid.expect(true)
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

    it("moves every dirty resident line into L2 during a cache-global FENCE drain") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80001800", 16)
        val words = Seq.tabulate(8)(word => BigInt("12340000", 16) + word)
        submit(dut, tag = 1, line)
        issueRefill(dut, words, lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.io.completion.valid.expect(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.clock.step()

        submitStore(dut, tag = 2, line + 12, mask = 15, data = BigInt("deadbeef", 16))
        consumeStoreResult(dut, tag = 2, line + 12)

        dut.io.fenceDrain.poke(true)
        dut.io.request(0).valid.poke(true)
        dut.io.request(0).bits.cacheable.poke(true)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(line)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(3).expect(BigInt("deadbeef", 16))
        dut.io.fenceDrained.expect(false)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.fenceDrained.expect(true)
        dut.io.l2Insert.valid.expect(false)
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
        startAxiRefill(dut, lineC)

        submit(dut, tag = 4, lineA)
        dut.io.completion.valid.expect(false)
        dut.io.l2Lookup.valid.expect(true)
      }
    }

    it("merges same-line secondary misses and preserves their exact ROB tags") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val words = (0 until 8).map(index => BigInt("b0000000", 16) + index)
        submit(dut, tag = 1, BigInt("80001000", 16))
        dut.io.l2Lookup.valid.expect(true)
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

    it("updates a committed store hit in place and returns its exact result") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80001000", 16)
        val words = (0 until 8).map(index => BigInt("c0000000", 16) + index)
        submit(dut, tag = 1, line)
        issueRefill(dut, words)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submitStore(dut, tag = 2, line, mask = 6, data = BigInt("00aabb00", 16))
        dut.io.storeBusy.expect(true)
        consumeStoreResult(dut, tag = 2, line)
        dut.io.storeBusy.expect(false)

        submit(dut, tag = 3, line)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(BigInt("c0aabb00", 16))
      }
    }

    it("write-allocates a committed store miss before reporting its result") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80001800", 16)
        val words = Seq.tabulate(8)(word => BigInt("e0000000", 16) + word)
        submitStore(dut, tag = 5, line + 4, mask = 3, data = BigInt("0000beef", 16))
        dut.io.l2Lookup.valid.expect(true)
        issueRefill(dut, words, lineAddress = line)
        consumeStoreResult(dut, tag = 5, line + 4)

        submit(dut, tag = 6, line + 4)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.cacheData.expect(BigInt("e000beef", 16))
      }
    }

    it("returns an exact store fault and leaves a failed allocation invalid") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80001c00", 16)
        submitStore(dut, tag = 7, line, mask = 15, data = BigInt("facefeed", 16))
        issueRefill(dut, Seq.fill(8)(BigInt(0)), fault = true, lineAddress = line)
        consumeStoreResult(dut, tag = 7, line, fault = true)

        submit(dut, tag = 8, line)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.completion.valid.expect(false)
      }
    }

    it("does not transfer a victim for a backpressured second store") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val firstLine = BigInt("80002400", 16)
        val secondLine = BigInt("80002600", 16)
        submitStore(dut, tag = 1, firstLine, mask = 15, data = 1)
        dut.io.l2Lookup.valid.expect(true)

        dut.io.storeRequest.valid.poke(true)
        dut.io.storeRequest.bits.robTag.poke(2)
        dut.io.storeRequest.bits.address.poke(secondLine)
        dut.io.storeRequest.bits.accessSize.poke(2)
        dut.io.storeRequest.bits.writeMask.poke(15)
        dut.io.storeRequest.bits.writeData.poke(2)
        dut.io.storeRequest.bits.isAtomic.poke(false)
        dut.io.storeRequest.bits.pmaKind.poke(PMARegionKind.Memory.code)
        dut.io.storeRequest.bits.aq.poke(false)
        dut.io.storeRequest.bits.rl.poke(false)
        dut.io.storeRequest.ready.expect(false)
        dut.io.l2Insert.valid.expect(false)
      }
    }

    it("blocks an external atomic while a matching L1D line is dirty") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80002000", 16)
        submit(dut, tag = 1, line)
        issueRefill(dut, Seq.fill(8)(BigInt("01020304", 16)), lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 2, line, mask = 15, data = BigInt("aabbccdd", 16))
        consumeStoreResult(dut, tag = 2, line)

        dut.io.atomicAccept.valid.poke(true)
        dut.io.atomicAccept.bits.address.poke(line)
        dut.io.atomicAcceptReady.expect(false)
        dut.io.atomicRequiresExternal.poke(false)
        dut.io.atomicAcceptReady.expect(true)
      }
    }

    it("transfers a dirty L1D victim to L2 after a committed store") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lineA = BigInt("80001000", 16)
        val lineB = BigInt("80001200", 16)
        val lineC = BigInt("80001400", 16)
        val wordsA = Seq.tabulate(8)(word => BigInt("11000000", 16) + word)
        val wordsB = Seq.tabulate(8)(word => BigInt("22000000", 16) + word)

        submit(dut, tag = 1, lineA)
        issueRefill(dut, wordsA, lineAddress = lineA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, lineB)
        issueRefill(dut, wordsB, lineAddress = lineB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submitStore(dut, tag = 3, lineA, mask = 15, data = BigInt("decafbad", 16))
        consumeStoreResult(dut, tag = 3, lineA)

        dut.io.request(0).valid.poke(true)
        dut.io.request(0).bits.robTag.poke(4)
        dut.io.request(0).bits.address.poke(lineC)
        dut.io.request(0).bits.readMask.poke(15)
        dut.io.request(0).bits.forwardMask.poke(0)
        dut.io.request(0).bits.forwardData.poke(0)
        dut.io.request(0).bits.requiresCache.poke(true)
        dut.io.request(0).bits.cacheable.poke(true)
        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(lineA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(0).expect(BigInt("decafbad", 16))
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
      }
    }

    it("flushes only an exact resident dirty line through the L2 transfer boundary") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80004800", 16)
        val words = Seq.tabulate(8)(word => BigInt("33000000", 16) + word)
        submit(dut, tag = 1, line)
        issueRefill(dut, words, lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 2, line, mask = 15, data = BigInt("decafbad", 16))
        consumeStoreResult(dut, tag = 2, line)

        dut.io.flushLine.valid.poke(true)
        dut.io.flushLine.bits.poke(line)
        dut.io.flushLine.ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(line)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(0).expect(BigInt("decafbad", 16))
        dut.clock.step()
        dut.io.flushLine.valid.poke(false)

        submit(dut, tag = 3, line)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.completion.valid.expect(false)
      }
    }

    it("acknowledges and invalidates clean or absent targeted lines") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80004c00", 16)
        submit(dut, tag = 1, line)
        issueRefill(dut, Seq.fill(8)(BigInt("12345678", 16)), lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        dut.io.flushLine.valid.poke(true)
        dut.io.flushLine.bits.poke(line)
        dut.io.flushLine.ready.expect(true)
        dut.io.l2Insert.valid.expect(false)
        dut.clock.step()
        dut.io.flushLine.valid.poke(false)
        submit(dut, tag = 2, line)
        dut.io.l2Lookup.valid.expect(true)

        dut.io.flushLine.valid.poke(true)
        dut.io.flushLine.bits.poke(BigInt("80005000", 16))
        dut.io.flushLine.ready.expect(true)
        dut.io.l2Insert.valid.expect(false)
      }
    }

    it("fills an L2 transfer hit without issuing an AXI refill") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80003000", 16)
        val words = Seq.tabulate(8)(word => BigInt("d0000000", 16) + word)
        submit(dut, tag = 7, line + 12)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(true)
        dut.io.l2Response.bits.transfer.lineAddress.poke(line)
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        words.zipWithIndex.foreach { case (word, index) =>
          dut.io.l2Response.bits.transfer.lineData(index).poke(word)
        }
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(3))
      }
    }

    it("updates an L2-hit line across consecutive byte store hits") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80003000", 16)
        val words = Seq.fill(8)(BigInt("efefefef", 16))
        submit(dut, tag = 1, line)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(true)
        dut.io.l2Response.bits.transfer.lineAddress.poke(line)
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        words.foreach(word => dut.io.l2Response.bits.transfer.lineData(words.indexOf(word)).poke(word))
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submitStore(dut, tag = 2, line, mask = 1, data = BigInt("000000aa", 16))
        consumeStoreResult(dut, tag = 2, line)
        submitStore(dut, tag = 3, line + 1, mask = 2, data = 0)
        consumeStoreResult(dut, tag = 3, line + 1)

        presentLoad(dut, lane = 0, tag = 4, address = line + 1)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.cacheData.expect(BigInt("efef00aa", 16))
      }
    }

    it("accepts two hit loads from different word banks and retains both exact results") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80005000", 16)
        val words = Seq.tabulate(8)(word => BigInt("44000000", 16) + word)
        submit(dut, tag = 1, line)
        issueRefill(dut, words, lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // Lane 1 is older, proving result selection is by ROB age rather than
        // input lane. Words 1 and 2 map to different four-way data banks.
        presentLoad(dut, lane = 0, tag = 7, address = line + 4)
        presentLoad(dut, lane = 1, tag = 3, address = line + 8)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(1))
      }
    }

    it("backpressures the younger same-bank hit until the older request is accepted") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80005400", 16)
        val words = Seq.tabulate(8)(word => BigInt("55000000", 16) + word)
        submit(dut, tag = 1, line)
        issueRefill(dut, words, lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // Words 0 and 4 share bank 0. Lane 1 carries the older ROB tag.
        presentLoad(dut, lane = 0, tag = 7, address = line)
        presentLoad(dut, lane = 1, tag = 3, address = line + 16)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(4))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(0))
      }
    }

    it("backpressures a same-address duplicate for its conflicting acceptance cycle") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80005800", 16)
        val words = Seq.tabulate(8)(word => BigInt("66000000", 16) + word)
        submit(dut, tag = 1, line)
        issueRefill(dut, words, lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        presentLoad(dut, lane = 0, tag = 2, address = line + 8)
        presentLoad(dut, lane = 1, tag = 6, address = line + 8)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(false)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        // The duplicate is blocked only while both ports contend for the same
        // bank. It can use its own retained-result slot on the next cycle.
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(2)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(6)
        dut.io.completion.bits.cacheData.expect(words(2))
      }
    }

    it("merges two same-line misses into one refill with exact waiter completions") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80005c00", 16)
        val words = Seq.tabulate(8)(word => BigInt("77000000", 16) + word)

        // The two lanes request different words from one absent line. Lane 1
        // is older, so it must retire first even though its waiter is second.
        presentLoad(dut, lane = 0, tag = 7, address = line + 4)
        presentLoad(dut, lane = 1, tag = 3, address = line + 20)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        issueRefill(dut, words, lineAddress = line)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(5))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(1))
      }
    }

    it("merges same-address cold misses into one refill with duplicate-word waiters") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80005e00", 16)
        val words = Seq.tabulate(8)(word => BigInt("78000000", 16) + word)

        // A cold same-address pair is a same-line miss, not a dual-hit bank
        // conflict. Both ports may attach exact waiters to the one MSHR.
        // Lane 1 is older, so completion still follows ROB age.
        presentLoad(dut, lane = 0, tag = 7, address = line + 12)
        presentLoad(dut, lane = 1, tag = 3, address = line + 12)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        issueRefill(dut, words, lineAddress = line)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(3))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(3))
      }
    }

    it("merges a live MSHR waiter while accepting an independent miss") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val liveLine = BigInt("80005f00", 16)
        val independentLine = BigInt("80006000", 16)
        val liveWords = Seq.tabulate(8)(word => BigInt("79000000", 16) + word)
        val independentWords = Seq.tabulate(8)(word => BigInt("7a000000", 16) + word)

        // Establish a live miss owner without returning its refill. The next
        // cycle attaches the older lane to that MSHR; the younger independent
        // miss remains valid for replay because the transfer path is one-wide.
        submit(dut, tag = 1, address = liveLine + 4)
        dut.io.l2Lookup.valid.expect(true)

        presentLoad(dut, lane = 0, tag = 3, address = liveLine + 12)
        presentLoad(dut, lane = 1, tag = 7, address = independentLine + 20)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(false)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        // The already-live line owns one MSHR and now has two exact waiters;
        // the independent request is still held at ingress and must not
        // fabricate a second owner while the older merge is being accepted.
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(liveLine)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(false)
        dut.io.l2Response.bits.transfer.lineAddress.poke(liveLine)
        dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)
        dut.io.dataRequest.valid.expect(true)
        dut.io.dataRequest.bits.clientMshr.expect(0)
        dut.io.dataRequest.bits.lineAddress.expect(liveLine)
        dut.io.dataRequest.ready.poke(true)
        dut.clock.step()
        dut.io.dataRequest.ready.poke(false)
        dut.io.dataResponse.valid.poke(true)
        dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
        dut.io.dataResponse.bits.clientMshr.poke(0)
        dut.io.dataResponse.bits.accessFault.poke(false)
        liveWords.zipWithIndex.foreach { case (word, index) =>
          dut.io.dataResponse.bits.lineData(index).poke(word)
        }
        dut.io.dataResponse.ready.expect(true)
        dut.clock.step()
        dut.io.dataResponse.valid.poke(false)

        // The original owner retires first; the merged waiter follows by ROB
        // age, then the held independent miss is accepted and refilled.
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.bits.cacheData.expect(liveWords(1))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(liveWords(3))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(independentLine)
        issueRefill(dut, independentWords, lineAddress = independentLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(independentWords(5))
      }
    }

    it("accepts a same-set hit and miss when an invalid way is available") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val hitLine = BigInt("80005c00", 16)
        val missLine = BigInt("80006000", 16)
        val words = Seq.tabulate(8)(word => BigInt("66000000", 16) + word)

        submit(dut, tag = 1, address = hitLine)
        issueRefill(dut, words, lineAddress = hitLine)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // Lane 1 is older. The other way is invalid, so the miss can reserve
        // it without invalidating the hit-visible line.
        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Lookup.valid.expect(false)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))
        issueRefill(dut, words, lineAddress = missLine)

        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(1))
      }
    }

    it("accepts a different-set hit and miss together with exact owners") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val hitLine = BigInt("80006400", 16)
        val missLine = BigInt("80006500", 16)
        val words = Seq.tabulate(8)(word => BigInt("55000000", 16) + word)

        submit(dut, tag = 1, address = hitLine)
        issueRefill(dut, words, lineAddress = hitLine)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // These lines use different L1D sets. The old hit owns one retained
        // result slot and the young miss owns one waiter/MSHR; neither needs a
        // victim transfer, so both handshakes are legal in the same cycle.
        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        issueRefill(dut, words, lineAddress = missLine)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(words(1))
      }
    }

    it("accepts a younger clean-victim miss beside an older different-set hit") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("80008000", 16)
        val residentB = BigInt("80008200", 16)
        val missLine = BigInt("80008400", 16)
        val hitLine = BigInt("80008100", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("51000000", 16) + word)
        val hitWords = Seq.tabulate(8)(word => BigInt("52000000", 16) + word)
        val refillWords = Seq.tabulate(8)(word => BigInt("53000000", 16) + word)

        // Fill both ways of the miss set, then populate a distinct hit set.
        // The same-set addresses are 0x200 apart for the fixed 16-set L1D.
        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 3, address = hitLine)
        issueRefill(dut, hitWords, lineAddress = hitLine)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // Lane 0 is younger and requires the sole clean L1D-to-L2 transfer;
        // lane 1 is an immediate hit in another set. The hit retains its own
        // result while the miss transfers its exact clean victim in the same
        // acceptance cycle.
        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(false)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        issueRefill(dut, refillWords, lineAddress = missLine)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(hitWords(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(refillWords(1))
      }
    }

    it("accepts a younger dirty-victim miss beside an older different-set hit") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("80008800", 16)
        val residentB = BigInt("80008a00", 16)
        val missLine = BigInt("80008c00", 16)
        val hitLine = BigInt("80008900", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("61000000", 16) + word)
        val hitWords = Seq.tabulate(8)(word => BigInt("62000000", 16) + word)
        val refillWords = Seq.tabulate(8)(word => BigInt("63000000", 16) + word)

        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 3, address = hitLine)
        issueRefill(dut, hitWords, lineAddress = hitLine)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // The miss transfers one dirty victim through the sole L1D-to-L2
        // record while the older independent hit retains its exact result.
        submitStore(dut, tag = 4, address = residentA + 4,
          mask = 15, data = BigInt("dead0001", 16))
        consumeStoreResult(dut, tag = 4, address = residentA + 4)
        submitStore(dut, tag = 5, address = residentB + 4,
          mask = 15, data = BigInt("dead0002", 16))
        consumeStoreResult(dut, tag = 5, address = residentB + 4)

        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("dead0001", 16))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))
        issueRefill(dut, refillWords, lineAddress = missLine)

        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(hitWords(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(refillWords(1))
      }
    }

    it("holds a younger dirty-victim miss through L2 backpressure after an older hit") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("80008e00", 16)
        val residentB = BigInt("80009000", 16)
        val missLine = BigInt("80009200", 16)
        val hitLine = BigInt("80008f00", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("66000000", 16) + word)
        val hitWords = Seq.tabulate(8)(word => BigInt("67000000", 16) + word)
        val refillWords = Seq.tabulate(8)(word => BigInt("68000000", 16) + word)

        // The replacement set has two dirty residents; the independent old
        // hit should not be held behind an unavailable L1D-to-L2 transfer.
        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 3, address = hitLine)
        issueRefill(dut, hitWords, lineAddress = hitLine)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 4, address = residentA + 4,
          mask = 15, data = BigInt("dead0005", 16))
        consumeStoreResult(dut, tag = 4, address = residentA + 4)
        submitStore(dut, tag = 5, address = residentB + 4,
          mask = 15, data = BigInt("dead0006", 16))
        consumeStoreResult(dut, tag = 5, address = residentB + 4)

        dut.io.l2Insert.ready.poke(false)
        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        // The dirty transfer may be offered while L2 is backpressured, but
        // the younger miss cannot allocate until that held offer fires.
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("dead0005", 16))
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        // Once the old result owns its slot, the retained miss exposes its
        // exact dirty victim but cannot allocate until that transfer fires.
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("dead0005", 16))
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(true)

        dut.io.l2Insert.ready.poke(true)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Insert.ready.poke(false)
        issueRefill(dut, refillWords, lineAddress = missLine)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(refillWords(1))
      }
    }

    it("replays a younger dirty-victim miss behind an older different-set miss") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("8000a000", 16)
        val residentB = BigInt("8000a200", 16)
        val youngerLine = BigInt("8000a400", 16)
        val olderLine = BigInt("8000a100", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("71000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("72000000", 16) + word)
        val youngerWords = Seq.tabulate(8)(word => BigInt("73000000", 16) + word)

        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 4, address = residentA + 4,
          mask = 15, data = BigInt("face0001", 16))
        consumeStoreResult(dut, tag = 4, address = residentA + 4)
        submitStore(dut, tag = 5, address = residentB + 4,
          mask = 15, data = BigInt("face0002", 16))
        consumeStoreResult(dut, tag = 5, address = residentB + 4)

        // The older request can use its invalid way. The younger request needs
        // the sole dirty-victim transfer, so it remains replayable until the
        // older miss has crossed the ingress boundary.
        presentLoad(dut, lane = 0, tag = 7, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(false)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("face0001", 16))
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        issueRefill(dut, olderWords, lineAddress = olderLine, mshrIndex = 0)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(olderWords(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        issueRefill(dut, youngerWords, lineAddress = youngerLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(youngerWords(1))
      }
    }

    it("serializes two dirty-victim misses while preserving both transfer owners") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerResidentA = BigInt("8000b000", 16)
        val youngerResidentB = BigInt("8000b200", 16)
        val youngerLine = BigInt("8000b400", 16)
        val olderResidentA = BigInt("8000b100", 16)
        val olderResidentB = BigInt("8000b300", 16)
        val olderLine = BigInt("8000b500", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("91000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("92000000", 16) + word)
        val youngerWords = Seq.tabulate(8)(word => BigInt("93000000", 16) + word)

        // Fill both ways in two separate sets, then dirty the selected
        // replacement way in each set. Both pending lines therefore need the
        // single L1D-to-L2 transfer boundary, even though they own distinct
        // MSHR/waiter resources.
        Seq(youngerResidentA, youngerResidentB, olderResidentA, olderResidentB)
          .zipWithIndex.foreach { case (line, index) =>
            submit(dut, tag = index + 1, address = line)
            issueRefill(dut, residentWords, lineAddress = line)
            dut.io.completion.ready.poke(true)
            dut.clock.step()
            dut.io.completion.ready.poke(false)
          }
        Seq(
          (youngerResidentA, BigInt("d00d0001", 16)),
          (youngerResidentB, BigInt("d00d0002", 16)),
          (olderResidentA, BigInt("d00d0003", 16)),
          (olderResidentB, BigInt("d00d0004", 16))
        ).zipWithIndex.foreach { case ((line, data), index) =>
          submitStore(dut, tag = 5 + index, address = line + 4, mask = 15, data = data)
          consumeStoreResult(dut, tag = 5 + index, address = line + 4)
        }

        // Lane 1 is older. The L2 transfer port admits only its dirty victim;
        // the younger lane remains replayable without consuming an unowned
        // MSHR or overwriting the first transfer payload.
        presentLoad(dut, lane = 0, tag = 13, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 11, address = olderLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(olderResidentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("d00d0003", 16))
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        // Once the oldest transfer is owned, the younger replay receives the
        // next cycle's sole transfer credit and must carry its own dirty word.
        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(youngerResidentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("d00d0001", 16))
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        issueRefill(dut, olderWords, lineAddress = olderLine, mshrIndex = 0)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(11)
        dut.io.completion.bits.cacheData.expect(olderWords(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        issueRefill(dut, youngerWords, lineAddress = youngerLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(13)
        dut.io.completion.bits.cacheData.expect(youngerWords(1))
      }
    }

    it("squashes a younger dirty-victim replay after the older transfer fires") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerResidentA = BigInt("8000b800", 16)
        val youngerResidentB = BigInt("8000ba00", 16)
        val youngerLine = BigInt("8000bc00", 16)
        val olderResidentA = BigInt("8000b900", 16)
        val olderResidentB = BigInt("8000bb00", 16)
        val olderLine = BigInt("8000bd00", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("94000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("95000000", 16) + word)

        Seq(youngerResidentA, youngerResidentB, olderResidentA, olderResidentB)
          .zipWithIndex.foreach { case (line, index) =>
            submit(dut, tag = index + 1, address = line)
            issueRefill(dut, residentWords, lineAddress = line)
            dut.io.completion.ready.poke(true)
            dut.clock.step()
            dut.io.completion.ready.poke(false)
          }
        Seq(youngerResidentA, youngerResidentB, olderResidentA, olderResidentB)
          .zipWithIndex.foreach { case (line, index) =>
            submitStore(dut, tag = index + 5, address = line + 4,
              mask = 15, data = BigInt("d00e0000", 16) + index)
            consumeStoreResult(dut, tag = index + 5, address = line + 4)
          }

        // The older lane crosses the sole L1D-to-L2 transfer boundary. The
        // younger lane remains an unaccepted replay and has not claimed an
        // MSHR, victim, or L2 transaction of its own.
        presentLoad(dut, lane = 0, tag = 13, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 11, address = olderLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(olderResidentA)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        // Selective recovery removes lane 0 while retaining the accepted
        // older owner. No second dirty-victim transfer may appear for the
        // killed line after the recovery boundary.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(11)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.request(0).valid.poke(false)
        dut.io.l2Insert.valid.expect(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(olderLine)

        issueRefill(dut, olderWords, lineAddress = olderLine)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(11)
        dut.io.completion.bits.cacheData.expect(olderWords(2))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.l2Insert.valid.expect(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("flushes an unaccepted dirty-victim replay after the older transfer fires") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerResidentA = BigInt("8000be00", 16)
        val youngerResidentB = BigInt("8000c000", 16)
        val youngerLine = BigInt("8000c200", 16)
        val olderResidentA = BigInt("8000bf00", 16)
        val olderResidentB = BigInt("8000c100", 16)
        val olderLine = BigInt("8000c300", 16)
        val freshLine = BigInt("8000c500", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("96000000", 16) + word)

        Seq(youngerResidentA, youngerResidentB, olderResidentA, olderResidentB)
          .zipWithIndex.foreach { case (line, index) =>
            submit(dut, tag = index + 1, address = line)
            issueRefill(dut, residentWords, lineAddress = line)
            dut.io.completion.ready.poke(true)
            dut.clock.step()
            dut.io.completion.ready.poke(false)
          }
        Seq(youngerResidentA, youngerResidentB, olderResidentA, olderResidentB)
          .zipWithIndex.foreach { case (line, index) =>
            submitStore(dut, tag = index + 5, address = line + 4,
              mask = 15, data = BigInt("d00f0000", 16) + index)
            consumeStoreResult(dut, tag = index + 5, address = line + 4)
          }

        // Lane 1 owns the only accepted dirty-victim transfer. Lane 0 remains
        // replayable and cannot acquire a second transfer in the same cycle.
        presentLoad(dut, lane = 0, tag = 13, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 11, address = olderLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(olderResidentA)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        // Global flush must remove both local demand records. The accepted
        // victim transfer remains L2-owned, but neither demand may emit a
        // stale probe, AXI refill, or completion after the recovery boundary.
        dut.io.flush.poke(true)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.request(0).valid.poke(false)
        dut.io.l2Insert.valid.expect(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // A new demand establishes that the flushed owner and the replay did
        // not leak either an MSHR or waiter credit.
        presentLoad(dut, lane = 0, tag = 15, address = freshLine + 4)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(freshLine)
      }
    }

    it("accepts different-set dual misses with two exact MSHR owners") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lane0Line = BigInt("80006800", 16)
        val lane1Line = BigInt("80006900", 16)
        val lane0Words = Seq.tabulate(8)(word => BigInt("44000000", 16) + word)
        val lane1Words = Seq.tabulate(8)(word => BigInt("33000000", 16) + word)

        // Both misses use empty, different sets. Each must reserve its own
        // invalid way and MSHR, while the downstream L2 probe remains one-wide.
        presentLoad(dut, lane = 0, tag = 7, address = lane0Line + 4)
        presentLoad(dut, lane = 1, tag = 3, address = lane1Line + 20)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        issueRefill(dut, lane0Words, lineAddress = lane0Line, mshrIndex = 0)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(lane0Words(1))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        issueRefill(dut, lane1Words, lineAddress = lane1Line, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(lane1Words(5))
      }
    }

    it("keeps dual AXI refills bound to their MSHRs across reverse response order") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerLine = BigInt("80006a00", 16)
        val olderLine = BigInt("80006b00", 16)
        val youngerWords = Seq.tabulate(8)(word => BigInt("51000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("52000000", 16) + word)

        // The two lanes reserve separate MSHRs in one cycle. Both requests
        // then cross the serialized L2 probe and shared AXI boundaries before
        // either response returns, so the physical response order is not an
        // allocation-order proxy.
        presentLoad(dut, lane = 0, tag = 7, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine + 20)
        dut.io.request.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        startAxiRefill(dut, youngerLine, mshrIndex = 0)
        startAxiRefill(dut, olderLine, mshrIndex = 1)

        // ID/MSHR 0 returns first even though it is younger architecturally.
        // Its data and completion must stay attached to tag 7 rather than the
        // older waiter's tag or word offset.
        dut.io.dataResponse.valid.poke(true)
        dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
        dut.io.dataResponse.bits.clientMshr.poke(0)
        dut.io.dataResponse.bits.accessFault.poke(false)
        youngerWords.zipWithIndex.foreach { case (word, index) =>
          dut.io.dataResponse.bits.lineData(index).poke(word)
        }
        dut.io.dataResponse.ready.expect(true)
        dut.clock.step()
        dut.io.dataResponse.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.bits.cacheData.expect(youngerWords(1))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // The delayed older response remains live in MSHR 1 and produces its
        // own exact word and tag after the first owner has drained.
        dut.io.dataResponse.valid.poke(true)
        dut.io.dataResponse.bits.clientMshr.poke(1)
        olderWords.zipWithIndex.foreach { case (word, index) =>
          dut.io.dataResponse.bits.lineData(index).poke(word)
        }
        dut.io.dataResponse.ready.expect(true)
        dut.clock.step()
        dut.io.dataResponse.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(olderWords(5))
      }
    }

    it("accepts same-set dual misses when both ways are invalid") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerLine = BigInt("80006000", 16)
        val olderLine = BigInt("80006400", 16)

        // Both ways are invalid, so the pair can reserve one way per line and
        // retain two independent owners while L2 probes remain one-wide.
        presentLoad(dut, lane = 0, tag = 7, address = youngerLine)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine)
        dut.io.request(0).ready.expect(true)
        dut.io.request(1).ready.expect(true)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(youngerLine)
        issueRefill(dut,
          Seq.tabulate(8)(word => BigInt("41000000", 16) + word),
          lineAddress = youngerLine, mshrIndex = 0)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        issueRefill(dut,
          Seq.tabulate(8)(word => BigInt("42000000", 16) + word),
          lineAddress = olderLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
      }
    }

    it("serializes same-set dual misses when only one way is invalid") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentLine = BigInt("8000e000", 16)
        val youngerLine = BigInt("8000e200", 16)
        val olderLine = BigInt("8000e400", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("61000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("62000000", 16) + word)

        // One line occupies a way in this set, leaving exactly one invalid
        // way. The two candidates map to the same set but different tags.
        // There is therefore no legal two-owner admission: only the older
        // ROB request may cross the ingress boundary.
        submit(dut, tag = 1, address = residentLine)
        issueRefill(dut, residentWords, lineAddress = residentLine)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        presentLoad(dut, lane = 0, tag = 7, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine + 12)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(false)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(olderLine)

        issueRefill(dut, olderWords, lineAddress = olderLine, mshrIndex = 0)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(olderWords(3))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // The younger request stayed at the boundary and can now replay
        // using the released invalid-way/MSHR credit.
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(youngerLine)
      }
    }

    it("admits only the older same-set miss when both ways need replacement") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("80006000", 16)
        val residentB = BigInt("80006200", 16)
        val youngerLine = BigInt("80006400", 16)
        val olderLine = BigInt("80006600", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("43000000", 16) + word)

        // Fill both ways of one set so neither dual-miss candidate has an
        // invalid way. L2 insertion remains available for the single winner.
        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        // Lane 1 is older. Only it may cross the request boundary; the
        // younger miss remains replayable and no second MSHR is fabricated.
        presentLoad(dut, lane = 0, tag = 7, address = youngerLine)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(olderLine)
      }
    }

    it("backpressures a fifth miss when all four MSHRs are live") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lines = (0 until 5).map(index =>
          BigInt("80008000", 16) + BigInt(index * 0x100))

        // Keep all four existing MSHRs before their L2 probes are accepted.
        // The fifth independent line must remain valid at the ingress and
        // cannot fabricate a waiter or a speculative cache replacement.
        for ((line, index) <- lines.take(4).zipWithIndex) {
          submit(dut, tag = index + 1, address = line + 4)
        }
        presentLoad(dut, lane = 0, tag = 9, address = lines(4) + 4)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.request(0).valid.poke(false)
      }
    }

    it("backpressures a ninth waiter when one MSHR has all eight waiter credits") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80008400", 16)

        // Four same-line dual-miss pairs consume all eight LQ waiter slots
        // while sharing one MSHR and waiting behind the serialized L2 probe.
        for (pair <- 0 until 4) {
          presentLoad(dut, lane = 0, tag = 2 + pair * 2,
            address = line + pair * 8)
          presentLoad(dut, lane = 1, tag = 3 + pair * 2,
            address = line + pair * 8 + 4)
          dut.io.request(0).ready.expect(true)
          dut.io.request(1).ready.expect(true)
          dut.clock.step()
          dut.io.request.foreach(_.valid.poke(false))
        }

        presentLoad(dut, lane = 0, tag = 12, address = line)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.request(0).valid.poke(false)
      }
    }

    it("retries a ninth same-line request after a full waiter MSHR refills") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80008600", 16)
        val words = Seq.tabulate(8)(word => BigInt("a5000000", 16) + word)

        // Four same-line pairs consume every waiter credit while retaining one
        // MSHR. The ninth request must remain held until the refill makes the
        // line resident; it may then take the normal hit path without needing
        // an extra speculative waiter.
        for (pair <- 0 until 4) {
          presentLoad(dut, lane = 0, tag = 2 + pair * 2,
            address = line + pair * 8)
          presentLoad(dut, lane = 1, tag = 3 + pair * 2,
            address = line + pair * 8 + 4)
          dut.io.request(0).ready.expect(true)
          dut.io.request(1).ready.expect(true)
          dut.clock.step()
          dut.io.request.foreach(_.valid.poke(false))
        }

        presentLoad(dut, lane = 0, tag = 12, address = line + 28)
        dut.io.request(0).ready.expect(false)

        issueRefill(dut, words, lineAddress = line)

        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(12)
        dut.io.completion.bits.cacheData.expect(words(7))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
      }
    }

    it("releases a fully squashed waiter MSHR before its L2 probe is accepted") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val killedLine = BigInt("80008800", 16)
        val freshLine = BigInt("80008900", 16)

        // Four same-line dual pairs consume every waiter while the serialized
        // L2 probe remains unaccepted. Every tag is younger than the squash
        // boundary, so no architectural waiter survives the recovery.
        for (pair <- 0 until 4) {
          presentLoad(dut, lane = 0, tag = 2 + pair * 2,
            address = killedLine + pair * 8)
          presentLoad(dut, lane = 1, tag = 3 + pair * 2,
            address = killedLine + pair * 8 + 4)
          dut.io.request.foreach(_.ready.expect(true))
          dut.clock.step()
          dut.io.request.foreach(_.valid.poke(false))
        }
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(killedLine)

        // This MSHR has not crossed the L2 boundary, hence squash must drop
        // all eight waiters and release its credit without an L2/AXI drain or
        // a fabricated completion.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(1)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // A fresh miss must reclaim the released MSHR instead of inheriting a
        // stale waiter or serialized probe for the killed line.
        presentLoad(dut, lane = 0, tag = 10, address = freshLine + 12)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(freshLine)
      }
    }

    it("reclaims saturated waiter credits after selective squash while retaining an older owner") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80008a00", 16)
        val words = Seq.tabulate(8)(word => BigInt("d1000000", 16) + word)

        // One shared MSHR owns every waiter credit. Tag 2 is the sole
        // architectural survivor; the other seven waiters are younger than
        // the recovery boundary and must not pin the released credits.
        for (pair <- 0 until 4) {
          presentLoad(dut, lane = 0, tag = 2 + pair * 2,
            address = line + pair * 8)
          presentLoad(dut, lane = 1, tag = 3 + pair * 2,
            address = line + pair * 8 + 4)
          dut.io.request.foreach(_.ready.expect(true))
          dut.clock.step()
          dut.io.request.foreach(_.valid.poke(false))
        }
        dut.io.l2Lookup.valid.expect(true)

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(2)
        dut.clock.step()
        dut.io.squash.valid.poke(false)

        // The MSHR stays live for tag 2, but its reclaimed waiter credits let
        // a new same-line request merge before the serialized L2 probe fires.
        presentLoad(dut, lane = 0, tag = 10, address = line + 28)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        issueRefill(dut, words, lineAddress = line)
        dut.io.completion.ready.poke(true)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(2)
        dut.io.completion.bits.cacheData.expect(words(0))
        dut.clock.step()
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(10)
        dut.io.completion.bits.cacheData.expect(words(7))
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("drains an accepted saturated waiter probe after selective squash") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80008c00", 16)
        val words = Seq.tabulate(8)(word => BigInt("d2000000", 16) + word)

        // Fill every waiter credit on one MSHR, then let its serialized L2
        // probe cross the irrevocable ownership boundary.
        for (pair <- 0 until 4) {
          presentLoad(dut, lane = 0, tag = 2 + pair * 2,
            address = line + pair * 8)
          presentLoad(dut, lane = 1, tag = 3 + pair * 2,
            address = line + pair * 8 + 4)
          dut.io.request.foreach(_.ready.expect(true))
          dut.clock.step()
          dut.io.request.foreach(_.valid.poke(false))
        }
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(line)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)

        // Tag 2 remains architectural. The seven younger waiters may be
        // discarded, but the accepted probe and its later AXI owner cannot.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(2)
        dut.clock.step()
        dut.io.squash.valid.poke(false)

        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(false)
        dut.io.l2Response.bits.transfer.lineAddress.poke(line)
        dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)

        dut.io.dataRequest.valid.expect(true)
        dut.io.dataRequest.bits.client.expect(L2DemandClient.Data)
        dut.io.dataRequest.bits.clientMshr.expect(0)
        dut.io.dataRequest.bits.lineAddress.expect(line)
        dut.io.dataRequest.ready.poke(true)
        dut.clock.step()
        dut.io.dataRequest.ready.poke(false)
        dut.io.dataResponse.valid.poke(true)
        dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
        dut.io.dataResponse.bits.clientMshr.poke(0)
        dut.io.dataResponse.bits.accessFault.poke(false)
        words.zipWithIndex.foreach { case (word, index) =>
          dut.io.dataResponse.bits.lineData(index).poke(word)
        }
        dut.io.dataResponse.ready.expect(true)
        dut.clock.step()
        dut.io.dataResponse.valid.poke(false)

        dut.io.completion.ready.poke(true)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(2)
        dut.io.completion.bits.cacheData.expect(words(0))
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("holds a dirty-victim miss behind L2 transfer backpressure") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lineA = BigInt("80009000", 16)
        val lineB = BigInt("80009200", 16)
        val lineC = BigInt("80009400", 16)
        val wordsA = Seq.tabulate(8)(word => BigInt("81000000", 16) + word)
        val wordsB = Seq.tabulate(8)(word => BigInt("82000000", 16) + word)

        // Fill both ways of one set, then make each resident line dirty.
        submit(dut, tag = 1, address = lineA)
        issueRefill(dut, wordsA, lineAddress = lineA)
        dut.io.completion.ready.poke(true)
        dut.io.completion.valid.expect(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submit(dut, tag = 2, address = lineB)
        issueRefill(dut, wordsB, lineAddress = lineB)
        dut.io.completion.ready.poke(true)
        dut.io.completion.valid.expect(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        submitStore(dut, tag = 3, address = lineA + 4,
          mask = 15, data = BigInt("dead0001", 16))
        consumeStoreResult(dut, tag = 3, address = lineA + 4)
        submitStore(dut, tag = 4, address = lineB + 4,
          mask = 15, data = BigInt("dead0002", 16))
        consumeStoreResult(dut, tag = 4, address = lineB + 4)

        // lineC maps to the same set, so replacement requires the sole L2
        // transfer port. With that port backpressured, no MSHR is allocated.
        dut.io.l2Insert.ready.poke(false)
        presentLoad(dut, lane = 0, tag = 5, address = lineC)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(lineA)
        dut.io.l2Insert.bits.dirty.expect(true)

        dut.io.l2Insert.ready.poke(true)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Insert.ready.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(lineC)
      }
    }

    it("flushes a dirty-victim miss before its backpressured L2 transfer is accepted") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lineA = BigInt("80009600", 16)
        val lineB = BigInt("80009800", 16)
        val lineC = BigInt("80009a00", 16)
        val wordsA = Seq.tabulate(8)(word => BigInt("83000000", 16) + word)
        val wordsB = Seq.tabulate(8)(word => BigInt("84000000", 16) + word)
        val dirtyWord = BigInt("dead0003", 16)

        // Fill and dirty both ways of one set. A third line needs an L1D-to-L2
        // dirty transfer, but the external boundary retains backpressure.
        submit(dut, tag = 1, address = lineA)
        issueRefill(dut, wordsA, lineAddress = lineA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = lineB)
        issueRefill(dut, wordsB, lineAddress = lineB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 3, address = lineA + 4, mask = 15, data = dirtyWord)
        consumeStoreResult(dut, tag = 3, address = lineA + 4)
        submitStore(dut, tag = 4, address = lineB + 4,
          mask = 15, data = BigInt("dead0004", 16))
        consumeStoreResult(dut, tag = 4, address = lineB + 4)

        dut.io.l2Insert.ready.poke(false)
        presentLoad(dut, lane = 0, tag = 5, address = lineC)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(lineA)

        // The request has not crossed any external ownership boundary. Flush
        // must cancel it, retain the dirty resident owner, and leave no L2/AXI
        // or completion work behind.
        dut.io.flush.poke(true)
        dut.io.request(0).ready.expect(false)
        dut.io.l2Insert.valid.expect(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.request(0).valid.poke(false)
        dut.io.l2Insert.valid.expect(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)

        // The original dirty line remains its sole stable owner and is still a
        // local hit after recovery.
        submit(dut, tag = 6, address = lineA + 4)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(6)
        dut.io.completion.bits.cacheData.expect(dirtyWord)
      }
    }

    it("releases saturated MSHRs after a dirty victim transfer on global flush") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("8000c000", 16)
        val residentB = BigInt("8000c200", 16)
        val transferredMiss = BigInt("8000c400", 16)
        val unissuedLines = Seq(
          BigInt("8000c100", 16),
          BigInt("8000c140", 16),
          BigInt("8000c180", 16))
        val freshLine = BigInt("8000c1c0", 16)
        val words = Seq.tabulate(8)(word => BigInt("87000000", 16) + word)

        // The first miss replaces a dirty resident line and crosses only the
        // L1D-to-L2 transfer boundary. Keep its serialized lookup unaccepted,
        // then consume the remaining three MSHR credits with independent
        // misses. All four owners are local/cancellable at flush time.
        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, words, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, words, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 3, address = residentA + 4,
          mask = 15, data = BigInt("dead0007", 16))
        consumeStoreResult(dut, tag = 3, address = residentA + 4)
        submitStore(dut, tag = 4, address = residentB + 4,
          mask = 15, data = BigInt("dead0008", 16))
        consumeStoreResult(dut, tag = 4, address = residentB + 4)

        presentLoad(dut, lane = 0, tag = 5, address = transferredMiss + 4)
        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("dead0007", 16))
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        unissuedLines.zipWithIndex.foreach { case (line, index) =>
          submit(dut, tag = 6 + index, address = line + 4)
        }
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(transferredMiss)
        dut.io.dataRequest.valid.expect(false)

        // The transferred dirty victim remains owned by L2, but every local
        // demand is still cancellable. Flush cannot emit a stale lookup, AXI
        // refill, or completion, and it must release all four MSHR credits.
        dut.io.flush.poke(true)
        dut.io.l2Insert.valid.expect(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // A fresh line proves that neither the three unissued requests nor
        // the dirty-victim demand retained a hidden MSHR or waiter credit.
        presentLoad(dut, lane = 0, tag = 9, address = freshLine + 4)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(freshLine)
      }
    }

    it("releases a squashed dirty-victim owner after its L2 transfer fires") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("80009c00", 16)
        val residentB = BigInt("80009e00", 16)
        val killedLine = BigInt("8000a000", 16)
        val freshLine = BigInt("8000a200", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("85000000", 16) + word)

        // Make both ways dirty. The killed miss must hand the chosen victim
        // to L2 before it can reserve the replacement way in L1D.
        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 3, address = residentA + 4,
          mask = 15, data = BigInt("dead0007", 16))
        consumeStoreResult(dut, tag = 3, address = residentA + 4)
        submitStore(dut, tag = 4, address = residentB + 4,
          mask = 15, data = BigInt("dead0008", 16))
        consumeStoreResult(dut, tag = 4, address = residentB + 4)

        presentLoad(dut, lane = 0, tag = 7, address = killedLine + 4)
        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        // L2 now owns the victim, while the speculative L1D miss has not
        // issued a lookup. Selective recovery must release only the local
        // owner: no later probe, refill, or completion may represent tag 7.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // The former reservation is reusable, but the fresh request must own
        // a new line and must not revive the cancelled demand.
        presentLoad(dut, lane = 0, tag = 10, address = freshLine + 8)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(freshLine)
      }
    }

    it("drops a flushed dirty-victim demand after its transfer reaches L2") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val residentA = BigInt("8000a400", 16)
        val residentB = BigInt("8000a600", 16)
        val flushedLine = BigInt("8000a800", 16)
        val freshLine = BigInt("8000aa00", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("86000000", 16) + word)

        // Both residents map to the same set and become dirty. Admission of
        // flushedLine transfers exactly one victim to L2 before the demand
        // itself has crossed the serialized L2-probe boundary.
        submit(dut, tag = 1, address = residentA)
        issueRefill(dut, residentWords, lineAddress = residentA)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submit(dut, tag = 2, address = residentB)
        issueRefill(dut, residentWords, lineAddress = residentB)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        submitStore(dut, tag = 3, address = residentA + 4,
          mask = 15, data = BigInt("dead0009", 16))
        consumeStoreResult(dut, tag = 3, address = residentA + 4)
        submitStore(dut, tag = 4, address = residentB + 4,
          mask = 15, data = BigInt("dead000a", 16))
        consumeStoreResult(dut, tag = 4, address = residentB + 4)

        presentLoad(dut, lane = 0, tag = 7, address = flushedLine + 4)
        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

        // The transferred victim remains owned by L2, but the local MSHR
        // never issued a probe. Global flush must release it without a stale
        // probe, AXI refill, or architectural completion.
        dut.io.flush.poke(true)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // The released local owner may not be resurrected by the next miss.
        presentLoad(dut, lane = 0, tag = 10, address = freshLine + 8)
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(freshLine)
      }
    }

    it("drops only the younger dual-miss owner before L2 acceptance") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerLine = BigInt("80006c00", 16)
        val olderLine = BigInt("80006d00", 16)
        val words = Seq.tabulate(8)(word => BigInt("22000000", 16) + word)

        presentLoad(dut, lane = 0, tag = 7, address = youngerLine)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine + 12)
        dut.io.request.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        // The younger MSHR has not crossed the L2 boundary, so recovery may
        // release it. The older owner survives and becomes the sole probe.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(olderLine)
        issueRefill(dut, words, lineAddress = olderLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(3))
      }
    }

    it("keeps an older same-line waiter while squashing its merged peer") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80006e00", 16)
        val words = Seq.tabulate(8)(word => BigInt("24000000", 16) + word)

        // The two lanes deliberately merge into one unissued MSHR. Recovery
        // may clear only the younger waiter; it must not release the shared
        // demand owner that the older architectural load still needs.
        presentLoad(dut, lane = 0, tag = 7, address = line + 4)
        presentLoad(dut, lane = 1, tag = 3, address = line + 12)
        dut.io.request.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(line)
        issueRefill(dut, words, lineAddress = line)

        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(words(3))
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("drains an accepted younger dual-miss probe before serving the survivor") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerLine = BigInt("80007000", 16)
        val olderLine = BigInt("80007100", 16)
        val youngerWords = Seq.tabulate(8)(word => BigInt("11000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("10000000", 16) + word)

        presentLoad(dut, lane = 0, tag = 7, address = youngerLine)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine + 16)
        dut.io.request.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(youngerLine)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)

        // This owner crossed the L2 boundary. Squash removes its waiter but
        // not its transfer owner, which must consume its response first.
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(true)
        dut.io.l2Response.bits.transfer.lineAddress.poke(youngerLine)
        dut.io.l2Response.bits.transfer.lineData.zip(youngerWords).foreach {
          case (word, data) => word.poke(data)
        }
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)

        dut.io.completion.valid.expect(false)
        issueRefill(dut, olderWords, lineAddress = olderLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(olderWords(4))
      }
    }

    it("drains an issued squashed refill before serving its older survivor") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val youngerLine = BigInt("80007200", 16)
        val olderLine = BigInt("80007300", 16)
        val youngerWords = Seq.tabulate(8)(word => BigInt("12000000", 16) + word)
        val olderWords = Seq.tabulate(8)(word => BigInt("13000000", 16) + word)

        presentLoad(dut, lane = 0, tag = 7, address = youngerLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = olderLine + 16)
        dut.io.request.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        // The younger MSHR has crossed both the L2 and AXI ownership
        // boundaries. A subsequent squash may discard its waiter, but it
        // cannot abandon the physical response credit.
        startAxiRefill(dut, youngerLine)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.dataResponse.valid.poke(true)
        dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
        dut.io.dataResponse.bits.clientMshr.poke(0)
        dut.io.dataResponse.bits.accessFault.poke(false)
        youngerWords.zipWithIndex.foreach { case (word, index) =>
          dut.io.dataResponse.bits.lineData(index).poke(word)
        }
        dut.io.dataResponse.ready.expect(true)
        dut.clock.step()
        dut.io.dataResponse.valid.poke(false)

        dut.io.completion.valid.expect(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(olderLine)
        issueRefill(dut, olderWords, lineAddress = olderLine, mshrIndex = 1)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(3)
        dut.io.completion.bits.cacheData.expect(olderWords(4))
      }
    }

    it("flushes an unissued peer while draining the one accepted dual-miss probe") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val firstLine = BigInt("80007200", 16)
        val secondLine = BigInt("80007300", 16)

        presentLoad(dut, lane = 0, tag = 7, address = firstLine)
        presentLoad(dut, lane = 1, tag = 3, address = secondLine + 12)
        dut.io.request.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.request.foreach(_.valid.poke(false))

        // Only this transfer has crossed the L2 boundary. A full flush must
        // retain it as the unique response sink while removing its unissued
        // peer before that peer can claim the serialized probe port.
        dut.io.l2Lookup.valid.expect(true)
        val acceptedLine = dut.io.l2Lookup.bits.lineAddress.peek().litValue
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)

        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(false)
        dut.io.l2Response.bits.transfer.lineAddress.poke(acceptedLine)
        dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)

        // The flushed accepted owner may drain but cannot produce an AXI
        // fallback or completion; the peer was cancelled before probe issue.
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("drains a flushed L2 miss transfer without creating a fallback AXI refill") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80003800", 16)
        submit(dut, tag = 9, line)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)

        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(false)
        dut.io.l2Response.bits.transfer.lineAddress.poke(line)
        dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)
      }
    }

    it("releases unissued saturated MSHRs while draining one flushed L2 probe") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lines = Seq.tabulate(5)(index => BigInt("80003e00", 16) + index * 32)

        // Saturate the four-MSHR budget behind the serialized L2 port, then
        // let only the oldest owner cross that boundary. A full flush must
        // cancel the three unissued owners without abandoning the accepted
        // probe's response credit.
        lines.take(4).zipWithIndex.foreach { case (line, index) =>
          submit(dut, tag = index + 1, address = line)
        }
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(lines.head)
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)

        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // A miss response for the accepted owner is a drain-only event after
        // flush: no waiter remains, so it cannot fall through to AXI.
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(false)
        dut.io.l2Response.bits.transfer.lineAddress.poke(lines.head)
        dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)
        dut.clock.step()
        dut.io.l2Lookup.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.completion.valid.expect(false)

        // Once the drain-only owner releases its MSHR, a fresh request must
        // be admitted normally; no cancelled owner may retain a hidden credit.
        presentLoad(dut, lane = 0, tag = 9, address = lines(4))
        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(lines(4))
      }
    }

    it("drains an issued AXI refill after flush without completing its waiter") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val line = BigInt("80003c00", 16)
        val words = Seq.tabulate(8)(word => BigInt("44000000", 16) + word)
        submit(dut, tag = 9, line + 8)
        startAxiRefill(dut, line)

        // The data request has crossed the AXI ownership boundary. Flush may
        // remove its speculative waiter, but the retained owner must still
        // drain the exact response instead of orphaning the physical credit.
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.completion.valid.expect(false)
        dut.io.dataResponse.valid.poke(true)
        dut.io.dataResponse.bits.client.poke(L2DemandClient.Data)
        dut.io.dataResponse.bits.clientMshr.poke(0)
        dut.io.dataResponse.bits.accessFault.poke(false)
        words.zipWithIndex.foreach { case (word, index) =>
          dut.io.dataResponse.bits.lineData(index).poke(word)
        }
        dut.io.dataResponse.ready.expect(true)
        dut.clock.step()
        dut.io.dataResponse.valid.poke(false)

        dut.io.completion.valid.expect(false)
        dut.io.dataRequest.valid.expect(false)
        dut.io.l2Lookup.valid.expect(false)
      }
    }

    it("replays a fifth independent miss until one of four MSHRs drains") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lines = Seq.tabulate(5)(index => BigInt("80004000", 16) + index * 32)

        // Keep the one-wide L2 probe backpressured while four distinct-set
        // misses consume the frozen four-MSHR budget.
        lines.take(4).zipWithIndex.foreach { case (line, index) =>
          submit(dut, tag = index + 1, line)
          dut.io.l2Lookup.valid.expect(true)
          dut.io.dataRequest.valid.expect(false)
        }
        presentLoad(dut, lane = 0, tag = 5, lines(4))
        dut.io.request(0).ready.expect(false)
        dut.io.dataRequest.valid.expect(false)

        // An L2 hit completes the oldest owner without allocating an AXI
        // demand. Its released MSHR is the only credit that admits the fifth
        // logical miss on the following cycle.
        dut.io.l2Lookup.ready.poke(true)
        dut.clock.step()
        dut.io.l2Lookup.ready.poke(false)
        dut.io.l2Response.valid.poke(true)
        dut.io.l2Response.bits.hit.poke(true)
        dut.io.l2Response.bits.transfer.lineAddress.poke(lines.head)
        dut.io.l2Response.bits.transfer.lineData.foreach(_.poke(0))
        dut.io.l2Response.bits.transfer.dirty.poke(false)
        dut.io.l2Response.ready.expect(true)
        dut.clock.step()
        dut.io.l2Response.valid.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
      }
    }

    it("replays a fifth miss until an AXI-refill waiter releases its MSHR") {
      simulate(new L1DLoadCache) { dut =>
        clear(dut)
        val lines = Seq.tabulate(5)(index => BigInt("80004400", 16) + index * 32)
        val words = Seq.tabulate(8)(word => BigInt("c3000000", 16) + word)

        // Four independent misses consume every MSHR while their serialized
        // L2 probes are retained. The fifth request must survive this
        // backpressure and cannot claim an owner early.
        lines.take(4).zipWithIndex.foreach { case (line, index) =>
          submit(dut, tag = index + 1, line)
        }
        presentLoad(dut, lane = 0, tag = 5, lines(4))
        dut.io.request(0).ready.expect(false)

        // Unlike the L2-hit recovery case, this owner crosses the physical
        // AXI refill boundary. Credit is not free until its exact completion
        // consumes the only waiter.
        issueRefill(dut, words, lineAddress = lines.head)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.bits.cacheData.expect(words.head)
        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)

        dut.io.request(0).ready.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
        dut.io.l2Lookup.valid.expect(true)
        dut.io.l2Lookup.bits.lineAddress.expect(lines(4))
      }
    }

  }
}
