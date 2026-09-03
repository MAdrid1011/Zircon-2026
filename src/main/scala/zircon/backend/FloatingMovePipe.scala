package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.FloatingOperation

/** Captured operands for the executable RV32F E2 slice. */
class FloatingMoveRequest(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val operation = FloatingOperation()
  val roundingMode = UInt(3.W)
  val integerDestinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val integerSource = UInt(32.W)
  val floatSource = Vec(3, UInt(32.W))
  val floatDestination = UInt(5.W)
}

/** A completed E2 result, retained until the future ROB/result-queue bridge
  * accepts both the normal completion and optional architectural FPR result.
  */
class FloatingMoveResult(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val writesInteger = Bool()
  val integerDestinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val integerData = UInt(32.W)
  val writesFloat = Bool()
  val floatDestination = UInt(5.W)
  val floatData = UInt(32.W)
  val flags = UInt(5.W)
}

/** E2 execution for RV32F moves, sign injection, min/max, comparisons,
  * classification, and integer-to-single conversions.
  *
  * This module has no direct FPR, CSR, or ROB mutation path. Its retained
  * result is deliberately an internal boundary until the owner bridge can
  * atomically mark the ROB entry complete and reserve the FPR result for the
  * matching commit tag.
  */
class FloatingMovePipe(
    config: ZirconCoreConfig = ZirconCoreConfig.default,
    useExternalMultiplier: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FloatingMoveRequest(config)))
    val output = Decoupled(new FloatingMoveResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val multiplierEnable = Output(Bool())
    val multiplierLhs = Output(UInt(32.W))
    val multiplierRhs = Output(UInt(32.W))
    val multiplierProduct = Input(UInt(64.W))
  })

  private def supported(operation: FloatingOperation.Type): Bool =
    operation === FloatingOperation.FmvWX || operation === FloatingOperation.FmvXW ||
    operation === FloatingOperation.FmaddS || operation === FloatingOperation.FmsubS ||
    operation === FloatingOperation.FnmsubS || operation === FloatingOperation.FnmaddS ||
    operation === FloatingOperation.FsgnjS || operation === FloatingOperation.FsgnjnS ||
      operation === FloatingOperation.FsgnjxS || operation === FloatingOperation.FminS ||
      operation === FloatingOperation.FmaxS || operation === FloatingOperation.FleS ||
      operation === FloatingOperation.FltS || operation === FloatingOperation.FeqS ||
      operation === FloatingOperation.FaddS || operation === FloatingOperation.FsubS ||
      operation === FloatingOperation.FmulS || operation === FloatingOperation.FdivS ||
      operation === FloatingOperation.FsqrtS ||
      operation === FloatingOperation.FclassS || operation === FloatingOperation.FcvtSW ||
      operation === FloatingOperation.FcvtSWu || operation === FloatingOperation.FcvtWS ||
      operation === FloatingOperation.FcvtWuS

  val active = RegInit(false.B)
  val request = Reg(new FloatingMoveRequest(config))
  val divInitialized = RegInit(false.B)
  val divDividend = Reg(UInt(24.W))
  val divDivisor = Reg(UInt(25.W))
  val divQuotient = Reg(UInt(28.W))
  val divRemainder = Reg(UInt(25.W))
  val divIteration = RegInit(0.U(6.W))
  val divExponentBiased = Reg(UInt(10.W))
  val divSign = RegInit(false.B)
  val divSpecial = RegInit(false.B)
  val divSpecialResult = Reg(UInt(32.W))
  val divSpecialFlags = Reg(UInt(5.W))
  val fmaInitialized = RegInit(false.B)
  val fmaProduct = RegInit(0.U(48.W))
  val fmaProductSign = RegInit(false.B)
  val fmaProductTopCoord = RegInit(0.U(11.W))
  val fmaProductZero = RegInit(false.B)
  val fmaProductInfinity = RegInit(false.B)
  // Keep the wide FMA align/normalize cone behind a result register.  This
  // adds one E2 cycle for FMA, but prevents its 57-bit carry/priority network
  // from extending into the shared completion and memory-control paths.
  val fmaResultDataReg = RegInit(0.U(32.W))
  val fmaResultFlagsReg = RegInit(0.U(5.W))
  val fmaResultCaptured = RegInit(false.B)
  val sqrtInitialized = RegInit(false.B)
  val sqrtRadicand = Reg(UInt(54.W))
  val sqrtRemainder = Reg(UInt(56.W))
  val sqrtRoot = Reg(UInt(27.W))
  val sqrtIteration = RegInit(0.U(5.W))
  val sqrtExponentBiased = Reg(UInt(10.W))
  val sqrtSign = RegInit(false.B)
  val sqrtSpecial = RegInit(false.B)
  val sqrtSpecialResult = Reg(UInt(32.W))
  val sqrtSpecialFlags = Reg(UInt(5.W))
  val recoveryBlocked = io.flush || io.squash.valid

  // Standalone unit tests use an internal instance; the production core sets
  // useExternalMultiplier and connects this pipe to ZirconCore's one shared
  // resource.
  val localMultiplier = if (useExternalMultiplier) None else
    Some(Module(new ZirconSharedMultiplier))
  localMultiplier.foreach { multiplier =>
    multiplier.io.enable := io.multiplierEnable
    multiplier.io.lhs := io.multiplierLhs
    multiplier.io.rhs := io.multiplierRhs
  }

  val sign = MuxLookup(request.operation.asUInt, 0.U(1.W))(Seq(
    FloatingOperation.FsgnjS.asUInt -> request.floatSource(1)(31),
    FloatingOperation.FsgnjnS.asUInt -> !request.floatSource(1)(31),
    FloatingOperation.FsgnjxS.asUInt ->
      (request.floatSource(0)(31) ^ request.floatSource(1)(31))
  ))
  val signInjected = Cat(sign, request.floatSource(0)(30, 0))
  val lhs = request.floatSource(0)
  val rhs = request.floatSource(1)
  val arithmetic = request.operation === FloatingOperation.FaddS ||
    request.operation === FloatingOperation.FsubS
  val arithmeticSubtraction = request.operation === FloatingOperation.FsubS
  val multiplication = request.operation === FloatingOperation.FmulS
  val division = request.operation === FloatingOperation.FdivS
  val fma = request.operation === FloatingOperation.FmaddS ||
    request.operation === FloatingOperation.FmsubS ||
    request.operation === FloatingOperation.FnmsubS ||
    request.operation === FloatingOperation.FnmaddS
  val fmaProductNegated = request.operation === FloatingOperation.FnmsubS ||
    request.operation === FloatingOperation.FnmaddS
  val fmaAddendSubtracted = request.operation === FloatingOperation.FmsubS ||
    request.operation === FloatingOperation.FnmsubS
  val lhsExponent = lhs(30, 23)
  val rhsExponent = rhs(30, 23)
  val lhsFraction = lhs(22, 0)
  val rhsFraction = rhs(22, 0)
  val lhsNaN = lhsExponent === "hff".U && lhsFraction.orR
  val rhsNaN = rhsExponent === "hff".U && rhsFraction.orR
  val canonicalNaN = "h7fc00000".U(32.W)
  val lhsSignalingNaN = lhsNaN && !lhsFraction(22)
  val rhsSignalingNaN = rhsNaN && !rhsFraction(22)
  val rhsArithmeticSign = rhs(31) ^ arithmeticSubtraction
  val lhsInfinity = lhsExponent === "hff".U && !lhsFraction.orR
  val rhsInfinity = rhsExponent === "hff".U && !rhsFraction.orR
  val lhsZero = lhs(30, 0) === 0.U
  val rhsZero = rhs(30, 0) === 0.U

  // Add/subtract uses three low guard bits. Bit zero is sticky (jammed), so
  // a single integer adder can implement all five architectural modes.
  private def rightJam(value: UInt, shift: UInt, width: Int): UInt = {
    val sticky = WireDefault(false.B)
    when(shift >= width.U) {
      sticky := value.orR
    }
    for (amount <- 1 until width) {
      when(shift === amount.U) {
        sticky := value(amount - 1, 0).orR
      }
    }
    (value >> shift) | sticky.asUInt
  }
  private def rightJamMasked(value: UInt, shift: UInt, width: Int): UInt = {
    // A dynamic shift plus one generated low-bit mask keeps the wide FMA
    // alignment cone out of the per-bit equality muxes used by the narrow
    // legacy paths above.
    val mask = Mux(shift >= width.U, Fill(width, 1.U(1.W)),
      ((1.U(width.W) << shift)(width - 1, 0) - 1.U(width.W)))
    (value >> shift) | (value & mask).orR.asUInt
  }
  val lhsArithmeticSignificand = Mux(lhsExponent === 0.U,
    Cat(0.U(1.W), lhsFraction), Cat(1.U(1.W), lhsFraction))
  val rhsArithmeticSignificand = Mux(rhsExponent === 0.U,
    Cat(0.U(1.W), rhsFraction), Cat(1.U(1.W), rhsFraction))
  val lhsArithmeticExponent = Mux(lhsExponent === 0.U, 1.U(9.W), lhsExponent)
  val rhsArithmeticExponent = Mux(rhsExponent === 0.U, 1.U(9.W), rhsExponent)
  val arithmeticBaseExponent = Mux(lhsArithmeticExponent >= rhsArithmeticExponent,
    lhsArithmeticExponent, rhsArithmeticExponent)
  val lhsArithmeticShift = arithmeticBaseExponent - lhsArithmeticExponent
  val rhsArithmeticShift = arithmeticBaseExponent - rhsArithmeticExponent
  val lhsArithmeticExtended = rightJam(Cat(lhsArithmeticSignificand, 0.U(3.W)),
    lhsArithmeticShift, 27)
  val rhsArithmeticExtended = rightJam(Cat(rhsArithmeticSignificand, 0.U(3.W)),
    rhsArithmeticShift, 27)
  val sameArithmeticSign = lhs(31) === rhsArithmeticSign
  val lhsMagnitudeAtBase = lhsArithmeticExtended
  val rhsMagnitudeAtBase = rhsArithmeticExtended
  val lhsAtLeastMagnitude = lhsMagnitudeAtBase >= rhsMagnitudeAtBase
  val arithmeticRawSum = (Cat(0.U(1.W), lhsMagnitudeAtBase) +&
    Cat(0.U(1.W), rhsMagnitudeAtBase))(27, 0)
  val arithmeticRawDifference = Mux(lhsAtLeastMagnitude,
    Cat(0.U(1.W), lhsMagnitudeAtBase) - Cat(0.U(1.W), rhsMagnitudeAtBase),
    Cat(0.U(1.W), rhsMagnitudeAtBase) - Cat(0.U(1.W), lhsMagnitudeAtBase))
  val arithmeticRaw = Mux(sameArithmeticSign, arithmeticRawSum, arithmeticRawDifference)
  val arithmeticInitialSign = Mux(sameArithmeticSign, lhs(31),
    Mux(lhsAtLeastMagnitude, lhs(31), rhsArithmeticSign))
  val arithmeticCarry = sameArithmeticSign && arithmeticRaw(27)
  val arithmeticPreRound = Mux(arithmeticCarry,
    rightJam(arithmeticRaw, 1.U, 28), arithmeticRaw)
  val arithmeticPreExponent = Mux(arithmeticCarry,
    arithmeticBaseExponent + 1.U, arithmeticBaseExponent)
  val arithmeticLeadingShift = PriorityEncoder(Reverse(arithmeticPreRound(26, 0)))
  val arithmeticSubtractionShift = Mux(arithmeticPreExponent > 1.U &&
    arithmeticLeadingShift > (arithmeticPreExponent - 1.U),
    arithmeticPreExponent - 1.U, arithmeticLeadingShift)
  val arithmeticNormalizeShift = Mux(arithmeticPreRound.orR &&
    !arithmeticCarry && arithmeticPreExponent > 1.U,
    arithmeticSubtractionShift, 0.U)
  val arithmeticNormalized = (arithmeticPreRound << arithmeticNormalizeShift)(26, 0)
  val arithmeticNormalizedExponent = arithmeticPreExponent - arithmeticNormalizeShift
  val arithmeticInexact = arithmeticNormalized(2, 0).orR
  val arithmeticRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (arithmeticNormalized(2) &&
      (arithmeticNormalized(1, 0).orR || arithmeticNormalized(3))),
    1.U -> false.B,
    2.U -> (arithmeticInitialSign && arithmeticInexact),
    3.U -> (!arithmeticInitialSign && arithmeticInexact),
    4.U -> arithmeticNormalized(2)
  ))
  val arithmeticRoundedSignificand = Cat(0.U(1.W), arithmeticNormalized(26, 3)) +
    arithmeticRoundUp.asUInt
  val arithmeticRoundedCarry = arithmeticRoundedSignificand(24)
  val arithmeticTiny = arithmeticNormalizedExponent === 1.U && !arithmeticNormalized(26)
  // The unbiased exponent represented by an IEEE exponent field of 255 is
  // already outside the finite range; rounding can raise a 254 result into it.
  val arithmeticOverflow = arithmeticNormalizedExponent >= 255.U ||
    (arithmeticNormalizedExponent === 254.U && arithmeticRoundedCarry)
  val arithmeticOverflowToInfinity = request.roundingMode === 0.U ||
    request.roundingMode === 4.U ||
    (request.roundingMode === 3.U && !arithmeticInitialSign) ||
    (request.roundingMode === 2.U && arithmeticInitialSign)
  val arithmeticOutputExponent = Mux(arithmeticOverflow,
    Mux(arithmeticOverflowToInfinity, 255.U(9.W), 254.U(9.W)),
    Mux(arithmeticRoundedCarry, arithmeticNormalizedExponent + 1.U,
      Mux(arithmeticTiny, 0.U(9.W), arithmeticNormalizedExponent)))
  val arithmeticOutputFraction = Mux(arithmeticOverflow && arithmeticOverflowToInfinity,
    0.U(23.W),
    Mux(arithmeticOverflow, "h7fffff".U(23.W),
      Mux(arithmeticRoundedCarry, 0.U(23.W), arithmeticRoundedSignificand(22, 0))))
  val arithmeticFiniteData = Cat(arithmeticInitialSign, arithmeticOutputExponent(7, 0),
    arithmeticOutputFraction)
  val arithmeticInvalid = (lhsInfinity && rhsInfinity &&
    lhs(31) =/= rhsArithmeticSign) || lhsSignalingNaN || rhsSignalingNaN
  val arithmeticSpecialData = Mux(arithmeticInvalid, canonicalNaN,
    Mux(lhsNaN || rhsNaN, canonicalNaN,
      Mux(lhsInfinity, Cat(lhs(31), "hff".U(8.W), 0.U(23.W)),
        Mux(rhsInfinity, Cat(rhsArithmeticSign, "hff".U(8.W), 0.U(23.W)),
          arithmeticFiniteData))))
  val arithmeticSpecial = arithmeticInvalid || lhsNaN || rhsNaN || lhsInfinity || rhsInfinity
  val arithmeticZeroSign = Mux(sameArithmeticSign, lhs(31), request.roundingMode === 2.U)
  val arithmeticResultData = Mux(arithmeticSpecial, arithmeticSpecialData,
    Mux(arithmeticRaw.orR, arithmeticFiniteData,
      Cat(arithmeticZeroSign,
        0.U(31.W))))
  val arithmeticFlags = Mux(arithmeticInvalid, "b10000".U,
    Mux(lhsNaN || rhsNaN || lhsInfinity || rhsInfinity, 0.U(5.W),
      Mux(arithmeticOverflow, "b00101".U,
        Cat(0.U(3.W), Mux(arithmeticTiny && arithmeticInexact, 1.U(1.W), 0.U(1.W)),
          arithmeticInexact))))

  val multiplicationSign = lhs(31) ^ rhs(31)
  val multiplicationInvalid = (lhsInfinity && rhsZero) ||
    (rhsInfinity && lhsZero) || lhsSignalingNaN || rhsSignalingNaN
  val multiplicationLhsLeading = PriorityEncoder(Reverse(lhsArithmeticSignificand))
  val multiplicationRhsLeading = PriorityEncoder(Reverse(rhsArithmeticSignificand))
  val multiplicationLhsShift = Mux(lhsZero, 0.U(5.W),
    multiplicationLhsLeading)
  val multiplicationRhsShift = Mux(rhsZero, 0.U(5.W),
    multiplicationRhsLeading)
  val multiplicationLhsSignificand = (lhsArithmeticSignificand << multiplicationLhsShift)(23, 0)
  val multiplicationRhsSignificand = (rhsArithmeticSignificand << multiplicationRhsShift)(23, 0)
  // Bias the normalized exponents by 256 so subnormal products never wrap
  // around an unsigned Chisel subtraction.
  val multiplicationLhsExponent =
    ((Cat(0.U(1.W), lhsArithmeticExponent) +& 256.U)(9, 0) - multiplicationLhsShift)
  val multiplicationRhsExponent =
    ((Cat(0.U(1.W), rhsArithmeticExponent) +& 256.U)(9, 0) - multiplicationRhsShift)
  io.multiplierEnable := active && (multiplication || fma)
  io.multiplierLhs := Cat(0.U(8.W), multiplicationLhsSignificand)
  io.multiplierRhs := Cat(0.U(8.W), multiplicationRhsSignificand)
  val multiplicationProduct = localMultiplier.map(_.io.product(47, 0)).getOrElse(
    io.multiplierProduct(47, 0))
  val multiplicationProductHigh = multiplicationProduct(47)
  val multiplicationProductShift = Mux(multiplicationProductHigh, 21.U, 20.U)
  val multiplicationNormalized = rightJam(multiplicationProduct,
    multiplicationProductShift, 48)(26, 0)
  val multiplicationExponentSum = Cat(0.U(1.W), multiplicationLhsExponent) +&
    Cat(0.U(1.W), multiplicationRhsExponent)
  val multiplicationExponentBiased = multiplicationExponentSum - 383.U(11.W)
  val multiplicationSubnormalShift = Mux(multiplicationExponentBiased < 257.U,
    257.U(11.W) - multiplicationExponentBiased, 0.U(11.W))
  val multiplicationRoundedBaseExponent = Mux(multiplicationExponentBiased < 257.U,
    1.U(11.W), multiplicationExponentBiased - 256.U)
  val multiplicationRoundedInput = rightJam(multiplicationNormalized,
    multiplicationSubnormalShift, 27)
  val multiplicationInexact = multiplicationRoundedInput(2, 0).orR
  val multiplicationRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (multiplicationRoundedInput(2) &&
      (multiplicationRoundedInput(1, 0).orR || multiplicationRoundedInput(3))),
    1.U -> false.B,
    2.U -> (multiplicationSign && multiplicationInexact),
    3.U -> (!multiplicationSign && multiplicationInexact),
    4.U -> multiplicationRoundedInput(2)
  ))
  val multiplicationRoundedSignificand = Cat(0.U(1.W), multiplicationRoundedInput(26, 3)) +
    multiplicationRoundUp.asUInt
  val multiplicationRoundedCarry = multiplicationRoundedSignificand(24)
  val multiplicationTiny = multiplicationRoundedBaseExponent <= 1.U &&
    !multiplicationRoundedInput(26)
  val multiplicationOverflow = multiplicationRoundedBaseExponent >= 255.U ||
    (multiplicationRoundedBaseExponent === 254.U && multiplicationRoundedCarry)
  val multiplicationOverflowToInfinity = request.roundingMode === 0.U ||
    request.roundingMode === 4.U ||
    (request.roundingMode === 3.U && !multiplicationSign) ||
    (request.roundingMode === 2.U && multiplicationSign)
  val multiplicationOutputExponent = Mux(multiplicationOverflow,
    Mux(multiplicationOverflowToInfinity, 255.U(10.W), 254.U(10.W)),
    Mux(multiplicationRoundedCarry, multiplicationRoundedBaseExponent + 1.U,
      Mux(multiplicationTiny, 0.U(10.W), multiplicationRoundedBaseExponent)))
  val multiplicationOutputFraction = Mux(multiplicationOverflow &&
    multiplicationOverflowToInfinity, 0.U(23.W),
    Mux(multiplicationOverflow, "h7fffff".U(23.W),
      Mux(multiplicationRoundedCarry, 0.U(23.W),
        multiplicationRoundedSignificand(22, 0))))
  val multiplicationFiniteData = Cat(multiplicationSign,
    multiplicationOutputExponent(7, 0), multiplicationOutputFraction)
  val multiplicationSpecial = multiplicationInvalid || lhsNaN || rhsNaN ||
    lhsInfinity || rhsInfinity
  val multiplicationSpecialData = Mux(multiplicationInvalid, canonicalNaN,
    Mux(lhsNaN || rhsNaN, canonicalNaN,
      Mux(lhsInfinity, Cat(multiplicationSign, "hff".U(8.W), 0.U(23.W)),
        Mux(rhsInfinity, Cat(multiplicationSign, "hff".U(8.W), 0.U(23.W)),
          multiplicationFiniteData))))
  val multiplicationResultData = Mux(multiplicationSpecial, multiplicationSpecialData,
    Mux(multiplicationProduct.orR, multiplicationFiniteData,
      Cat(multiplicationSign, 0.U(31.W))))
  val multiplicationFlags = Mux(multiplicationInvalid, "b10000".U,
    Mux(lhsNaN || rhsNaN || lhsInfinity || rhsInfinity, 0.U(5.W),
      Mux(multiplicationOverflow, "b00101".U,
        Cat(0.U(3.W), Mux(multiplicationTiny && multiplicationInexact,
          1.U(1.W), 0.U(1.W)), multiplicationInexact))))

  // FMA keeps the complete product until the addend has been aligned.  The
  // common representation places each operand's most significant bit at bit
  // 52, leaving enough room for the 48-bit product, cancellation, and GRS
  // bits before the single architectural rounding step.
  val fmaAddend = request.floatSource(2)
  val fmaAddendExponent = fmaAddend(30, 23)
  val fmaAddendFraction = fmaAddend(22, 0)
  val fmaAddendNaN = fmaAddendExponent === "hff".U && fmaAddendFraction.orR
  val fmaAddendSignalingNaN = fmaAddendNaN && !fmaAddendFraction(22)
  val fmaAddendInfinity = fmaAddendExponent === "hff".U && !fmaAddendFraction.orR
  val fmaAddendZero = fmaAddend(30, 0) === 0.U
  val fmaAddendRawSignificand = Mux(fmaAddendExponent === 0.U,
    Cat(0.U(1.W), fmaAddendFraction), Cat(1.U(1.W), fmaAddendFraction))
  val fmaAddendLeadingShift = Mux(fmaAddendZero, 0.U(5.W),
    PriorityEncoder(Reverse(fmaAddendRawSignificand)))
  val fmaAddendSignificand = (fmaAddendRawSignificand << fmaAddendLeadingShift)(23, 0)
  val fmaAddendTopCoord = Mux(fmaAddendZero, 0.U(11.W),
    Mux(fmaAddendExponent === 0.U,
      513.U(11.W) - fmaAddendLeadingShift,
      512.U(11.W) + fmaAddendExponent))

  val fmaLhsZero = lhs(30, 0) === 0.U
  val fmaRhsZero = rhs(30, 0) === 0.U
  // FMUL and FMA are mutually exclusive in this single-entry E2 pipe. Reuse
  // the normalized operands and product node so the FPU does not infer a
  // second multiplier/DSP for the fused path.
  val fmaLhsLeadingShift = multiplicationLhsShift
  val fmaRhsLeadingShift = multiplicationRhsShift
  val fmaLhsSignificand = multiplicationLhsSignificand
  val fmaRhsSignificand = multiplicationRhsSignificand
  val fmaLhsTopCoord = Mux(fmaLhsZero, 0.U(11.W),
    Mux(lhsExponent === 0.U, 513.U(11.W) - fmaLhsLeadingShift,
      512.U(11.W) + lhsExponent))
  val fmaRhsTopCoord = Mux(fmaRhsZero, 0.U(11.W),
    Mux(rhsExponent === 0.U, 513.U(11.W) - fmaRhsLeadingShift,
      512.U(11.W) + rhsExponent))
  val fmaProductRaw = multiplicationProduct
  val fmaProductRawZero = fmaLhsZero || fmaRhsZero
  val fmaProductRawTopCoord = (Cat(0.U(1.W), fmaLhsTopCoord) +&
    Cat(0.U(1.W), fmaRhsTopCoord) -
    Mux(fmaProductRaw(47), 638.U(12.W), 639.U(12.W)))(10, 0)
  val fmaProductRawSign = lhs(31) ^ rhs(31) ^ fmaProductNegated
  val fmaProductTopForAlign = Mux(fmaProductZero,
    fmaAddendTopCoord, fmaProductTopCoord)
  val fmaAddendTopForAlign = Mux(fmaAddendZero,
    fmaProductTopForAlign, fmaAddendTopCoord)
  val fmaCommonTopCoord = Mux(fmaProductTopForAlign >= fmaAddendTopForAlign,
    fmaProductTopForAlign, fmaAddendTopForAlign)
  val fmaProductAligned = (Cat(0.U(8.W), fmaProduct) <<
    Mux(fmaProduct(47), 5.U(6.W), 6.U(6.W)))(55, 0)
  val fmaAddendAligned = Cat(0.U(3.W), fmaAddendSignificand, 0.U(29.W))
  val fmaProductAtCommonTop = rightJamMasked(fmaProductAligned,
    fmaCommonTopCoord - fmaProductTopForAlign, 56)
  val fmaAddendAtCommonTop = rightJamMasked(fmaAddendAligned,
    fmaCommonTopCoord - fmaAddendTopForAlign, 56)
  val fmaProductEffectiveSign = fmaProductSign
  val fmaAddendEffectiveSign = fmaAddend(31) ^ fmaAddendSubtracted
  val fmaSameSign = fmaProductEffectiveSign === fmaAddendEffectiveSign
  val fmaMagnitudeSum = (Cat(0.U(1.W), fmaProductAtCommonTop) +&
    Cat(0.U(1.W), fmaAddendAtCommonTop))(56, 0)
  val fmaMagnitudeDifference = Mux(fmaProductAtCommonTop >= fmaAddendAtCommonTop,
    Cat(0.U(1.W), fmaProductAtCommonTop) - Cat(0.U(1.W), fmaAddendAtCommonTop),
    Cat(0.U(1.W), fmaAddendAtCommonTop) - Cat(0.U(1.W), fmaProductAtCommonTop))
  val fmaMagnitude = Mux(fmaSameSign, fmaMagnitudeSum, fmaMagnitudeDifference)
  val fmaInitialSign = Mux(fmaSameSign, fmaProductEffectiveSign,
    Mux(fmaProductAtCommonTop >= fmaAddendAtCommonTop,
      fmaProductEffectiveSign, fmaAddendEffectiveSign))
  val fmaLeadingZeros = PriorityEncoder(Reverse(fmaMagnitude))
  val fmaLeadingBit = 56.U(6.W) - fmaLeadingZeros
  val fmaNormalized = Mux(fmaLeadingBit >= 26.U,
    rightJamMasked(fmaMagnitude, fmaLeadingBit - 26.U, 57),
    (fmaMagnitude << (26.U - fmaLeadingBit))(56, 0))
  val fmaTopCoordWide = Cat(0.U(1.W), fmaCommonTopCoord) +
    Cat(0.U(6.W), fmaLeadingBit) - 52.U(12.W)
  val fmaTopCoord = fmaTopCoordWide(10, 0)
  val fmaSubnormalShift = Mux(fmaTopCoord < 513.U,
    513.U(11.W) - fmaTopCoord, 0.U(11.W))
  val fmaRoundedInput = rightJamMasked(fmaNormalized, fmaSubnormalShift, 57)(26, 0)
  val fmaRoundedBaseExponent = Mux(fmaTopCoord < 513.U, 0.U(10.W),
    fmaTopCoord - 512.U)
  val fmaInexact = fmaRoundedInput(2, 0).orR
  val fmaRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (fmaRoundedInput(2) &&
      (fmaRoundedInput(1, 0).orR || fmaRoundedInput(3))),
    1.U -> false.B,
    2.U -> (fmaInitialSign && fmaInexact),
    3.U -> (!fmaInitialSign && fmaInexact),
    4.U -> fmaRoundedInput(2)
  ))
  val fmaRoundedSignificand = Cat(0.U(1.W), fmaRoundedInput(26, 3)) +
    fmaRoundUp.asUInt
  val fmaRoundedCarry = fmaRoundedSignificand(24)
  val fmaTiny = fmaRoundedBaseExponent === 0.U && !fmaRoundedInput(26)
  val fmaOverflow = fmaRoundedBaseExponent >= 255.U ||
    (fmaRoundedBaseExponent === 254.U && fmaRoundedCarry)
  val fmaOverflowToInfinity = request.roundingMode === 0.U ||
    request.roundingMode === 4.U ||
    (request.roundingMode === 3.U && !fmaInitialSign) ||
    (request.roundingMode === 2.U && fmaInitialSign)
  val fmaOutputExponent = Mux(fmaOverflow,
    Mux(fmaOverflowToInfinity, 255.U(10.W), 254.U(10.W)),
    Mux(fmaRoundedCarry, fmaRoundedBaseExponent + 1.U, fmaRoundedBaseExponent))
  val fmaOutputFraction = Mux(fmaOverflow && fmaOverflowToInfinity,
    0.U(23.W), Mux(fmaOverflow, "h7fffff".U(23.W),
      Mux(fmaRoundedCarry, 0.U(23.W), fmaRoundedSignificand(22, 0))))
  val fmaFiniteData = Cat(fmaInitialSign, fmaOutputExponent(7, 0),
    fmaOutputFraction)
  val fmaProductNaN = lhsNaN || rhsNaN
  val fmaProductInvalid = (lhsInfinity && fmaRhsZero) ||
    (rhsInfinity && fmaLhsZero)
  val fmaProductInf = fmaProductInfinity && !fmaProductZero
  val fmaInvalid = lhsSignalingNaN || rhsSignalingNaN || fmaAddendSignalingNaN ||
    fmaProductInvalid || (fmaProductInf && fmaAddendInfinity &&
      fmaProductEffectiveSign =/= fmaAddendEffectiveSign)
  val fmaSpecial = fmaInvalid || fmaProductNaN || fmaAddendNaN ||
    fmaProductInf || fmaAddendInfinity
  val fmaSpecialData = Mux(fmaInvalid, canonicalNaN,
    Mux(fmaProductNaN || fmaAddendNaN, canonicalNaN,
      Mux(fmaProductInf || fmaAddendInfinity,
        Cat(Mux(fmaProductInf, fmaProductEffectiveSign,
          fmaAddendEffectiveSign), "hff".U(8.W), 0.U(23.W)),
        fmaFiniteData)))
  val fmaResultData = Mux(fmaSpecial, fmaSpecialData,
    Mux(fmaMagnitude.orR, fmaFiniteData,
      Cat(Mux(fmaSameSign, fmaProductEffectiveSign,
        request.roundingMode === 2.U), 0.U(31.W))))
  val fmaFlags = Mux(fmaInvalid, "b10000".U,
    Mux(fmaProductNaN || fmaAddendNaN || fmaProductInf || fmaAddendInfinity,
      0.U(5.W), Mux(fmaOverflow, "b00101".U,
        Cat(0.U(3.W), Mux(fmaTiny && fmaInexact, 1.U(1.W), 0.U(1.W)),
          fmaInexact))))

  val divisionLhsFinite = !lhsNaN && !lhsInfinity
  val divisionRhsFinite = !rhsNaN && !rhsInfinity
  val divisionInvalid = lhsSignalingNaN || rhsSignalingNaN ||
    (lhsInfinity && rhsInfinity) || (lhsZero && rhsZero)
  val divisionByZero = rhsZero && !lhsZero && divisionLhsFinite && divisionRhsFinite
  val divisionSpecial = divisionInvalid || lhsNaN || rhsNaN || lhsInfinity || rhsInfinity ||
    lhsZero || rhsZero
  val divisionSign = lhs(31) ^ rhs(31)
  val divisionSpecialData = Mux(divisionInvalid, canonicalNaN,
    Mux(lhsNaN || rhsNaN, canonicalNaN,
      Mux(lhsInfinity || divisionByZero,
        Cat(divisionSign, "hff".U(8.W), 0.U(23.W)),
        Mux(rhsInfinity || lhsZero, Cat(divisionSign, 0.U(31.W)),
          Cat(divisionSign, 0.U(31.W))))))
  val divisionSpecialFlags = Mux(divisionInvalid, "b10000".U,
    Mux(divisionByZero, "b01000".U, 0.U(5.W)))

  // The divider generates floor((normalized lhs / normalized rhs) * 2^27)
  // over 51 restoring steps. The final remainder is folded into sticky.
  val divisionQuotientHigh = divQuotient(27)
  val divisionQuotientJammed = Mux(divisionQuotientHigh,
    rightJam(divQuotient, 1.U, 28), divQuotient)
  val divisionQuotientWithRemainder = Mux(divRemainder.orR,
    divisionQuotientJammed | 1.U, divisionQuotientJammed)
  val divisionExtended = divisionQuotientWithRemainder(26, 0)
  val divisionEffectiveExponentBiased = divExponentBiased -
    Mux(divisionQuotientHigh, 0.U(10.W), 1.U(10.W))
  val divisionSubnormalShift = Mux(divisionEffectiveExponentBiased < 257.U,
    257.U(10.W) - divisionEffectiveExponentBiased, 0.U(10.W))
  val divisionRoundedBaseExponent = Mux(divisionEffectiveExponentBiased < 257.U,
    1.U(10.W), divisionEffectiveExponentBiased - 256.U)
  val divisionRoundedInput = rightJam(divisionExtended, divisionSubnormalShift, 27)
  val divisionInexact = divisionRoundedInput(2, 0).orR
  val divisionRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (divisionRoundedInput(2) &&
      (divisionRoundedInput(1, 0).orR || divisionRoundedInput(3))),
    1.U -> false.B,
    2.U -> (divisionSign && divisionInexact),
    3.U -> (!divisionSign && divisionInexact),
    4.U -> divisionRoundedInput(2)
  ))
  val divisionRoundedSignificand = Cat(0.U(1.W), divisionRoundedInput(26, 3)) +
    divisionRoundUp.asUInt
  val divisionRoundedCarry = divisionRoundedSignificand(24)
  val divisionTiny = divisionEffectiveExponentBiased <= 257.U &&
    !divisionRoundedInput(26)
  val divisionOverflow = divisionEffectiveExponentBiased >= 511.U ||
    (divisionEffectiveExponentBiased === 510.U && divisionRoundedCarry)
  val divisionOverflowToInfinity = request.roundingMode === 0.U ||
    request.roundingMode === 4.U ||
    (request.roundingMode === 3.U && !divisionSign) ||
    (request.roundingMode === 2.U && divisionSign)
  val divisionOutputExponent = Mux(divisionOverflow,
    Mux(divisionOverflowToInfinity, 255.U(10.W), 254.U(10.W)),
    Mux(divisionRoundedCarry, divisionRoundedBaseExponent + 1.U,
      Mux(divisionTiny, 0.U(10.W), divisionRoundedBaseExponent)))
  val divisionOutputFraction = Mux(divisionOverflow && divisionOverflowToInfinity,
    0.U(23.W), Mux(divisionOverflow, "h7fffff".U(23.W),
      Mux(divisionRoundedCarry, 0.U(23.W), divisionRoundedSignificand(22, 0))))
  val divisionFiniteData = Cat(divisionSign, divisionOutputExponent(7, 0),
    divisionOutputFraction)
  val divisionFlags = Mux(divisionOverflow, "b00101".U,
    Cat(0.U(3.W), Mux(divisionTiny && divisionInexact, 1.U(1.W), 0.U(1.W)),
      divisionInexact))

  val squareRoot = request.operation === FloatingOperation.FsqrtS
  val sqrtInvalid = lhsSignalingNaN || (lhs(31) && !lhsZero)
  val sqrtSpecialInput = sqrtInvalid || lhsNaN || lhsInfinity || lhsZero
  val sqrtSignificandLeading = PriorityEncoder(Reverse(lhsArithmeticSignificand))
  val sqrtSignificandShift = Mux(lhsZero, 0.U(5.W), sqrtSignificandLeading)
  val sqrtNormalizedSignificand = (lhsArithmeticSignificand <<
    sqrtSignificandShift)(23, 0)
  val sqrtInputExponentBiased =
    ((Cat(0.U(1.W), lhsArithmeticExponent) +& 256.U)(9, 0) -
      sqrtSignificandShift)
  val sqrtExponentOdd = !sqrtInputExponentBiased(0)
  val sqrtAdjustedSignificand = Wire(UInt(25.W))
  sqrtAdjustedSignificand := Mux(sqrtExponentOdd,
    (Cat(0.U(1.W), sqrtNormalizedSignificand) << 1)(24, 0),
    Cat(0.U(1.W), sqrtNormalizedSignificand))
  val sqrtExponentBase =
    ((Cat(0.U(1.W), sqrtInputExponentBiased) + 383.U)(10, 0) -
      Mux(sqrtExponentOdd, 1.U, 0.U)) >> 1
  val sqrtPair = sqrtRadicand(53, 52)
  val sqrtShiftedRemainder = Cat(sqrtRemainder(53, 0), sqrtPair)
  val sqrtTrial = (Cat(0.U(27.W), sqrtRoot, 0.U(2.W)) + 1.U)(55, 0)
  val sqrtRootBit = sqrtShiftedRemainder >= sqrtTrial
  val sqrtNextRemainder = Mux(sqrtRootBit,
    sqrtShiftedRemainder - sqrtTrial, sqrtShiftedRemainder)
  val sqrtNextRoot = Cat(sqrtRoot(25, 0), sqrtRootBit)
  val sqrtRootJammed = Mux(sqrtRemainder.orR, sqrtRoot | 1.U, sqrtRoot)
  val sqrtInexact = sqrtRootJammed(2, 0).orR
  val sqrtRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (sqrtRootJammed(2) &&
      (sqrtRootJammed(1, 0).orR || sqrtRootJammed(3))),
    1.U -> false.B,
    2.U -> (sqrtSign && sqrtInexact),
    3.U -> (!sqrtSign && sqrtInexact),
    4.U -> sqrtRootJammed(2)
  ))
  val sqrtRoundedSignificand = Cat(0.U(1.W), sqrtRootJammed(26, 3)) +
    sqrtRoundUp.asUInt
  val sqrtRoundedCarry = sqrtRoundedSignificand(24)
  val sqrtTiny = sqrtExponentBiased <= 257.U && !sqrtRootJammed(26)
  val sqrtOverflow = sqrtExponentBiased >= 511.U ||
    (sqrtExponentBiased === 510.U && sqrtRoundedCarry)
  val sqrtOutputExponent = Mux(sqrtOverflow, 255.U(10.W),
    Mux(sqrtRoundedCarry, sqrtExponentBiased + 1.U,
      Mux(sqrtTiny, 0.U(10.W), sqrtExponentBiased)))
  val sqrtOutputFraction = Mux(sqrtOverflow, "h7fffff".U(23.W),
    Mux(sqrtRoundedCarry, 0.U(23.W), sqrtRoundedSignificand(22, 0)))
  val sqrtFiniteData = Cat(0.U(1.W), sqrtOutputExponent(7, 0), sqrtOutputFraction)
  val sqrtSpecialData = Mux(sqrtInvalid, canonicalNaN,
    Mux(lhsNaN, canonicalNaN,
      Mux(lhsInfinity, Cat(0.U(1.W), "hff".U(8.W), 0.U(23.W)),
        Cat(lhs(31), 0.U(31.W)))))
  val sqrtResultData = Mux(sqrtSpecial, sqrtSpecialResult,
    Mux(sqrtRoot.orR, sqrtFiniteData, Cat(0.U(1.W), 0.U(31.W))))
  val sqrtFlags = Mux(sqrtOverflow, "b00101".U,
    Cat(0.U(3.W), Mux(sqrtTiny && sqrtInexact, 1.U(1.W), 0.U(1.W)),
      sqrtInexact))
  val divisionExponentBase =
    ((Cat(0.U(1.W), multiplicationLhsExponent) + 383.U)(10, 0) -
      Cat(0.U(1.W), multiplicationRhsExponent))(9, 0)
  val divisionInputBit = Mux(divIteration < 24.U, divDividend(23), false.B)
  val divisionShiftedRemainder = Cat(divRemainder(23, 0), divisionInputBit)
  val divisionQuotientBit = divisionShiftedRemainder >= divDivisor
  val divisionNextRemainder = Mux(divisionQuotientBit,
    divisionShiftedRemainder - divDivisor, divisionShiftedRemainder)
  val divisionNextQuotient = Cat(divQuotient(26, 0), divisionQuotientBit)
  val numericEqual = lhs === rhs || (lhsZero && rhsZero)
  val lhsOrderKey = Mux(lhs(31), ~lhs, lhs ^ "h80000000".U(32.W))
  val rhsOrderKey = Mux(rhs(31), ~rhs, rhs ^ "h80000000".U(32.W))
  val lhsLessThanRhs = lhsOrderKey < rhsOrderKey
  val minOperation = request.operation === FloatingOperation.FminS
  val maxOperation = request.operation === FloatingOperation.FmaxS
  val minMaxSelectLhs = Mux(numericEqual,
    Mux(minOperation, lhs(31), !lhs(31)),
    Mux(minOperation, lhsLessThanRhs, !lhsLessThanRhs))
  val minMaxData = Mux(lhsNaN && rhsNaN, canonicalNaN,
    Mux(lhsNaN, rhs, Mux(rhsNaN, lhs,
      Mux(minMaxSelectLhs, lhs, rhs))))
  val comparison = request.operation === FloatingOperation.FleS ||
    request.operation === FloatingOperation.FltS ||
    request.operation === FloatingOperation.FeqS
  val comparisonData = Mux(lhsNaN || rhsNaN, false.B,
    Mux(request.operation === FloatingOperation.FeqS, numericEqual,
      Mux(request.operation === FloatingOperation.FleS,
        numericEqual || lhsLessThanRhs, lhsLessThanRhs)))
  val comparisonInvalid = (request.operation === FloatingOperation.FeqS &&
    (lhsSignalingNaN || rhsSignalingNaN)) ||
    ((request.operation === FloatingOperation.FleS ||
      request.operation === FloatingOperation.FltS) && (lhsNaN || rhsNaN))
  val fclassData = MuxCase(0.U(32.W), Seq(
    (lhsNaN && !lhsFraction(22)) -> "h00000100".U(32.W),
    (lhsNaN && lhsFraction(22)) -> "h00000200".U(32.W),
    (lhsExponent === "hff".U && !lhsFraction.orR && lhs(31)) -> "h00000001".U(32.W),
    (lhsExponent === "hff".U && !lhsFraction.orR) -> "h00000080".U(32.W),
    (lhsExponent === 0.U && !lhsFraction.orR && lhs(31)) -> "h00000008".U(32.W),
    (lhsExponent === 0.U && !lhsFraction.orR) -> "h00000010".U(32.W),
    (lhsExponent === 0.U && lhsFraction.orR && lhs(31)) -> "h00000004".U(32.W),
    (lhsExponent === 0.U && lhsFraction.orR) -> "h00000020".U(32.W),
    lhs(31) -> "h00000002".U(32.W),
    true.B -> "h00000040".U(32.W)
  ))

  val integerToFloatConversion = request.operation === FloatingOperation.FcvtSW ||
    request.operation === FloatingOperation.FcvtSWu
  val conversionSigned = request.operation === FloatingOperation.FcvtSW
  val conversionSign = conversionSigned && request.integerSource(31)
  val conversionMagnitude = Mux(conversionSign,
    (~request.integerSource).asUInt + 1.U, request.integerSource)
  val conversionMsb = WireDefault(0.U(5.W))
  for (bit <- 0 until 32) {
    when(conversionMagnitude(bit)) { conversionMsb := bit.U }
  }
  val conversionRightShift = Mux(conversionMsb > 23.U,
    conversionMsb - 23.U, 0.U(5.W))
  val conversionLeftShift = Mux(conversionMsb <= 23.U,
    23.U - conversionMsb, 0.U(5.W))
  val conversionSignificand = Mux(conversionMsb <= 23.U,
    conversionMagnitude << conversionLeftShift,
    conversionMagnitude >> conversionRightShift)(23, 0)
  val conversionDiscarded = WireDefault(false.B)
  val conversionGuard = WireDefault(false.B)
  val conversionSticky = WireDefault(false.B)
  for (shift <- 1 to 8) {
    when(conversionRightShift === shift.U) {
      conversionDiscarded := conversionMagnitude(shift - 1, 0).orR
      conversionGuard := conversionMagnitude(shift - 1)
      if (shift > 1) {
        conversionSticky := conversionMagnitude(shift - 2, 0).orR
      }
    }
  }
  val conversionRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (conversionGuard && (conversionSticky || conversionSignificand(0))),
    1.U -> false.B,
    2.U -> (conversionSign && conversionDiscarded),
    3.U -> (!conversionSign && conversionDiscarded),
    4.U -> conversionGuard
  ))
  val roundedConversionSignificand = Cat(0.U(1.W), conversionSignificand) +
    conversionRoundUp.asUInt
  val conversionCarry = roundedConversionSignificand(24)
  val conversionExponent = Cat(0.U(3.W), conversionMsb) + 127.U(8.W)
  val roundedConversionExponent = Mux(conversionCarry,
    conversionExponent + 1.U, conversionExponent)
  val conversionData = Mux(conversionMagnitude === 0.U, 0.U(32.W),
    Cat(conversionSign, roundedConversionExponent(7, 0),
      Mux(conversionCarry, 0.U(23.W), roundedConversionSignificand(22, 0))))

  val floatToIntegerConversion = request.operation === FloatingOperation.FcvtWS ||
    request.operation === FloatingOperation.FcvtWuS
  val floatToUnsigned = request.operation === FloatingOperation.FcvtWuS
  val floatToIntegerSign = lhs(31)
  val floatToIntegerFinite = lhsExponent =/= "hff".U
  val floatToIntegerNonzero = lhs(30, 0).orR
  val floatToIntegerSignificand = Mux(lhsExponent === 0.U,
    Cat(0.U(1.W), lhsFraction), Cat(1.U(1.W), lhsFraction))
  val floatToIntegerRightTruncated = WireDefault(0.U(24.W))
  val floatToIntegerDiscarded = WireDefault(false.B)
  val floatToIntegerGuard = WireDefault(false.B)
  val floatToIntegerSticky = WireDefault(false.B)
  for (shift <- 1 to 23) {
    when(lhsExponent === (150 - shift).U) {
      floatToIntegerRightTruncated := floatToIntegerSignificand >> shift
      floatToIntegerDiscarded := floatToIntegerSignificand(shift - 1, 0).orR
      floatToIntegerGuard := floatToIntegerSignificand(shift - 1)
      if (shift > 1) {
        floatToIntegerSticky := floatToIntegerSignificand(shift - 2, 0).orR
      }
    }
  }
  val lessThanOneGreaterHalf = lhsExponent > 126.U ||
    (lhsExponent === 126.U && lhsFraction.orR)
  val lessThanOneAtLeastHalf = lhsExponent >= 126.U
  val lessThanOneRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> lessThanOneGreaterHalf,
    1.U -> false.B,
    2.U -> (floatToIntegerSign && floatToIntegerNonzero),
    3.U -> (!floatToIntegerSign && floatToIntegerNonzero),
    4.U -> lessThanOneAtLeastHalf
  ))
  val floatToIntegerRightRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (floatToIntegerGuard &&
      (floatToIntegerSticky || floatToIntegerRightTruncated(0))),
    1.U -> false.B,
    2.U -> (floatToIntegerSign && floatToIntegerDiscarded),
    3.U -> (!floatToIntegerSign && floatToIntegerDiscarded),
    4.U -> floatToIntegerGuard
  ))
  val floatToIntegerLeftMagnitude = WireDefault(0.U(33.W))
  for (shift <- 0 to 8) {
    when(lhsExponent === (150 + shift).U) {
      // Exponents 150--158 produce at most 32 magnitude bits. Slice the
      // statically widened Chisel shift result so this range remains explicit.
      floatToIntegerLeftMagnitude := (Cat(0.U(9.W), floatToIntegerSignificand) << shift)(32, 0)
    }
  }
  val floatToIntegerMagnitude = WireDefault(0.U(33.W))
  val floatToIntegerInexact = WireDefault(false.B)
  when(lhsExponent < 127.U) {
    floatToIntegerMagnitude := lessThanOneRoundUp.asUInt
    floatToIntegerInexact := floatToIntegerNonzero
  }.elsewhen(lhsExponent < 150.U) {
    floatToIntegerMagnitude := Cat(0.U(9.W), floatToIntegerRightTruncated) +
      floatToIntegerRightRoundUp.asUInt
    floatToIntegerInexact := floatToIntegerDiscarded
  }.elsewhen(lhsExponent <= 158.U) {
    floatToIntegerMagnitude := floatToIntegerLeftMagnitude
  }
  val floatToIntegerSignedInvalid = !floatToIntegerFinite || lhsExponent > 158.U ||
    (!floatToIntegerSign && floatToIntegerMagnitude > "h07fffffff".U) ||
    (floatToIntegerSign && floatToIntegerMagnitude > "h080000000".U)
  val floatToIntegerUnsignedInvalid = !floatToIntegerFinite || lhsExponent > 158.U ||
    (floatToIntegerSign && floatToIntegerMagnitude.orR)
  val floatToIntegerInvalid = Mux(floatToUnsigned, floatToIntegerUnsignedInvalid,
    floatToIntegerSignedInvalid)
  val floatToIntegerData = Mux(floatToIntegerInvalid,
    Mux(floatToUnsigned, "hffffffff".U(32.W), "h80000000".U(32.W)),
    Mux(floatToIntegerSign, (~floatToIntegerMagnitude(31, 0)).asUInt + 1.U,
      floatToIntegerMagnitude(31, 0)))

  val result = WireDefault(0.U.asTypeOf(new FloatingMoveResult(config)))
  result.robTag := request.robTag
  result.integerDestinationPhysical := request.integerDestinationPhysical
  result.floatDestination := request.floatDestination
  result.flags := 0.U
  when(fma) {
    result.writesFloat := true.B
    result.floatData := Mux(fmaResultCaptured, fmaResultDataReg, fmaResultData)
    result.flags := Mux(fmaResultCaptured, fmaResultFlagsReg, fmaFlags)
  }.elsewhen(multiplication) {
    result.writesFloat := true.B
    result.floatData := multiplicationResultData
    result.flags := multiplicationFlags
  }.elsewhen(squareRoot) {
    result.writesFloat := true.B
    result.floatData := Mux(sqrtSpecial, sqrtSpecialResult, sqrtFiniteData)
    result.flags := Mux(sqrtSpecial, sqrtSpecialFlags, sqrtFlags)
  }.elsewhen(division) {
    result.writesFloat := true.B
    result.floatData := Mux(divSpecial, divSpecialResult, divisionFiniteData)
    result.flags := Mux(divSpecial, divSpecialFlags, divisionFlags)
  }.elsewhen(arithmetic) {
    result.writesFloat := true.B
    result.floatData := arithmeticResultData
    result.flags := arithmeticFlags
  }.elsewhen(request.operation === FloatingOperation.FmvWX) {
    result.writesFloat := true.B
    result.floatData := request.integerSource
  }.elsewhen(request.operation === FloatingOperation.FmvXW) {
    result.writesInteger := true.B
    result.integerData := request.floatSource(0)
  }.elsewhen(request.operation === FloatingOperation.FclassS) {
    result.writesInteger := true.B
    result.integerData := fclassData
  }.elsewhen(integerToFloatConversion) {
    result.writesFloat := true.B
    result.floatData := conversionData
    when(conversionDiscarded) { result.flags := "b00001".U }
  }.elsewhen(floatToIntegerConversion) {
    result.writesInteger := true.B
    result.integerData := floatToIntegerData
    when(floatToIntegerInvalid) {
      result.flags := "b10000".U
    }.elsewhen(floatToIntegerInexact) {
      result.flags := "b00001".U
    }
  }.elsewhen(comparison) {
    result.writesInteger := true.B
    result.integerData := comparisonData
    when(comparisonInvalid) { result.flags := "b10000".U }
  }.elsewhen(minOperation || maxOperation) {
    result.writesFloat := true.B
    result.floatData := minMaxData
    when(lhsSignalingNaN || rhsSignalingNaN) { result.flags := "b10000".U }
  }.otherwise {
    result.writesFloat := true.B
    result.floatData := signInjected
  }

  io.input.ready := !active && !recoveryBlocked
  val divisionDone = divInitialized && (divSpecial || divIteration === 51.U)
  val sqrtDone = sqrtInitialized && (sqrtSpecial || sqrtIteration === 27.U)
  io.output.valid := active && !recoveryBlocked &&
    (!fma || fmaResultCaptured) && (!division || divisionDone) &&
    (!squareRoot || sqrtDone)
  io.output.bits := result

  val activeYounger = active && ROBTagOrder.isYounger(
    request.robTag, io.squash.bits, io.robHeadTag, config)
  when(io.flush) {
    active := false.B
    divInitialized := false.B
    fmaInitialized := false.B
    fmaResultCaptured := false.B
    sqrtInitialized := false.B
  }.elsewhen(io.squash.valid) {
    when(activeYounger) {
      active := false.B
      divInitialized := false.B
      fmaInitialized := false.B
      sqrtInitialized := false.B
    }
  }.otherwise {
    when(io.output.fire) { active := false.B }
    when(io.input.fire) {
      request := io.input.bits
      active := true.B
      when(io.input.bits.operation === FloatingOperation.FdivS) {
        divInitialized := false.B
        divIteration := 0.U
      }.elsewhen(io.input.bits.operation === FloatingOperation.FsqrtS) {
        sqrtInitialized := false.B
        sqrtIteration := 0.U
      }.otherwise {
        divInitialized := true.B
        sqrtInitialized := true.B
      }
      fmaInitialized := !(io.input.bits.operation === FloatingOperation.FmaddS ||
        io.input.bits.operation === FloatingOperation.FmsubS ||
        io.input.bits.operation === FloatingOperation.FnmsubS ||
        io.input.bits.operation === FloatingOperation.FnmaddS)
      fmaResultCaptured := false.B
    }
    when(active && division && !divInitialized) {
      divInitialized := true.B
      divSign := divisionSign
      divSpecial := divisionSpecial
      divSpecialResult := divisionSpecialData
      divSpecialFlags := divisionSpecialFlags
      divIteration := 0.U
      divDividend := multiplicationLhsSignificand
      divDivisor := Cat(0.U(1.W), multiplicationRhsSignificand)
      divQuotient := 0.U
      divRemainder := 0.U
      divExponentBiased := divisionExponentBase
    }.elsewhen(active && division && divInitialized && !divSpecial &&
        divIteration =/= 51.U) {
      divDividend := Cat(divDividend(22, 0), 0.U(1.W))
      divRemainder := divisionNextRemainder
      divQuotient := divisionNextQuotient
      divIteration := divIteration + 1.U
    }
    when(active && fma && !fmaInitialized) {
      fmaInitialized := true.B
      fmaProduct := fmaProductRaw
      fmaProductSign := fmaProductRawSign
      fmaProductTopCoord := fmaProductRawTopCoord
      fmaProductZero := fmaProductRawZero
      fmaProductInfinity := lhsInfinity || rhsInfinity
    }
    when(active && fma && fmaInitialized && !fmaResultCaptured) {
      fmaResultDataReg := fmaResultData
      fmaResultFlagsReg := fmaFlags
      fmaResultCaptured := true.B
    }
    when(active && squareRoot && !sqrtInitialized) {
      sqrtInitialized := true.B
      sqrtSign := lhs(31)
      sqrtSpecial := sqrtSpecialInput
      sqrtSpecialResult := sqrtSpecialData
      sqrtSpecialFlags := Mux(sqrtInvalid, "b10000".U, 0.U(5.W))
      sqrtIteration := 0.U
      sqrtRadicand := Cat(sqrtAdjustedSignificand, 0.U(29.W))
      sqrtRemainder := 0.U
      sqrtRoot := 0.U
      sqrtExponentBiased := sqrtExponentBase(9, 0)
    }.elsewhen(active && squareRoot && sqrtInitialized && !sqrtSpecial &&
        sqrtIteration =/= 27.U) {
      sqrtRadicand := Cat(sqrtRadicand(51, 0), 0.U(2.W))
      sqrtRemainder := sqrtNextRemainder
      sqrtRoot := sqrtNextRoot
      sqrtIteration := sqrtIteration + 1.U
    }
  }

  when(io.input.fire) {
    assert(supported(io.input.bits.operation),
      "FloatingMovePipe accepted an operation outside the executable E2 slice")
    when(io.input.bits.operation === FloatingOperation.FmvXW) {
      assert(io.input.bits.integerDestinationPhysical =/= 0.U,
        "FMV.X.W cannot target integer p0")
    }
    when(io.input.bits.operation === FloatingOperation.FcvtSW ||
        io.input.bits.operation === FloatingOperation.FcvtSWu ||
        io.input.bits.operation === FloatingOperation.FcvtWS ||
        io.input.bits.operation === FloatingOperation.FcvtWuS) {
      assert(io.input.bits.roundingMode <= 4.U,
        "FCVT received a reserved effective rounding mode")
    }
    when(io.input.bits.operation === FloatingOperation.FaddS ||
        io.input.bits.operation === FloatingOperation.FsubS) {
      assert(io.input.bits.roundingMode <= 4.U,
        "FADD/FSUB received a reserved effective rounding mode")
    }
    when(io.input.bits.operation === FloatingOperation.FmulS) {
      assert(io.input.bits.roundingMode <= 4.U,
        "FMUL received a reserved effective rounding mode")
    }
    when(io.input.bits.operation === FloatingOperation.FdivS) {
      assert(io.input.bits.roundingMode <= 4.U,
        "FDIV received a reserved effective rounding mode")
    }
    when(io.input.bits.operation === FloatingOperation.FsqrtS) {
      assert(io.input.bits.roundingMode <= 4.U,
        "FSQRT received a reserved effective rounding mode")
    }
    when(io.input.bits.operation === FloatingOperation.FmaddS ||
        io.input.bits.operation === FloatingOperation.FmsubS ||
        io.input.bits.operation === FloatingOperation.FnmsubS ||
        io.input.bits.operation === FloatingOperation.FnmaddS) {
      assert(io.input.bits.roundingMode <= 4.U,
        "FMA received a reserved effective rounding mode")
    }
  }
  when(io.output.valid) {
    assert(supported(request.operation),
      "FloatingMovePipe retained an unsupported operation")
    assert(!(result.writesInteger && result.writesFloat),
      "floating E2 operation cannot write both register namespaces")
  }
  when(io.squash.valid) {
    assert(!io.input.fire && !io.output.fire,
      "FloatingMovePipe transferred work during selective squash")
  }
}
