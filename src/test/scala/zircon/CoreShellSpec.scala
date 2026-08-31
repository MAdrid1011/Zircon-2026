package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.core.ZirconCore

class CoreShellSpec extends AnyFunSpec with ChiselSim {
  describe("ZirconCore M0 shell") {
    it("holds AXI request channels idle and exposes two invalid retire lanes") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        dut.io.interrupts.meip.poke(false)
        dut.io.interrupts.msip.poke(false)
        dut.io.interrupts.mtip.poke(false)
        dut.io.axi.aw.ready.poke(true)
        dut.io.axi.w.ready.poke(true)
        dut.io.axi.ar.ready.poke(true)
        dut.io.axi.b.valid.poke(false)
        dut.io.axi.b.bits.id.poke(0)
        dut.io.axi.b.bits.resp.poke(0)
        dut.io.axi.r.valid.poke(false)
        dut.io.axi.r.bits.id.poke(0)
        dut.io.axi.r.bits.data.poke(0)
        dut.io.axi.r.bits.resp.poke(0)
        dut.io.axi.r.bits.last.poke(false)

        dut.io.axi.aw.valid.expect(false)
        dut.io.axi.w.valid.expect(false)
        dut.io.axi.ar.valid.expect(false)
        dut.io.trace.get(0).valid.expect(false)
        dut.io.trace.get(1).valid.expect(false)
      }
    }
  }
}
