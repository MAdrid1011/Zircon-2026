package zircon.backend

import chisel3._
import chisel3.util._
import zircon.{ZirconCoreConfig}

class IntegerPhysicalWrite(physicalWidth: Int) extends Bundle {
  val physical = UInt(physicalWidth.W)
  val data = UInt(32.W)
}

class IntegerPhysicalRegisterFile(config: ZirconCoreConfig) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val readPhysical = Input(Vec(6, UInt(physicalWidth.W)))
    val readData = Output(Vec(6, UInt(32.W)))
    val write = Input(Vec(2, Valid(new IntegerPhysicalWrite(physicalWidth))))
  })

  val registers = RegInit(VecInit.fill(config.intPhysicalRegisters)(0.U(32.W)))

  io.write.foreach { write =>
    when(write.valid) {
      assert(write.bits.physical =/= 0.U, "integer PRF must never write p0")
      assert(write.bits.physical < config.intPhysicalRegisters.U,
        "integer PRF write address out of range")
      registers(write.bits.physical) := write.bits.data
    }
  }
  assert(!(io.write(0).valid && io.write(1).valid &&
    io.write(0).bits.physical === io.write(1).bits.physical),
    "completion ports must not write the same integer physical register")

  io.readPhysical.zip(io.readData).foreach { case (physical, data) =>
    assert(physical < config.intPhysicalRegisters.U,
      "integer PRF read address out of range")
    val forwarded = MuxCase(registers(physical), Seq(
      (io.write(0).valid && io.write(0).bits.physical === physical) -> io.write(0).bits.data,
      (io.write(1).valid && io.write(1).bits.physical === physical) -> io.write(1).bits.data
    ))
    data := Mux(physical === 0.U, 0.U, forwarded)
  }

  registers(0) := 0.U
}
