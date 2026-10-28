import chisel3._
import chisel3.util._

/** One write and one synchronous read; output holds between enabled reads.
  * Data has no reset. Concurrent read/write of the same word is undefined.
  * The leaf template is replaced by the target SRAM adapter for ASIC mapping.
  */
class ZirconSram1W1R(depth: Int, width: Int)
    extends ExtModule(Map("DEPTH" -> depth, "WIDTH" -> width)) {
    require(depth >= 2 && isPow2(depth) && width > 0)
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val writeEnable = Input(Bool())
        val writeAddress = Input(UInt(log2Ceil(depth).W))
        val writeData = Input(UInt(width.W))
        val readEnable = Input(Bool())
        val readAddress = Input(UInt(log2Ceil(depth).W))
        val readData = Output(UInt(width.W))
    })
    addResource("/memory/ZirconSram1W1R.sv")
}
