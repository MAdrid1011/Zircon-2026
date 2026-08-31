package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, LongPipe, UopClass}
import zircon.frontend.IntOperation

class LongPipeSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: LongPipe): Unit = {
    dut.io.input.valid.poke(false)
    dut.io.completion.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(
      dut: LongPipe,
      tag: Int,
      operation: IntOperation.Type,
      lhs: BigInt,
      rhs: BigInt,
      destination: Int = 32
  ): Unit = {
    dut.io.input.valid.poke(true)
    dut.io.input.bits.uop.robTag.poke(tag)
    dut.io.input.bits.uop.allowedEndpoints.poke(EndpointMask.E2)
    dut.io.input.bits.uop.uopClass.poke(
      if (operation == IntOperation.Mul || operation == IntOperation.Mulh ||
        operation == IntOperation.Mulhsu || operation == IntOperation.Mulhu)
        UopClass.Multiply else UopClass.Divide)
    dut.io.input.bits.uop.operation.poke(operation.asUInt)
    dut.io.input.bits.uop.destinationPhysical.poke(destination)
    dut.io.input.bits.uop.writesInteger.poke(true)
    dut.io.input.bits.uop.writesFloat.poke(false)
    dut.io.input.bits.lhs.poke(lhs)
    dut.io.input.bits.rhs.poke(rhs)
  }

  private def waitForResult(dut: LongPipe, expected: BigInt): Unit = {
    for (_ <- 0 until 40 if !dut.io.completion.valid.peek().litToBoolean) dut.clock.step()
    dut.io.completion.valid.expect(true)
    dut.io.completion.bits.data.expect(expected)
  }

  describe("LongPipe") {
    it("computes signed, signed-unsigned, and unsigned product halves") {
      simulate(new LongPipe) { dut =>
        clear(dut)
        drive(dut, 1, IntOperation.Mul, BigInt("ffffffff", 16), 2)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, BigInt("fffffffe", 16))
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        drive(dut, 2, IntOperation.Mulh, BigInt("80000000", 16), 2)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, BigInt("ffffffff", 16))
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        drive(dut, 3, IntOperation.Mulhu, BigInt("ffffffff", 16), BigInt("ffffffff", 16))
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, BigInt("fffffffe", 16))
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        drive(dut, 4, IntOperation.Mulhsu, BigInt("fffffffe", 16), BigInt("ffffffff", 16))
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, BigInt("fffffffe", 16))
      }
    }

    it("implements divide and remainder zero-divisor and overflow rules") {
      simulate(new LongPipe) { dut =>
        clear(dut)
        drive(dut, 1, IntOperation.Div, BigInt("80000000", 16), BigInt("ffffffff", 16))
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, BigInt("80000000", 16))
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        drive(dut, 2, IntOperation.Rem, BigInt("80000000", 16), BigInt("ffffffff", 16))
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, 0)
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        drive(dut, 3, IntOperation.Divu, 7, 0)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, BigInt("ffffffff", 16))
        dut.io.completion.ready.poke(true)
        dut.clock.step()

        drive(dut, 4, IntOperation.Remu, 7, 0)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, 7)
      }
    }

    it("iterates normal signed and unsigned divide/remainder values") {
      simulate(new LongPipe) { dut =>
        clear(dut)
        val cases = Seq(
          (IntOperation.Div, BigInt("fffffff9", 16), 3, BigInt("fffffffe", 16)),
          (IntOperation.Rem, BigInt("fffffff9", 16), 3, BigInt("ffffffff", 16)),
          (IntOperation.Divu, BigInt("ffffffff", 16), 2, BigInt("7fffffff", 16)),
          (IntOperation.Remu, BigInt("ffffffff", 16), 2, BigInt(1))
        )
        cases.zipWithIndex.foreach { case ((operation, lhs, rhs, expected), index) =>
          drive(dut, index + 1, operation, lhs, rhs)
          dut.clock.step()
          dut.io.input.valid.poke(false)
          waitForResult(dut, expected)
          dut.io.completion.ready.poke(true)
          dut.clock.step()
          dut.io.completion.ready.poke(false)
        }
      }
    }

    it("holds a completed result under backpressure and kills younger active work") {
      simulate(new LongPipe) { dut =>
        clear(dut)
        drive(dut, 7, IntOperation.Mul, 3, 9)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, 27)
        dut.clock.step(2)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(7)

        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        drive(dut, 9, IntOperation.Div, 1, 1)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(8)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.clock.step(35)
        dut.io.completion.valid.expect(false)

        drive(dut, 11, IntOperation.Mul, 5, 5)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, 25)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(10)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.completion.valid.expect(false)

        drive(dut, 12, IntOperation.Div, 8, 3)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.clock.step(35)
        dut.io.completion.valid.expect(false)
      }
    }

    it("retains two ordered results in the E2 completion buffer") {
      simulate(new LongPipe) { dut =>
        clear(dut)
        drive(dut, 1, IntOperation.Mul, 2, 3)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        waitForResult(dut, 6)

        drive(dut, 2, IntOperation.Mul, 4, 5)
        dut.clock.step()
        dut.io.input.valid.poke(false)
        dut.clock.step()
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(1)
        dut.io.completion.bits.data.expect(6)

        dut.io.completion.ready.poke(true)
        dut.clock.step()
        dut.io.completion.ready.poke(false)
        dut.io.completion.valid.expect(true)
        dut.io.completion.bits.robTag.expect(2)
        dut.io.completion.bits.data.expect(20)
      }
    }
  }
}
