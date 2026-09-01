package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, MemIssueQueue, SourceKind, UopClass}

class MemIssueQueueSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: MemIssueQueue): Unit = {
    dut.io.enqueue.foreach(_.valid.poke(false))
    dut.io.integerReady.poke((BigInt(1) << 56) - 1)
    dut.io.m0Issue.ready.poke(false)
    dut.io.m1Issue.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(
      dut: MemIssueQueue,
      lane: Int,
      tag: Int,
      endpoints: Int,
      uopClass: UopClass.Type,
      sourceReady: Boolean = true,
      sourcePhysical: Int = 1
  ): Unit = {
    val enqueue = dut.io.enqueue(lane)
    val uop = enqueue.bits
    enqueue.valid.poke(true)
    uop.robTag.poke(tag)
    uop.allowedEndpoints.poke(endpoints)
    uop.uopClass.poke(uopClass)
    uop.operation.poke(0)
    uop.sourceKind(0).poke(SourceKind.IntegerRegister)
    uop.sourceKind(1).poke(SourceKind.None)
    uop.sourceKind(2).poke(SourceKind.None)
    uop.sourcePhysical(0).poke(sourcePhysical)
    uop.sourcePhysical(1).poke(0)
    uop.sourceReady(0).poke(sourceReady)
    uop.sourceReady(1).poke(true)
    uop.sourceReady(2).poke(true)
    uop.destinationPhysical.poke(32 + lane)
    uop.writesInteger.poke(true)
    uop.writesFloat.poke(false)
    uop.immediate.poke(0)
  }

