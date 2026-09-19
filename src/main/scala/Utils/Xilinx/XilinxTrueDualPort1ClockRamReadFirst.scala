// Vivado true dual-port block RAM binding.
import chisel3._
import chisel3.util._

class XilinxTrueDualPortReadFirst1ClockRamIO(width: Int, depth: Int) extends Bundle {
    val addra = Input(UInt(log2Ceil(depth).W))
    val addrb = Input(UInt(log2Ceil(depth).W))
    val dina = Input(UInt(width.W))
    val dinb = Input(UInt(width.W))
    val clka = Input(Clock())
    val wea = Input(Bool())
    val web = Input(Bool())
    val ena = Input(Bool())
    val enb = Input(Bool())
    val douta = Output(UInt(width.W))
    val doutb = Output(UInt(width.W))
}

/** Registered-address, read-first true dual-port RAM with one shared clock. */
class XilinxTrueDualPortReadFirst1ClockRam(RAMWIDTH: Int, RAMDEPTH: Int)
    extends ExtModule(Map("RAMWIDTH" -> RAMWIDTH, "RAMDEPTH" -> RAMDEPTH)) {
    require(RAMWIDTH > 0 && RAMDEPTH >= 2 && isPow2(RAMDEPTH))

    val io = FlatIO(new XilinxTrueDualPortReadFirst1ClockRamIO(RAMWIDTH, RAMDEPTH))

    if (!sys.env.get("ZIRCON_INCLUDE_VIVADO_RAM_SOURCE").contains("false")) {
        addResource("/Xilinx/XilinxTrueDualPortReadFirst1ClockRam.sv")
    }
}
