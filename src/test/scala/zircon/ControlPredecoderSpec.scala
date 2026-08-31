package zircon

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.{RV32ControlPredecoder, RV32IDecoder}

class ControlPredecodeConsistencyHarness extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(32.W))
    val instruction = Input(UInt(32.W))
    val controlMatches = Output(Bool())
    val targetMatches = Output(Bool())
  })

  val predecoder = Module(new RV32ControlPredecoder)
  val decoder = Module(new RV32IDecoder)
  predecoder.io.pc := io.pc
  predecoder.io.instruction := io.instruction
  decoder.io.instruction := io.instruction

  val decodedControl = decoder.io.decoded.legal &&
    decoder.io.decoded.isControl
  io.controlMatches := predecoder.io.predecode.control === decodedControl
  io.targetMatches := !predecoder.io.predecode.direct ||
    predecoder.io.predecode.directTarget ===
      (io.pc +% decoder.io.decoded.immediate)
}

class ControlPredecoderSpec extends AnyFunSpec with ChiselSim {
  private def branch(offset: Int, funct3: Int): BigInt = {
    val immediate = offset & 0x1fff
    (BigInt((immediate >> 12) & 1) << 31) |
      (BigInt((immediate >> 5) & 0x3f) << 25) |
      (BigInt(2) << 20) | (BigInt(1) << 15) |
      (BigInt(funct3) << 12) |
      (BigInt((immediate >> 1) & 0xf) << 8) |
      (BigInt((immediate >> 11) & 1) << 7) | 0x63
  }

  private def jal(offset: Int, rd: Int): BigInt = {
    val immediate = offset & 0x1fffff
    (BigInt((immediate >> 20) & 1) << 31) |
      (BigInt((immediate >> 1) & 0x3ff) << 21) |
      (BigInt((immediate >> 11) & 1) << 20) |
      (BigInt((immediate >> 12) & 0xff) << 12) |
      (BigInt(rd) << 7) | 0x6f
  }

  private def jalr(rd: Int, rs1: Int, funct3: Int = 0): BigInt =
    (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | 0x67

  describe("RV32ControlPredecoder") {
    it("recognizes only the six legal conditional branch funct3 values") {
      simulate(new RV32ControlPredecoder) { dut =>
        dut.io.pc.poke(0x1000)
        for (funct3 <- Seq(0, 1, 4, 5, 6, 7)) {
          dut.io.instruction.poke(branch(16, funct3))
          dut.io.predecode.control.expect(true)
          dut.io.predecode.conditional.expect(true)
          dut.io.predecode.direct.expect(true)
          dut.io.predecode.directTarget.expect(0x1010)
        }
        for (funct3 <- Seq(2, 3)) {
          dut.io.instruction.poke(branch(16, funct3))
          dut.io.predecode.control.expect(false)
        }
      }
    }

    it("sign-extends B and J immediates at their encoding boundaries") {
      simulate(new RV32ControlPredecoder) { dut =>
        dut.io.pc.poke(BigInt("80001000", 16))
        dut.io.instruction.poke(branch(-4096, 0))
        dut.io.predecode.directTarget.expect(BigInt("80000000", 16))
        dut.io.instruction.poke(branch(4094, 0))
        dut.io.predecode.directTarget.expect(BigInt("80001ffe", 16))

        dut.io.instruction.poke(jal(-1048576, 0))
        dut.io.predecode.directTarget.expect(BigInt("7ff01000", 16))
        dut.io.instruction.poke(jal(1048574, 0))
        dut.io.predecode.directTarget.expect(BigInt("80100ffe", 16))
      }
    }

    it("marks JAL link registers as pushes without inventing a pop") {
      simulate(new RV32ControlPredecoder) { dut =>
        dut.io.pc.poke(0x1000)
        for (rd <- Seq(1, 5)) {
          dut.io.instruction.poke(jal(4, rd))
          dut.io.predecode.control.expect(true)
          dut.io.predecode.call.expect(true)
          dut.io.predecode.ret.expect(false)
        }
        dut.io.instruction.poke(jal(4, 0))
        dut.io.predecode.call.expect(false)
        dut.io.predecode.ret.expect(false)
      }
    }

    it("implements every x1/x5 JALR RAS hint combination") {
      simulate(new RV32ControlPredecoder) { dut =>
        dut.io.pc.poke(0x1000)
        val cases = Seq(
          (0, 2, false, false),
          (0, 1, false, true),
          (1, 2, true, false),
          (1, 1, true, false),
          (5, 5, true, false),
          (1, 5, true, true),
          (5, 1, true, true)
        )
        for ((rd, rs1, push, pop) <- cases) {
          dut.io.instruction.poke(jalr(rd, rs1))
          dut.io.predecode.control.expect(true)
          dut.io.predecode.indirect.expect(true)
          dut.io.predecode.call.expect(push)
          dut.io.predecode.ret.expect(pop)
        }
      }
    }

    it("rejects reserved JALR funct3 and ordinary instructions") {
      simulate(new RV32ControlPredecoder) { dut =>
        dut.io.pc.poke(0x1000)
        dut.io.instruction.poke(jalr(1, 1, funct3 = 1))
        dut.io.predecode.control.expect(false)
        dut.io.instruction.poke(BigInt("00108093", 16))
        dut.io.predecode.control.expect(false)
      }
    }

    it("stays consistent with the full decoder over control encoding classes") {
      simulate(new ControlPredecodeConsistencyHarness) { dut =>
        dut.io.pc.poke(BigInt("8ffff000", 16))
        for {
          funct3 <- 0 until 8
          offset <- Seq(-4096, -2, 0, 2, 4094)
        } {
          dut.io.instruction.poke(branch(offset, funct3))
          dut.io.controlMatches.expect(true)
          dut.io.targetMatches.expect(true)
        }
        for {
          rd <- Seq(0, 1, 5, 31)
          offset <- Seq(-1048576, -2, 0, 2, 1048574)
        } {
          dut.io.instruction.poke(jal(offset, rd))
          dut.io.controlMatches.expect(true)
          dut.io.targetMatches.expect(true)
        }
        for {
          funct3 <- 0 until 8
          rd <- Seq(0, 1, 2, 5)
          rs1 <- Seq(0, 1, 2, 5)
        } {
          dut.io.instruction.poke(jalr(rd, rs1, funct3))
          dut.io.controlMatches.expect(true)
          dut.io.targetMatches.expect(true)
        }
      }
    }
  }
}
