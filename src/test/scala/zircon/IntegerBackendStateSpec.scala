package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, IntegerBackendState, UopClass}
import zircon.frontend.IntOperation

class IntegerBackendStateSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: IntegerBackendState): Unit = {
    dut.io.enqueue.foreach(_.valid.poke(false))
    dut.io.readyAllocation.foreach { allocation =>
      allocation.valid.poke(false)
      allocation.bits.poke(0)
    }
    dut.io.endpointCompletion.zipWithIndex.foreach { case (result, index) =>
      result.valid.poke(false)
      result.bits.robTag.poke(index)
      result.bits.writesInteger.poke(false)
      result.bits.destinationPhysical.poke(0)
      result.bits.data.poke(0)
    }
    dut.io.readPhysical.foreach(_.poke(0))
    dut.io.executionRead.foreach { read =>
      read.valid.poke(false)
      read.bits.poke(0)
    }
    dut.io.commit.foreach(_.ready.poke(false))
    dut.io.rollback.valid.poke(false)
    dut.io.rollback.bits.poke(0)
    dut.io.rollbackUndo.ready.poke(false)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def driveEnqueue(
      dut: IntegerBackendState,
      lane: Int,
      pc: BigInt,
      architectural: Int,
      oldPhysical: Int,
      newPhysical: Int,
      initiallyComplete: Boolean = false
  ): Unit = {
    val enqueue = dut.io.enqueue(lane)
    val entry = enqueue.bits.entry
    val decoded = entry.decoded
    enqueue.valid.poke(true)
    enqueue.bits.initiallyComplete.poke(initiallyComplete)
    entry.pc.poke(pc)
    entry.instruction.poke(BigInt("00000013", 16))
    entry.privilege.poke(3)
    entry.architecturalDestination.poke(architectural)
    entry.oldPhysicalDestination.poke(oldPhysical)
    entry.newPhysicalDestination.poke(newPhysical)
    entry.allocatesPhysical.poke(true)
    entry.hasBranchData.poke(false)
    entry.branchDataIndex.poke(0)

    decoded.legal.poke(true)
    decoded.operation.poke(IntOperation.Add)
    decoded.uopClass.poke(UopClass.Integer)
    decoded.allowedEndpoints.poke(EndpointMask.IntegerSimple)
    decoded.rs1.poke(1)
    decoded.rs2.poke(2)
    decoded.rd.poke(architectural)
    decoded.readsRs1.poke(true)
    decoded.readsRs2.poke(true)
    decoded.writesRd.poke(true)
    decoded.operandBImmediate.poke(false)
    decoded.immediate.poke(0)
    decoded.csrAddress.poke(0)
    decoded.csrImmediate.poke(0)
    decoded.csrRead.poke(false)
    decoded.csrWrite.poke(false)
    decoded.isControl.poke(false)
    decoded.isMemory.poke(false)
    decoded.isFenceI.poke(false)

    dut.io.readyAllocation(lane).valid.poke(true)
    dut.io.readyAllocation(lane).bits.poke(newPhysical)
  }

  private def driveResult(
      dut: IntegerBackendState,
      endpoint: Int,
      robTag: Int,
      physical: Int,
      data: BigInt
  ): Unit = {
    val result = dut.io.endpointCompletion(endpoint)
    result.valid.poke(true)
    result.bits.robTag.poke(robTag)
    result.bits.writesInteger.poke(true)
    result.bits.destinationPhysical.poke(physical)
    result.bits.data.poke(data)
  }

  private def expectReady(
      dut: IntegerBackendState,
      physical: Int,
      expected: Boolean
  ): Unit = {
    val actual = dut.io.integerReady.peek().litValue.testBit(physical)
    assert(actual == expected,
      s"physical register p$physical ready=$actual, expected $expected")
  }

