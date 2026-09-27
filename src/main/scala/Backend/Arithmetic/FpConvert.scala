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
    val toFloat = Bool()
    val signedInt = Bool()
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

class FpConvertStage2(tagWidth: Int) extends Bundle {
    val retained = UInt(32.W)
    val guard = Bool()
    val sticky = Bool()
    val toFloat = Bool()
    val signedInt = Bool()
    val sign = Bool()
    val zero = Bool()
    val nan = Bool()
    val tooLarge = Bool()
    val exponent = UInt(8.W)
    val exponentCarry = UInt(8.W)
    val rm = UInt(3.W)
    val tag = UInt(tagWidth.W)
}

/** Three elastic stages, with conversion completing at the existing EX4 boundary. */
class FpConvert(val tagWidth: Int = 32) extends Module {
    require(tagWidth > 0)
    val io = IO(new FpConvertIO(tagWidth))

    val r1 = Reg(new FpConvertStage1(tagWidth))
    val r2 = Reg(new FpConvertStage2(tagWidth))
    val r3 = Reg(new FpConvertResponse(tagWidth))
    val valid1 = RegInit(false.B)
    val valid2 = RegInit(false.B)
    val valid3 = RegInit(false.B)
    val active = !reset.asBool && !io.flush
    val advance3 = !valid3 || io.out.ready
    val advance2 = !valid2 || advance3
    val advance1 = !valid1 || advance2
    io.in.ready := active && advance1
    io.out.valid := active && valid3
    io.out.bits := r3

    /* EX1: integer magnitude and leading zeros run in parallel. */
    val req = io.in.bits
    val toFloat = req.toFloat
    val signedInt = req.signedInt
    val negativeInt = signedInt && req.src1(31)
    val negativeBits = ~req.src1
    val unsignedLeading = Mux(!req.src1.orR, 32.U(6.W), Log2Rev(Reverse(req.src1)).pad(6))
    val signedLeading = Mux(!negativeBits.orR, 32.U(6.W), Log2Rev(Reverse(negativeBits)).pad(6))
    val lowOnes = !((negativeBits >> 1) & ~negativeBits).orR
    val negativeLeading = signedLeading - lowOnes.asUInt
    val magnitudeLeading = Mux(negativeInt, negativeLeading, unsignedLeading)
    val negativeCarry = VecInit((0 until 32).map { bit =>
        if (bit == 0) true.B else negativeBits(bit - 1, 0).andR
    }).asUInt
    val negativeMagnitude = negativeBits ^ negativeCarry
    val magnitude = Mux(negativeInt, negativeMagnitude, req.src1)

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
    s1.exponent := Mux(negativeInt,
        158.U(8.W) - negativeLeading, 158.U(8.W) - unsignedLeading)
    s1.exponentCarry := Mux(negativeInt,
        159.U(8.W) - negativeLeading, 159.U(8.W) - unsignedLeading)
    s1.rm := req.roundingMode
    s1.tag := req.tag

    /* EX2: shifted-out sticky bits are reduced beside the barrel, not through it. */
    val shiftedData = (r1.data >> r1.shift).pad(34)
    val shiftedOut = VecInit((0 until 32).map { bit =>
        r1.data(bit) && r1.shift > bit.U
    }).asUInt.orR
    val shifted = shiftedData | shiftedOut.asUInt
    val normalized = Reverse(shifted)
    val integerMagnitude = Mux(r1.small, 0.U(32.W), shifted(33, 2))
    val retained = Mux(r1.toFloat, normalized(33, 10).pad(32), integerMagnitude)
    val guard = Mux(r1.toFloat, normalized(9), Mux(r1.small, r1.smallGuard, shifted(1)))
    val sticky = Mux(r1.toFloat, normalized(8, 0).orR, Mux(r1.small, r1.smallSticky, shifted(0)))
    val s2 = Wire(new FpConvertStage2(tagWidth))
    s2.retained := retained
    s2.guard := guard
    s2.sticky := sticky
    s2.toFloat := r1.toFloat
    s2.signedInt := r1.signedInt
    s2.sign := r1.sign
    s2.zero := r1.zero
    s2.nan := r1.nan
    s2.tooLarge := r1.tooLarge
    s2.exponent := r1.exponent
    s2.exponentCarry := r1.exponentCarry
    s2.rm := r1.rm
    s2.tag := r1.tag

    /* EX3: round the registered shifted result and check its integer range. */
    val inexact = r2.guard || r2.sticky
    val roundUp =
        (r2.rm === 0.U && r2.guard && (r2.sticky || r2.retained(0))) ||
            (r2.rm === 4.U && r2.guard) ||
            (r2.rm === 2.U && r2.sign && inexact) ||
            (r2.rm === 3.U && !r2.sign && inexact)

    // -(q + up) = ~q + !up. A conditional increment has only prefix carries.
    val negate = !r2.toFloat && r2.sign
    val roundBase = r2.retained ^ Fill(32, negate)
    val increment = roundUp ^ negate
    val rounded = BLevelPAdder32.sum(roundBase, 0.U(32.W), increment.asUInt)
    val floatCarry = r2.retained(23, 0).andR && roundUp
    val floatExponent = Mux(floatCarry, r2.exponentCarry, r2.exponent)
    val floatResult = Mux(r2.zero, 0.U(32.W), Cat(r2.sign, floatExponent, rounded(22, 0)))

    /* Range checks run beside the incrementer and use the rounded magnitude's limit. */
    val signedOverflow = Mux(
        r2.sign,
        r2.retained(31) && (r2.retained(30, 0).orR || roundUp),
        r2.retained(31) || (r2.retained(30, 0).andR && roundUp)
    )
    val unsignedOverflow = r2.retained.andR && roundUp
    val negativeUnsigned = !r2.signedInt && r2.sign && (r2.retained.orR || roundUp)
    val invalid = r2.tooLarge || Mux(r2.signedInt, signedOverflow, unsignedOverflow) || negativeUnsigned
    val saturatePositive = r2.nan || !r2.sign
    val saturation = Mux(
        saturatePositive,
        Mux(r2.signedInt, "h7fffffff".U(32.W), "hffffffff".U(32.W)),
        Mux(r2.signedInt, "h80000000".U(32.W), 0.U(32.W))
    )
    val s3 = Wire(new FpConvertResponse(tagWidth))
    s3.res := Mux(r2.toFloat, floatResult, Mux(invalid, saturation, rounded))
    s3.fflags := Cat(!r2.toFloat && invalid, 0.U(3.W), inexact && (r2.toFloat || !invalid))
    s3.tag := r2.tag

    /* Each stage can fill a bubble independently while the other stage is blocked. */
    when(io.flush) {
        valid1 := false.B
        valid2 := false.B
        valid3 := false.B
    }.otherwise {
        when(advance1) { valid1 := io.in.valid }
        when(advance2) { valid2 := valid1 }
        when(advance3) { valid3 := valid2 }
    }
    when(io.in.fire) { r1 := s1 }
    when(active && advance2 && valid1) { r2 := s2 }
    when(active && advance3 && valid2) { r3 := s3 }
    when(active && io.in.valid) {
        assert(req.op >= FCVT_W_S.U && req.op <= FCVT_S_WU.U, "FpConvert received a non-conversion operation")
        assert(toFloat === (req.op === FCVT_S_W.U || req.op === FCVT_S_WU.U))
        assert(signedInt === (req.op === FCVT_W_S.U || req.op === FCVT_S_W.U))
        assert(req.roundingMode <= 4.U, "FpConvert requires a resolved rounding mode")
    }
}
