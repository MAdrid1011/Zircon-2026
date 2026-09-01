package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, SourceKind, UopClass}
import zircon.frontend.IntOperation
import zircon.memory.DualLSUIngress

class DualLSUIngressSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: DualLSUIngress): Unit = {
    for (issue <- Seq(dut.io.m0Issue, dut.io.m1Issue)) {
      issue.valid.poke(false)
      issue.bits.robTag.poke(0)
      issue.bits.allowedEndpoints.poke(0)
      issue.bits.uopClass.poke(UopClass.Load)
      issue.bits.operation.poke(0)
      issue.bits.sourceKind.foreach(_.poke(SourceKind.None))
      issue.bits.sourcePhysical.foreach(_.poke(0))
      issue.bits.sourceReady.foreach(_.poke(true))
      issue.bits.destinationPhysical.poke(32)
      issue.bits.writesInteger.poke(true)
      issue.bits.writesFloat.poke(false)
      issue.bits.immediate.poke(0)
    }
    dut.io.robContext.foreach { context =>
      context.valid.poke(false)
      context.bits.robTag.poke(0)
      context.bits.pc.poke(0)
      context.bits.instruction.poke(0)
      context.bits.privilege.poke(3)
      context.bits.csrAddress.poke(0)
      context.bits.csrImmediate.poke(0)
      context.bits.csrRead.poke(false)
      context.bits.csrWrite.poke(false)
      context.bits.atomicAq.poke(false)
      context.bits.atomicRl.poke(false)
      context.bits.hasBranchData.poke(false)
      context.bits.branchDataIndex.poke(0)
    }
    dut.io.prfReadData.foreach(_.poke(0))
    dut.io.loadComplete.valid.poke(false)
    dut.io.loadComplete.bits.robTag.poke(0)
    dut.io.loadComplete.bits.cacheData.poke(0)
    dut.io.loadComplete.bits.accessFault.poke(false)
    dut.io.loadComplete.bits.faultAddress.poke(0)
    dut.io.loadForwardReady.poke(true)
    dut.io.m0Completion.ready.poke(true)
    dut.io.m1Completion.ready.poke(true)
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
    dut.io.storeEffectComplete.valid.poke(false)
    dut.io.storeEffectComplete.bits.robTag.poke(0)
    dut.io.storeEffectComplete.bits.accessFault.poke(false)
    dut.io.storeWriteResult.valid.poke(false)
    dut.io.storeWriteResult.bits.robTag.poke(0)
    dut.io.storeWriteResult.bits.address.poke(0)
    dut.io.storeWriteResult.bits.accessFault.poke(false)
    dut.io.retire.foreach { retire =>
      retire.valid.poke(false)
      retire.bits.poke(0)
    }
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def driveIssue(
      dut: DualLSUIngress,
      m1: Boolean,
      tag: Int,
      operation: IntOperation.Type,
      uopClass: UopClass.Type,
      endpoints: Int,
      basePhysical: Int,
      storePhysical: Int = 0,
      storeSource: Boolean = false
  ): Unit = {
    val issue = if (m1) dut.io.m1Issue else dut.io.m0Issue
    issue.valid.poke(true)
    issue.bits.robTag.poke(tag)
    issue.bits.allowedEndpoints.poke(endpoints)
    issue.bits.uopClass.poke(uopClass)
    issue.bits.operation.poke(operation.asUInt.litValue)
    issue.bits.sourceKind(0).poke(SourceKind.IntegerRegister)
    issue.bits.sourceKind(1).poke(if (storeSource) SourceKind.IntegerRegister else SourceKind.None)
    issue.bits.sourceKind(2).poke(SourceKind.None)
    issue.bits.sourcePhysical(0).poke(basePhysical)
    issue.bits.sourcePhysical(1).poke(storePhysical)
    issue.bits.sourceReady.foreach(_.poke(true))
    issue.bits.destinationPhysical.poke(32)
    issue.bits.writesInteger.poke(uopClass != UopClass.Store)
    issue.bits.writesFloat.poke(false)
    issue.bits.immediate.poke(0)
  }

  private def context(dut: DualLSUIngress, lane: Int, tag: Int): Unit = {
    val value = dut.io.robContext(lane)
    value.valid.poke(true)
    value.bits.robTag.poke(tag)
  }

  private def clearIssues(dut: DualLSUIngress): Unit =
    Seq(dut.io.m0Issue, dut.io.m1Issue).foreach(_.valid.poke(false))

