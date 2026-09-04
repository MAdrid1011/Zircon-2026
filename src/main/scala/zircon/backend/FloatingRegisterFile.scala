package zircon.backend

import chisel3._
import chisel3.util._

/** Architectural floating-point register file.
  *
  * FPRs are not renamed. The sole write port is consequently driven only by
  * commit-qualified floating-point results; speculative execution must not
  * write this state directly.
  */
class FloatingRegisterFile extends Module {
  val io = IO(new Bundle {
    val readAddress = Input(Vec(3, UInt(5.W)))
    val readData = Output(Vec(3, UInt(32.W)))
    // LSU stores intentionally use the registered architectural value.  The
    // normal read path keeps commit write-through for an E2 consumer, while
    // this path prevents that wide bypass cone from reaching L2 write data.
    val readDataNoBypass = Output(Vec(3, UInt(32.W)))
    val write = Input(Valid(new Bundle {
      val address = UInt(5.W)
      val data = UInt(32.W)
    }))
  })

  val registers = RegInit(VecInit.fill(32)(0.U(32.W)))

  for (((address, data), noBypass) <- io.readAddress.zip(io.readData)
      .zip(io.readDataNoBypass)) {
    data := Mux(io.write.valid && io.write.bits.address === address,
      io.write.bits.data, registers(address))
    noBypass := registers(address)
  }

  when(io.write.valid) {
    registers(io.write.bits.address) := io.write.bits.data
  }
}
