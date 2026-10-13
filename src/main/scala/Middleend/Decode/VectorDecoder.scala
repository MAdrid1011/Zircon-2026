import chisel3._
import chisel3.util._
import ZirconConfig.{EXEOp, MultiplyOp}

object VectorExecution {
    val ALU = 0
    val Multiply = 1
    val Configuration = 2
    val Load = 3
    val Store = 4
    val Serial = 5
    val FpLogic = 6
}

object VectorSerialOperation {
    val Divide = 0
    val Convert = 1
    val IntegerCompare = 2
    val FloatCompare = 3
    val Merge = 4
    val Insert = 5
    val MaskLogic = 6
    val Carry = 7
    val MaskCarry = 8
    val Population = 9
    val First = 10
    val MaskPrefix = 11
    val Iota = 12
    val Index = 13
    val Saturate = 14
    val Average = 15
    val RoundedShift = 16
    val IntegerReduction = 17
    val FloatReduction = 18
    val Permute = 20
    val Widen = 21
    val Narrow = 22
    val Extend = 23
    val FractionalMultiply = 24
    val WidenReduction = 25
    val FloatEstimate = 26
    val Compress = 27
}

/** Effective element widths shared by register grouping and task collection. */
object VectorElementWidths {
    def apply(inst: UInt, control: VectorDecodeControl, sew: UInt): (UInt, UInt) = {
        val serial = control.execution === VectorExecution.Serial.U
        val widening = serial && (control.operation === VectorSerialOperation.Widen.U ||
            control.operation === VectorSerialOperation.WidenReduction.U)
        val narrowing = serial && control.operation === VectorSerialOperation.Narrow.U
        val extending = serial && control.operation === VectorSerialOperation.Extend.U
        val converting = serial && control.operation === VectorSerialOperation.Convert.U
        val wideConversion = converting && (inst(19, 15) === 10.U || inst(19, 15) === 11.U)
        val narrowConversion = converting && inst(19)
        val destination = Mux(widening || wideConversion, sew + 1.U, sew)
        val wideInput = widening && inst(31, 26) >= 0x34.U && inst(31, 26) <= 0x37.U
        val source = Mux(narrowing || wideInput || narrowConversion, sew + 1.U,
            Mux(extending, sew - (4.U - inst(17, 16)), sew))
        (destination, source)
    }
}

class VectorDecodeControl extends Bundle {
    val recognized = Bool()
    val legal = Bool()
    val execution = UInt(3.W)
    val operation = UInt(5.W)
    val scalar = Bool()
    val immediate = Bool()
    val floating = Bool()
    val readsVs1 = Bool()
    val readsVs2 = Bool()
    val readsDestination = Bool()
    val writesVector = Bool()
    val writesMask = Bool()
    val reverse = Bool()
    val minmax = Bool()
    val unsigned = Bool()
    val maximum = Bool()
    val scalarInfo = new FrontendRegisterInfo
}

object VectorDecoder {
    def recognized(inst: UInt): Bool = inst(6, 0) === "h57".U ||
        (inst(6, 0) === "h07".U || inst(6, 0) === "h27".U) && inst(14, 12) =/= 2.U

    def scalarOperands(inst: UInt): FrontendRegisterInfo = {
        val info = WireDefault(0.U.asTypeOf(new FrontendRegisterInfo))
        val form = inst(14, 12)
        val arithmetic = inst(6, 0) === "h57".U && form =/= 7.U
        val configuration = inst(6, 0) === "h57".U && form === 7.U
        val memory = inst(6, 0) =/= "h57".U && recognized(inst)
        val scalarMove = arithmetic && inst(31, 26) === 0x10.U && inst(25) &&
            inst(19, 15) === 0.U && (form === 1.U || form === 2.U)
        info.src(0).index := inst(19, 15)
        info.src(0).isFp := arithmetic && form === 5.U
        info.src(0).valid := memory || arithmetic && (form === 4.U || form === 5.U || form === 6.U) ||
            configuration && inst(31, 30) =/= 3.U
        info.src(1).index := inst(24, 20)
        info.src(1).valid := configuration && inst(31, 25) === "h40".U ||
            memory && inst(27, 26) === 2.U
        info.dest.index := inst(11, 7)
        val maskScalar = arithmetic && form === 2.U && inst(31, 26) === 0x10.U &&
            (inst(19, 15) === 16.U || inst(19, 15) === 17.U)
        info.dest.valid := configuration || scalarMove || maskScalar
        info.dest.isFp := scalarMove && form === 1.U
        for (source <- info.src) when(!source.isFp && source.index === 0.U) { source.valid := false.B }
        when(!info.dest.isFp && info.dest.index === 0.U) { info.dest.valid := false.B }
        info
    }
}

