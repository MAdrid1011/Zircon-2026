import chisel3._
import chisel3.util._

class CommitSideTableWrite(depth: Int, width: Int) extends Bundle {
    val address = UInt(log2Ceil(depth).W)
    val data = UInt(width.W)
}

/** Vector state consumed at architectural retirement. */
class CommitVectorResult extends Bundle {
    val configuration = Bool()
    val vl = UInt(32.W)
    val vtype = UInt(32.W)
    val fflags = UInt(5.W)
    val saturated = Bool()
}

/** Replicated SRAM side table with one shared write and synchronous reads.
  * A colliding read receives the forwarded write on the following cycle.
  */
class CommitSideTable(depth: Int, width: Int, readPorts: Int) extends Module {
    require(depth >= 2 && isPow2(depth) && width > 0 && readPorts > 0)

    val io = IO(new Bundle {
        val write = Flipped(Valid(new CommitSideTableWrite(depth, width)))
        val readAddress = Input(Vec(readPorts, UInt(log2Ceil(depth).W)))
        val readData = Output(Vec(readPorts, UInt(width.W)))
    })

    for (port <- 0 until readPorts) {
        val writeHit = io.write.valid && io.write.bits.address === io.readAddress(port)
        val ram = Module(new ZirconSram1W1R(depth, width))
        ram.io.clock := clock
        ram.io.writeEnable := io.write.valid
        ram.io.writeAddress := io.write.bits.address
        ram.io.writeData := io.write.bits.data
        // Reads have no side effects. Keeping the SRAM port enabled removes the
        // completion-address comparison from its timing-sensitive enable path;
        // the registered bypass masks the undefined same-address RAM result.
        ram.io.readEnable := true.B
        ram.io.readAddress := io.readAddress(port)

        val bypassValid = RegNext(writeHit, false.B)
        val bypassData = RegEnable(io.write.bits.data, writeHit)
        io.readData(port) := Mux(bypassValid, bypassData, ram.io.readData)
    }
}
