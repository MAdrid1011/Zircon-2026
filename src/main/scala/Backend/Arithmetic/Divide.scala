// SPDX-License-Identifier: Apache-2.0
// Radix-4 recurrence and selection constants follow Harris et al.,
// "Unified Digit Selection for Radix-4 Recurrence Division and Square Root",
// DOI: 10.1109/TC.2023.3305760, Algorithms III-VI and Table VI.
// Integer sign preparation and leading-zero normalization follow Zircon-2024.
import chisel3._
import chisel3.util._
import ZirconConfig.DivideOp._
import ZirconUtil.Log2Rev

class DivideRequest(val tagWidth: Int) extends Bundle {
    val src1 = UInt(32.W)
    val src2 = UInt(32.W)
    val op = UInt(3.W)
    // Resolved by issue: RNE=0, RTZ=1, RDN=2, RUP=3, RMM=4.
    val roundingMode = UInt(3.W)
    val tag = UInt(tagWidth.W)
}

class DivideResponse(val tagWidth: Int) extends Bundle {
    val res = UInt(32.W)
    val fflags = UInt(5.W)
    val tag = UInt(tagWidth.W)
}

class DivideWbInput extends Bundle {
    val res = UInt(32.W)
    val tag = new MixArithWakeTag
}

object SRT4Logic {
    val Fraction = 32
    val Width = Fraction + 4 // Q4.32 residual; all arithmetic is modulo 2^Width.
    val ResultWidth = Fraction + 2 // U2.32 result and its signed companion.

    def leadingZeros(x: UInt): UInt = Mux(x === 0.U, 32.U(6.W), Log2Rev(Reverse(x)).pad(6))

    /** Compute |x| and its leading zeros in parallel. negative implies x(31).
      * For a negative input, y = ~x and |x| = y + 1. Only a low all-ones
      * pattern changes the leading position; detect it without a wide increment.
      */
    def magnitudeAndLeadingZeros(x: UInt, negative: Bool): (UInt, UInt) = {
        val y = x ^ Fill(32, negative)
        val lowOnes = !((y >> 1) & ~y).orR
        val leading = leadingZeros(y) - (negative && lowOnes).asUInt
        (Mux(negative, -x, x), leading)
    }

    def rightJam(x: UInt, shift: UInt): UInt = {
        val width = x.getWidth
        val stages = log2Ceil(width)
        var value = x
        for (i <- 0 until stages) {
            val distance = 1 << i
            val shifted = (value >> distance).pad(width)
            value = Mux(shift(i), shifted | value(distance - 1, 0).orR, value)
        }
        Mux(shift >= width.U, Cat(0.U((width - 1).W), x.orR), value)
    }

    def integerWindow(data: UInt, shift: UInt): UInt = {
        val stages = (0 until 6).foldLeft(data) { case (value, bit) =>
            val amount = 1 << bit
            val shifted = Cat(0.U(amount.W), value(Width - 1, amount))
            Mux(shift(bit), shifted, value)
        }
        stages(31, 0)
    }

    /** Round an already normalized 27-bit significand window with a sticky LSB. */
    def round(windowIn: UInt, exponent: SInt, sign: Bool, rm: UInt): (UInt, UInt) = {
        val distance = (-126).S(11.W) - exponent
        val shift = Mux(exponent < (-126).S, Mux(distance > 27.S, 27.U, distance.asUInt), 0.U)
        val window = rightJam(windowIn, shift)
        def increment(g: Bool, s: Bool, lsb: Bool): Bool =
            (rm === 0.U && g && (s || lsb)) ||
                (rm === 4.U && g) ||
                (rm === 2.U && sign && (g || s)) ||
                (rm === 3.U && !sign && (g || s))
        val q = window(26, 3)
        val inexact = window(2, 0).orR
        val up = increment(window(2), window(1, 0).orR, q(0))
        val carry = q.andR && up
        val rounded = q + up
        val sig = Mux(carry, "h800000".U(24.W), rounded)
        // Tininess is checked at unbounded exponent precision, before narrowing
        // to the subnormal grid. This also handles rounding to the smallest normal.
        val normalCarry = windowIn(26, 3).andR && increment(windowIn(2), windowIn(1, 0).orR, windowIn(3))
        val tiny = exponent < (-127).S || (exponent === (-127).S && !normalCarry)
        val base = Mux(exponent < (-126).S, (-126).S(11.W), exponent)
        val outExponent = base + carry.asUInt.zext
        val biased = (base + 127.S).asUInt
        val biasedCarry = Cat(!base.asUInt(7), base.asUInt(6, 0))
        val ef = Mux(!sig(23), 0.U(8.W), Mux(carry, biasedCarry, biased(7, 0)))
        val overflow = outExponent > 127.S
        val toInf = rm === 0.U || rm === 4.U || (rm === 2.U && sign) || (rm === 3.U && !sign)
        val magnitude = Mux(overflow, Mux(toInf, "h7f800000".U, "h7f7fffff".U), Cat(ef, sig(22, 0)))
        val flags = Mux(overflow, 5.U, Mux(inexact, Mux(tiny, 3.U, 1.U), 0.U))
        (Cat(sign, magnitude(30, 0)), flags)
    }
}

