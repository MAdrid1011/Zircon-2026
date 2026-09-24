// SPDX-License-Identifier: Apache-2.0
// Integer Booth organization follows Zircon-2024 b9f7b2b.
// FP decode/rounding and Brent-Kung construction adapted from Zircon-ASIC
// c128eefa (hardware/src/main/scala/zircon/{Floating,Integer}.scala, Apache-2.0).
import chisel3._
import chisel3.util._
import ZirconConfig.MultiplyOp._
import ZirconUtil.Log2Rev

class MultiplyRequest(val tagWidth: Int) extends Bundle {
    val src1 = UInt(32.W)
    val src2 = UInt(32.W)
    val src3 = UInt(32.W)
    // FP-only views exclude integer-only bypass producers from exponent alignment.
    val fpSrc1 = UInt(32.W)
    val fpSrc2 = UInt(32.W)
    val fpSrc3 = UInt(32.W)
    val fpExp1 = UInt(8.W)
    val fpExp2 = UInt(8.W)
    val fpExp3 = UInt(8.W)
    val fpZero1 = Bool()
    val fpZero2 = Bool()
    val fpZero3 = Bool()
    val op = UInt(4.W)
    // Already resolved by issue: RNE=0, RTZ=1, RDN=2, RUP=3, RMM=4.
    val roundingMode = UInt(3.W)
    val tag = UInt(tagWidth.W)
}
class MultiplyResponse(val tagWidth: Int) extends Bundle {
    val res = UInt(32.W)
    val fflags = UInt(5.W)
    val tag = UInt(tagWidth.W)
}
class MultiplyWbInput extends Bundle {
    val res = UInt(32.W)
    val tag = new MixArithWakeTag
}
class MultiplyIO(tagWidth: Int) extends Bundle {
    val in = Flipped(Decoupled(new MultiplyRequest(tagWidth)))
    val out = Decoupled(new MultiplyResponse(tagWidth))
    val wbInput = Output(Valid(new MultiplyWbInput))
    val present = Output(Bool())
    val flush = Input(Bool())
}
class MulFpDecoded extends Bundle {
    val sign = Bool()
    val sig = UInt(24.W)
    val zero = Bool()
    val inf = Bool()
    val nan = Bool()
    val snan = Bool()
}
class MulMeta(tagWidth: Int) extends Bundle {
    val fp = Bool()
    val high = Bool()
    val rm = UInt(3.W)
    val tag = UInt(tagWidth.W)
    val special = Bool()
    val specialBits = UInt(32.W)
    val invalid = Bool()
}

/** R1: eight product rows and addend alignment controls, before fusion. */
class MulStage1(tagWidth: Int) extends Bundle {
    val pp = Vec(8, UInt(80.W))
    val useMul = Bool() // EX2 main input: true selects the product rows, false selects primary.
    val primary = UInt(51.W) // FADD/FSUB's larger-exponent term, or the far-FMA product sticky.
    val other = UInt(24.W) // FADD/FSUB's second term, FMA's C, or zero for plain multiplication.
    val shift = UInt(7.W)
    val right = Bool() // right/shift specify the alignment direction/distance of other.
    val exp = SInt(11.W) // Weight of fusion-window bit 0; unused for integer results.
    val baseSign = Bool()
    val subtract = Bool() // Subtract other when its effective sign differs from baseSign.
    val zeroSign = Bool() // Explicit sign for zero inputs or exact cancellation.
    val meta = new MulMeta(tagWidth)
}

/** R2: block candidates for the fused sum D, rather than the original S/C rows. */
class MulStage2(tagWidth: Int) extends Bundle {
    val exp = SInt(11.W)
    val baseSign = Bool()
    val zeroSign = Bool()
    val meta = new MulMeta(tagWidth)
    val positiveBlocks = Vec(10, new MulSpeculativeBlock) // Candidates for D; D may be negative.
    val negativeBlocks = Vec(10, new MulSpeculativeBlock) // Candidates for -D, used to select |D|.
}

/** One 8-bit block: sum, nonzero flag and leading bit for carry-in=0/1, plus block G/P. */
class MulSpeculativeBlock extends Bundle {
    val sum0 = UInt(8.W); val sum1 = UInt(8.W)
    val generate = Bool(); val propagate = Bool()
    val nonzero0 = Bool(); val nonzero1 = Bool()
    val top0 = UInt(3.W); val top1 = UInt(3.W)
}