/** RVV operand classification precedes the scalar RAT reads. Register group
  * widths are formed separately from the instruction's configuration snapshot.
  */
class VectorDecoder extends RawModule {
    val io = IO(new Bundle {
        val instruction = Input(UInt(32.W))
        val control = Output(new VectorDecodeControl)
    })
    val inst = io.instruction
    val opcode = inst(6, 0)
    val form = inst(14, 12)
    val funct = inst(31, 26)
    val configuration = opcode === "h57".U && form === 7.U
    val load = opcode === "h07".U && (form === 0.U || form >= 5.U)
    val store = opcode === "h27".U && (form === 0.U || form >= 5.U)
    val arithmetic = opcode === "h57".U && !configuration
    val control = WireDefault(0.U.asTypeOf(new VectorDecodeControl))
    control.recognized := arithmetic || configuration || load || store
    control.scalarInfo := VectorDecoder.scalarOperands(inst)
    control.scalar := form === 4.U || form === 5.U || form === 6.U
    control.immediate := form === 3.U
    control.floating := form === 1.U || form === 5.U
    control.readsVs2 := arithmetic
    control.readsVs1 := arithmetic && (form === 0.U || form === 1.U || form === 2.U)
    control.writesVector := arithmetic || load
    val aluRows = Seq(
        0x00 -> EXEOp.ADD, 0x02 -> EXEOp.SUB, 0x09 -> EXEOp.AND,
        0x0a -> EXEOp.OR, 0x0b -> EXEOp.XOR, 0x25 -> EXEOp.SLL,
        0x28 -> EXEOp.SRL, 0x29 -> EXEOp.SRA,
    )
    val aluHit = VecInit(aluRows.map { case (code, _) => funct === code.U })
    val integerForm = form === 0.U || form === 3.U || form === 4.U
    when(arithmetic && integerForm && aluHit.asUInt.orR &&
        !(funct === 2.U && form === 3.U)) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := Mux1H(aluHit, aluRows.map(_._2))
    }
    when(arithmetic && (form === 3.U || form === 4.U) && funct === 3.U) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := EXEOp.SUB
        control.reverse := true.B
    }
    when(arithmetic && (form === 0.U || form === 4.U) && funct >= 4.U && funct <= 7.U) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := Mux(funct(0), EXEOp.SLT, EXEOp.SLTU)
        control.minmax := true.B
        control.unsigned := !funct(0)
        control.maximum := funct(1)
    }
    when(arithmetic && integerForm && funct === 0x17.U && inst(25) && inst(24, 20) === 0.U) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := EXEOp.ADD
        control.readsVs2 := false.B
    }
    when(arithmetic && form === 3.U && funct === 0x27.U && inst(25) &&
        (inst(19, 15) === 0.U || inst(19, 15) === 1.U || inst(19, 15) === 3.U || inst(19, 15) === 7.U)) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := EXEOp.ADD
        control.immediate := false.B
        control.readsVs1 := false.B
    }
    when(arithmetic && form === 5.U && funct === 0x17.U && inst(25) && inst(24, 20) === 0.U) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := EXEOp.ADD
        control.readsVs2 := false.B
    }
    val scalarMove = arithmetic && funct === 0x10.U && inst(25) && inst(19, 15) === 0.U &&
        (form === 1.U || form === 2.U)
    when(scalarMove) {
        control.legal := true.B
        control.execution := VectorExecution.ALU.U
        control.operation := EXEOp.ADD
        control.readsVs1 := false.B
        control.writesVector := false.B
    }
    val multiplyRows = Seq(0x25 -> MultiplyOp.MUL, 0x27 -> MultiplyOp.MULH,
        0x26 -> MultiplyOp.MULHSU, 0x24 -> MultiplyOp.MULHU)
    val multiplyHit = VecInit(multiplyRows.map { case (code, _) => funct === code.U })
    when(arithmetic && (form === 2.U || form === 6.U) && multiplyHit.asUInt.orR) {
        control.legal := true.B
        control.execution := VectorExecution.Multiply.U
        control.operation := Mux1H(multiplyHit, multiplyRows.map(_._2)).pad(5)
    }
    val floatRows = Seq(0x00 -> MultiplyOp.FADD, 0x02 -> MultiplyOp.FSUB,
        0x28 -> MultiplyOp.FMADD, 0x2a -> MultiplyOp.FMSUB,
        0x29 -> MultiplyOp.FNMADD, 0x2b -> MultiplyOp.FNMSUB,
        0x24 -> MultiplyOp.FMUL, 0x2c -> MultiplyOp.FMADD,
        0x2e -> MultiplyOp.FMSUB, 0x2d -> MultiplyOp.FNMADD, 0x2f -> MultiplyOp.FNMSUB)
    val floatHit = VecInit(floatRows.map { case (code, _) => funct === code.U })
    when(arithmetic && control.floating && floatHit.asUInt.orR) {
        control.legal := true.B
        control.execution := VectorExecution.Multiply.U
        control.operation := Mux1H(floatHit, floatRows.map(_._2)).pad(5)
        control.readsDestination := funct >= 0x28.U
    }
    when(arithmetic && form === 5.U && funct === 0x27.U) {
        control.legal := true.B
        control.execution := VectorExecution.Multiply.U
        control.operation := MultiplyOp.FSUB
        control.reverse := true.B
    }
    val logicRows = Seq(0x04 -> ZirconConfig.FpMiscOp.FMIN, 0x06 -> ZirconConfig.FpMiscOp.FMAX,
        0x08 -> ZirconConfig.FpMiscOp.FSGNJ, 0x09 -> ZirconConfig.FpMiscOp.FSGNJN,
        0x0a -> ZirconConfig.FpMiscOp.FSGNJX)
    val logicHit = VecInit(logicRows.map { case (code, _) => funct === code.U })
    when(arithmetic && control.floating && logicHit.asUInt.orR) {
        control.legal := true.B
        control.execution := VectorExecution.FpLogic.U
        control.operation := Mux1H(logicHit, logicRows.map(_._2.U(5.W)))
    }
    when(arithmetic && form === 1.U && funct === 0x13.U && inst(19, 15) === 16.U) {
        control.legal := true.B
        control.execution := VectorExecution.FpLogic.U
        control.operation := ZirconConfig.FpMiscOp.FCLASS.U
        control.readsVs1 := false.B
    }
    def serial(operation: Int): Unit = {
        control.legal := true.B
        control.execution := VectorExecution.Serial.U
        control.operation := operation.U
    }
    when(arithmetic && (form === 2.U || form === 6.U) && funct >= 0x20.U && funct <= 0x23.U) {
        serial(VectorSerialOperation.Divide)
    }
    when(arithmetic && control.floating && (funct === 0x20.U || form === 5.U && funct === 0x21.U ||
        form === 1.U && funct === 0x13.U && inst(19, 15) === 0.U)) {
        serial(VectorSerialOperation.Divide)
        when(funct === 0x13.U) { control.readsVs1 := false.B }
    }
    when(arithmetic && form === 1.U && funct === 0x12.U &&
        (inst(19, 15) <= 3.U || inst(19, 15) === 6.U || inst(19, 15) === 7.U ||
            inst(19, 15) === 10.U || inst(19, 15) === 11.U ||
            inst(19, 15) === 16.U || inst(19, 15) === 17.U ||
            inst(19, 15) === 22.U || inst(19, 15) === 23.U)) {
        serial(VectorSerialOperation.Convert)
        control.readsVs1 := false.B
    }
    val integerCompare = funct >= 0x18.U && funct <= 0x1f.U &&
        !(form === 3.U && (funct === 0x1a.U || funct === 0x1b.U)) &&
        !(form === 0.U && funct >= 0x1e.U)
    when(arithmetic && integerForm && integerCompare) {
        serial(VectorSerialOperation.IntegerCompare)
        control.writesMask := true.B
    }
    val floatCompare = Seq(0x18, 0x19, 0x1b, 0x1c).map(funct === _.U).reduce(_ || _) ||
        form === 5.U && (funct === 0x1d.U || funct === 0x1f.U)
    when(arithmetic && control.floating && floatCompare) {
        serial(VectorSerialOperation.FloatCompare)
        control.writesMask := true.B
    }
    when(arithmetic && (integerForm || form === 5.U) && funct === 0x17.U && !inst(25)) {
        serial(VectorSerialOperation.Merge)
    }
    when(arithmetic && (form === 5.U || form === 6.U) && funct === 0x10.U && inst(25) &&
        inst(24, 20) === 0.U) {
        serial(VectorSerialOperation.Insert)
        control.readsVs2 := false.B
        control.readsDestination := true.B
    }
    when(arithmetic && integerForm && (funct === 0x10.U || funct === 0x11.U ||
        form =/= 3.U && (funct === 0x12.U || funct === 0x13.U))) {
        serial(VectorSerialOperation.Carry)
        control.operation := Mux(funct(0), VectorSerialOperation.MaskCarry.U, VectorSerialOperation.Carry.U)
        control.writesMask := funct(0)
        control.legal := funct(0) || !inst(25)
    }
    when(arithmetic && form === 2.U && funct >= 0x18.U && funct <= 0x1f.U && inst(25)) {
        serial(VectorSerialOperation.MaskLogic)
        control.writesMask := true.B
    }
    when(arithmetic && form === 2.U && funct === 0x10.U &&
        (inst(19, 15) === 16.U || inst(19, 15) === 17.U)) {
        serial(VectorSerialOperation.Population)
        control.operation := Mux(inst(15), VectorSerialOperation.First.U, VectorSerialOperation.Population.U)
        control.writesVector := false.B
        control.readsVs1 := false.B
    }
    when(arithmetic && form === 2.U && funct === 0x14.U &&
        (inst(19, 15) === 1.U || inst(19, 15) === 2.U || inst(19, 15) === 3.U ||
            inst(19, 15) === 16.U || inst(19, 15) === 17.U)) {
        serial(VectorSerialOperation.MaskPrefix)
        control.operation := Mux(inst(19), Mux(inst(15), VectorSerialOperation.Index.U,
            VectorSerialOperation.Iota.U), VectorSerialOperation.MaskPrefix.U)
        control.writesMask := !inst(19)
        control.readsVs1 := false.B
        control.readsVs2 := inst(19, 15) =/= 17.U
    }
    when(arithmetic && integerForm && (funct === 0x20.U || funct === 0x21.U ||
        form =/= 3.U && (funct === 0x22.U || funct === 0x23.U))) {
        serial(VectorSerialOperation.Saturate)
    }
    when(arithmetic && (form === 2.U || form === 6.U) && funct >= 8.U && funct <= 11.U) {
        serial(VectorSerialOperation.Average)
    }
    when(arithmetic && integerForm && (funct === 0x2a.U || funct === 0x2b.U)) {
        serial(VectorSerialOperation.RoundedShift)
    }
    when(arithmetic && form === 2.U && funct <= 7.U) {
        serial(VectorSerialOperation.IntegerReduction)
        control.readsDestination := true.B
    }
    when(arithmetic && form === 1.U && (funct === 1.U || funct === 3.U || funct === 5.U || funct === 7.U)) {
        serial(VectorSerialOperation.FloatReduction)
        control.readsDestination := true.B
    }
    when(arithmetic && (form === 2.U || form === 6.U) &&
        (funct === 0x29.U || funct === 0x2b.U || funct === 0x2d.U || funct === 0x2f.U)) {
        control.legal := true.B
        control.execution := VectorExecution.Multiply.U
        control.operation := Cat(1.U(1.W), 0.U(3.W), funct(1))
        control.readsDestination := true.B
    }
    when(arithmetic && ((funct === 0x0c.U && integerForm) ||
        funct === 0x0e.U && form === 0.U ||
        (funct === 0x0e.U || funct === 0x0f.U) &&
            (form === 3.U || form === 4.U || form === 5.U || form === 6.U))) {
        serial(VectorSerialOperation.Permute)
        control.readsDestination := true.B
        when(funct === 0x0e.U || funct === 0x0f.U) { control.readsVs1 := false.B }
        when(funct === 0x0e.U && form === 0.U) { control.readsVs1 := true.B }
    }
    when(arithmetic && (form === 2.U || form === 6.U) &&
        (funct >= 0x30.U && funct <= 0x37.U || funct === 0x38.U ||
            funct === 0x3a.U || funct === 0x3b.U || funct >= 0x3c.U)) {
        serial(VectorSerialOperation.Widen)
        control.readsDestination := funct >= 0x3c.U
    }
    when(arithmetic && integerForm && funct >= 0x2c.U && funct <= 0x2f.U) {
        serial(VectorSerialOperation.Narrow)
    }
    when(arithmetic && form === 2.U && funct === 0x12.U &&
        inst(19, 15) >= 2.U && inst(19, 15) <= 7.U) {
        serial(VectorSerialOperation.Extend)
        control.readsVs1 := false.B
    }
    when(arithmetic && (form === 0.U || form === 4.U) && funct === 0x27.U) {
        serial(VectorSerialOperation.FractionalMultiply)
    }
    when(arithmetic && form === 0.U && (funct === 0x30.U || funct === 0x31.U)) {
        serial(VectorSerialOperation.WidenReduction)
        control.readsDestination := true.B
    }
    when(arithmetic && form === 1.U && funct === 0x13.U &&
        (inst(19, 15) === 4.U || inst(19, 15) === 5.U)) {
        serial(VectorSerialOperation.FloatEstimate)
        control.readsVs1 := false.B
    }
    when(arithmetic && form === 2.U && funct === 0x17.U && inst(25)) {
        serial(VectorSerialOperation.Compress)
        control.readsDestination := true.B
    }
    val vsetvli = !inst(31)
    val vsetivli = inst(31, 30) === 3.U
    val vsetvl = inst(31, 25) === "h40".U
    when(configuration) {
        control.legal := vsetvli || vsetivli || vsetvl
        control.execution := VectorExecution.Configuration.U
        control.writesVector := false.B
    }
    when(load || store) {
        val whole = inst(27, 26) === 0.U && inst(24, 20) === 8.U
        val maskMemory = inst(27, 26) === 0.U && inst(24, 20) === 11.U
        val wholeCount = inst(31, 29) +& 1.U
        val legalWholeCount = wholeCount === 1.U || wholeCount === 2.U ||
            wholeCount === 4.U || wholeCount === 8.U
        // Whole-register transfers use NF, independently of LMUL, SEW and VL.
        control.legal := !inst(28) && form =/= 7.U &&
            (inst(27, 26) =/= 0.U || inst(24, 20) === 0.U || load && inst(24, 20) === 16.U)
        when(whole) { control.legal := !inst(28) && inst(25) && legalWholeCount &&
            (load && form =/= 7.U || store && form === 0.U) }
        when(maskMemory) { control.legal := !inst(28) && inst(25) &&
            !inst(31, 29).orR && form === 0.U }
        control.execution := Mux(load, VectorExecution.Load.U, VectorExecution.Store.U)
        control.scalar := true.B
        control.readsVs1 := inst(26)
        control.readsVs2 := store
    }
    io.control := control
}