class SRT4IterationIO extends Bundle {
    import SRT4Logic._
    val sqrt = Input(Bool())
    val first = Input(Bool())
    val divisor = Input(UInt((Width - 2).W))
    val negativeDivisor = Input(UInt((Width - 2).W))
    val sum = Input(UInt(Width.W))
    val carry = Input(UInt(Width.W))
    val result = Input(UInt(ResultWidth.W))
    val resultMinus = Input(UInt(ResultWidth.W))
    val position = Input(UInt((Width - 2).W))
    val nextSum = Output(UInt(Width.W))
    val nextCarry = Output(UInt(Width.W))
    val nextResult = Output(UInt(ResultWidth.W))
    val nextMinus = Output(UInt(ResultWidth.W))
    val nextPosition = Output(UInt(Width.W))
    val digit = Output(UInt(5.W))
}

/** One shared radix-4 iteration. Division emits q0 then fractional digits;
  * square root starts from S0=1 and emits fractional digits only.
  */
class SRT4Iteration extends RawModule {
    import SRT4Logic._
    val io = IO(new SRT4IterationIO)
    val position = io.position.asSInt.pad(Width).asUInt
    val bit = (position & ~(position << 1))(ResultWidth - 1, 0)
    val index = Mux(
        io.sqrt,
        Mux(io.first, 5.U, Mux(io.result(Fraction), 7.U, io.result(Fraction - 2, Fraction - 4))),
        io.divisor(Fraction - 1, Fraction - 3)
    )
    // Sum only the leading Q4.4 fields, then truncate to Q4.3. The error
    // relative to the full carry-save residual is bounded by 3/16.
    val highA = io.sum(Width - 1, Fraction - 4)
    val highB = io.carry(Width - 1, Fraction - 4)
    val high = (highA(7, 1) +& highB(7, 1) +& (highA(0) && highB(0)).asUInt)(6, 0).asSInt
    val m2 = VecInit(Seq(12, 14, 16, 16, 18, 20, 20, 24).map(_.S(7.W)))(index)
    val m1 = VecInit(Seq(4, 4, 4, 4, 6, 6, 8, 8).map(_.S(7.W)))(index)
    val m0 = VecInit(Seq(-4, -4, -6, -6, -6, -8, -8, -8).map(_.S(7.W)))(index)
    val mn = VecInit(Seq(-13, -14, -16, -17, -18, -20, -22, -22).map(_.S(7.W)))(index)
    val ge2 = high >= m2; val ge1 = high >= m1; val ge0 = high >= m0; val gen = high >= mn
    val digit = Cat(ge2, !ge2 && ge1, !ge1 && ge0, !ge0 && gen, !gen)
    io.digit := digit
    val u = io.result.pad(Width); val um = io.resultMinus.pad(Width)
    // Adder-free square-root multiples, derived from UMinus = U - 4*K.
    // Thermometer masks insert the small q^2*K correction at its exact weight.
    val rootTerms = Seq(
        ((um << 2) | ((position << 2) & ~(position << 4)))(Width - 1, 0),
        ((um << 1) | (position & ~(position << 3)))(Width - 1, 0),
        0.U(Width.W),
        (~(u << 1) & position)(Width - 1, 0),
        (~(u << 2) & (position << 2))(Width - 1, 0)
    )
    val divTerms = Seq(
        (io.divisor.pad(Width) << 1)(Width - 1, 0),
        io.divisor.pad(Width),
        0.U(Width.W),
        io.negativeDivisor.asSInt.pad(Width).asUInt,
        (io.negativeDivisor.asSInt.pad(Width).asUInt << 1)(Width - 1, 0)
    )
    val addend = Mux1H((0 until 5).map(i => digit(i) -> Mux(io.sqrt, rootTerms(i), divTerms(i))))
    val xor = io.sum ^ io.carry ^ addend
    val majority = (io.sum & io.carry) | ((io.sum ^ io.carry) & addend)
    io.nextSum := (xor << 2)(Width - 1, 0)
    io.nextCarry := (majority << 3)(Width - 1, 0)
    val two = (bit << 1)(ResultWidth - 1, 0)
    io.nextResult := Mux1H(Seq(
        digit(0) -> (io.resultMinus | two),
        digit(1) -> (io.resultMinus | two | bit),
        digit(2) -> io.result,
        digit(3) -> (io.result | bit),
        digit(4) -> (io.result | two)
    ))
    io.nextMinus := Mux1H(Seq(
        digit(0) -> (io.resultMinus | bit),
        digit(1) -> (io.resultMinus | two),
        digit(2) -> (io.resultMinus | two | bit),
        digit(3) -> io.result,
        digit(4) -> (io.result | bit)
    ))
    io.nextPosition := position
}

