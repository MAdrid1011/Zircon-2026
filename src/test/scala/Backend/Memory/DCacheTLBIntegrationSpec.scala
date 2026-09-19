import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.DCacheParams

class DCacheTLBIntegrationDriver(val dut: DCache) extends chisel3.simulator.PeekPokeAPI {
    private val pendingForward = Array.fill[Option[BigInt]](2)(None)

    def driveIdle(): Unit = {
        dut.io.flush.poke(false)
        for (lane <- 0 until 2) {
            dut.io.load(lane).req.valid.poke(false)
            dut.io.load(lane).req.bits.vaddr.poke(0)
            dut.io.load(lane).req.bits.paddr.poke(0)
            dut.io.load(lane).req.bits.slot.poke(0)
            dut.io.load(lane).req.bits.mtype.poke(2)
            dut.io.load(lane).req.bits.uncache.poke(false)
            dut.io.load(lane).req.bits.ioAuthorized.poke(false)
            dut.io.load(lane).req.bits.exception.poke(0)
            dut.io.load(lane).req.bits.translationMiss.poke(false)
            dut.io.forward(lane).result.valid.poke(false)
            dut.io.forward(lane).result.bits.slot.poke(0)
            dut.io.forward(lane).result.bits.data.poke(0)
            dut.io.forward(lane).result.bits.mask.poke(0)
            dut.io.forward(lane).result.bits.blocked.poke(false)
        }
        dut.io.store.req.valid.poke(false)
        dut.io.store.req.bits.paddr.poke(0)
        dut.io.store.req.bits.data.poke(0)
        dut.io.store.req.bits.mask.poke(0)
        dut.io.store.req.bits.size.poke(0)
        dut.io.store.req.bits.uncache.poke(false)
        dut.io.store.rsp.ready.poke(true)
        dut.io.l2.req.ready.poke(true)
        dut.io.l2.rsp.valid.poke(false)
        dut.io.l2.rsp.bits.data.poke(0)
        dut.io.l2.rsp.bits.dirty.poke(false)
        dut.io.l2.rsp.bits.error.poke(false)
        dut.io.storeTranslation.get.request.valid.poke(false)
        dut.io.storeTranslation.get.request.bits.vaddr.poke(0)
        dut.io.storeTranslation.get.request.bits.uncache.poke(false)
        dut.io.storeTranslation.get.request.bits.exception.poke(0)
        dut.io.tlb.get.refill.valid.poke(false)
        dut.io.tlb.get.refill.bits.vpn.poke(0)
        dut.io.tlb.get.refill.bits.ppn.poke(0)
        dut.io.tlb.get.refill.bits.asid.poke(0)
        dut.io.tlb.get.refill.bits.global.poke(false)
        dut.io.tlb.get.refill.bits.pma.poke(0)
        dut.io.tlb.get.refill.bits.permissions.read.poke(false)
        dut.io.tlb.get.refill.bits.permissions.write.poke(false)
        dut.io.tlb.get.refill.bits.permissions.execute.poke(false)
        dut.io.tlb.get.refill.bits.permissions.user.poke(false)
        dut.io.tlb.get.refill.bits.permissions.accessed.poke(false)
        dut.io.tlb.get.refill.bits.permissions.dirty.poke(false)
        dut.io.tlb.get.refill.bits.superpage.poke(false)
        dut.io.tlb.get.flush.poke(false)
    }

    def initialize(): Unit = {
        driveIdle()
        dut.io.tlb.get.control.enabled.poke(true)
        dut.io.tlb.get.control.asid.poke(1)
        dut.io.tlb.get.control.privilege.poke(1)
        dut.io.tlb.get.control.mxr.poke(false)
        dut.io.tlb.get.control.sum.poke(false)
        dut.reset.poke(true)
        step(2)
        dut.reset.poke(false)
        step()
    }