class VectorRegisterGroupInfo extends Bundle {
    val sourceBase = Vec(3, UInt(5.W))
    val sourceCount = Vec(3, UInt(4.W))
    val destinationBase = UInt(5.W)
    val destinationCount = UInt(4.W)
    val mask = Bool()
    val legal = Bool()
}

/** Parallel operand-shape decode. Register allocation depends on these bits,
  * not on an execution/operation encoding followed by another decoder.
  */
class VectorGrouping(inst: UInt) {
    private val opcode = inst(6, 0)
    private val form = inst(14, 12)
    private val funct = inst(31, 26)
    private val arithmetic = opcode === "h57".U && form =/= 7.U
    private val integerForm = form === 0.U || form === 3.U || form === 4.U
    private val multiplyForm = form === 2.U || form === 6.U
    private val floatingForm = form === 1.U || form === 5.U
    val memory = (opcode === "h07".U || opcode === "h27".U) &&
        (form === 0.U || form >= 5.U)
    val widening = arithmetic && (multiplyForm &&
        (funct >= 0x30.U && funct <= 0x37.U || funct === 0x38.U ||
            funct === 0x3a.U || funct === 0x3b.U || funct >= 0x3c.U) ||
        form === 0.U && (funct === 0x30.U || funct === 0x31.U))
    val narrowing = arithmetic && integerForm && funct >= 0x2c.U && funct <= 0x2f.U
    val extending = arithmetic && form === 2.U && funct === 0x12.U &&
        inst(19, 15) >= 2.U && inst(19, 15) <= 7.U
    val conversion = arithmetic && form === 1.U && funct === 0x12.U
    val wideConversion = conversion && (inst(19, 15) === 10.U || inst(19, 15) === 11.U)
    val narrowConversion = conversion && inst(19)
    val wideInput = widening && funct >= 0x34.U && funct <= 0x37.U
    val reduction = arithmetic && (form === 2.U && funct <= 7.U ||
        form === 1.U && (funct === 1.U || funct === 3.U || funct === 5.U || funct === 7.U) ||
        form === 0.U && (funct === 0x30.U || funct === 0x31.U))
    val insert = arithmetic && (form === 5.U || form === 6.U) && funct === 0x10.U &&
        inst(25) && inst(24, 20) === 0.U
    private val integerCompare = integerForm && funct >= 0x18.U && funct <= 0x1f.U &&
        !(form === 3.U && (funct === 0x1a.U || funct === 0x1b.U)) &&
        !(form === 0.U && funct >= 0x1e.U)
    private val floatCompare = floatingForm &&
        (Seq(0x18, 0x19, 0x1b, 0x1c).map(funct === _.U).reduce(_ || _) ||
            form === 5.U && (funct === 0x1d.U || funct === 0x1f.U))
    private val maskCarry = integerForm &&
        (funct === 0x11.U || form =/= 3.U && funct === 0x13.U)
    val maskLogic = arithmetic && form === 2.U && funct >= 0x18.U && funct <= 0x1f.U && inst(25)
    val maskPrefix = arithmetic && form === 2.U && funct === 0x14.U &&
        (inst(19, 15) === 1.U || inst(19, 15) === 2.U || inst(19, 15) === 3.U)
    val iota = arithmetic && form === 2.U && funct === 0x14.U && inst(19, 15) === 16.U
    val writesMask = arithmetic && (integerCompare || floatCompare || maskCarry) || maskLogic || maskPrefix
    val singleDestination = writesMask || reduction || insert
    private val scalarMove = arithmetic && funct === 0x10.U && inst(25) && inst(19, 15) === 0.U &&
        (form === 1.U || form === 2.U)
    private val population = arithmetic && form === 2.U && funct === 0x10.U &&
        (inst(19, 15) === 16.U || inst(19, 15) === 17.U)
    val writesVector = arithmetic && !scalarMove && !population || memory && opcode === "h07".U
}

