package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.frontend.IntOperation

class LongPipeRequest(config: ZirconCoreConfig) extends Bundle {
  val uop = new UopRef(config)
  val lhs = UInt(32.W)
  val rhs = UInt(32.W)
}

/** Explicitly sized partial-product primitive. Keeping the operands at 16 bits
  * prevents Verilog emission from widening the multiply into a 32x32 DSP. */
class ZirconUIntMul16 extends BlackBox with HasBlackBoxInline {
  override val desiredName: String = "ZirconUIntMul16"

  val io = IO(new Bundle {
    val a = Input(UInt(16.W))
    val b = Input(UInt(16.W))
    val y = Output(UInt(32.W))
  })

  setInline("ZirconUIntMul16.sv",
    """(* use_dsp = "yes" *)
      |module ZirconUIntMul16(
      |  input wire [15:0] a,
      |  input wire [15:0] b,
      |  output wire [31:0] y
      |);
      |  assign y = a * b;
      |endmodule
      |""".stripMargin)
}

/** One physical multiplier shared by integer MUL and floating multiply/FMA.
  * Both clients present 32-bit zero-extended operands; the implementation
  * deliberately contains only four 16x16 partial-product primitives. */
class ZirconSharedMultiplier extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val lhs = Input(UInt(32.W))
    val rhs = Input(UInt(32.W))
    val product = Output(UInt(64.W))
  })

  private def partial(a: UInt, b: UInt): UInt = {
    val unit = Module(new ZirconUIntMul16)
    unit.io.a := a
    unit.io.b := b
    unit.io.y
  }
  val p00 = partial(io.lhs(15, 0), io.rhs(15, 0))
  val p01 = partial(io.lhs(15, 0), io.rhs(31, 16))
  val p10 = partial(io.lhs(31, 16), io.rhs(15, 0))
  val p11 = partial(io.lhs(31, 16), io.rhs(31, 16))
  val productWide = p00.pad(64) +& (p01.pad(64) << 16) +&
    (p10.pad(64) << 16) +& (p11.pad(64) << 32)
  io.product := Mux(io.enable, productWide(63, 0), 0.U)
}

/** E2 RV32M engine. The integer multiplier is composed only from four 16x16
  * partial products. Division uses one restoring step per active cycle.
  */