/** R3: FP magnitude/normalization controls and the selected integer word for EX4. */
class MulStage3(tagWidth: Int) extends Bundle {
    val mag = UInt(80.W)
    val shift = UInt(7.W)
    val right = Bool()
    val sign = Bool()
    val zero = Bool()
    val top = SInt(11.W) // Binary exponent of the leading bit: exp + leading-bit index.
    val integer = UInt(32.W)
    val meta = new MulMeta(tagWidth)
}

object SharedMultiplyLogic {
    val Width = 80
    // Keep the significand in its encoded scale. For finite values, sig * 2^exp is
    // exact for both normal and subnormal operands. Delaying normalization until the
    // fused result removes three input LZDs from EX1 without changing arithmetic.
    def decode(bits: UInt): MulFpDecoded = {
        val d = Wire(new MulFpDecoded)
        val ef = bits(30, 23)
        val frac = bits(22, 0)
        val raw = Cat(ef.orR, frac)
        d.sign := bits(31)
        d.sig := raw
        d.zero := !raw.orR
        d.inf := ef.andR && !frac.orR
        d.nan := ef.andR && frac.orR
        d.snan := d.nan && !frac(22)
        d
    }

    /** Fixed-width barrel shift with jamming at each level, including overshift. */
    def rightJam(x: UInt, amount: UInt, width: Int): UInt = {
        require(x.getWidth == width)
        var value = x
        for (i <- 0 until log2Ceil(width)) {
            val distance = 1 << i
            val shifted = Cat(0.U(distance.W), value(width - 1, distance))
            val jammed = shifted | value(distance - 1, 0).orR.asUInt
            value = Mux(amount(i), jammed, value)
        }
        if (amount.getWidth > log2Ceil(width))
            Mux(amount(amount.getWidth - 1, log2Ceil(width)).orR, x.orR.asUInt, value)
        else value
    }
    def leftTruncate(x: UInt, amount: UInt, width: Int): UInt = {
        require(x.getWidth == width)
        var value = x
        for (i <- 0 until amount.getWidth) {
            val distance = 1 << i
            val shifted = if (distance < width) Cat(value(width - distance - 1, 0), 0.U(distance.W)) else 0.U(width.W)
            value = Mux(amount(i), shifted, value)
        }
        value
    }
    def shiftControl(delta: SInt): (UInt, Bool) = {
        require(delta.getWidth > 7)
        val bits = delta.asUInt
        val right = bits(delta.getWidth - 1)
        // Absolute value and saturation share the same two's-complement increment.
        // For the low seven bits, values 80..127 are exactly 1xxxxxx with bit 5 or 4 set.
        val distance = (bits ^ Fill(delta.getWidth, right)) + right.asUInt
        val overflow = distance(delta.getWidth - 1, 7).orR ||
            (distance(6) && (distance(5) || distance(4)))
        (Mux(overflow, Width.U(7.W), distance(6, 0)), right)
    }

    /** EX2 carry-select block: both carry-in cases and their exact leading bits. */
    def prepareBlock(a: UInt, b: UInt): MulSpeculativeBlock = {
        val o = Wire(new MulSpeculativeBlock)
        val p = (0 until 8).map(i => a(i) ^ b(i))
        val g = (0 until 8).map(i => a(i) && b(i))
        val prefixP = (0 until 8).map(i => p.take(i + 1).reduce(_ && _))
        val prefixG = (0 until 8).map(i =>
            (0 to i).map(j =>
                g(j) && p.slice(j + 1, i + 1).foldLeft(true.B)(_ && _)
            ).reduce(_ || _)
        )
        o.sum0 := VecInit((0 until 8).map(i => p(i) ^ (if (i == 0) false.B else prefixG(i - 1)))).asUInt
        o.sum1 :=
            VecInit((0 until 8).map(i => p(i) ^ (if (i == 0) true.B else prefixG(i - 1) || prefixP(i - 1)))).asUInt
        o.generate := prefixG.last; o.propagate := prefixP.last
        o.nonzero0 := o.sum0.orR; o.nonzero1 := o.sum1.orR
        o.top0 := ~Log2Rev(Reverse(o.sum0))(2, 0)
        o.top1 := ~Log2Rev(Reverse(o.sum1))(2, 0)
        o
    }

