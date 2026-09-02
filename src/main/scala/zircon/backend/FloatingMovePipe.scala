package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.FloatingOperation

/** Captured operands for the first non-rounding RV32F E2 slice. */
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

/** E2 execution for non-rounding RV32F moves, sign injection, min/max,
  * comparisons, and classification.
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
      operation === FloatingOperation.FclassS

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
      "FloatingMovePipe accepted an operation outside the bit-move/sign slice")
    when(io.input.bits.operation === FloatingOperation.FmvXW) {
      assert(io.input.bits.integerDestinationPhysical =/= 0.U,
        "FMV.X.W cannot target integer p0")
    }
  }
  when(io.output.valid) {
    assert(supported(request.operation),
      "FloatingMovePipe retained an unsupported operation")
    assert(!(result.writesInteger && result.writesFloat),
      "bit-move/sign operation cannot write both register namespaces")
  }
  when(io.squash.valid) {
    assert(!io.input.fire && !io.output.fire,
      "FloatingMovePipe transferred work during selective squash")
  }
}