class LongPipe(
    config: ZirconCoreConfig = ZirconCoreConfig.default,
    useExternalMultiplier: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new LongPipeRequest(config)))
    val completion = Decoupled(new CompletionResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val multiplierEnable = Output(Bool())
    val multiplierLhs = Output(UInt(32.W))
    val multiplierRhs = Output(UInt(32.W))
    val multiplierProduct = Input(UInt(64.W))
  })

  private def negate32(value: UInt): UInt = (0.U(32.W) - value)(31, 0)
  private def negate64(value: UInt): UInt = (0.U(64.W) - value)(63, 0)
  private def magnitude(value: UInt): UInt =
    Mux(value(31), negate32(value), value)

  val results = Module(new CompletionBuffer(config, depth = 2))
  results.io.robHeadTag := io.robHeadTag
  results.io.squash := io.squash
  results.io.flush := io.flush
  io.completion <> results.io.dequeue

  // Unit-level simulations instantiate LongPipe by itself. Keep that use
  // self-contained while allowing ZirconCore to provide the sole production
  // multiplier explicitly.
  val localMultiplier = if (useExternalMultiplier) None else
    Some(Module(new ZirconSharedMultiplier))
  localMultiplier.foreach { multiplier =>
    multiplier.io.enable := io.multiplierEnable
    multiplier.io.lhs := io.multiplierLhs
    multiplier.io.rhs := io.multiplierRhs
  }

  val active = RegInit(false.B)
  val activeUop = Reg(new UopRef(config))
  val activeOperation = RegInit(IntOperation.Invalid)
  val activeLhs = Reg(UInt(32.W))
  val activeRhs = Reg(UInt(32.W))
  val divDividend = Reg(UInt(32.W))
  val divDivisor = Reg(UInt(32.W))
  val divQuotient = Reg(UInt(32.W))
  val divRemainder = Reg(UInt(33.W))
  val divIteration = RegInit(0.U(5.W))
  val divSpecial = RegInit(false.B)
  val divSpecialResult = Reg(UInt(32.W))
  val divIsRemainder = RegInit(false.B)
  val divResultNegative = RegInit(false.B)

  val (inputOperation, inputOperationValid) =
    IntOperation.safe(io.input.bits.uop.operation(5, 0))
  val inputIsMultiply = inputOperation === IntOperation.Mul ||
    inputOperation === IntOperation.Mulh || inputOperation === IntOperation.Mulhsu ||
    inputOperation === IntOperation.Mulhu
  val inputIsDivide = inputOperation === IntOperation.Div ||
    inputOperation === IntOperation.Divu || inputOperation === IntOperation.Rem ||
    inputOperation === IntOperation.Remu
  val activeIsDivide = activeOperation === IntOperation.Div ||
    activeOperation === IntOperation.Divu || activeOperation === IntOperation.Rem ||
    activeOperation === IntOperation.Remu

  // Derive all RV32M signedness variants from one raw product.  For two's
  // complement operands, signed(a)*signed(b) is the raw unsigned product
  // minus the high-half correction for each negative operand; signed(a)*u(b)
  // needs only the lhs correction.  This keeps the shared partial-product
  // multiplier at four 16x16 blocks instead of triplicating it.
  io.multiplierEnable := active && !activeIsDivide
  io.multiplierLhs := activeLhs
  io.multiplierRhs := activeRhs
  val rawProduct = localMultiplier.map(_.io.product).getOrElse(io.multiplierProduct)
  val lhsCorrection = Mux(activeLhs(31), Cat(activeRhs, 0.U(32.W)), 0.U(64.W))
  val rhsCorrection = Mux(activeRhs(31), Cat(activeLhs, 0.U(32.W)), 0.U(64.W))
  val signedProduct = rawProduct - lhsCorrection - rhsCorrection
  val signedUnsignedProduct = rawProduct - lhsCorrection
  val multiplyResult = MuxLookup(activeOperation.asUInt, 0.U(32.W))(Seq(
    IntOperation.Mul.asUInt -> signedProduct(31, 0),
    IntOperation.Mulh.asUInt -> signedProduct(63, 32),
    IntOperation.Mulhsu.asUInt -> signedUnsignedProduct(63, 32),
    IntOperation.Mulhu.asUInt -> rawProduct(63, 32)
  ))

  val shiftedRemainder = Cat(divRemainder(31, 0), divDividend(31))
  val divisorExtended = Cat(0.U(1.W), divDivisor)
  val quotientBit = shiftedRemainder >= divisorExtended
  val nextRemainder = Mux(quotientBit, shiftedRemainder - divisorExtended,
    shiftedRemainder)
  val nextQuotient = Cat(divQuotient(30, 0), quotientBit)
  val unsignedDivideResult = Mux(divIsRemainder, nextRemainder(31, 0),
    nextQuotient)
  val divideResult = Mux(divSpecial, divSpecialResult,
    Mux(divResultNegative, negate32(unsignedDivideResult), unsignedDivideResult))

  val divisionDone = divSpecial || divIteration === 31.U
  val completionData = Mux(activeIsDivide, divideResult, multiplyResult)
  val completionReady = active && Mux(activeIsDivide, divisionDone, true.B)
  val recoveryBlocked = io.flush || io.squash.valid
  results.io.enqueue.valid := completionReady && !recoveryBlocked
  results.io.enqueue.bits.robTag := activeUop.robTag
  results.io.enqueue.bits.writesInteger := activeUop.writesInteger
  results.io.enqueue.bits.destinationPhysical := activeUop.destinationPhysical
  results.io.enqueue.bits.data := completionData

  io.input.ready := !active && results.io.enqueue.ready && !recoveryBlocked
  val activeYounger = active && ROBTagOrder.isYounger(
    activeUop.robTag, io.squash.bits, io.robHeadTag, config)

  when(io.flush) {
    active := false.B
  }.elsewhen(io.squash.valid) {
    when(activeYounger) { active := false.B }
  }.otherwise {
    when(active && results.io.enqueue.fire) {
      active := false.B
    }.elsewhen(active && activeIsDivide && !divSpecial && divIteration =/= 31.U) {
      divDividend := Cat(divDividend(30, 0), 0.U(1.W))
      divQuotient := nextQuotient
      divRemainder := nextRemainder
      divIteration := divIteration + 1.U
    }
    when(io.input.fire) {
      active := true.B
      activeUop := io.input.bits.uop
      activeOperation := inputOperation
      activeLhs := io.input.bits.lhs
      activeRhs := io.input.bits.rhs
      when(inputIsDivide) {
        val signedOperation = inputOperation === IntOperation.Div ||
          inputOperation === IntOperation.Rem
        val isRemainder = inputOperation === IntOperation.Rem ||
          inputOperation === IntOperation.Remu
        val divisorZero = io.input.bits.rhs === 0.U
        val signedOverflow = signedOperation &&
          io.input.bits.lhs === "h80000000".U && io.input.bits.rhs === "hffffffff".U
        divDividend := Mux(signedOperation, magnitude(io.input.bits.lhs),
          io.input.bits.lhs)
        divDivisor := Mux(signedOperation, magnitude(io.input.bits.rhs),
          io.input.bits.rhs)
        divQuotient := 0.U
        divRemainder := 0.U
        divIteration := 0.U
        divSpecial := divisorZero || signedOverflow
        divSpecialResult := Mux(divisorZero,
          Mux(isRemainder, io.input.bits.lhs, "hffffffff".U),
          Mux(isRemainder, 0.U, "h80000000".U))
        divIsRemainder := isRemainder
        divResultNegative := signedOperation && Mux(isRemainder,
          io.input.bits.lhs(31), io.input.bits.lhs(31) ^ io.input.bits.rhs(31))
      }.otherwise {
        divSpecial := false.B
        divIteration := 0.U
      }
    }
  }

  when(io.input.fire) {
    assert(inputOperationValid && (inputIsMultiply || inputIsDivide),
      "LongPipe accepted an operation outside RV32M")
    assert(io.input.bits.uop.allowedEndpoints(ExecutionEndpoint.E2LongPipe.asUInt),
      "LongPipe accepted a uop not eligible for E2")
    assert((inputIsMultiply && io.input.bits.uop.uopClass === UopClass.Multiply) ||
      (inputIsDivide && io.input.bits.uop.uopClass === UopClass.Divide),
      "LongPipe uop class disagreed with its RV32M operation")
    assert(io.input.bits.uop.writesInteger && !io.input.bits.uop.writesFloat,
      "RV32M LongPipe uop did not carry its integer destination")
  }
  when(io.squash.valid) {
    assert(!io.input.fire && !results.io.enqueue.fire,
      "LongPipe accepted or completed work during selective squash")
  }
}
