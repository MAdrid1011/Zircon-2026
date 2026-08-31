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

        driveEnqueue(dut, 0, BigInt("80000100", 16), initiallyComplete = false)
        driveEnqueue(dut, 1, BigInt("80000104", 16), initiallyComplete = false)
        dut.io.commit.foreach(_.ready.poke(true))
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
  }
}