/** Metadata follows its own transaction through all four execution stages. */
class DivMeta(val tagWidth: Int) extends Bundle {
    val op = UInt(3.W)
    val sign = Bool()
    val roundingMode = UInt(3.W)
    val tag = UInt(tagWidth.W)
    val special = Bool()
    val specialBits = UInt(32.W)
    val specialFlags = UInt(5.W)
}

/** R12: magnitudes and leading counts, before normalization shifts. */
class DivStage1(tagWidth: Int) extends Bundle {
    val a = UInt(32.W)
    val b = UInt(32.W)
    val leadingA = UInt(6.W)
    val leadingB = UInt(6.W)
    val exponentA = SInt(9.W)
    val exponentB = SInt(9.W)
    val meta = new DivMeta(tagWidth)
}

/** R23 also supplies EX2's working registers while v2 is false.
  * residual holds the sum row until RESOLVE, then the nonredundant residual.
  * multiple holds D until RESOLVE, then the final correction multiple.
  */
class DivStage2(tagWidth: Int) extends Bundle {
    val residual = UInt(SRT4Logic.Width.W)
    val multiple = UInt(SRT4Logic.Width.W)
    val result = UInt(SRT4Logic.ResultWidth.W)
    val resultMinus = UInt(SRT4Logic.ResultWidth.W)
    val exponent = SInt(11.W)
    val integerShift = UInt(6.W)
    val meta = new DivMeta(tagWidth)
}

/** R34: normalized FP window with sticky, or an unscaled integer magnitude. */
class DivStage3(tagWidth: Int) extends Bundle {
    val data = UInt(SRT4Logic.Width.W)
    val exponent = SInt(11.W)
    val integerShift = UInt(6.W)
    val meta = new DivMeta(tagWidth)
}

class DivSqrtSRT4IO(tagWidth: Int) extends Bundle {
    val in = Flipped(Decoupled(new DivideRequest(tagWidth)))
    val out = Decoupled(new DivideResponse(tagWidth))
    val wbInput = Output(Valid(new DivideWbInput))
    val present = Output(Bool())
    val flush = Input(Bool())
    val divBusy = Output(Bool())
}

/** Four execution stages, including output register R4:
  * EX1 magnitude/classification -> R12
  * EX2 INIT -> ITERATE* -> RESOLVE -> R23
  * EX3 correction/window preparation -> R34
  * EX4 rounding/integer restoration -> R4
  *
  * EX2 is iterative; EX1 can hold one younger request while EX3/EX4 drain.
  * divBusy directly exposes EX2's registered compute state, including RESOLVE.
  * Completed R23 backpressure is handled separately by the ready/valid chain.
  */
