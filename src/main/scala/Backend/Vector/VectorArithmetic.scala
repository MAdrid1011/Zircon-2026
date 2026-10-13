import chisel3._
import chisel3.util._

class VectorArithmeticRequest(tagWidth: Int) extends Bundle {
    val source = Vec(3, Vec(8, UInt(32.W)))
    val multiply = Bool()
    val integerAccumulate = Bool()
    val negateProduct = Bool()
    val fpLogic = Bool()
    val aluOp = UInt(5.W)
    val multiplyOp = UInt(4.W)
    val fp = Bool()
    val minmax = Bool()
    val maximum = Bool()
    val roundingMode = UInt(3.W)
    val active = UInt(8.W)
    val tag = UInt(tagWidth.W)
}

class VectorArithmeticResponse(tagWidth: Int) extends Bundle {
    val data = Vec(8, UInt(32.W))
    val fflags = UInt(5.W)
    val active = UInt(8.W)
    val tag = UInt(tagWidth.W)
}

/** Eight shared scalar ALU and integer/FP/FMA lanes. Both paths use the same
  * register boundaries and advance together under output backpressure.
  * Operand collection and destination merging belong to the vector backend.
  */
class VectorArithmetic(val tagWidth: Int = 16) extends Module {
    private val productLatency = 5
    val latency = productLatency + 1
    require(tagWidth > 0)
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorArithmeticRequest(tagWidth)))
        val out = Decoupled(new VectorArithmeticResponse(tagWidth))
        val flush = Input(Bool())
        val outputAdvance = Output(Bool())
    })
    // Pack only control into the existing multiplier tag pipeline. Sending all
    // accepted tasks through its valid pipeline keeps ALU and MUL stalls equal.
    val packedTagWidth = tagWidth + 12
    val products = Seq.fill(8)(Module(new MulBooth2Wallce(packedTagWidth,
        splitFpFinish = true, blockWidth = 4)))
    io.in.ready := products.head.io.in.ready
    val outputValid = RegInit(false.B)
    val output = Reg(new VectorArithmeticResponse(tagWidth))
    val outputReady = !outputValid || io.out.ready
    io.outputAdvance := outputReady && !io.flush
    io.out.valid := outputValid && !io.flush
    io.out.bits := output
    when(io.flush) { outputValid := false.B }
    .elsewhen(outputReady) { outputValid := products.head.io.out.valid }
    val advance = products.head.io.in.ready
    val packedTag = Cat(io.in.bits.integerAccumulate, io.in.bits.negateProduct,
        io.in.bits.fpLogic, io.in.bits.multiply, io.in.bits.active, io.in.bits.tag)
    val outputTag = products.head.io.out.bits.tag
    val completed = Wire(new VectorArithmeticResponse(tagWidth))
    completed.tag := outputTag(tagWidth - 1, 0)
    completed.active := outputTag(tagWidth + 7, tagWidth)
    val useProduct = outputTag(tagWidth + 8)
    val useLogic = outputTag(tagWidth + 9)
    val negateProduct = outputTag(tagWidth + 10)
    val accumulate = outputTag(tagWidth + 11)
    val logicFlags = Wire(Vec(8, UInt(5.W)))

    for ((product, lane) <- products.zipWithIndex) {
        val alu = Module(new ALU)
        alu.io.src1 := io.in.bits.source(0)(lane)
        alu.io.src2 := io.in.bits.source(1)(lane)
        alu.io.op := io.in.bits.aluOp
        val aluStages = Seq.fill(productLatency)(Reg(UInt(32.W)))
        val logicRequest = Wire(new FpLogicRequest(1))
        logicRequest.src1 := io.in.bits.source(0)(lane)
        logicRequest.src2 := io.in.bits.source(1)(lane)
        logicRequest.op := io.in.bits.aluOp(3, 0)
        logicRequest.tag := 0.U
        val logic = FpLogic.execute(logicRequest)
        val auxiliary = Seq.fill(productLatency)(Reg(UInt(32.W)))
        val auxiliaryFlags = Seq.fill(productLatency)(Reg(UInt(5.W)))
        val addends = Seq.fill(productLatency)(Reg(UInt(32.W)))
        val lesser = alu.io.res(0)
        val integerResult = Mux(io.in.bits.minmax,
            Mux(lesser ^ io.in.bits.maximum, io.in.bits.source(0)(lane), io.in.bits.source(1)(lane)), alu.io.res)
        when(advance) {
            aluStages.head := integerResult
            auxiliary.head := logic.res
            auxiliaryFlags.head := logic.fflags
            addends.head := io.in.bits.source(2)(lane)
            for (stage <- 1 until productLatency) aluStages(stage) := aluStages(stage - 1)
            for (stage <- 1 until productLatency) {
                auxiliary(stage) := auxiliary(stage - 1)
                auxiliaryFlags(stage) := auxiliaryFlags(stage - 1)
                addends(stage) := addends(stage - 1)
            }
        }
        val request = product.io.in.bits
        product.io.flush := io.flush
        product.io.in.valid := io.in.valid
        product.io.out.ready := outputReady
        request.op := Mux(io.in.bits.multiply, io.in.bits.multiplyOp, 0.U)
        request.fp := io.in.bits.multiply && io.in.bits.fp
        request.aSigned := !request.fp && request.op =/= ZirconConfig.MultiplyOp.MULHU
        request.bSigned := !request.fp && request.op =/= ZirconConfig.MultiplyOp.MULHU &&
            request.op =/= ZirconConfig.MultiplyOp.MULHSU
        request.roundingMode := io.in.bits.roundingMode
        request.tag := packedTag
        // The output active mask owns both destination writes and FP flags.
        // Unused products need no operand-zero mux before exponent alignment.
        val values = (0 until 3).map(source => io.in.bits.source(source)(lane))
        request.src1 := values(0); request.src2 := values(1); request.src3 := values(2)
        request.fpSrc1 := values(0); request.fpSrc2 := values(1); request.fpSrc3 := values(2)
        val exponents = values.map(_(30, 23))
        request.fpEffectiveExp1 := Mux(exponents(0).orR, exponents(0), 1.U)
        request.fpEffectiveExp2 := Mux(exponents(1).orR, exponents(1), 1.U)
        request.fpEffectiveExp3 := Mux(exponents(2).orR, exponents(2), 1.U)
        request.fpZero1 := !values(0)(30, 0).orR
        request.fpZero2 := !values(1)(30, 0).orR
        request.fpZero3 := !values(2)(30, 0).orR
        val accumulateAdder = BLevelPAdder32(addends.last,
            product.io.out.bits.res ^ Fill(32, negateProduct), negateProduct.asUInt)
        val integerResultWithAddend = accumulateAdder.io.res
        completed.data(lane) := Mux(useLogic, auxiliary.last,
            Mux(useProduct, Mux(accumulate, integerResultWithAddend, product.io.out.bits.res), aluStages.last))
        logicFlags(lane) := auxiliaryFlags.last
        assert(product.io.in.ready === io.in.ready)
        assert(product.io.out.valid === products.head.io.out.valid)
        when(product.io.out.valid) { assert(product.io.out.bits.tag === outputTag) }
    }
    completed.fflags := products.zipWithIndex.map { case (product, lane) =>
        Mux(completed.active(lane), Mux(useLogic, logicFlags(lane),
            Mux(useProduct, product.io.out.bits.fflags, 0.U(5.W))), 0.U(5.W))
    }.reduce(_ | _)
    when(outputReady) { output := completed }
}
