package zircon

import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.DecoupledIO
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, IntegerPipeRequest, IntegerShortPipes,
  SourceKind, UopClass}
import zircon.frontend.IntOperation

class IntegerShortPipesSpec extends AnyFunSpec with ChiselSim {
  private def driveRequest(
      request: DecoupledIO[IntegerPipeRequest],
      tag: Int,
      endpoint: Int,
      uopClass: UopClass.Type,
      operation: IntOperation.Type,
      lhs: BigInt = 0,
      rhs: BigInt = 0,
      pc: BigInt = BigInt("80000000", 16),
      immediate: BigInt = 0,
      writesInteger: Boolean = true,
      destinationPhysical: Int = 32,
      hasBranchData: Boolean = false,
      branchDataIndex: Int = 0,
      instruction: BigInt = BigInt("00000013", 16),
      csrAddress: Int = 0,
      csrImmediate: Int = 0,
      csrRead: Boolean = false,
      csrWrite: Boolean = false
  ): Unit = {
    request.bits.uop.robTag.poke(tag)
    request.bits.uop.allowedEndpoints.poke(endpoint)
    request.bits.uop.uopClass.poke(uopClass)
    request.bits.uop.operation.poke(operation.litValue)
    request.bits.uop.sourceKind(0).poke(SourceKind.None)
    request.bits.uop.sourceKind(1).poke(SourceKind.None)
    request.bits.uop.sourceKind(2).poke(SourceKind.None)
    request.bits.uop.sourcePhysical(0).poke(0)
    request.bits.uop.sourcePhysical(1).poke(0)
    request.bits.uop.sourceReady.foreach(_.poke(true))
    request.bits.uop.destinationPhysical.poke(destinationPhysical)
    request.bits.uop.writesInteger.poke(writesInteger)
    request.bits.uop.writesFloat.poke(false)
    request.bits.uop.immediate.poke(immediate)

    request.bits.context.robTag.poke(tag)
    request.bits.context.pc.poke(pc)
    request.bits.context.instruction.poke(instruction)
    request.bits.context.privilege.poke(3)
    request.bits.context.csrAddress.poke(csrAddress)
    request.bits.context.csrImmediate.poke(csrImmediate)
    request.bits.context.csrRead.poke(csrRead)
    request.bits.context.csrWrite.poke(csrWrite)
    request.bits.context.hasBranchData.poke(hasBranchData)
    request.bits.context.branchDataIndex.poke(branchDataIndex)
    request.bits.lhs.poke(lhs)
    request.bits.rhs.poke(rhs)
  }

  private def clearInputs(dut: IntegerShortPipes): Unit = {
    dut.io.e0.valid.poke(false)
    dut.io.e1.valid.poke(false)
    driveRequest(dut.io.e0, 0, EndpointMask.E0, UopClass.Integer,
      IntOperation.Add)
    driveRequest(dut.io.e1, 0, EndpointMask.E1, UopClass.Integer,
      IntOperation.Add)
    dut.io.e0Completion.ready.poke(false)
    dut.io.e1Completion.ready.poke(false)
    dut.io.branchResolve.ready.poke(false)
    dut.io.csrAccessData.poke(0)
    dut.io.csrAccessLegal.poke(false)
    dut.io.systemSerializingReady.poke(true)
    dut.io.retire.foreach { retire =>
      retire.valid.poke(false)
      retire.bits.poke(0)
    }
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.recoveryActive.poke(false)
    dut.io.flush.poke(false)
  }

  describe("IntegerShortPipes") {
    it("buffers an E1 integer result until the completion network accepts it") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e1, tag = 1, endpoint = EndpointMask.E1,
          uopClass = UopClass.Integer, operation = IntOperation.Add,
          lhs = 5, rhs = 7, destinationPhysical = 33)
        dut.io.e1.valid.poke(true)
        dut.io.e1.ready.expect(true)
        dut.clock.step()

        dut.io.e1.valid.poke(false)
        dut.io.e1Count.expect(1)
        dut.io.e1Completion.valid.expect(true)
        dut.io.e1Completion.bits.robTag.expect(1)
        dut.io.e1Completion.bits.destinationPhysical.expect(33)
        dut.io.e1Completion.bits.data.expect(12)
        dut.clock.step(2)
        dut.io.e1Completion.bits.data.expect(12)

