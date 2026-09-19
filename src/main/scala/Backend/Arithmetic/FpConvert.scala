// SPDX-License-Identifier: Apache-2.0
// Parallel magnitude/LZC follows SRT4Logic; merged rounding/sign restoration follows
// the RecFNToIN arithmetic identity documented in docs/FpMisc-Design-Research.md.
import chisel3._
import chisel3.util._
import ZirconConfig.FpMiscOp._
import ZirconUtil.Log2Rev

class FpConvertRequest(val tagWidth: Int) extends Bundle {
    val src1 = UInt(32.W)
    val op = UInt(4.W)
    // Resolved by issue: RNE=0, RTZ=1, RDN=2, RUP=3, RMM=4.
    val roundingMode = UInt(3.W)
    val tag = UInt(tagWidth.W)
}

class FpConvertResponse(val tagWidth: Int) extends Bundle {
    val res = UInt(32.W)
    val fflags = UInt(5.W)
    val tag = UInt(tagWidth.W)
}

class FpConvertIO(tagWidth: Int) extends Bundle {
    val in = Flipped(Decoupled(new FpConvertRequest(tagWidth)))
    val out = Decoupled(new FpConvertResponse(tagWidth))
    val flush = Input(Bool())
}

/** R1 holds only the shared shifter input and final-rounding controls. */
class FpConvertStage1(tagWidth: Int) extends Bundle {
    val data = UInt(34.W)
    val shift = UInt(5.W)
    val toFloat = Bool()
    val signedInt = Bool()
    val sign = Bool()
    val zero = Bool()
    val nan = Bool()
    val tooLarge = Bool()
    val small = Bool()
    val smallGuard = Bool()
    val smallSticky = Bool()
    val exponent = UInt(8.W)
    val exponentCarry = UInt(8.W)
    val rm = UInt(3.W)
    val tag = UInt(tagWidth.W)
}

/** Two elastic stages for all four RV32 FP32/integer conversions. */
class FpConvert(val tagWidth: Int = 32) extends Module {
    require(tagWidth > 0)
    val io = IO(new FpConvertIO(tagWidth))

    val r1 = Reg(new FpConvertStage1(tagWidth))
    val r2 = Reg(new FpConvertResponse(tagWidth))
    val valid1 = RegInit(false.B)
    val valid2 = RegInit(false.B)
    val active = !reset.asBool && !io.flush
    val advance2 = !valid2 || io.out.ready
    val advance1 = !valid1 || advance2
    io.in.ready := active && advance1
    io.out.valid := active && valid2
    io.out.bits := r2

    /* EX1: integer magnitude and leading zeros run in parallel. */
    val req = io.in.bits
    val toFloat = req.op === FCVT_S_W.U || req.op === FCVT_S_WU.U
    val signedInt = req.op === FCVT_W_S.U || req.op === FCVT_S_W.U
    val negativeInt = signedInt && req.src1(31)
    val complemented = req.src1 ^ Fill(32, negativeInt)
    val leading = Mux(!complemented.orR, 32.U(6.W), Log2Rev(Reverse(complemented)).pad(6))
    val lowOnes = !((complemented >> 1) & ~complemented).orR
    val magnitudeLeading = leading - (negativeInt && lowOnes).asUInt
    val magnitude = Mux(negativeInt, -req.src1, req.src1)

    val exponent = req.src1(30, 23)
    val fraction = req.src1(22, 0)
    val floatShift = 158.U(8.W) - exponent
    val s1 = Wire(new FpConvertStage1(tagWidth))
    // Reverse I2F before R1, keeping the input-direction mux out of EX2.
    s1.data := Mux(toFloat, Reverse(Cat(magnitude, 0.U(2.W))), Cat(exponent.orR, fraction, 0.U(10.W)))
    s1.shift := Mux(toFloat, magnitudeLeading(4, 0), floatShift(4, 0))
    s1.toFloat := toFloat
    s1.signedInt := signedInt
    s1.sign := Mux(toFloat, negativeInt, req.src1(31))
    s1.zero := !req.src1.orR
    s1.nan := exponent.andR && fraction.orR
    s1.tooLarge := exponent > 158.U
    s1.small := exponent < 127.U
    s1.smallGuard := exponent === 126.U
    s1.smallSticky := Mux(exponent === 126.U, fraction.orR, req.src1(30, 0).orR)
    s1.exponent := 158.U(8.W) - magnitudeLeading
    s1.exponentCarry := 159.U(8.W) - magnitudeLeading
    s1.rm := req.roundingMode
    s1.tag := req.tag

