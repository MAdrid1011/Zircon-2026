package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{CSRInstructionUnit, MachineCSRAddress, MachineCSRFile, MachineInterruptCause}
import zircon.frontend.IntOperation

class MachineCSRSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: MachineCSRFile): Unit = {
    dut.io.access.address.poke(0)
    dut.io.access.write.poke(false)
    dut.io.commitWrite.valid.poke(false)
    dut.io.commitWrite.bits.address.poke(0)
    dut.io.commitWrite.bits.data.poke(0)
    dut.io.trapCommit.valid.poke(false)
    dut.io.trapCommit.bits.interrupt.poke(false)
    dut.io.trapCommit.bits.cause.poke(0)
    dut.io.trapCommit.bits.exceptionPc.poke(0)
    dut.io.trapCommit.bits.trapValue.poke(0)
    dut.io.mretCommit.poke(false)
    dut.io.retiredInstructions.poke(0)
    dut.io.fpCommit.valid.poke(false)
    dut.io.fpCommit.bits.flags.poke(0)
    dut.io.fpCommit.bits.dirty.poke(false)
    dut.io.interrupts.meip.poke(false)
    dut.io.interrupts.msip.poke(false)
    dut.io.interrupts.mtip.poke(false)
  }

  private def writeCSR(dut: MachineCSRFile, address: Int, data: BigInt): Unit = {
    dut.io.commitWrite.valid.poke(true)
    dut.io.commitWrite.bits.address.poke(address)
    dut.io.commitWrite.bits.data.poke(data)
    dut.clock.step()
    dut.io.commitWrite.valid.poke(false)
  }

  private def expectRead(
      dut: MachineCSRFile,
      address: Int,
      expected: BigInt,
      legal: Boolean = true,
      write: Boolean = false
  ): Unit = {
    dut.io.access.address.poke(address)
    dut.io.access.write.poke(write)
    dut.io.accessLegal.expect(legal)
    dut.io.accessData.expect(expected)
  }

  describe("MachineCSRFile") {
    it("reports the frozen identity and applies write legality by CSR encoding") {
      val cfg = ZirconCoreConfig(hartId = 7)
      simulate(new MachineCSRFile(cfg)) { dut =>
        clearInputs(dut)
        expectRead(dut, MachineCSRAddress.Mstatus, BigInt("00001800", 16))
        expectRead(dut, MachineCSRAddress.Misa, BigInt("40001121", 16))
        expectRead(dut, MachineCSRAddress.Mhartid, 7)
        expectRead(dut, MachineCSRAddress.Mvendorid, 0)
        expectRead(dut, MachineCSRAddress.Misa, BigInt("40001121", 16), legal = true, write = true)
        writeCSR(dut, MachineCSRAddress.Misa, 0)
        expectRead(dut, MachineCSRAddress.Misa, BigInt("40001121", 16))
        expectRead(dut, MachineCSRAddress.Mhartid, 7, legal = false, write = true)
        expectRead(dut, MachineCSRAddress.Mip, 0, legal = true, write = true)
        expectRead(dut, 0x306, 0, legal = false)
        expectRead(dut, MachineCSRAddress.Fcsr, 0, legal = false)
      }
    }

    it("applies mstatus WARL fields and accumulates floating state") {
      simulate(new MachineCSRFile) { dut =>
        clearInputs(dut)
        writeCSR(dut, MachineCSRAddress.Mstatus, BigInt("ffffffff", 16))
        expectRead(dut, MachineCSRAddress.Mstatus, BigInt("80007888", 16))

        writeCSR(dut, MachineCSRAddress.Mstatus, BigInt("00002000", 16))
        expectRead(dut, MachineCSRAddress.Fcsr, 0)
        writeCSR(dut, MachineCSRAddress.Fflags, 3)
        expectRead(dut, MachineCSRAddress.Fflags, 3)
        expectRead(dut, MachineCSRAddress.Mstatus, BigInt("80007800", 16))

        dut.io.fpCommit.valid.poke(true)
        dut.io.fpCommit.bits.flags.poke(4)
        dut.io.fpCommit.bits.dirty.poke(false)
        dut.clock.step()
        dut.io.fpCommit.valid.poke(false)
        expectRead(dut, MachineCSRAddress.Fflags, 7)

        writeCSR(dut, MachineCSRAddress.Frm, 5)
        expectRead(dut, MachineCSRAddress.Frm, 5)
        expectRead(dut, MachineCSRAddress.Fcsr, BigInt("000000a7", 16))
      }
    }

    it("selects enabled interrupts in MEI then MSI then MTI priority") {
      simulate(new MachineCSRFile) { dut =>
        clearInputs(dut)
        writeCSR(dut, MachineCSRAddress.Mstatus, 1 << 3)
        writeCSR(dut, MachineCSRAddress.Mie, (1 << 11) | (1 << 3) | (1 << 7))

        dut.io.interrupts.meip.poke(true)
        dut.io.interrupts.msip.poke(true)
        dut.io.interrupts.mtip.poke(true)
        dut.io.eligibleInterrupt.valid.expect(true)
        dut.io.eligibleInterrupt.cause.expect(MachineInterruptCause.External)
        expectRead(dut, MachineCSRAddress.Mip, (1 << 11) | (1 << 3) | (1 << 7))

        dut.io.interrupts.meip.poke(false)
        dut.io.eligibleInterrupt.cause.expect(MachineInterruptCause.Software)
        dut.io.interrupts.msip.poke(false)
        dut.io.eligibleInterrupt.cause.expect(MachineInterruptCause.Timer)

        writeCSR(dut, MachineCSRAddress.Mstatus, 0)
        dut.io.eligibleInterrupt.valid.expect(false)
      }
    }

    it("enters Direct or Vectored traps precisely and restores MIE on MRET") {
      simulate(new MachineCSRFile) { dut =>
        clearInputs(dut)
        writeCSR(dut, MachineCSRAddress.Mstatus, 1 << 3)
        writeCSR(dut, MachineCSRAddress.Mtvec, BigInt("80000101", 16))

        dut.io.trapCommit.valid.poke(true)
        dut.io.trapCommit.bits.interrupt.poke(true)
        dut.io.trapCommit.bits.cause.poke(MachineInterruptCause.External)
        dut.io.trapCommit.bits.exceptionPc.poke(BigInt("80000002", 16))
        dut.io.trapCommit.bits.trapValue.poke(BigInt("deadbeef", 16))
        dut.io.trapTarget.expect(BigInt("8000012c", 16))
        dut.clock.step()
        dut.io.trapCommit.valid.poke(false)

        expectRead(dut, MachineCSRAddress.Mepc, BigInt("80000000", 16))
        expectRead(dut, MachineCSRAddress.Mcause, BigInt("8000000b", 16))
        expectRead(dut, MachineCSRAddress.Mtval, BigInt("deadbeef", 16))
        expectRead(dut, MachineCSRAddress.Mstatus, BigInt("00001880", 16))
        dut.io.mretTarget.expect(BigInt("80000000", 16))

        dut.io.mretCommit.poke(true)
        dut.clock.step()
        dut.io.mretCommit.poke(false)
        expectRead(dut, MachineCSRAddress.Mstatus, BigInt("00001888", 16))

        dut.io.trapCommit.bits.interrupt.poke(false)
        dut.io.trapCommit.bits.cause.poke(2)
        dut.io.trapTarget.expect(BigInt("80000100", 16))

        writeCSR(dut, MachineCSRAddress.Mtvec, BigInt("80000203", 16))
        expectRead(dut, MachineCSRAddress.Mtvec, BigInt("80000200", 16))
        writeCSR(dut, MachineCSRAddress.Mepc, BigInt("80000003", 16))
        expectRead(dut, MachineCSRAddress.Mepc, BigInt("80000000", 16))
      }
    }

    it("preserves 64-bit counter carries and gives explicit writes priority") {
      simulate(new MachineCSRFile) { dut =>
        clearInputs(dut)
        writeCSR(dut, MachineCSRAddress.Mcycleh, 0)
        writeCSR(dut, MachineCSRAddress.Mcycle, BigInt("ffffffff", 16))
        dut.clock.step()
        expectRead(dut, MachineCSRAddress.Mcycle, 0)
        expectRead(dut, MachineCSRAddress.Mcycleh, 1)

        dut.io.retiredInstructions.poke(2)
        dut.clock.step()
        expectRead(dut, MachineCSRAddress.Minstret, 2)

        dut.io.commitWrite.valid.poke(true)
        dut.io.commitWrite.bits.address.poke(MachineCSRAddress.Minstret)
        dut.io.commitWrite.bits.data.poke(BigInt("ffffffff", 16))
        dut.clock.step()
        dut.io.commitWrite.valid.poke(false)
        expectRead(dut, MachineCSRAddress.Minstret, BigInt("ffffffff", 16))

        dut.clock.step()
        expectRead(dut, MachineCSRAddress.Minstret, 1)
        expectRead(dut, MachineCSRAddress.Minstreth, 1)

        dut.io.retiredInstructions.poke(0)
        writeCSR(dut, MachineCSRAddress.Minstreth, BigInt("00001234", 16))
        expectRead(dut, MachineCSRAddress.Minstreth, BigInt("00001234", 16))
        expectRead(dut, MachineCSRAddress.Minstret, 1)
      }
    }
  }

  describe("CSRInstructionUnit") {
    it("implements all six Zicsr read-modify-write operations") {
      simulate(new CSRInstructionUnit) { dut =>
        def check(operation: IntOperation.Type, source: BigInt, expected: BigInt): Unit = {
          dut.io.request.operation.poke(operation)
          dut.io.request.source.poke(source)
          dut.io.request.currentValue.poke(BigInt("aa5500ff", 16))
          dut.io.request.accessLegal.poke(true)
          dut.io.request.writeIntent.poke(true)
          dut.io.response.illegal.expect(false)
          dut.io.response.readData.expect(BigInt("aa5500ff", 16))
          dut.io.response.writeValid.expect(true)
          dut.io.response.writeData.expect(expected)
        }

        check(IntOperation.Csrrw, BigInt("12345678", 16), BigInt("12345678", 16))
        check(IntOperation.Csrrwi, 31, 31)
        check(IntOperation.Csrrs, BigInt("00ff0f00", 16), BigInt("aaff0fff", 16))
        check(IntOperation.Csrrsi, 1, BigInt("aa5500ff", 16))
        check(IntOperation.Csrrc, BigInt("00ff0f00", 16), BigInt("aa0000ff", 16))
        check(IntOperation.Csrrci, 15, BigInt("aa5500f0", 16))
      }
    }

    it("uses architectural write intent rather than the runtime source value") {
      simulate(new CSRInstructionUnit) { dut =>
        dut.io.request.operation.poke(IntOperation.Csrrs)
        dut.io.request.source.poke(0)
        dut.io.request.currentValue.poke(BigInt("12345678", 16))
        dut.io.request.accessLegal.poke(true)
        dut.io.request.writeIntent.poke(true)
        dut.io.response.writeValid.expect(true)
        dut.io.response.writeData.expect(BigInt("12345678", 16))

        dut.io.request.writeIntent.poke(false)
        dut.io.response.writeValid.expect(false)

        dut.io.request.accessLegal.poke(false)
        dut.io.response.illegal.expect(true)
        dut.io.response.writeValid.expect(false)

        dut.io.request.operation.poke(IntOperation.Add)
        dut.io.request.accessLegal.poke(true)
        dut.io.response.illegal.expect(true)
      }
    }
  }
}
