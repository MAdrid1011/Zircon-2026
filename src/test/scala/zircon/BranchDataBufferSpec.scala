package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{BranchDataBuffer, BranchProvider}

class BranchDataBufferSpec extends AnyFunSpec with ChiselSim {
  private val HistoryMask: BigInt = (BigInt(1) << 64) - 1

  private def clearInputs(dut: BranchDataBuffer): Unit = {
    dut.io.robHeadTag.poke(0)
    dut.io.flushAll.poke(false)
    dut.io.resolution.ready.poke(true)

    dut.io.allocate.valid.poke(false)
    dut.io.allocate.bits.robTag.poke(0)
    dut.io.allocate.bits.metadata.pc.poke(0)
    dut.io.allocate.bits.metadata.historyBefore.poke(0)
    dut.io.allocate.bits.metadata.predictedTaken.poke(false)
    dut.io.allocate.bits.metadata.predictedTarget.poke(0)
    dut.io.allocate.bits.metadata.conditional.poke(false)
    dut.io.allocate.bits.metadata.call.poke(false)
    dut.io.allocate.bits.metadata.ret.poke(false)
    dut.io.allocate.bits.metadata.provider.poke(BranchProvider.Base)
    dut.io.allocate.bits.metadata.alternateProvider.poke(BranchProvider.Base)
    dut.io.allocate.bits.metadata.providerPrediction.poke(false)
    dut.io.allocate.bits.metadata.alternatePrediction.poke(false)
    dut.io.allocate.bits.metadata.btbWay.poke(0)
    dut.io.allocate.bits.metadata.rasPointerBefore.poke(0)
    dut.io.allocate.bits.metadata.rasCountBefore.poke(0)

    dut.io.resolve.valid.poke(false)
    dut.io.resolve.bits.reference.index.poke(0)
    dut.io.resolve.bits.reference.robTag.poke(0)
    dut.io.resolve.bits.actualTaken.poke(false)
    dut.io.resolve.bits.actualTarget.poke(0)

    dut.io.commit.valid.poke(false)
    dut.io.commit.bits.index.poke(0)
    dut.io.commit.bits.robTag.poke(0)
  }

  private def driveAllocation(
      dut: BranchDataBuffer,
      robTag: Int,
      pc: BigInt,
      history: BigInt,
      predictedTaken: Boolean,
      predictedTarget: BigInt,
      conditional: Boolean = true,
      call: Boolean = false,
      ret: Boolean = false,
      rasPointer: Int = 0,
      rasCount: Int = 0
  ): Unit = {
    dut.io.allocate.valid.poke(true)
    dut.io.allocate.bits.robTag.poke(robTag)
    dut.io.allocate.bits.metadata.pc.poke(pc)
    dut.io.allocate.bits.metadata.historyBefore.poke(history)
    dut.io.allocate.bits.metadata.predictedTaken.poke(predictedTaken)
    dut.io.allocate.bits.metadata.predictedTarget.poke(predictedTarget)
    dut.io.allocate.bits.metadata.conditional.poke(conditional)
    dut.io.allocate.bits.metadata.call.poke(call)
    dut.io.allocate.bits.metadata.ret.poke(ret)
    dut.io.allocate.bits.metadata.provider.poke(BranchProvider.Base)
    dut.io.allocate.bits.metadata.alternateProvider.poke(BranchProvider.Base)
    dut.io.allocate.bits.metadata.providerPrediction.poke(predictedTaken)
    dut.io.allocate.bits.metadata.alternatePrediction.poke(predictedTaken)
    dut.io.allocate.bits.metadata.btbWay.poke(0)
    dut.io.allocate.bits.metadata.rasPointerBefore.poke(rasPointer)
    dut.io.allocate.bits.metadata.rasCountBefore.poke(rasCount)
  }