class DivSqrtSRT4(val tagWidth: Int = 32) extends Module {
    import SRT4Logic._
    require(tagWidth > 0)
    val io = IO(new DivSqrtSRT4IO(tagWidth))

    val r1 = Reg(new DivStage1(tagWidth))
    val r2 = Reg(new DivStage2(tagWidth))
    val r3 = Reg(new DivStage3(tagWidth))
    val r4 = Reg(new DivideResponse(tagWidth))
    val v1 = RegInit(false.B)
    val v2 = RegInit(false.B)
    val v3 = RegInit(false.B)
    val v4 = RegInit(false.B)

    // INIT is the R12 acceptance cycle. Only EX2 owns this local state machine.
    // Encode the three states as (busy, resolving): idle=00, iterate=10, resolve=11.
    // The busy state bit drives the public port directly, without an output decode.
    val ex2Busy = RegInit(false.B)
    val ex2Resolving = RegInit(false.B)
    val iterCarry = RegInit(0.U(Width.W))
    val iterNegativeDivisor = RegInit(0.U((Width - 2).W))
    val iterPosition = RegInit(0.U(Width.W))
    val iterCount = RegInit(0.U(5.W))
    val iterFirst = RegInit(false.B)

    val ready4 = !v4 || io.out.ready
    val ready3 = !v3 || ready4
    val ready2 = !ex2Busy && (!v2 || ready3)
    val ready1 = !v1 || ready2
    val active = !reset.asBool && !io.flush
    io.in.ready := ready1 && active
    io.present := v4
    io.out.valid := v4 && active
    io.out.bits := r4
    // Busy changes only at clock edges, including synchronous reset/flush.
    // It excludes completed R23 backpressure; upstream acceptance uses in.ready.
    io.divBusy := ex2Busy

    def normalizeLeft32(value: UInt, amount: UInt): UInt = MuxLookup(amount, 0.U(32.W))(
        (0 until 32).map { shift =>
            shift.U -> (if (shift == 0) value else Cat(value(31 - shift, 0), 0.U(shift.W)))
        }
    )

    // -- EX1: classify, compute magnitudes and count leading zeros in parallel --
    val req = io.in.bits
    val a = req.src1
    val b = req.src2
    val fp = req.op >= FDIV.U
    val sqrt = req.op === FSQRT.U
    val remainder = req.op(1)
    val signedInteger = !fp && !req.op(0)
    val ea = a(30, 23)
    val eb = b(30, 23)
    val az = a(30, 0) === 0.U
    val bz = b(30, 0) === 0.U
    val ai = a(30, 0) === "h7f800000".U
    val bi = b(30, 0) === "h7f800000".U
    val an = ea.andR && a(22, 0).orR
    val bn = eb.andR && b(22, 0).orR
    val asn = an && !a(22)
    val bsn = bn && !b(22)
    val sourceA = Mux(fp, Cat(ea.orR, a(22, 0), 0.U(8.W)), a)
    val sourceB = Mux(fp, Cat(eb.orR, b(22, 0), 0.U(8.W)), b)
    val (magnitudeA, leadingA) = magnitudeAndLeadingZeros(sourceA, signedInteger && a(31))
    val (magnitudeB, leadingB) = magnitudeAndLeadingZeros(sourceB, signedInteger && b(31))
    val s1 = Wire(new DivStage1(tagWidth))
    s1.a := magnitudeA
    s1.b := magnitudeB
    s1.leadingA := leadingA
    s1.leadingB := leadingB
    s1.exponentA := Mux(ea.orR, ea.zext - 127.S, (-126).S)
    s1.exponentB := Mux(eb.orR, eb.zext - 127.S, (-126).S)
    s1.meta.op := req.op
    s1.meta.roundingMode := req.roundingMode
    s1.meta.tag := req.tag
    s1.meta.sign := Mux(
        fp,
        Mux(sqrt, false.B, a(31) ^ b(31)),
        signedInteger && Mux(remainder, a(31), a(31) ^ b(31))
    )
    s1.meta.special := false.B
    // Also prepares the final result for |A| < |B|, detected from R12 in EX2.
    s1.meta.specialBits := Mux(remainder, a, 0.U)
    s1.meta.specialFlags := 0.U
    when(fp) {
        val invalid = asn || Mux(sqrt, a(31) && !az && !an, bsn || (az && bz) || (ai && bi))
        val nan = an || (!sqrt && bn) || invalid
        val divideZero = !sqrt && !an && !bn && !ai && !az && bz
        val resultSign = Mux(sqrt, a(31), a(31) ^ b(31))
        val infinity = Mux(sqrt, ai, ai || bz)
        s1.meta.special := nan || az || ai || (!sqrt && (bz || bi))
        s1.meta.specialBits := Mux(
            nan,
            "h7fc00000".U,
            Cat(resultSign, Mux(infinity, "h7f800000".U(31.W), 0.U(31.W)))
        )
        s1.meta.specialFlags := Cat(invalid, divideZero, 0.U(3.W))
    }.otherwise {
        val overflow = signedInteger && a === "h80000000".U && b === "hffffffff".U
        s1.meta.special := b === 0.U || overflow || a === 0.U
        when(b === 0.U) {
            s1.meta.specialBits := Mux(remainder, a, "hffffffff".U)
        }.elsewhen(overflow) {
            s1.meta.specialBits := Mux(remainder, 0.U, a)
        }
    }

