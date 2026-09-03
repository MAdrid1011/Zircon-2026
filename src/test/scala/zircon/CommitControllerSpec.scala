package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{CommitController, CommitRedirectReason, EndpointMask, MachineInterruptCause, UopClass}
import zircon.frontend.{FloatingOperation, IntOperation}

class CommitControllerSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: CommitController): Unit = {
    dut.io.rob.foreach(_.valid.poke(false))
    dut.io.sideEffect.foreach { sideEffect =>
      sideEffect.csrWrite.poke(false)
      sideEffect.csrAddress.poke(0)
      sideEffect.csrData.poke(0)
      sideEffect.serializingReady.poke(true)
    }
    dut.io.firstFault.valid.poke(false)
    dut.io.firstFault.bits.robTag.poke(0)
    dut.io.firstFault.bits.cause.poke(0)
    dut.io.firstFault.bits.trapValue.poke(0)
    dut.io.eligibleInterrupt.valid.poke(false)
    dut.io.eligibleInterrupt.cause.poke(0)
    dut.io.interruptHead.valid.poke(false)
    dut.io.interruptBlocked.poke(false)
    dut.io.trapVector.poke(BigInt("80000100", 16))
    dut.io.mretTarget.poke(BigInt("80000200", 16))
  }

  private def driveRobLane(
      dut: CommitController,
      lane: Int,
      robTag: Int,
      pc: BigInt,
      operation: IntOperation.Type = IntOperation.Add,
      uopClass: UopClass.Type = UopClass.Integer,
      allocatesPhysical: Boolean = true
  ): Unit = {
    val commit = dut.io.rob(lane)
    val entry = commit.bits.entry
    val decoded = entry.decoded

    commit.valid.poke(true)
    commit.bits.robTag.poke(robTag)
    entry.pc.poke(pc)
    entry.instruction.poke(BigInt("00000013", 16))
    entry.privilege.poke(3)
    entry.architecturalDestination.poke(lane + 1)
    entry.oldPhysicalDestination.poke(lane + 1)
    entry.newPhysicalDestination.poke(32 + lane)
    entry.allocatesPhysical.poke(allocatesPhysical)
    entry.hasBranchData.poke(false)
    entry.branchDataIndex.poke(0)

    decoded.legal.poke(true)
    decoded.operation.poke(operation)
    decoded.uopClass.poke(uopClass)
    decoded.allowedEndpoints.poke(
      if (uopClass == UopClass.Integer) EndpointMask.IntegerSimple else EndpointMask.E0)
    decoded.rs1.poke(1)
    decoded.rs2.poke(2)
    decoded.rd.poke(lane + 1)
    decoded.readsRs1.poke(true)
    decoded.readsRs2.poke(true)
    decoded.writesRd.poke(allocatesPhysical)
    decoded.operandBImmediate.poke(false)
    decoded.immediate.poke(0)
    decoded.csrAddress.poke(0)
    decoded.csrImmediate.poke(0)
    decoded.csrRead.poke(false)
    decoded.csrWrite.poke(false)
    decoded.isControl.poke(false)
    decoded.isMemory.poke(false)
    decoded.isFenceI.poke(operation == IntOperation.FenceI)
    entry.floating.legal.poke(false)
    entry.floating.operation.poke(FloatingOperation.Invalid)
    entry.floating.writesFloatRd.poke(false)
  }

  describe("CommitController") {
    it("retires two ordinary instructions and updates both committed rename lanes") {
      simulate(new CommitController) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 0, BigInt("80000000", 16))
        driveRobLane(dut, 1, 1, BigInt("80000004", 16))

        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(true)
        dut.io.retired(0).valid.expect(true)
        dut.io.retired(1).valid.expect(true)
        dut.io.retiredInstructions.expect(2)
        dut.io.renameCommit(0).valid.expect(true)
        dut.io.renameCommit(0).architectural.expect(1)
        dut.io.renameCommit(0).newPhysical.expect(32)
        dut.io.renameCommit(1).valid.expect(true)
        dut.io.renameCommit(1).architectural.expect(2)
        dut.io.flush.expect(false)
        dut.io.redirect.valid.expect(false)
      }
    }

    it("takes a lane-0 fault without retirement and a lane-1 fault after lane 0") {
      simulate(new CommitController) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 8, BigInt("80000020", 16))
        driveRobLane(dut, 1, 9, BigInt("80000024", 16))
        dut.io.firstFault.valid.poke(true)
        dut.io.firstFault.bits.robTag.poke(8)
        dut.io.firstFault.bits.cause.poke(2)
        dut.io.firstFault.bits.trapValue.poke(BigInt("bad00000", 16))

        dut.io.rob(0).ready.expect(false)
        dut.io.rob(1).ready.expect(false)
        dut.io.retiredInstructions.expect(0)
        dut.io.trapCommit.valid.expect(true)
        dut.io.trapCommit.bits.interrupt.expect(false)
        dut.io.trapCommit.bits.cause.expect(2)
        dut.io.trapCommit.bits.exceptionPc.expect(BigInt("80000020", 16))
        dut.io.trapCommit.bits.trapValue.expect(BigInt("bad00000", 16))
        dut.io.trapEntry.valid.expect(true)
        dut.io.trapEntry.bits.entry.pc.expect(BigInt("80000020", 16))
        dut.io.trapLane.expect(0)
        dut.io.firstFaultClear.expect(true)
        dut.io.flush.expect(true)
        dut.io.redirect.bits.target.expect(BigInt("80000100", 16))
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Exception)

        dut.io.firstFault.bits.robTag.poke(9)
        dut.io.firstFault.bits.cause.poke(5)
        dut.io.firstFault.bits.trapValue.poke(BigInt("deadbeef", 16))
        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.retired(0).valid.expect(true)
        dut.io.retired(1).valid.expect(false)
        dut.io.retiredInstructions.expect(1)
        dut.io.renameCommit(0).valid.expect(true)
        dut.io.renameCommit(1).valid.expect(false)
        dut.io.trapCommit.bits.cause.expect(5)
        dut.io.trapCommit.bits.exceptionPc.expect(BigInt("80000024", 16))
        dut.io.trapEntry.valid.expect(true)
        dut.io.trapEntry.bits.entry.pc.expect(BigInt("80000024", 16))
        dut.io.trapLane.expect(1)
      }
    }

    it("retires an older FPR write before exposing a younger synchronous fault") {
      simulate(new CommitController) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 10, BigInt("80000028", 16),
          allocatesPhysical = false)
        driveRobLane(dut, 1, 11, BigInt("8000002c", 16))
        dut.io.rob(0).bits.entry.floating.legal.poke(true)
        dut.io.rob(0).bits.entry.floating.operation.poke(FloatingOperation.FmvWX)
        dut.io.rob(0).bits.entry.floating.writesFloatRd.poke(true)
        dut.io.firstFault.valid.poke(true)
        dut.io.firstFault.bits.robTag.poke(11)
        dut.io.firstFault.bits.cause.poke(2)
        dut.io.firstFault.bits.trapValue.poke(BigInt("ffffffff", 16))

        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.retired(0).valid.expect(true)
        dut.io.retired(1).valid.expect(false)
        dut.io.trapCommit.valid.expect(false)
        dut.io.flush.expect(false)
      }
    }

    it("takes an unblocked interrupt before retirement but never above a head fault") {
      simulate(new CommitController) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 4, BigInt("80000010", 16))
        driveRobLane(dut, 1, 5, BigInt("80000014", 16))
        dut.io.interruptHead.valid.poke(true)
        dut.io.interruptHead.bits.entry.pc.poke(BigInt("80000010", 16))
        dut.io.eligibleInterrupt.valid.poke(true)
        dut.io.eligibleInterrupt.cause.poke(MachineInterruptCause.External)

        dut.io.rob(0).ready.expect(false)
        dut.io.rob(1).ready.expect(false)
        dut.io.trapCommit.valid.expect(true)
        dut.io.trapCommit.bits.interrupt.expect(true)
        dut.io.trapCommit.bits.cause.expect(MachineInterruptCause.External)
        dut.io.trapCommit.bits.exceptionPc.expect(BigInt("80000010", 16))
        dut.io.trapCommit.bits.trapValue.expect(0)
        dut.io.trapEntry.valid.expect(true)
        dut.io.trapEntry.bits.entry.pc.expect(BigInt("80000010", 16))
        dut.io.trapLane.expect(0)
        dut.io.firstFaultClear.expect(false)
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Interrupt)

        dut.io.firstFault.valid.poke(true)
        dut.io.firstFault.bits.robTag.poke(4)
        dut.io.firstFault.bits.cause.poke(3)
        dut.io.firstFault.bits.trapValue.poke(0)
        dut.io.trapCommit.bits.interrupt.expect(false)
        dut.io.trapCommit.bits.cause.expect(3)
        dut.io.firstFaultClear.expect(true)

        dut.io.firstFault.valid.poke(false)
        dut.io.interruptBlocked.poke(true)
        dut.io.trapCommit.valid.expect(false)
        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(true)

        dut.io.firstFault.valid.poke(true)
        dut.io.firstFault.bits.robTag.poke(5)
        dut.io.interruptBlocked.poke(false)
        dut.io.trapCommit.bits.interrupt.expect(true)
        dut.io.rob(0).ready.expect(false)
      }
    }

    it("serializes CSR effects and defers a younger fault or lane-1 CSR") {
      simulate(new CommitController) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 12, BigInt("80000030", 16),
          IntOperation.Csrrw, UopClass.Csr)
        driveRobLane(dut, 1, 13, BigInt("80000034", 16))
        dut.io.sideEffect(0).csrWrite.poke(true)
        dut.io.sideEffect(0).csrAddress.poke(0x340)
        dut.io.sideEffect(0).csrData.poke(BigInt("12345678", 16))
        dut.io.sideEffect(0).serializingReady.poke(false)

        dut.io.rob(0).ready.expect(false)
        dut.io.csrWrite.valid.expect(false)

        dut.io.sideEffect(0).serializingReady.poke(true)
        dut.io.firstFault.valid.poke(true)
        dut.io.firstFault.bits.robTag.poke(13)
        dut.io.firstFault.bits.cause.poke(2)
        dut.io.firstFault.bits.trapValue.poke(0)
        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.csrWrite.valid.expect(true)
        dut.io.csrWrite.bits.address.expect(0x340)
        dut.io.csrWrite.bits.data.expect(BigInt("12345678", 16))
        dut.io.trapCommit.valid.expect(false)
        dut.io.firstFaultClear.expect(false)

        dut.io.firstFault.valid.poke(false)
        dut.io.sideEffect(0).csrWrite.poke(false)
        driveRobLane(dut, 0, 12, BigInt("80000030", 16))
        driveRobLane(dut, 1, 13, BigInt("80000034", 16),
          IntOperation.Csrrs, UopClass.Csr)
        dut.io.sideEffect(1).csrWrite.poke(true)
        dut.io.sideEffect(1).csrAddress.poke(0x340)
        dut.io.sideEffect(1).csrData.poke(1)
        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.csrWrite.valid.expect(false)
      }
    }

    it("redirects MRET and FENCE.I while exposing WFI and FENCE commits") {
      simulate(new CommitController) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 20, BigInt("80000050", 16),
          IntOperation.Mret, UopClass.System, allocatesPhysical = false)
        driveRobLane(dut, 1, 21, BigInt("80000054", 16))
        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.mretCommit.expect(true)
        dut.io.flush.expect(true)
        dut.io.redirect.bits.target.expect(BigInt("80000200", 16))
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Mret)

        dut.io.rob(1).valid.poke(false)
        driveRobLane(dut, 0, 20, BigInt("80000050", 16),
          IntOperation.FenceI, UopClass.System, allocatesPhysical = false)
        dut.io.sideEffect(0).serializingReady.poke(false)
        dut.io.rob(0).ready.expect(false)
        dut.io.fenceICommit.expect(false)
        dut.io.sideEffect(0).serializingReady.poke(true)
        dut.io.rob(0).ready.expect(true)
        dut.io.fenceICommit.expect(true)
        dut.io.flush.expect(true)
        dut.io.redirect.bits.target.expect(BigInt("80000054", 16))
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.FenceI)

        driveRobLane(dut, 0, 20, BigInt("80000050", 16),
          IntOperation.Wfi, UopClass.System, allocatesPhysical = false)
        dut.io.wfiCommit.expect(true)
        dut.io.flush.expect(true)
        dut.io.redirect.valid.expect(true)
        dut.io.redirect.bits.target.expect(BigInt("80000054", 16))
        dut.io.redirect.bits.reason.expect(CommitRedirectReason.Wfi)

        driveRobLane(dut, 0, 20, BigInt("80000050", 16),
          IntOperation.Fence, UopClass.System, allocatesPhysical = false)
        dut.io.wfiCommit.expect(false)
        dut.io.rob(0).ready.expect(true)
        dut.io.flush.expect(false)
      }
    }
  }
}