    /* EX2: one 34-bit barrel; inputs below one bypass the 0..31 shift range. */
    var shifted = r1.data
    for (stage <- 0 until 5) {
        val distance = 1 << stage
        val jammed = (shifted >> distance).pad(34) | shifted(distance - 1, 0).orR
        shifted = Mux(r1.shift(stage), jammed, shifted)
    }
    val normalized = Reverse(shifted)
    val integerMagnitude = Mux(r1.small, 0.U(32.W), shifted(33, 2))
    val retained = Mux(r1.toFloat, normalized(33, 10).pad(32), integerMagnitude)
    val guard = Mux(r1.toFloat, normalized(9), Mux(r1.small, r1.smallGuard, shifted(1)))
    val sticky = Mux(r1.toFloat, normalized(8, 0).orR, Mux(r1.small, r1.smallSticky, shifted(0)))
    val inexact = guard || sticky
    val roundUp =
        (r1.rm === 0.U && guard && (sticky || retained(0))) ||
            (r1.rm === 4.U && guard) ||
            (r1.rm === 2.U && r1.sign && inexact) ||
            (r1.rm === 3.U && !r1.sign && inexact)

    // -(q + up) = ~q + !up: one shared increment, not rounding followed by negation.
    val negate = !r1.toFloat && r1.sign
    val rounded = BLevelPAdder32.sum(
        retained ^ Fill(32, negate),
        0.U(32.W),
        (roundUp ^ negate).asUInt
    )
    val floatCarry = retained(23, 0).andR && roundUp
    val floatExponent = Mux(floatCarry, r1.exponentCarry, r1.exponent)
    val floatResult = Mux(r1.zero, 0.U(32.W), Cat(r1.sign, floatExponent, rounded(22, 0)))

    /* Range checks run beside the incrementer and use the rounded magnitude's limit. */
    val signedOverflow = Mux(
        r1.sign,
        retained(31) && (retained(30, 0).orR || roundUp),
        retained(31) || (retained(30, 0).andR && roundUp)
    )
    val unsignedOverflow = retained.andR && roundUp
    val negativeUnsigned = !r1.signedInt && r1.sign && (retained.orR || roundUp)
    val invalid = r1.tooLarge || Mux(r1.signedInt, signedOverflow, unsignedOverflow) || negativeUnsigned
    val saturatePositive = r1.nan || !r1.sign
    val saturation = Mux(
        saturatePositive,
        Mux(r1.signedInt, "h7fffffff".U(32.W), "hffffffff".U(32.W)),
        Mux(r1.signedInt, "h80000000".U(32.W), 0.U(32.W))
    )
    val s2 = Wire(new FpConvertResponse(tagWidth))
    s2.res := Mux(r1.toFloat, floatResult, Mux(invalid, saturation, rounded))
    s2.fflags := Cat(!r1.toFloat && invalid, 0.U(3.W), inexact && (r1.toFloat || !invalid))
    s2.tag := r1.tag

    /* Each stage can fill a bubble independently while the other stage is blocked. */
    when(io.flush) {
        valid1 := false.B
        valid2 := false.B
    }.otherwise {
        when(advance1) { valid1 := io.in.valid }
        when(advance2) { valid2 := valid1 }
    }
    when(io.in.fire) { r1 := s1 }
    when(active && advance2 && valid1) { r2 := s2 }
    when(active && io.in.valid) {
        assert(req.op >= FCVT_W_S.U && req.op <= FCVT_S_WU.U, "FpConvert received a non-conversion operation")
        assert(req.roundingMode <= 4.U, "FpConvert requires a resolved rounding mode")
    }
}
