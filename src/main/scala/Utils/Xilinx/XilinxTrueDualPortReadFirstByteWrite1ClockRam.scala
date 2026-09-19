// Vivado byte-write true dual-port RAM binding.
import chisel3._
import chisel3.util._

class XilinxTrueDualPortReadFirstByteWrite1ClockRamIO(columns: Int, columnWidth: Int, depth: Int) extends Bundle {
    val addra = Input(UInt(log2Ceil(depth).W))
    val addrb = Input(UInt(log2Ceil(depth).W))
    val dina = Input(UInt((columns * columnWidth).W))
    val dinb = Input(UInt((columns * columnWidth).W))
    val clka = Input(Clock())
    val wea = Input(UInt(columns.W))
    val web = Input(UInt(columns.W))
    val ena = Input(Bool())
    val enb = Input(Bool())
    val douta = Output(UInt((columns * columnWidth).W))
    val doutb = Output(UInt((columns * columnWidth).W))
}

/** Registered-address, read-first dual-port RAM with per-byte write enables. */
class XilinxTrueDualPortReadFirstByteWrite1ClockRam(NBCOL: Int, COLWIDTH: Int, RAMDEPTH: Int)
    extends ExtModule(Map("NBCOL" -> NBCOL, "COLWIDTH" -> COLWIDTH, "RAMDEPTH" -> RAMDEPTH)) {
    require(NBCOL > 0 && COLWIDTH > 0 && RAMDEPTH >= 2 && isPow2(RAMDEPTH))

    val io = FlatIO(new XilinxTrueDualPortReadFirstByteWrite1ClockRamIO(NBCOL, COLWIDTH, RAMDEPTH))

    if (!sys.env.get("ZIRCON_INCLUDE_VIVADO_RAM_SOURCE").contains("false")) {
        addResource("/Xilinx/XilinxTrueDualPortReadFirstByteWrite1ClockRam.sv")
    }
}
