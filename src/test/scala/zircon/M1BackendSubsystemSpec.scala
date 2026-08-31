package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{BranchProvider, CommitRedirectReason, M1BackendSubsystem}

class M1BackendSubsystemSpec extends AnyFunSpec with ChiselSim {
  private val Nop = BigInt("00000013", 16)
  private val AddiX1X0_0x123 = BigInt("12300093", 16)
  private val CsrrwX5MscratchX1 = BigInt("340092f3", 16)
  private val CsrrsX6MscratchX0 = BigInt("34002373", 16)
  private val Ecall = BigInt("00000073", 16)
  private val Fence = BigInt("0000000f", 16)
  private val CsrrwiX0MieEight = BigInt("30445073", 16)
  private val CsrrwiX0MstatusEight = BigInt("30045073", 16)
  private val Mret = BigInt("30200073", 16)

  private def clearInputs(dut: M1BackendSubsystem): Unit = {
    for (lane <- 0 until 2) {
      val input = dut.io.input(lane)
      input.valid.poke(false)
      input.bits.instruction.poke(Nop)
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
    dut.io.interrupts.meip.poke(false)
    dut.io.interrupts.msip.poke(false)
    dut.io.interrupts.mtip.poke(false)
    dut.io.interruptBlocked.poke(false)
    dut.io.systemSerializingReady.poke(true)
    dut.io.fpCommit.valid.poke(false)
    dut.io.fpCommit.bits.flags.poke(0)
    dut.io.fpCommit.bits.dirty.poke(false)
    dut.io.auxReadPhysical.foreach(_.poke(0))
  }

  private def driveInstruction(
      dut: M1BackendSubsystem,
      lane: Int,
      instruction: BigInt,
      pc: BigInt
  ): Unit = {
    val input = dut.io.input(lane)
    input.valid.poke(true)
    input.bits.instruction.poke(instruction)
    input.bits.prediction.pc.poke(pc)
    input.bits.prediction.predictedTarget.poke(pc + 4)
  }

  private def clearDecode(dut: M1BackendSubsystem): Unit =
    dut.io.input.foreach(_.valid.poke(false))

  private def stepUntil(
      dut: M1BackendSubsystem,
      maximumCycles: Int
  )(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < maximumCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(condition, s"condition did not become true within $maximumCycles cycles")
  }

  describe("M1BackendSubsystem") {
    it("executes a dependent CSRRW and exposes the committed value to a later CSR read") {
      simulate(new M1BackendSubsystem) { dut =>
        clearInputs(dut)
        dut.io.auxReadPhysical(0).poke(32)
        dut.io.auxReadPhysical(1).poke(33)
        driveInstruction(dut, 0, AddiX1X0_0x123,
          BigInt("80000000", 16))
        driveInstruction(dut, 1, CsrrwX5MscratchX1,
          BigInt("80000004", 16))
        dut.io.acceptedCount.expect(2)
        dut.clock.step()
        clearDecode(dut)

        stepUntil(dut, 16) {
          dut.io.robCount.peek().litValue == 0
        }
        dut.io.committedMap(1).expect(32)
        dut.io.committedMap(5).expect(33)
        dut.io.auxReadData(0).expect(BigInt("123", 16))
        dut.io.auxReadData(1).expect(0)

        // The committed CSRRW freed architectural x1's old p1 mapping, so the
        // circular free list legitimately reuses p1 for this destination.
        dut.io.auxReadPhysical(0).poke(1)
        driveInstruction(dut, 0, CsrrsX6MscratchX0,
          BigInt("80000008", 16))
        dut.io.input(1).valid.poke(false)
        dut.io.acceptedCount.expect(1)
        dut.clock.step()
        clearDecode(dut)

        stepUntil(dut, 12) {
          dut.io.robCount.peek().litValue == 0
        }
        dut.io.committedMap(6).expect(1)
        dut.io.auxReadData(0).expect(BigInt("123", 16))
      }
    }

    it("converts ECALL into a precise M-mode trap without retiring it") {
      simulate(new M1BackendSubsystem) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, Ecall, BigInt("80000100", 16))
        dut.io.input(1).valid.poke(false)
        dut.io.acceptedCount.expect(1)
        dut.clock.step()
        clearDecode(dut)

        stepUntil(dut, 10) {
          dut.io.redirect.valid.peek().litToBoolean
        }
        dut.io.globalFlush.expect(true)
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Exception)
        dut.io.redirect.bits.target.expect(0)
        dut.io.trapCommit.valid.expect(true)
        dut.io.trapCommit.bits.interrupt.expect(false)
        dut.io.trapCommit.bits.cause.expect(11)
        dut.io.trapCommit.bits.exceptionPc.expect(BigInt("80000100", 16))
        dut.io.trapCommit.bits.trapValue.expect(0)
        dut.io.trapEntry.valid.expect(true)
        dut.io.trapEntry.bits.entry.pc.expect(BigInt("80000100", 16))
        dut.io.trapLane.expect(0)
        dut.io.retiredInstructions.expect(0)
        dut.clock.step()
        dut.io.robCount.expect(0)
      }
    }

    it("keeps FENCE at the head until external serialization is complete") {
      simulate(new M1BackendSubsystem) { dut =>
        clearInputs(dut)
        dut.io.systemSerializingReady.poke(false)
        driveInstruction(dut, 0, Fence, BigInt("80000200", 16))
        dut.io.input(1).valid.poke(false)
        dut.clock.step()
        clearDecode(dut)

        dut.clock.step(4)
        dut.io.robCount.expect(1)
        dut.io.retiredInstructions.expect(0)
        dut.io.systemSerializingReady.poke(true)
        dut.io.retiredInstructions.expect(1)
        dut.clock.step()
        dut.io.robCount.expect(0)
      }
    }

    it("takes an enabled software interrupt and returns through MRET") {
      simulate(new M1BackendSubsystem) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, CsrrwiX0MieEight,
          BigInt("80000240", 16))
        driveInstruction(dut, 1, CsrrwiX0MstatusEight,
          BigInt("80000244", 16))
        dut.io.acceptedCount.expect(2)
        dut.clock.step()
        clearDecode(dut)

        stepUntil(dut, 16) {
          dut.io.robCount.peek().litValue == 0
        }
        dut.io.mstatusMie.expect(true)

        val returnPc = BigInt("80000300", 16)
        driveInstruction(dut, 0, Nop, returnPc)
        dut.io.input(1).valid.poke(false)
        dut.clock.step()
        clearDecode(dut)
        dut.io.interrupts.msip.poke(true)
        dut.io.redirect.valid.expect(true)
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Interrupt)
        dut.io.trapCommit.valid.expect(true)
        dut.io.trapCommit.bits.interrupt.expect(true)
        dut.io.trapCommit.bits.cause.expect(3)
        dut.io.trapCommit.bits.exceptionPc.expect(returnPc)
        dut.clock.step()
        dut.io.interrupts.msip.poke(false)
        dut.io.mstatusMie.expect(false)

        driveInstruction(dut, 0, Mret, BigInt("00000080", 16))
        dut.io.input(1).valid.poke(false)
        dut.clock.step()
        clearDecode(dut)
        stepUntil(dut, 10) {
          dut.io.redirect.valid.peek().litToBoolean
        }
        dut.io.mretCommit.expect(true)
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Mret)
        dut.io.redirect.bits.target.expect(returnPc)
        dut.clock.step()
        dut.io.mstatusMie.expect(true)
      }
    }
  }
}
