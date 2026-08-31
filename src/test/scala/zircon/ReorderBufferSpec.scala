package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, ReorderBuffer, UopClass}
import zircon.frontend.IntOperation

class ReorderBufferSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: ReorderBuffer): Unit = {
    dut.io.enqueue.foreach(_.valid.poke(false))
    dut.io.completion.foreach { completion =>
      completion.valid.poke(false)
      completion.robTag.poke(0)
    }
    dut.io.commit.foreach(_.ready.poke(false))
    dut.io.flush.poke(false)
    dut.io.rollback.valid.poke(false)
    dut.io.rollback.bits.poke(0)
    dut.io.rollbackUndo.ready.poke(false)
    dut.io.executionRead.foreach { read =>
      read.valid.poke(false)
      read.bits.poke(0)
    }
  }

  private def driveEnqueue(
      dut: ReorderBuffer,
      lane: Int,
      pc: BigInt,
      initiallyComplete: Boolean
  ): Unit = {
    val enqueue = dut.io.enqueue(lane)
    val entry = enqueue.bits.entry
    val decoded = entry.decoded
    enqueue.valid.poke(true)
    enqueue.bits.initiallyComplete.poke(initiallyComplete)
    entry.pc.poke(pc)
    entry.instruction.poke(BigInt("00000013", 16))
    entry.privilege.poke(3)
    entry.architecturalDestination.poke(lane + 1)
    entry.oldPhysicalDestination.poke(lane + 1)
    entry.newPhysicalDestination.poke(32 + lane)
    entry.allocatesPhysical.poke(true)
    entry.hasBranchData.poke(false)
    entry.branchDataIndex.poke(0)

    decoded.legal.poke(true)
    decoded.operation.poke(IntOperation.Add)
    decoded.uopClass.poke(UopClass.Integer)
    decoded.allowedEndpoints.poke(EndpointMask.IntegerSimple)
    decoded.rs1.poke(1)
    decoded.rs2.poke(2)
    decoded.rd.poke(lane + 1)
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
  }

  describe("ReorderBuffer") {
    it("accepts two entries, completes out of order, and only commits a contiguous prefix") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        dut.io.enqueueCapacity.expect(2)
        driveEnqueue(dut, 0, BigInt("80000000", 16), initiallyComplete = false)
        driveEnqueue(dut, 1, BigInt("80000004", 16), initiallyComplete = true)
        dut.io.enqueue(0).ready.expect(true)
        dut.io.enqueueTag(0).bits.expect(0)
        dut.io.enqueueTag(1).bits.expect(1)
        dut.clock.step()

        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.count.expect(2)
        dut.io.commit(0).valid.expect(false)
        dut.io.completion(0).valid.poke(true)
        dut.io.completion(0).robTag.poke(0)
        dut.io.completionAccepted(0).expect(true)
        dut.clock.step()

        dut.io.completion(0).valid.poke(false)
        dut.io.commit(0).valid.expect(true)
        dut.io.commit(1).valid.expect(true)
        dut.io.commit(0).bits.entry.pc.expect(BigInt("80000000", 16))
        dut.io.commit(1).bits.entry.pc.expect(BigInt("80000004", 16))
        dut.io.commit.foreach(_.ready.poke(true))
        dut.clock.step()
        dut.io.count.expect(0)
      }
    }

    it("uses completion bandwidth out of order without bypassing an incomplete head") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 2) {
          driveEnqueue(dut, 0, BigInt("80000000", 16) + pair * 8, initiallyComplete = false)
          driveEnqueue(dut, 1, BigInt("80000004", 16) + pair * 8, initiallyComplete = false)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.completion(0).valid.poke(true)
        dut.io.completion(0).robTag.poke(2)
        dut.io.completion(1).valid.poke(true)
        dut.io.completion(1).robTag.poke(3)
        dut.clock.step()
        dut.io.completion.foreach(_.valid.poke(false))
        dut.io.commit(0).valid.expect(false)

        dut.io.completion(0).valid.poke(true)
        dut.io.completion(0).robTag.poke(0)
        dut.io.completion(1).valid.poke(true)
        dut.io.completion(1).robTag.poke(1)
        dut.clock.step()
        dut.io.completion.foreach(_.valid.poke(false))
        dut.io.commit(0).valid.expect(true)
        dut.io.commit(1).valid.expect(true)
      }
    }

    it("reads narrow execution context by live tag and suppresses it on flush") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        driveEnqueue(dut, 0, BigInt("80000040", 16), initiallyComplete = false)
        driveEnqueue(dut, 1, BigInt("80000044", 16), initiallyComplete = false)
        dut.io.enqueue(0).bits.entry.decoded.csrAddress.poke(0x305)
        dut.io.enqueue(0).bits.entry.decoded.csrImmediate.poke(7)
        dut.io.enqueue(0).bits.entry.decoded.csrRead.poke(true)
        dut.io.enqueue(0).bits.entry.decoded.csrWrite.poke(true)
        dut.io.enqueue(0).bits.entry.hasBranchData.poke(true)
        dut.io.enqueue(0).bits.entry.branchDataIndex.poke(5)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.executionRead(0).valid.poke(true)
        dut.io.executionRead(0).bits.poke(1)
        dut.io.executionRead(1).valid.poke(true)
        dut.io.executionRead(1).bits.poke(0)
        dut.io.executionContext(0).valid.expect(true)
        dut.io.executionContext(0).bits.robTag.expect(1)
        dut.io.executionContext(0).bits.pc.expect(BigInt("80000044", 16))
        dut.io.executionContext(0).bits.hasBranchData.expect(false)
        dut.io.executionContext(1).valid.expect(true)
        dut.io.executionContext(1).bits.robTag.expect(0)
        dut.io.executionContext(1).bits.pc.expect(BigInt("80000040", 16))
        dut.io.executionContext(1).bits.privilege.expect(3)
        dut.io.executionContext(1).bits.csrAddress.expect(0x305)
        dut.io.executionContext(1).bits.csrImmediate.expect(7)
        dut.io.executionContext(1).bits.csrRead.expect(true)
        dut.io.executionContext(1).bits.csrWrite.expect(true)
        dut.io.executionContext(1).bits.hasBranchData.expect(true)
        dut.io.executionContext(1).bits.branchDataIndex.expect(5)

        dut.io.flush.poke(true)
        dut.io.executionContext.foreach(_.valid.expect(false))
        dut.clock.step()
        dut.io.executionRead.foreach(_.valid.poke(false))
        dut.io.flush.poke(false)
        dut.io.count.expect(0)
      }
    }

    it("reuses two retiring slots while full and flips generation on natural wrap") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 12) {
          driveEnqueue(dut, 0, BigInt("80000000", 16) + pair * 8, initiallyComplete = true)
          driveEnqueue(dut, 1, BigInt("80000004", 16) + pair * 8, initiallyComplete = true)
          dut.io.enqueueTag(0).bits.expect(pair * 2)
          dut.io.enqueueTag(1).bits.expect(pair * 2 + 1)
          dut.clock.step()
        }
        dut.io.count.expect(24)
        dut.io.enqueueCapacity.expect(0)

        driveEnqueue(dut, 0, BigInt("80000100", 16), initiallyComplete = false)
        driveEnqueue(dut, 1, BigInt("80000104", 16), initiallyComplete = false)
        dut.io.commit.foreach(_.ready.poke(true))
        dut.io.enqueueCapacity.expect(2)
        dut.io.enqueue(0).ready.expect(true)
        dut.io.enqueueTag(0).bits.expect(32)
        dut.io.enqueueTag(1).bits.expect(33)
        dut.clock.step()
        dut.io.count.expect(24)
        dut.io.commit(0).bits.robTag.expect(2)
      }
    }

    it("changes generation on flush and rejects stale completions") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        driveEnqueue(dut, 0, BigInt("80000000", 16), initiallyComplete = false)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.enqueueTag(0).bits.expect(0)
        dut.clock.step()

        dut.io.enqueue(0).valid.poke(false)
        dut.io.completion(0).valid.poke(true)
        dut.io.completion(0).robTag.poke(0)
        dut.io.flush.poke(true)
        dut.io.completionAccepted(0).expect(false)
        dut.clock.step()
        dut.io.count.expect(0)

        dut.io.flush.poke(false)
        dut.io.completion(0).valid.poke(false)
        driveEnqueue(dut, 0, BigInt("80001000", 16), initiallyComplete = false)
        dut.io.enqueueTag(0).bits.expect(32)
        dut.clock.step()

        dut.io.enqueue(0).valid.poke(false)
        dut.io.completion(0).valid.poke(true)
        dut.io.completion(0).robTag.poke(0)
        dut.io.completionAccepted(0).expect(false)
        dut.clock.step()
        dut.io.commit(0).valid.expect(false)

        dut.io.completion(0).robTag.poke(32)
        dut.io.completionAccepted(0).expect(true)
        dut.clock.step()
        dut.io.completion(0).valid.poke(false)
        dut.io.commit(0).valid.expect(true)
      }
    }

    it("walks the tail two entries at a time and preserves the resolving prefix") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 3) {
          driveEnqueue(dut, 0, BigInt("80000000", 16) + pair * 8,
            initiallyComplete = true)
          driveEnqueue(dut, 1, BigInt("80000004", 16) + pair * 8,
            initiallyComplete = true)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.count.expect(6)

        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.poke(1)
        dut.io.rollback.ready.expect(true)
        dut.io.enqueueCapacity.expect(0)
        dut.io.commit.foreach(_.valid.expect(false))
        dut.clock.step()
        dut.io.rollback.valid.poke(false)
        dut.io.rollbackActive.expect(true)
        dut.io.rollbackUndo.valid.expect(true)
        dut.io.rollbackUndo.bits.count.expect(2)
        dut.io.rollbackUndo.bits.records(0).robTag.expect(5)
        dut.io.rollbackUndo.bits.records(1).robTag.expect(4)

        // Backpressure must keep the tail bundle and occupancy stable.
        dut.clock.step()
        dut.io.count.expect(6)
        dut.io.rollbackUndo.bits.records(0).robTag.expect(5)
        dut.io.rollbackUndo.ready.poke(true)
        dut.io.rollbackDone.expect(false)
        dut.clock.step()
        dut.io.count.expect(4)
        dut.io.rollbackUndo.bits.records(0).robTag.expect(3)
        dut.io.rollbackUndo.bits.records(1).robTag.expect(2)
        dut.io.rollbackDone.expect(true)
        dut.clock.step()
        dut.io.count.expect(2)
        dut.io.rollbackActive.expect(false)
        dut.io.rollbackUndo.valid.expect(false)
        dut.io.commit(0).valid.expect(true)
        dut.io.commit(0).bits.robTag.expect(0)
        dut.io.commit(1).bits.robTag.expect(1)
      }
    }

    it("completes a zero-younger rollback without entering walker state") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        driveEnqueue(dut, 0, BigInt("80000000", 16), initiallyComplete = true)
        driveEnqueue(dut, 1, BigInt("80000004", 16), initiallyComplete = true)
        dut.clock.step()
        dut.io.enqueue.foreach(_.valid.poke(false))

        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.poke(1)
        dut.io.rollback.ready.expect(true)
        dut.io.rollbackDone.expect(true)
        dut.clock.step()
        dut.io.rollback.valid.poke(false)
        dut.io.rollbackActive.expect(false)
        dut.io.count.expect(2)
      }
    }

    it("changes a rewound slot generation before rejecting stale completion") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 2) {
          driveEnqueue(dut, 0, BigInt("80000000", 16) + pair * 8,
            initiallyComplete = false)
          driveEnqueue(dut, 1, BigInt("80000004", 16) + pair * 8,
            initiallyComplete = false)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.poke(0)
        dut.clock.step()
        dut.io.rollback.valid.poke(false)
        dut.io.rollbackUndo.ready.poke(true)
        dut.clock.step()
        dut.io.rollbackDone.expect(true)
        dut.clock.step()
        dut.io.rollbackUndo.ready.poke(false)
        dut.io.count.expect(1)

        driveEnqueue(dut, 0, BigInt("80001000", 16), initiallyComplete = false)
        driveEnqueue(dut, 1, BigInt("80001004", 16), initiallyComplete = false)
        dut.io.enqueueTag(0).bits.expect(33)
        dut.io.enqueueTag(1).bits.expect(34)
        dut.clock.step()
        driveEnqueue(dut, 0, BigInt("80001008", 16), initiallyComplete = false)
        dut.io.enqueue(1).valid.poke(false)
        dut.io.enqueueTag(0).bits.expect(35)
        dut.clock.step()
        dut.io.enqueue(0).valid.poke(false)

        dut.io.completion(0).valid.poke(true)
        dut.io.completion(0).robTag.poke(1)
        dut.io.completionAccepted(0).expect(false)
        dut.clock.step()
        dut.io.completion(0).robTag.poke(33)
        dut.io.completionAccepted(0).expect(true)
      }
    }

    it("rolls back across the physical index wrap") {
      simulate(new ReorderBuffer(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        for (pair <- 0 until 12) {
          driveEnqueue(dut, 0, BigInt("80000000", 16) + pair * 8,
            initiallyComplete = true)
          driveEnqueue(dut, 1, BigInt("80000004", 16) + pair * 8,
            initiallyComplete = true)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.commit.foreach(_.ready.poke(true))
        dut.clock.step(10)
        dut.io.commit.foreach(_.ready.poke(false))
        dut.io.count.expect(4)
        dut.io.headTag.expect(20)

        for (pair <- 0 until 2) {
          driveEnqueue(dut, 0, BigInt("80001000", 16) + pair * 8,
            initiallyComplete = false)
          driveEnqueue(dut, 1, BigInt("80001004", 16) + pair * 8,
            initiallyComplete = false)
          dut.io.enqueueTag(0).bits.expect(32 + pair * 2)
          dut.io.enqueueTag(1).bits.expect(33 + pair * 2)
          dut.clock.step()
        }
        dut.io.enqueue.foreach(_.valid.poke(false))
        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.poke(23)
        dut.clock.step()
        dut.io.rollback.valid.poke(false)
        dut.io.rollbackUndo.ready.poke(true)
        dut.io.rollbackUndo.bits.records(0).robTag.expect(35)
        dut.io.rollbackUndo.bits.records(1).robTag.expect(34)
        dut.clock.step()
        dut.io.rollbackUndo.bits.records(0).robTag.expect(33)
        dut.io.rollbackUndo.bits.records(1).robTag.expect(32)
        dut.io.rollbackDone.expect(true)
        dut.clock.step()
        dut.io.count.expect(4)
        dut.io.rollbackActive.expect(false)
      }
    }
  }
}
