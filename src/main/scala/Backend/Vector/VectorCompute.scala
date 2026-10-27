import chisel3._
import chisel3.util._
import ZirconConfig.{BackendParams, EXEOp, MultiplyOp, VectorParams}

class VectorComputeTask(p: BackendParams) extends Bundle {
    val arithmetic = new VectorArithmeticRequest(p.robWidth)
    val address = UInt(6.W)
    val old = Vec(8, UInt(32.W))
    val fill = UInt(8.W)
    val bytes = UInt(4.W)
    val shift = UInt(5.W)
    val highShift = UInt(5.W)
    val highMultiply = Bool()
    val firstSubelement = Bool()
    val lastSubelement = Bool()
    val last = Bool()
    val configuration = Bool()
    val writesVector = Bool()
    val fma = Bool()
    val state = new VectorCSRState
    val scalar = Valid(new VectorScalarWrite(p))
}

/** Task expansion and registered operand collection around the shared eight
  * arithmetic lanes. SEW8/16 reuse lanes over subelements; SEW32 issues one
  * complete physical register per cycle, including across LMUL groups.
  */
class VectorCompute(val p: BackendParams = BackendParams(), val shareSerialMultiply: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorInstruction(p)))
        val read = Flipped(Vec(3, new VectorRegisterRead(VectorParams())))
        val scalarAddress = Output(UInt(p.tagWidth.W))
        val scalarData = Input(UInt(32.W))
        val scalarAvailable = Input(Bool())
        val registerReady = Input(UInt(64.W))
        val roundingMode = Input(UInt(3.W))
        val writeback = Output(Valid(new VectorTaskWriteback))
        val completion = Decoupled(new VectorCompletion(p))
        val configuration = Output(Valid(new VectorCSRState))
        val flush = Input(Bool())
        val readAvailable = Input(Bool())
        val readRequest = Output(Bool())
        val idle = Output(Bool())
        val activity = Output(UInt(7.W))
        val serialMultiply = if (shareSerialMultiply) Some(new MultiplyIO(1)) else None
        val serialActive = if (shareSerialMultiply) Some(Input(Bool())) else None
    })
    val instruction = Reg(new VectorInstruction(p))
    val busy = RegInit(false.B)
    val prepare = RegInit(0.U(2.W)) // 0=mask/rs1, 1=rs2, 2=collected task.
    val scalar = Reg(UInt(32.W))
    val registerVtype = Reg(UInt(32.W))
    val mask = Reg(UInt(256.W))
    val group = RegInit(0.U(3.W))
    val groupOH = RegInit(1.U(8.W))
    val readAddresses = RegInit(VecInit.fill(3)(0.U(6.W)))
    val pending = RegInit(0.U(5.W))
    io.idle := pending === 0.U
    when(io.flush) { pending := 0.U }
    .elsewhen(io.in.fire =/= io.completion.fire) {
        pending := Mux(io.in.fire, pending + 1.U, pending - 1.U)
    }
    val subelement = RegInit(0.U(2.W))
    val configuration = Module(new VectorConfiguration)
    val control = instruction.vector.control
    val state = instruction.vector.state
    val sew = state.vtype(5, 3)
    val isConfiguration = control.execution === VectorExecution.Configuration.U
    val scalarMove = !isConfiguration && !control.writesVector
    val wholeMove = instruction.scalar.inst(31, 26) === 0x27.U && instruction.scalar.inst(14, 12) === 3.U
    val effectiveVl = Mux(wholeMove,
        instruction.vector.destination.count.pad(32) << Mux(sew === 0.U, 5.U, Mux(sew === 1.U, 4.U, 3.U)), state.vl)
    val subelementLast = Mux(sew === 0.U, 3.U, Mux(sew === 1.U, 1.U, 0.U))
    val lastSubelement = isConfiguration || scalarMove || subelement === subelementLast
    val lastGroup = isConfiguration || scalarMove || group +& 1.U === instruction.vector.destination.count
    val last = lastSubelement && lastGroup

    // The scalar PRF has asynchronous reads. Select the incoming tag while
    // the current vector collector owns its registered operands, and capture
    // that scalar only at instruction acceptance. Mask/VSET preparation keeps
    // priority for the current instruction's additional scalar operand.
    io.scalarAddress := Mux(busy && prepare === 1.U, instruction.scalar.prs(1),
        Mux(busy && prepare === 0.U, instruction.scalar.prs(0), io.in.bits.scalar.prs(0)))
    for (port <- 0 until 3) {
        // The next group is known at the preceding collection edge. Keep its
        // tag mux off the asynchronous RF-to-operand collection path.
        io.read(port).address := readAddresses(port)
        io.read(port).oneHot := UIntToOH(readAddresses(port), 64)
    }
    val inst = instruction.scalar.inst
    val vsetImmediate = inst(31, 30) === 3.U
    val vsetRegister = inst(31, 25) === "h40".U
    configuration.io.request.vtype := Mux(vsetRegister, registerVtype,
        Mux(vsetImmediate, inst(29, 20).pad(32), inst(30, 20).pad(32)))
    configuration.io.request.avl := Mux(vsetImmediate, inst(19, 15).pad(32), scalar)
    configuration.io.request.rs1Zero := inst(19, 15) === 0.U
    configuration.io.request.rdZero := inst(11, 7) === 0.U
    configuration.io.request.immediateAvl := vsetImmediate
    configuration.io.request.oldVl := state.vl

    val collectorValid = RegInit(false.B)
    val collector = Reg(new VectorComputeTask(p))
    val configurationValid = RegInit(false.B)
    val configurationHeld = RegInit(false.B)
    val configurationResult = Reg(new VectorCompletion(p))
    val configurationState = Reg(new VectorCSRState)
    val configurationUnchanged = Reg(Bool())
    val arithmetic = Module(new VectorArithmetic(p.robWidth, shareSerialMultiply))
    if (shareSerialMultiply) {
        arithmetic.io.serialMultiply.get <> io.serialMultiply.get
        arithmetic.io.serialActive.get := io.serialActive.get
        when(io.serialActive.get) { assert(io.idle && !busy && !collectorValid) }
    }
    arithmetic.io.flush := io.flush
    arithmetic.io.in.valid := collectorValid && !io.flush
    arithmetic.io.in.bits := collector.arithmetic
    val collectorReady = !collectorValid || arithmetic.io.in.ready
    val groupReady = (0 until 3).map(port =>
        group >= instruction.vector.groups.sourceCount(port) || io.registerReady(readAddresses(port))).reduce(_ && _)
    val configurationReady = !configurationValid || io.completion.ready && !arithmetic.io.out.valid
    io.readRequest := busy && (prepare =/= 2.U || collectorReady)
    val collect = busy && prepare === 2.U &&
        Mux(isConfiguration, configurationReady, collectorReady && groupReady && io.readAvailable) && !io.flush
    val inputConfiguration = io.in.bits.vector.control.execution === VectorExecution.Configuration.U
    val inputScalar = io.in.bits.vector.control.scalar ||
        inputConfiguration && io.in.bits.scalar.inst(31, 30) =/= 3.U
    io.in.ready := (!busy || collect && last) && (!inputScalar || io.scalarAvailable)
    io.activity := VecInit(Seq(
        busy && prepare===2.U && !groupReady,
        busy && prepare===2.U && groupReady && !io.readAvailable,
        io.in.valid && (!busy || collect && last) && inputScalar && !io.scalarAvailable,
        busy,
        arithmetic.io.in.fire && collector.fma,
        io.in.fire,
        arithmetic.io.in.fire)).asUInt
    val incoming = WireDefault(0.U.asTypeOf(new VectorComputeTask(p)))
    incoming.address := Mux1H(groupOH, instruction.vector.destination.physical)
    incoming.firstSubelement := subelement === 0.U
    incoming.lastSubelement := lastSubelement
    incoming.last := last
    incoming.configuration := isConfiguration
    incoming.writesVector := control.writesVector
    incoming.state := state
    incoming.state.vl := configuration.io.vl
    incoming.state.vtype := configuration.io.vtype
    incoming.state.vstart := 0.U
    incoming.scalar.valid := (isConfiguration || scalarMove) && instruction.scalar.prd.orR
    incoming.scalar.bits.destination := instruction.scalar.prd
    val scalarElement = io.read(0).data(0)
    val integerScalar = Mux(sew === 0.U, Cat(Fill(24, scalarElement(7)), scalarElement(7, 0)),
        Mux(sew === 1.U, Cat(Fill(16, scalarElement(15)), scalarElement(15, 0)), scalarElement))
    incoming.scalar.bits.data := Mux(isConfiguration, configuration.io.vl,
        Mux(control.floating, scalarElement, integerScalar))
    incoming.arithmetic.tag := instruction.scalar.robIdx
    incoming.arithmetic.multiply := control.execution === VectorExecution.Multiply.U
    incoming.arithmetic.integerAccumulate := incoming.arithmetic.multiply && !control.floating && control.operation(4)
    incoming.arithmetic.negateProduct := control.operation(0)
    incoming.arithmetic.fpLogic := control.execution === VectorExecution.FpLogic.U
    incoming.arithmetic.minmax := control.minmax
    incoming.arithmetic.maximum := control.maximum
    incoming.arithmetic.fp := control.floating
    incoming.arithmetic.roundingMode := io.roundingMode
    incoming.arithmetic.aluOp := control.operation
    val narrowHigh = control.execution === VectorExecution.Multiply.U &&
        !control.floating && !control.operation(4) && sew =/= 2.U && control.operation =/= MultiplyOp.MUL
    incoming.arithmetic.multiplyOp := Mux(narrowHigh || incoming.arithmetic.integerAccumulate,
        MultiplyOp.MUL, control.operation(3, 0))
    incoming.highMultiply := narrowHigh
    incoming.highShift := Mux(sew === 0.U, 8.U, 16.U)
    val shift = Mux(sew === 0.U, Cat(subelement, 0.U(3.W)),
        Mux(sew === 1.U, Cat(subelement(0), 0.U(4.W)), 0.U))
    incoming.shift := shift
    incoming.bytes := Mux(sew === 0.U, (1.U(4.W) << subelement)(3, 0),
        Mux(sew === 1.U, Mux(subelement(0), 12.U, 3.U), 15.U))
    val immediate = Cat(Fill(27, inst(19)), inst(19, 15))
    val signedA = !control.floating &&
        (control.execution === VectorExecution.ALU.U && control.operation === EXEOp.SRA ||
            control.minmax && !control.unsigned ||
            control.execution === VectorExecution.Multiply.U &&
                (control.operation === MultiplyOp.MULH || control.operation === MultiplyOp.MULHSU))
    val signedB = !control.floating && (control.minmax && !control.unsigned ||
        control.execution === VectorExecution.Multiply.U && control.operation === MultiplyOp.MULH)
    def extend(value: UInt, signed: Bool): UInt = Mux(sew === 0.U,
        Cat(Fill(24, signed && value(7)), value(7, 0)), Mux(sew === 1.U,
            Cat(Fill(16, signed && value(15)), value(15, 0)), value))
    val active = Wire(Vec(8, Bool()))
    val fill = Wire(Vec(8, Bool()))
    for (lane <- 0 until 8) {
        val element = Mux(sew === 0.U, Cat(group, lane.U(3.W), subelement),
            Mux(sew === 1.U, Cat(group, lane.U(3.W), subelement(0)), Cat(group, lane.U(3.W))))
        val enabled = !instruction.vector.groups.mask || mask(element)
        val body = element >= state.vstart && element < effectiveVl
        active(lane) := control.writesVector && body && enabled
        fill(lane) := control.writesVector && !wholeMove && state.vstart < effectiveVl && element >= state.vstart &&
            (element >= effectiveVl && state.vtype(6) || body && !enabled && state.vtype(7))
        val vs2 = Mux(instruction.vector.groups.sourceCount(0).orR, io.read(0).data(lane) >> shift, 0.U)
        val vs1 = Mux(control.scalar, scalar,
            Mux(control.immediate, immediate, Mux(wholeMove, 0.U,
                io.read(1).data(lane) >> shift)))
        val operandA = extend(vs2, signedA)
        val operandB = extend(vs1, signedB)
        val shifting = control.execution === VectorExecution.ALU.U &&
            (control.operation === EXEOp.SLL || control.operation === EXEOp.SRL || control.operation === EXEOp.SRA)
        incoming.arithmetic.source(1)(lane) := Mux(shifting,
            operandB & Mux(sew === 0.U, 7.U, Mux(sew === 1.U, 15.U, 31.U)), operandB)
        incoming.arithmetic.source(0)(lane) := Mux(control.reverse, operandB, operandA)
        when(control.reverse) { incoming.arithmetic.source(1)(lane) := operandA }
        val destinationOperand = Mux(control.floating, io.read(2).data(lane),
            extend(io.read(2).data(lane) >> shift, false.B))
        incoming.arithmetic.source(2)(lane) := destinationOperand
        val destructiveMultiply = control.readsDestination && inst(31, 26) < 0x2c.U &&
            (control.floating || incoming.arithmetic.integerAccumulate)
        when(destructiveMultiply) {
            incoming.arithmetic.source(0)(lane) := destinationOperand
            incoming.arithmetic.source(2)(lane) := operandA
        }
        incoming.old(lane) := Mux(instruction.vector.groups.sourceCount(2).orR,
            io.read(2).data(lane), "hffffffff".U)
    }
    incoming.fma := control.floating && sew === 2.U && inst(31, 29) === 5.U
    incoming.arithmetic.active := active.asUInt
    incoming.fill := fill.asUInt

    // Sideband follows exactly the shared arithmetic advance.
    val sideband = Seq.fill(arithmetic.latency)(Reg(new VectorComputeTask(p)))
    when(arithmetic.io.in.ready) {
        sideband.head := collector
        for (stage <- 1 until arithmetic.latency - 1) sideband(stage) := sideband(stage - 1)
    }
    when(arithmetic.io.outputAdvance) { sideband.last := sideband(arithmetic.latency - 2) }
    val output = sideband.last
    val fmaElements = RegInit(0.U(7.W))
    val combinedFma = fmaElements + Mux(output.fma, PopCount(arithmetic.io.out.bits.active), 0.U)
    val flags = RegInit(0.U(5.W))
    val combinedFlags = flags | arithmetic.io.out.bits.fflags
    val arithmeticCompletion = arithmetic.io.out.valid && output.last
    val selectConfiguration = configurationValid && (configurationHeld || !arithmeticCompletion)
    arithmetic.io.out.ready := !output.last || io.completion.ready && !selectConfiguration
    io.completion.valid := (arithmeticCompletion || configurationValid) && !io.flush
    io.completion.bits.robIdx := arithmetic.io.out.bits.tag
    io.completion.bits.scalar := output.scalar
    io.completion.bits.retirement.fmaElements := combinedFma
    io.completion.bits.retirement.configuration := output.configuration
    io.completion.bits.retirement.vl := output.state.vl
    io.completion.bits.retirement.vtype := output.state.vtype
    io.completion.bits.retirement.fflags := combinedFlags
    io.completion.bits.retirement.saturated := false.B
    when(selectConfiguration) { io.completion.bits := configurationResult }
    // Redundant immediate configurations may overlap younger decode. Their
    // delayed completion must not overwrite a newer speculative configuration
    // or release the fence belonging to that newer instruction.
    io.configuration.valid := io.completion.fire && selectConfiguration && !configurationUnchanged
    io.configuration.bits := configurationState
    io.writeback.valid := arithmetic.io.out.fire && output.writesVector && !io.flush
    io.writeback.bits.address := output.address
    io.writeback.bits.ready := output.lastSubelement
    for (lane <- 0 until 8) {
        val result = Mux(output.highMultiply,
            arithmetic.io.out.bits.data(lane) >> output.highShift, arithmetic.io.out.bits.data(lane))
        val produced = Mux(output.fill(lane), "hffffffff".U, result) << output.shift
        val selected = arithmetic.io.out.bits.active(lane) || output.fill(lane)
        val bits = Cat(output.bytes.asBools.reverse.map(bit => Fill(8, bit)))
        io.writeback.bits.data(lane) := (output.old(lane) & ~Mux(selected, bits, 0.U)) |
            (produced(31, 0) & Mux(selected, bits, 0.U))
        io.writeback.bits.bytes(lane) := Mux(output.firstSubelement, 15.U,
            Mux(selected, output.bytes, 0.U))
    }
    when(io.flush) {
        busy := false.B
        collectorValid := false.B
        flags := 0.U
        fmaElements := 0.U
        configurationValid := false.B
        configurationHeld := false.B
    }.otherwise {
        when(io.completion.fire) { configurationHeld := false.B }
            .elsewhen(selectConfiguration) { configurationHeld := true.B }
        when(io.completion.fire && selectConfiguration) { configurationValid := false.B }
        when(collectorReady) { collectorValid := collect && !isConfiguration }
        when(collect) {
            when(isConfiguration) {
                configurationValid := true.B
                configurationState := incoming.state
                configurationUnchanged := VectorConfiguration.unchangedImmediate(inst,state)
                configurationResult.robIdx := instruction.scalar.robIdx
                configurationResult.scalar := incoming.scalar
                configurationResult.retirement.fmaElements := 0.U
                configurationResult.retirement.configuration := true.B
                configurationResult.retirement.vl := configuration.io.vl
                configurationResult.retirement.vtype := configuration.io.vtype
                configurationResult.retirement.fflags := 0.U
                configurationResult.retirement.saturated := false.B
            }.otherwise { collector := incoming }
            when(last) { busy := false.B }
            when(lastSubelement) {
                group := group + 1.U
                groupOH := Cat(groupOH(6, 0), groupOH(7))
                subelement := 0.U
                for (port <- 0 until 3) {
                    readAddresses(port) := Mux1H(Cat(groupOH(6, 0), groupOH(7)),
                        instruction.vector.source(port))
                }
            }
                .otherwise { subelement := subelement + 1.U }
        }
        when(busy && prepare === 0.U && io.readAvailable) {
            mask := io.read(0).data.asUInt
            prepare := 2.U
            readAddresses(0) := instruction.vector.source(0)(0)
        }.elsewhen(busy && prepare === 1.U && io.scalarAvailable) {
            registerVtype := io.scalarData
            prepare := 2.U
        }
        when(io.in.fire) {
            instruction := io.in.bits
            scalar := io.scalarData
            busy := true.B
            prepare := Mux(inputConfiguration && io.in.bits.scalar.inst(31, 25) === "h40".U, 1.U,
                Mux(io.in.bits.vector.groups.mask, 0.U, 2.U))
            group := 0.U
            groupOH := 1.U
            subelement := 0.U
            for (port <- 0 until 3) {
                readAddresses(port) := io.in.bits.vector.source(port)(0)
            }
            when(io.in.bits.vector.groups.mask) { readAddresses(0) := io.in.bits.vector.mask }
            assert(io.in.bits.vector.control.execution === VectorExecution.ALU.U ||
                io.in.bits.vector.control.execution === VectorExecution.Multiply.U ||
                io.in.bits.vector.control.execution === VectorExecution.FpLogic.U ||
                io.in.bits.vector.control.execution === VectorExecution.Configuration.U)
        }
        when(arithmetic.io.out.fire) {
            flags := Mux(output.last, 0.U, combinedFlags)
            fmaElements := Mux(output.last, 0.U, combinedFma)
        }
    }
    when(busy) {
        assert(groupOH === UIntToOH(group, 8))
        for (port <- 0 until 3) {
            val expected = if (port == 0) Mux(prepare === 0.U && instruction.vector.groups.mask,
                instruction.vector.mask, Mux1H(groupOH, instruction.vector.source(port)))
                else Mux1H(groupOH, instruction.vector.source(port))
            assert(readAddresses(port) === expected)
        }
    }
}