    /** EX3 exact block-carry resolution and highest-bit selection, with no LZA correction. */
    def resolveBlocks(blocks: Seq[MulSpeculativeBlock]): (UInt, UInt, Bool) = {
        require(blocks.size == 10)
        var gp = blocks.map(b => (b.generate, b.propagate))
        for (distance <- Seq(1, 2, 4, 8)) {
            val prev = gp
            gp = prev.indices.map(i =>
                if (i < distance) prev(i)
                else
                    (prev(i)._1 || (prev(i)._2 && prev(i - distance)._1), prev(i)._2 && prev(i - distance)._2)
            )
        }
        val carry = (0 until 10).map(i => if (i == 0) false.B else gp(i - 1)._1)
        val nonzero = blocks.indices.map(i => Mux(carry(i), blocks(i).nonzero1, blocks(i).nonzero0))
        val highest = blocks.indices.map(i => nonzero(i) && !nonzero.drop(i + 1).foldLeft(false.B)(_ || _))
        val tops = blocks.indices.map(i => Cat(i.U(4.W), Mux(carry(i), blocks(i).top1, blocks(i).top0)))
        // The prefix has already resolved every byte-boundary carry. Selecting the
        // corresponding local sum completes the adder without a second 64-bit CPA.
        val words = blocks.indices.map(i => Mux(carry(i), blocks(i).sum1, blocks(i).sum0))
        (VecInit(words).asUInt, Mux1H(highest.zip(tops)), !nonzero.reduce(_ || _))
    }

    // A 3:2 compressor produces sum/carry rows without word-wide carry propagation.
    // The carry row is already shifted to its correct bit weight.
    def compressLevel(rows: Seq[UInt]): Seq[UInt] = rows.grouped(3).flatMap {
        case Seq(a, b, c) => Seq(a ^ b ^ c, (((a & b) | (a & c) | (b & c)) << 1)(79, 0))
        case rest => rest
    }.toSeq

    /** Radix-4, with independent operand signedness and corrections at bit 2*i.
    * Every row is extended to the fusion width BEFORE compression.
    */
    def booth(a: UInt, b: UInt, aSigned: Bool, bSigned: Bool): (Seq[UInt], UInt) = {
        val aa = Cat(Fill(48, aSigned && a(31)), a)
        val bb = Cat(Fill(2, bSigned && b(31)), b, 0.U(1.W))
        val negatives = Wire(Vec(17, Bool()))
        val rows = (0 until 17).map { i =>
            val digit = bb(2 * i + 2, 2 * i)
            val one = digit(1) ^ digit(0)
            val two = !one && (digit(2) ^ digit(1))
            val negative = digit(2) && !digit.andR
            negatives(i) := negative
            val magnitude = Mux(one, aa, 0.U) | Mux(two, Cat(aa(78, 0), 0.U(1.W)), 0.U)
            ((magnitude ^ Fill(80, negative)) << (2 * i))(79, 0)
        }
        val correction = VecInit((0 until 33).map(i => if (i % 2 == 0) negatives(i / 2) else false.B)).asUInt
        (rows, correction)
    }
    def compress(rows: Seq[UInt]): Seq[UInt] = {
        var heap = rows
        while (heap.size > 2) {
            heap = compressLevel(heap)
        }
        heap
    }

    /** Brent-Kung, padding the prefix graph (not the arithmetic result) to a power of two. */
    def prefixAdd(a: UInt, b: UInt, width: Int, cin: Bool = false.B): UInt = {
        val size = 1 << log2Ceil(width)
        val aa = a.pad(size); val bb = b.pad(size)
        val p0 = (0 until size).map(i => aa(i) ^ bb(i))
        var gp = (0 until size).map(i => (aa(i) && bb(i), p0(i))).toVector
        gp = gp.updated(0, (gp(0)._1 || (p0(0) && cin), p0(0)))
        def combine(i: Int, j: Int): Unit = {
            val (g, p) = gp(i); val (h, q) = gp(j)
            gp = gp.updated(i, (g || (p && h), p && q))
        }
        var stride = 2
        while (stride <= size) {
            for (i <- stride - 1 until size by stride) combine(i, i - stride / 2)
            stride *= 2
        }
        stride = size / 2
        while (stride >= 2) {
            for (i <- stride + stride / 2 - 1 until size by stride) combine(i, i - stride / 2)
            stride /= 2
        }
        VecInit((0 until width).map(i => p0(i) ^ (if (i == 0) cin else gp(i - 1)._1))).asUInt
    }

