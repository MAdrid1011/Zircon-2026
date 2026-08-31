package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.IntegerPhysicalRegisterFile

class IntegerPhysicalRegisterFileSpec extends AnyFunSpec with ChiselSim {
  describe("IntegerPhysicalRegisterFile") {
    it("supports six reads, two writes, forwarding, and an immutable p0") {
      simulate(new IntegerPhysicalRegisterFile(ZirconCoreConfig.default)) { dut =>
        dut.io.write.foreach(_.valid.poke(false))
        (0 until 6).foreach { index =>
          dut.io.readPhysical(index).poke(index)
          dut.io.readData(index).expect(0)
        }

        dut.io.write(0).valid.poke(true)
        dut.io.write(0).bits.physical.poke(1)
        dut.io.write(0).bits.data.poke(BigInt("12345678", 16))
        dut.io.write(1).valid.poke(true)
        dut.io.write(1).bits.physical.poke(55)
        dut.io.write(1).bits.data.poke(BigInt("fedcba98", 16))
        dut.io.readPhysical(0).poke(1)
        dut.io.readPhysical(1).poke(55)
        dut.io.readData(0).expect(BigInt("12345678", 16))
        dut.io.readData(1).expect(BigInt("fedcba98", 16))
        dut.clock.step()

        dut.io.write.foreach(_.valid.poke(false))
        dut.io.readData(0).expect(BigInt("12345678", 16))
        dut.io.readData(1).expect(BigInt("fedcba98", 16))
        dut.io.readPhysical(2).poke(0)
        dut.io.readData(2).expect(0)

        dut.io.write(0).valid.poke(true)
        dut.io.write(0).bits.physical.poke(2)
        dut.io.write(0).bits.data.poke(BigInt("a5a55a5a", 16))
        dut.io.readPhysical(3).poke(2)
        dut.io.readData(3).expect(BigInt("a5a55a5a", 16))
      }
    }
  }
}