    // -- EX2 INIT: normalize once and initialize the shared recurrence registers --
    val fp1 = r1.meta.op >= FDIV.U
    val sqrt1 = r1.meta.op === FSQRT.U
    val special1 = r1.meta.special || (!fp1 && r1.a < r1.b)
    val normalizedA = normalizeLeft32(r1.a, r1.leadingA)
    val normalizedB = normalizeLeft32(r1.b, r1.leadingB)
    val divisor = Cat(0.U(3.W), normalizedB, 0.U(1.W))
    val dividend = Cat(0.U(3.W), normalizedA, 0.U(1.W))
    val delta = r1.leadingB - r1.leadingA
    val evenDelta = delta + delta(0)
    val exponentA = r1.exponentA.pad(11) - r1.leadingA.zext
    val exponentB = r1.exponentB.pad(11) - r1.leadingB.zext
    val radicand = Mux(exponentA.asUInt(0), dividend >> 1, dividend >> 2)
    val initial = Mux(fp1, dividend, Mux(delta(0), dividend >> 1, dividend))

    // -- EX2 ITERATE: one signed radix-4 digit per cycle, with carry-save feedback --
    val step = Module(new SRT4Iteration)
    step.io.sqrt := r2.meta.op === FSQRT.U
    step.io.first := iterFirst
    step.io.divisor := r2.multiple(Width - 3, 0)
    step.io.negativeDivisor := iterNegativeDivisor
    step.io.sum := r2.residual
    step.io.carry := iterCarry
    step.io.result := r2.result
    step.io.resultMinus := r2.resultMinus
    step.io.position := iterPosition(Width - 1, 2)

    // -- EX2 RESOLVE: residual CPA and correction-multiple generation in parallel --
    val resolved = r2.residual + iterCarry
    val lastBit = iterPosition & ~(iterPosition << 1)
    val rootCorrection = ((r2.resultMinus.pad(Width) << 3) | (lastBit << 2))(Width - 1, 0)
    val correction = Mux(r2.meta.op === FSQRT.U, rootCorrection, (r2.multiple << 2)(Width - 1, 0))

    // -- EX3: correct residual/result; form the unrounded FP window or integer word --
    val negative = r2.residual(Width - 1)
    val adjusted = BLevelPAdder36.sum(r2.residual, r2.multiple, 0.U)
    val correctedRemainder = Mux(negative, adjusted, r2.residual)
    val correctedResult = Mux(negative, r2.resultMinus, r2.result)
    val lowerThanOne = !correctedResult(Fraction)
    val normalized = Mux(lowerThanOne, (correctedResult << 1)(ResultWidth - 1, 0), correctedResult)
    val window = Cat(
        normalized(Fraction, Fraction - 25),
        normalized(Fraction - 26, 0).orR || correctedRemainder.orR
    )
    val s3 = Wire(new DivStage3(tagWidth))
    s3.data := Mux(
        r2.meta.op >= FDIV.U,
        window.pad(Width),
        Mux(r2.meta.op(1), correctedRemainder, correctedResult.pad(Width))
    )
    s3.exponent := r2.exponent - lowerThanOne.asUInt.zext
    s3.integerShift := r2.integerShift
    s3.meta := r2.meta

