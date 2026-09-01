package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{BranchProvider, CommitRedirectReason}
import zircon.frontend.M1Frontend
import zircon.memory.L2DemandClient

class M1FrontendSpec extends AnyFunSpec with ChiselSim {
  private val ResetVector = BigInt("80000000", 16)
  private val Nop = BigInt("00000013", 16)
  private val BeqX0X0Plus16 = BigInt("00000863", 16)
  private val JalX0Plus16 = BigInt("0100006f", 16)
  private val JalrX0X0 = BigInt("00000067", 16)

  private def clearInputs(dut: M1Frontend): Unit = {
    dut.io.enable.poke(false)
    dut.io.l2Request.ready.poke(false)
    dut.io.l2Response.valid.poke(false)
    dut.io.l2Response.bits.client.poke(L2DemandClient.Instruction)
    dut.io.l2Response.bits.clientMshr.poke(0)
    dut.io.l2Response.bits.accessFault.poke(false)
    for (word <- 0 until 8) dut.io.l2Response.bits.lineData(word).poke(0)
    dut.io.decode.foreach(_.ready.poke(false))

    dut.io.branchTraining.valid.poke(false)
    dut.io.branchTraining.bits.reference.index.poke(0)
    dut.io.branchTraining.bits.reference.robTag.poke(0)
    val training = dut.io.branchTraining.bits.metadata
    training.pc.poke(0)
    training.historyBefore.poke(0)
    training.predictedTaken.poke(false)
    training.predictedTarget.poke(0)
    training.conditional.poke(false)
    training.call.poke(false)
    training.ret.poke(false)
    training.provider.poke(BranchProvider.Base)
    training.alternateProvider.poke(BranchProvider.Base)
    training.providerPrediction.poke(false)
    training.alternatePrediction.poke(false)
    training.btbWay.poke(0)
    training.rasPointerBefore.poke(0)
    training.rasCountBefore.poke(0)
    dut.io.branchTraining.bits.actualTaken.poke(false)
    dut.io.branchTraining.bits.actualTarget.poke(0)

    dut.io.executeRecovery.valid.poke(false)
    val recovery = dut.io.executeRecovery.bits
    recovery.reference.index.poke(0)
    recovery.reference.robTag.poke(0)
    recovery.mispredict.poke(true)
    recovery.recoveryHistory.poke(0)
    recovery.recoveryRasPointer.poke(0)
    recovery.recoveryRasCount.poke(0)
    recovery.rasPointerBefore.poke(0)
    recovery.rasCountBefore.poke(0)
    recovery.rasPush.poke(false)
    recovery.rasPop.poke(false)
    recovery.rasReturnAddress.poke(0)
    recovery.actualTaken.poke(false)
    recovery.actualTarget.poke(0)
    recovery.redirectTarget.poke(0)

    dut.io.commitRedirect.valid.poke(false)
    dut.io.commitRedirect.bits.target.poke(0)
    dut.io.commitRedirect.bits.reason.poke(CommitRedirectReason.Exception)
  }

  private def finishPredictorScrub(dut: M1Frontend): Unit = {
    dut.io.predictorsReady.expect(false)
    dut.clock.step(128)
    dut.io.predictorsReady.expect(true)
  }

  private def launchRequest(dut: M1Frontend, base: BigInt, beats: Int = 4): Unit = {
    dut.io.currentPc.expect(base)
    dut.io.enable.poke(true)
    dut.clock.step()
    dut.clock.step()
    dut.io.l2Request.valid.expect(true)
    dut.io.l2Request.bits.client.expect(L2DemandClient.Instruction)
    dut.io.l2Request.bits.clientMshr.expect(0)
    dut.io.l2Request.bits.lineAddress.expect(base & ~BigInt(31))
  }

  private def acceptRequest(dut: M1Frontend): Unit = {
    dut.io.l2Request.ready.poke(true)
    dut.clock.step()
    dut.io.l2Request.ready.poke(false)
    dut.io.l2Response.ready.expect(true)
  }

  private def sendPacket(dut: M1Frontend, words: Seq[BigInt],
      expectPacket: Boolean = true): Unit = {
    dut.io.l2Response.valid.poke(true)
    dut.io.l2Response.bits.client.poke(L2DemandClient.Instruction)
    dut.io.l2Response.bits.clientMshr.poke(0)
    dut.io.l2Response.bits.accessFault.poke(false)
    for (index <- 0 until 8) {
      dut.io.l2Response.bits.lineData(index).poke(words.lift(index).getOrElse(Nop))
    }
    dut.io.l2Response.ready.expect(true)
    dut.clock.step()
    dut.io.l2Response.valid.poke(false)
    dut.io.fetchBusy.expect(expectPacket)
    if (expectPacket) dut.clock.step()
  }

  private def driveRecovery(dut: M1Frontend, target: BigInt): Unit = {
    dut.io.executeRecovery.valid.poke(true)
    dut.io.executeRecovery.bits.redirectTarget.poke(target)
    dut.clock.step()
    dut.io.executeRecovery.valid.poke(false)
  }