  private def allocate(
      dut: BranchDataBuffer,
      robTag: Int,
      pc: BigInt,
      history: BigInt = 0,
      predictedTaken: Boolean = false,
      predictedTarget: BigInt = 0,
      conditional: Boolean = true,
      expectedIndex: Int,
      call: Boolean = false,
      ret: Boolean = false,
      rasPointer: Int = 0,
      rasCount: Int = 0
  ): Unit = {
    driveAllocation(dut, robTag, pc, history, predictedTaken, predictedTarget,
      conditional, call, ret, rasPointer, rasCount)
    dut.io.allocate.ready.expect(true)
    dut.io.allocatedIndex.valid.expect(true)
    dut.io.allocatedIndex.bits.expect(expectedIndex)
    dut.clock.step()
    dut.io.allocate.valid.poke(false)
  }

  private def resolve(
      dut: BranchDataBuffer,
      index: Int,
      robTag: Int,
      actualTaken: Boolean,
      actualTarget: BigInt,
      expectedMispredict: Boolean,
      expectedHistory: BigInt,
      expectedRasPointer: Int = 0,
      expectedRasCount: Int = 0,
      expectedRedirectTarget: Option[BigInt] = None
  ): Unit = {
    dut.io.resolve.valid.poke(true)
    dut.io.resolve.bits.reference.index.poke(index)
    dut.io.resolve.bits.reference.robTag.poke(robTag)
    dut.io.resolve.bits.actualTaken.poke(actualTaken)
    dut.io.resolve.bits.actualTarget.poke(actualTarget)
    dut.io.resolve.ready.expect(true)
    dut.io.resolution.valid.expect(true)
    dut.io.resolution.bits.mispredict.expect(expectedMispredict)
    dut.io.resolution.bits.recoveryHistory.expect(expectedHistory & HistoryMask)
    dut.io.resolution.bits.recoveryRasPointer.expect(expectedRasPointer)
    dut.io.resolution.bits.recoveryRasCount.expect(expectedRasCount)
    expectedRedirectTarget.foreach(
      dut.io.resolution.bits.redirectTarget.expect(_))
    dut.clock.step()
    dut.io.resolve.valid.poke(false)
  }

