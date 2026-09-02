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
  val floatSource = Vec(2, UInt(32.W))
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
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FloatingMoveRequest(config)))
    val output = Decoupled(new FloatingMoveResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  private def supported(operation: FloatingOperation.Type): Bool =
    operation === FloatingOperation.FmvWX || operation === FloatingOperation.FmvXW ||
    operation === FloatingOperation.FsgnjS || operation === FloatingOperation.FsgnjnS ||
      operation === FloatingOperation.FsgnjxS || operation === FloatingOperation.FminS ||
      operation === FloatingOperation.FmaxS || operation === FloatingOperation.FleS ||
      operation === FloatingOperation.FltS || operation === FloatingOperation.FeqS ||
      operation === FloatingOperation.FclassS || operation === FloatingOperation.FcvtSW ||
      operation === FloatingOperation.FcvtSWu || operation === FloatingOperation.FcvtWS ||
      operation === FloatingOperation.FcvtWuS || operation === FloatingOperation.FaddS ||
      operation === FloatingOperation.FsubS

  val active = RegInit(false.B)
  val request = Reg(new FloatingMoveRequest(config))
  val recoveryBlocked = io.flush || io.squash.valid

  val sign = MuxLookup(request.operation.asUInt, 0.U(1.W))(Seq(
    FloatingOperation.FsgnjS.asUInt -> request.floatSource(1)(31),
    FloatingOperation.FsgnjnS.asUInt -> !request.floatSource(1)(31),
    FloatingOperation.FsgnjxS.asUInt ->
      (request.floatSource(0)(31) ^ request.floatSource(1)(31))
  ))
  val signInjected = Cat(sign, request.floatSource(0)(30, 0))
  val lhs = request.floatSource(0)
  val rhs = request.floatSource(1)
  val lhsExponent = lhs(30, 23)
  val rhsExponent = rhs(30, 23)
  val lhsFraction = lhs(22, 0)
  val rhsFraction = rhs(22, 0)
  val lhsNaN = lhsExponent === "hff".U && lhsFraction.orR
  val rhsNaN = rhsExponent === "hff".U && rhsFraction.orR
  val lhsSignalingNaN = lhsNaN && !lhsFraction(22)
  val rhsSignalingNaN = rhsNaN && !rhsFraction(22)
  val lhsZero = lhs(30, 0) === 0.U
  val rhsZero = rhs(30, 0) === 0.U
  val numericEqual = lhs === rhs || (lhsZero && rhsZero)
  val lhsOrderKey = Mux(lhs(31), ~lhs, lhs ^ "h80000000".U(32.W))
  val rhsOrderKey = Mux(rhs(31), ~rhs, rhs ^ "h80000000".U(32.W))
  val lhsLessThanRhs = lhsOrderKey < rhsOrderKey
  val canonicalNaN = "h7fc00000".U(32.W)
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

  val addSubOperation = request.operation === FloatingOperation.FaddS ||
    request.operation === FloatingOperation.FsubS
  val subtractOperation = request.operation === FloatingOperation.FsubS
  val rhsArithmeticSign = rhs(31) ^ subtractOperation
  val lhsInfinity = lhsExponent === "hff".U && !lhsFraction.orR
  val rhsInfinity = rhsExponent === "hff".U && !rhsFraction.orR
  val lhsArithmeticZero = lhsExponent === 0.U && !lhsFraction.orR
  val rhsArithmeticZero = rhsExponent === 0.U && !rhsFraction.orR
  val lhsArithmeticExponent = Mux(lhsExponent === 0.U, 1.U(8.W), lhsExponent)
  val rhsArithmeticExponent = Mux(rhsExponent === 0.U, 1.U(8.W), rhsExponent)
  val lhsArithmeticSignificand = Cat((lhsExponent =/= 0.U).asUInt, lhsFraction)
  val rhsArithmeticSignificand = Cat((rhsExponent =/= 0.U).asUInt, rhsFraction)
  val lhsLarger = lhsArithmeticExponent > rhsArithmeticExponent ||
    (lhsArithmeticExponent === rhsArithmeticExponent &&
      lhsArithmeticSignificand >= rhsArithmeticSignificand)
  val largerExponent = Mux(lhsLarger, lhsArithmeticExponent, rhsArithmeticExponent)
  val smallerExponent = Mux(lhsLarger, rhsArithmeticExponent, lhsArithmeticExponent)
  val largerSign = Mux(lhsLarger, lhs(31), rhsArithmeticSign)
  val smallerSign = Mux(lhsLarger, rhsArithmeticSign, lhs(31))
  val largerSignificand = Mux(lhsLarger, lhsArithmeticSignificand,
    rhsArithmeticSignificand)
  val smallerSignificand = Mux(lhsLarger, rhsArithmeticSignificand,
    lhsArithmeticSignificand)
  val largerExtended = Cat(largerSignificand, 0.U(3.W))
  val smallerExtended = Cat(smallerSignificand, 0.U(3.W))
  val exponentDifference = largerExponent - smallerExponent
  val shiftedSmaller = WireDefault(0.U(27.W))
  val shiftedSmallerLost = WireDefault(false.B)
  for (shift <- 1 to 26) {
    when(exponentDifference === shift.U) {
      shiftedSmaller := smallerExtended >> shift
      shiftedSmallerLost := smallerExtended(shift - 1, 0).orR
    }
  }
  when(exponentDifference === 0.U) {
    shiftedSmaller := smallerExtended
  }.elsewhen(exponentDifference >= 27.U) {
    shiftedSmallerLost := smallerExtended.orR
  }
  val alignedSmaller = WireDefault(0.U(27.W))
  alignedSmaller := shiftedSmaller
  when(shiftedSmallerLost) {
    alignedSmaller := shiftedSmaller | 1.U(27.W)
  }
  val arithmeticSameSign = largerSign === smallerSign
  val arithmeticSum = Cat(0.U(1.W), largerExtended) + Cat(0.U(1.W), alignedSmaller)
  val arithmeticDifference = largerExtended - alignedSmaller
  val requestedNormalization = PriorityEncoder(Reverse(arithmeticDifference))
  val maximumNormalization = Mux(largerExponent > 1.U,
    largerExponent - 1.U, 0.U(8.W))
  val appliedNormalization = Mux(requestedNormalization < maximumNormalization,
    requestedNormalization, maximumNormalization)
  val normalizedDifference = (arithmeticDifference << appliedNormalization)(26, 0)
  val differenceExponent = largerExponent - appliedNormalization
  val sumSignificand = Mux(arithmeticSum(27),
    (arithmeticSum >> 1)(26, 0) | arithmeticSum(0).asUInt,
    arithmeticSum(26, 0))
  val sumExponent = Cat(0.U(1.W), largerExponent) + arithmeticSum(27).asUInt
  val arithmeticSignificand = Mux(arithmeticSameSign, sumSignificand, normalizedDifference)
  val arithmeticExponent = Mux(arithmeticSameSign, sumExponent,
    Cat(0.U(1.W), differenceExponent))
  val arithmeticInexact = arithmeticSignificand(2, 0).orR
  val arithmeticRoundUp = MuxLookup(request.roundingMode, false.B)(Seq(
    0.U -> (arithmeticSignificand(2) &&
      (arithmeticSignificand(1) || arithmeticSignificand(0) || arithmeticSignificand(3))),
    1.U -> false.B,
    2.U -> (largerSign && arithmeticInexact),
    3.U -> (!largerSign && arithmeticInexact),
    4.U -> arithmeticSignificand(2)
  ))
  val arithmeticRoundedSignificand = Cat(0.U(1.W), arithmeticSignificand(26, 3)) +
    arithmeticRoundUp.asUInt
  val arithmeticRoundedExponent = arithmeticExponent + arithmeticRoundedSignificand(24).asUInt
  val arithmeticOverflow = arithmeticRoundedExponent >= "h0ff".U
  val arithmeticSubnormal = arithmeticRoundedExponent === 1.U &&
    !arithmeticRoundedSignificand(23)
  val arithmeticFiniteData = Cat(largerSign,
    Mux(arithmeticSubnormal, 0.U(8.W), arithmeticRoundedExponent(7, 0)),
    Mux(arithmeticRoundedSignificand(24), 0.U(23.W),
      arithmeticRoundedSignificand(22, 0)))
  val overflowToInfinity = request.roundingMode === 0.U || request.roundingMode === 4.U ||
    (request.roundingMode === 3.U && !largerSign) ||
    (request.roundingMode === 2.U && largerSign)
  val arithmeticOverflowData = Mux(overflowToInfinity,
    Cat(largerSign, "hff".U(8.W), 0.U(23.W)),
    Cat(largerSign, "hfe".U(8.W), "h7fffff".U(23.W)))
  val arithmeticFiniteFlags = Mux(arithmeticOverflow, "b00101".U(5.W),
    Mux(arithmeticSubnormal && arithmeticInexact, "b00011".U(5.W),
      Mux(arithmeticInexact, "b00001".U(5.W), 0.U(5.W))))
  val arithmeticData = WireDefault(0.U(32.W))
  val arithmeticFlags = WireDefault(0.U(5.W))
  when(lhsNaN || rhsNaN) {
    arithmeticData := canonicalNaN
    when(lhsSignalingNaN || rhsSignalingNaN) { arithmeticFlags := "b10000".U }
  }.elsewhen(lhsInfinity || rhsInfinity) {
    when(lhsInfinity && rhsInfinity && lhs(31) =/= rhsArithmeticSign) {
      arithmeticData := canonicalNaN
      arithmeticFlags := "b10000".U
    }.otherwise {
      arithmeticData := Mux(lhsInfinity, Cat(lhs(31), "hff".U(8.W), 0.U(23.W)),
        Cat(rhsArithmeticSign, "hff".U(8.W), 0.U(23.W)))
    }
  }.elsewhen(lhsArithmeticZero && rhsArithmeticZero) {
    arithmeticData := Cat(Mux(lhs(31) === rhsArithmeticSign, lhs(31),
      request.roundingMode === 2.U), 0.U(31.W))
  }.elsewhen(lhsArithmeticZero) {
    arithmeticData := Cat(rhsArithmeticSign, rhs(30, 0))
  }.elsewhen(rhsArithmeticZero) {
    arithmeticData := lhs
  }.elsewhen(!arithmeticSameSign && !arithmeticDifference.orR) {
    arithmeticData := Cat(request.roundingMode === 2.U, 0.U(31.W))
  }.otherwise {
    arithmeticData := Mux(arithmeticOverflow, arithmeticOverflowData, arithmeticFiniteData)
    arithmeticFlags := arithmeticFiniteFlags
  }

  val result = WireDefault(0.U.asTypeOf(new FloatingMoveResult(config)))
  result.robTag := request.robTag
  result.integerDestinationPhysical := request.integerDestinationPhysical
  result.floatDestination := request.floatDestination
  result.flags := 0.U
  when(request.operation === FloatingOperation.FmvWX) {
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
  }.elsewhen(addSubOperation) {
    result.writesFloat := true.B
    result.floatData := arithmeticData
    result.flags := arithmeticFlags
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
  io.output.valid := active && !recoveryBlocked
  io.output.bits := result

  val activeYounger = active && ROBTagOrder.isYounger(
    request.robTag, io.squash.bits, io.robHeadTag, config)
  when(io.flush) {
    active := false.B
  }.elsewhen(io.squash.valid) {
    when(activeYounger) { active := false.B }
  }.otherwise {
    when(io.output.fire) { active := false.B }
    when(io.input.fire) {
      request := io.input.bits
      active := true.B
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