    /** Shared FP EX4: normalize, extract GRS, round once and pack. Integer results bypass this. */
    def finish(x: MulStage3): (UInt, UInt) = {
        val mag = Mux(x.right, rightJam(x.mag, x.shift, 80), leftTruncate(x.mag, x.shift, 80))
        val window = mag(26, 0)
        val q = window(26, 3)
        val guard = window(2); val sticky = window(1, 0).orR
        val inexact = guard || sticky
        val rm = x.meta.rm
        def increment(g: Bool, s: Bool, lsb: Bool): Bool =
            (rm === 0.U && g && (s || lsb)) ||
                (rm === 4.U && g) ||
                (rm === 2.U && x.sign && (g || s)) ||
                (rm === 3.U && !x.sign && (g || s))
        val roundUp = increment(guard, sticky, q(0))
        val rounded = q + roundUp
        // Predict carry from the unrounded bits, in parallel with the significand increment.
        val carry = q.andR && roundUp
        // Tininess is tested after rounding at unbounded exponent precision. In
        // particular, rounding to the subnormal grid alone is not this test.
        val normCarry = window(25, 2).andR && increment(window(1), window(0), window(2))
        val tinyAfter = x.top < (-127).S || (x.top === (-127).S && !normCarry)
        // A 24-bit increment overflows only from all ones, so its normalized value is fixed.
        val sig = Mux(carry, "h800000".U(24.W), rounded)
        val exponentBase = Mux(x.top < (-126).S, (-126).S, x.top)
        val exponent = exponentBase + carry.asUInt.zext
        // Prepare both biased exponent cases before the rounding carry arrives.
        // Adding 128 modulo 256 only toggles bit 7 of the encoded exponent.
        val biased = (exponentBase + 127.S).asUInt
        val biasedCarry = Cat(!exponentBase.asUInt(7), exponentBase.asUInt(6, 0))
        val ef = Mux(!sig(23), 0.U(8.W), Mux(carry, biasedCarry, biased(7, 0)))
        val overflow = exponent > 127.S
        val toInf = rm === 0.U || rm === 4.U || (rm === 2.U && x.sign) || (rm === 3.U && !x.sign)
        val overflowBits = Mux(toInf, "h7f800000".U, "h7f7fffff".U)
        val normalBits = Cat(ef(7, 0), sig(22, 0))
        val magnitude = Mux(x.zero, 0.U, Mux(overflow, overflowBits, normalBits))
        val bits = Mux(x.meta.special, x.meta.specialBits, Cat(x.sign, magnitude(30, 0)))
        val flags = Mux(
            x.meta.special,
            Mux(x.meta.invalid, 16.U, 0.U),
            Mux(x.zero, 0.U, Mux(overflow, 5.U, Mux(inexact, Mux(tinyAfter, 3.U, 1.U), 0.U)))
        )
        (bits, flags)
    }
}

/** Four-stage shared integer multiply / FP32 add, multiply and fused multiply-add, II=1.
  *
  * FMA below covers FMADD/FMSUB/FNMSUB/FNMADD; all FP instructions use the .S format.
  * Integer operations are MUL/MULH/MULHSU/MULHU. The paths meet at EX2:
  *
  *   Integer operands / FP significands -> shared Booth/CSA --+
  *   FADD/FSUB's larger-exponent term -> primary -------------+-> main --+
  *   FADD/FSUB's other term / FMA's C -> alignment ----------------------+-> fusion CSA
  *
  *   EX2: fusion CSA -> block candidates (R2)
  *   EX3: resolve block carries -> D -> integer high/low word (R3) -> EX4: writeback
  *                                  -> FP |D| + shift control (R3) -> EX4: shift/round/pack
  *
  * FADD/FSUB bypass the product result and share the final adder and FP postprocessing.
  * FMUL supplies a zero addend. FMA adds C before final carry propagation and rounds only
  * in EX4. useMul selects the main input; it does not gate the Booth circuit. See EX1 for
  * the far-FMA window change, where primary carries the small product's sticky contribution.
  *
  * The four stages include output register R4. Output backpressure holds the entire pipeline.
  * Flush kills all entries and suppresses handshakes in that cycle. Payload needs no reset.
  * tag is opaque backend identity carried through the stages in meta.
  */
