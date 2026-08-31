package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, SourceKind, UopClass}
import zircon.memory.MemoryQueueIngress

class MemoryQueueIngressSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: MemoryQueueIngress): Unit = {
    for (input <- dut.io.input) {
      input.valid.poke(false)
      input.bits.request.uop.robTag.poke(0)
      input.bits.request.uop.allowedEndpoints.poke(EndpointMask.M0)
      input.bits.request.uop.uopClass.poke(UopClass.Load)
      input.bits.request.uop.operation.poke(0)
      input.bits.request.uop.sourceKind.foreach(_.poke(SourceKind.None))
      input.bits.request.uop.sourcePhysical.foreach(_.poke(0))
      input.bits.request.uop.sourceReady.foreach(_.poke(true))
      input.bits.request.uop.destinationPhysical.poke(32)
      input.bits.request.uop.writesInteger.poke(true)
      input.bits.request.uop.writesFloat.poke(false)
      input.bits.request.uop.immediate.poke(0)
      input.bits.request.base.poke(0)
      input.bits.request.storeData.poke(0)
      input.bits.request.atomicAq.poke(false)
      input.bits.request.atomicRl.poke(false)
      input.bits.address.robTag.poke(0)
      input.bits.address.legalMemoryOperation.poke(true)
      input.bits.address.isLoad.poke(false)
      input.bits.address.isStore.poke(false)
      input.bits.address.isAtomic.poke(false)
      input.bits.address.unsignedLoad.poke(false)
      input.bits.address.accessSize.poke(2)
      input.bits.address.address.poke(0)
      input.bits.address.readMask.poke(0)
      input.bits.address.writeMask.poke(0)
      input.bits.address.writeData.poke(0)
      input.bits.address.pmaKind.poke(PMARegionKind.Memory.code)
      input.bits.address.naturallyAligned.poke(true)
      input.bits.address.m1Eligible.poke(false)
      input.bits.address.faultValid.poke(false)
      input.bits.address.faultCause.poke(0)
      input.bits.address.faultTval.poke(0)
      input.bits.address.aq.poke(false)
      input.bits.address.rl.poke(false)
      input.bits.m1Owner.poke(false)
    }
    dut.io.loadComplete.valid.poke(false)
    dut.io.loadComplete.bits.robTag.poke(0)
    dut.io.loadComplete.bits.cacheData.poke(0)
    dut.io.loadComplete.bits.accessFault.poke(false)
    dut.io.loadComplete.bits.faultAddress.poke(0)
    dut.io.faultReady.foreach(_.poke(true))
    dut.io.loadForwardReady.poke(true)
    dut.io.loadResult.ready.poke(true)
    dut.io.loadFault.ready.poke(true)
    dut.io.loadContextRead.valid.poke(false)
    dut.io.loadContextRead.bits.poke(0)
    dut.io.commitAuthorize.valid.poke(false)
    dut.io.commitAuthorize.bits.poke(0)
    dut.io.storeEffect.ready.poke(false)
    dut.io.storeEffectComplete.valid.poke(false)
    dut.io.storeEffectComplete.bits.robTag.poke(0)
    dut.io.storeEffectComplete.bits.accessFault.poke(false)
    dut.io.retire.foreach { lane =>
      lane.valid.poke(false)
      lane.bits.poke(0)
    }
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def request(
      dut: MemoryQueueIngress,
      lane: Int,
      tag: Int,
      load: Boolean,
      store: Boolean,
      address: BigInt,
      readMask: Int = 15,
      writeMask: Int = 15,
      writeData: BigInt = 0,
      atomic: Boolean = false,
      fault: Option[(Int, BigInt)] = None
  ): Unit = {
    val input = dut.io.input(lane)
    input.valid.poke(true)
    input.bits.request.uop.robTag.poke(tag)
    input.bits.request.uop.uopClass.poke(
      if (atomic) UopClass.Atomic else if (store) UopClass.Store else UopClass.Load)
    input.bits.request.uop.destinationPhysical.poke(32 + lane)
    input.bits.address.robTag.poke(tag)
    input.bits.address.isLoad.poke(load)
    input.bits.address.isStore.poke(store)
    input.bits.address.isAtomic.poke(atomic)
    input.bits.address.address.poke(address)
    input.bits.address.readMask.poke(readMask)
    input.bits.address.writeMask.poke(writeMask)
    input.bits.address.writeData.poke(writeData)
    input.bits.address.faultValid.poke(fault.nonEmpty)
    input.bits.address.faultCause.poke(fault.map(_._1).getOrElse(0))
    input.bits.address.faultTval.poke(fault.map(_._2).getOrElse(BigInt(0)))
    input.bits.m1Owner.poke(lane == 1)
  }

  private def clearRequests(dut: MemoryQueueIngress): Unit =
    dut.io.input.foreach(_.valid.poke(false))

