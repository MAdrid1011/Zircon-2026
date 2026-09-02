package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.{FloatingOperation, RV32FMetadataDecoder, RV32IDecoder}

class FloatingDecoderSpec extends AnyFunSpec with ChiselSim {
  private def iType(immediate: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    BigInt(((immediate & 0xfff).toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | (rd.toLong << 7) | opcode.toLong)

  private def sType(immediate: Int, rs2: Int, rs1: Int, funct3: Int, opcode: Int): BigInt = {
    val value = immediate & 0xfff
    BigInt(((value >> 5).toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | ((value & 0x1f).toLong << 7) | opcode.toLong)
  }

  private def opFp(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    BigInt((funct7.toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | (rd.toLong << 7) | 0x53L)

  private def fma(opcode: Int, rs3: Int, rs2: Int, rs1: Int, rm: Int, rd: Int): BigInt =
    BigInt((rs3.toLong << 27) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (rm.toLong << 12) | (rd.toLong << 7) | opcode.toLong)

  describe("RV32FMetadataDecoder") {
    it("decodes FLW and FSW with distinct integer and floating namespaces") {
      simulate(new RV32FMetadataDecoder) { dut =>
        dut.io.instruction.poke(iType(-16, rs1 = 8, funct3 = 2, rd = 4, opcode = 0x07))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.Flw)
        dut.io.decoded.readsIntegerRs1.expect(true)
        dut.io.decoded.readsFloatRs1.expect(false)
        dut.io.decoded.writesFloatRd.expect(true)
        dut.io.decoded.writesIntegerRd.expect(false)
        dut.io.decoded.isMemory.expect(true)
        dut.io.decoded.memoryWrite.expect(false)

        dut.io.instruction.poke(sType(20, rs2 = 5, rs1 = 9, funct3 = 2, opcode = 0x27))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.Fsw)
        dut.io.decoded.readsIntegerRs1.expect(true)
        dut.io.decoded.readsFloatRs2.expect(true)
        dut.io.decoded.writesFloatRd.expect(false)
        dut.io.decoded.isMemory.expect(true)
        dut.io.decoded.memoryWrite.expect(true)
      }
    }

    it("decodes arithmetic, fused sources, and dynamic rounding") {
      simulate(new RV32FMetadataDecoder) { dut =>
        dut.io.instruction.poke(opFp(0x00, rs2 = 3, rs1 = 2, funct3 = 7, rd = 1))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FaddS)
        dut.io.decoded.readsFloatRs1.expect(true)
        dut.io.decoded.readsFloatRs2.expect(true)
        dut.io.decoded.writesFloatRd.expect(true)
        dut.io.decoded.usesRoundingMode.expect(true)
        dut.io.decoded.dynamicRounding.expect(true)

        dut.io.instruction.poke(fma(0x43, rs3 = 6, rs2 = 5, rs1 = 4, rm = 0, rd = 3))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FmaddS)
        dut.io.decoded.readsFloatRs1.expect(true)
        dut.io.decoded.readsFloatRs2.expect(true)
        dut.io.decoded.readsFloatRs3.expect(true)
        dut.io.decoded.writesFloatRd.expect(true)

        dut.io.instruction.poke(opFp(0x2c, rs2 = 0, rs1 = 7, funct3 = 4, rd = 8))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FsqrtS)
        dut.io.decoded.readsFloatRs2.expect(false)
      }
    }

    it("decodes conversions, comparisons, and moves into their architectural namespaces") {
      simulate(new RV32FMetadataDecoder) { dut =>
        dut.io.instruction.poke(opFp(0x60, rs2 = 1, rs1 = 10, funct3 = 1, rd = 11))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FcvtWuS)
        dut.io.decoded.readsFloatRs1.expect(true)
        dut.io.decoded.writesIntegerRd.expect(true)

        dut.io.instruction.poke(opFp(0x68, rs2 = 0, rs1 = 12, funct3 = 2, rd = 13))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FcvtSW)
        dut.io.decoded.readsIntegerRs1.expect(true)
        dut.io.decoded.writesFloatRd.expect(true)

        dut.io.instruction.poke(opFp(0x50, rs2 = 2, rs1 = 1, funct3 = 2, rd = 14))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FeqS)
        dut.io.decoded.readsFloatRs1.expect(true)
        dut.io.decoded.readsFloatRs2.expect(true)
        dut.io.decoded.writesIntegerRd.expect(true)

        dut.io.instruction.poke(opFp(0x78, rs2 = 0, rs1 = 15, funct3 = 0, rd = 16))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(FloatingOperation.FmvWX)
        dut.io.decoded.readsIntegerRs1.expect(true)
        dut.io.decoded.writesFloatRd.expect(true)
      }
    }

    it("rejects reserved encodings and leaves the executable integer decoder unchanged") {
      val faddReservedRm = opFp(0x00, rs2 = 2, rs1 = 1, funct3 = 5, rd = 3)
      val faddReservedRm6 = opFp(0x00, rs2 = 2, rs1 = 1, funct3 = 6, rd = 3)
      simulate(new RV32FMetadataDecoder) { dut =>
        dut.io.instruction.poke(faddReservedRm)
        dut.io.decoded.legal.expect(false)
        dut.io.instruction.poke(faddReservedRm6)
        dut.io.decoded.legal.expect(false)
        dut.io.instruction.poke(opFp(0x2c, rs2 = 1, rs1 = 7, funct3 = 0, rd = 8))
        dut.io.decoded.legal.expect(false)
        dut.io.instruction.poke(fma(0x43, rs3 = 6, rs2 = 5, rs1 = 4, rm = 0, rd = 3) |
          BigInt(1L << 25)) // fmt=D, outside RV32F
        dut.io.decoded.legal.expect(false)
      }
      simulate(new RV32IDecoder) { dut =>
        dut.io.instruction.poke(faddReservedRm - BigInt(5L << 12) + BigInt(0L << 12))
        dut.io.decoded.legal.expect(false)
      }
    }
  }
}
