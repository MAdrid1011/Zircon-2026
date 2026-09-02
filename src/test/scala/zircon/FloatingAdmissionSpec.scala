package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.{FloatingAdmission, FloatingOperation}

class FloatingAdmissionSpec extends AnyFunSpec with ChiselSim {
  private def opFp(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    BigInt((funct7.toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | (rd.toLong << 7) | 0x53L)

  describe("FloatingAdmission") {
    it("admits only the documented bit-move/sign subset when FS is enabled") {
      simulate(new FloatingAdmission) { dut =>
        val fmvwx = opFp(0x78, rs2 = 0, rs1 = 4, funct3 = 0, rd = 7)
        dut.io.instruction.poke(fmvwx)
        dut.io.mstatusFs.poke(0)
        dut.io.floatingOpcode.expect(true)
        dut.io.live.expect(false)
        dut.io.illegal.expect(true)

        dut.io.mstatusFs.poke(1)
        dut.io.live.expect(true)
        dut.io.illegal.expect(false)
        dut.io.decoded.operation.expect(FloatingOperation.FmvWX)

        dut.io.instruction.poke(opFp(0x10, rs2 = 2, rs1 = 1, funct3 = 2, rd = 3))
        dut.io.live.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FsgnjxS)

        dut.io.instruction.poke(opFp(0x14, rs2 = 2, rs1 = 1, funct3 = 0, rd = 3))
        dut.io.live.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FminS)

        dut.io.instruction.poke(opFp(0x70, rs2 = 0, rs1 = 1, funct3 = 1, rd = 3))
        dut.io.live.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FclassS)
      }
    }

    it("keeps unsupported, reserved, and non-F encodings outside the live path") {
      simulate(new FloatingAdmission) { dut =>
        dut.io.mstatusFs.poke(3)
        dut.io.instruction.poke(opFp(0x00, rs2 = 2, rs1 = 1, funct3 = 0, rd = 3))
        dut.io.floatingOpcode.expect(true)
        dut.io.live.expect(false)
        dut.io.illegal.expect(true)

        dut.io.instruction.poke(opFp(0x00, rs2 = 2, rs1 = 1, funct3 = 5, rd = 3))
        dut.io.floatingOpcode.expect(true)
        dut.io.live.expect(false)
        dut.io.illegal.expect(true)

        dut.io.instruction.poke(BigInt("00100093", 16))
        dut.io.floatingOpcode.expect(false)
        dut.io.live.expect(false)
        dut.io.illegal.expect(false)
      }
    }
  }
}
