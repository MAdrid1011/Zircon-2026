package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{BranchProvider, IntegerDispatchRecoveryBackend}

class IntegerDispatchRecoveryBackendSpec extends AnyFunSpec with ChiselSim {
  private val AddiX5X0Five = BigInt("00500293", 16)
  private val AddiX6X5Three = BigInt("00328313", 16)
  private val AddiX5X0One = BigInt("00100293", 16)
  private val BeqX0X0PlusEight = BigInt("00000463", 16)

  private def clearInputs(dut: IntegerDispatchRecoveryBackend): Unit = {
    for (lane <- 0 until 2) {
      val input = dut.io.input(lane)
      input.valid.poke(false)
      input.bits.instruction.poke(BigInt("00000013", 16))
      input.bits.privilege.poke(3)
      input.bits.fault.valid.poke(false)
      input.bits.fault.cause.poke(0)
      input.bits.fault.tval.poke(0)
      val prediction = input.bits.prediction
      prediction.pc.poke(BigInt("80000000", 16) + lane * 4)
      prediction.historyBefore.poke(0)
      prediction.predictedTaken.poke(false)
      prediction.predictedTarget.poke(
        BigInt("80000004", 16) + lane * 4)
      prediction.conditional.poke(false)
      prediction.call.poke(false)
      prediction.ret.poke(false)
      prediction.provider.poke(BranchProvider.Base)
      prediction.alternateProvider.poke(BranchProvider.Base)
      prediction.providerPrediction.poke(false)
      prediction.alternatePrediction.poke(false)
      prediction.btbWay.poke(0)
      prediction.rasPointerBefore.poke(0)
      prediction.rasCountBefore.poke(0)

      dut.io.longEnqueue(lane).ready.poke(true)
      dut.io.memEnqueue(lane).ready.poke(true)
      dut.io.commit(lane).ready.poke(false)
      dut.io.renameCommit(lane).valid.poke(false)
      dut.io.renameCommit(lane).architectural.poke(0)
      dut.io.renameCommit(lane).oldPhysical.poke(0)
      dut.io.renameCommit(lane).newPhysical.poke(0)
    }

    dut.io.longCapacity.poke(2)
    dut.io.memCapacity.poke(2)
    for (endpoint <- 0 until 3) {
      dut.io.otherCompletion(endpoint).valid.poke(false)
      dut.io.otherCompletion(endpoint).bits.robTag.poke(endpoint)
      dut.io.otherCompletion(endpoint).bits.writesInteger.poke(false)
      dut.io.otherCompletion(endpoint).bits.destinationPhysical.poke(0)
      dut.io.otherCompletion(endpoint).bits.data.poke(0)
      dut.io.otherFault(endpoint).valid.poke(false)
      dut.io.otherFault(endpoint).record.robTag.poke(endpoint)
      dut.io.otherFault(endpoint).record.cause.poke(0)
      dut.io.otherFault(endpoint).record.trapValue.poke(0)
    }
    dut.io.branchCommit.valid.poke(false)
    dut.io.branchCommit.bits.index.poke(0)
    dut.io.branchCommit.bits.robTag.poke(0)
    dut.io.firstFaultClear.poke(false)
    dut.io.globalFlush.poke(false)
    dut.io.csrAccessData.poke(0)
    dut.io.csrAccessLegal.poke(false)
    dut.io.systemSerializingReady.poke(true)
    dut.io.auxReadPhysical.foreach(_.poke(0))
    dut.io.memoryExecutionRead.foreach { read =>
      read.valid.poke(false)
      read.bits.poke(0)
    }
  }

  private def driveInstruction(
      dut: IntegerDispatchRecoveryBackend,
      lane: Int,
      instruction: BigInt,
      pc: BigInt,
      conditional: Boolean = false
  ): Unit = {
    val input = dut.io.input(lane)
    input.valid.poke(true)
    input.bits.instruction.poke(instruction)
    input.bits.prediction.pc.poke(pc)
    input.bits.prediction.predictedTaken.poke(false)
    input.bits.prediction.predictedTarget.poke(pc + 4)
    input.bits.prediction.conditional.poke(conditional)
  }

  private def clearDecodedInput(dut: IntegerDispatchRecoveryBackend): Unit =
    dut.io.input.foreach(_.valid.poke(false))