/** Shared count decode for allocation preview and the complete group payload. */
class VectorGroupCounts(inst: UInt, vtype: UInt) {
    val shape = new VectorGrouping(inst)
    val lmul = vtype(2, 0)
    val sew = vtype(5, 3)
    val memory = shape.memory
    val whole = memory && inst(27, 26) === 0.U && inst(24, 20) === 8.U
    val maskMemory = memory && inst(27, 26) === 0.U && inst(24, 20) === 11.U
    val wholeMove = !memory && inst(31, 26) === 0x27.U && inst(14, 12) === 3.U
    val gather16 = !memory && inst(31, 26) === 0x0e.U && inst(14, 12) === 0.U
    val indexed = memory && inst(26)
    val sourceBases = VecInit(Seq(Mux(memory, inst(11, 7), inst(24, 20)),
        Mux(indexed, inst(24, 20), inst(19, 15)), inst(11, 7)))
    val eew = Mux(inst(14, 12) === 0.U, 0.U, inst(13, 12))
    val emul = lmul.asSInt.pad(5) + eew.zext.pad(5) - sew.zext.pad(5)
    // Legal group counts are powers of two. Decode constant exponent offsets
    // in parallel; keep signed add/sub and wide variable shifts off RAT admission.
    def countAt(delta: Int): UInt = MuxLookup(lmul, 0.U(4.W))(
        Seq(0, 1, 2, 3, 5, 6, 7).map { encoding =>
            val signed = if (encoding < 4) encoding else encoding - 8
            val adjusted = signed + delta
            encoding.U -> (if (adjusted <= 0) 1 else if (adjusted <= 3) 1 << adjusted else 0).U(4.W)
        })
    val normalCount = countAt(0)
    val memoryCount = Mux1H((for (dataWidth <- 0 to 2; elementWidth <- 0 to 2) yield
        (sew === dataWidth.U && eew === elementWidth.U) -> countAt(elementWidth - dataWidth)))
    val wideCount = countAt(1)
    val destinationArithmeticCount = Mux(shape.widening || shape.wideConversion, wideCount, normalCount)
    val sourceCount = Mux(shape.narrowing || shape.wideInput || shape.narrowConversion, wideCount,
        Mux(shape.extending, Mux(inst(17, 16) === 2.U, countAt(-2), countAt(-1)), normalCount))
    val count = Mux(indexed, normalCount, Mux(memory, memoryCount, destinationArithmeticCount))
    val segmentCount = inst(31, 29) +& 1.U
    val totalCount = Mux(wholeMove, inst(19, 15) +& 1.U, Mux(whole, segmentCount, Mux(maskMemory, 1.U,
        Mux(memory, MuxLookup(count, 0.U(7.W))(Seq(
            1.U -> segmentCount.pad(7), 2.U -> Cat(segmentCount, 0.U(1.W)).pad(7),
            4.U -> Cat(segmentCount, 0.U(2.W)).pad(7), 8.U -> Cat(segmentCount, 0.U(3.W)))), count))))
    val reduction = shape.reduction
    val singleDestination = shape.singleDestination
    val destinationCount = Mux(singleDestination, 1.U, totalCount)
    val encodedAllocationCount = Mux(shape.writesVector, destinationCount, 0.U)(3, 0)
    // These disjoint classes are shared by count and capacity lookup. Clocked
    // consumers assert the partition; this helper also serves RawModules.
    val allocationClasses = {
        val grouped = shape.writesVector && !singleDestination
        val ordinary = grouped && !wholeMove && !whole && !maskMemory
        val wide = shape.widening || shape.wideConversion
        Seq(
            !shape.writesVector,
            shape.writesVector && singleDestination || grouped && maskMemory,
            grouped && wholeMove,
            grouped && whole,
            ordinary && memory && indexed,
            ordinary && memory && !indexed,
            ordinary && !memory && wide,
            ordinary && !memory && !wide,
        )
    }
    // Use the same disjoint class lookup for the stored count and capacity.
    // Allocation consumers do not re-encode the complete group-size mux chain.
    val allocationCount = selectAllocation((0 until 16).map(_.U(4.W)))

