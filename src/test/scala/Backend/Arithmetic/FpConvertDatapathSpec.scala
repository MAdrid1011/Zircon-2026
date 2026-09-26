import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import org.scalatest.freespec.AnyFreeSpec
import ZirconUtil.Log2Rev

import scala.util.Random

class FpConvertDatapathEquivalence extends Module {
    val io = IO(new Bundle {
        val data = Input(UInt(34.W))
        val shift = Input(UInt(5.W))
        val retained = Input(UInt(32.W))
        val negate = Input(Bool())
        val roundUp = Input(Bool())
        val equivalent = Output(Bool())
    })

    var jammed = io.data
    for (stage <- 0 until 5) {
        val distance = 1 << stage
        jammed = Mux(io.shift(stage), (jammed >> distance).pad(34) |
            jammed(distance - 1, 0).orR, jammed)
    }
    val shiftedData = (io.data >> io.shift).pad(34)
    val shiftedOut = VecInit((0 until 32).map { bit =>
        io.data(bit) && io.shift > bit.U
    }).asUInt.orR

    val base = io.retained ^ Fill(32, io.negate)
    val increment = io.roundUp ^ io.negate
    val oldRounded = BLevelPAdder32.sum(base, 0.U(32.W), increment.asUInt)
    val carries = VecInit((0 until 32).map { bit =>
        if (bit == 0) increment else increment && base(bit - 1, 0).andR
    }).asUInt
    io.equivalent := jammed === (shiftedData | shiftedOut.asUInt) &&
        oldRounded === (base ^ carries)
}

class FpConvertInputEquivalence extends Module {
    val io = IO(new Bundle {
        val data = Input(UInt(32.W))
        val signed = Input(Bool())
        val equivalent = Output(Bool())
    })

    val negative = io.signed && io.data(31)
    val oldBits = io.data ^ Fill(32, negative)
    val oldLeading = Mux(!oldBits.orR, 32.U(6.W), Log2Rev(Reverse(oldBits)).pad(6))
    val oldLowOnes = !((oldBits >> 1) & ~oldBits).orR
    val oldMagnitudeLeading = oldLeading - (negative && oldLowOnes).asUInt
    val oldMagnitude = Mux(negative, -io.data, io.data)

    val negativeBits = ~io.data
    val unsignedLeading = Mux(!io.data.orR, 32.U(6.W), Log2Rev(Reverse(io.data)).pad(6))
    val signedLeading = Mux(!negativeBits.orR, 32.U(6.W), Log2Rev(Reverse(negativeBits)).pad(6))
    val lowOnes = !((negativeBits >> 1) & ~negativeBits).orR
    val negativeLeading = signedLeading - lowOnes.asUInt
    val magnitudeLeading = Mux(negative, negativeLeading, unsignedLeading)
    val carries = VecInit((0 until 32).map { bit =>
        if (bit == 0) true.B else negativeBits(bit - 1, 0).andR
    }).asUInt
    val magnitude = Mux(negative, negativeBits ^ carries, io.data)
    val exponent = Mux(negative, 158.U(8.W) - negativeLeading, 158.U(8.W) - unsignedLeading)
    val exponentCarry = Mux(negative, 159.U(8.W) - negativeLeading, 159.U(8.W) - unsignedLeading)
    io.equivalent := oldMagnitudeLeading === magnitudeLeading && oldMagnitude === magnitude &&
        exponent === (158.U(8.W) - oldMagnitudeLeading) &&
        exponentCarry === (159.U(8.W) - oldMagnitudeLeading)
}

class FpConvertDatapathSpec extends AnyFreeSpec with ChiselSim {
    "parallel leading-zero candidates match the previous signed magnitude" in {
        simulate(new FpConvertInputEquivalence) { dut =>
            val random = new Random(0x6c2e5L)
            val corners = Seq(BigInt(0), BigInt(1), BigInt(1) << 31,
                (BigInt(1) << 31) - 1, (BigInt(1) << 32) - 1)
            for (data <- corners; signed <- Seq(false, true)) {
                dut.io.data.poke(data)
                dut.io.signed.poke(signed)
                dut.io.equivalent.expect(true)
            }
            for (_ <- 0 until 2000) {
                dut.io.data.poke(BigInt(32, random))
                dut.io.signed.poke(random.nextBoolean())
                dut.io.equivalent.expect(true)
            }
        }
    }

    "parallel sticky and prefix increment match the previous datapath" in {
        simulate(new FpConvertDatapathEquivalence) { dut =>
            val random = new Random(0x5c0ffeeL)
            val corners = Seq(BigInt(0), BigInt(1), BigInt(1) << 31,
                (BigInt(1) << 34) - 1)
            for (shift <- 0 until 32; data <- corners) {
                dut.io.data.poke(data)
                dut.io.shift.poke(shift)
                dut.io.retained.poke(data & ((BigInt(1) << 32) - 1))
                dut.io.negate.poke((shift & 1) != 0)
                dut.io.roundUp.poke((shift & 2) != 0)
                dut.io.equivalent.expect(true)
            }
            for (_ <- 0 until 2000) {
                dut.io.data.poke(BigInt(34, random))
                dut.io.shift.poke(random.nextInt(32))
                dut.io.retained.poke(BigInt(32, random))
                dut.io.negate.poke(random.nextBoolean())
                dut.io.roundUp.poke(random.nextBoolean())
                dut.io.equivalent.expect(true)
            }
        }
    }
}