        dut.io.e1Completion.ready.poke(true)
        dut.clock.step()
        dut.io.e1Count.expect(0)
      }
    }

    it("buffers and completes an E0 non-control integer operation") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 2, endpoint = EndpointMask.E0,
          uopClass = UopClass.Integer, operation = IntOperation.Sub,
          lhs = 19, rhs = 4, destinationPhysical = 34)
        dut.io.e0.valid.poke(true)
        dut.io.e0.ready.expect(true)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.e0Occupied.expect(true)
        dut.io.branchResolve.valid.expect(false)
        dut.io.e0Completion.valid.expect(true)
        dut.io.e0Completion.bits.robTag.expect(2)
        dut.io.e0Completion.bits.data.expect(15)

        dut.io.e0Completion.ready.poke(true)
        dut.clock.step()
        dut.io.e0Occupied.expect(false)
      }
    }

    it("resolves an aligned branch before exposing its completion") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 3, endpoint = EndpointMask.E0,
          uopClass = UopClass.Branch, operation = IntOperation.Beq,
          lhs = 9, rhs = 9, immediate = 8, writesInteger = false,
          hasBranchData = true, branchDataIndex = 5)
        dut.io.e0.valid.poke(true)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.branchResolve.valid.expect(true)
        dut.io.branchResolve.bits.reference.robTag.expect(3)
        dut.io.branchResolve.bits.reference.index.expect(5)
        dut.io.branchResolve.bits.actualTaken.expect(true)
        dut.io.branchResolve.bits.actualTarget.expect(BigInt("80000008", 16))
        dut.io.e0Completion.valid.expect(false)
        dut.clock.step(2)
        dut.io.branchResolve.valid.expect(true)
        dut.io.branchResolve.bits.reference.robTag.expect(3)
        dut.io.branchResolve.bits.actualTarget.expect(BigInt("80000008", 16))

        dut.io.branchResolve.ready.poke(true)
        dut.clock.step()
        dut.io.branchResolve.valid.expect(false)
        dut.io.e0Completion.valid.expect(true)
        dut.io.e0Completion.bits.robTag.expect(3)
      }
    }

    it("keeps the resolving branch across its own squash and recovery") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 4, endpoint = EndpointMask.E0,
          uopClass = UopClass.Branch, operation = IntOperation.Bne,
          lhs = 1, rhs = 2, immediate = 12, writesInteger = false,
          hasBranchData = true, branchDataIndex = 2)
        dut.io.e0.valid.poke(true)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.branchResolve.ready.poke(true)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(4)
        dut.io.branchResolve.valid.expect(true)
        dut.clock.step()

        dut.io.squash.valid.poke(false)
        dut.io.recoveryActive.poke(true)
        dut.io.e0Occupied.expect(true)
        dut.io.branchResolve.valid.expect(false)
        dut.io.e0Completion.valid.expect(false)
        dut.clock.step()

        dut.io.recoveryActive.poke(false)
        dut.io.e0Completion.valid.expect(true)
        dut.io.e0Completion.bits.robTag.expect(4)
      }
    }

    it("removes a younger E0 result during selective squash") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 7, endpoint = EndpointMask.E0,
          uopClass = UopClass.Integer, operation = IntOperation.Add,
          lhs = 1, rhs = 2)
        dut.io.e0.valid.poke(true)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(5)
        dut.io.e0Completion.valid.expect(false)
        dut.clock.step()

        dut.io.squash.valid.poke(false)
        dut.io.e0Occupied.expect(false)
        dut.io.e0Completion.valid.expect(false)
      }
    }

    it("reports a taken misaligned target without resolving the branch") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 6, endpoint = EndpointMask.E0,
          uopClass = UopClass.Branch, operation = IntOperation.Beq,
          lhs = 11, rhs = 11, immediate = 2, writesInteger = false,
          hasBranchData = true, branchDataIndex = 1)
        dut.io.e0.valid.poke(true)
        dut.io.e0.ready.expect(true)
        dut.io.e0Fault.valid.expect(true)
        dut.io.e0Fault.record.robTag.expect(6)
        dut.io.e0Fault.record.cause.expect(0)
        dut.io.e0Fault.record.trapValue.expect(BigInt("80000002", 16))
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.e0Fault.valid.expect(false)
        dut.io.branchResolve.valid.expect(false)
        dut.io.e0Completion.valid.expect(true)
        dut.io.e0Completion.bits.robTag.expect(6)
      }
    }

    it("replaces a completed E0 result in one cycle and flushes the replacement") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 10, endpoint = EndpointMask.E0,
          uopClass = UopClass.Integer, operation = IntOperation.Add,
          lhs = 2, rhs = 3)
        dut.io.e0.valid.poke(true)
        dut.clock.step()

        driveRequest(dut.io.e0, tag = 11, endpoint = EndpointMask.E0,
          uopClass = UopClass.Integer, operation = IntOperation.Or,
          lhs = 8, rhs = 1)
        dut.io.e0Completion.ready.poke(true)
        dut.io.e0Completion.bits.robTag.expect(10)
        dut.io.e0.ready.expect(true)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.e0Completion.ready.poke(false)
        dut.io.e0Occupied.expect(true)
        dut.io.e0Completion.bits.robTag.expect(11)
        dut.io.e0Completion.bits.data.expect(9)
        dut.io.flush.poke(true)
        dut.io.e0Completion.valid.expect(false)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.e0Occupied.expect(false)
      }
    }

    it("allows E1 to progress while E0 waits for branch resolution") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 8, endpoint = EndpointMask.E0,
          uopClass = UopClass.Branch, operation = IntOperation.Beq,
          lhs = 1, rhs = 1, immediate = 8, writesInteger = false,
          hasBranchData = true, branchDataIndex = 0)
        dut.io.e0.valid.poke(true)
        dut.clock.step()
        dut.io.e0.valid.poke(false)

        driveRequest(dut.io.e1, tag = 9, endpoint = EndpointMask.E1,
          uopClass = UopClass.Integer, operation = IntOperation.Xor,
          lhs = BigInt("a5a5a5a5", 16), rhs = BigInt("ffff0000", 16))
        dut.io.e1.valid.poke(true)
        dut.io.branchResolve.valid.expect(true)
        dut.io.e1.ready.expect(true)
        dut.clock.step()

        dut.io.e1.valid.poke(false)
        dut.io.e1Completion.valid.expect(true)
        dut.io.e1Completion.bits.robTag.expect(9)
        dut.io.e1Completion.bits.data.expect(BigInt("5a5aa5a5", 16))
        dut.io.e0Completion.valid.expect(false)
      }
    }

    it("executes a head CSR and retains its write until matching retirement") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        val instruction = BigInt("340092f3", 16) // csrrw x5, mscratch, x1
        driveRequest(dut.io.e0, tag = 12, endpoint = EndpointMask.E0,
          uopClass = UopClass.Csr, operation = IntOperation.Csrrw,
          lhs = BigInt("12345678", 16), destinationPhysical = 35,
          instruction = instruction, csrAddress = 0x340,
          csrRead = true, csrWrite = true)
        dut.io.robHeadTag.poke(12)
        dut.io.csrAccessData.poke(BigInt("deadbeef", 16))
        dut.io.csrAccessLegal.poke(true)
        dut.io.e0.valid.poke(true)

        dut.io.e0.ready.expect(true)
        dut.io.csrAccess.address.expect(0x340)
        dut.io.csrAccess.write.expect(true)
        dut.io.e0Fault.valid.expect(false)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.e0Completion.valid.expect(true)
        dut.io.e0Completion.bits.writesInteger.expect(true)
        dut.io.e0Completion.bits.destinationPhysical.expect(35)
        dut.io.e0Completion.bits.data.expect(BigInt("deadbeef", 16))
        dut.io.commitSideEffect.valid.expect(true)
        dut.io.commitSideEffect.bits.robTag.expect(12)
        dut.io.commitSideEffect.bits.sideEffect.csrWrite.expect(true)
        dut.io.commitSideEffect.bits.sideEffect.csrAddress.expect(0x340)
        dut.io.commitSideEffect.bits.sideEffect.csrData.expect(
          BigInt("12345678", 16))
        dut.io.e0Completion.ready.poke(true)
        dut.clock.step()

        dut.io.e0Completion.ready.poke(false)
        dut.io.commitSideEffect.valid.expect(true)
        dut.io.retire(0).valid.poke(true)
        dut.io.retire(0).bits.poke(12)
        dut.clock.step()
        dut.io.retire(0).valid.poke(false)
        dut.io.commitSideEffect.valid.expect(false)
      }
    }

    it("turns an illegal CSR access into a precise illegal-instruction fault") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        val instruction = BigInt("fff092f3", 16)
        driveRequest(dut.io.e0, tag = 13, endpoint = EndpointMask.E0,
          uopClass = UopClass.Csr, operation = IntOperation.Csrrw,
          lhs = 7, destinationPhysical = 36, instruction = instruction,
          csrAddress = 0xfff, csrRead = true, csrWrite = true)
        dut.io.robHeadTag.poke(13)
        dut.io.csrAccessLegal.poke(false)
        dut.io.e0.valid.poke(true)

        dut.io.e0.ready.expect(true)
        dut.io.e0Fault.valid.expect(true)
        dut.io.e0Fault.record.cause.expect(2)
        dut.io.e0Fault.record.trapValue.expect(instruction)
        dut.clock.step()

        dut.io.e0.valid.poke(false)
        dut.io.e0Completion.valid.expect(true)
        dut.io.e0Completion.bits.writesInteger.expect(false)
        dut.io.commitSideEffect.valid.expect(true)
        dut.io.commitSideEffect.bits.sideEffect.csrWrite.expect(false)
      }
    }

    it("holds a System uop away from E0 until it reaches the ROB head") {
      simulate(new IntegerShortPipes) { dut =>
        clearInputs(dut)
        driveRequest(dut.io.e0, tag = 15, endpoint = EndpointMask.E0,
          uopClass = UopClass.System, operation = IntOperation.Fence,
          writesInteger = false)
        dut.io.robHeadTag.poke(14)
        dut.io.e0.valid.poke(true)
        dut.io.e0.ready.expect(false)

        dut.io.robHeadTag.poke(15)
        dut.io.e0.ready.expect(true)
        dut.clock.step()
        dut.io.e0.valid.poke(false)
        dut.io.systemSerializingReady.poke(false)
        dut.io.commitSideEffect.valid.expect(true)
        dut.io.commitSideEffect.bits.sideEffect.serializingReady.expect(false)
        dut.io.systemSerializingReady.poke(true)
        dut.io.commitSideEffect.bits.sideEffect.serializingReady.expect(true)
      }
    }
  }
}
