import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class PageTableWalkerSpec extends AnyFreeSpec with ChiselSim {
    private def clearPte(dut: PageTableWalker): Unit = {
        dut.io.iptw.rsp.bits.pte.ppn.poke(0)
        dut.io.iptw.rsp.bits.pte.dirty.poke(false)
        dut.io.iptw.rsp.bits.pte.accessed.poke(false)
        dut.io.iptw.rsp.bits.pte.global.poke(false)
        dut.io.iptw.rsp.bits.pte.user.poke(false)
        dut.io.iptw.rsp.bits.pte.execute.poke(false)
        dut.io.iptw.rsp.bits.pte.write.poke(false)
        dut.io.iptw.rsp.bits.pte.read.poke(false)
        dut.io.iptw.rsp.bits.pte.valid.poke(false)
        dut.io.iptw.rsp.bits.error.poke(false)
        dut.io.dptw.rsp.bits.pte.ppn.poke(0)
        dut.io.dptw.rsp.bits.pte.dirty.poke(false)
        dut.io.dptw.rsp.bits.pte.accessed.poke(false)
        dut.io.dptw.rsp.bits.pte.global.poke(false)
        dut.io.dptw.rsp.bits.pte.user.poke(false)
        dut.io.dptw.rsp.bits.pte.execute.poke(false)
        dut.io.dptw.rsp.bits.pte.write.poke(false)
        dut.io.dptw.rsp.bits.pte.read.poke(false)
        dut.io.dptw.rsp.bits.pte.valid.poke(false)
        dut.io.dptw.rsp.bits.error.poke(false)
    }

    private def initialize(dut: PageTableWalker): Unit = {
        dut.io.control.enabled.poke(true)
        dut.io.control.asid.poke(3)
        dut.io.control.privilege.poke(1)
        dut.io.control.mxr.poke(false)
        dut.io.control.sum.poke(false)
        dut.io.rootPpn.poke(BigInt("80000", 16))
        dut.io.flush.poke(false)
        dut.io.instruction.miss.valid.poke(false)
        dut.io.instruction.miss.bits.token.poke(0)
        dut.io.instruction.miss.bits.pc.poke(0)
        dut.io.data.miss.foreach { miss =>
            miss.valid.poke(false)
            miss.bits.vaddr.poke(0)
            miss.bits.store.poke(false)
        }
        dut.io.iptw.req.ready.poke(false)
        dut.io.iptw.rsp.valid.poke(false)
        dut.io.dptw.req.ready.poke(false)
        dut.io.dptw.rsp.valid.poke(false)
        clearPte(dut)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    "data misses use DPTW for both Sv32 levels and emit a writable refill" in {
        simulate(new PageTableWalker) { dut =>
            initialize(dut)
            val vaddr = BigInt("c1401ff8", 16)
            val root = BigInt("80000", 16) << 12
            val nextPpn = BigInt("80c02", 16)
            val leafPpn = BigInt("80123", 16)

            dut.io.data.miss(1).valid.poke(true)
            dut.io.data.miss(1).bits.vaddr.poke(vaddr)
            dut.io.data.miss(1).bits.store.poke(true)
            dut.clock.step()

            dut.io.dptw.req.valid.expect(true)
            dut.io.iptw.req.valid.expect(false)
            dut.io.dptw.req.bits.paddr.expect(root + ((vaddr >> 22) << 2))
            dut.io.dptw.req.ready.poke(true)
            dut.clock.step()

            dut.io.dptw.req.ready.poke(false)
            dut.io.dptw.rsp.ready.expect(true)
            dut.io.dptw.rsp.bits.pte.ppn.poke(nextPpn)
            dut.io.dptw.rsp.bits.pte.valid.poke(true)
            dut.io.dptw.rsp.valid.poke(true)
            dut.clock.step()

            dut.io.dptw.rsp.valid.poke(false)
            dut.io.dptw.rsp.bits.pte.valid.poke(false)
            dut.io.dptw.req.valid.expect(true)
            dut.io.dptw.req.bits.paddr.expect((nextPpn << 12) + (((vaddr >> 12) & 0x3ff) << 2))
            dut.io.dptw.req.ready.poke(true)
            dut.clock.step()

            dut.io.dptw.req.ready.poke(false)
            dut.io.dptw.rsp.ready.expect(true)
            dut.io.dptw.rsp.bits.pte.ppn.poke(leafPpn)
            dut.io.dptw.rsp.bits.pte.dirty.poke(true)
            dut.io.dptw.rsp.bits.pte.accessed.poke(true)
            dut.io.dptw.rsp.bits.pte.write.poke(true)
            dut.io.dptw.rsp.bits.pte.read.poke(true)
            dut.io.dptw.rsp.bits.pte.valid.poke(true)
            dut.io.dptw.rsp.valid.poke(true)
            dut.clock.step()

            dut.io.dptw.rsp.valid.poke(false)
            dut.io.data.refill.valid.expect(true)
            dut.io.data.refill.bits.vpn.expect(vaddr >> 12)
            dut.io.data.refill.bits.ppn.expect(leafPpn)
            dut.io.data.refill.bits.asid.expect(3)
            dut.io.data.refill.bits.permissions.read.expect(true)
            dut.io.data.refill.bits.permissions.write.expect(true)
            dut.io.data.refill.bits.permissions.accessed.expect(true)
            dut.io.data.refill.bits.permissions.dirty.expect(true)
            dut.io.instruction.refill.valid.expect(false)

            dut.io.data.miss(1).valid.poke(false)
            dut.clock.step(2)
            dut.io.busy.expect(false)
        }
    }
}
