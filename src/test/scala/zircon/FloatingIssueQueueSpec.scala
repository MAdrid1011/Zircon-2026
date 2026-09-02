package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, FloatingIssueQueue, SourceKind, UopClass}
import zircon.frontend.FloatingOperation

class FloatingIssueQueueSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: FloatingIssueQueue): Unit = {
    dut.io.enqueue.foreach(_.valid.poke(false))
    dut.io.integerReady.poke(BigInt("ffffffffffffff", 16))
    dut.io.issue.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(dut: FloatingIssueQueue, lane: Int, tag: Int,
      integerSourceReady: Boolean = true): Unit = {
    val uop = dut.io.enqueue(lane).bits
    dut.io.enqueue(lane).valid.poke(true)
    uop.robTag.poke(tag)
    uop.allowedEndpoints.poke(EndpointMask.E2)
    uop.uopClass.poke(UopClass.Floating)
    uop.operation.poke(0)
    uop.sourceKind(0).poke(SourceKind.IntegerRegister)
    uop.sourceKind(1).poke(SourceKind.FloatingRegister)
    uop.sourceKind(2).poke(SourceKind.None)
    uop.sourcePhysical(0).poke(1)
    uop.sourcePhysical(1).poke(0)
    uop.sourceReady(0).poke(integerSourceReady)
    uop.sourceReady(1).poke(true)
    uop.sourceReady(2).poke(true)
    uop.destinationPhysical.poke(0)
    uop.writesInteger.poke(false)
    uop.writesFloat.poke(true)
    uop.floatingOperation.poke(FloatingOperation.FmvWX)
    uop.floatingSource(0).poke(0)
    uop.floatingSource(1).poke(0)
    uop.floatingSource(2).poke(0)
    uop.floatingDestination.poke(4 + lane)
    uop.immediate.poke(0)
  }

  describe("FloatingIssueQueue") {
    it("issues the oldest ready RV32F uop and recycles the fired slot") {
      simulate(new FloatingIssueQueue) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, 0, 2)
        drive(dut, 1, 22)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.issue.valid.expect(true)
        dut.io.issue.bits.robTag.expect(22)
        dut.io.issue.bits.floatingOperation.expect(FloatingOperation.FmvWX)
        dut.io.issue.ready.poke(true)
        drive(dut, 0, 3)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.enqueueCapacity.expect(2)
        dut.clock.step()
        dut.io.count.expect(2)
      }
    }

    it("waits for FMV.W.X integer input readiness without blocking FPR-only work") {
      simulate(new FloatingIssueQueue) { dut =>
        clear(dut)
        drive(dut, 0, 1, integerSourceReady = false)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.integerReady.poke(0)
        dut.clock.step()
        dut.io.enqueue(0).valid.poke(false)
        dut.io.issue.valid.expect(false)
        dut.io.integerReady.poke(2)
        dut.io.issue.valid.expect(true)
      }
    }

    it("drops younger work on squash and all work on global flush") {
      simulate(new FloatingIssueQueue) { dut =>
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
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.count.expect(1)
        dut.io.issue.valid.expect(true)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.count.expect(0)
      }
    }
  }
}
