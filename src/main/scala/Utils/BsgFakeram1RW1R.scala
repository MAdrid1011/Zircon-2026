import chisel3._
import chisel3.util._

class BsgFakeram1RW1RIO(width: Int, depth: Int = 16) extends Bundle {
    val clock = Input(Clock())
    val csb0 = Input(Bool())
    val csb1 = Input(Bool())
    val web0 = Input(Bool())
    val addr0 = Input(UInt(log2Ceil(depth).W))
    val addr1 = Input(UInt(log2Ceil(depth).W))
    val din0 = Input(UInt(width.W))
    val wmask0 = if (width == 32) Some(Input(UInt(4.W))) else None
    val dout0 = Output(UInt(width.W))
    val dout1 = Output(UInt(width.W))
}

/** Cycle-accurate model for the BSG-derived 1RW+1R logic-only SRAM interface. */
class BsgFakeram1RW1R(width: Int, depth: Int = 16) extends RawModule {
    require(width == 25 || width == 32)
    require(depth >= 2 && isPow2(depth))
    override def desiredName = if (depth == 16) s"BsgFakeram1RW1R_$width" else s"BsgFakeram1RW1R_${depth}_$width"
    val io = FlatIO(new BsgFakeram1RW1RIO(width, depth))

    private val lanes = if (width == 32) 4 else 1
    private val laneBits = if (width == 32) 8 else 25
    withClockAndReset(io.clock, false.B) {
        val memory = Reg(Vec(depth, Vec(lanes, UInt(laneBits.W))))
        val readAddress0 = RegEnable(io.addr0, !io.csb0)
        val readAddress1 = RegEnable(io.addr1, !io.csb1)
        for (lane <- 0 until lanes) {
            val writeLane = if (width == 32) io.wmask0.get(lane) else true.B
            when(!io.csb0 && !io.web0 && writeLane) {
                memory(io.addr0)(lane) := io.din0((lane + 1) * laneBits - 1, lane * laneBits)
            }
        }
        io.dout0 := memory(readAddress0).asUInt
        io.dout1 := memory(readAddress1).asUInt
    }
}

/** Nangate45 logic-only binding. The EDA flow supplies the BSG-derived timing model. */
class BsgFakeram1RW1RMacro(width: Int, depth: Int = 16) extends ExtModule {
    require(width == 25 || width == 32)
    require(depth >= 2 && isPow2(depth))
    override def desiredName = if (depth == 16) s"BsgFakeram1RW1R_$width" else s"BsgFakeram1RW1R_${depth}_$width"
    val io = FlatIO(new BsgFakeram1RW1RIO(width, depth))
}
