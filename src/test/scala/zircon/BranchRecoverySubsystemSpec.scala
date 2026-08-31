package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{BranchProvider, BranchRecoverySubsystem}

class BranchRecoverySubsystemSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: BranchRecoverySubsystem): Unit = {
    dut.io.robHeadTag.poke(0)
    dut.io.allocate.valid.poke(false)
    dut.io.allocate.bits.robTag.poke(0)
    val metadata = dut.io.allocate.bits.metadata
    metadata.pc.poke(0)
    metadata.historyBefore.poke(0)
    metadata.predictedTaken.poke(false)
    metadata.predictedTarget.poke(0)
    metadata.conditional.poke(false)
    metadata.call.poke(false)
    metadata.ret.poke(false)
    metadata.provider.poke(BranchProvider.Base)
    metadata.alternateProvider.poke(BranchProvider.Base)
    metadata.providerPrediction.poke(false)
    metadata.alternatePrediction.poke(false)
    metadata.btbWay.poke(0)
    metadata.rasPointerBefore.poke(0)
    metadata.rasCountBefore.poke(0)

    dut.io.resolve.valid.poke(false)
    dut.io.resolve.bits.reference.index.poke(0)
    dut.io.resolve.bits.reference.robTag.poke(0)
    dut.io.resolve.bits.actualTaken.poke(false)
    dut.io.resolve.bits.actualTarget.poke(0)
    dut.io.commit.valid.poke(false)
    dut.io.commit.bits.index.poke(0)
    dut.io.commit.bits.robTag.poke(0)
    dut.io.robRollback.ready.poke(false)
    dut.io.robRollbackDone.poke(false)
    dut.io.globalFlush.poke(false)
  }

  private def allocate(
      dut: BranchRecoverySubsystem,
      robTag: Int,
      pc: BigInt,
      predictedTaken: Boolean,
      predictedTarget: BigInt,
      expectedIndex: Int,
      history: BigInt = 0
  ): Unit = {
    dut.io.allocate.valid.poke(true)
    dut.io.allocate.bits.robTag.poke(robTag)
    val metadata = dut.io.allocate.bits.metadata
    metadata.pc.poke(pc)
    metadata.historyBefore.poke(history)
    metadata.predictedTaken.poke(predictedTaken)
    metadata.predictedTarget.poke(predictedTarget)
    metadata.conditional.poke(true)
    metadata.providerPrediction.poke(predictedTaken)
    metadata.alternatePrediction.poke(predictedTaken)
    dut.io.allocate.ready.expect(true)
    dut.io.allocatedIndex.valid.expect(true)
    dut.io.allocatedIndex.bits.expect(expectedIndex)
    dut.clock.step()
    dut.io.allocate.valid.poke(false)
  }

  private def driveResolve(
      dut: BranchRecoverySubsystem,
      index: Int,
      robTag: Int,
      actualTaken: Boolean,
      actualTarget: BigInt
  ): Unit = {
    dut.io.resolve.valid.poke(true)
    dut.io.resolve.bits.reference.index.poke(index)
    dut.io.resolve.bits.reference.robTag.poke(robTag)
    dut.io.resolve.bits.actualTaken.poke(actualTaken)
    dut.io.resolve.bits.actualTarget.poke(actualTarget)
  }

  describe("BranchRecoverySubsystem") {
    it("resolves and trains a correct branch without launching recovery") {
      simulate(new BranchRecoverySubsystem) { dut =>
        clearInputs(dut)
        val target = BigInt("80000100", 16)
        allocate(dut, robTag = 0, pc = BigInt("80000000", 16),
          predictedTaken = true, predictedTarget = target, expectedIndex = 0)
        driveResolve(dut, index = 0, robTag = 0,
          actualTaken = true, actualTarget = target)
        dut.io.resolve.ready.expect(true)
        dut.io.squash.valid.expect(false)
        dut.io.frontendRecovery.valid.expect(false)
        dut.io.robRollback.valid.expect(false)
        dut.clock.step()

        dut.io.resolve.valid.poke(false)
        dut.io.commit.valid.poke(true)
        dut.io.commit.bits.index.poke(0)
        dut.io.commit.bits.robTag.poke(0)
        dut.io.commit.ready.expect(true)
        dut.io.training.valid.expect(true)
        dut.io.training.bits.actualTaken.expect(true)
        dut.io.training.bits.actualTarget.expect(target)
        dut.clock.step()
        dut.io.commit.valid.poke(false)
        dut.io.count.expect(0)
      }
    }

    it("atomically broadcasts a mispredict and retains rollback until done") {
      simulate(new BranchRecoverySubsystem) { dut =>
        clearInputs(dut)
        allocate(dut, robTag = 4, pc = BigInt("80000010", 16),
          predictedTaken = false,
          predictedTarget = BigInt("80000014", 16), expectedIndex = 0,
          history = BigInt("12", 16))
        allocate(dut, robTag = 5, pc = BigInt("80000014", 16),
          predictedTaken = false,
          predictedTarget = BigInt("80000018", 16), expectedIndex = 1)
        dut.io.count.expect(2)

        val target = BigInt("80000200", 16)
        driveResolve(dut, index = 0, robTag = 4,
          actualTaken = true, actualTarget = target)
        dut.io.resolve.ready.expect(true)
        dut.io.squash.valid.expect(true)
        dut.io.squash.bits.expect(4)
        dut.io.frontendRecovery.valid.expect(true)
        dut.io.frontendRecovery.bits.redirectTarget.expect(target)
        dut.io.robRollback.valid.expect(true)
        dut.io.robRollback.bits.expect(4)
        dut.io.dispatchBlocked.expect(true)
        dut.clock.step()

        dut.io.resolve.valid.poke(false)
        dut.io.count.expect(1)
        dut.io.squash.valid.expect(false)
        dut.io.robRollback.valid.expect(true)
        dut.io.recoveryActive.expect(true)
        dut.io.robRollback.ready.poke(true)
        dut.clock.step()

        dut.io.robRollback.ready.poke(false)
        dut.io.robRollback.valid.expect(false)
        dut.io.recoveryActive.expect(true)
        dut.io.robRollbackDone.poke(true)
        dut.clock.step()
        dut.io.robRollbackDone.poke(false)
        dut.io.recoveryActive.expect(false)
        dut.io.dispatchBlocked.expect(false)

        dut.io.globalFlush.poke(true)
        dut.clock.step()
        dut.io.globalFlush.poke(false)
        dut.io.count.expect(0)
      }
    }
  }
}