class MulBooth2Wallce(val tagWidth: Int = 32) extends Module {
    require(tagWidth > 0)
    val io = IO(new MultiplyIO(tagWidth))
    import SharedMultiplyLogic._

    // Pipeline control: s1/s2/s3/s4 are combinational outputs; r1/r2/r3/r4 register them.
    val r1 = Reg(new MulStage1(tagWidth))
    val r2 = Reg(new MulStage2(tagWidth))
    val r3 = Reg(new MulStage3(tagWidth))
    val r4 = Reg(new MultiplyResponse(tagWidth))
    val v1 = RegInit(false.B); val v2 = RegInit(false.B)
    val v3 = RegInit(false.B); val v4 = RegInit(false.B)
    val advance = !v4 || io.out.ready
    val active = !io.flush && !reset.asBool
    io.in.ready := advance && active
    io.present := v4
    io.out.valid := v4 && active
    io.out.bits := r4
    when(io.flush) { v1 := false.B; v2 := false.B; v3 := false.B; v4 := false.B }
        .elsewhen(advance) { v1 := io.in.valid; v2 := v1; v3 := v2; v4 := v3 }

    // -- EX1: select the operation form and prepare product/addend inputs ------------
    // 1. Decode effective signs. FSUB negates B; FMA variants negate the product and/or C.
    val req = io.in.bits
    val op = req.op
    val fp = op >= FADD
    val add = op === FADD || op === FSUB
    val fma = op >= FMADD
    val negProduct = op === FNMSUB || op === FNMADD
    val negAddend = op === FMSUB || op === FNMADD
    val a = decode(req.fpSrc1); val b = decode(req.fpSrc2); val c = decode(req.fpSrc3)
    // Encoded exponent zero represents the subnormal exponent field one. Keep
    // this compact scale through alignment; the common -150 bias cancels.
    val aExp = Cat(0.U(1.W), Mux(req.fpExp1.orR, req.fpExp1, 1.U(8.W)))
    val bExp = Cat(0.U(1.W), Mux(req.fpExp2.orR, req.fpExp2, 1.U(8.W)))
    val cExp = Cat(0.U(1.W), Mux(req.fpExp3.orR, req.fpExp3, 1.U(8.W)))
    val bs = b.sign ^ (op === FSUB)
    val ps = a.sign ^ b.sign ^ negProduct
    val cs = c.sign ^ negAddend
    val pzero = req.fpZero1 || req.fpZero2
    val peRaw = aExp +& bExp

    // 2. FMA normally uses a product-relative window. When C is too far above the product,
    //    switch to a C-relative window and retain a nonzero small product as a sticky unit
    //    with its original effective sign. delta compares C's leading-bit exponent with
    //    the highest possible leading-bit exponent of the product.
    // Share the exponent difference with addend alignment; fold 23 - 47 into -24.
    val far = fma && !req.fpZero3 && (pzero || (cExp +& 99.U) > peRaw)

    // 3. FADD/FSUB order terms by exponent, without comparing significands on equal exponents.
    //    EX3 resolves the sign of the difference. Fusion represents signs relative to baseSign.
    val bLarge = !req.fpZero2 && (req.fpZero1 || bExp > aExp)
    val large = Mux(bLarge, b, a); val small = Mux(bLarge, a, b)
    val largeExp = Mux(bLarge, bExp, aExp)
    val smallExp = Mux(bLarge, aExp, bExp)
    val largeSign = Mux(bLarge, bs, a.sign); val smallSign = Mux(bLarge, a.sign, bs)
    val baseSign = Mux(add, largeSign, ps)
    val otherSign = Mux(add, smallSign, cs)
    val hasOther = add || fma

