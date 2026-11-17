import chisel3._
import chisel3.util._

/** Stable one-read/write plus one-read SRAM leaf for portable cache RTL. */
class ZirconSram1RW1R(depth: Int, lanes: Int, laneBits: Int, writePort: Int)
    extends ExtModule(Map(
        "DEPTH" -> depth,
        "LANES" -> lanes,
        "LANE_BITS" -> laneBits,
        "WRITE_PORT" -> writePort
    )) {
    require(depth >= 2 && isPow2(depth) && lanes > 0 && laneBits > 0)
    require(writePort == 0 || writePort == 1)
    val io = FlatIO(new DualPortMaskedRamIO(depth, lanes, laneBits))
    addResource("/memory/ZirconSram1RW1R.sv")
}
