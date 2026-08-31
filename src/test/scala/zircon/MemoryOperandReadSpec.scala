package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, SourceKind, UopClass}
import zircon.frontend.IntOperation
import zircon.memory.MemoryOperandRead

class MemoryOperandReadSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: MemoryOperandRead): Unit = {
    dut.io.issue.foreach { issue =>
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
    dut.io.request.foreach(_.ready.poke(false))
    dut.io.flush.poke(false)
  }

  private def driveIssue(
      dut: MemoryOperandRead,
      lane: Int,
      tag: Int,
      operation: IntOperation.Type,
      uopClass: UopClass.Type,
      endpoints: Int,
      basePhysical: Int,
      storePhysical: Int,
      storeSource: Boolean
  ): Unit = {
    val issue = dut.io.issue(lane)
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

  private def context(dut: MemoryOperandRead, lane: Int, tag: Int, aq: Boolean, rl: Boolean): Unit = {
    val value = dut.io.robContext(lane)
    value.valid.poke(true)
    value.bits.robTag.poke(tag)
    value.bits.pc.poke(0)
    value.bits.instruction.poke(0)
    value.bits.privilege.poke(3)
    value.bits.csrAddress.poke(0)
    value.bits.csrImmediate.poke(0)
    value.bits.csrRead.poke(false)
    value.bits.csrWrite.poke(false)
    value.bits.atomicAq.poke(aq)
    value.bits.atomicRl.poke(rl)
    value.bits.hasBranchData.poke(false)
    value.bits.branchDataIndex.poke(0)
  }

  describe("MemoryOperandRead") {
    it("reads base/store operands and ROB-owned atomic metadata") {
      simulate(new MemoryOperandRead) { dut =>
        clear(dut)
        driveIssue(dut, 0, 4, IntOperation.AmoAddW, UopClass.Atomic,
          EndpointMask.M0, basePhysical = 6, storePhysical = 7, storeSource = true)
        context(dut, 0, 4, aq = true, rl = true)
        dut.io.prfReadData(0).poke(BigInt("80001000", 16))
        dut.io.prfReadData(1).poke(BigInt("cafebabe", 16))
        dut.io.request(0).ready.poke(true)
        dut.io.robRead(0).valid.expect(true)
        dut.io.robRead(0).bits.expect(4)
        dut.io.prfReadPhysical(0).expect(6)
        dut.io.prfReadPhysical(1).expect(7)
        dut.io.request(0).valid.expect(true)
        dut.io.request(0).bits.base.expect(BigInt("80001000", 16))
        dut.io.request(0).bits.storeData.expect(BigInt("cafebabe", 16))
        dut.io.request(0).bits.atomicAq.expect(true)
        dut.io.request(0).bits.atomicRl.expect(true)
        dut.io.issue(0).ready.expect(true)
      }
    }

    it("uses zero store data for a load and suppresses issue on flush") {
      simulate(new MemoryOperandRead) { dut =>
        clear(dut)
        driveIssue(dut, 1, 5, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, basePhysical = 8, storePhysical = 0,
          storeSource = false)
        context(dut, 1, 5, aq = false, rl = false)
        dut.io.prfReadData(2).poke(BigInt("80002000", 16))
        dut.io.request(1).ready.poke(true)
        dut.io.request(1).valid.expect(true)
        dut.io.request(1).bits.base.expect(BigInt("80002000", 16))
        dut.io.request(1).bits.storeData.expect(0)
        dut.io.flush.poke(true)
        dut.io.request(1).valid.expect(false)
        dut.io.robRead(1).valid.expect(false)
        dut.io.issue(1).ready.expect(false)
      }
    }
  }
}
