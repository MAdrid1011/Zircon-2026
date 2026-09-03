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
      floatSource1: BigInt = 0, floatDestination: Int = 0,
      roundingMode: Int = 0): Unit = {
    dut.io.input.valid.poke(true)
    dut.io.input.bits.robTag.poke(tag)
    dut.io.input.bits.operation.poke(operation)
    dut.io.input.bits.roundingMode.poke(roundingMode)
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

    it("adds and subtracts single values with IEEE rounding and exception flags") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(operation: FloatingOperation.Type, lhs: BigInt, rhs: BigInt,
            rounding: Int, expected: BigInt, flags: BigInt): Unit = {
          drive(dut, tag = 11, operation = operation, floatSource0 = lhs,
            floatSource1 = rhs, floatDestination = 7, roundingMode = rounding)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(true)
          dut.io.output.bits.floatData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        check(FloatingOperation.FaddS, BigInt("3f800000", 16),
          BigInt("40000000", 16), 0, BigInt("40400000", 16), 0)
        check(FloatingOperation.FsubS, BigInt("40b00000", 16),
          BigInt("40100000", 16), 0, BigInt("40500000", 16), 0)
        check(FloatingOperation.FaddS, BigInt("3fc00000", 16),
          BigInt("c0100000", 16), 0, BigInt("bf400000", 16), 0)
        // Half an ulp is inexact: RNE ties to even, RUP advances one ulp.
        check(FloatingOperation.FaddS, BigInt("3f800000", 16),
          BigInt("33800000", 16), 0, BigInt("3f800000", 16), 1)
        check(FloatingOperation.FaddS, BigInt("3f800000", 16),
          BigInt("33800000", 16), 3, BigInt("3f800001", 16), 1)
        check(FloatingOperation.FsubS, BigInt("00000000", 16),
          BigInt("00000000", 16), 2, BigInt("80000000", 16), 0)
        check(FloatingOperation.FaddS, BigInt("00000001", 16),
          BigInt("00000001", 16), 0, BigInt("00000002", 16), 0)
        check(FloatingOperation.FaddS, BigInt("7f800000", 16),
          BigInt("ff800000", 16), 0, BigInt("7fc00000", 16), 16)
        check(FloatingOperation.FaddS, BigInt("7fc00001", 16),
          BigInt("3f800000", 16), 0, BigInt("7fc00000", 16), 0)
        check(FloatingOperation.FaddS, BigInt("7f7fffff", 16),
          BigInt("7f7fffff", 16), 0, BigInt("7f800000", 16), 5)
        check(FloatingOperation.FaddS, BigInt("7f7fffff", 16),
          BigInt("7f7fffff", 16), 1, BigInt("7f7fffff", 16), 5)
      }
    }

    it("multiplies single values with special-value handling and rounding flags") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(lhs: BigInt, rhs: BigInt, rounding: Int,
            expected: BigInt, flags: BigInt): Unit = {
          drive(dut, tag = 13, operation = FloatingOperation.FmulS,
            floatSource0 = lhs, floatSource1 = rhs, floatDestination = 8,
            roundingMode = rounding)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(true)
          dut.io.output.bits.floatData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        check(BigInt("3fc00000", 16), BigInt("40000000", 16), 0,
          BigInt("40400000", 16), 0)
        check(BigInt("bfc00000", 16), BigInt("40000000", 16), 0,
          BigInt("c0400000", 16), 0)
        check(BigInt("00000001", 16), BigInt("40000000", 16), 0,
          BigInt("00000002", 16), 0)
        check(BigInt("00000000", 16), BigInt("7f800000", 16), 0,
          BigInt("7fc00000", 16), 16)
        check(BigInt("7fc00001", 16), BigInt("3f800000", 16), 0,
          BigInt("7fc00000", 16), 0)
        check(BigInt("7f7fffff", 16), BigInt("40000000", 16), 0,
          BigInt("7f800000", 16), 5)
        check(BigInt("7f7fffff", 16), BigInt("40000000", 16), 1,
          BigInt("7f7fffff", 16), 5)
      }
    }

    it("divides single values with an iterative result and precise specials") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(lhs: BigInt, rhs: BigInt, rounding: Int,
            expected: BigInt, flags: BigInt): Unit = {
          drive(dut, tag = 15, operation = FloatingOperation.FdivS,
            floatSource0 = lhs, floatSource1 = rhs, floatDestination = 10,
            roundingMode = rounding)
          accept(dut)
          var cycles = 0
          while (!dut.io.output.valid.peek().litToBoolean && cycles < 60) {
            dut.clock.step()
            cycles += 1
          }
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(true)
          dut.io.output.bits.floatData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        check(BigInt("40c00000", 16), BigInt("40000000", 16), 0,
          BigInt("40400000", 16), 0)
        check(BigInt("3f800000", 16), BigInt("40000000", 16), 0,
          BigInt("3f000000", 16), 0)
        check(BigInt("bf800000", 16), BigInt("40000000", 16), 0,
          BigInt("bf000000", 16), 0)
        check(BigInt("3f800000", 16), BigInt("40400000", 16), 0,
          BigInt("3eaaaaab", 16), 1)
        check(BigInt("3f800000", 16), BigInt("00000000", 16), 0,
          BigInt("7f800000", 16), 8)
        check(BigInt("00000000", 16), BigInt("00000000", 16), 0,
          BigInt("7fc00000", 16), 16)
        check(BigInt("7f800000", 16), BigInt("7f800000", 16), 0,
          BigInt("7fc00000", 16), 16)
      }
    }

    it("computes single square roots with an iterative restoring root") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(source: BigInt, rounding: Int, expected: BigInt,
            flags: BigInt): Unit = {
          drive(dut, tag = 17, operation = FloatingOperation.FsqrtS,
            floatSource0 = source, floatDestination = 11, roundingMode = rounding)
          accept(dut)
          var cycles = 0
          while (!dut.io.output.valid.peek().litToBoolean && cycles < 35) {
            dut.clock.step()
            cycles += 1
          }
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(true)
          dut.io.output.bits.floatData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        check(BigInt("3f800000", 16), 0, BigInt("3f800000", 16), 0)
        check(BigInt("40000000", 16), 0, BigInt("3fb504f3", 16), 1)
        check(BigInt("40800000", 16), 0, BigInt("40000000", 16), 0)
        check(BigInt("bf800000", 16), 0, BigInt("7fc00000", 16), 16)
        check(BigInt("80000000", 16), 0, BigInt("80000000", 16), 0)
        check(BigInt("7f800000", 16), 0, BigInt("7f800000", 16), 0)
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

    it("converts signed and unsigned integers with every architectural rounding mode") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(operation: FloatingOperation.Type, source: BigInt, rounding: Int,
            expected: BigInt, flags: BigInt): Unit = {
          drive(dut, tag = 3, operation = operation, integerSource = source,
            floatDestination = 9, roundingMode = rounding)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesFloat.expect(true)
          dut.io.output.bits.floatDestination.expect(9)
          dut.io.output.bits.floatData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        check(FloatingOperation.FcvtSW, 0, rounding = 0, 0, 0)
        check(FloatingOperation.FcvtSW, 1, rounding = 0, BigInt("3f800000", 16), 0)
        check(FloatingOperation.FcvtSW, BigInt("ffffffff", 16), rounding = 0,
          BigInt("bf800000", 16), 0)
        check(FloatingOperation.FcvtSW, BigInt("01000001", 16), rounding = 0,
          BigInt("4b800000", 16), BigInt(1))
        check(FloatingOperation.FcvtSW, BigInt("01000001", 16), rounding = 1,
          BigInt("4b800000", 16), BigInt(1))
        check(FloatingOperation.FcvtSW, BigInt("01000001", 16), rounding = 2,
          BigInt("4b800000", 16), BigInt(1))
        check(FloatingOperation.FcvtSW, BigInt("01000001", 16), rounding = 3,
          BigInt("4b800001", 16), BigInt(1))
        check(FloatingOperation.FcvtSW, BigInt("01000001", 16), rounding = 4,
          BigInt("4b800001", 16), BigInt(1))
        check(FloatingOperation.FcvtSW, BigInt("feffffff", 16), rounding = 2,
          BigInt("cb800001", 16), BigInt(1))
        check(FloatingOperation.FcvtSW, BigInt("feffffff", 16), rounding = 3,
          BigInt("cb800000", 16), BigInt(1))
        check(FloatingOperation.FcvtSWu, BigInt("ffffffff", 16), rounding = 1,
          BigInt("4f7fffff", 16), BigInt(1))
        check(FloatingOperation.FcvtSWu, BigInt("ffffffff", 16), rounding = 3,
          BigInt("4f800000", 16), BigInt(1))
        check(FloatingOperation.FcvtSWu, BigInt("80000000", 16), rounding = 0,
          BigInt("4f000000", 16), 0)
      }
    }

    it("converts finite, exceptional, and boundary single values to integers precisely") {
      simulate(new FloatingMovePipe) { dut =>
        clear(dut)
        def check(operation: FloatingOperation.Type, source: BigInt, rounding: Int,
            expected: BigInt, flags: BigInt): Unit = {
          drive(dut, tag = 5, operation = operation, integerDestination = 37,
            floatSource0 = source, roundingMode = rounding)
          accept(dut)
          dut.io.output.valid.expect(true)
          dut.io.output.bits.writesInteger.expect(true)
          dut.io.output.bits.writesFloat.expect(false)
          dut.io.output.bits.integerDestinationPhysical.expect(37)
          dut.io.output.bits.integerData.expect(expected)
          dut.io.output.bits.flags.expect(flags)
          dut.io.output.ready.poke(true)
          dut.clock.step()
          dut.io.output.ready.poke(false)
        }

        // +/-0.5 is the RNE/RMM tie boundary, and directed modes depend on sign.
        check(FloatingOperation.FcvtWS, BigInt("3f000000", 16), 0, 0, 1)
        check(FloatingOperation.FcvtWS, BigInt("3f000000", 16), 3, 1, 1)
        check(FloatingOperation.FcvtWS, BigInt("3f000000", 16), 4, 1, 1)
        check(FloatingOperation.FcvtWS, BigInt("bf000000", 16), 0, 0, 1)
        check(FloatingOperation.FcvtWS, BigInt("bf000000", 16), 2,
          BigInt("ffffffff", 16), 1)
        check(FloatingOperation.FcvtWS, BigInt("bf000000", 16), 4,
          BigInt("ffffffff", 16), 1)
        // Exact, fractional, and subnormal finite cases.
        check(FloatingOperation.FcvtWS, BigInt("3fc00000", 16), 0, 2, 1)
        check(FloatingOperation.FcvtWS, BigInt("40200000", 16), 0, 2, 1)
        check(FloatingOperation.FcvtWS, BigInt("bfa00000", 16), 2,
          BigInt("fffffffe", 16), 1)
        check(FloatingOperation.FcvtWS, BigInt("bfa00000", 16), 3,
          BigInt("ffffffff", 16), 1)
        check(FloatingOperation.FcvtWS, BigInt("00000001", 16), 3, 1, 1)
        check(FloatingOperation.FcvtWS, BigInt("80000001", 16), 2,
          BigInt("ffffffff", 16), 1)
        // Signed and unsigned representability boundaries.
        check(FloatingOperation.FcvtWS, BigInt("cf000000", 16), 0,
          BigInt("80000000", 16), 0)
        check(FloatingOperation.FcvtWS, BigInt("4f000000", 16), 0,
          BigInt("80000000", 16), BigInt(16))
        check(FloatingOperation.FcvtWuS, BigInt("4f7fffff", 16), 0,
          BigInt("ffffff00", 16), 0)
        check(FloatingOperation.FcvtWuS, BigInt("4f800000", 16), 0,
          BigInt("ffffffff", 16), BigInt(16))
        check(FloatingOperation.FcvtWuS, BigInt("bf000000", 16), 1, 0, 1)
        check(FloatingOperation.FcvtWuS, BigInt("bf000000", 16), 2,
          BigInt("ffffffff", 16), BigInt(16))
        // Both NaN classes and infinities take the architectural invalid path.
        Seq("7fc00001", "7f800001", "7f800000", "ff800000").foreach { bits =>
          check(FloatingOperation.FcvtWS, BigInt(bits, 16), 0,
            BigInt("80000000", 16), BigInt(16))
          check(FloatingOperation.FcvtWuS, BigInt(bits, 16), 0,
            BigInt("ffffffff", 16), BigInt(16))
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
