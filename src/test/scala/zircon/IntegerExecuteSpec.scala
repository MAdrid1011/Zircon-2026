package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.IntegerExecute
import zircon.frontend.IntOperation

class IntegerExecuteSpec extends AnyFunSpec with ChiselSim {
  describe("IntegerExecute") {
    it("implements wraparound arithmetic, logic, shifts, and signed comparisons") {
      simulate(new IntegerExecute) { dut =>
        def check(operation: IntOperation.Type, lhs: BigInt, rhs: BigInt, expected: BigInt): Unit = {
          dut.io.request.operation.poke(operation)
          dut.io.request.lhs.poke(lhs)
          dut.io.request.rhs.poke(rhs)
          dut.io.request.pc.poke(BigInt("80000000", 16))
          dut.io.request.immediate.poke(0)
          dut.io.response.result.expect(expected & BigInt("ffffffff", 16))
          dut.io.response.controlValid.expect(false)
        }

        check(IntOperation.Add, BigInt("ffffffff", 16), 1, 0)
        check(IntOperation.Sub, 0, 1, BigInt("ffffffff", 16))
        check(IntOperation.Sll, 1, 31, BigInt("80000000", 16))
        check(IntOperation.Sll, 1, 63, BigInt("80000000", 16))
        check(IntOperation.Srl, BigInt("80000000", 16), 31, 1)
        check(IntOperation.Sra, BigInt("80000000", 16), 31, BigInt("ffffffff", 16))
        check(IntOperation.Slt, BigInt("80000000", 16), 0, 1)
        check(IntOperation.Sltu, BigInt("80000000", 16), 0, 0)
        check(IntOperation.Xor, BigInt("aa55aa55", 16), BigInt("ffff0000", 16), BigInt("55aaaa55", 16))
        check(IntOperation.Or, BigInt("aa550000", 16), BigInt("000055aa", 16), BigInt("aa5555aa", 16))
        check(IntOperation.And, BigInt("aa55aa55", 16), BigInt("0f0f0f0f", 16), BigInt("0a050a05", 16))
      }
    }

    it("computes upper-immediate, memory address, and jump results") {
      simulate(new IntegerExecute) { dut =>
        dut.io.request.operation.poke(IntOperation.Lui)
        dut.io.request.lhs.poke(0)
        dut.io.request.rhs.poke(0)
        dut.io.request.pc.poke(BigInt("80000000", 16))
        dut.io.request.immediate.poke(BigInt("12345000", 16))
        dut.io.response.result.expect(BigInt("12345000", 16))

        dut.io.request.operation.poke(IntOperation.Auipc)
        dut.io.response.result.expect(BigInt("92345000", 16))

        dut.io.request.operation.poke(IntOperation.Lw)
        dut.io.request.lhs.poke(BigInt("80001000", 16))
        dut.io.request.immediate.poke(BigInt("fffffff0", 16))
        dut.io.response.result.expect(BigInt("80000ff0", 16))

        dut.io.request.operation.poke(IntOperation.Jal)
        dut.io.request.pc.poke(BigInt("80000000", 16))
        dut.io.request.immediate.poke(8)
        dut.io.response.result.expect(BigInt("80000004", 16))
        dut.io.response.controlTaken.expect(true)
        dut.io.response.controlTarget.expect(BigInt("80000008", 16))
        dut.io.response.nextPc.expect(BigInt("80000008", 16))

        dut.io.request.operation.poke(IntOperation.Jalr)
        dut.io.request.lhs.poke(BigInt("80001001", 16))
        dut.io.request.immediate.poke(0)
        dut.io.response.controlTarget.expect(BigInt("80001000", 16))
        dut.io.response.instructionAddressMisaligned.expect(false)
      }
    }

    it("covers every branch relation and only faults a taken misaligned target") {
      simulate(new IntegerExecute) { dut =>
        dut.io.request.pc.poke(BigInt("80000000", 16))
        dut.io.request.immediate.poke(2)

        val cases = Seq(
          (IntOperation.Beq, BigInt(7), BigInt(7), true),
          (IntOperation.Bne, BigInt(7), BigInt(8), true),
          (IntOperation.Blt, BigInt("ffffffff", 16), BigInt(0), true),
          (IntOperation.Bge, BigInt(0), BigInt("ffffffff", 16), true),
          (IntOperation.Bltu, BigInt(0), BigInt("ffffffff", 16), true),
          (IntOperation.Bgeu, BigInt("ffffffff", 16), BigInt(0), true),
          (IntOperation.Beq, BigInt(7), BigInt(8), false)
        )
        cases.foreach { case (operation, lhs, rhs, taken) =>
          dut.io.request.operation.poke(operation)
          dut.io.request.lhs.poke(lhs)
          dut.io.request.rhs.poke(rhs)
          dut.io.response.controlValid.expect(true)
          dut.io.response.controlTaken.expect(taken)
          dut.io.response.instructionAddressMisaligned.expect(taken)
          dut.io.response.nextPc.expect(if (taken) BigInt("80000002", 16) else BigInt("80000004", 16))
        }
      }
    }
  }
}
