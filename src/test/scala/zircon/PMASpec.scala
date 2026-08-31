package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.PMAClassifier

class PMASpec extends AnyFunSpec with ChiselSim {
  describe("PMAClassifier") {
    it("distinguishes memory, strong device, burstable device, and holes") {
      simulate(new PMAClassifier(ZirconCoreConfig.default)) { dut =>
        dut.io.address.poke(BigInt("80000000", 16))
        dut.io.matched.expect(true)
        dut.io.attributes.kind.expect(PMARegionKind.Memory.code)
        dut.io.attributes.executable.expect(true)
        dut.io.attributes.atomic.expect(true)

        dut.io.address.poke(BigInt("a00003f8", 16))
        dut.io.matched.expect(true)
        dut.io.attributes.kind.expect(PMARegionKind.DeviceStrong.code)
        dut.io.attributes.executable.expect(false)
        dut.io.attributes.atomic.expect(false)

        dut.io.address.poke(BigInt("b0001000", 16))
        dut.io.attributes.kind.expect(PMARegionKind.DeviceBurstable.code)

        dut.io.address.poke(BigInt("40000000", 16))
        dut.io.matched.expect(false)
        dut.io.attributes.kind.expect(PMARegionKind.Inaccessible.code)
      }
    }
  }
}