  describe("MemoryQueueIngress") {
    it("allocates a load and store before publishing their separate LSQ updates") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        request(dut, 0, tag = 3, load = false, store = true,
          address = BigInt("80001000", 16), writeData = BigInt("deadbeef", 16))
        request(dut, 1, tag = 4, load = true, store = false,
          address = BigInt("80001000", 16))
        dut.io.input.foreach(_.ready.expect(true))
        dut.clock.step()
        clearRequests(dut)
        dut.io.intakeCount.expect(2)
        dut.io.loadCount.expect(0)
        dut.io.storeCount.expect(0)

        dut.clock.step()
        dut.io.intakeCount.expect(0)
        dut.io.updateCount.expect(2)
        dut.io.loadCount.expect(1)
        dut.io.storeCount.expect(1)

        dut.io.loadForward.valid.expect(false)
        dut.clock.step()
        dut.io.loadForward.valid.expect(true)
        dut.io.loadForward.bits.robTag.expect(4)
        dut.io.loadForward.bits.forwardMask.expect(15)
        dut.io.loadForward.bits.forwardData.expect(BigInt("deadbeef", 16))
        dut.io.loadForward.bits.requiresCache.expect(false)
        dut.clock.step()
        dut.io.updateCount.expect(0)
      }
    }

    it("holds a second intake request stable until the buffered batch allocates") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        request(dut, 0, tag = 1, load = true, store = false,
          address = BigInt("80002000", 16))
        dut.clock.step()
        clearRequests(dut)
        request(dut, 1, tag = 2, load = true, store = false,
          address = BigInt("80002004", 16))
        dut.io.input(1).ready.expect(false)
        dut.clock.step()
        dut.io.input(1).ready.expect(true)
        dut.clock.step()
        clearRequests(dut)
        dut.clock.step()
        dut.io.loadCount.expect(2)
        dut.io.loadForward.valid.expect(true)
      }
    }

    it("allocates both LQ and SQ ownership for an atomic without a completion") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        request(dut, 0, tag = 7, load = true, store = true,
          address = BigInt("80003000", 16), writeData = BigInt("cafebabe", 16), atomic = true)
        dut.clock.step()
        clearRequests(dut)
        dut.clock.step()
        dut.io.loadCount.expect(1)
        dut.io.storeCount.expect(1)
        dut.io.loadForward.valid.expect(true)
        dut.io.loadForward.bits.robTag.expect(7)
        dut.clock.step()
        dut.io.storeEffect.valid.expect(false)
      }
    }

    it("turns a classified access fault into an exact candidate without LSQ ownership") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        request(dut, 0, tag = 9, load = true, store = false,
          address = BigInt("90000000", 16), fault = Some(5 -> BigInt("90000000", 16)))
        dut.io.input(0).ready.expect(true)
        dut.io.fault(0).valid.expect(true)
        dut.io.fault(0).record.robTag.expect(9)
        dut.io.fault(0).record.cause.expect(5)
        dut.io.fault(0).record.trapValue.expect(BigInt("90000000", 16))
        dut.clock.step()
        clearRequests(dut)
        dut.io.loadCount.expect(0)
        dut.io.storeCount.expect(0)
        dut.io.intakeCount.expect(0)
      }
    }

    it("holds a faulting batch until its completion owner has credit") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        request(dut, 0, tag = 10, load = true, store = false,
          address = BigInt("90000000", 16), fault = Some(5 -> BigInt("90000000", 16)))
        dut.io.faultReady(0).poke(false)
        dut.io.input(0).ready.expect(false)
        dut.io.fault(0).valid.expect(true)

        dut.io.faultReady(0).poke(true)
        dut.io.input(0).ready.expect(true)
        dut.clock.step()
        clearRequests(dut)
        dut.io.loadCount.expect(0)
        dut.io.storeCount.expect(0)
      }
    }

    it("does not accept a fault until its source lane handshakes") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        request(dut, 0, tag = 1, load = true, store = false,
          address = BigInt("80001000", 16))
        dut.clock.step()
        clearRequests(dut)

        request(dut, 0, tag = 2, load = true, store = false,
          address = BigInt("90000000", 16), fault = Some(5 -> BigInt("90000000", 16)))
        request(dut, 1, tag = 3, load = true, store = false,
          address = BigInt("80001004", 16))
        dut.io.input(0).ready.expect(false)
        dut.io.fault(0).valid.expect(false)
        dut.clock.step()

        dut.io.input(0).ready.expect(true)
        dut.io.fault(0).valid.expect(true)
        dut.clock.step()
        dut.io.fault(0).valid.expect(false)
      }
    }

    it("drops only younger ingress state during recovery") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        request(dut, 0, tag = 20, load = true, store = false,
          address = BigInt("80004000", 16))
        request(dut, 1, tag = 21, load = true, store = false,
          address = BigInt("80004004", 16))
        dut.clock.step()
        clearRequests(dut)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(20)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.intakeCount.expect(1)
        dut.clock.step()
        dut.io.loadCount.expect(1)
      }
    }

    it("compacts a surviving lane-one request after selective recovery") {
      simulate(new MemoryQueueIngress) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        request(dut, 0, tag = 21, load = true, store = false,
          address = BigInt("80005000", 16))
        request(dut, 1, tag = 20, load = true, store = false,
          address = BigInt("80005004", 16))
        dut.clock.step()
        clearRequests(dut)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(20)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.intakeCount.expect(1)
        dut.clock.step()
        dut.io.loadCount.expect(1)
      }
    }
  }
}