  describe("MemIssueQueue") {
    it("does not let a cacheable M1 load pass a live M0 atomic") {
      simulate(new MemIssueQueue) { dut =>
        clear(dut)
        drive(dut, 0, tag = 1, endpoints = EndpointMask.M0, UopClass.Atomic)
        drive(dut, 1, tag = 2, endpoints = EndpointMask.CacheableLoadCandidate,
          UopClass.Load)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.m0Issue.valid.expect(true)
        dut.io.m0Issue.bits.robTag.expect(1)
        dut.io.m0Issue.bits.uopClass.expect(UopClass.Atomic)
        dut.io.m1Issue.valid.expect(false)
        dut.io.m0Issue.ready.poke(true)
        dut.clock.step()
        dut.io.m0Issue.ready.poke(false)
        dut.io.m1Issue.valid.expect(true)
        dut.io.m1Issue.bits.robTag.expect(2)
        dut.io.m1Issue.bits.uopClass.expect(UopClass.Load)
      }
    }

    it("does not let younger M0 work pass an unready atomic") {
      simulate(new MemIssueQueue) { dut =>
        clear(dut)
        // The atomic cannot start until p32 wakes; the younger M0 store is
        // otherwise ready and must remain behind the pre-LSQ ordering barrier.
        dut.io.integerReady.poke(BigInt(1) << 1)
        drive(dut, 0, tag = 1, endpoints = EndpointMask.M0, UopClass.Atomic,
          sourceReady = false, sourcePhysical = 32)
        drive(dut, 1, tag = 2, endpoints = EndpointMask.M0, UopClass.Store,
          sourcePhysical = 1)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.m0Issue.valid.expect(false)
        dut.io.m1Issue.valid.expect(false)
        dut.io.integerReady.poke((BigInt(1) << 1) | (BigInt(1) << 32))
        dut.io.m0Issue.valid.expect(true)
        dut.io.m0Issue.bits.robTag.expect(1)
        dut.io.m0Issue.ready.poke(true)
        dut.clock.step()
        dut.io.m0Issue.ready.poke(false)
        dut.io.m0Issue.valid.expect(true)
        dut.io.m0Issue.bits.robTag.expect(2)
      }
    }

    it("uses M1 for the oldest load and M0 for the next independent load") {
      simulate(new MemIssueQueue) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, 0, tag = 2, endpoints = EndpointMask.CacheableLoadCandidate,
          UopClass.Load)
        drive(dut, 1, tag = 22, endpoints = EndpointMask.CacheableLoadCandidate,
          UopClass.Load)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.m1Issue.valid.expect(true)
        dut.io.m1Issue.bits.robTag.expect(22)
        dut.io.m0Issue.valid.expect(true)
        dut.io.m0Issue.bits.robTag.expect(2)
      }
    }

    it("holds a dependent memory uop until the shared integer ready table wakes it") {
      simulate(new MemIssueQueue) { dut =>
        clear(dut)
        drive(dut, 0, tag = 1, endpoints = EndpointMask.CacheableLoadCandidate,
          UopClass.Load, sourceReady = false, sourcePhysical = 32)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.integerReady.poke(0)
        dut.clock.step()
        dut.io.enqueue(0).valid.poke(false)
        dut.io.m0Issue.valid.expect(false)
        dut.io.m1Issue.valid.expect(false)

        dut.io.integerReady.poke(BigInt(1) << 32)
        dut.io.m1Issue.valid.expect(true)
        dut.io.m1Issue.bits.sourceReady(0).expect(true)
      }
    }

    it("recycles two issued slots while full and blocks all transfer on recovery") {
      simulate(new MemIssueQueue) { dut =>
        clear(dut)
        for (pair <- 0 until 4) {
          drive(dut, 0, tag = pair * 2, endpoints = EndpointMask.M0, UopClass.Store)
          drive(dut, 1, tag = pair * 2 + 1,
            endpoints = EndpointMask.CacheableLoadCandidate, UopClass.Load)
          dut.clock.step()
        }
        dut.io.count.expect(8)
        dut.io.enqueueCapacity.expect(0)

        dut.io.m0Issue.ready.poke(true)
        dut.io.m1Issue.ready.poke(true)
        drive(dut, 0, tag = 8, endpoints = EndpointMask.M0, UopClass.Store)
        drive(dut, 1, tag = 9, endpoints = EndpointMask.CacheableLoadCandidate,
          UopClass.Load)
        dut.io.enqueueCapacity.expect(2)
        dut.io.enqueue.foreach(_.ready.expect(true))
        dut.clock.step()
        dut.io.count.expect(8)

        dut.io.flush.poke(true)
        dut.io.m0Issue.valid.expect(false)
        dut.io.m1Issue.valid.expect(false)
        dut.io.enqueueCapacity.expect(0)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.count.expect(0)
      }
    }

    it("defers issue-slot reuse in free-only top-level admission mode") {
      simulate(new MemIssueQueue(allowIssueRecycle = false)) { dut =>
        clear(dut)
        for (pair <- 0 until 4) {
          drive(dut, 0, tag = pair * 2, endpoints = EndpointMask.M0, UopClass.Store)
          drive(dut, 1, tag = pair * 2 + 1,
            endpoints = EndpointMask.CacheableLoadCandidate, UopClass.Load)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.count.expect(8)
        dut.io.m0Issue.ready.poke(true)
        dut.io.m1Issue.ready.poke(true)
        drive(dut, 0, tag = 8, endpoints = EndpointMask.M0, UopClass.Store)
        drive(dut, 1, tag = 9, endpoints = EndpointMask.CacheableLoadCandidate,
          UopClass.Load)
        dut.io.enqueueCapacity.expect(0)
        dut.io.enqueue.foreach(_.ready.expect(false))
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.count.expect(6)
        dut.io.enqueueCapacity.expect(2)
      }
    }

    it("removes younger work on selective squash while retaining the boundary") {
      simulate(new MemIssueQueue) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, 0, tag = 21, endpoints = EndpointMask.M0, UopClass.Atomic)
        drive(dut, 1, tag = 23, endpoints = EndpointMask.M0, UopClass.Atomic)
        dut.clock.step()
        drive(dut, 0, tag = 0, endpoints = EndpointMask.M0, UopClass.Atomic)
        drive(dut, 1, tag = 2, endpoints = EndpointMask.M0, UopClass.Atomic)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.m0Issue.ready.poke(true)
        dut.io.m0Issue.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.count.expect(2)
        dut.io.m0Issue.valid.expect(true)
        dut.io.m0Issue.bits.robTag.expect(21)
      }
    }
  }
}