    /** Select a bank mask directly from constant group sizes. Admission
      * need not encode a count, then decode it in eight bank comparisons.
      */
    def selectAllocation(values: Seq[UInt]): UInt = {
        require(values.size == 16)
        def at(delta: Int, multiplier: Int = 1): UInt = MuxLookup(lmul, values(0))(
            Seq(0, 1, 2, 3, 5, 6, 7).map { encoding =>
                val adjusted = (if (encoding < 4) encoding else encoding - 8) + delta
                val size = if (adjusted <= 0) 1 else if (adjusted <= 3) 1 << adjusted else 0
                encoding.U -> values((size * multiplier) & 15)
            })
        def segments(delta: Int): UInt = MuxLookup(inst(31, 29), values(0))(
            (0 until 8).map(fields => fields.U -> at(delta, fields + 1)))
        val memoryValue = Mux1H((for (dataWidth <- 0 to 2; elementWidth <- 0 to 2) yield
            (sew === dataWidth.U && eew === elementWidth.U) -> segments(elementWidth - dataWidth)))
        val wholeValue = MuxLookup(inst(31, 29), values(0))(
            (0 until 8).map(fields => fields.U -> values(fields + 1)))
        val moveValue = MuxLookup(inst(19, 15), values(0))(
            (0 until 32).map(registers => registers.U -> values((registers + 1) & 15)))
        Mux1H(allocationClasses, Seq(values(0), values(1), moveValue, wholeValue,
            segments(0), memoryValue, at(1), at(0)))
    }

