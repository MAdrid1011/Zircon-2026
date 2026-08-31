package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, IntegerExecutionBackend, SourceKind,
  UopClass}
import zircon.frontend.IntOperation

class IntegerExecutionBackendSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: IntegerExecutionBackend): Unit = {
    dut.io.robEnqueue.foreach(_.valid.poke(false))
    dut.io.intEnqueue.foreach(_.valid.poke(false))
    dut.io.readyAllocation.foreach { allocation =>
      allocation.valid.poke(false)
      allocation.bits.poke(0)
    }
    dut.io.otherCompletion.zipWithIndex.foreach { case (result, index) =>
      result.valid.poke(false)
      result.bits.robTag.poke(index)
      result.bits.writesInteger.poke(false)
      result.bits.destinationPhysical.poke(0)
      result.bits.data.poke(0)
    }
    dut.io.branchResolve.ready.poke(false)
    dut.io.auxReadPhysical.foreach(_.poke(0))
    dut.io.commit.foreach(_.ready.poke(false))
    dut.io.rollback.valid.poke(false)
    dut.io.rollback.bits.poke(0)
    dut.io.rollbackUndo.ready.poke(false)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.recoveryActive.poke(false)
    dut.io.flush.poke(false)
  }

  private def driveRobEntry(
      dut: IntegerExecutionBackend,
      lane: Int,
      pc: BigInt,
      operation: IntOperation.Type,
      uopClass: UopClass.Type,
      architectural: Int,
      oldPhysical: Int,
      newPhysical: Int,
      allocates: Boolean,
      hasBranchData: Boolean = false,
      branchDataIndex: Int = 0
  ): Unit = {
    val enqueue = dut.io.robEnqueue(lane)
    val entry = enqueue.bits.entry
    val decoded = entry.decoded
    enqueue.valid.poke(true)
    enqueue.bits.initiallyComplete.poke(false)
    entry.pc.poke(pc)
    entry.instruction.poke(BigInt("00000013", 16))
    entry.privilege.poke(3)
    entry.architecturalDestination.poke(architectural)
    entry.oldPhysicalDestination.poke(oldPhysical)
    entry.newPhysicalDestination.poke(newPhysical)
    entry.allocatesPhysical.poke(allocates)
    entry.hasBranchData.poke(hasBranchData)
    entry.branchDataIndex.poke(branchDataIndex)

    decoded.legal.poke(true)
    decoded.operation.poke(operation)
    decoded.uopClass.poke(uopClass)
    decoded.allowedEndpoints.poke(
      if (uopClass == UopClass.Branch) EndpointMask.E0 else EndpointMask.E1)
    decoded.rs1.poke(0)
    decoded.rs2.poke(0)
    decoded.rd.poke(architectural)
    decoded.readsRs1.poke(false)
    decoded.readsRs2.poke(false)
    decoded.writesRd.poke(allocates)
    decoded.operandBImmediate.poke(false)
    decoded.immediate.poke(0)
    decoded.csrAddress.poke(0)
    decoded.csrImmediate.poke(0)
    decoded.csrRead.poke(false)
    decoded.csrWrite.poke(false)
    decoded.isControl.poke(uopClass == UopClass.Branch)
    decoded.isMemory.poke(false)
    decoded.isFenceI.poke(false)
  }

  private def driveUop(
      dut: IntegerExecutionBackend,
      lane: Int,
      robTag: Int,
      endpoint: Int,
      uopClass: UopClass.Type,
      operation: IntOperation.Type,
      source0Physical: Int,
      source0Ready: Boolean,
      source1Kind: SourceKind.Type,
      destinationPhysical: Int,
      writesInteger: Boolean,
      immediate: BigInt
  ): Unit = {
    val enqueue = dut.io.intEnqueue(lane)
    val uop = enqueue.bits
    enqueue.valid.poke(true)
    uop.robTag.poke(robTag)
    uop.allowedEndpoints.poke(endpoint)
    uop.uopClass.poke(uopClass)
    uop.operation.poke(operation.litValue)
    uop.sourceKind(0).poke(SourceKind.IntegerRegister)
    uop.sourceKind(1).poke(source1Kind)
    uop.sourceKind(2).poke(SourceKind.None)
    uop.sourcePhysical(0).poke(source0Physical)
    uop.sourcePhysical(1).poke(0)
    uop.sourceReady(0).poke(source0Ready)
    uop.sourceReady(1).poke(true)
    uop.sourceReady(2).poke(true)
    uop.destinationPhysical.poke(destinationPhysical)
    uop.writesInteger.poke(writesInteger)
    uop.writesFloat.poke(false)
    uop.immediate.poke(immediate)
  }

  private def expectReady(
      dut: IntegerExecutionBackend,
      physical: Int,
      expected: Boolean
  ): Unit = {
    val actual = dut.io.integerReady.peek().litValue.testBit(physical)
    assert(actual == expected,
      s"physical register p$physical ready=$actual, expected $expected")
  }

  describe("IntegerExecutionBackend") {
    it("executes a dependent pair through issue, wakeup, writeback, and commit") {
      simulate(new IntegerExecutionBackend) { dut =>
        clearInputs(dut)
        driveRobEntry(dut, 0, BigInt("80000000", 16), IntOperation.Add,
          UopClass.Integer, 5, 5, 32, allocates = true)
        driveRobEntry(dut, 1, BigInt("80000004", 16), IntOperation.Add,
          UopClass.Integer, 6, 6, 33, allocates = true)
        driveUop(dut, 0, robTag = 0, endpoint = EndpointMask.E1,
          uopClass = UopClass.Integer, operation = IntOperation.Add,
          source0Physical = 0, source0Ready = true,
          source1Kind = SourceKind.Immediate, destinationPhysical = 32,
          writesInteger = true, immediate = 5)
        driveUop(dut, 1, robTag = 1, endpoint = EndpointMask.E1,
          uopClass = UopClass.Integer, operation = IntOperation.Add,
          source0Physical = 32, source0Ready = false,
          source1Kind = SourceKind.Immediate, destinationPhysical = 33,
          writesInteger = true, immediate = 3)
        dut.io.readyAllocation(0).valid.poke(true)
        dut.io.readyAllocation(0).bits.poke(32)
        dut.io.readyAllocation(1).valid.poke(true)
        dut.io.readyAllocation(1).bits.poke(33)
        dut.io.auxReadPhysical(0).poke(32)
        dut.io.auxReadPhysical(1).poke(33)
        dut.io.robEnqueue.foreach(_.ready.expect(true))
        dut.io.intEnqueue.foreach(_.ready.expect(true))
        dut.clock.step()

        dut.io.robEnqueue.foreach(_.valid.poke(false))
        dut.io.intEnqueue.foreach(_.valid.poke(false))
        dut.io.readyAllocation.foreach(_.valid.poke(false))
        dut.io.intCount.expect(2)
        expectReady(dut, 32, expected = false)
        expectReady(dut, 33, expected = false)

        // The only ready uop enters E1.
        dut.clock.step()
        dut.io.intCount.expect(1)
        dut.io.e1Count.expect(1)

        // Producer completion is accepted and wakes p32.
        dut.io.completionAccepted(0).expect(true)
        dut.io.wakeup(0).valid.expect(true)
        dut.io.wakeup(0).physical.expect(32)
        dut.io.auxReadData(0).expect(5)
        expectReady(dut, 32, expected = true)
        dut.clock.step()
        // Completion wakeup bypasses directly into issue, replacing the E1
        // result slot in the producer pop cycle.
        dut.io.intCount.expect(0)
        dut.io.e1Count.expect(1)

        dut.io.completionAccepted(0).expect(true)
        dut.io.wakeup(0).valid.expect(true)
        dut.io.wakeup(0).physical.expect(33)
        dut.io.auxReadData(1).expect(8)
        expectReady(dut, 33, expected = true)
        dut.clock.step()

        dut.io.commit(0).valid.expect(true)
        dut.io.commit(0).bits.robTag.expect(0)
        dut.io.commit(1).valid.expect(true)
        dut.io.commit(1).bits.robTag.expect(1)
        dut.io.auxReadData(0).expect(5)
        dut.io.auxReadData(1).expect(8)
      }
    }

    it("resolves an E0 branch before completing its ROB entry") {
      simulate(new IntegerExecutionBackend) { dut =>
        clearInputs(dut)
        driveRobEntry(dut, 0, BigInt("80000000", 16), IntOperation.Beq,
          UopClass.Branch, 0, 0, 0, allocates = false,
          hasBranchData = true, branchDataIndex = 3)
        dut.io.robEnqueue(1).valid.poke(false)
        driveUop(dut, 0, robTag = 0, endpoint = EndpointMask.E0,
          uopClass = UopClass.Branch, operation = IntOperation.Beq,
          source0Physical = 0, source0Ready = true,
          source1Kind = SourceKind.IntegerRegister,
          destinationPhysical = 0, writesInteger = false, immediate = 8)
        dut.io.intEnqueue(1).valid.poke(false)
        dut.clock.step()

        dut.io.robEnqueue(0).valid.poke(false)
        dut.io.intEnqueue(0).valid.poke(false)
        dut.io.intCount.expect(1)
        dut.clock.step()
        dut.io.e0Occupied.expect(true)

        dut.io.branchResolve.valid.expect(true)
        dut.io.branchResolve.bits.reference.robTag.expect(0)
        dut.io.branchResolve.bits.reference.index.expect(3)
        dut.io.branchResolve.bits.actualTaken.expect(true)
        dut.io.branchResolve.bits.actualTarget.expect(BigInt("80000008", 16))
        dut.io.completionAccepted.foreach(_.expect(false))
        dut.clock.step(2)
        dut.io.branchResolve.valid.expect(true)

        dut.io.branchResolve.ready.poke(true)
        dut.clock.step()
        dut.io.branchResolve.ready.poke(false)
        dut.io.completionAccepted(0).expect(true)
        dut.clock.step()

        dut.io.commit(0).valid.expect(true)
        dut.io.commit(0).bits.robTag.expect(0)
      }
    }
  }
}
