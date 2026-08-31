package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, LongIssueQueue, SourceKind, UopClass}

class LongIssueQueueSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: LongIssueQueue): Unit = {
    dut.io.enqueue.foreach(_.valid.poke(false))
    dut.io.integerReady.poke(BigInt("ffffffffffffff", 16))
    dut.io.issue.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(dut: LongIssueQueue, lane: Int, tag: Int, ready: Boolean = true): Unit = {
    val uop = dut.io.enqueue(lane).bits
    dut.io.enqueue(lane).valid.poke(true)
    uop.robTag.poke(tag)
    uop.allowedEndpoints.poke(EndpointMask.E2)
    uop.uopClass.poke(UopClass.Multiply)
    uop.operation.poke(0)
    uop.sourceKind(0).poke(SourceKind.IntegerRegister)
    uop.sourceKind(1).poke(SourceKind.IntegerRegister)
    uop.sourceKind(2).poke(SourceKind.None)
    uop.sourcePhysical(0).poke(1)
    uop.sourcePhysical(1).poke(2)
    uop.sourceReady(0).poke(ready)
    uop.sourceReady(1).poke(true)
    uop.sourceReady(2).poke(true)
    uop.destinationPhysical.poke(32 + lane)
    uop.writesInteger.poke(true)
    uop.writesFloat.poke(false)
    uop.immediate.poke(0)
  }

  describe("LongIssueQueue") {
    it("issues the oldest E2-ready uop and recycles an issued slot") {
      simulate(new LongIssueQueue) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, 0, 2)
        drive(dut, 1, 22)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.issue.valid.expect(true)
        dut.io.issue.bits.robTag.expect(22)
        dut.io.issue.ready.poke(true)
        drive(dut, 0, 3)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.enqueueCapacity.expect(2)
        dut.clock.step()
        dut.io.count.expect(2)
      }
    }

    it("holds an unready source until the integer ready table marks it ready") {
      simulate(new LongIssueQueue) { dut =>
        clear(dut)
        drive(dut, 0, 1, ready = false)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.integerReady.poke(0)
        dut.clock.step()
        dut.io.enqueue(0).valid.poke(false)
        dut.io.issue.valid.expect(false)
        dut.io.integerReady.poke(2)
        dut.io.issue.valid.expect(true)
      }
    }

    it("removes younger work and blocks transfer during selective squash") {
      simulate(new LongIssueQueue) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, 0, 21)
        drive(dut, 1, 2)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.issue.ready.poke(true)
        dut.io.issue.valid.expect(false)
        dut.io.enqueueCapacity.expect(0)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.count.expect(1)
        dut.io.issue.valid.expect(true)
        dut.io.issue.bits.robTag.expect(21)
      }
    }

    it("drops every queued uop on a global flush") {
      simulate(new LongIssueQueue) { dut =>
        clear(dut)
        drive(dut, 0, 1)
        drive(dut, 1, 2)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.flush.poke(true)
        dut.io.issue.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.count.expect(0)
        dut.io.issue.valid.expect(false)
      }
    }
  }
}
