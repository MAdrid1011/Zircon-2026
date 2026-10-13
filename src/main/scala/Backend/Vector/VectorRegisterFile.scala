import chisel3._
import chisel3.util._
import ZirconConfig.{RegfileParams, VectorParams}

class VectorRegisterRead(p: VectorParams) extends Bundle {
    val address = Input(UInt(log2Ceil(p.physicalRegisters).W))
    val data = Output(Vec(p.lanes, UInt(32.W)))
    val oneHot = Input(UInt(p.physicalRegisters.W))
}

class VectorRegisterWrite(p: VectorParams) extends Bundle {
    val valid = Input(Bool())
    val address = Input(UInt(log2Ceil(p.physicalRegisters).W))
    val data = Input(Vec(p.lanes, UInt(32.W)))
    val bytes = Input(Vec(p.lanes, UInt(4.W)))
}

/** Three collected operands, with independent compute/load writeback ports.
  * ReadyBoard publishes readiness after the write edge, so reads need no WB
  * bypass. Byte enables preserve mask bits and SEW8/16 undisturbed elements.
  */
class VectorRegisterFile(val p: VectorParams = VectorParams(), val observe: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val read = Vec(3, new VectorRegisterRead(p))
        val write = Vec(2, new VectorRegisterWrite(p))
        val observation = if (observe) Some(Output(UInt((64 * 256).W))) else None
    })
    val observed = if (observe) Some(Wire(Vec(64, Vec(8, UInt(32.W))))) else None
    for (lane <- 0 until p.lanes) {
        val bank = Module(new Regfile(RegfileParams(
            numEntries = p.physicalRegisters, numReadPorts = 3, numWritePorts = 2,
            hasZeroReg = false, byteWrite = true, readBypass = false, initialize = false,
            observe = observe,
            oneHotRead = true,
        )))
        for (port <- 0 until 3) {
            bank.io.read(port).addr := io.read(port).address
            bank.io.read(port).oneHot.get := io.read(port).oneHot
            io.read(port).data(lane) := bank.io.read(port).data
        }
        for (port <- 0 until 2) {
            bank.io.write(port).we := io.write(port).valid
            bank.io.write(port).addr := io.write(port).address
            bank.io.write(port).data := io.write(port).data(lane)
            bank.io.write(port).bytes.get := io.write(port).bytes(lane)
        }
        if (observe) for (register <- 0 until 64) {
            observed.get(register)(lane) := bank.io.observation.get(register)
        }
    }
    io.observation.foreach(_ := observed.get.asUInt)
    assert(!(io.write(0).valid && io.write(1).valid &&
        io.write(0).address === io.write(1).address), "Vector writers own distinct physical registers")
}