    // 4. Shared Booth: integer operand signedness follows the opcode; FP significands are unsigned.
    //    Shift the first FP significand left by 3 so the unrounded product occupies bits [50:3].
    val input1 = Mux(fp, Cat(0.U(5.W), a.sig, 0.U(3.W)), req.src1)
    val input2 = Mux(fp, Cat(0.U(8.W), b.sig), req.src2)
    val (partials, correction) = booth(
        input1,
        input2,
        !fp && op =/= MULHU,
        !fp && op =/= MULHU && op =/= MULHSU
    )
    val s1 = Wire(new MulStage1(tagWidth))
    // 17 partial products + 1 Booth correction row: 18 -> 12 -> 8, two CSA levels before R1.
    s1.pp := VecInit(compressLevel(compressLevel(partials :+ correction.pad(80))))

    // 5. EX2 input convention. M denotes a decoded significand; e weights its bit 0.
    //    Operation    Main input                     other    Window bit-0 exponent E
    //    Integer MUL  Booth product                  0        unused
    //    FMUL         (Ma * Mb) << 3                  0        ea + eb - 3
    //    Normal FMA   (Ma * Mb) << 3                  Mc       ea + eb - 3
    //    FADD/FSUB    primary = Mlarge << 27          Msmall   elarge - 27
    //    Far FMA      primary = nonzero-product bit  Mc       ec - 54
    //    Align other by its exponent minus E; rightJam preserves discarded bits as sticky.
    s1.useMul := !add && !far
    s1.primary := Mux(add, large.sig << 27, (far && !pzero).asUInt)
    s1.other := Mux(add, small.sig, Mux(fma, c.sig, 0.U))
    // Plain integer and FP multiplies have no aligned addend. Keeping their
    // shift at zero removes unused FP exponent alignment from the EX1 data cone.
    // Keep the no-addend integer/FP-multiply case off the exponent-alignment
    // arithmetic. The old form fed a wide zero-select Mux through shiftControl,
    // allowing operand exponent and bypass data to reach the R1 shift register
    // even though that result is unused for these operations.
    val addDistance = largeExp - smallExp
    val shiftCWithOther = Mux(
        add,
        27.S(11.W) - addDistance.zext,
        Mux(far, 54.S(11.W), cExp.zext - peRaw.zext + 153.S(11.W)),
    )
    val (alignShiftWithOther, alignRightWithOther) = shiftControl(shiftCWithOther)
    val alignShift = Mux(hasOther, alignShiftWithOther, 0.U(7.W))
    val alignRight = Mux(hasOther, alignRightWithOther, false.B)
    s1.shift := alignShift; s1.right := alignRight
    s1.exp := Mux(
        add,
        largeExp.zext - 177.S(11.W),
        Mux(far, cExp.zext - 204.S(11.W), peRaw.zext - 303.S(11.W)),
    )

    // 6. Opposite effective signs select main - aligned; preserve the exact-zero sign separately.
    s1.baseSign := baseSign
    s1.subtract := fp && hasOther && (baseSign =/= otherSign)
    s1.zeroSign := Mux(!hasOther || baseSign === otherSign, baseSign, req.roundingMode === 2.U)
    s1.meta.fp := fp; s1.meta.high := op =/= MUL
    s1.meta.rm := req.roundingMode; s1.meta.tag := req.tag

    // 7. Special-value bypass: carry NaN/Inf/invalid decisions in meta to override EX4's result.
    //    Only FMA consumes src3; FADD/FSUB use addition's invalid rules, not the 0 * Inf rule.
    val nan = a.nan || b.nan || (fma && c.nan)
    val invalidProduct = (a.inf && req.fpZero2) || (b.inf && req.fpZero1)
    val invalid = a.snan || b.snan || (fma && c.snan) ||
        Mux(
            add,
            !nan && a.inf && b.inf && (a.sign =/= bs),
            invalidProduct || (fma && !nan && (a.inf || b.inf) && c.inf && (ps =/= cs))
        )
    val inf = a.inf || b.inf || (fma && c.inf)
    val infSign = Mux(add, Mux(a.inf, a.sign, bs), Mux(a.inf || b.inf, ps, cs))
    s1.meta.special := fp && (nan || invalid || inf)
    s1.meta.specialBits := Mux(nan || invalid, "h7fc00000".U, Cat(infSign, "hff".U(8.W), 0.U(23.W)))
    s1.meta.invalid := invalid

