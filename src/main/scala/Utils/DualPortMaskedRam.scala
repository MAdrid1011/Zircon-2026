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
    val writeAddress = Input(UInt(log2Ceil(depth).W))
    val dina = Input(UInt((lanes * laneBits).W))
    val dinb = Input(UInt((lanes * laneBits).W))
    val clka = Input(Clock())
    val wea = Input(UInt(lanes.W))
    val web = Input(UInt(lanes.W))
    val writeAllA = Input(Bool())
    val writeAllB = Input(Bool())
    val ena = Input(Bool())
    val enb = Input(Bool())
    val douta = Output(UInt((lanes * laneBits).W))
    val doutb = Output(UInt((lanes * laneBits).W))
}

/** One selected port supports masked writes; the other port is read-only.
  * All backends preserve the registered-address contract and live held outputs.
  * writeAddress selects the write row independently from the read addresses.
  * writeCycleReadUnused declares both outputs unobserved during a write; the
  * next consumed output must follow a later enabled read.
  * Reads of uninitialized register storage are unspecified. Consumers use separate valid bits.
  */
class DualPortMaskedRam(
    depth: Int,
    lanes: Int,
    laneBits: Int,
    backend: DualPortRamBackend,
    readWritePort: Int = 0,
    fullWriteOnly: Boolean = false,
    writeCycleReadUnused: Boolean = false
) extends RawModule {
    require(depth >= 2 && isPow2(depth) && lanes > 0 && laneBits > 0)
    require(readWritePort == 0 || readWritePort == 1)
    val io = IO(new DualPortMaskedRamIO(depth, lanes, laneBits))

    val writeMask = Seq(io.wea, io.web)
    val writeAll = Seq(io.writeAllA, io.writeAllB)
    val address = Seq(io.addra, io.addrb)
    val enable = Seq(io.ena, io.enb)
    val effectiveWriteMask = writeMask.zip(writeAll).map { case (mask, all) =>
        Mux(all, Fill(lanes, 1.U), mask)
    }
    val writeOperation = enable(readWritePort) && effectiveWriteMask(readWritePort).orR
    withClockAndReset(io.clka, false.B) {
        assert(!(writeAll(1 - readWritePort) || writeMask(1 - readWritePort).orR),
            "RAM read-only port cannot write")
        if (fullWriteOnly) {
            assert(!writeMask(readWritePort).orR,
                "Whole-word RAM cannot use a lane write mask")
        } else {
            assert(!writeAll(readWritePort),
                "Masked RAM cannot use the whole-word write input")
        }
        if (!writeCycleReadUnused) {
            when(writeOperation) {
                assert(io.writeAddress === address(readWritePort),
                    "Independent write address requires an unused write-cycle read result")
            }
        }
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
            val mask = effectiveWriteMask
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
                r.addr0 := Mux(writeOperation, io.writeAddress, address(readWritePort))
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
            ram.io.addra := (if (readWritePort == 0) Mux(writeOperation, io.writeAddress, io.addra) else io.addra)
            ram.io.addrb := (if (readWritePort == 1) Mux(writeOperation, io.writeAddress, io.addrb) else io.addrb)
            ram.io.dina := io.dina
            ram.io.dinb := io.dinb
            ram.io.clka := io.clka
            ram.io.wea := effectiveWriteMask(0).asBool
            ram.io.web := effectiveWriteMask(1).asBool
            ram.io.ena := io.ena
            ram.io.enb := io.enb
            io.douta := ram.io.douta
            io.doutb := ram.io.doutb
        case DualPortRamBackend.Vivado =>
            val ram = Module(new XilinxTrueDualPortReadFirstByteWrite1ClockRam(lanes, laneBits, depth))
            ram.io.addra := (if (readWritePort == 0) Mux(writeOperation, io.writeAddress, io.addra) else io.addra)
            ram.io.addrb := (if (readWritePort == 1) Mux(writeOperation, io.writeAddress, io.addrb) else io.addrb)
            ram.io.dina := io.dina
            ram.io.dinb := io.dinb
            ram.io.clka := io.clka
            ram.io.wea := effectiveWriteMask(0)
            ram.io.web := effectiveWriteMask(1)
            ram.io.ena := io.ena
            ram.io.enb := io.enb
            io.douta := ram.io.douta
            io.doutb := ram.io.doutb
        case DualPortRamBackend.Registers =>
            val ram = Module(new ZirconSram1RW1R(
                depth, lanes, laneBits, readWritePort, fullWriteOnly, writeCycleReadUnused))
            ram.io <> io
    }
}

