import chisel3._
import chisel3.util._

sealed trait DualPortRamBackend
object DualPortRamBackend {
    case object Vivado extends DualPortRamBackend
    case object Registers extends DualPortRamBackend
    case object BSG extends DualPortRamBackend

    def parse(name: String): DualPortRamBackend = name match {
        case "vivado" => Vivado
        case "registers" => Registers
        case "bsg" => BSG
        case _ => throw new IllegalArgumentException(s"Unknown dual-port RAM backend: $name")
    }
}

/** Both ports share one clock and independently accept one address per enabled edge. */
class DualPortMaskedRamIO(depth: Int, lanes: Int, laneBits: Int) extends Bundle {
    val addra = Input(UInt(log2Ceil(depth).W))
    val addrb = Input(UInt(log2Ceil(depth).W))
    val dina = Input(UInt((lanes * laneBits).W))
    val dinb = Input(UInt((lanes * laneBits).W))
    val clka = Input(Clock())
    val wea = Input(UInt(lanes.W))
    val web = Input(UInt(lanes.W))
    val ena = Input(Bool())
    val enb = Input(Bool())
    val douta = Output(UInt((lanes * laneBits).W))
    val doutb = Output(UInt((lanes * laneBits).W))
}

/** One selected port supports masked writes; the other port is read-only.
  * All backends preserve the registered-address contract and live held outputs.
  * Reads of uninitialized register storage are unspecified. Consumers use separate valid bits.
  */
class DualPortMaskedRam(
    depth: Int,
    lanes: Int,
    laneBits: Int,
    backend: DualPortRamBackend,
    readWritePort: Int = 0
) extends RawModule {
    require(depth >= 2 && isPow2(depth) && lanes > 0 && laneBits > 0)
    require(readWritePort == 0 || readWritePort == 1)
    val io = IO(new DualPortMaskedRamIO(depth, lanes, laneBits))

    val writeMask = Seq(io.wea, io.web)
    withClockAndReset(io.clka, false.B) {
        assert(!writeMask(1 - readWritePort).orR, "RAM read-only port cannot write")
    }

    val effectiveBackend = if (backend == DualPortRamBackend.Vivado &&
        !sys.env.get("ZIRCON_USE_EXTERNAL_VIVADO_RAM").contains("true")) {
        DualPortRamBackend.Registers
    } else {
        backend
    }

    effectiveBackend match {
        case DualPortRamBackend.BSG =>
            require(lanes == 1 && laneBits <= 25 || lanes % 4 == 0 && laneBits == 8)
            val address = Seq(io.addra, io.addrb)
            val enable = Seq(io.ena, io.enb)
            val mask = Seq(io.wea, io.web)
            val data = Seq(io.dina, io.dinb)
            val width = if (lanes == 1) 25 else 32
            val logicalWidth = lanes * laneBits
            val macroWidth = ((logicalWidth + width - 1) / width) * width
            val paddedData = data.map(_.pad(macroWidth))
            val useMacro = sys.env.get("ZIRCON_USE_EXTERNAL_BSG_RAM").contains("true")
            val ram = Seq.fill(macroWidth / width) {
                if (useMacro) Module(new BsgFakeram1RW1RMacro(width, depth)).io else Module(new BsgFakeram1RW1R(width, depth)).io
            }
            for ((r, i) <- ram.zipWithIndex) {
                r.clock := io.clka
                r.csb0 := !enable(readWritePort)
                r.csb1 := !enable(1 - readWritePort)
                r.web0 := !mask(readWritePort).orR
                r.addr0 := address(readWritePort)
                r.addr1 := address(1 - readWritePort)
                r.din0 := paddedData(readWritePort)((i + 1) * width - 1, i * width)
                if (width == 32) {
                    r.wmask0.get := mask(readWritePort)(i * 4 + 3, i * 4)
                }
            }
            val outputA = Cat(ram.reverse.map(r => if (readWritePort == 0) r.dout0 else r.dout1))
            val outputB = Cat(ram.reverse.map(r => if (readWritePort == 1) r.dout0 else r.dout1))
            io.douta := outputA(logicalWidth - 1, 0)
            io.doutb := outputB(logicalWidth - 1, 0)
        case DualPortRamBackend.Vivado if lanes == 1 =>
            val ram = Module(new XilinxTrueDualPortReadFirst1ClockRam(laneBits, depth))
            ram.io.addra := io.addra
            ram.io.addrb := io.addrb
            ram.io.dina := io.dina
            ram.io.dinb := io.dinb
            ram.io.clka := io.clka
            ram.io.wea := io.wea.asBool
            ram.io.web := io.web.asBool
            ram.io.ena := io.ena
            ram.io.enb := io.enb
            io.douta := ram.io.douta
            io.doutb := ram.io.doutb
        case DualPortRamBackend.Vivado =>
            val ram = Module(new XilinxTrueDualPortReadFirstByteWrite1ClockRam(lanes, laneBits, depth))
            ram.io.addra := io.addra
            ram.io.addrb := io.addrb
            ram.io.dina := io.dina
            ram.io.dinb := io.dinb
            ram.io.clka := io.clka
            ram.io.wea := io.wea
            ram.io.web := io.web
            ram.io.ena := io.ena
            ram.io.enb := io.enb
            io.douta := ram.io.douta
            io.doutb := ram.io.doutb
        case DualPortRamBackend.Registers =>
            val ram = Module(new ZirconSram1RW1R(depth, lanes, laneBits, readWritePort))
            ram.io <> io
    }
}
