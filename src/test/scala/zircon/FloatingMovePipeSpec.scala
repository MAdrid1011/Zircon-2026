package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FloatingMovePipe
import zircon.frontend.FloatingOperation

class FloatingMovePipeSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: FloatingMovePipe): Unit = {
    dut.io.input.valid.poke(false)
    dut.io.input.bits.robTag.poke(0)
    dut.io.input.bits.operation.poke(FloatingOperation.Invalid)
    dut.io.input.bits.roundingMode.poke(0)
    dut.io.input.bits.integerDestinationPhysical.poke(0)
    dut.io.input.bits.integerSource.poke(0)
    dut.io.input.bits.floatSource.foreach(_.poke(0))
    dut.io.input.bits.floatDestination.poke(0)
    dut.io.output.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(dut: FloatingMovePipe, tag: Int,
      operation: FloatingOperation.Type, integerDestination: Int = 0,
      integerSource: BigInt = 0, floatSource0: BigInt = 0,
      floatSource1: BigInt = 0, floatDestination: Int = 0): Unit = {
    dut.io.input.valid.poke(true)
    dut.io.input.bits.robTag.poke(tag)
    dut.io.input.bits.operation.poke(operation)
    dut.io.input.bits.roundingMode.poke(0)
    dut.io.input.bits.integerDestinationPhysical.poke(integerDestination)
    dut.io.input.bits.integerSource.poke(integerSource)
    dut.io.input.bits.floatSource(0).poke(floatSource0)
    dut.io.input.bits.floatSource(1).poke(floatSource1)
    dut.io.input.bits.floatDestination.poke(floatDestination)
  }

  private def accept(dut: FloatingMovePipe): Unit = {
    dut.io.input.ready.expect(true)
    dut.clock.step()
    dut.io.input.valid.poke(false)
  }

  describe("FloatingMovePipe") {
    it("retains exact namespace-specific FMV results under output backpressure") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        drive(dut, tag = 3, operation = FloatingOperation.FmvWX,
          integerSource = BigInt("deadbeef", 16), floatDestination = 9)
        accept(dut)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.robTag.expect(3)
        dut.io.output.bits.writesInteger.expect(false)
        dut.io.output.bits.writesFloat.expect(true)
        dut.io.output.bits.floatDestination.expect(9)
        dut.io.output.bits.floatData.expect(BigInt("deadbeef", 16))
        dut.io.output.bits.flags.expect(0)
        dut.clock.step(2)
        dut.io.output.valid.expect(true)

        dut.io.output.ready.poke(true)
        dut.clock.step()
        dut.io.output.ready.poke(false)
        drive(dut, tag = 4, operation = FloatingOperation.FmvXW,
          integerDestination = 37, floatSource0 = BigInt("40490fdb", 16))
        accept(dut)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.robTag.expect(4)
        dut.io.output.bits.writesInteger.expect(true)
        dut.io.output.bits.integerDestinationPhysical.expect(37)
        dut.io.output.bits.integerData.expect(BigInt("40490fdb", 16))
        dut.io.output.bits.writesFloat.expect(false)
      }
    }

    it("injects, inverts, and xors only the sign bit") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        val source = BigInt("3f123456", 16)
        val signOne = BigInt("80000000", 16)
        val cases = Seq(
          FloatingOperation.FsgnjS -> BigInt("bf123456", 16),
          FloatingOperation.FsgnjnS -> source,
          FloatingOperation.FsgnjxS -> BigInt("bf123456", 16))
        cases.zipWithIndex.foreach { case ((operation, expected), index) =>
          drive(dut, tag = index + 1, operation = operation,
            floatSource0 = source, floatSource1 = signOne, floatDestination = 12)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(true)
          dut.io.output.bits.floatDestination.expect(12)
          dut.io.output.bits.floatData.expect(expected)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }
      }
    }

    it("implements min/max and comparison NaN, signed-zero, and NV rules") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(operation: FloatingOperation.Type, lhs: BigInt, rhs: BigInt,
            writesFloat: Boolean, expected: BigInt, flags: BigInt): Unit = {
          drive(dut, tag = 3, operation = operation, integerDestination = 37,
            floatSource0 = lhs, floatSource1 = rhs, floatDestination = 9)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(writesFloat)
          dut.io.output.bits.writesInteger.expect(!writesFloat)
          if (writesFloat) dut.io.output.bits.floatData.expect(expected)
          else dut.io.output.bits.integerData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        check(FloatingOperation.FminS, BigInt("3f800000", 16), BigInt("40000000", 16),
          writesFloat = true, BigInt("3f800000", 16), 0)
        check(FloatingOperation.FmaxS, BigInt("80000000", 16), 0,
          writesFloat = true, 0, 0)
        check(FloatingOperation.FminS, BigInt("80000000", 16), 0,
          writesFloat = true, BigInt("80000000", 16), 0)
        check(FloatingOperation.FminS, BigInt("7f800001", 16), BigInt("3f800000", 16),
          writesFloat = true, BigInt("3f800000", 16), BigInt(16))
        check(FloatingOperation.FeqS, BigInt("80000000", 16), 0,
          writesFloat = false, 1, 0)
        check(FloatingOperation.FltS, BigInt("3f800000", 16), BigInt("40000000", 16),
          writesFloat = false, 1, 0)
        check(FloatingOperation.FleS, BigInt("7fc00001", 16), BigInt("3f800000", 16),
          writesFloat = false, 0, BigInt(16))
        check(FloatingOperation.FeqS, BigInt("7fc00001", 16), BigInt("3f800000", 16),
          writesFloat = false, 0, 0)
      }
    }

    it("classifies every floating category without modifying fflags") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        val cases = Seq(
          BigInt("ff800000", 16) -> BigInt(1),
          BigInt("80000001", 16) -> BigInt(4),
          BigInt("80000000", 16) -> BigInt(8),
          BigInt(0) -> BigInt(16),
          BigInt("00000001", 16) -> BigInt(32),
          BigInt("3f800000", 16) -> BigInt(64),
          BigInt("7f800000", 16) -> BigInt(128),
          BigInt("7f800001", 16) -> BigInt(256),
          BigInt("7fc00001", 16) -> BigInt(512))
        cases.zipWithIndex.foreach { case ((source, expected), index) =>
          drive(dut, tag = index + 1, operation = FloatingOperation.FclassS,
            integerDestination = 37, floatSource0 = source)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesInteger.expect(true)
          dut.io.output.bits.integerData.expect(expected)
          dut.io.output.bits.flags.expect(0)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }
      }
    }

    it("drops only younger retained work on squash and all work on flush") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        drive(dut, tag = 9, operation = FloatingOperation.FmvWX,
          integerSource = 1, floatDestination = 1)
        accept(dut)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(8)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.output.valid.expect(false)

        drive(dut, tag = 7, operation = FloatingOperation.FmvWX,
          integerSource = 2, floatDestination = 2)
        accept(dut)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(8)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.output.valid.expect(true)

        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.output.valid.expect(false)
      }
    }
  }
}
