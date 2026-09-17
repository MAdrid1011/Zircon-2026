import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.TLBParams

class TLBDriver(val dut: TLB) extends chisel3.simulator.PeekPokeAPI {
    def initialize(): Unit = {
        dut.io.lookup.foreach { query =>
            query.valid.poke(false)
            query.bits.vaddr.poke(0)
        }
        dut.io.refill.valid.poke(false)
        dut.io.refill.bits.vaddr.poke(0)
        dut.io.refill.bits.ppn.poke(0)
        dut.io.refill.bits.asid.poke(0)
        dut.io.refill.bits.global.poke(false)
        dut.io.refill.bits.pma.poke(0)
        dut.io.refill.bits.permissions.read.poke(false)
        dut.io.refill.bits.permissions.write.poke(false)
        dut.io.refill.bits.permissions.execute.poke(false)
        dut.io.refill.bits.permissions.user.poke(false)
        dut.io.refill.bits.permissions.accessed.poke(false)
        dut.io.refill.bits.permissions.dirty.poke(false)
        dut.io.refill.bits.superpage.poke(false)
        dut.io.scopeUpdate.valid.poke(false)
        dut.io.scopeUpdate.bits.asid.poke(0)
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    def scope(asid: Int): Unit = {
        dut.io.scopeUpdate.bits.asid.poke(asid)
        dut.io.scopeUpdate.valid.poke(true)
        dut.clock.step()
        dut.io.scopeUpdate.valid.poke(false)
    }

    def refill(
        vaddr: BigInt,
        ppn: BigInt,
        asid: Int,
        global: Boolean = false,
        pma: Int = 0,
        superpage: Boolean = false,
        read: Boolean = true,
        write: Boolean = false,
        execute: Boolean = false,
        user: Boolean = false,
        accessed: Boolean = true,
        dirty: Boolean = false,
    ): Unit = {
        dut.io.refill.bits.vaddr.poke(vaddr)
        dut.io.refill.bits.ppn.poke(ppn)
        dut.io.refill.bits.asid.poke(asid)
        dut.io.refill.bits.global.poke(global)
        dut.io.refill.bits.pma.poke(pma)
        dut.io.refill.bits.permissions.read.poke(read)
        dut.io.refill.bits.permissions.write.poke(write)
        dut.io.refill.bits.permissions.execute.poke(execute)
        dut.io.refill.bits.permissions.user.poke(user)
        dut.io.refill.bits.permissions.accessed.poke(accessed)
        dut.io.refill.bits.permissions.dirty.poke(dirty)
        dut.io.refill.bits.superpage.poke(superpage)
        dut.io.refill.valid.poke(true)
        dut.clock.step()
        dut.io.refill.valid.poke(false)
    }

    def expect(
        port: Int,
        vaddr: BigInt,
        hit: Boolean,
        paddr: BigInt = 0,
        pma: Int = 0,
        superpage: Boolean = false,
    ): Unit = {
        dut.io.lookup(port).bits.vaddr.poke(vaddr)
        dut.io.lookup(port).valid.poke(true)
        dut.io.response(port).hit.expect(hit)
        dut.io.response(port).paddr.expect(if (hit) paddr else BigInt(0))
        dut.io.response(port).pma.expect(if (hit) pma else 0)
        dut.io.response(port).superpage.expect(hit && superpage)
    }

    def clearLookups(): Unit = dut.io.lookup.foreach(_.valid.poke(false))

    def flush(): Unit = {
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
    }
}

class TLBSpec extends AnyFreeSpec with ChiselSim {
    "ITLB translates 4 KiB pages and keeps only the current ASID in scope" in {
        simulate(new InstructionTLB) { dut =>
            val d = new TLBDriver(dut)
            d.initialize()
            d.scope(3)

            val va = BigInt("12345000", 16)
            val ppn = BigInt("2abcd", 16)
            d.refill(va, ppn, asid = 3, pma = 2, execute = true, user = true)
            d.expect(0, va + 0x678, hit = true, (ppn << 12) + 0x678, pma = 2)
            dut.io.response(0).permissions.execute.expect(true)
            dut.io.response(0).permissions.user.expect(true)
            dut.io.response(0).permissions.write.expect(false)
            dut.clock.step()
            d.clearLookups()

            d.expect(0, va + 0x1000, hit = false)
            d.clearLookups()
            d.scope(7)
            d.expect(0, va + 0x678, hit = false)
            d.clearLookups()

            val globalVa = BigInt("45678000", 16)
            val globalPpn = BigInt("30000", 16)
            d.refill(globalVa, globalPpn, asid = 3, global = true, pma = 1, execute = true)
            d.scope(12)
            d.expect(0, globalVa + 0x24, hit = true, (globalPpn << 12) + 0x24, pma = 1)
            d.clearLookups()

            d.scope(3)
            d.expect(0, va + 0x678, hit = true, (ppn << 12) + 0x678, pma = 2)
            d.clearLookups()

            val updatedPpn = BigInt("15555", 16)
            d.refill(va, updatedPpn, asid = 3, pma = 0, execute = true)
            d.expect(0, va + 0x678, hit = true, (updatedPpn << 12) + 0x678, pma = 0)
            d.clearLookups()

            d.flush()
            d.expect(0, va, hit = false)
            d.clearLookups()
            d.expect(0, globalVa, hit = false)
        }
    }