/** A wide RAM split into independent stripes with replicated write-address registers.
  *
  * The caller presents the write address at least one cycle before a write and keeps
  * it unchanged through the write edge. Reads retain DualPortMaskedRam's registered-
  * address timing. Splitting the payload gives every physical stripe a local address
  * source instead of placing all SRAM write-address pins on one high-fanout net.
  */
class StripedDualPortMaskedRam(
    depth: Int,
    lanes: Int,
    laneBits: Int,
    stripes: Int,
    backend: DualPortRamBackend,
    readWritePort: Int = 0,
    fullWriteOnly: Boolean = false,
    writeCycleReadUnused: Boolean = false
) extends RawModule {
    require(depth >= 2 && isPow2(depth) && lanes > 0 && laneBits > 0)
    require(stripes > 1 && lanes % stripes == 0)
    require(readWritePort == 0 || readWritePort == 1)
    val io = IO(new DualPortMaskedRamIO(depth, lanes, laneBits))

    private val lanesPerStripe = lanes / stripes
    private val bitsPerStripe = lanesPerStripe * laneBits
    val stripeWriteAddress = withClockAndReset(io.clka, false.B) {
        Reg(Vec(stripes, UInt(log2Ceil(depth).W)))
    }
    withClockAndReset(io.clka, false.B) {
        stripeWriteAddress.foreach(_ := io.writeAddress)
        val selectedMask = if (readWritePort == 0) io.wea else io.web
        val selectedWriteAll = if (readWritePort == 0) io.writeAllA else io.writeAllB
        val selectedEnable = if (readWritePort == 0) io.ena else io.enb
        val write = selectedEnable &&
            (if (fullWriteOnly) selectedWriteAll else selectedMask.orR)
        when(write) {
            assert(stripeWriteAddress.head === io.writeAddress,
                "Striped RAM write address must be prepared one cycle before write")
        }
    }

    val stripeRam = Seq.fill(stripes) {
        Module(new DualPortMaskedRam(
            depth,
            lanesPerStripe,
            laneBits,
            backend,
            readWritePort,
            fullWriteOnly,
            writeCycleReadUnused,
        ))
    }
    for ((ram, stripe) <- stripeRam.zipWithIndex) {
        val lowLane = stripe * lanesPerStripe
        val highLane = lowLane + lanesPerStripe - 1
        val lowBit = stripe * bitsPerStripe
        val highBit = lowBit + bitsPerStripe - 1
        ram.io.addra := io.addra
        ram.io.addrb := io.addrb
        ram.io.writeAddress := stripeWriteAddress(stripe)
        ram.io.dina := io.dina(highBit, lowBit)
        ram.io.dinb := io.dinb(highBit, lowBit)
        ram.io.clka := io.clka
        ram.io.wea := io.wea(highLane, lowLane)
        ram.io.web := io.web(highLane, lowLane)
        ram.io.writeAllA := io.writeAllA
        ram.io.writeAllB := io.writeAllB
        ram.io.ena := io.ena
        ram.io.enb := io.enb
    }
    io.douta := Cat(stripeRam.reverse.map(_.io.douta))
    io.doutb := Cat(stripeRam.reverse.map(_.io.doutb))
}