  private def trainTakenConditional(dut: M1Frontend, pc: BigInt,
      target: BigInt): Unit = {
    dut.io.branchTraining.valid.poke(true)
    dut.io.branchTraining.bits.metadata.pc.poke(pc)
    dut.io.branchTraining.bits.metadata.conditional.poke(true)
    dut.io.branchTraining.bits.actualTaken.poke(true)
    dut.io.branchTraining.bits.actualTarget.poke(target)
    dut.io.predictorsReady.expect(false)
    dut.clock.step()
    dut.io.branchTraining.valid.poke(false)
    dut.io.predictorsReady.expect(true)
  }

  describe("M1Frontend") {
    it("maps an AXI packet into ordered decode entries and uses the earliest control target") {
      simulate(new M1Frontend) { dut =>
        clearInputs(dut)
        finishPredictorScrub(dut)
        launchRequest(dut, ResetVector)
        acceptRequest(dut)
        sendPacket(dut, Seq(Nop, JalX0Plus16, JalX0Plus16, Nop))

        dut.io.decode(0).valid.expect(true)
        dut.io.decode(0).bits.instruction.expect(Nop)
        dut.io.decode(0).bits.prediction.pc.expect(ResetVector)
        dut.io.decode(0).bits.prediction.historyBefore.expect(0)
        dut.io.decode(0).bits.prediction.predictedTaken.expect(false)
        dut.io.decode(1).valid.expect(true)
        dut.io.decode(1).bits.instruction.expect(JalX0Plus16)
        dut.io.decode(1).bits.prediction.pc.expect(ResetVector + 4)
        dut.io.decode(1).bits.prediction.predictedTaken.expect(true)
        dut.io.decode(1).bits.prediction.predictedTarget.expect(ResetVector + 20)
        dut.io.decode(1).bits.prediction.conditional.expect(false)
        dut.io.decode(0).ready.poke(true)
        dut.io.decode(1).ready.poke(true)
        dut.clock.step()

        dut.clock.step()
        dut.io.l2Request.valid.expect(false)
        dut.io.fetchBusy.expect(true)
      }
    }

    it("stops after a targetless JALR until execute recovery installs the target") {
      val recoveredTarget = ResetVector + 0x180
      simulate(new M1Frontend) { dut =>
        clearInputs(dut)
        finishPredictorScrub(dut)
        launchRequest(dut, ResetVector)
        acceptRequest(dut)
        sendPacket(dut, Seq(JalrX0X0, Nop, Nop, Nop))

        dut.io.unresolvedIndirect.expect(true)
        dut.io.decode(0).valid.expect(true)
        dut.io.decode(0).bits.instruction.expect(JalrX0X0)
        dut.io.decode(0).bits.prediction.predictedTaken.expect(false)
        dut.io.decode(1).valid.expect(false)
        dut.clock.step(3)
        dut.io.l2Request.valid.expect(false)

        driveRecovery(dut, recoveredTarget)
        dut.io.unresolvedIndirect.expect(false)
        dut.io.currentPc.expect(recoveredTarget)
        dut.clock.step(2)
        dut.io.l2Request.valid.expect(true)
        dut.io.l2Request.bits.lineAddress.expect(recoveredTarget)
      }
    }

    it("uses commit-stage conditional training for the next fetch prediction") {
      simulate(new M1Frontend) { dut =>
        clearInputs(dut)
        finishPredictorScrub(dut)
        trainTakenConditional(dut, ResetVector, ResetVector + 16)
        launchRequest(dut, ResetVector)
        acceptRequest(dut)
        sendPacket(dut, Seq(BeqX0X0Plus16, Nop, Nop, Nop))

        dut.io.decode(0).valid.expect(true)
        dut.io.decode(0).bits.prediction.predictedTaken.expect(true)
        dut.io.decode(0).bits.prediction.predictedTarget.expect(ResetVector + 16)
        dut.io.decode(0).bits.prediction.conditional.expect(true)
        dut.io.decode(0).bits.prediction.provider.expect(BranchProvider.Base)
        dut.clock.step(2)
        dut.io.l2Request.valid.expect(false)
      }
    }

    it("gives a commit redirect priority over recovery and drains an accepted burst") {
      val recoveryTarget = ResetVector + 0x80
      val commitTarget = ResetVector + 0x140
      simulate(new M1Frontend) { dut =>
        clearInputs(dut)
        finishPredictorScrub(dut)
        launchRequest(dut, ResetVector)
        acceptRequest(dut)

        dut.io.executeRecovery.valid.poke(true)
        dut.io.executeRecovery.bits.redirectTarget.poke(recoveryTarget)
        dut.io.commitRedirect.valid.poke(true)
        dut.io.commitRedirect.bits.target.poke(commitTarget)
        dut.io.commitRedirect.bits.reason.poke(CommitRedirectReason.Exception)
        dut.clock.step()
        dut.io.executeRecovery.valid.poke(false)
        dut.io.commitRedirect.valid.poke(false)
        dut.io.currentPc.expect(commitTarget)
        dut.io.fetchDraining.expect(true)

        sendPacket(dut, Seq.tabulate(4)(BigInt(_)), expectPacket = false)
        dut.io.decode.foreach(_.valid.expect(false))
        dut.clock.step(2)
        dut.io.l2Request.valid.expect(true)
        dut.io.l2Request.bits.lineAddress.expect(commitTarget)
      }
    }
  }
}
