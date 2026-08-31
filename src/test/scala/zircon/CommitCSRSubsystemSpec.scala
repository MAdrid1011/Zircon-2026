package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{CommitCSRSubsystem, EndpointMask, MachineCSRAddress,
  UopClass}
import zircon.frontend.IntOperation

class CommitCSRSubsystemSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: CommitCSRSubsystem): Unit = {
    for (lane <- 0 until 2) {
      dut.io.rob(lane).valid.poke(false)
      driveRobLane(dut, lane, robTag = lane,
        pc = BigInt("80000000", 16) + lane * 4)
      dut.io.rob(lane).valid.poke(false)
      dut.io.sideEffect(lane).csrWrite.poke(false)
      dut.io.sideEffect(lane).csrAddress.poke(0)
      dut.io.sideEffect(lane).csrData.poke(0)
      dut.io.sideEffect(lane).serializingReady.poke(true)
    }
    dut.io.firstFault.valid.poke(false)
    dut.io.firstFault.bits.robTag.poke(0)
    dut.io.firstFault.bits.cause.poke(0)
    dut.io.firstFault.bits.trapValue.poke(0)
    dut.io.csrAccess.address.poke(0)
    dut.io.csrAccess.write.poke(false)
    dut.io.interrupts.meip.poke(false)
    dut.io.interrupts.msip.poke(false)
    dut.io.interrupts.mtip.poke(false)
    dut.io.interruptHead.valid.poke(false)
    dut.io.interruptBlocked.poke(false)
    dut.io.fpCommit.valid.poke(false)
    dut.io.fpCommit.bits.flags.poke(0)
    dut.io.fpCommit.bits.dirty.poke(false)
    dut.io.branchCommit.ready.poke(true)
  }

  private def driveRobLane(
      dut: CommitCSRSubsystem,
      lane: Int,
      robTag: Int,
      pc: BigInt,
      operation: IntOperation.Type = IntOperation.Add,
      uopClass: UopClass.Type = UopClass.Integer,
      allocatesPhysical: Boolean = true,
      hasBranchData: Boolean = false,
      branchDataIndex: Int = 0
  ): Unit = {
    val rob = dut.io.rob(lane)
    val entry = rob.bits.entry
    val decoded = entry.decoded
    rob.valid.poke(true)
    rob.bits.robTag.poke(robTag)
    entry.pc.poke(pc)
    entry.instruction.poke(BigInt("00000013", 16))
    entry.privilege.poke(3)
    entry.architecturalDestination.poke(lane + 1)
    entry.oldPhysicalDestination.poke(lane + 1)
    entry.newPhysicalDestination.poke(32 + lane)
    entry.allocatesPhysical.poke(allocatesPhysical)
    entry.hasBranchData.poke(hasBranchData)
    entry.branchDataIndex.poke(branchDataIndex)

    decoded.legal.poke(true)
    decoded.operation.poke(operation)
    decoded.uopClass.poke(uopClass)
    decoded.allowedEndpoints.poke(
      if (uopClass == UopClass.Integer) EndpointMask.IntegerSimple
      else EndpointMask.E0)
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
    decoded.isControl.poke(uopClass == UopClass.Branch)
    decoded.isMemory.poke(false)
    decoded.isFenceI.poke(operation == IntOperation.FenceI)
  }

  private def readCSR(
      dut: CommitCSRSubsystem,
      address: Int,
      expected: BigInt
  ): Unit = {
    dut.io.csrAccess.address.poke(address)
    dut.io.csrAccess.write.poke(false)
    dut.io.csrAccessLegal.expect(true)
    dut.io.csrAccessData.expect(expected)
  }

  describe("CommitCSRSubsystem") {
    it("retires two ordinary instructions and increments minstret") {
      simulate(new CommitCSRSubsystem) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 0, BigInt("80000000", 16))
        driveRobLane(dut, 1, 1, BigInt("80000004", 16))

        dut.io.rob.foreach(_.ready.expect(true))
        dut.io.retiredInstructions.expect(2)
        dut.io.renameCommit.foreach(_.valid.expect(true))
        dut.io.branchCommit.valid.expect(false)
        dut.io.globalFlush.expect(false)
        dut.clock.step()

        dut.io.rob.foreach(_.valid.poke(false))
        readCSR(dut, MachineCSRAddress.Minstret, 2)
      }
    }

    it("serializes adjacent branch training through one BDB port") {
      simulate(new CommitCSRSubsystem) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 0, BigInt("80000000", 16),
          IntOperation.Beq, UopClass.Branch, allocatesPhysical = false,
          hasBranchData = true, branchDataIndex = 3)
        driveRobLane(dut, 1, 1, BigInt("80000004", 16),
          IntOperation.Bne, UopClass.Branch, allocatesPhysical = false,
          hasBranchData = true, branchDataIndex = 4)

        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.retiredInstructions.expect(1)
        dut.io.branchCommit.valid.expect(true)
        dut.io.branchCommit.bits.robTag.expect(0)
        dut.io.branchCommit.bits.index.expect(3)
        dut.clock.step()

        driveRobLane(dut, 0, 1, BigInt("80000004", 16),
          IntOperation.Bne, UopClass.Branch, allocatesPhysical = false,
          hasBranchData = true, branchDataIndex = 4)
        dut.io.rob(1).valid.poke(false)
        dut.io.branchCommit.valid.expect(true)
        dut.io.branchCommit.bits.robTag.expect(1)
        dut.io.branchCommit.bits.index.expect(4)
        dut.clock.step()

        dut.io.rob(0).valid.poke(false)
        readCSR(dut, MachineCSRAddress.Minstret, 2)
      }
    }

    it("retires an older branch while taking a lane-one exception") {
      simulate(new CommitCSRSubsystem) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 8, BigInt("80000020", 16),
          IntOperation.Beq, UopClass.Branch, allocatesPhysical = false,
          hasBranchData = true, branchDataIndex = 5)
        driveRobLane(dut, 1, 9, BigInt("80000024", 16))
        dut.io.firstFault.valid.poke(true)
        dut.io.firstFault.bits.robTag.poke(9)
        dut.io.firstFault.bits.cause.poke(2)
        dut.io.firstFault.bits.trapValue.poke(BigInt("ffffffff", 16))

        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.branchCommit.valid.expect(true)
        dut.io.branchCommit.bits.robTag.expect(8)
        dut.io.retiredInstructions.expect(1)
        dut.io.trapCommit.valid.expect(true)
        dut.io.trapCommit.bits.exceptionPc.expect(BigInt("80000024", 16))
        dut.io.trapEntry.valid.expect(true)
        dut.io.trapEntry.bits.entry.pc.expect(BigInt("80000024", 16))
        dut.io.trapLane.expect(1)
        dut.io.firstFaultClear.expect(true)
        dut.io.globalFlush.expect(true)
        dut.clock.step()

        dut.io.rob.foreach(_.valid.poke(false))
        dut.io.firstFault.valid.poke(false)
        readCSR(dut, MachineCSRAddress.Mepc, BigInt("80000024", 16))
        readCSR(dut, MachineCSRAddress.Mcause, 2)
        readCSR(dut, MachineCSRAddress.Mtval, BigInt("ffffffff", 16))
        readCSR(dut, MachineCSRAddress.Minstret, 1)
      }
    }

    it("commits a serialized CSR write into architectural state") {
      simulate(new CommitCSRSubsystem) { dut =>
        clearInputs(dut)
        driveRobLane(dut, 0, 12, BigInt("80000030", 16),
          IntOperation.Csrrw, UopClass.Csr)
        driveRobLane(dut, 1, 13, BigInt("80000034", 16))
        dut.io.sideEffect(0).csrWrite.poke(true)
        dut.io.sideEffect(0).csrAddress.poke(MachineCSRAddress.Mscratch)
        dut.io.sideEffect(0).csrData.poke(BigInt("12345678", 16))

        dut.io.rob(0).ready.expect(true)
        dut.io.rob(1).ready.expect(false)
        dut.io.csrWrite.valid.expect(true)
        dut.io.retiredInstructions.expect(1)
        dut.clock.step()

        dut.io.rob.foreach(_.valid.poke(false))
        dut.io.sideEffect(0).csrWrite.poke(false)
        readCSR(dut, MachineCSRAddress.Mscratch,
          BigInt("12345678", 16))
      }
    }
  }
}