  describe("BranchDataBuffer") {
    it("recycles a committing entry while returning its pre-overwrite training data") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        val history = BigInt("123456789abcdef0", 16)
        allocate(dut, 0, BigInt("80000000", 16), history,
          predictedTaken = true, predictedTarget = BigInt("80000100", 16),
          expectedIndex = 0)
        resolve(dut, 0, 0, actualTaken = true,
          actualTarget = BigInt("80000100", 16), expectedMispredict = false,
          expectedHistory = ((history << 1) | 1) & HistoryMask,
          expectedRedirectTarget = Some(BigInt("80000100", 16)))

        dut.io.commit.valid.poke(true)
        dut.io.commit.bits.index.poke(0)
        dut.io.commit.bits.robTag.poke(0)
        driveAllocation(dut, 1, BigInt("80000004", 16), 0,
          predictedTaken = false, predictedTarget = BigInt("80000008", 16))
        dut.io.commit.ready.expect(true)
        dut.io.training.valid.expect(true)
        dut.io.training.bits.metadata.pc.expect(BigInt("80000000", 16))
        dut.io.training.bits.actualTaken.expect(true)
        dut.io.allocate.ready.expect(true)
        dut.io.allocatedIndex.bits.expect(0)
        dut.clock.step()

        dut.io.commit.valid.poke(false)
        dut.io.allocate.valid.poke(false)
        dut.io.count.expect(1)
        resolve(dut, 0, 1, actualTaken = false,
          actualTarget = BigInt("80000008", 16), expectedMispredict = false,
          expectedHistory = 0,
          expectedRedirectTarget = Some(BigInt("80000008", 16)))
      }
    }

    it("restores conditional history and removes only younger branches across ROB wrap") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        dut.io.robHeadTag.poke(20)
        allocate(dut, 22, BigInt("80000058", 16), expectedIndex = 0)
        val history = BigInt("8000000000000001", 16)
        allocate(dut, 23, BigInt("8000005c", 16), history,
          predictedTaken = false, predictedTarget = BigInt("80000060", 16),
          expectedIndex = 1)
        allocate(dut, 32, BigInt("80000060", 16), expectedIndex = 2)
        dut.io.count.expect(3)

        resolve(dut, 1, 23, actualTaken = true,
          actualTarget = BigInt("80000100", 16), expectedMispredict = true,
          expectedHistory = ((history << 1) | 1) & HistoryMask,
          expectedRedirectTarget = Some(BigInt("80000100", 16)))
        dut.io.count.expect(2)

        // The cleared younger slot is immediately reusable by the correct path.
        allocate(dut, 24, BigInt("80000100", 16), expectedIndex = 2)
        dut.io.count.expect(3)
      }
    }

    it("distinguishes taken target mismatch from irrelevant not-taken target data") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        allocate(dut, 0, BigInt("80000000", 16),
          predictedTaken = true, predictedTarget = BigInt("80000100", 16),
          expectedIndex = 0)
        resolve(dut, 0, 0, actualTaken = true,
          actualTarget = BigInt("80000104", 16), expectedMispredict = true,
          expectedHistory = 1)

        dut.io.commit.valid.poke(true)
        dut.io.commit.bits.index.poke(0)
        dut.io.commit.bits.robTag.poke(0)
        dut.clock.step()
        dut.io.commit.valid.poke(false)

        allocate(dut, 1, BigInt("80000004", 16),
          predictedTaken = false, predictedTarget = BigInt("80000100", 16),
          expectedIndex = 0)
        resolve(dut, 0, 1, actualTaken = false,
          actualTarget = BigInt("deadbeef", 16), expectedMispredict = false,
          expectedHistory = 0,
          expectedRedirectTarget = Some(BigInt("80000008", 16)))
      }
    }

    it("recovers bounded RAS pointer and occupancy across overflow and underflow") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        allocate(dut, 0, BigInt("80000000", 16),
          predictedTaken = true, predictedTarget = BigInt("80000100", 16),
          conditional = false, expectedIndex = 0, call = true,
          rasPointer = 7, rasCount = 8)
        resolve(dut, 0, 0, actualTaken = true,
          actualTarget = BigInt("80000104", 16), expectedMispredict = true,
          expectedHistory = 0, expectedRasPointer = 0, expectedRasCount = 8)

        dut.io.commit.valid.poke(true)
        dut.io.commit.bits.index.poke(0)
        dut.io.commit.bits.robTag.poke(0)
        dut.clock.step()
        dut.io.commit.valid.poke(false)

        allocate(dut, 1, BigInt("80000004", 16),
          predictedTaken = true, predictedTarget = BigInt("80000200", 16),
          conditional = false, expectedIndex = 0, ret = true,
          rasPointer = 0, rasCount = 0)
        resolve(dut, 0, 1, actualTaken = true,
          actualTarget = BigInt("80000204", 16), expectedMispredict = true,
          expectedHistory = 0, expectedRasPointer = 0, expectedRasCount = 0)
      }
    }

    it("recovers a coroutine RAS hint as pop-then-push") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        allocate(dut, 0, BigInt("80000020", 16),
          predictedTaken = true, predictedTarget = BigInt("80000100", 16),
          conditional = false, expectedIndex = 0, call = true, ret = true,
          rasPointer = 5, rasCount = 3)

        dut.io.resolve.valid.poke(true)
        dut.io.resolve.bits.reference.index.poke(0)
        dut.io.resolve.bits.reference.robTag.poke(0)
        dut.io.resolve.bits.actualTaken.poke(true)
        dut.io.resolve.bits.actualTarget.poke(BigInt("80000104", 16))
        dut.io.resolve.ready.expect(true)
        dut.io.resolution.valid.expect(true)
        dut.io.resolution.bits.recoveryRasPointer.expect(5)
        dut.io.resolution.bits.recoveryRasCount.expect(3)
        dut.io.resolution.bits.rasPointerBefore.expect(5)
        dut.io.resolution.bits.rasCountBefore.expect(3)
        dut.io.resolution.bits.rasPush.expect(true)
        dut.io.resolution.bits.rasPop.expect(true)
        dut.io.resolution.bits.rasReturnAddress.expect(BigInt("80000024", 16))
      }
    }

    it("prioritizes commit read over resolve while still admitting one allocation write") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        allocate(dut, 0, BigInt("80000000", 16), expectedIndex = 0)
        resolve(dut, 0, 0, actualTaken = false,
          actualTarget = BigInt("80000004", 16), expectedMispredict = false,
          expectedHistory = 0)
        allocate(dut, 1, BigInt("80000004", 16), expectedIndex = 1)

        dut.io.commit.valid.poke(true)
        dut.io.commit.bits.index.poke(0)
        dut.io.commit.bits.robTag.poke(0)
        dut.io.resolve.valid.poke(true)
        dut.io.resolve.bits.reference.index.poke(1)
        dut.io.resolve.bits.reference.robTag.poke(1)
        dut.io.resolve.bits.actualTaken.poke(false)
        dut.io.resolve.bits.actualTarget.poke(BigInt("80000008", 16))
        driveAllocation(dut, 2, BigInt("80000008", 16), 0,
          predictedTaken = false, predictedTarget = BigInt("8000000c", 16))

        dut.io.commit.ready.expect(true)
        dut.io.resolve.ready.expect(false)
        dut.io.resolution.valid.expect(false)
        dut.io.allocate.ready.expect(true)
        dut.io.allocatedIndex.bits.expect(0)
        dut.clock.step()

        dut.io.commit.valid.poke(false)
        dut.io.allocate.valid.poke(false)
        dut.io.resolve.ready.expect(true)
        dut.io.resolution.valid.expect(true)
      }
    }

    it("holds an unresolved result stable under recovery-controller backpressure") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        val pc = BigInt("80000040", 16)
        val target = BigInt("80000100", 16)
        allocate(dut, 4, pc, predictedTaken = false,
          predictedTarget = pc + 4, expectedIndex = 0)

        dut.io.resolution.ready.poke(false)
        dut.io.resolve.valid.poke(true)
        dut.io.resolve.bits.reference.index.poke(0)
        dut.io.resolve.bits.reference.robTag.poke(4)
        dut.io.resolve.bits.actualTaken.poke(true)
        dut.io.resolve.bits.actualTarget.poke(target)
        dut.io.resolve.ready.expect(false)
        dut.io.resolution.valid.expect(true)
        dut.io.resolution.bits.reference.robTag.expect(4)
        dut.io.resolution.bits.mispredict.expect(true)
        dut.io.resolution.bits.redirectTarget.expect(target)

        // A stalled resolve is read-only, so the independent write port remains usable.
        driveAllocation(dut, 5, BigInt("80000044", 16), 0,
          predictedTaken = false, predictedTarget = BigInt("80000048", 16))
        dut.io.allocate.ready.expect(true)
        dut.clock.step()
        dut.io.allocate.valid.poke(false)
        dut.io.count.expect(2)
        dut.io.resolution.bits.reference.robTag.expect(4)
        dut.io.resolution.bits.redirectTarget.expect(target)

        dut.io.resolution.ready.poke(true)
        dut.io.resolve.ready.expect(true)
        dut.clock.step()
        dut.io.resolve.valid.poke(false)
        dut.io.resolution.valid.expect(false)
      }
    }

    it("clears every outstanding checkpoint on a global flush") {
      simulate(new BranchDataBuffer) { dut =>
        clearInputs(dut)
        allocate(dut, 0, BigInt("80000000", 16), expectedIndex = 0)
        allocate(dut, 1, BigInt("80000004", 16), expectedIndex = 1)
        dut.io.count.expect(2)
        dut.io.flushAll.poke(true)
        dut.io.allocate.ready.expect(false)
        dut.io.resolve.ready.expect(false)
        dut.io.commit.ready.expect(false)
        dut.clock.step()
        dut.io.flushAll.poke(false)
        dut.io.count.expect(0)
        allocate(dut, 32, BigInt("80001000", 16), expectedIndex = 0)
      }
    }
  }
}