    // -- EX2: fuse product/addend and precompute block candidates for the final adder --
    // 1. Parallel work: reduce the product to two rows and align other to the 80-bit window.
    val productRows = compress(r1.pp.toSeq)
    val other = Cat(0.U(56.W), r1.other)
    val aligned = Mux(r1.right, rightJam(other, r1.shift, 80), leftTruncate(other, r1.shift, 80))

    // 2. All paths meet here. useMul=false replaces the product rows with primary.
    //    D = main + (subtract ? -aligned : aligned); subtraction uses inversion plus one.
    //    fused remains two S/C rows: no word-wide carry propagation or FP product rounding yet.
    val fused = compress(Seq(
        Mux(r1.useMul, productRows(0), r1.primary.pad(80)),
        Mux(r1.useMul, productRows(1), 0.U(80.W)),
        aligned ^ Fill(80, r1.subtract),
        Cat(0.U(79.W), r1.subtract)
    ))
    val s2 = Wire(new MulStage2(tagWidth))
    s2.exp := r1.exp; s2.baseSign := r1.baseSign; s2.zeroSign := r1.zeroSign; s2.meta := r1.meta

    // 3. Prepare ten 8-bit blocks for both D and -D; -D = ~S + ~C + 2 (modulo 2^80).
    //    Each block stores sums/leading bits for both carry inputs; EX3 resolves block carries.
    val negRows = compress(Seq(~fused(0), ~fused(1), 2.U(80.W)))
    for (i <- 0 until 10) {
        s2.positiveBlocks(i) := prepareBlock(fused(0)(8 * i + 7, 8 * i), fused(1)(8 * i + 7, 8 * i))
        s2.negativeBlocks(i) := prepareBlock(negRows(0)(8 * i + 7, 8 * i), negRows(1)(8 * i + 7, 8 * i))
    }

    // -- EX3: finish shared addition; select the integer word and FP normalization controls --
    // 1. Resolve D/-D in parallel; FP uses D's sign to select |D| and its leading-bit index.
    val (d, positiveTop, zero) = resolveBlocks(r2.positiveBlocks.toSeq)
    val (negative, negativeTop, _) = resolveBlocks(r2.negativeBlocks.toSeq)
    val topBit = Mux(d(79), negativeTop, positiveTop)
    val top = prefixAdd(r2.exp.asUInt, Cat(0.U(4.W), topBit), 11).asSInt
    val s3 = Wire(new MulStage3(tagWidth))
    s3.mag := Mux(d(79), negative, d)
    // 2. FP: prepare normal/subnormal shift controls in parallel; EX4 performs the shift.
    //    Normal results place the leading bit at bit 26. Subnormals use a fixed E=-152 window,
    //    so bit 3 has the minimum-subnormal weight 2^-149 and bits [2:0] retain rounding data.
    val (tinyShift, tinyRight) = shiftControl(r2.exp + 152.S)
    val normalRight = topBit >= 26.U
    val normalShift = Mux(normalRight, topBit - 26.U, 26.U - topBit)
    s3.shift := Mux(top < (-126).S, tinyShift, normalShift)
    s3.right := Mux(top < (-126).S, tinyRight, normalRight)
    s3.zero := zero
    s3.sign := Mux(s3.zero, r2.zeroSign, r2.baseSign ^ d(79))
    s3.top := top

    // 3. Integer: select the high/low word from the original product D, not the FP magnitude.
    s3.integer := Mux(r2.meta.high, d(63, 32), d(31, 0))
    s3.meta := r2.meta

    // -- EX4: shared FP normalization/rounding, integer bypass and registered writeback --
    val (floatBits, floatFlags) = finish(r3)
    val s4 = Wire(new MultiplyResponse(tagWidth))
    s4.res := Mux(r3.meta.fp, floatBits, r3.integer)
    s4.fflags := Mux(r3.meta.fp, floatFlags, 0.U)
    s4.tag := r3.meta.tag
    io.wbInput.valid := advance && v3 && active
    io.wbInput.bits.res := s4.res
    io.wbInput.bits.tag := MixArithWakeTag.fromUInt(s4.tag)
    // Data contents are don't-care after flush; keep flush out of every wide register D path.
    when(advance) { r1 := s1; r2 := s2; r3 := s3; r4 := s4 }
}
