import chisel3._
import chisel3.util._

/** One clocked port with lane write masks. Consumers must hold an unconsumed read response. */
class SinglePortMaskedRam(depth: Int, lanes: Int, laneBits: Int) extends RawModule {
    require(depth >= 1 && isPow2(depth) && lanes > 0 && laneBits > 0)
    private val addressBits = math.max(1, log2Ceil(depth))
    private val dataBits = lanes * laneBits
    override def desiredName = s"SinglePortMaskedRam_${depth}_${lanes}_${laneBits}"
    val io = FlatIO(new Bundle {
        val clock = Input(Clock())
        val enable = Input(Bool())
        val write = Input(Bool())
        val address = Input(UInt(addressBits.W))
        val dataIn = Input(UInt(dataBits.W))
        val mask = Input(UInt(lanes.W))
        val dataOut = Output(UInt(dataBits.W))
    })

    // Each slot has its own write enable. ASIC flows replace this wrapper with masked SRAM macros.
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
}