    def step(cycles: Int = 1): Unit = {
        for (_ <- 0 until cycles) {
            for (lane <- 0 until 2) {
                val query = dut.io.forward(lane).query
                val next = Option.when(query.valid.peek().litToBoolean)(query.bits.slot.peek().litValue)
                dut.io.forward(lane).result.valid.poke(pendingForward(lane).nonEmpty)
                dut.io.forward(lane).result.bits.slot.poke(pendingForward(lane).getOrElse(BigInt(0)))
                dut.io.forward(lane).result.bits.data.poke(0)
                dut.io.forward(lane).result.bits.mask.poke(0)
                dut.io.forward(lane).result.bits.blocked.poke(false)
                pendingForward(lane) = next
            }
            dut.clock.step()
        }
    }

    def refill(
        vaddr: BigInt,
        ppn: BigInt,
        read: Boolean = true,
        write: Boolean = false,
        dirty: Boolean = false,
        pma: Int = 0,
        superpage: Boolean = false,
    ): Unit = {
        dut.io.tlb.get.refill.valid.poke(true)
        dut.io.tlb.get.refill.bits.vpn.poke(vaddr >> 12)
        dut.io.tlb.get.refill.bits.ppn.poke(ppn)
        dut.io.tlb.get.refill.bits.asid.poke(1)
        dut.io.tlb.get.refill.bits.global.poke(false)
        dut.io.tlb.get.refill.bits.pma.poke(pma)
        dut.io.tlb.get.refill.bits.permissions.read.poke(read)
        dut.io.tlb.get.refill.bits.permissions.write.poke(write)
        dut.io.tlb.get.refill.bits.permissions.execute.poke(false)
        dut.io.tlb.get.refill.bits.permissions.user.poke(false)
        dut.io.tlb.get.refill.bits.permissions.accessed.poke(true)
        dut.io.tlb.get.refill.bits.permissions.dirty.poke(dirty)
        dut.io.tlb.get.refill.bits.superpage.poke(superpage)
        step()
        dut.io.tlb.get.refill.valid.poke(false)
    }

    def presentLoad(lane: Int, vaddr: BigInt, slot: Int): Unit = {
        dut.io.load(lane).req.valid.poke(true)
        dut.io.load(lane).req.bits.vaddr.poke(vaddr)
        dut.io.load(lane).req.bits.paddr.poke(0)
        dut.io.load(lane).req.bits.slot.poke(slot)
        dut.io.load(lane).req.bits.mtype.poke(2)
        dut.io.load(lane).req.bits.uncache.poke(false)
        dut.io.load(lane).req.bits.ioAuthorized.poke(false)
        dut.io.load(lane).req.bits.exception.poke(0)
        dut.io.load(lane).req.bits.translationMiss.poke(false)
    }
}

class DCacheTLBIntegrationSpec extends AnyFreeSpec with ChiselSim {
    "DTLB reports both lane misses and retries without accessing L2" in {
        simulate(new DCache(DualPortRamBackend.Registers, DCacheParams(), tlbEnabled = true)) { dut =>
            val d = new DCacheTLBIntegrationDriver(dut)
            d.initialize()
            val va0 = BigInt("40123004", 16)
            val va1 = BigInt("51234008", 16)
            d.presentLoad(0, va0, 1)
            d.presentLoad(1, va1, 2)
            dut.io.load(0).req.ready.expect(true)
            dut.io.load(1).req.ready.expect(true)
            dut.io.tlbMiss.get(0).valid.expect(true)
            dut.io.tlbMiss.get(0).bits.vaddr.expect(va0)
            dut.io.tlbMiss.get(1).valid.expect(true)
            dut.io.tlbMiss.get(1).bits.vaddr.expect(va1)
            d.step()
            dut.io.load.foreach(_.req.valid.poke(false))

            var responses = Set.empty[Int]
            for (_ <- 0 until 8) {
                dut.io.l2.req.valid.expect(false)
                for (lane <- 0 until 2 if dut.io.load(lane).rsp.valid.peek().litToBoolean) {
                    dut.io.load(lane).rsp.bits.retry.expect(true)
                    dut.io.load(lane).rsp.bits.exception.expect(0)
                    responses += lane
                }
                d.step()
            }
            assert(responses == Set(0, 1), s"missing retry responses: $responses")
        }
    }