  describe("DualLSUIngress") {
    it("moves an eligible M1 load through operand read and LQ ownership") {
      simulate(new DualLSUIngress) { dut =>
        clear(dut)
        driveIssue(dut, m1 = true, tag = 4, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, basePhysical = 8)
        context(dut, lane = 1, tag = 4)
        dut.io.prfReadData(2).poke(BigInt("80001000", 16))
        dut.io.m1Issue.ready.expect(true)
        dut.io.robRead(1).valid.expect(true)
        dut.io.robRead(1).bits.expect(4)
        dut.clock.step()
        clearIssues(dut)
        dut.clock.step()
        dut.io.loadCount.expect(1)
        dut.io.storeCount.expect(0)
        dut.io.loadForward.valid.expect(true)
        dut.io.loadForward.bits.robTag.expect(4)
        dut.io.storeEffect.valid.expect(false)
      }
    }

    it("replays a device candidate to M0 behind a direct store") {
      simulate(new DualLSUIngress) { dut =>
        clear(dut)
        driveIssue(dut, m1 = false, tag = 3, IntOperation.Sw, UopClass.Store,
          EndpointMask.M0, basePhysical = 6, storePhysical = 7, storeSource = true)
        driveIssue(dut, m1 = true, tag = 4, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, basePhysical = 8)
        context(dut, lane = 0, tag = 3)
        context(dut, lane = 1, tag = 4)
        dut.io.prfReadData(0).poke(BigInt("80002000", 16))
        dut.io.prfReadData(1).poke(BigInt("deadbeef", 16))
        dut.io.prfReadData(2).poke(BigInt("a0000000", 16))
        dut.io.m0Issue.ready.expect(true)
        dut.io.m1Issue.ready.expect(true)
        dut.clock.step()
        clearIssues(dut)
        dut.clock.step(3)
        dut.io.loadCount.expect(1)
        dut.io.storeCount.expect(1)
        dut.io.storeEffect.valid.expect(false)
      }
    }

    it("emits a precise M0 address fault without allocating queue state") {
      simulate(new DualLSUIngress) { dut =>
        clear(dut)
        driveIssue(dut, m1 = false, tag = 7, IntOperation.Lh, UopClass.Load,
          EndpointMask.M0, basePhysical = 6)
        context(dut, lane = 0, tag = 7)
        dut.io.prfReadData(0).poke(BigInt("80003001", 16))
        dut.io.m0Issue.ready.expect(true)
        dut.io.fault(0).valid.expect(true)
        dut.io.fault(0).record.robTag.expect(7)
        dut.io.fault(0).record.cause.expect(4)
        dut.io.fault(0).record.trapValue.expect(BigInt("80003001", 16))
        dut.clock.step()
        clearIssues(dut)
        dut.io.loadCount.expect(0)
        dut.io.storeCount.expect(0)
      }
    }

    it("replays an inaccessible M1 candidate to the exact M0 fault owner") {
      simulate(new DualLSUIngress) { dut =>
        clear(dut)
        driveIssue(dut, m1 = true, tag = 8, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, basePhysical = 8)
        context(dut, lane = 1, tag = 8)
        dut.io.prfReadData(2).poke(0)
        dut.io.m1Issue.ready.expect(true)
        dut.clock.step()
        clearIssues(dut)

        dut.io.fault(0).valid.expect(true)
        dut.io.fault(0).record.robTag.expect(8)
        dut.io.fault(0).record.cause.expect(5)
        dut.io.fault(0).record.trapValue.expect(0)
        dut.clock.step()
        dut.io.loadCount.expect(0)
      }
    }

    it("routes an M1 response through its own completion buffer") {
      simulate(new DualLSUIngress) { dut =>
        clear(dut)
        driveIssue(dut, m1 = true, tag = 11, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, basePhysical = 8)
        context(dut, lane = 1, tag = 11)
        dut.io.prfReadData(2).poke(BigInt("80005000", 16))
        dut.clock.step()
        clearIssues(dut)
        dut.clock.step(2)
        dut.io.loadComplete.valid.poke(true)
        dut.io.loadComplete.bits.robTag.poke(11)
        dut.io.loadComplete.bits.cacheData.poke(BigInt("cafebabe", 16))
        dut.io.loadComplete.ready.expect(true)
        dut.clock.step()
        dut.io.loadComplete.valid.poke(false)
        dut.io.m0Completion.valid.expect(false)
        dut.io.m1Completion.valid.expect(true)
        dut.io.m1Completion.bits.robTag.expect(11)
        dut.io.m1Completion.bits.data.expect(BigInt("cafebabe", 16))
      }
    }

    it("blocks issue and ROB reads during a global flush") {
      simulate(new DualLSUIngress) { dut =>
        clear(dut)
        driveIssue(dut, m1 = true, tag = 9, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, basePhysical = 8)
        context(dut, lane = 1, tag = 9)
        dut.io.flush.poke(true)
        dut.io.m0Issue.ready.expect(false)
        dut.io.m1Issue.ready.expect(false)
        dut.io.robRead(0).valid.expect(false)
        dut.io.robRead(1).valid.expect(false)
        dut.io.fault.foreach(_.valid.expect(false))
      }
    }
  }
}
