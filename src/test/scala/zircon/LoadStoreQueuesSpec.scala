package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.LoadStoreQueues

class LoadStoreQueuesSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: LoadStoreQueues): Unit = {
    dut.io.allocate.foreach { port =>
      port.valid.poke(false)
      port.bits.robTag.poke(0)
      port.bits.allocateLoad.poke(false)
      port.bits.allocateStore.poke(false)
      port.bits.accessSize.poke(2)
      port.bits.unsignedLoad.poke(false)
      port.bits.destinationPhysical.poke(0)
      port.bits.isAtomic.poke(false)
      port.bits.aq.poke(false)
      port.bits.rl.poke(false)
    }
    dut.io.storeAddress.valid.poke(false)
    dut.io.storeAddress.bits.robTag.poke(0)
    dut.io.storeAddress.bits.address.poke(0)
    dut.io.storeAddress.bits.writeMask.poke(0)
    dut.io.storeData.valid.poke(false)
    dut.io.storeData.bits.robTag.poke(0)
    dut.io.storeData.bits.writeData.poke(0)
    dut.io.loadAddress.valid.poke(false)
    dut.io.loadAddress.bits.robTag.poke(0)
    dut.io.loadAddress.bits.address.poke(0)
    dut.io.loadAddress.bits.readMask.poke(0)
    dut.io.loadComplete.valid.poke(false)
    dut.io.loadComplete.bits.robTag.poke(0)
    dut.io.loadComplete.bits.cacheData.poke(0)
    dut.io.loadResult.ready.poke(true)
    dut.io.loadContextRead.valid.poke(false)
    dut.io.loadContextRead.bits.poke(0)
    dut.io.commitAuthorize.valid.poke(false)
    dut.io.commitAuthorize.bits.poke(0)
    dut.io.storeEffect.ready.poke(false)
    dut.io.storeEffectComplete.valid.poke(false)
    dut.io.storeEffectComplete.bits.robTag.poke(0)
    dut.io.retire.foreach { port =>
      port.valid.poke(false)
      port.bits.poke(0)
    }
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def allocate(
      dut: LoadStoreQueues,
      lane: Int,
      tag: Int,
      load: Boolean,
      store: Boolean,
      atomic: Boolean = false
  ): Unit = {
    val port = dut.io.allocate(lane)
    port.valid.poke(true)
    port.bits.robTag.poke(tag)
    port.bits.allocateLoad.poke(load)
    port.bits.allocateStore.poke(store)
    port.bits.accessSize.poke(2)
    port.bits.unsignedLoad.poke(false)
    port.bits.destinationPhysical.poke(32)
    port.bits.writesInteger.poke(true)
    port.bits.isAtomic.poke(atomic)
    port.bits.aq.poke(false)
    port.bits.rl.poke(false)
  }

  private def noAllocations(dut: LoadStoreQueues): Unit =
    dut.io.allocate.foreach(_.valid.poke(false))

  private def updateStoreAddress(
      dut: LoadStoreQueues,
      tag: Int,
      address: BigInt,
      mask: Int
  ): Unit = {
    dut.io.storeAddress.valid.poke(true)
    dut.io.storeAddress.bits.robTag.poke(tag)
    dut.io.storeAddress.bits.address.poke(address)
    dut.io.storeAddress.bits.writeMask.poke(mask)
    dut.io.storeAddress.ready.expect(true)
    dut.clock.step()
    dut.io.storeAddress.valid.poke(false)
  }

  private def updateStoreData(dut: LoadStoreQueues, tag: Int, data: BigInt): Unit = {
    dut.io.storeData.valid.poke(true)
    dut.io.storeData.bits.robTag.poke(tag)
    dut.io.storeData.bits.writeData.poke(data)
    dut.io.storeData.ready.expect(true)
    dut.clock.step()
    dut.io.storeData.valid.poke(false)
  }

  private def queryLoad(
      dut: LoadStoreQueues,
      tag: Int,
      address: BigInt,
      mask: Int
  ): Unit = {
    dut.io.loadAddress.valid.poke(true)
    dut.io.loadAddress.bits.robTag.poke(tag)
    dut.io.loadAddress.bits.address.poke(address)
    dut.io.loadAddress.bits.readMask.poke(mask)
  }