  describe("IntegerDispatchRecoveryBackend") {
    it("executes and commits a dependent dual-dispatch bundle") {
      simulate(new IntegerDispatchRecoveryBackend) { dut =>
        clearInputs(dut)
        dut.io.auxReadPhysical(0).poke(32)
        dut.io.auxReadPhysical(1).poke(33)
        driveInstruction(dut, 0, AddiX5X0Five,
          BigInt("80000000", 16))
        driveInstruction(dut, 1, AddiX6X5Three,
          BigInt("80000004", 16))

        dut.io.acceptedCount.expect(2)
        dut.io.input.foreach(_.ready.expect(true))
        dut.clock.step()
        clearDecodedInput(dut)
        dut.io.robCount.expect(2)
        dut.io.intCount.expect(2)
        dut.io.speculativeMap(5).expect(32)
        dut.io.speculativeMap(6).expect(33)

        dut.clock.step(3)
        dut.io.commit(0).valid.expect(true)
        dut.io.commit(0).bits.robTag.expect(0)
        dut.io.commit(1).valid.expect(true)
        dut.io.commit(1).bits.robTag.expect(1)
        dut.io.auxReadData(0).expect(5)
        dut.io.auxReadData(1).expect(8)

        dut.io.commit.foreach(_.ready.poke(true))
        dut.io.renameCommit(0).valid.poke(true)
        dut.io.renameCommit(0).architectural.poke(5)
        dut.io.renameCommit(0).oldPhysical.poke(5)
        dut.io.renameCommit(0).newPhysical.poke(32)
        dut.io.renameCommit(1).valid.poke(true)
        dut.io.renameCommit(1).architectural.poke(6)
        dut.io.renameCommit(1).oldPhysical.poke(6)
        dut.io.renameCommit(1).newPhysical.poke(33)
        dut.clock.step()

        dut.io.renameCommit.foreach(_.valid.poke(false))
        dut.io.committedMap(5).expect(32)
        dut.io.committedMap(6).expect(33)
        dut.io.robCount.expect(0)
      }
    }

    it("recovers a branch and removes a younger dispatched integer") {
      simulate(new IntegerDispatchRecoveryBackend) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, BeqX0X0PlusEight,
          BigInt("80000000", 16), conditional = true)
        driveInstruction(dut, 1, AddiX5X0One,
          BigInt("80000004", 16))
        dut.io.acceptedCount.expect(2)
        dut.clock.step()

        clearDecodedInput(dut)
        dut.io.robCount.expect(2)
        dut.io.branchDataCount.expect(1)
        dut.io.speculativeMap(5).expect(32)
        dut.clock.step()

        dut.io.frontendRecovery.valid.expect(true)
        dut.io.frontendRecovery.bits.reference.robTag.expect(0)
        dut.io.frontendRecovery.bits.redirectTarget.expect(
          BigInt("80000008", 16))
        dut.io.squash.valid.expect(true)
        dut.io.squash.bits.expect(0)
        dut.io.dispatchBlocked.expect(true)
        driveInstruction(dut, 0, AddiX5X0Five,
          BigInt("80000100", 16))
        dut.io.input(0).ready.expect(false)
        dut.clock.step()

        dut.io.frontendRecovery.valid.expect(false)
        dut.io.dispatchBlocked.expect(true)
        dut.io.robCount.expect(2)
        clearDecodedInput(dut)
        dut.clock.step()

        dut.io.dispatchBlocked.expect(false)
        dut.io.robCount.expect(1)
        dut.io.intCount.expect(0)
        dut.io.speculativeMap(5).expect(5)
        dut.io.renameFreeCount.expect(24)
        dut.clock.step()

        dut.io.commit(0).valid.expect(true)
        dut.io.commit(0).bits.robTag.expect(0)
        dut.io.commit(0).bits.entry.hasBranchData.expect(true)
        dut.io.commit(0).bits.entry.branchDataIndex.expect(0)
        dut.io.commit(0).ready.poke(true)
        dut.io.branchCommit.valid.poke(true)
        dut.io.branchCommit.bits.index.poke(0)
        dut.io.branchCommit.bits.robTag.poke(0)
        dut.io.branchCommit.ready.expect(true)
        dut.io.branchTraining.valid.expect(true)
        dut.io.branchTraining.bits.actualTaken.expect(true)
        dut.clock.step()

        dut.io.branchCommit.valid.poke(false)
        dut.io.branchDataCount.expect(0)
        dut.io.robCount.expect(0)
      }
    }

    it("retains the oldest dispatch fault and clears speculative state") {
      simulate(new IntegerDispatchRecoveryBackend) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, BigInt("ffffffff", 16),
          BigInt("80000000", 16))
        driveInstruction(dut, 1, AddiX5X0One,
          BigInt("80000004", 16))
        dut.io.acceptedCount.expect(2)
        dut.clock.step()

        clearDecodedInput(dut)
        dut.io.firstFault.valid.expect(true)
        dut.io.firstFault.bits.robTag.expect(0)
        dut.io.firstFault.bits.cause.expect(2)
        dut.io.firstFault.bits.trapValue.expect(BigInt("ffffffff", 16))
        dut.io.speculativeMap(5).expect(32)

        dut.io.globalFlush.poke(true)
        dut.clock.step()
        dut.io.globalFlush.poke(false)
        dut.io.firstFault.valid.expect(false)
        dut.io.robCount.expect(0)
        dut.io.intCount.expect(0)
        dut.io.speculativeMap(5).expect(5)
        dut.io.renameFreeCount.expect(24)
      }
    }
  }
}
