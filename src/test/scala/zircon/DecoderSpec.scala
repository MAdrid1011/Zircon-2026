package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, UopClass}
import zircon.frontend.{IntOperation, RV32IDecoder}

class DecoderSpec extends AnyFunSpec with ChiselSim {
  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    BigInt((funct7.toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | (rd.toLong << 7) | 0x33L)

  private def iType(immediate: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    BigInt(((immediate & 0xfff).toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | (rd.toLong << 7) | opcode.toLong)

  private def sType(immediate: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    val value = immediate & 0xfff
    BigInt(((value >> 5).toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (funct3.toLong << 12) | ((value & 0x1f).toLong << 7) | 0x23L)
  }

  private def bType(immediate: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    val value = immediate & 0x1fff
    BigInt((((value >> 12) & 1).toLong << 31) | (((value >> 5) & 0x3f).toLong << 25) |
      (rs2.toLong << 20) | (rs1.toLong << 15) | (funct3.toLong << 12) |
      (((value >> 1) & 0xf).toLong << 8) | (((value >> 11) & 1).toLong << 7) | 0x63L)
  }

  describe("RV32IDecoder") {
    it("decodes every base register and immediate ALU operation") {
      simulate(new RV32IDecoder) { dut =>
        val registerOperations = Seq(
          (0x00, 0, IntOperation.Add), (0x20, 0, IntOperation.Sub),
          (0x00, 1, IntOperation.Sll), (0x00, 2, IntOperation.Slt),
          (0x00, 3, IntOperation.Sltu), (0x00, 4, IntOperation.Xor),
          (0x00, 5, IntOperation.Srl), (0x20, 5, IntOperation.Sra),
          (0x00, 6, IntOperation.Or), (0x00, 7, IntOperation.And)
        )
        registerOperations.foreach { case (funct7, funct3, operation) =>
          dut.io.instruction.poke(rType(funct7, 2, 1, funct3, 3))
          dut.io.decoded.legal.expect(true)
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.IntegerSimple)
          dut.io.decoded.readsRs1.expect(true)
          dut.io.decoded.readsRs2.expect(true)
          dut.io.decoded.writesRd.expect(true)
        }

        val immediateOperations = Seq(
          (0x000, 0, IntOperation.Add), (0xfff, 0, IntOperation.Add),
          (0xfff, 2, IntOperation.Slt), (0xfff, 3, IntOperation.Sltu),
          (0x555, 4, IntOperation.Xor), (0x555, 6, IntOperation.Or),
          (0x555, 7, IntOperation.And), (0x01f, 1, IntOperation.Sll),
          (0x01f, 5, IntOperation.Srl), (0x41f, 5, IntOperation.Sra)
        )
        immediateOperations.foreach { case (immediate, funct3, operation) =>
          dut.io.instruction.poke(iType(immediate, 1, funct3, 3, 0x13))
          dut.io.decoded.legal.expect(true)
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.operandBImmediate.expect(true)
          dut.io.decoded.readsRs2.expect(false)
        }

        dut.io.instruction.poke(iType(0xfff, 1, 0, 3, 0x13))
        dut.io.decoded.immediate.expect(BigInt("ffffffff", 16))
      }
    }

    it("maps control, memory, fence, and CSR instructions to their sole legal endpoints") {
      simulate(new RV32IDecoder) { dut =>
        val branches = Seq(
          0 -> IntOperation.Beq, 1 -> IntOperation.Bne, 4 -> IntOperation.Blt,
          5 -> IntOperation.Bge, 6 -> IntOperation.Bltu, 7 -> IntOperation.Bgeu
        )
        branches.foreach { case (funct3, operation) =>
          dut.io.instruction.poke(bType(-4096, 2, 1, funct3))
          dut.io.decoded.legal.expect(true)
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.E0)
          dut.io.decoded.immediate.expect(BigInt("fffff000", 16))
          dut.io.decoded.isControl.expect(true)
        }

        val loads = Seq(0 -> IntOperation.Lb, 1 -> IntOperation.Lh, 2 -> IntOperation.Lw,
          4 -> IntOperation.Lbu, 5 -> IntOperation.Lhu)
        loads.foreach { case (funct3, operation) =>
          dut.io.instruction.poke(iType(-2048, 1, funct3, 3, 0x03))
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.CacheableLoadCandidate)
          dut.io.decoded.uopClass.expect(UopClass.Load)
          dut.io.decoded.isMemory.expect(true)
        }

        Seq(0 -> IntOperation.Sb, 1 -> IntOperation.Sh, 2 -> IntOperation.Sw).foreach {
          case (funct3, operation) =>
            dut.io.instruction.poke(sType(2047, 2, 1, funct3))
            dut.io.decoded.operation.expect(operation)
            dut.io.decoded.allowedEndpoints.expect(EndpointMask.M0)
            dut.io.decoded.readsRs2.expect(true)
        }

        val system = Seq(
          BigInt("0ff0000f", 16) -> IntOperation.Fence,
          BigInt("00000073", 16) -> IntOperation.Ecall,
          BigInt("00100073", 16) -> IntOperation.Ebreak,
          BigInt("30200073", 16) -> IntOperation.Mret,
          BigInt("10500073", 16) -> IntOperation.Wfi,
          BigInt("0000100f", 16) -> IntOperation.FenceI
        )
        system.foreach { case (instruction, operation) =>
          dut.io.instruction.poke(instruction)
          dut.io.decoded.legal.expect(true)
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.E0)
        }

        dut.io.instruction.poke(iType(-1, 1, 0, 3, 0x67))
        dut.io.decoded.legal.expect(true)
        dut.io.decoded.operation.expect(IntOperation.Jalr)
        dut.io.decoded.immediate.expect(BigInt("ffffffff", 16))
        dut.io.decoded.allowedEndpoints.expect(EndpointMask.E0)

        val csrOperations = Seq(1 -> IntOperation.Csrrw, 2 -> IntOperation.Csrrs,
          3 -> IntOperation.Csrrc, 5 -> IntOperation.Csrrwi,
          6 -> IntOperation.Csrrsi, 7 -> IntOperation.Csrrci)
        csrOperations.foreach { case (funct3, operation) =>
          dut.io.instruction.poke(iType(0x300, 1, funct3, 3, 0x73))
          dut.io.decoded.legal.expect(true)
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.uopClass.expect(UopClass.Csr)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.E0)
          dut.io.decoded.csrAddress.expect(0x300)
        }
      }
    }