    "DTLB serves two lookups and prevents overlapping 4 KiB and 4 MiB translations" in {
        simulate(new DataTLB) { dut =>
            val d = new TLBDriver(dut)
            d.initialize()
            d.scope(5)

            val va = BigInt("48c00000", 16)
            val ppn1 = BigInt("2aa", 16)
            d.refill(va, ppn1 << 10, asid = 5, pma = 0, superpage = true, write = true, user = true, dirty = true)
            val va0 = va + 0x1234
            val va1 = va + 0x321abc
            d.expect(0, va0, hit = true, (ppn1 << 22) + 0x1234, pma = 0, superpage = true)
            d.expect(1, va1, hit = true, (ppn1 << 22) + 0x321abc, pma = 0, superpage = true)
            dut.io.response(0).permissions.write.expect(true)
            dut.io.response(1).permissions.dirty.expect(true)
            dut.clock.step()
            d.clearLookups()

            val smallVa = va + 0x12000
            val smallPpn = BigInt("1fedc", 16)
            d.refill(smallVa, smallPpn, asid = 5, pma = 1, write = true)
            d.expect(0, smallVa + 0x88, hit = true, (smallPpn << 12) + 0x88, pma = 1)
            d.expect(1, va + 0x22000, hit = false)
            d.clearLookups()

            val replacementPpn1 = BigInt("155", 16)
            d.refill(va, replacementPpn1 << 10, asid = 5, pma = 2, superpage = true, read = false, write = true)
            d.expect(0, smallVa + 0x88, hit = true, (replacementPpn1 << 22) + 0x12088, pma = 2, superpage = true)
            d.expect(1, va + 0x22000, hit = true, (replacementPpn1 << 22) + 0x22000, pma = 2, superpage = true)
        }
    }

    "four-way normal bank uses invalid entries first and tree-PLRU after it fills" in {
        val p = TLBParams(queryPorts = 1)
        simulate(new TLB(p)) { dut =>
            val d = new TLBDriver(dut)
            d.initialize()
            d.scope(1)
            val base = BigInt("10001000", 16)
            val stride = BigInt(1) << 14
            val pages = (0 until 5).map(i => base + i * stride)
            val ppns = (0 until 5).map(i => BigInt("20000", 16) + i)
            for (i <- 0 until 4) d.refill(pages(i), ppns(i), asid = 1)

            // Touch way 0. With all four ways full, the PLRU victim becomes way 2.
            d.expect(0, pages(0), hit = true, ppns(0) << 12)
            dut.clock.step()
            d.clearLookups()
            d.refill(pages(4), ppns(4), asid = 1)

            for (i <- Seq(0, 1, 3, 4)) {
                d.expect(0, pages(i), hit = true, ppns(i) << 12)
                d.clearLookups()
            }
            d.expect(0, pages(2), hit = false)
        }
    }

    "scope update and refill in the same cycle use the new accepted ASID" in {
        simulate(new InstructionTLB) { dut =>
            val d = new TLBDriver(dut)
            d.initialize()
            val va = BigInt("80001000", 16)
            val ppn = BigInt("28001", 16)
            dut.io.scopeUpdate.valid.poke(true)
            dut.io.scopeUpdate.bits.asid.poke(9)
            dut.io.refill.valid.poke(true)
            dut.io.refill.bits.vaddr.poke(va)
            dut.io.refill.bits.ppn.poke(ppn)
            dut.io.refill.bits.asid.poke(9)
            dut.io.refill.bits.global.poke(false)
            dut.io.refill.bits.pma.poke(0)
            dut.io.refill.bits.permissions.read.poke(true)
            dut.io.refill.bits.permissions.write.poke(false)
            dut.io.refill.bits.permissions.execute.poke(false)
            dut.io.refill.bits.permissions.user.poke(false)
            dut.io.refill.bits.permissions.accessed.poke(true)
            dut.io.refill.bits.permissions.dirty.poke(false)
            dut.io.refill.bits.superpage.poke(false)
            dut.clock.step()
            dut.io.scopeUpdate.valid.poke(false)
            dut.io.refill.valid.poke(false)
            d.expect(0, va, hit = true, ppn << 12)
        }
    }

    "a global refill removes same-VPN entries from every ASID" in {
        simulate(new InstructionTLB) { dut =>
            val d = new TLBDriver(dut)
            d.initialize()
            val va = BigInt("60005000", 16)
            val asid1Ppn = BigInt("10001", 16)
            val asid2Ppn = BigInt("10002", 16)
            val globalPpn = BigInt("10003", 16)

            d.scope(1)
            d.refill(va, asid1Ppn, asid = 1)
            d.scope(2)
            d.refill(va, asid2Ppn, asid = 2)
            d.expect(0, va, hit = true, asid2Ppn << 12)
            d.clearLookups()
            d.scope(1)
            d.expect(0, va, hit = true, asid1Ppn << 12)
            d.clearLookups()

            d.refill(va, globalPpn, asid = 1, global = true)
            d.scope(2)
            d.expect(0, va, hit = true, globalPpn << 12)
            d.clearLookups()
            d.scope(31)
            d.expect(0, va, hit = true, globalPpn << 12)
        }
    }
}
