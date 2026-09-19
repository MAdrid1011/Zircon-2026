import chisel3._
import chisel3.util._

class XilinxSinglePortRamReadFirstIO(width: Int, depth: Int) extends Bundle {
    val addra = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val dina = Input(UInt(width.W))
    val clka = Input(Clock())
    val wea = Input(Bool())
    val ena = Input(Bool())
    val douta = Output(UInt(width.W))
}

/** Registered-address, read-first single-port RAM used by the ICache. */
class XilinxSinglePortRamReadFirst(RAMWIDTH: Int, RAMDEPTH: Int)
    extends ExtModule(Map("RAMWIDTH" -> RAMWIDTH, "RAMDEPTH" -> RAMDEPTH)) {
    require(RAMWIDTH > 0 && RAMDEPTH >= 1 && isPow2(RAMDEPTH))
    val io = FlatIO(new XilinxSinglePortRamReadFirstIO(RAMWIDTH, RAMDEPTH))

    // ChiselSim needs the Verilog body in its temporary source tree. The full-core
    // RTL path copies the same source explicitly, avoiding CIRCT's inline annotation.
    if (!sys.env.get("ZIRCON_INCLUDE_VIVADO_RAM_SOURCE").contains("false")) {
        addResource("/Xilinx/XilinxSinglePortRamReadFirst.sv")
    }
}