  describe("IntegerBackendState") {
    it("completes two ROB entries and updates PRF, readiness, and wakeup atomically") {
      simulate(new IntegerBackendState) { dut =>
        clearInputs(dut)
        driveEnqueue(dut, 0, BigInt("80000000", 16), 5, 5, 32)
        driveEnqueue(dut, 1, BigInt("80000004", 16), 6, 6, 33)
        dut.io.enqueueTag(0).bits.expect(0)
        dut.io.enqueueTag(1).bits.expect(1)
        dut.clock.step()

        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.readyAllocation.foreach(_.valid.poke(false))
        expectReady(dut, 32, expected = false)
        expectReady(dut, 33, expected = false)
        dut.io.readPhysical(0).poke(32)
        dut.io.readPhysical(1).poke(33)
        driveResult(dut, endpoint = 0, robTag = 1, physical = 33,
          data = BigInt("bbbbbbbb", 16))
        driveResult(dut, endpoint = 1, robTag = 0, physical = 32,
          data = BigInt("aaaaaaaa", 16))

        dut.io.endpointCompletion(0).ready.expect(true)
        dut.io.endpointCompletion(1).ready.expect(true)
        dut.io.completionAccepted.foreach(_.expect(true))
        dut.io.completionDiscarded.foreach(_.expect(false))
        dut.io.wakeup(0).valid.expect(true)
        dut.io.wakeup(0).physical.expect(32)
        dut.io.wakeup(1).valid.expect(true)
        dut.io.wakeup(1).physical.expect(33)
        expectReady(dut, 32, expected = true)
        expectReady(dut, 33, expected = true)
        dut.io.readData(0).expect(BigInt("aaaaaaaa", 16))
        dut.io.readData(1).expect(BigInt("bbbbbbbb", 16))
        dut.clock.step()

        dut.io.endpointCompletion.foreach(_.valid.poke(false))
        dut.io.wakeup.foreach(_.valid.expect(false))
        dut.io.commit(0).valid.expect(true)
        dut.io.commit(0).bits.robTag.expect(0)
        dut.io.commit(1).valid.expect(true)
        dut.io.commit(1).bits.robTag.expect(1)
        dut.io.readData(0).expect(BigInt("aaaaaaaa", 16))
        dut.io.readData(1).expect(BigInt("bbbbbbbb", 16))
      }
    }

    it("drains a post-flush stale result without changing PRF or readiness") {
      simulate(new IntegerBackendState) { dut =>
        clearInputs(dut)
        driveEnqueue(dut, 0, BigInt("80000000", 16), 5, 5, 32)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.readyAllocation(1).valid.poke(false)
        dut.clock.step()

        dut.io.enqueue(0).valid.poke(false)
        dut.io.readyAllocation(0).valid.poke(false)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)

        driveEnqueue(dut, 0, BigInt("80001000", 16), 6, 6, 33)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.readyAllocation(1).valid.poke(false)
        dut.io.enqueueTag(0).bits.expect(32)
        dut.clock.step()
        dut.io.enqueue(0).valid.poke(false)
        dut.io.readyAllocation(0).valid.poke(false)

        dut.io.readPhysical(0).poke(32)
        driveResult(dut, endpoint = 3, robTag = 0, physical = 32,
          data = BigInt("deadbeef", 16))
        dut.io.endpointCompletion(3).ready.expect(true)
        dut.io.completionAccepted.foreach(_.expect(false))
        dut.io.completionDiscarded(0).expect(true)
        dut.io.completionDiscarded(1).expect(false)
        dut.io.wakeup.foreach(_.valid.expect(false))
        dut.io.readData(0).expect(0)
        expectReady(dut, 32, expected = false)
        dut.clock.step()
        dut.io.endpointCompletion(3).valid.poke(false)
        dut.io.readData(0).expect(0)
        dut.io.robCount.expect(1)
        dut.io.commit(0).valid.expect(false)
      }
    }

    it("holds a younger completion through rollback and discards it afterwards") {
      simulate(new IntegerBackendState) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 2) {
          driveEnqueue(dut, 0, BigInt("80000000", 16) + pair * 8,
            pair * 2 + 1, pair * 2 + 1, 32 + pair * 2)
          driveEnqueue(dut, 1, BigInt("80000004", 16) + pair * 8,
            pair * 2 + 2, pair * 2 + 2, 33 + pair * 2)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.readyAllocation.foreach(_.valid.poke(false))
        driveResult(dut, endpoint = 2, robTag = 3, physical = 35,
          data = BigInt("33333333", 16))
        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.poke(1)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(1)
        dut.io.endpointCompletion(2).ready.expect(false)
        dut.clock.step()

        dut.io.rollback.valid.poke(false)
        dut.io.squash.valid.poke(false)
        dut.io.rollbackActive.expect(true)
        dut.io.endpointCompletion(2).ready.expect(false)
        dut.io.completionAccepted.foreach(_.expect(false))
        dut.io.completionDiscarded.foreach(_.expect(false))
        dut.io.rollbackUndo.ready.poke(true)
        dut.io.rollbackDone.expect(true)
        dut.clock.step()

        dut.io.rollbackActive.expect(false)
        dut.io.robCount.expect(2)
        dut.io.endpointCompletion(2).ready.expect(true)
        dut.io.completionDiscarded(0).expect(true)
        dut.io.wakeup.foreach(_.valid.expect(false))
        expectReady(dut, 35, expected = false)
      }
    }
  }
}
