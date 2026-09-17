import chisel3._
import chisel3.util._

sealed trait DualPortRamBackend
object DualPortRamBackend {
    case object Vivado extends DualPortRamBackend
    case object Registers extends DualPortRamBackend
    case object OpenRAM extends DualPortRamBackend

    def parse(name: String): DualPortRamBackend = name match {
        case "vivado" => Vivado
        case "registers" => Registers
        case "openram" => OpenRAM
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

/** Vivado and Registers preserve the 2024 registered-address contract and live held outputs.
  * OpenRAM only guarantees enabled reads until the next sampling edge; consumers must snapshot or reread.
  * Reads of uninitialized register storage are unspecified. Consumers use separate valid bits.
  * Overlapping writes to the same lane are forbidden; legal accesses have exactly two ports.
  */
class DualPortMaskedRam(
    depth: Int,
    lanes: Int,
    laneBits: Int,
    backend: DualPortRamBackend,
    readWritePort: Int = 0
) extends RawModule {
    require(depth >= 2 && isPow2(depth) && lanes > 0 && laneBits > 0)
    val io = IO(new DualPortMaskedRamIO(depth, lanes, laneBits))

    backend match {
        case DualPortRamBackend.OpenRAM =>
            require(depth == 16 && (lanes == 1 && laneBits == 25 || lanes == 32 && laneBits == 8))
            require(readWritePort == 0 || readWritePort == 1)
            val address = Seq(io.addra, io.addrb)
            val enable = Seq(io.ena, io.enb)
            val mask = Seq(io.wea, io.web)
            val data = Seq(io.dina, io.dinb)
            val width = if (lanes == 1) 25 else 32
            val ram = Seq.fill(lanes * laneBits / width)(Module(new OpenRam1RW1R(width)))
            for ((r, i) <- ram.zipWithIndex) {
                r.io.clock := io.clka
                r.io.csb0 := !enable(readWritePort)
                r.io.csb1 := !enable(1 - readWritePort)
                r.io.web0 := !mask(readWritePort).orR
                r.io.addr0 := address(readWritePort)
                r.io.addr1 := address(1 - readWritePort)
                r.io.din0 := data(readWritePort)((i + 1) * width - 1, i * width)
                r.io.wmask0 := (if (lanes == 1) mask(readWritePort) else mask(readWritePort)(i * 4 + 3, i * 4))
            }
            io.douta := Cat(ram.reverse.map(r => if (readWritePort == 0) r.io.dout0 else r.io.dout1))
            io.doutb := Cat(ram.reverse.map(r => if (readWritePort == 1) r.io.dout0 else r.io.dout1))
            withClockAndReset(io.clka, false.B) {
                assert(!enable(1 - readWritePort) || !mask(1 - readWritePort).orR, "OpenRAM: write on read-only port")
            }
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
            withClockAndReset(io.clka, false.B) {
                val memory = Reg(Vec(depth, Vec(lanes, UInt(laneBits.W))))
                val addressA = RegEnable(io.addra, io.ena)
                val addressB = RegEnable(io.addrb, io.enb)
                for (lane <- 0 until lanes) {
                    when(io.ena && io.wea(lane)) {
                        memory(io.addra)(lane) := io.dina((lane + 1) * laneBits - 1, lane * laneBits)
                    }
                    when(io.enb && io.web(lane)) {
                        memory(io.addrb)(lane) := io.dinb((lane + 1) * laneBits - 1, lane * laneBits)
                    }
                }
                io.douta := memory(addressA).asUInt
                io.doutb := memory(addressB).asUInt
            }
    }
}
