package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.IntOperation
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
      port.bits.writesInteger.poke(false)
      port.bits.m1Owner.poke(false)
      port.bits.isAtomic.poke(false)
      port.bits.atomicOperation.poke(0)
      port.bits.pmaKind.poke(PMARegionKind.Memory.code)
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
    dut.io.loadAddress.foreach { port =>
      port.valid.poke(false)
      port.bits.robTag.poke(0)
      port.bits.address.poke(0)
      port.bits.readMask.poke(0)
    }
    dut.io.loadForward.foreach(_.ready.poke(true))
    dut.io.loadComplete.valid.poke(false)
    dut.io.loadComplete.bits.robTag.poke(0)
    dut.io.loadComplete.bits.cacheData.poke(0)
    dut.io.loadComplete.bits.accessFault.poke(false)
    dut.io.loadComplete.bits.faultAddress.poke(0)
    dut.io.loadResult.ready.poke(true)
    dut.io.loadFault.ready.poke(true)
    dut.io.loadContextRead.valid.poke(false)
    dut.io.loadContextRead.bits.poke(0)
    dut.io.commitAuthorize.valid.poke(false)
    dut.io.commitAuthorize.bits.poke(0)
    dut.io.storeEffect.ready.poke(false)
    dut.io.atomicEffect.ready.poke(false)
    dut.io.atomicComplete.valid.poke(false)
    dut.io.atomicComplete.bits.robTag.poke(0)
    dut.io.atomicComplete.bits.operation.poke(0)
    dut.io.atomicComplete.bits.destinationPhysical.poke(0)
    dut.io.atomicComplete.bits.writesInteger.poke(false)
    dut.io.atomicComplete.bits.data.poke(0)
    dut.io.atomicComplete.bits.accessFault.poke(false)
    dut.io.atomicComplete.bits.faultAddress.poke(0)
    dut.io.atomicComplete.bits.readData.poke(0)
    dut.io.atomicComplete.bits.readMask.poke(0)
    dut.io.atomicComplete.bits.writeData.poke(0)
    dut.io.atomicComplete.bits.writeMask.poke(0)
    dut.io.atomicComplete.bits.storePerformed.poke(false)
    dut.io.atomicResult.ready.poke(true)
    dut.io.deviceLoadEffect.ready.poke(false)
    dut.io.burstableDeviceGroup.ready.poke(false)
    dut.io.burstableDeviceGroupAccepted.valid.poke(false)
    dut.io.burstableDeviceGroupAccepted.bits.count.poke(1)
    dut.io.burstableDeviceGroupAccepted.bits.requests.foreach { request =>
      request.order.poke(0)
      request.robTag.poke(0)
      request.address.poke(0)
      request.write.poke(false)
      request.size.poke(2)
      request.writeData.poke(0)
      request.writeMask.poke(0)
      request.burstable.poke(true)
      request.regionTag.poke(PMARegionKind.DeviceBurstable.code)
    }
    dut.io.orderingBarrier.valid.poke(false)
    dut.io.orderingBarrier.bits.poke(0)
    dut.io.storeEffectComplete.valid.poke(false)
    dut.io.storeEffectComplete.bits.robTag.poke(0)
    dut.io.storeEffectComplete.bits.accessFault.poke(false)
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
      atomic: Boolean = false,
      atomicOperation: IntOperation.Type = IntOperation.LrW,
      m1Owner: Boolean = false,
      pmaKind: PMARegionKind = PMARegionKind.Memory
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
    port.bits.m1Owner.poke(m1Owner)
    port.bits.isAtomic.poke(atomic)
    port.bits.atomicOperation.poke(atomicOperation.asUInt.litValue)
    port.bits.pmaKind.poke(pmaKind.code)
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
    dut.io.loadAddress(0).valid.poke(true)
    dut.io.loadAddress(0).bits.robTag.poke(tag)
    dut.io.loadAddress(0).bits.address.poke(address)
    dut.io.loadAddress(0).bits.readMask.poke(mask)
  }

  describe("LoadStoreQueues") {
    it("drains only owners older than an age-tagged FENCE barrier") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(23)
        // tag 23 precedes the wrapped tag 32 (generation one, index zero).
        allocate(dut, 0, tag = 23, load = true, store = false)
        dut.clock.step()
        noAllocations(dut)
        dut.io.orderingBarrier.valid.poke(true)
        dut.io.orderingBarrier.bits.poke(32)
        dut.io.orderingReady.expect(false)

        // The live LQ owner is younger than this barrier and must not block it.
        dut.io.orderingBarrier.bits.poke(23)
        dut.io.orderingReady.expect(true)

        // Stores participate in the same age comparison.
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        allocate(dut, 0, tag = 23, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        dut.io.orderingBarrier.bits.poke(32)
        dut.io.orderingReady.expect(false)
        dut.io.orderingBarrier.bits.poke(23)
        dut.io.orderingReady.expect(true)
      }
    }

    it("issues an exact-head device load once and waits for its real completion") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        val address = BigInt("a00003f8", 16)
        allocate(dut, 0, tag = 6, load = true, store = false,
          pmaKind = PMARegionKind.DeviceStrong)
        dut.clock.step()
        noAllocations(dut)
        queryLoad(dut, 6, address, 15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.clock.step()
        dut.io.loadAddress(0).valid.poke(false)

        dut.io.robHeadTag.poke(5)
        dut.io.deviceLoadEffect.valid.expect(false)
        dut.io.robHeadTag.poke(6)
        dut.io.deviceLoadEffect.valid.expect(true)
        dut.io.deviceLoadEffect.bits.robTag.expect(6)
        dut.io.deviceLoadEffect.bits.address.expect(address)
        dut.io.deviceLoadEffect.bits.accessSize.expect(2)
        dut.io.deviceLoadEffect.bits.pmaKind.expect(PMARegionKind.DeviceStrong.code)
        dut.io.deviceLoadEffect.ready.poke(true)
        dut.clock.step()
        dut.io.deviceLoadEffect.ready.poke(false)
        dut.io.deviceLoadEffect.valid.expect(false)
        dut.io.deviceLoadInFlight.expect(true)

        dut.io.loadComplete.valid.poke(true)
        dut.io.loadComplete.bits.robTag.poke(6)
        dut.io.loadComplete.bits.cacheData.poke(BigInt("11223344", 16))
        dut.io.loadComplete.bits.accessFault.poke(false)
        dut.io.loadComplete.bits.faultAddress.poke(address)
        dut.io.loadComplete.ready.expect(true)
        dut.clock.step()
        dut.io.loadComplete.valid.poke(false)
        dut.io.deviceLoadInFlight.expect(true)
        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(6)
        dut.clock.step()
        dut.io.retire(0).valid.poke(false)
        dut.io.deviceLoadInFlight.expect(false)
      }
    }

    it("previews and accepts four contiguous DeviceBurstable loads across ROB wrap") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        val tags = Seq(22, 23, 32, 33)
        val addresses = Seq(BigInt("b0000ff0", 16), BigInt("b0000ff4", 16),
          BigInt("b0000ff8", 16), BigInt("b0000ffc", 16))
        for ((tag, address) <- tags.zip(addresses)) {
          allocate(dut, 0, tag = tag, load = true, store = false,
            pmaKind = PMARegionKind.DeviceBurstable)
          dut.clock.step()
          noAllocations(dut)
          queryLoad(dut, tag, address, 15)
          dut.io.loadAddress(0).ready.expect(true)
          dut.clock.step()
          dut.io.loadAddress(0).valid.poke(false)
        }

        dut.io.robHeadTag.poke(tags.head)
        dut.io.deviceLoadEffect.valid.expect(false)
        // The collector waits six full cycles so M0 can populate later LQ/SQ members.
        dut.clock.step(7)
        dut.io.burstableDeviceGroup.valid.expect(true)
        dut.io.burstableDeviceGroup.bits.count.expect(4)
        for ((tag, member) <- tags.zipWithIndex) {
          dut.io.burstableDeviceGroup.bits.requests(member).robTag.expect(tag)
          dut.io.burstableDeviceGroup.bits.requests(member).order.expect(member)
          dut.io.burstableDeviceGroup.bits.requests(member).address.expect(addresses(member))
          dut.io.burstableDeviceGroup.bits.requests(member).write.expect(false)
          dut.io.burstableDeviceGroup.bits.requests(member).burstable.expect(true)
        }

        dut.io.burstableDeviceGroupAccepted.valid.poke(true)
        dut.io.burstableDeviceGroupAccepted.bits.count.poke(4)
        for ((tag, member) <- tags.zipWithIndex) {
          val request = dut.io.burstableDeviceGroupAccepted.bits.requests(member)
          request.order.poke(member)
          request.robTag.poke(tag)
          request.address.poke(addresses(member))
          request.write.poke(false)
          request.size.poke(2)
          request.writeData.poke(0)
          request.writeMask.poke(0)
          request.burstable.poke(true)
          request.regionTag.poke(PMARegionKind.DeviceBurstable.code)
        }
        dut.clock.step()
        dut.io.burstableDeviceGroupAccepted.valid.poke(false)
        dut.io.burstableDeviceGroup.valid.expect(false)
        dut.io.deviceLoadInFlight.expect(true)
      }
    }

    it("forwards normal Memory loads from either LSU owner to the cache") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, tag = 1, load = true, store = false, m1Owner = true)
        dut.clock.step()
        noAllocations(dut)
        queryLoad(dut, 1, BigInt("80001000", 16), 15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
        dut.io.loadForward(0).bits.cacheable.expect(true)
        dut.clock.step()
        dut.io.loadAddress(0).valid.poke(false)

        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        allocate(dut, 0, tag = 2, load = true, store = false, m1Owner = false)
        dut.clock.step()
        noAllocations(dut)
        queryLoad(dut, 2, BigInt("80002000", 16), 15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
        dut.io.loadForward(0).bits.cacheable.expect(true)

        dut.clock.step()
        dut.io.loadAddress(0).valid.poke(false)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        allocate(dut, 0, tag = 3, load = true, store = false,
          pmaKind = PMARegionKind.DeviceStrong)
        dut.clock.step()
        noAllocations(dut)
        queryLoad(dut, 3, BigInt("a0000000", 16), 15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
        dut.io.loadForward(0).bits.cacheable.expect(false)
      }
    }

    it("accepts two independent load queries in one cycle") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, tag = 5, load = true, store = false)
        allocate(dut, 1, tag = 6, load = true, store = false, m1Owner = true)
        dut.clock.step()
        noAllocations(dut)

        queryLoad(dut, 5, BigInt("80001000", 16), 15)
        dut.io.loadAddress(1).valid.poke(true)
        dut.io.loadAddress(1).bits.robTag.poke(6)
        dut.io.loadAddress(1).bits.address.poke(BigInt("80002000", 16))
        dut.io.loadAddress(1).bits.readMask.poke(15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadAddress(1).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
        dut.io.loadForward(0).bits.robTag.expect(5)
        dut.io.loadForward(0).bits.address.expect(BigInt("80001000", 16))
        dut.io.loadForward(1).valid.expect(true)
        dut.io.loadForward(1).bits.robTag.expect(6)
        dut.io.loadForward(1).bits.address.expect(BigInt("80002000", 16))
        dut.clock.step()
        dut.io.loadAddress.foreach(_.valid.poke(false))
      }
    }

    it("retains atomic LQ/SQ state through one exact response and emits paired retire metadata") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        val address = BigInt("80003000", 16)
        val oldValue = BigInt("11223344", 16)
        val newValue = BigInt("11223349", 16)
        allocate(dut, 0, tag = 0, load = true, store = true, atomic = true,
          atomicOperation = IntOperation.AmoAddW)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 0, address, 15)
        updateStoreData(dut, 0, 5)
        queryLoad(dut, 0, address, 15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.clock.step()
        dut.io.loadAddress(0).valid.poke(false)

        dut.io.atomicEffect.valid.expect(true)
        dut.io.atomicEffect.bits.robTag.expect(0)
        dut.io.atomicEffect.bits.operation.expect(IntOperation.AmoAddW.asUInt.litValue)
        dut.io.atomicEffect.bits.address.expect(address)
        dut.io.atomicEffect.bits.writeData.expect(5)
        dut.io.atomicEffect.bits.destinationPhysical.expect(32)
        dut.io.atomicEffect.ready.poke(true)
        dut.clock.step()
        dut.io.atomicEffect.ready.poke(false)
        dut.io.atomicInFlight.expect(true)

        dut.io.atomicComplete.valid.poke(true)
        dut.io.atomicComplete.bits.robTag.poke(0)
        dut.io.atomicComplete.bits.operation.poke(IntOperation.AmoAddW.asUInt.litValue)
        dut.io.atomicComplete.bits.destinationPhysical.poke(32)
        dut.io.atomicComplete.bits.writesInteger.poke(true)
        dut.io.atomicComplete.bits.data.poke(oldValue)
        dut.io.atomicComplete.bits.accessFault.poke(false)
        dut.io.atomicComplete.bits.faultAddress.poke(address)
        dut.io.atomicComplete.bits.readData.poke(oldValue)
        dut.io.atomicComplete.bits.readMask.poke(15)
        dut.io.atomicComplete.bits.writeData.poke(newValue)
        dut.io.atomicComplete.bits.writeMask.poke(15)
        dut.io.atomicComplete.bits.storePerformed.poke(true)
        dut.io.atomicComplete.ready.expect(true)
        dut.clock.step()
        dut.io.atomicComplete.valid.poke(false)
        dut.io.atomicResult.valid.expect(true)
        dut.io.atomicResult.bits.robTag.expect(0)
        dut.io.atomicResult.bits.data.expect(oldValue)
        dut.clock.step()

        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(0)
        dut.io.retireMetadata(0).valid.expect(true)
        dut.io.retireMetadata(0).bits.address.expect(address)
        dut.io.retireMetadata(0).bits.readMask.expect(15)
        dut.io.retireMetadata(0).bits.readData.expect(oldValue)
        dut.io.retireMetadata(0).bits.writeMask.expect(15)
        dut.io.retireMetadata(0).bits.writeData.expect(newValue)
        dut.clock.step()
        dut.io.retire(0).valid.poke(false)
        dut.io.atomicInFlight.expect(false)
      }
    }

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
        dut.io.loadAddress(0).ready.expect(false)

        dut.io.storeAddress.valid.poke(true)
        dut.io.storeAddress.bits.robTag.poke(1)
        dut.io.storeAddress.bits.address.poke(BigInt("80001000", 16))
        dut.io.storeAddress.bits.writeMask.poke(15)
        dut.io.storeAddress.ready.expect(true)
        dut.io.loadAddress(0).ready.expect(false)
        dut.clock.step()
        dut.io.storeAddress.valid.poke(false)

        updateStoreData(dut, 1, BigInt("deadbeef", 16))
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
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
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
        dut.io.loadForward(0).bits.forwardMask.expect(0)
        dut.io.loadForward(0).bits.requiresCache.expect(true)
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
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).valid.expect(true)
        dut.io.loadForward(0).bits.forwardMask.expect(15)
        dut.io.loadForward(0).bits.forwardData.expect(BigInt("deadbeef", 16))
        dut.io.loadForward(0).bits.requiresCache.expect(false)
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
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).bits.forwardMask.expect(2)
        dut.io.loadForward(0).bits.forwardData.expect(BigInt("0000bb00", 16))
        dut.io.loadForward(0).bits.requiresCache.expect(true)
        dut.clock.step()
        dut.io.loadAddress(0).valid.poke(false)

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
        dut.io.loadAddress(0).ready.expect(true)
        dut.io.loadForward(0).bits.forwardMask.expect(15)
        dut.io.loadForward(0).bits.forwardData.expect(BigInt("22222222", 16))
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

    it("permits fault cleanup after an accepted store result but never creates retire metadata") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 6, load = false, store = true)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 6, BigInt("80002000", 16), 15)
        updateStoreData(dut, 6, BigInt("abad1dea", 16))
        dut.io.commitAuthorize.valid.poke(true)
        dut.io.commitAuthorize.bits.poke(6)
        dut.io.commitAuthorize.ready.expect(true)
        dut.clock.step()
        dut.io.commitAuthorize.valid.poke(false)
        dut.io.storeEffect.ready.poke(true)
        dut.io.storeEffect.valid.expect(true)
        dut.clock.step()
        dut.io.storeEffect.ready.poke(false)
        dut.io.storeEffectComplete.valid.poke(true)
        dut.io.storeEffectComplete.bits.robTag.poke(6)
        dut.io.storeEffectComplete.bits.accessFault.poke(true)
        dut.clock.step()
        dut.io.storeEffectComplete.valid.poke(false)
        dut.io.storeEffectComplete.bits.accessFault.poke(false)
        dut.io.storeCommitInFlight.expect(false)
        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(6)
        dut.io.retireMetadata(0).valid.expect(false)
        dut.io.retire(0).valid.poke(false)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.storeCount.expect(0)
      }
    }

    it("keeps atomics outside the cacheable single-beat store slice") {
      simulate(new LoadStoreQueues) { dut =>
        clear(dut)
        allocate(dut, 0, 4, load = true, store = true, atomic = true)
        dut.clock.step()
        noAllocations(dut)
        updateStoreAddress(dut, 4, BigInt("80003000", 16), 15)
        updateStoreData(dut, 4, BigInt("cafebabe", 16))
        queryLoad(dut, 4, BigInt("80003000", 16), 15)
        dut.io.loadAddress(0).ready.expect(true)
        dut.clock.step()
        dut.io.loadAddress(0).valid.poke(false)
        dut.io.loadComplete.valid.poke(true)
        dut.io.loadComplete.bits.robTag.poke(4)
        dut.io.loadComplete.bits.cacheData.poke(BigInt("12345678", 16))
        dut.clock.step()
        dut.io.loadComplete.valid.poke(false)

        dut.io.commitAuthorize.valid.poke(true)
        dut.io.commitAuthorize.bits.poke(4)
        dut.io.commitAuthorize.ready.expect(false)
        dut.io.storeEffect.valid.expect(false)
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
        dut.io.loadAddress(0).valid.poke(false)
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