  describe("LoadStoreQueues") {
    it("blocks a load behind an older store until address and data are both known") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, tag = 1, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        allocate(dut, 0, tag = 2, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)

        queryLoad(dut, 2, BigInt("80001000", 16), 15)
        dut.io.loadAddress.ready.expect(false)

        dut.io.storeAddress.valid.poke(true)
        dut.io.storeAddress.bits.robTag.poke(1)
        dut.io.storeAddress.bits.address.poke(BigInt("80001000", 16))
        dut.io.storeAddress.bits.writeMask.poke(15)
        dut.io.storeAddress.ready.expect(true)
        dut.io.loadAddress.ready.expect(false)
        dut.clock.step()
        dut.io.storeAddress.valid.poke(false)

        updateStoreData(dut, 1, BigInt("deadbeef", 16))
        dut.io.loadAddress.ready.expect(true)
        dut.io.loadForward.valid.expect(true)
      }
    }

    it("does not stall a load for a known non-overlapping older store byte") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, tag = 1, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        allocate(dut, 0, tag = 2, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 1, BigInt("80001000", 16), 1)

        queryLoad(dut, 2, BigInt("80001000", 16), 8)
        dut.io.loadAddress.ready.expect(true)
        dut.io.loadForward.valid.expect(true)
        dut.io.loadForward.bits.forwardMask.expect(0)
        dut.io.loadForward.bits.requiresCache.expect(true)
      }
    }

    it("fully forwards each byte from an older store without a cache request") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 1, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        allocate(dut, 0, 2, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 1, BigInt("80001000", 16), 15)
        updateStoreData(dut, 1, BigInt("deadbeef", 16))

        dut.io.loadContextRead.valid.poke(true)
        dut.io.loadContextRead.bits.poke(2)
        dut.io.loadContext.valid.expect(true)
        dut.io.loadContext.bits.robTag.expect(2)
        dut.io.loadContext.bits.accessSize.expect(2)
        dut.io.loadContext.bits.destinationPhysical.expect(32)
        dut.io.loadContextRead.valid.poke(false)

        queryLoad(dut, 2, BigInt("80001000", 16), 15)
        dut.io.loadAddress.ready.expect(true)
        dut.io.loadForward.valid.expect(true)
        dut.io.loadForward.bits.forwardMask.expect(15)
        dut.io.loadForward.bits.forwardData.expect(BigInt("deadbeef", 16))
        dut.io.loadForward.bits.requiresCache.expect(false)
      }
    }

    it("merges partial forwarding with cache data and preserves retirement metadata") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 1, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        allocate(dut, 0, 2, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 1, BigInt("80001000", 16), 2)
        updateStoreData(dut, 1, BigInt("0000bb00", 16))

        queryLoad(dut, 2, BigInt("80001000", 16), 15)
        dut.io.loadAddress.ready.expect(true)
        dut.io.loadForward.bits.forwardMask.expect(2)
        dut.io.loadForward.bits.forwardData.expect(BigInt("0000bb00", 16))
        dut.io.loadForward.bits.requiresCache.expect(true)
        dut.clock.step()
        dut.io.loadAddress.valid.poke(false)

        dut.io.loadComplete.valid.poke(true)
        dut.io.loadComplete.bits.robTag.poke(2)
        dut.io.loadComplete.bits.cacheData.poke(BigInt("11223344", 16))
        dut.io.loadComplete.ready.expect(true)
        dut.io.loadResult.valid.expect(true)
        dut.io.loadResult.bits.destinationPhysical.expect(32)
        dut.io.loadResult.bits.data.expect(BigInt("1122bb44", 16))
        dut.clock.step()
        dut.io.loadComplete.valid.poke(false)

        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(2)
        dut.io.retireMetadata(0).valid.expect(true)
        dut.io.retireMetadata(0).bits.address.expect(BigInt("80001000", 16))
        dut.io.retireMetadata(0).bits.readMask.expect(15)
        dut.io.retireMetadata(0).bits.readData.expect(BigInt("1122bb44", 16))
        dut.clock.step()
        dut.io.retire(0).valid.poke(false)
        dut.io.loadCount.expect(0)
      }
    }

    it("selects the youngest matching older store for every forwarded byte") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 1, load = false, store = true)
        allocate(dut, 1, 2, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        allocate(dut, 0, 3, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 1, BigInt("80001000", 16), 15)
        updateStoreData(dut, 1, BigInt("11111111", 16))
        updateStoreAddress(dut, 2, BigInt("80001000", 16), 15)
        updateStoreData(dut, 2, BigInt("22222222", 16))

        queryLoad(dut, 3, BigInt("80001000", 16), 15)
        dut.io.loadAddress.ready.expect(true)
        dut.io.loadForward.bits.forwardMask.expect(15)
        dut.io.loadForward.bits.forwardData.expect(BigInt("22222222", 16))
      }
    }

    it("uses all eight LQ and SQ entries and backpressures a ninth allocation") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        for (pair <- 0 until 4) {
          allocate(dut, 0, pair * 2, load = true, store = true, atomic = true)
          allocate(dut, 1, pair * 2 + 1, load = true, store = true, atomic = true)
          dut.io.allocate.foreach(_.ready.expect(true))
          dut.clock.step()
        }
        noAllocations(dut)
        dut.io.loadCount.expect(8)
        dut.io.storeCount.expect(8)
        dut.io.loadCapacity.expect(0)
        dut.io.storeCapacity.expect(0)

        allocate(dut, 0, 8, load = true, store = true, atomic = true)
        dut.io.allocate(0).ready.expect(false)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        noAllocations(dut)
        dut.io.loadCount.expect(0)
        dut.io.storeCount.expect(0)
      }
    }

    it("emits a store effect only after commit authorization and keeps its metadata to retire") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 5, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 5, BigInt("80002000", 16), 15)
        updateStoreData(dut, 5, BigInt("cafebabe", 16))
        dut.io.storeEffect.valid.expect(false)

        dut.io.commitAuthorize.valid.poke(true)
        dut.io.commitAuthorize.bits.poke(5)
        dut.io.commitAuthorize.ready.expect(true)
        dut.clock.step()
        dut.io.commitAuthorize.valid.poke(false)
        dut.io.storeEffect.valid.expect(true)
        dut.io.storeEffect.bits.robTag.expect(5)
        dut.io.storeEffect.bits.address.expect(BigInt("80002000", 16))
        dut.io.storeEffect.bits.accessSize.expect(2)
        dut.io.storeEffect.bits.writeData.expect(BigInt("cafebabe", 16))

        dut.io.storeEffect.ready.poke(true)
        dut.clock.step()
        dut.io.storeEffect.ready.poke(false)
        dut.io.storeEffectComplete.valid.poke(true)
        dut.io.storeEffectComplete.bits.robTag.poke(5)
        dut.clock.step()
        dut.io.storeEffectComplete.valid.poke(false)

        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(5)
        dut.io.retireMetadata(0).valid.expect(true)
        dut.io.retireMetadata(0).bits.writeMask.expect(15)
        dut.io.retireMetadata(0).bits.writeData.expect(BigInt("cafebabe", 16))
        dut.clock.step()
        dut.io.retire(0).valid.poke(false)
        dut.io.storeCount.expect(0)
      }
    }

    it("combines atomic load and committed-store metadata at retirement") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 4, load = true, store = true, atomic = true)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 4, BigInt("80003000", 16), 15)
        updateStoreData(dut, 4, BigInt("cafebabe", 16))
        queryLoad(dut, 4, BigInt("80003000", 16), 15)
        dut.io.loadAddress.ready.expect(true)
        dut.clock.step()
        dut.io.loadAddress.valid.poke(false)
        dut.io.loadComplete.valid.poke(true)
        dut.io.loadComplete.bits.robTag.poke(4)
        dut.io.loadComplete.bits.cacheData.poke(BigInt("12345678", 16))
        dut.clock.step()
        dut.io.loadComplete.valid.poke(false)

        dut.io.commitAuthorize.valid.poke(true)
        dut.io.commitAuthorize.bits.poke(4)
        dut.io.commitAuthorize.ready.expect(true)
        dut.clock.step()
        dut.io.commitAuthorize.valid.poke(false)
        dut.io.storeEffect.ready.poke(true)
        dut.io.storeEffect.valid.expect(true)
        dut.clock.step()
        dut.io.storeEffect.ready.poke(false)
        dut.io.storeEffectComplete.valid.poke(true)
        dut.io.storeEffectComplete.bits.robTag.poke(4)
        dut.clock.step()
        dut.io.storeEffectComplete.valid.poke(false)

        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(4)
        dut.io.retireMetadata(0).valid.expect(true)
        dut.io.retireMetadata(0).bits.readData.expect(BigInt("12345678", 16))
        dut.io.retireMetadata(0).bits.writeData.expect(BigInt("cafebabe", 16))
        dut.clock.step()
      }
    }

    it("retains the squash boundary across ROB wraparound and flushes local work") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        allocate(dut, 0, 21, load = true, store = false)
        allocate(dut, 1, 23, load = true, store = false)
        dut.clock.step()
        allocate(dut, 0, 0, load = true, store = false)
        allocate(dut, 1, 2, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)
        dut.io.loadCount.expect(4)

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.loadAddress.valid.poke(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.loadCount.expect(2)

        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.loadCount.expect(0)
      }
    }
  }
}
