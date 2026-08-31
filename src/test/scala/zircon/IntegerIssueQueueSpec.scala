package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, IntegerIssueQueue, SourceKind, UopClass}

class IntegerIssueQueueSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: IntegerIssueQueue): Unit = {
    dut.io.enqueue.foreach(_.valid.poke(false))
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false)
      wakeup.physical.poke(0)
    }
    dut.io.issueE0.ready.poke(false)
    dut.io.issueE1.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.flush.poke(false)
  }

  private def driveUop(
      dut: IntegerIssueQueue,
      lane: Int,
      robTag: Int,
      endpoints: Int,
      source0Ready: Boolean = true,
      source0Physical: Int = 1
  ): Unit = {
    val enqueue = dut.io.enqueue(lane)
    val uop = enqueue.bits
    enqueue.valid.poke(true)
    uop.robTag.poke(robTag)
    uop.allowedEndpoints.poke(endpoints)
    uop.uopClass.poke(UopClass.Integer)
    uop.operation.poke(0)
    uop.sourceKind(0).poke(SourceKind.IntegerRegister)
    uop.sourceKind(1).poke(SourceKind.IntegerRegister)
    uop.sourceKind(2).poke(SourceKind.None)
    uop.sourcePhysical(0).poke(source0Physical)
    uop.sourcePhysical(1).poke(2)
    uop.sourceReady(0).poke(source0Ready)
    uop.sourceReady(1).poke(true)
    uop.sourceReady(2).poke(true)
    uop.destinationPhysical.poke(32 + lane)
    uop.writesInteger.poke(true)
    uop.writesFloat.poke(false)
    uop.immediate.poke(0)
  }

  describe("IntegerIssueQueue") {
    it("reserves E0 for exclusive work and lets E1 issue a flexible uop independently") {
      simulate(new IntegerIssueQueue(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        driveUop(dut, 0, robTag = 1, endpoints = EndpointMask.IntegerSimple)
        driveUop(dut, 1, robTag = 2, endpoints = EndpointMask.E0)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.issueE0.valid.expect(true)
        dut.io.issueE0.bits.robTag.expect(2)
        dut.io.issueE1.valid.expect(true)
        dut.io.issueE1.bits.robTag.expect(1)
        dut.io.issueE1.ready.poke(true)
        dut.clock.step()
        dut.io.count.expect(1)
        dut.io.issueE0.valid.expect(true)
        dut.io.issueE0.bits.robTag.expect(2)
        dut.io.issueE0.ready.poke(true)
        dut.clock.step()
        dut.io.count.expect(0)
      }
    }

    it("selects oldest ready uops across ROB index wrap") {
      simulate(new IntegerIssueQueue(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        dut.io.robHeadTag.poke(20)
        driveUop(dut, 0, robTag = 2, endpoints = EndpointMask.E0)
        driveUop(dut, 1, robTag = 22, endpoints = EndpointMask.E0)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.issueE0.valid.expect(true)
        dut.io.issueE0.bits.robTag.expect(22)
      }
    }

    it("captures same-cycle wakeup when a dependent uop is enqueued") {
      simulate(new IntegerIssueQueue(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        driveUop(dut, 0, robTag = 0, endpoints = EndpointMask.E1,
          source0Ready = false, source0Physical = 32)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.wakeup(0).valid.poke(true)
        dut.io.wakeup(0).physical.poke(32)
        dut.clock.step()
        dut.io.enqueue(0).valid.poke(false)
        dut.io.wakeup(0).valid.poke(false)
        dut.io.issueE1.valid.expect(true)
        dut.io.issueE1.bits.robTag.expect(0)
      }
    }

    it("recycles two issuing slots while full and flushes atomically") {
      simulate(new IntegerIssueQueue(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 6) {
          driveUop(dut, 0, robTag = pair * 2, endpoints = EndpointMask.IntegerSimple)
          driveUop(dut, 1, robTag = pair * 2 + 1, endpoints = EndpointMask.IntegerSimple)
          dut.clock.step()
        }
        dut.io.count.expect(12)

        driveUop(dut, 0, robTag = 12, endpoints = EndpointMask.IntegerSimple)
        driveUop(dut, 1, robTag = 13, endpoints = EndpointMask.IntegerSimple)
        dut.io.issueE0.ready.poke(true)
        dut.io.issueE1.ready.poke(true)
        dut.io.enqueue(0).ready.expect(true)
        dut.clock.step()
        dut.io.count.expect(12)

        dut.io.flush.poke(true)
        dut.io.enqueue(0).ready.expect(false)
        dut.io.issueE0.valid.expect(false)
        dut.io.issueE1.valid.expect(false)
        dut.clock.step()
        dut.io.count.expect(0)
      }
    }
  }
}
