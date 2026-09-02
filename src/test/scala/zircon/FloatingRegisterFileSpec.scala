package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FloatingRegisterFile

class FloatingRegisterFileSpec extends AnyFunSpec with ChiselSim {
  private def clearWrite(dut: FloatingRegisterFile): Unit = {
    dut.io.write.valid.poke(false)
    dut.io.write.bits.address.poke(0)
    dut.io.write.bits.data.poke(0)
  }

  describe("FloatingRegisterFile") {
    it("supports independent two-read/one-write access and write forwarding") {
      simulate(new FloatingRegisterFile) { dut =>
        clearWrite(dut)
        dut.io.readAddress(0).poke(0)
        dut.io.readAddress(1).poke(31)
        dut.io.readData(0).expect(0)
        dut.io.readData(1).expect(0)

        dut.io.write.valid.poke(true)
        dut.io.write.bits.address.poke(0)
        dut.io.write.bits.data.poke(BigInt("3f800000", 16))
        dut.io.readData(0).expect(BigInt("3f800000", 16))
        dut.io.readData(1).expect(0)
        dut.clock.step()

        dut.io.write.bits.address.poke(31)
        dut.io.write.bits.data.poke(BigInt("7fc00000", 16))
        dut.io.readData(0).expect(BigInt("3f800000", 16))
        dut.io.readData(1).expect(BigInt("7fc00000", 16))
        dut.clock.step()

        clearWrite(dut)
        dut.io.readData(0).expect(BigInt("3f800000", 16))
        dut.io.readData(1).expect(BigInt("7fc00000", 16))

        dut.io.readAddress(0).poke(13)
        dut.io.readAddress(1).poke(0)
        dut.io.readData(0).expect(0)
        dut.io.readData(1).expect(BigInt("3f800000", 16))
      }
    }
  }
}
