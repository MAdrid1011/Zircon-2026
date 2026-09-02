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

  private def startAxiRefill(dut: L1DLoadCache, lineAddress: BigInt): Unit = {
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
    dut.io.dataRequest.bits.clientMshr.expect(0)
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

    it("does not accept a targeted flush for a clean resident line") {
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
        dut.io.flushLine.ready.expect(false)
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

    it("replays a younger victim miss behind an older different-set hit") {
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

        // Lane 0 is younger and requires a resident victim transfer; lane 1
        // is an immediate hit in another set. The dual fast path may not
        // fabricate a second L2 transfer, so only the older hit fires.
        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(false)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        // With the hit accepted, the retained younger request can now claim
        // the single victim-transfer path and issue its exact MSHR owner.
        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.clock.step()
        dut.io.request(0).valid.poke(false)

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

    it("replays a younger dirty-victim miss behind an older different-set hit") {
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

        // Both replacement candidates are dirty. The younger miss must wait
        // while the older independent hit uses its retained result slot.
        submitStore(dut, tag = 4, address = residentA + 4,
          mask = 15, data = BigInt("dead0001", 16))
        consumeStoreResult(dut, tag = 4, address = residentA + 4)
        submitStore(dut, tag = 5, address = residentB + 4,
          mask = 15, data = BigInt("dead0002", 16))
        consumeStoreResult(dut, tag = 5, address = residentB + 4)

        presentLoad(dut, lane = 0, tag = 7, address = missLine + 4)
        presentLoad(dut, lane = 1, tag = 3, address = hitLine + 8)
        dut.io.request(0).ready.expect(false)
        dut.io.request(1).ready.expect(true)
        dut.io.l2Insert.valid.expect(false)
        dut.clock.step()
        dut.io.request(1).valid.poke(false)

        dut.io.request(0).ready.expect(true)
        dut.io.l2Insert.valid.expect(true)
        dut.io.l2Insert.bits.lineAddress.expect(residentA)
        dut.io.l2Insert.bits.dirty.expect(true)
        dut.io.l2Insert.bits.lineData(1).expect(BigInt("dead0001", 16))
        dut.clock.step()
        dut.io.request(0).valid.poke(false)
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
  }
}
