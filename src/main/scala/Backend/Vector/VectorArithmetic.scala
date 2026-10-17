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
class VectorArithmetic(val tagWidth: Int = 16, val shareSerialMultiply: Boolean = false) extends Module {
    private val productLatency = 5
    val latency = productLatency + 1
    require(tagWidth > 0)
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorArithmeticRequest(tagWidth)))
        val out = Decoupled(new VectorArithmeticResponse(tagWidth))
        val flush = Input(Bool())
        val outputAdvance = Output(Bool())
        val serialMultiply = if (shareSerialMultiply) Some(new MultiplyIO(1)) else None
        val serialActive = if (shareSerialMultiply) Some(Input(Bool())) else None
    })
    // Pack only control into the existing multiplier tag pipeline. Sending all
    // accepted tasks through its valid pipeline keeps ALU and MUL stalls equal.
    val packedTagWidth = tagWidth + 12
    val products = Seq.fill(8)(Module(new MulBooth2Wallce(packedTagWidth,
        splitFpFinish = true, blockWidth = 4, holdInvalidData = true)))
    val serialActive = io.serialActive.getOrElse(false.B)
    // Serial ownership begins only after the compute completion drains and
    // remains asserted through the serial instruction's completion handshake.
    io.in.ready := products(1).io.in.ready && !serialActive
    val outputValid = RegInit(false.B)
    val output = Reg(new VectorArithmeticResponse(tagWidth))
    val outputReady = !outputValid || io.out.ready
    io.outputAdvance := outputReady && !io.flush
    io.out.valid := outputValid && !io.flush
    io.out.bits := output
    when(io.flush) { outputValid := false.B }
    .elsewhen(outputReady) { outputValid := products.head.io.out.valid && !serialActive }
    val advance = io.in.ready
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
    // Use the first two existing auxiliary stages for operand capture and ALU
    // evaluation. Arithmetic and product completion retain the same latency.
    val auxiliaryFpLogic = RegEnable(io.in.bits.fpLogic, advance)
    val auxiliaryMinmax = RegEnable(io.in.bits.minmax, advance)
    val auxiliaryMaximum = RegEnable(io.in.bits.maximum, advance)

    for ((product, lane) <- products.zipWithIndex) {
        val auxiliary = Seq.fill(productLatency)(Reg(UInt(32.W)))
        val auxiliarySource2 = RegEnable(io.in.bits.source(1)(lane), advance)
        val alu = Module(new ALU(pipelined = true))
        alu.io.src1 := io.in.bits.source(0)(lane)
        alu.io.src2 := io.in.bits.source(1)(lane)
        alu.io.op := io.in.bits.aluOp
        alu.io.advance.get := advance
        val logicRequest = Wire(new FpLogicRequest(1))
        logicRequest.src1 := io.in.bits.source(0)(lane)
        logicRequest.src2 := io.in.bits.source(1)(lane)
        logicRequest.op := io.in.bits.aluOp(3, 0)
        logicRequest.tag := 0.U
        val logic = FpLogic.execute(logicRequest)
        val auxiliaryFlags = Seq.fill(productLatency)(Reg(UInt(5.W)))
        val addends = Seq.fill(productLatency)(Reg(UInt(32.W)))
        val lesser = alu.io.res(0)
        val integerResult = Mux(auxiliaryMinmax,
            Mux(lesser ^ auxiliaryMaximum, auxiliary.head, auxiliarySource2), alu.io.res)
        when(advance) {
            auxiliary.head := Mux(io.in.bits.fpLogic, logic.res, io.in.bits.source(0)(lane))
            auxiliary(1) := Mux(auxiliaryFpLogic, auxiliary.head, integerResult)
            auxiliaryFlags.head := logic.fflags
            addends.head := io.in.bits.source(2)(lane)
            for (stage <- 1 until productLatency) {
                if (stage > 1) { auxiliary(stage) := auxiliary(stage - 1) }
                auxiliaryFlags(stage) := auxiliaryFlags(stage - 1)
                addends(stage) := addends(stage - 1)
            }
        }
        val request = Wire(new MultiplyRequest(packedTagWidth))
        product.io.flush := io.flush
        product.io.in.valid := io.in.valid && !serialActive
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
        product.io.in.bits := request
        if (lane == 0 && shareSerialMultiply) {
            val serial = io.serialMultiply.get
            val serialRequest = Wire(new MultiplyRequest(packedTagWidth))
            for ((name, field) <- serialRequest.elements) {
                field := (if (name == "tag") 0.U else serial.in.bits.elements(name))
            }
            product.io.in.bits := Mux(serialActive, serialRequest, request)
            product.io.in.valid := Mux(serialActive, serial.in.valid, io.in.valid)
            product.io.out.ready := Mux(serialActive, serial.out.ready, outputReady)
            serial.in.ready := serialActive && product.io.in.ready
            serial.out.valid := serialActive && product.io.out.valid
            serial.out.bits.res := product.io.out.bits.res
            serial.out.bits.fflags := product.io.out.bits.fflags
            serial.out.bits.tag := 0.U
            serial.present := product.io.present
            serial.wbInput := 0.U.asTypeOf(serial.wbInput)
            when(serialActive) { assert(serial.flush === io.flush && !io.in.valid && !outputValid) }
        }
        val accumulateAdder = BLevelPAdder32(addends.last,
            product.io.out.bits.res ^ Fill(32, negateProduct), negateProduct.asUInt)
        val integerResultWithAddend = accumulateAdder.io.res
        completed.data(lane) := Mux(useProduct && !useLogic,
            Mux(accumulate, integerResultWithAddend, product.io.out.bits.res), auxiliary.last)
        logicFlags(lane) := auxiliaryFlags.last
        when(!serialActive) {
            assert(product.io.in.ready === io.in.ready)
            assert(product.io.out.valid === products.head.io.out.valid)
            when(product.io.out.valid) { assert(product.io.out.bits.tag === outputTag) }
        }
    }
    completed.fflags := products.zipWithIndex.map { case (product, lane) =>
        Mux(completed.active(lane), Mux(useLogic, logicFlags(lane),
            Mux(useProduct, product.io.out.bits.fflags, 0.U(5.W))), 0.U(5.W))
    }.reduce(_ | _)
    when(outputReady) { output := completed }
}
