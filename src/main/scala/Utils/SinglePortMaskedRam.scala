import chisel3._
import chisel3.util._

class SinglePortMaskedRamIO(addressBits: Int, dataBits: Int, lanes: Int) extends Bundle {
    val clock = Input(Clock())
    val enable = Input(Bool())
    val write = Input(Bool())
    val address = Input(UInt(addressBits.W))
    val dataIn = Input(UInt(dataBits.W))
    val mask = Input(UInt(lanes.W))
    val dataOut = Output(UInt(dataBits.W))
}

/** One clocked port with lane write masks. Consumers must hold an unconsumed read response. */
class SinglePortMaskedRam(depth: Int, lanes: Int, laneBits: Int) extends RawModule {
    require(depth >= 1 && isPow2(depth) && lanes > 0 && laneBits > 0)
    private val addressBits = math.max(1, log2Ceil(depth))
    private val dataBits = lanes * laneBits
    override def desiredName = s"SinglePortMaskedRam_${depth}_${lanes}_${laneBits}"
    val io = FlatIO(new SinglePortMaskedRamIO(addressBits, dataBits, lanes))

    // Each slot has its own write enable. ASIC flows replace only the stable SRAM leaf.
    if (sys.env.get("ZIRCON_USE_EXTERNAL_VIVADO_RAM").contains("true")) {
        val data = Wire(Vec(lanes, UInt(laneBits.W)))
        for (i <- 0 until lanes) {
            val ram = Module(new XilinxSinglePortRamReadFirst(laneBits, depth))
            ram.io.clka := io.clock
            ram.io.ena := io.enable
            ram.io.wea := io.write && io.mask(i)
            ram.io.addra := io.address
            ram.io.dina := io.dataIn((i + 1) * laneBits - 1, i * laneBits)
            data(i) := ram.io.douta
        }
        io.dataOut := data.asUInt
    } else {
        withClockAndReset(io.clock, false.B) {
            if (depth == 1) {
                val data = Reg(Vec(lanes, UInt(laneBits.W)))
                for (lane <- 0 until lanes) {
                    when(io.enable && io.write && io.mask(lane)) {
                        data(lane) := io.dataIn((lane + 1) * laneBits - 1, lane * laneBits)
                    }
                }
                io.dataOut := data.asUInt
            } else {
                val raw = Wire(Vec(lanes, UInt(laneBits.W)))
                for (lane <- 0 until lanes) {
                    val ram = Module(new ZirconSram1W1R(depth, laneBits))
                    ram.io.clock := io.clock
                    ram.io.writeEnable := io.enable && io.write && io.mask(lane)
                    ram.io.writeAddress := io.address
                    ram.io.writeData := io.dataIn((lane + 1) * laneBits - 1, lane * laneBits)
                    ram.io.readEnable := io.enable
                    ram.io.readAddress := io.address
                    raw(lane) := ram.io.readData
                }
                val forwarding = RegInit(false.B)
                val forwardingMask = Reg(UInt(lanes.W))
                val forwardingData = Reg(UInt(dataBits.W))
                when(io.enable) {
                    forwarding := io.write
                    when(io.write) {
                        forwardingMask := io.mask
                        forwardingData := io.dataIn
                    }
                }
                val expandedMask = Cat(forwardingMask.asBools.reverse.map(Fill(laneBits, _)))
                io.dataOut := Mux(
                    forwarding,
                    (raw.asUInt & ~expandedMask) | (forwardingData & expandedMask),
                    raw.asUInt,
                )
            }
        }
    }
}
