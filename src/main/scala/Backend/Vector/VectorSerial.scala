import chisel3._
import chisel3.util._
import ZirconConfig.{BackendParams, DivideOp, FpMiscOp, MultiplyOp, VectorParams}

/** Multi-cycle operations collect operands through the same three vector read
  * ports. Single-register SEW32 permutations reuse that complete register read;
  * other operations traverse elements. Divide/sqrt and conversion reuse scalar
  * arithmetic modules. PRF/ROB ownership lasts until the instruction completes.
  */
class VectorSerial(val p: BackendParams = BackendParams(), val externalMultiply: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorInstruction(p)))
        val read = Flipped(Vec(3, new VectorRegisterRead(VectorParams())))
        val scalarAddress = Output(UInt(p.tagWidth.W))
        val scalarData = Input(UInt(32.W))
        val roundingMode = Input(UInt(3.W))
        val readAvailable = Input(Bool())
        val readRequest = Output(Bool())
        val writeback = Output(Valid(new VectorTaskWriteback))
        val completion = Decoupled(new VectorCompletion(p))
        val flush = Input(Bool())
        val idle = Output(Bool())
        val multiply = if (externalMultiply) Some(Flipped(new MultiplyIO(1))) else None
    })
    val idle :: prepareRead :: operands :: initialize :: readElement :: gatherElement :: evaluate :: finishArithmetic :: issue :: awaitResult :: update :: writeback :: complete :: Nil = Enum(13)
    val phase = RegInit(idle)
    val nextPhase = WireDefault(phase)
    phase := nextPhase
    val nextRead = Reg(UInt(4.W))
    val readAddresses = Reg(Vec(3, UInt(6.W)))
    val readSelection = RegInit(VecInit.fill(3)(1.U(64.W)))
    val writeAddress = Reg(UInt(6.W))
    def enterRead(target: UInt): Unit = { nextRead := target; nextPhase := prepareRead }
    val instruction = Reg(new VectorInstruction(p))
    val scalar = Reg(UInt(32.W))
    val mask = Reg(UInt(256.W))
    val result = Reg(UInt(32.W))
    val output = Reg(UInt(256.W))
    val a = Reg(UInt(32.W))
    val b = Reg(UInt(32.W))
    val arithmeticB = Reg(UInt(33.W))
    val arithmeticResult = Reg(UInt(33.W))
    val c = Reg(UInt(32.W))
    val accumulator = Reg(UInt(32.W))
    val gatherIndex = Reg(UInt(32.W))
    val gatherValid = Reg(Bool())
    val productLow = Reg(UInt(32.W))
    val productHigh = RegInit(false.B)
    val outputElement = RegInit(0.U(9.W))
    val compressTail = RegInit(false.B)
    val sourceBit = Reg(Bool())
    val arithmeticCarry = Reg(Bool())
    val element = RegInit(0.U(9.W))
    val flags = RegInit(0.U(5.W))
    val saturated = RegInit(false.B)
    val rounding = Reg(UInt(3.W))
    val population = RegInit(0.U(9.W))
    val first = RegInit("hffffffff".U(32.W))
    val seen = RegInit(false.B)
    val metadata = instruction.vector
    val control = metadata.control
    val state = metadata.state
    val op = control.operation
    val inst = instruction.scalar.inst
    val funct = inst(31, 26)
    val sew = state.vtype(5, 3)
    val destinationWidth = Reg(UInt(3.W))
    val sourceWidth = Reg(UInt(3.W))
    val widening = op === VectorSerialOperation.Widen.U
    val narrowing = op === VectorSerialOperation.Narrow.U
    val extending = op === VectorSerialOperation.Extend.U
    val fractionalMultiply = op === VectorSerialOperation.FractionalMultiply.U
    val wideningReduction = op === VectorSerialOperation.WidenReduction.U
    val compress = op === VectorSerialOperation.Compress.U
    val maskDestination = control.writesMask
    val reduction = op === VectorSerialOperation.IntegerReduction.U || op === VectorSerialOperation.FloatReduction.U ||
        wideningReduction
    val floatingSum = op === VectorSerialOperation.FloatReduction.U && (funct === 1.U || funct === 3.U)
    val permute = op === VectorSerialOperation.Permute.U
    val gather16 = permute && funct === 0x0e.U && inst(14, 12) === 0.U
    val gather = permute && (funct === 0x0c.U || gather16)
    val operandBWidth = Mux(gather16, 1.U, sew)
    val maskSource = op === VectorSerialOperation.MaskLogic.U ||
        op === VectorSerialOperation.MaskPrefix.U || op === VectorSerialOperation.Iota.U ||
        op === VectorSerialOperation.Population.U || op === VectorSerialOperation.First.U
    val scalarDestination = !control.writesVector
    def registerGroup(width: UInt, index: UInt): UInt = Mux(width === 0.U, index(7, 5),
        Mux(width === 1.U, index(6, 4), index(5, 3)))
    def wordLane(width: UInt, index: UInt): UInt = Mux(width === 0.U, index(4, 2),
        Mux(width === 1.U, index(3, 1), index(2, 0)))
    def wordShift(width: UInt, index: UInt): UInt = Mux(width === 0.U, Cat(index(1, 0), 0.U(3.W)),
        Mux(width === 1.U, Cat(index(0), 0.U(4.W)), 0.U(5.W)))
    val sourceGroup = registerGroup(sew, element)
    val sourceGroupA = registerGroup(sourceWidth, element)
    val destinationElement = Mux(compress, outputElement, element)
    val destinationGroup = Mux(maskDestination || scalarDestination || reduction, 0.U,
        registerGroup(destinationWidth, destinationElement))
    val lane = wordLane(sew, element)
    val elementShift = wordShift(sew, element)
    val bitPosition = Mux(maskDestination, element(7, 0),
        Mux(destinationWidth === 0.U, Cat(destinationElement(4, 0), 0.U(3.W)),
            Mux(destinationWidth === 1.U, Cat(destinationElement(3, 0), 0.U(4.W)), Cat(destinationElement(2, 0), 0.U(5.W)))))
    val widthMask = Mux(sew === 0.U, 255.U(32.W), Mux(sew === 1.U, 65535.U(32.W), "hffffffff".U))
    val count = Mux(reduction, metadata.groups.sourceCount(0), metadata.destination.count)
    val traversalWidth = Mux(reduction, sew, destinationWidth)
    val maximum = Mux(maskDestination || scalarDestination, 256.U(9.W),
        (count << Mux(traversalWidth === 0.U, 5.U, Mux(traversalWidth === 1.U, 4.U, 3.U)))(8, 0))
    val maximumExponent = 5.S(5.W) - sew.zext.pad(5) + state.vtype(2, 0).asSInt.pad(5)
    val vlmax = (1.U(9.W) << maximumExponent.asUInt)(8, 0)
    // VLMAX is zero or a power of two, including unsupported vtype encodings.
    // Prepare the low-bit mask independently of the late element data.
    val gatherAllowedBits = (vlmax - 1.U)(8, 0)
    val destinationMask = Mux(destinationWidth === 0.U, 255.U(32.W),
        Mux(destinationWidth === 1.U, 65535.U(32.W), "hffffffff".U))
    val enabled = !metadata.groups.mask || mask(element(7, 0))
    // Merge and carry consume v0 as data, rather than using it as a predicate.
    val maskIsData = op === VectorSerialOperation.Merge.U || op === VectorSerialOperation.Carry.U ||
        op === VectorSerialOperation.MaskCarry.U
    val body = element >= state.vstart && element < state.vl
    val slideOne = inst(14, 12) === 5.U || inst(14, 12) === 6.U
    val slideUp = permute && funct === 0x0e.U && !gather16
    val offset = Mux(control.immediate, inst(19, 15).pad(32), scalar)
    val active = Mux(compress, !compressTail && element < state.vl && b(0), body && (enabled || maskIsData) &&
        (op =/= VectorSerialOperation.Insert.U || element === 0.U) &&
        (!slideUp || slideOne || element >= offset))
    val ordinaryFill = state.vstart < state.vl && element >= state.vstart &&
        (element >= state.vl && (maskDestination || state.vtype(6)) ||
            body && !enabled && !maskIsData && state.vtype(7))
    val fill = !reduction && Mux(compress, compressTail && state.vl.orR && state.vtype(6),
        Mux(op === VectorSerialOperation.Insert.U,
            state.vstart === 0.U && state.vl.orR && element =/= 0.U && state.vtype(6), ordinaryFill))

    io.idle := phase === idle
    io.in.ready := io.idle && !io.flush
    io.readRequest := RegNext(nextPhase === operands || nextPhase === initialize ||
        nextPhase === readElement || nextPhase === gatherElement, false.B)
    io.scalarAddress := instruction.scalar.prs(0)
    val gatheredGroup = Mux(sew === 0.U, gatherIndex(7, 5), Mux(sew === 1.U, gatherIndex(6, 4), gatherIndex(5, 3)))
    val addresses = Wire(Vec(3, UInt(6.W)))
    addresses(0) := Mux(nextRead === operands, metadata.mask,
        metadata.source(0)(Mux(maskSource, 0.U, Mux(nextRead === gatherElement, gatheredGroup, sourceGroupA))))
    addresses(1) := metadata.source(1)(Mux(maskSource || reduction || compress, 0.U,
        registerGroup(operandBWidth, element)))
    addresses(2) := metadata.source(2)(destinationGroup)
    for (port <- 0 until 3) {
        io.read(port).address := readAddresses(port)
        io.read(port).oneHot := readSelection(port)
    }
    io.writeback.valid := phase === writeback && !io.flush
    io.writeback.bits.address := writeAddress
    io.writeback.bits.data := output.asTypeOf(Vec(8, UInt(32.W)))
    io.writeback.bits.bytes := VecInit.fill(8)(15.U(4.W))
    io.writeback.bits.ready := true.B
    io.completion.valid := phase === complete && !io.flush
    io.completion.bits := 0.U.asTypeOf(new VectorCompletion(p))
    io.completion.bits.robIdx := instruction.scalar.robIdx
    io.completion.bits.retirement.fflags := flags
    io.completion.bits.retirement.saturated := saturated
    io.completion.bits.scalar.valid := scalarDestination && instruction.scalar.prd.orR
    io.completion.bits.scalar.bits.destination := instruction.scalar.prd
    io.completion.bits.scalar.bits.data := Mux(op === VectorSerialOperation.First.U, first, population)

    def extendWidth(value: UInt, signed: Bool, width: UInt): UInt = Mux(width === 0.U,
        Cat(Fill(24, signed && value(7)), value(7, 0)), Mux(width === 1.U,
            Cat(Fill(16, signed && value(15)), value(15, 0)), value))
    def extend(value: UInt, signed: Bool): UInt = extendWidth(value, signed, sew)
    val signedOperands = !control.floating && (op === VectorSerialOperation.Divide.U && funct(0) ||
        op === VectorSerialOperation.IntegerCompare.U && funct(0) && funct >= 0x1a.U ||
        (op === VectorSerialOperation.Saturate.U || op === VectorSerialOperation.Average.U ||
            op === VectorSerialOperation.RoundedShift.U) && funct(0) ||
        narrowing && funct(0) || extending && inst(15) ||
        widening && (funct(0) && funct =/= 0x3f.U || funct === 0x3a.U || funct === 0x3e.U))
    val signedA = signedOperands || fractionalMultiply || wideningReduction && funct(0) ||
        op === VectorSerialOperation.Convert.U && inst(16) && !inst(17) && inst(15)
    val immediate = Cat(Fill(27, inst(19)), inst(19, 15))
    val rawA = (io.read(0).data(wordLane(sourceWidth, element)) >> wordShift(sourceWidth, element))(31, 0)
    val rawB = Mux(control.scalar, scalar, Mux(control.immediate, immediate,
        (io.read(1).data(wordLane(operandBWidth, element)) >> wordShift(operandBWidth, element))(31, 0)))
    val integerReduction = MuxLookup(funct, 0.U(32.W))(Seq(
        0.U -> BLevelPAdder32.sum(accumulator, a, 0.U), 1.U -> (accumulator & a), 2.U -> (accumulator | a),
        3.U -> (accumulator ^ a), 4.U -> Mux(accumulator < a, accumulator, a),
        5.U -> Mux(accumulator.asSInt < extend(a, true.B).asSInt, accumulator, extend(a, true.B)),
        6.U -> Mux(accumulator > a, accumulator, a),
        7.U -> Mux(accumulator.asSInt > extend(a, true.B).asSInt, accumulator, extend(a, true.B))))
    val less = Mux(funct(0), a.asSInt < b.asSInt, a < b)
    val integerCompare = MuxLookup(funct, false.B)(Seq(
        0x18.U -> (a === b), 0x19.U -> (a =/= b), 0x1a.U -> less, 0x1b.U -> less,
        0x1c.U -> (less || a === b), 0x1d.U -> (less || a === b),
        0x1e.U -> (!less && a =/= b), 0x1f.U -> (!less && a =/= b)))
    val logicRequest = Wire(new FpLogicRequest(1))
    val fpReducing = op === VectorSerialOperation.FloatReduction.U
    logicRequest.src1 := Mux(fpReducing, accumulator, Mux(funct === 0x1d.U || funct === 0x1f.U, b, a))
    logicRequest.src2 := Mux(fpReducing, a, Mux(funct === 0x1d.U || funct === 0x1f.U, a, b))
    logicRequest.op := Mux(fpReducing, Mux(funct === 5.U, FpMiscOp.FMIN.U, FpMiscOp.FMAX.U),
        Mux(funct === 0x18.U || funct === 0x1c.U, FpMiscOp.FEQ.U,
            Mux(funct === 0x19.U || funct === 0x1f.U, FpMiscOp.FLE.U, FpMiscOp.FLT.U)))
    logicRequest.tag := 0.U
    val fpLogic = FpLogic.execute(logicRequest)
    val maskA = a(0)
    val maskB = b(0)
    val maskLogic = MuxLookup(funct(2, 0), false.B)(Seq(
        0.U -> (maskA && !maskB), 1.U -> (maskA && maskB), 2.U -> (maskA || maskB),
        3.U -> (maskA ^ maskB), 4.U -> (maskA || !maskB), 5.U -> !(maskA && maskB),
        6.U -> !(maskA || maskB), 7.U -> !(maskA ^ maskB)))
    val carrying = op === VectorSerialOperation.Carry.U || op === VectorSerialOperation.MaskCarry.U
    val subtracting = funct(1)
    // Carry, saturation, averaging and widening share one adder. Operand complement
    // is collected with B; post-add policy/rounding uses a separate boundary.
    val fixedAdder = Module(new BLevelPAdder33)
    fixedAdder.io.src1 := Cat(!carrying && funct(0) && a(31), a)
    fixedAdder.io.src2 := arithmeticB
    fixedAdder.io.cin := arithmeticCarry.asUInt
    val carryResult = fixedAdder.io.res
    val carry = Mux(sew === 0.U, carryResult(8), Mux(sew === 1.U, carryResult(16), carryResult(32)))
    val addSubtract = arithmeticResult(31, 0)
    val signResult = Mux(sew === 0.U, addSubtract(7), Mux(sew === 1.U, addSubtract(15), addSubtract(31)))
    val signedOverflow = Mux(subtracting, a(31) =/= b(31), a(31) === b(31)) && signResult =/= a(31)
    val unsignedCarry = Mux(sew === 0.U, arithmeticResult(8),
        Mux(sew === 1.U, arithmeticResult(16), arithmeticResult(32)))
    val unsignedOverflow = Mux(subtracting, a < b, unsignedCarry)
    val overflow = Mux(funct(0), signedOverflow, unsignedOverflow)
    val positiveLimit = widthMask >> 1
    val negativeLimit = Mux(sew === 0.U, "hffffff80".U, Mux(sew === 1.U, "hffff8000".U, "h80000000".U))
    val saturationLimit = Mux(funct(0), Mux(a(31), negativeLimit, positiveLimit),
        Mux(subtracting, 0.U, widthMask))
    val saturatedResult = Mux(overflow, saturationLimit, addSubtract)
    def fixedRound(guard: Bool, sticky: Bool, retained: Bool): Bool = MuxLookup(state.vxrm, false.B)(Seq(
        0.U -> guard, 1.U -> (guard && (sticky || retained)), 3.U -> (!retained && (guard || sticky))))
    val unsignedAverageMask = Cat(widthMask, 1.U(1.W))
    val averageBase = Mux(funct(0), (carryResult.asSInt >> 1).asUInt,
        (carryResult & unsignedAverageMask) >> 1)(31, 0)
    val shiftAmount = Reg(UInt(5.W))
    val shiftRound = Reg(Bool())
    val shiftedArithmetic = op === VectorSerialOperation.RoundedShift.U || narrowing
    val shifter = Shifter.Shifter(a, shiftAmount, funct(0))
    val shiftGuard = VecInit((0 until 32).map(bit => a(bit) && shiftAmount === (bit + 1).U)).asUInt.orR
    val shiftSticky = VecInit((0 until 31).map(bit => a(bit) && shiftAmount > (bit + 1).U)).asUInt.orR
    // Average scaling and its rounding policy are collected at the same
    // boundary as shifts. The following incrementer has no operation mux.
    val roundedArithmetic = BLevelPAdder32.sum(addSubtract, 0.U(32.W), shiftRound.asUInt)
    val narrowingClip = funct(1)
    val narrowResult = Mux(narrowingClip, roundedArithmetic, addSubtract)
    val narrowNegativeLimit = Mux(sew === 0.U, "hffffff80".U, "hffff8000".U)
    val narrowPositiveLimit = Mux(funct(0), widthMask >> 1, widthMask)
    val signedFits8 = narrowResult(31, 7).andR || !narrowResult(31, 7).orR
    val signedFits16 = narrowResult(31, 15).andR || !narrowResult(31, 15).orR
    val narrowOverflow = Mux(funct(0), !Mux(sew === 0.U, signedFits8, signedFits16),
        Mux(sew === 0.U, narrowResult(31, 8).orR, narrowResult(31, 16).orR))
    val narrowClipped = Mux(narrowOverflow,
        Mux(funct(0) && narrowResult(31), narrowNegativeLimit, narrowPositiveLimit), narrowResult)
    val prefix = MuxLookup(inst(19, 15), false.B)(Seq(
        1.U -> (!seen && !sourceBit), 2.U -> (!seen && sourceBit), 3.U -> !seen))
    val directCandidates = Seq(
        VectorSerialOperation.IntegerCompare.U -> integerCompare.asUInt,
        VectorSerialOperation.FloatCompare.U -> (fpLogic.res ^ (funct === 0x1c.U).asUInt),
        VectorSerialOperation.Merge.U -> Mux(mask(element(7, 0)), b, a),
        VectorSerialOperation.Insert.U -> scalar,
        VectorSerialOperation.MaskLogic.U -> maskLogic.asUInt,
        VectorSerialOperation.Carry.U -> carryResult(31, 0),
        VectorSerialOperation.MaskCarry.U -> Mux(funct(1), carryResult(32), carry).asUInt,
        VectorSerialOperation.MaskPrefix.U -> prefix.asUInt,
        VectorSerialOperation.Iota.U -> population.pad(32),
        VectorSerialOperation.Index.U -> element.pad(32),
        VectorSerialOperation.IntegerReduction.U -> integerReduction,
        VectorSerialOperation.FloatReduction.U -> fpLogic.res,
        VectorSerialOperation.Permute.U -> a,
        VectorSerialOperation.Extend.U -> a,
        VectorSerialOperation.Compress.U -> a)
    val direct = Mux1H(directCandidates.map { case (operation, value) => (op === operation) -> value })

    val divide = Module(new DivSqrtSRT4(1))
    val convert = Module(new FpConvert(1, narrowIntegerEnabled = true))
    val multiply = if (externalMultiply) io.multiply.get else
        Module(new MulBooth2Wallce(1, splitFpFinish = true, blockWidth = 4)).io
    val estimate = Module(new FpEstimate)
    val wideningProduct = widening && funct >= 0x38.U
    val wideningAccumulate = widening && funct >= 0x3c.U
    val fixedArithmetic = op === VectorSerialOperation.Saturate.U ||
        op === VectorSerialOperation.Average.U || widening && !wideningProduct || shiftedArithmetic
    val useMultiply = floatingSum || wideningProduct || fractionalMultiply
    val conversion = op === VectorSerialOperation.Convert.U
    val estimation = op === VectorSerialOperation.FloatEstimate.U
    estimate.io.flush := io.flush
    estimate.io.in.valid := phase === issue && estimation && !io.flush
    estimate.io.in.bits.source := a
    estimate.io.in.bits.reciprocalSqrt := inst(19, 15) === 4.U
    estimate.io.in.bits.rounding := rounding
    estimate.io.out.ready := phase === awaitResult && estimation
    divide.io.flush := io.flush
    divide.io.in.valid := phase === issue && !conversion && !useMultiply && !estimation && !io.flush
    divide.io.in.bits := 0.U.asTypeOf(new DivideRequest(1))
    divide.io.in.bits.src1 := Mux(control.floating && funct === 0x21.U, b, a)
    divide.io.in.bits.src2 := Mux(control.floating && funct === 0x21.U, a, b)
    divide.io.in.bits.fp := control.floating
    divide.io.in.bits.op := Mux(control.floating,
        Mux(funct === 0x13.U, DivideOp.FSQRT.U, DivideOp.FDIV.U), Cat(funct(1), !funct(0)))
    divide.io.in.bits.signedInteger := !control.floating && funct(0)
    divide.io.in.bits.roundingMode := rounding
    divide.io.out.ready := phase === awaitResult && !conversion && !useMultiply && !estimation
    convert.io.flush := io.flush
    convert.io.integerWidth16.get := destinationWidth === 1.U
    convert.io.in.valid := phase === issue && conversion && !io.flush
    convert.io.in.bits.src1 := a
    val toFloat = inst(16) && !inst(17)
    convert.io.in.bits.toFloat := toFloat
    convert.io.in.bits.signedInt := inst(15)
    convert.io.in.bits.op := Mux(toFloat, Mux(inst(15), FpMiscOp.FCVT_S_W.U, FpMiscOp.FCVT_S_WU.U),
        Mux(inst(15), FpMiscOp.FCVT_W_S.U, FpMiscOp.FCVT_WU_S.U))
    convert.io.in.bits.roundingMode := Mux(inst(17), 1.U, rounding)
    convert.io.in.bits.tag := 0.U
    convert.io.out.ready := phase === awaitResult && conversion
    multiply.flush := io.flush
    multiply.in.valid := phase === issue && useMultiply && !io.flush
    multiply.out.ready := phase === awaitResult && useMultiply
    val multiplyInput = Reg(new MultiplyRequest(1))
    multiply.in.bits := multiplyInput
    val multiplyValues = Seq(Mux(floatingSum, accumulator, a),
        Mux(floatingSum, a, b), 0.U(32.W))
    val mr = WireDefault(0.U.asTypeOf(new MultiplyRequest(1)))
    mr.op := Mux(floatingSum, MultiplyOp.FADD,
        Mux(fractionalMultiply && productHigh, MultiplyOp.MULH, MultiplyOp.MUL))
    mr.fp := floatingSum
    mr.aSigned := !floatingSum
    mr.bSigned := !floatingSum
    mr.roundingMode := rounding
    mr.src1 := multiplyValues(0); mr.src2 := multiplyValues(1); mr.src3 := multiplyValues(2)
    mr.fpSrc1 := multiplyValues(0); mr.fpSrc2 := multiplyValues(1); mr.fpSrc3 := multiplyValues(2)
    mr.fpEffectiveExp1 := Mux(multiplyValues(0)(30, 23).orR, multiplyValues(0)(30, 23), 1.U)
    mr.fpEffectiveExp2 := Mux(multiplyValues(1)(30, 23).orR, multiplyValues(1)(30, 23), 1.U)
    mr.fpEffectiveExp3 := 1.U
    mr.fpZero1 := !multiplyValues(0)(30, 0).orR
    mr.fpZero2 := !multiplyValues(1)(30, 0).orR
    mr.fpZero3 := true.B
    val fractionalLow = Mux(sew === 2.U, productLow, multiply.out.bits.res)
    val fractionalBase = Mux(sew === 0.U, (fractionalLow.asSInt >> 7).asUInt,
        Mux(sew === 1.U, (fractionalLow.asSInt >> 15).asUInt,
            Cat(multiply.out.bits.res(30, 0), fractionalLow(31))))
    val fractionalGuard = Mux(sew === 0.U, fractionalLow(6),
        Mux(sew === 1.U, fractionalLow(14), fractionalLow(30)))
    val fractionalSticky = Mux(sew === 0.U, fractionalLow(5, 0).orR,
        Mux(sew === 1.U, fractionalLow(13, 0).orR, fractionalLow(29, 0).orR))
    val fractionalRounded = BLevelPAdder32.sum(fractionalBase, 0.U(32.W),
        fixedRound(fractionalGuard, fractionalSticky, fractionalBase(0)).asUInt)
    val fractionalOverflow = a === negativeLimit && b === negativeLimit

    val replacePosition = Mux(reduction, 0.U, bitPosition)
    val replaceMask = Mux(maskDestination, 1.U(256.W), destinationMask.pad(256)) << replacePosition
    val replaceData = (result.pad(256) << replacePosition)(255, 0)
    val updated = (output & ~replaceMask(255, 0)) | (replaceData & replaceMask(255, 0))
    val boundary = Mux(destinationWidth === 0.U, destinationElement(4, 0).andR,
        Mux(destinationWidth === 1.U, destinationElement(3, 0).andR, destinationElement(2, 0).andR))
    val finalElement = element + 1.U === maximum
    when(io.flush) {
        nextPhase := idle
        flags := 0.U
        saturated := false.B
    }.otherwise {
        when(io.in.fire) {
            instruction := io.in.bits
            val widths = VectorElementWidths(io.in.bits.scalar.inst,
                io.in.bits.vector.control, io.in.bits.vector.state.vtype(5, 3))
            destinationWidth := widths._1
            sourceWidth := widths._2
            element := 0.U
            flags := 0.U
            saturated := false.B
            population := 0.U
            first := "hffffffff".U
            seen := false.B
            rounding := io.roundingMode
            productHigh := false.B
            outputElement := 0.U
            compressTail := io.in.bits.vector.state.vl === 0.U
            enterRead(operands)
            assert(io.in.bits.vector.control.execution === VectorExecution.Serial.U)
        }
        switch(phase) {
            is(prepareRead) {
                readAddresses := addresses
                readSelection := VecInit(addresses.map(address => UIntToOH(address, 64)))
                // Each output register is initialized before its elements are
                // collected. Keep that destination stable through writeback.
                when(nextRead === initialize) {
                    writeAddress := metadata.destination.physical(destinationGroup)
                }
                nextPhase := nextRead
            }
            is(operands) { when(io.readAvailable) {
                scalar := io.scalarData
                mask := io.read(0).data.asUInt
                enterRead(initialize)
            } }
            is(initialize) { when(io.readAvailable) {
                val old = Mux(metadata.groups.sourceCount(2).orR, io.read(2).data.asUInt, Fill(256, 1.U(1.W)))
                val initial = Mux(reduction && state.vstart < state.vl && state.vtype(6),
                    old | ~(destinationMask.pad(256)), old)
                val seed = extendWidth(io.read(1).data(0), funct(0) && !control.floating, destinationWidth)
                // A nonempty reduction copies its scalar seed even when every
                // source element is masked. No active update will write it later.
                output := Mux(reduction && state.vstart < state.vl,
                    (initial & ~destinationMask.pad(256)) | (seed & destinationMask).pad(256), initial)
                accumulator := seed
                // All operands of an M1 (or fractional LMUL) SEW32 shuffle are
                // already present on the existing read ports. Re-reading the
                // same registers for each element only serializes a lane mux.
                // Keep larger groups and narrower elements on the iterative
                // path; neither register-file ports nor payload buffers grow.
                val registerShuffle = sew === 2.U && metadata.destination.count === 1.U &&
                    metadata.groups.sourceCount(0) <= 1.U && metadata.groups.sourceCount(1) <= 1.U &&
                    (permute || op === VectorSerialOperation.Merge.U)
                val oldWords = old.asTypeOf(Vec(8, UInt(32.W)))
                val indices16 = io.read(1).data.asUInt.asTypeOf(Vec(16, UInt(16.W)))
                val shuffled = Wire(Vec(8, UInt(32.W)))
                for (i <- 0 until 8) {
                    val position = i.U(9.W)
                    val inBody = position >= state.vstart && position < state.vl
                    val laneEnabled = !metadata.groups.mask || mask(i)
                    val indexOperand = Mux(control.immediate, inst(19, 15).pad(32),
                        Mux(control.scalar, scalar, Mux(gather16, indices16(i).pad(32), io.read(1).data(i))))
                    // Retain the carry on down-slides: a large unsigned XLEN
                    // offset must yield zero, rather than wrap into lane zero.
                    val distance = Mux(slideOne, 1.U(32.W), offset)
                    val index = Mux(gather, indexOperand.pad(33),
                        Mux(slideUp, position.pad(33) - distance.pad(33),
                            position.pad(33) + distance.pad(33)))
                    val sourceFits = index < vlmax
                    val inserted = slideOne && !gather &&
                        Mux(slideUp, position === 0.U, position + 1.U === state.vl)
                    val permutation = Mux(inserted, scalar,
                        Mux(sourceFits, io.read(0).data(index(2, 0)), 0.U))
                    val mergeOperand = Mux(control.scalar, scalar,
                        Mux(control.immediate, immediate, io.read(1).data(i)))
                    val value = Mux(permute, permutation,
                        Mux(mask(i), mergeOperand, io.read(0).data(i)))
                    val writes = inBody && (laneEnabled || maskIsData) &&
                        (!slideUp || slideOne || position >= offset)
                    val agnostic = state.vstart < state.vl && position >= state.vstart &&
                        (position >= state.vl && state.vtype(6) ||
                            inBody && !laneEnabled && !maskIsData && state.vtype(7))
                    shuffled(i) := Mux(writes, value, Mux(agnostic, "hffffffff".U, oldWords(i)))
                }
                when(registerShuffle) {
                    output := shuffled.asUInt
                    element := maximum
                    nextPhase := writeback
                }.otherwise { enterRead(readElement) }
            } }
            is(readElement) { when(io.readAvailable) {
                a := Mux(maskSource, (io.read(0).data.asUInt >> element(7, 0))(0).asUInt,
                    extendWidth(rawA, signedA, sourceWidth))
                val collectedB = Mux(maskSource || compress, (io.read(1).data.asUInt >> element(7, 0))(0).asUInt,
                    extendWidth(rawB, Mux(widening,
                        funct === 0x31.U || funct === 0x33.U || funct === 0x35.U || funct === 0x37.U ||
                            funct === 0x3b.U || funct === 0x3d.U || funct === 0x3f.U,
                        signedA && !narrowing), operandBWidth))
                b := collectedB
                shiftAmount := collectedB(4, 0) & Mux(sourceWidth === 0.U, 7.U,
                    Mux(sourceWidth === 1.U, 15.U, 31.U))
                arithmeticB := Cat(!carrying && funct(0) && collectedB(31), collectedB) ^ Fill(33, subtracting)
                c := extendWidth((io.read(2).data(wordLane(destinationWidth, element)) >>
                    wordShift(destinationWidth, element))(31, 0), signedOperands, destinationWidth)
                sourceBit := (io.read(0).data.asUInt >> element(7, 0))(0)
                // Capture the carry policy with B; no mask selection precedes the adder.
                arithmeticCarry := subtracting ^ (carrying && !inst(25) && mask(element(7, 0)))
                val gatheredIndex = Mux(gather, Mux(control.immediate, inst(19, 15).pad(32),
                    extendWidth(rawB, false.B, operandBWidth)),
                    Mux(slideUp, element.pad(32) - Mux(slideOne, 1.U, offset),
                        element.pad(32) + Mux(slideOne, 1.U, offset)))
                gatherIndex := gatheredIndex
                val gatheredIndexFits = vlmax.orR && !gatheredIndex(31, 9).orR &&
                    !(gatheredIndex(8, 0) & ~gatherAllowedBits).orR
                gatherValid := gatheredIndexFits && !(slideUp && slideOne && element === 0.U) &&
                    !(permute && funct === 0x0f.U && slideOne && element + 1.U === state.vl)
                when(permute && active) { enterRead(gatherElement) }.otherwise { nextPhase := evaluate }
            } }
            is(gatherElement) { when(io.readAvailable) {
                val gatherLane = Mux(sew === 0.U, gatherIndex(4, 2), Mux(sew === 1.U, gatherIndex(3, 1), gatherIndex(2, 0)))
                val gatherShift = Mux(sew === 0.U, Cat(gatherIndex(1, 0), 0.U(3.W)),
                    Mux(sew === 1.U, Cat(gatherIndex(0), 0.U(4.W)), 0.U(5.W)))
                a := Mux(gatherValid, (io.read(0).data(gatherLane) >> gatherShift)(31, 0),
                    Mux(slideOne && !gather, scalar, 0.U))
                nextPhase := evaluate
            } }
            is(evaluate) {
                when(useMultiply) { multiplyInput := mr }
                when(active && fixedArithmetic) {
                    arithmeticResult := Mux(shiftedArithmetic, shifter.io.res.pad(33),
                        Mux(op === VectorSerialOperation.Average.U, averageBase.pad(33), fixedAdder.io.res))
                    shiftRound := Mux(shiftedArithmetic,
                        fixedRound(shiftGuard, shiftSticky, shifter.io.res(0)),
                        fixedRound(carryResult(0), false.B, carryResult(1)))
                    nextPhase := finishArithmetic
                }.elsewhen(active && (op === VectorSerialOperation.Divide.U || conversion || useMultiply || estimation)) {
                    nextPhase := issue
                }.otherwise {
                    result := Mux(fill && !active, "hffffffff".U,
                        Mux(wideningReduction, BLevelPAdder32.sum(accumulator, a, 0.U), direct))
                    when(active && (op === VectorSerialOperation.FloatCompare.U || fpReducing)) {
                        flags := flags | fpLogic.fflags
                    }
                    when(body && enabled && sourceBit && maskSource) {
                        population := population + 1.U
                        when(!seen) { first := element }
                        seen := true.B
                    }
                    nextPhase := update
                }
            }
            is(finishArithmetic) {
                result := Mux(shiftedArithmetic, Mux(narrowing,
                    Mux(narrowingClip, narrowClipped, narrowResult), roundedArithmetic),
                    Mux(op === VectorSerialOperation.Average.U, roundedArithmetic,
                        Mux(op === VectorSerialOperation.Saturate.U, saturatedResult, addSubtract)))
                when(op === VectorSerialOperation.Saturate.U && overflow) { saturated := true.B }
                when(narrowing && narrowingClip && narrowOverflow) { saturated := true.B }
                nextPhase := update
            }
            is(issue) { when(divide.io.in.fire || convert.io.in.fire || multiply.in.fire || estimate.io.in.fire) { nextPhase := awaitResult } }
            is(awaitResult) {
                when(divide.io.out.fire || convert.io.out.fire || multiply.out.fire || estimate.io.out.fire) {
                    when(fractionalMultiply && sew === 2.U && !productHigh) {
                        productLow := multiply.out.bits.res
                        productHigh := true.B
                        multiplyInput.op := MultiplyOp.MULH
                        nextPhase := issue
                    }.otherwise {
                        result := Mux(estimation, estimate.io.out.bits.result,
                            Mux(fractionalMultiply, Mux(fractionalOverflow, positiveLimit, fractionalRounded),
                            Mux(conversion, convert.io.out.bits.res, Mux(useMultiply,
                                Mux(wideningAccumulate, BLevelPAdder32.sum(c, multiply.out.bits.res, 0.U),
                                    multiply.out.bits.res), divide.io.out.bits.res))))
                        flags := flags | Mux(estimation, estimate.io.out.bits.flags,
                            Mux(conversion, convert.io.out.bits.fflags,
                                Mux(useMultiply, multiply.out.bits.fflags, divide.io.out.bits.fflags)))
                        when(fractionalMultiply && fractionalOverflow) { saturated := true.B }
                        productHigh := false.B
                        nextPhase := update
                    }
                }
            }
            is(update) {
                when(control.writesVector && (active || fill)) { output := updated }
                when(reduction && active) { accumulator := extendWidth(result, funct(0) && !control.floating, destinationWidth) }
                when(compress) {
                    when(!compressTail) {
                        element := element + 1.U
                        when(element + 1.U === state.vl) { compressTail := true.B }
                    }
                    when(active || compressTail) { outputElement := outputElement + 1.U }
                    val finalOutput = compressTail && outputElement + 1.U === maximum
                    when(finalOutput || (active || compressTail) && boundary) { nextPhase := writeback }
                    .otherwise { enterRead(readElement) }
                }.otherwise {
                    element := element + 1.U
                    when(finalElement) { nextPhase := Mux(control.writesVector, writeback, complete) }
                    .elsewhen(!maskDestination && !scalarDestination && !reduction && boundary) { nextPhase := writeback }
                    .otherwise { enterRead(readElement) }
                }
            }
            is(writeback) {
                when(destinationElement === maximum) { nextPhase := complete }.otherwise { enterRead(initialize) }
            }
            is(complete) { when(io.completion.fire) { nextPhase := idle } }
        }
    }
    assert(io.readRequest === (phase === operands || phase === initialize ||
        phase === readElement || phase === gatherElement))
}