    it("maps all RV32M OP encodings exclusively to E2") {
      simulate(new RV32IDecoder) { dut =>
        val operations = Seq(
          0 -> (IntOperation.Mul, UopClass.Multiply),
          1 -> (IntOperation.Mulh, UopClass.Multiply),
          2 -> (IntOperation.Mulhsu, UopClass.Multiply),
          3 -> (IntOperation.Mulhu, UopClass.Multiply),
          4 -> (IntOperation.Div, UopClass.Divide),
          5 -> (IntOperation.Divu, UopClass.Divide),
          6 -> (IntOperation.Rem, UopClass.Divide),
          7 -> (IntOperation.Remu, UopClass.Divide)
        )
        operations.foreach { case (funct3, (operation, uopClass)) =>
          dut.io.instruction.poke(rType(0x01, 2, 1, funct3, 3))
          dut.io.decoded.legal.expect(true)
          dut.io.decoded.operation.expect(operation)
          dut.io.decoded.uopClass.expect(uopClass)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.E2)
          dut.io.decoded.readsRs1.expect(true)
          dut.io.decoded.readsRs2.expect(true)
          dut.io.decoded.writesRd.expect(true)
        }
      }
    }

    it("rejects reserved encodings and extensions that are not yet implemented") {
      simulate(new RV32IDecoder) { dut =>
        val illegal = Seq(
          BigInt(0),
          rType(0x02, 2, 1, 0, 3), // unsupported OP funct7
          iType(0x401, 1, 1, 3, 0x13), // reserved SLLI funct7
          iType(0, 1, 1, 3, 0x67), // JALR funct3 must be zero
          iType(0, 1, 3, 3, 0x03), // reserved load width
          sType(0, 2, 1, 3), // reserved store width
          BigInt("00200073", 16), // unknown SYSTEM immediate
          BigInt("00004073", 16) // reserved CSR funct3
        )
        illegal.foreach { instruction =>
          dut.io.instruction.poke(instruction)
          dut.io.decoded.legal.expect(false)
          dut.io.decoded.allowedEndpoints.expect(EndpointMask.None)
          dut.io.decoded.writesRd.expect(false)
        }

        // FENCE and FENCE.I reserved operands are ignored for forward compatibility.
        dut.io.instruction.poke(BigInt("ffff8f8f", 16))
        dut.io.decoded.operation.expect(IntOperation.Fence)
        dut.io.decoded.legal.expect(true)
        dut.io.instruction.poke(BigInt("fff0908f", 16))
        dut.io.decoded.operation.expect(IntOperation.FenceI)
        dut.io.decoded.legal.expect(true)
      }
    }

    it("extracts U and jump immediates at both sign boundaries") {
      simulate(new RV32IDecoder) { dut =>
        dut.io.instruction.poke(BigInt("800001b7", 16)) // LUI x3, 0x80000
        dut.io.decoded.operation.expect(IntOperation.Lui)
        dut.io.decoded.immediate.expect(BigInt("80000000", 16))

        dut.io.instruction.poke(BigInt("7ffff197", 16)) // AUIPC x3, 0x7ffff
        dut.io.decoded.operation.expect(IntOperation.Auipc)
        dut.io.decoded.immediate.expect(BigInt("7ffff000", 16))

        dut.io.instruction.poke(BigInt("008000ef", 16)) // JAL x1, +8
        dut.io.decoded.operation.expect(IntOperation.Jal)
        dut.io.decoded.immediate.expect(8)
        dut.io.decoded.rd.expect(1)
      }
    }
  }
}
