package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.BranchRecoveryController

class BranchRecoveryControllerSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: BranchRecoveryController): Unit = {
    dut.io.resolution.valid.poke(false)
    dut.io.resolution.bits.reference.index.poke(0)
    dut.io.resolution.bits.reference.robTag.poke(0)
    dut.io.resolution.bits.mispredict.poke(false)
    dut.io.resolution.bits.recoveryHistory.poke(0)
    dut.io.resolution.bits.recoveryRasPointer.poke(0)
    dut.io.resolution.bits.recoveryRasCount.poke(0)
    dut.io.resolution.bits.rasPointerBefore.poke(0)
    dut.io.resolution.bits.rasCountBefore.poke(0)
    dut.io.resolution.bits.rasPush.poke(false)
    dut.io.resolution.bits.rasPop.poke(false)
    dut.io.resolution.bits.rasReturnAddress.poke(0)
    dut.io.resolution.bits.actualTaken.poke(false)
    dut.io.resolution.bits.actualTarget.poke(0)
    dut.io.resolution.bits.redirectTarget.poke(0)
    dut.io.robRollback.ready.poke(false)
    dut.io.robRollbackDone.poke(false)
    dut.io.globalFlush.poke(false)
  }

  private def driveResolution(
      dut: BranchRecoveryController,
      robTag: Int,
      mispredict: Boolean,
      target: BigInt = BigInt("80000100", 16)
  ): Unit = {
    dut.io.resolution.valid.poke(true)
    dut.io.resolution.bits.reference.index.poke(1)
    dut.io.resolution.bits.reference.robTag.poke(robTag)
    dut.io.resolution.bits.mispredict.poke(mispredict)
    dut.io.resolution.bits.recoveryHistory.poke(BigInt("1234", 16))
    dut.io.resolution.bits.recoveryRasPointer.poke(3)
    dut.io.resolution.bits.recoveryRasCount.poke(4)
    dut.io.resolution.bits.rasPointerBefore.poke(2)
    dut.io.resolution.bits.rasCountBefore.poke(3)
    dut.io.resolution.bits.rasPush.poke(true)
    dut.io.resolution.bits.rasPop.poke(false)
    dut.io.resolution.bits.rasReturnAddress.poke(BigInt("80000044", 16))
    dut.io.resolution.bits.actualTaken.poke(true)
    dut.io.resolution.bits.actualTarget.poke(target)
    dut.io.resolution.bits.redirectTarget.poke(target)
  }

  describe("BranchRecoveryController") {
    it("accepts correct predictions without starting recovery") {
      simulate(new BranchRecoveryController) { dut =>
        clearInputs(dut)
        driveResolution(dut, robTag = 4, mispredict = false)
        dut.io.resolution.ready.expect(true)
        dut.io.squash.valid.expect(false)
        dut.io.frontendRecovery.valid.expect(false)
        dut.io.robRollback.valid.expect(false)
        dut.io.dispatchBlocked.expect(false)
        dut.clock.step()
        dut.io.resolution.valid.poke(false)
        dut.io.recoveryActive.expect(false)
      }
    }

    it("broadcasts recovery once and retains a backpressured ROB request") {
      simulate(new BranchRecoveryController) { dut =>
        clearInputs(dut)
        driveResolution(dut, robTag = 7, mispredict = true)
        dut.io.resolution.ready.expect(true)
        dut.io.squash.valid.expect(true)
        dut.io.squash.bits.expect(7)
        dut.io.frontendRecovery.valid.expect(true)
        dut.io.frontendRecovery.bits.redirectTarget.expect(
          BigInt("80000100", 16))
        dut.io.robRollback.valid.expect(true)
        dut.io.robRollback.bits.expect(7)
        dut.io.dispatchBlocked.expect(true)
        dut.clock.step()

        dut.io.resolution.valid.poke(false)
        dut.io.squash.valid.expect(false)
        dut.io.frontendRecovery.valid.expect(false)
        dut.io.robRollback.valid.expect(true)
        dut.io.robRollback.bits.expect(7)
        dut.io.resolution.ready.expect(false)
        dut.clock.step(2)
        dut.io.robRollback.bits.expect(7)

        dut.io.robRollback.ready.poke(true)
        dut.clock.step()
        dut.io.robRollback.ready.poke(false)
        dut.io.robRollback.valid.expect(false)
        dut.io.recoveryActive.expect(true)
        dut.io.dispatchBlocked.expect(true)

        dut.io.robRollbackDone.poke(true)
        dut.clock.step()
        dut.io.robRollbackDone.poke(false)
        dut.io.recoveryActive.expect(false)
        dut.io.dispatchBlocked.expect(false)
        dut.io.resolution.ready.expect(true)
      }
    }

    it("handles a zero-younger rollback in the launch cycle") {
      simulate(new BranchRecoveryController) { dut =>
        clearInputs(dut)
        dut.io.robRollback.ready.poke(true)
        dut.io.robRollbackDone.poke(true)
        driveResolution(dut, robTag = 3, mispredict = true)
        dut.io.robRollback.valid.expect(true)
        dut.io.squash.valid.expect(true)
        dut.clock.step()
        dut.io.resolution.valid.poke(false)
        dut.io.robRollbackDone.poke(false)
        dut.io.recoveryActive.expect(false)
        dut.io.dispatchBlocked.expect(false)
      }
    }

    it("lets a global flush cancel a pending rollback") {
      simulate(new BranchRecoveryController) { dut =>
        clearInputs(dut)
        driveResolution(dut, robTag = 9, mispredict = true)
        dut.clock.step()
        dut.io.resolution.valid.poke(false)
        dut.io.robRollback.valid.expect(true)

        dut.io.globalFlush.poke(true)
        dut.io.robRollback.valid.expect(false)
        dut.clock.step()
        dut.io.globalFlush.poke(false)
        dut.io.recoveryActive.expect(false)
        dut.io.dispatchBlocked.expect(false)
      }
    }
  }
}
