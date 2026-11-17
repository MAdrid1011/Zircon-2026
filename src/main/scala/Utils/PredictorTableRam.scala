import chisel3._
import chisel3.util._

class PredictorBsgFakeramIO(depth: Int, width: Int) extends Bundle {
    private val addressWidth = log2Ceil(depth)
    val clock = Input(Clock())
    val csb0 = Input(Bool())
    val csb1 = Input(Bool())
    val web0 = Input(Bool())
    val addr0 = Input(UInt(addressWidth.W))
    val addr1 = Input(UInt(addressWidth.W))
    val din0 = Input(UInt(width.W))
    val dout0 = Output(UInt(width.W))
    val dout1 = Output(UInt(width.W))
}

/** Cycle-accurate model for exact-depth predictor SRAMs. */
class PredictorBsgFakeram(depth: Int, width: Int) extends RawModule {
    require(depth >= 2 && isPow2(depth) && width > 0)
    override def desiredName = s"PredictorBsgFakeram_${depth}_$width"
    val io = FlatIO(new PredictorBsgFakeramIO(depth, width))

    withClockAndReset(io.clock, false.B) {
        val memory = Reg(Vec(depth, UInt(width.W)))
        val readAddress0 = RegEnable(io.addr0, !io.csb0)
        val readAddress1 = RegEnable(io.addr1, !io.csb1)
        when(!io.csb0 && !io.web0) { memory(io.addr0) := io.din0 }
        io.dout0 := memory(readAddress0)
        io.dout1 := memory(readAddress1)
    }
}

/** BSG Fakeram binding supplied by the Nangate45 logic-only synthesis flow. */
class PredictorBsgFakeramMacro(depth: Int, width: Int) extends ExtModule {
    require(depth >= 2 && isPow2(depth) && width > 0)
    override def desiredName = s"PredictorBsgFakeram_${depth}_$width"
    val io = FlatIO(new PredictorBsgFakeramIO(depth, width))
}

class PredictorTableRamIO(depth: Int, width: Int) extends Bundle {
    val predictEnable = Input(Bool())
    val predictAddress = Input(UInt(log2Ceil(depth).W))
    val predictData = Output(UInt(width.W))
    val updateReadEnable = Input(Bool())
    val updateReadAddress = Input(UInt(log2Ceil(depth).W))
    val updateReadData = Output(UInt(width.W))
    val updateWriteEnable = Input(Bool())
    val updateWriteAddress = Input(UInt(log2Ceil(depth).W))
    val updateWriteData = Input(UInt(width.W))
}

/** Two replicated 1RW+1R memories sustain one prediction read and one pipelined update per cycle. */
class PredictorTableRam(
    depth: Int,
    width: Int,
    backend: DualPortRamBackend = DualPortRamBackend.Vivado,
) extends Module {
    require(depth >= 2 && isPow2(depth) && width > 0)
    val io = IO(new PredictorTableRamIO(depth, width))

    val effectiveBackend = if (backend == DualPortRamBackend.Vivado &&
        !sys.env.get("ZIRCON_USE_EXTERNAL_VIVADO_RAM").contains("true")) {
        DualPortRamBackend.Registers
    } else {
        backend
    }

    val rawPredictData = Wire(UInt(width.W))
    val rawUpdateData = Wire(UInt(width.W))

    effectiveBackend match {
        case DualPortRamBackend.BSG =>
            val useMacro = sys.env.get("ZIRCON_USE_EXTERNAL_BSG_RAM").contains("true")

            def buildCopy(readEnable: Bool, readAddress: UInt): UInt = {
                val ram = if (useMacro) Module(new PredictorBsgFakeramMacro(depth, width)).io
                    else Module(new PredictorBsgFakeram(depth, width)).io
                ram.clock := clock
                ram.csb0 := !io.updateWriteEnable
                ram.csb1 := !readEnable
                ram.web0 := !io.updateWriteEnable
                ram.addr0 := io.updateWriteAddress
                ram.addr1 := readAddress
                ram.din0 := io.updateWriteData
                ram.dout1
            }

            rawPredictData := buildCopy(io.predictEnable, io.predictAddress)
            rawUpdateData := buildCopy(io.updateReadEnable, io.updateReadAddress)

        case DualPortRamBackend.Vivado =>
            def buildCopy(readEnable: Bool, readAddress: UInt): UInt = {
                val ram = Module(new XilinxTrueDualPortReadFirst1ClockRam(width, depth))
                ram.io.addra := io.updateWriteAddress
                ram.io.addrb := readAddress
                ram.io.dina := io.updateWriteData
                ram.io.dinb := 0.U
                ram.io.clka := clock
                ram.io.wea := io.updateWriteEnable
                ram.io.web := false.B
                ram.io.ena := io.updateWriteEnable
                ram.io.enb := readEnable
                val unusedWriteData = Wire(UInt(width.W))
                unusedWriteData := ram.io.douta
                dontTouch(unusedWriteData)
                ram.io.doutb
            }
            rawPredictData := buildCopy(io.predictEnable, io.predictAddress)
            rawUpdateData := buildCopy(io.updateReadEnable, io.updateReadAddress)

        case DualPortRamBackend.Registers =>
            def buildCopy(readEnable: Bool, readAddress: UInt): UInt = {
                val ram = Module(new ZirconSram1W1R(depth, width))
                ram.io.clock := clock
                ram.io.writeEnable := io.updateWriteEnable
                ram.io.writeAddress := io.updateWriteAddress
                ram.io.writeData := io.updateWriteData
                ram.io.readEnable := readEnable
                ram.io.readAddress := readAddress
                ram.io.readData
            }
            rawPredictData := buildCopy(io.predictEnable, io.predictAddress)
            rawUpdateData := buildCopy(io.updateReadEnable, io.updateReadAddress)
    }

    // Preserve the logical register backend's write-through behavior while the
    // physical SRAM output is holding the most recently enabled read address.
    def writeThrough(readEnable: Bool, readAddress: UInt, raw: UInt): UInt = {
        val heldAddress = RegEnable(readAddress, readEnable)
        val heldValid = RegInit(false.B)
        val forwarded = RegInit(false.B)
        val forwardedData = Reg(UInt(width.W))
        val readCollision = readEnable && io.updateWriteEnable &&
            readAddress === io.updateWriteAddress
        val heldCollision = !readEnable && heldValid && io.updateWriteEnable &&
            heldAddress === io.updateWriteAddress
        when(readEnable) {
            heldValid := true.B
            forwarded := readCollision
            when(readCollision) {
                forwardedData := io.updateWriteData
            }
        }.elsewhen(heldCollision) {
            forwarded := true.B
            forwardedData := io.updateWriteData
        }
        Mux(forwarded, forwardedData, raw)
    }

    def collisionForward(readEnable: Bool, readAddress: UInt, raw: UInt): UInt = {
        val collision = readEnable && io.updateWriteEnable && readAddress === io.updateWriteAddress
        val valid = RegNext(collision, false.B)
        val data = RegEnable(io.updateWriteData, collision)
        Mux(valid, data, raw)
    }

    if (effectiveBackend == DualPortRamBackend.Registers) {
        io.predictData := writeThrough(io.predictEnable, io.predictAddress, rawPredictData)
        io.updateReadData := writeThrough(io.updateReadEnable, io.updateReadAddress, rawUpdateData)
    } else {
        io.predictData := rawPredictData
        io.updateReadData := collisionForward(io.updateReadEnable, io.updateReadAddress, rawUpdateData)
    }
}