    "DTLB refill supplies the PA tag and enforces load/store permissions" in {
        simulate(new DCache(DualPortRamBackend.Registers, DCacheParams(), tlbEnabled = true)) { dut =>
            val d = new DCacheTLBIntegrationDriver(dut)
            d.initialize()
            val va = BigInt("62345004", 16)
            val ppn = BigInt("31234", 16)
            d.refill(va, ppn)
            d.presentLoad(0, va, 3)
            dut.io.tlbMiss.get(0).valid.expect(false)
            d.step()
            dut.io.load(0).req.valid.poke(false)

            var lowerSeen = false
            for (_ <- 0 until 10 if !lowerSeen) {
                if (dut.io.l2.req.valid.peek().litToBoolean) {
                    dut.io.l2.req.bits.paddr.expect(((ppn << 12) | (va & 0xfff)) & ~BigInt(31))
                    lowerSeen = true
                }
                d.step()
            }
            assert(lowerSeen, "translated cache miss did not reach L2")
        }

        simulate(new DCache(DualPortRamBackend.Registers, DCacheParams(), tlbEnabled = true)) { dut =>
            val d = new DCacheTLBIntegrationDriver(dut)
            d.initialize()
            val deniedVa = BigInt("73456000", 16)
            d.refill(deniedVa, BigInt("24567", 16), read = false)
            d.presentLoad(0, deniedVa, 4)
            d.step()
            dut.io.load(0).req.valid.poke(false)
            var faultSeen = false
            for (_ <- 0 until 8) {
                dut.io.l2.req.valid.expect(false)
                if (dut.io.load(0).rsp.valid.peek().litToBoolean) {
                    dut.io.load(0).rsp.bits.exception.expect(13)
                    dut.io.load(0).rsp.bits.retry.expect(false)
                    faultSeen = true
                }
                d.step()
            }
            assert(faultSeen, "load page fault response was not produced")

            val storeVa = BigInt("7456700c", 16)
            val storePpn = BigInt("35678", 16)
            d.refill(storeVa, storePpn, write = true, dirty = true, pma = 1)
            dut.io.storeTranslation.get.request.valid.poke(true)
            dut.io.storeTranslation.get.request.bits.vaddr.poke(storeVa)
            dut.io.storeTranslation.get.request.bits.uncache.poke(false)
            dut.io.storeTranslation.get.request.bits.exception.poke(0)
            dut.io.storeTranslation.get.response.miss.expect(false)
            dut.io.storeTranslation.get.response.paddr.expect((storePpn << 12) | (storeVa & 0xfff))
            dut.io.storeTranslation.get.response.uncache.expect(true)
            dut.io.storeTranslation.get.response.exception.expect(0)

            dut.io.storeTranslation.get.request.bits.vaddr.poke(deniedVa)
            dut.io.storeTranslation.get.response.miss.expect(false)
            dut.io.storeTranslation.get.response.exception.expect(15)
        }
    }

    "lane 1 load has priority over STA and a 4 MiB entry translates the held STA" in {
        simulate(new DCache(DualPortRamBackend.Registers, DCacheParams(), tlbEnabled = true)) { dut =>
            val d = new DCacheTLBIntegrationDriver(dut)
            d.initialize()
            val loadVa = BigInt("45678004", 16)
            d.refill(loadVa, BigInt("23456", 16))
            val storeVa = BigInt("58abc00c", 16)
            val superPpn1 = BigInt("2aa", 16)
            d.refill(storeVa, superPpn1 << 10, write = true, dirty = true, superpage = true)

            d.presentLoad(1, loadVa, 5)
            dut.io.storeTranslation.get.request.valid.poke(true)
            dut.io.storeTranslation.get.request.bits.vaddr.poke(storeVa)
            dut.io.storeTranslation.get.request.bits.uncache.poke(false)
            dut.io.storeTranslation.get.request.bits.exception.poke(0)
            dut.io.storeTranslation.get.response.miss.expect(true)
            dut.io.tlbMiss.get(1).valid.expect(false)
            d.step()

            dut.io.load(1).req.valid.poke(false)
            dut.io.storeTranslation.get.response.miss.expect(false)
            dut.io.storeTranslation.get.response.paddr.expect((superPpn1 << 22) | (storeVa & 0x3fffff))
            dut.io.storeTranslation.get.response.exception.expect(0)
        }
    }
}
