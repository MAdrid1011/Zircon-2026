package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, IntegerOperandRead, SourceKind, UopClass}

class IntegerOperandReadSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: IntegerOperandRead): Unit = {
    dut.io.flush.poke(false)
    for (lane <- 0 until 2) {
      val issue = dut.io.issue(lane)
      issue.valid.poke(false)
      issue.bits.robTag.poke(lane)
      issue.bits.allowedEndpoints.poke(
        if (lane == 0) EndpointMask.E0 else EndpointMask.E1)
      issue.bits.uopClass.poke(UopClass.Integer)
      issue.bits.operation.poke(0)
      issue.bits.sourceKind(0).poke(SourceKind.None)
      issue.bits.sourceKind(1).poke(SourceKind.None)
      issue.bits.sourceKind(2).poke(SourceKind.None)
      issue.bits.sourcePhysical(0).poke(0)
      issue.bits.sourcePhysical(1).poke(0)
      issue.bits.sourceReady.foreach(_.poke(true))
      issue.bits.destinationPhysical.poke(32 + lane)
      issue.bits.writesInteger.poke(true)
      issue.bits.writesFloat.poke(false)
      issue.bits.immediate.poke(0)

      val context = dut.io.robContext(lane)
      context.valid.poke(true)
      context.bits.robTag.poke(lane)
      context.bits.pc.poke(BigInt("80000000", 16) + lane * 4)
      context.bits.instruction.poke(BigInt("00000013", 16))
      context.bits.privilege.poke(3)
      context.bits.csrAddress.poke(0)
      context.bits.csrImmediate.poke(0)
      context.bits.csrRead.poke(false)
      context.bits.csrWrite.poke(false)
      context.bits.hasBranchData.poke(false)
      context.bits.branchDataIndex.poke(0)
      dut.io.execute(lane).ready.poke(false)
    }
    dut.io.prfReadData.foreach(_.poke(0))
  }

  describe("IntegerOperandRead") {
    it("selects PRF, PC, and immediate operands with independent endpoint backpressure") {
      simulate(new IntegerOperandRead) { dut =>
        clearInputs(dut)

        dut.io.issue(0).valid.poke(true)
        dut.io.issue(0).bits.sourceKind(0).poke(SourceKind.IntegerRegister)
        dut.io.issue(0).bits.sourceKind(1).poke(SourceKind.IntegerRegister)
        dut.io.issue(0).bits.sourcePhysical(0).poke(5)
        dut.io.issue(0).bits.sourcePhysical(1).poke(6)
        dut.io.prfReadData(0).poke(BigInt("11111111", 16))
        dut.io.prfReadData(1).poke(BigInt("22222222", 16))

        dut.io.issue(1).valid.poke(true)
        dut.io.issue(1).bits.sourceKind(0).poke(SourceKind.ProgramCounter)
        dut.io.issue(1).bits.sourceKind(1).poke(SourceKind.Immediate)
        dut.io.issue(1).bits.sourcePhysical(0).poke(7)
        dut.io.issue(1).bits.sourcePhysical(1).poke(8)
        dut.io.issue(1).bits.immediate.poke(BigInt("fffff000", 16))

        dut.io.robRead(0).valid.expect(true)
        dut.io.robRead(0).bits.expect(0)
        dut.io.robRead(1).valid.expect(true)
        dut.io.robRead(1).bits.expect(1)
        dut.io.prfReadPhysical(0).expect(5)
        dut.io.prfReadPhysical(1).expect(6)
        dut.io.prfReadPhysical(2).expect(7)
        dut.io.prfReadPhysical(3).expect(8)

        dut.io.execute(0).valid.expect(true)
        dut.io.execute(0).bits.lhs.expect(BigInt("11111111", 16))
        dut.io.execute(0).bits.rhs.expect(BigInt("22222222", 16))
        dut.io.execute(1).valid.expect(true)
        dut.io.execute(1).bits.lhs.expect(BigInt("80000004", 16))
        dut.io.execute(1).bits.rhs.expect(BigInt("fffff000", 16))

        dut.io.execute(0).ready.poke(false)
        dut.io.execute(1).ready.poke(true)
        dut.io.issue(0).ready.expect(false)
        dut.io.issue(1).ready.expect(true)
      }
    }

    it("drives None operands to zero and blocks all transfers on flush") {
      simulate(new IntegerOperandRead) { dut =>
        clearInputs(dut)
        dut.io.issue(0).valid.poke(true)
        dut.io.execute(0).ready.poke(true)
        dut.io.execute(0).valid.expect(true)
        dut.io.execute(0).bits.lhs.expect(0)
        dut.io.execute(0).bits.rhs.expect(0)
        dut.io.issue(0).ready.expect(true)

        dut.io.flush.poke(true)
        dut.io.robRead.foreach(_.valid.expect(false))
        dut.io.execute.foreach(_.valid.expect(false))
        dut.io.issue.foreach(_.ready.expect(false))
      }
    }

    it("uses p0 for an invalid issue lane instead of exposing its payload as a PRF address") {
      simulate(new IntegerOperandRead) { dut =>
        clearInputs(dut)
        for (lane <- 0 until 2) {
          dut.io.issue(lane).valid.poke(false)
          dut.io.issue(lane).bits.sourcePhysical(0).poke(63)
          dut.io.issue(lane).bits.sourcePhysical(1).poke(62)
        }

        dut.io.robRead.foreach(_.valid.expect(false))
        dut.io.execute.foreach(_.valid.expect(false))
        dut.io.prfReadPhysical.foreach(_.expect(0))
      }
    }
  }
}
