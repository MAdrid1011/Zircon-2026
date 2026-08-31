package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{CommitRedirectReason, EndpointMask, UopClass}
import zircon.frontend.IntOperation
import zircon.trace.RetireTraceFormatter

class RetireTraceFormatterSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: RetireTraceFormatter): Unit = {
    for (lane <- 0 until 2) {
      dut.io.retired(lane).valid.poke(false)
      dut.io.gprData(lane).poke(0)
      val entry = dut.io.retired(lane).bits.entry
      entry.pc.poke(0)
      entry.instruction.poke(0)
      entry.privilege.poke(3)
      entry.architecturalDestination.poke(0)
      entry.oldPhysicalDestination.poke(0)
      entry.newPhysicalDestination.poke(0)
      entry.allocatesPhysical.poke(false)
      entry.hasBranchData.poke(false)
      entry.branchDataIndex.poke(0)
      val decoded = entry.decoded
      decoded.legal.poke(true)
      decoded.operation.poke(IntOperation.Add)
      decoded.uopClass.poke(UopClass.Integer)
      decoded.allowedEndpoints.poke(EndpointMask.IntegerSimple)
      decoded.rs1.poke(0)
      decoded.rs2.poke(0)
      decoded.rd.poke(0)
      decoded.readsRs1.poke(false)
      decoded.readsRs2.poke(false)
      decoded.writesRd.poke(false)
      decoded.operandBImmediate.poke(false)
      decoded.immediate.poke(0)
      decoded.csrAddress.poke(0)
      decoded.csrImmediate.poke(0)
      decoded.csrRead.poke(false)
      decoded.csrWrite.poke(false)
      decoded.isControl.poke(false)
      decoded.isMemory.poke(false)
      decoded.isFenceI.poke(false)
    }
    dut.io.csrWrite.valid.poke(false)
    dut.io.csrWrite.bits.address.poke(0)
    dut.io.csrWrite.bits.data.poke(0)
    dut.io.trapCommit.valid.poke(false)
    dut.io.trapCommit.bits.interrupt.poke(false)
    dut.io.trapCommit.bits.cause.poke(0)
    dut.io.trapCommit.bits.exceptionPc.poke(0)
    dut.io.trapCommit.bits.trapValue.poke(0)
    dut.io.trapEntry.valid.poke(false)
    dut.io.trapLane.poke(0)
    dut.io.currentFflags.poke(0)
  }

  private def driveRetired(dut: RetireTraceFormatter, lane: Int, pc: BigInt,
      instruction: BigInt, destination: Int, data: BigInt): Unit = {
    val retired = dut.io.retired(lane)
    retired.valid.poke(true)
    retired.bits.robTag.poke(lane)
    retired.bits.entry.pc.poke(pc)
    retired.bits.entry.instruction.poke(instruction)
    retired.bits.entry.privilege.poke(3)
    retired.bits.entry.architecturalDestination.poke(destination)
    retired.bits.entry.allocatesPhysical.poke(destination != 0)
    dut.io.gprData(lane).poke(data)
  }

  private def driveTrapEntry(dut: RetireTraceFormatter, pc: BigInt,
      instruction: BigInt): Unit = {
    dut.io.trapEntry.valid.poke(true)
    dut.io.trapEntry.bits.robTag.poke(7)
    dut.io.trapEntry.bits.entry.pc.poke(pc)
    dut.io.trapEntry.bits.entry.instruction.poke(instruction)
    dut.io.trapEntry.bits.entry.privilege.poke(3)
    dut.io.trapEntry.bits.entry.allocatesPhysical.poke(false)
  }

  describe("RetireTraceFormatter") {
    it("orders two architectural retirements and emits their real GPR data") {
      simulate(new RetireTraceFormatter) { dut =>
        clearInputs(dut)
        driveRetired(dut, 0, BigInt("80000000", 16), BigInt("00100093", 16),
          destination = 1, data = 1)
        driveRetired(dut, 1, BigInt("80000004", 16), BigInt("00200113", 16),
          destination = 2, data = 2)
        dut.io.currentFflags.poke(3)

        dut.io.events(0).valid.expect(true)
        dut.io.events(0).order.expect(0)
        dut.io.events(0).pc.expect(BigInt("80000000", 16))
        dut.io.events(0).gprWrite.expect(true)
        dut.io.events(0).gprAddress.expect(1)
        dut.io.events(0).gprData.expect(1)
        dut.io.events(0).fflags.expect(3)
        dut.io.events(1).valid.expect(true)
        dut.io.events(1).order.expect(1)
        dut.io.events(1).gprAddress.expect(2)
        dut.io.events(1).gprData.expect(2)
        dut.clock.step()

        dut.io.retired(1).valid.poke(false)
        driveRetired(dut, 0, BigInt("80000008", 16), BigInt("00300193", 16),
          destination = 3, data = 3)
        dut.io.events(0).order.expect(2)
      }
    }

    it("places a lane-one exception after an older lane-zero retirement") {
      simulate(new RetireTraceFormatter) { dut =>
        clearInputs(dut)
        driveRetired(dut, 0, BigInt("80000020", 16), BigInt("00400213", 16),
          destination = 4, data = 4)
        dut.io.trapCommit.valid.poke(true)
        dut.io.trapCommit.bits.interrupt.poke(false)
        dut.io.trapCommit.bits.cause.poke(2)
        dut.io.trapCommit.bits.trapValue.poke(BigInt("ffffffff", 16))
        driveTrapEntry(dut, BigInt("80000024", 16), BigInt("ffffffff", 16))
        dut.io.trapLane.poke(1)

        dut.io.events(0).valid.expect(true)
        dut.io.events(0).order.expect(0)
        dut.io.events(0).trap.expect(false)
        dut.io.events(1).valid.expect(true)
        dut.io.events(1).order.expect(1)
        dut.io.events(1).pc.expect(BigInt("80000024", 16))
        dut.io.events(1).instruction.expect(BigInt("ffffffff", 16))
        dut.io.events(1).gprWrite.expect(false)
        dut.io.events(1).trap.expect(true)
        dut.io.events(1).interrupt.expect(false)
        dut.io.events(1).cause.expect(2)
        dut.io.events(1).trapValue.expect(BigInt("ffffffff", 16))
      }
    }

    it("formats an interrupt at the real head entry without a retirement") {
      simulate(new RetireTraceFormatter) { dut =>
        clearInputs(dut)
        dut.io.trapCommit.valid.poke(true)
        dut.io.trapCommit.bits.interrupt.poke(true)
        dut.io.trapCommit.bits.cause.poke(11)
        dut.io.trapCommit.bits.trapValue.poke(0)
        driveTrapEntry(dut, BigInt("80000040", 16), BigInt("00000013", 16))
        dut.io.trapLane.poke(0)

        dut.io.events(0).valid.expect(true)
        dut.io.events(0).pc.expect(BigInt("80000040", 16))
        dut.io.events(0).trap.expect(true)
        dut.io.events(0).interrupt.expect(true)
        dut.io.events(0).cause.expect(BigInt("8000000b", 16))
        dut.io.events(1).valid.expect(false)
      }
    }
  }
}