    // -- EX4: round FP once, restore integer scale/sign, and register every response --
    val (floatResult, floatFlags) = round(r3.data(26, 0), r3.exponent, r3.meta.sign, r3.meta.roundingMode)
    val unsignedInteger = SRT4Logic.integerWindow(r3.data, r3.integerShift)
    val integerResult = Mux(r3.meta.sign, -unsignedInteger, unsignedInteger)
    val s4 = Wire(new DivideResponse(tagWidth))
    val floatOperation = r3.meta.op === FDIV.U || r3.meta.op === FSQRT.U
    s4.tag := r3.meta.tag
    s4.res := Mux(r3.meta.special, r3.meta.specialBits, Mux(floatOperation, floatResult, integerResult))
    s4.fflags := Mux(r3.meta.special, r3.meta.specialFlags, Mux(floatOperation, floatFlags, 0.U))
    io.wbInput.valid := ready4 && v3 && active
    io.wbInput.bits.res := s4.res
    io.wbInput.bits.tag := MixArithWakeTag.fromUInt(s4.tag)

    // Each stage advances independently. EX2 busy blocks R12 consumption, never EX3/EX4 drain.
    when(io.flush) {
        v1 := false.B
        v2 := false.B
        v3 := false.B
        v4 := false.B
        ex2Busy := false.B
        ex2Resolving := false.B
    }.otherwise {
        when(ready4) {
            v4 := v3
            when(v3) { r4 := s4 }
        }
        when(ready3) {
            v3 := v2
            when(v2) { r3 := s3 }
        }
        when(ready1) {
            v1 := io.in.valid
            when(io.in.valid) { r1 := s1 }
        }
        when(ready2) {
            v2 := false.B
            when(v1) {
                r2.meta := r1.meta
                r2.meta.special := special1
                r2.residual := Mux(
                    sqrt1,
                    ((radicand - (BigInt(1) << Fraction).U(Width.W)) << 2)(Width - 1, 0),
                    initial
                )
                r2.multiple := divisor
                r2.result := Mux(sqrt1, (BigInt(1) << Fraction).U, 0.U)
                r2.resultMinus := 0.U
                r2.exponent := Mux(sqrt1, (exponentA >> 1) + 1.S, exponentA - exponentB)
                r2.integerShift := Mux(r1.meta.op(1), 3.U + r1.leadingB, Fraction.U - evenDelta)
                iterCarry := 0.U
                iterNegativeDivisor := (-divisor)(Width - 3, 0)
                iterPosition := Mux(
                    sqrt1,
                    (-(BigInt(1) << Fraction)).S(Width.W).asUInt,
                    (-(BigInt(4) << Fraction)).S(Width.W).asUInt
                )
                iterCount := Mux(fp1, Mux(sqrt1, 13.U, 14.U), (evenDelta >> 1) + 1.U)
                iterFirst := true.B
                when(special1) { v2 := true.B }
                    .otherwise { ex2Busy := true.B }
            }
        }
        when(ex2Busy && !ex2Resolving) {
            r2.residual := step.io.nextSum
            iterCarry := step.io.nextCarry
            r2.result := step.io.nextResult
            r2.resultMinus := step.io.nextMinus
            iterPosition := step.io.nextPosition
            iterFirst := false.B
            iterCount := iterCount - 1.U
            when(iterCount === 1.U) { ex2Resolving := true.B }
        }
        when(ex2Resolving) {
            r2.residual := resolved
            // D is no longer needed; its existing register becomes the correction multiple.
            r2.multiple := correction
            v2 := true.B
            ex2Busy := false.B
            ex2Resolving := false.B
        }
    }
}