    def allocationBanks: UInt = {
        val base = (0 until 8).map(index => inst(9, 7) === index.U)
        val candidates = (0 until 16).map { count =>
            if (count == 0) 0.U(8.W)
            else Mux1H(base, (0 until 8).map { first =>
                (0 until math.min(count, 8)).foldLeft(BigInt(0)) { (mask, offset) =>
                    mask.setBit((first + offset) & 7)
                }.U(8.W)
            })
        }
        selectAllocation(candidates)
    }
    def allocationBlocked(available: UInt): Bool = {
        require(available.getWidth == 8)
        (allocationBanks & ~available).orR
    }
}

class VectorRegisterGroups extends RawModule {
    val io = IO(new Bundle {
        val instruction = Input(UInt(32.W))
        val control = Input(new VectorDecodeControl)
        val vtype = Input(UInt(32.W))
        val vl = Input(UInt(32.W))
        val vstart = Input(UInt(32.W))
        val groups = Output(new VectorRegisterGroupInfo)
    })
    val inst = io.instruction
    val counts = new VectorGroupCounts(inst, io.vtype)
    import counts._
    val widths = VectorElementWidths(inst, io.control, sew)
    val destinationExponent = lmul.asSInt.pad(5) + widths._1.zext.pad(5) - sew.zext.pad(5)
    val sourceExponent = lmul.asSInt.pad(5) + widths._2.zext.pad(5) - sew.zext.pad(5)
    val exponent = Mux(indexed, lmul.asSInt.pad(5), Mux(memory, emul, destinationExponent))
    val serial = io.control.execution === VectorExecution.Serial.U
    val maskSource = shape.maskLogic || shape.maskPrefix || shape.iota
    val groups = WireDefault(0.U.asTypeOf(new VectorRegisterGroupInfo))
    groups.sourceBase := sourceBases
    groups.sourceCount(0) := Mux(io.control.readsVs2, Mux(memory || wholeMove, totalCount, sourceCount), 0.U)
    when(!io.control.writesVector && !memory) {
        groups.sourceCount(0) := Mux(io.control.readsVs2, 1.U, 0.U)
    }
    groups.sourceCount(1) := Mux(io.control.readsVs1, normalCount, 0.U)
    when(indexed) { groups.sourceCount(1) := memoryCount }
    val indexExponent = lmul.asSInt.pad(5) + 1.S(5.W) - sew.zext.pad(5)
    when(gather16) { groups.sourceCount(1) := Mux(sew === 0.U, wideCount,
        Mux(sew === 1.U, normalCount, countAt(-1))) }
    when(reduction) { groups.sourceCount(1) := 1.U }
    when(maskSource) {
        groups.sourceCount(0) := Mux(io.control.readsVs2, 1.U, 0.U)
        groups.sourceCount(1) := Mux(io.control.readsVs1, 1.U, 0.U)
    }
    when(serial && io.control.operation === VectorSerialOperation.Compress.U) {
        groups.sourceCount(1) := 1.U
    }
    val needOldDestination = io.control.writesVector &&
        (io.control.writesMask || io.control.readsDestination || !io.vtype(6) || !inst(25) && !io.vtype(7) ||
            io.vl === 0.U || io.vstart.orR)
    groups.sourceCount(2) := Mux(needOldDestination, destinationCount, 0.U)
    groups.destinationBase := inst(11, 7)
    groups.destinationCount := allocationCount
    groups.mask := !inst(25) && !whole && !maskMemory && !wholeMove &&
        io.control.execution =/= VectorExecution.Configuration.U
    val alignment = Mux(whole || maskMemory || wholeMove, totalCount, count) - 1.U
    val alignedDestination = singleDestination || (groups.destinationBase & alignment) === 0.U
    val alignedSources = groups.sourceBase.zip(groups.sourceCount).zipWithIndex.map { case ((base, size), port) =>
        val sourceAlignment = if (port == 1) size - 1.U else Mux(memory && !whole && !maskMemory,
            count - 1.U, size - 1.U)
        size === 0.U || (base & sourceAlignment) === 0.U && base +& size <= 32.U
    }.reduce(_ && _)
    val configuration = io.control.execution === VectorExecution.Configuration.U
    val crossConversion = serial && io.control.operation === VectorSerialOperation.Convert.U &&
        (inst(19) || inst(18))
    groups.legal := configuration || !io.vtype(31) &&
        (!io.control.floating || memory || Mux(crossConversion, sew === 1.U, sew === 2.U)) &&
        (singleDestination || exponent >= (-3).S && exponent <= 3.S) &&
        sourceExponent >= (-3).S && sourceExponent <= 3.S &&
        widths._1 <= 2.U && widths._2 <= 2.U &&
        (!indexed || emul >= (-3).S && emul <= 3.S) &&
        (!gather16 || indexExponent >= (-3).S && indexExponent <= 3.S) &&
        (singleDestination || totalCount <= 8.U) && alignedSources &&
        (!io.control.writesVector || alignedDestination && groups.destinationBase +& destinationCount <= 32.U &&
            (!groups.mask || io.control.writesMask || groups.destinationBase =/= 0.U))
    when(whole || maskMemory || wholeMove) {
        groups.legal := alignedSources && alignedDestination && groups.destinationBase +& totalCount <= 32.U
    }
    io.groups := groups
}
