import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{FrontendParams, ICacheParams}

class ICacheTLBIntegrationDriver(val dut: ICache) extends chisel3.simulator.PeekPokeAPI {
    def initialize(): Unit = {
        dut.io.flush.poke(false)
        dut.io.pp.request.valid.poke(false)
        dut.io.pp.request.bits.pc.poke(0)
        dut.io.pp.request.bits.token.poke(0)
        dut.io.pp.response.ready.poke(true)
        dut.io.mmu.response.valid.poke(false)
        dut.io.mmu.response.bits.token.poke(0)
        dut.io.mmu.response.bits.paddr.poke(0)
        dut.io.mmu.response.bits.uncache.poke(false)
        dut.io.mmu.response.bits.fault.poke(false)
        dut.io.l2.request.ready.poke(false)
        dut.io.l2.response.valid.poke(false)
        dut.io.l2.response.bits.token.poke(0)
        dut.io.l2.response.bits.data.poke(0)
        dut.io.l2.response.bits.error.poke(false)
        dut.io.tlb.get.control.enabled.poke(true)
        dut.io.tlb.get.control.asid.poke(1)
        dut.io.tlb.get.control.privilege.poke(1)
        dut.io.tlb.get.control.mxr.poke(false)
        dut.io.tlb.get.control.sum.poke(false)
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
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
        dut.clock.step()
    }

    def refill(vaddr: BigInt, ppn: BigInt, execute: Boolean = true, pma: Int = 0): Unit = {
        dut.io.tlb.get.refill.valid.poke(true)
        dut.io.tlb.get.refill.bits.vpn.poke(vaddr >> 12)
        dut.io.tlb.get.refill.bits.ppn.poke(ppn)
        dut.io.tlb.get.refill.bits.asid.poke(1)
        dut.io.tlb.get.refill.bits.global.poke(false)
        dut.io.tlb.get.refill.bits.pma.poke(pma)
        dut.io.tlb.get.refill.bits.permissions.read.poke(true)
        dut.io.tlb.get.refill.bits.permissions.write.poke(false)
        dut.io.tlb.get.refill.bits.permissions.execute.poke(execute)
        dut.io.tlb.get.refill.bits.permissions.user.poke(false)
        dut.io.tlb.get.refill.bits.permissions.accessed.poke(true)
        dut.io.tlb.get.refill.bits.permissions.dirty.poke(false)
        dut.io.tlb.get.refill.bits.superpage.poke(false)
        dut.clock.step()
        dut.io.tlb.get.refill.valid.poke(false)
    }

    def accept(pc: BigInt, token: BigInt): Unit = {
        dut.io.pp.request.valid.poke(true)
        dut.io.pp.request.bits.pc.poke(pc)
        dut.io.pp.request.bits.token.poke(token)
        dut.io.pp.request.ready.expect(true)
        dut.clock.step()
        dut.io.pp.request.valid.poke(false)
    }
}

class ICacheTLBIntegrationSpec extends AnyFreeSpec with ChiselSim {
    "ITLB hit bypasses the miss service and supplies a 34-bit physical address" in {
        val c = ICacheParams(tlbEnabled = true)
        simulate(new ICache(FrontendParams(), c)) { dut =>
            val d = new ICacheTLBIntegrationDriver(dut)
            d.initialize()
            val va = BigInt("81234000", 16)
            val ppn = BigInt("31234", 16)
            d.refill(va, ppn)
            d.accept(va, 7)

            var lowerSeen = false
            for (_ <- 0 until 10 if !lowerSeen) {
                dut.io.mmu.request.valid.expect(false)
                if (dut.io.l2.request.valid.peek().litToBoolean) {
                    dut.io.l2.request.bits.paddr.expect((ppn << 12) & ~BigInt(c.lineBytes - 1))
                    dut.io.l2.request.bits.token.expect(7)
                    lowerSeen = true
                }
                dut.clock.step()
            }
            assert(lowerSeen, "ITLB-translated instruction miss did not reach L2")
        }
    }

    "ITLB miss preserves the request identity and execute denial faults locally" in {
        val c = ICacheParams(tlbEnabled = true)
        simulate(new ICache(FrontendParams(), c)) { dut =>
            val d = new ICacheTLBIntegrationDriver(dut)
            d.initialize()
            val missVa = BigInt("82345000", 16)
            d.accept(missVa, 9)
            dut.io.mmu.request.valid.expect(true)
            dut.io.mmu.request.bits.pc.expect(missVa)
            dut.io.mmu.request.bits.token.expect(9)
            dut.io.l2.request.valid.expect(false)

            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            val deniedVa = BigInt("83456000", 16)
            d.refill(deniedVa, BigInt("24567", 16), execute = false)
            d.accept(deniedVa, 10)
            var faultSeen = false
            for (_ <- 0 until 8) {
                dut.io.mmu.request.valid.expect(false)
                dut.io.l2.request.valid.expect(false)
                if (dut.io.pp.response.valid.peek().litToBoolean) {
                    dut.io.pp.response.bits.token.expect(10)
                    dut.io.pp.response.bits.fault.expect(15)
                    faultSeen = true
                }
                dut.clock.step()
            }
            assert(faultSeen, "ITLB execute permission fault was not returned")
        }
    }

    "bare mode bypasses the ITLB and a device mapping uses uncached reads" in {
        val c = ICacheParams(tlbEnabled = true)
        simulate(new ICache(FrontendParams(), c)) { dut =>
            val d = new ICacheTLBIntegrationDriver(dut)
            d.initialize()
            val bare = BigInt("80001000", 16)
            dut.io.tlb.get.control.enabled.poke(false)
            d.accept(bare, 11)
            var bareSeen = false
            for (_ <- 0 until 10 if !bareSeen) {
                dut.io.mmu.request.valid.expect(false)
                if (dut.io.l2.request.valid.peek().litToBoolean) {
                    dut.io.l2.request.bits.paddr.expect(bare)
                    dut.io.l2.request.bits.uncache.expect(false)
                    bareSeen = true
                }
                dut.clock.step()
            }
            assert(bareSeen, "bare instruction request did not bypass the ITLB")
        }

        simulate(new ICache(FrontendParams(), c)) { dut =>
            val d = new ICacheTLBIntegrationDriver(dut)
            d.initialize()
            val va = BigInt("84567000", 16)
            val ppn = BigInt("25678", 16)
            d.refill(va, ppn, pma = 2)
            d.accept(va, 12)
            var deviceSeen = false
            for (_ <- 0 until 10 if !deviceSeen) {
                dut.io.mmu.request.valid.expect(false)
                if (dut.io.l2.request.valid.peek().litToBoolean) {
                    dut.io.l2.request.bits.paddr.expect(ppn << 12)
                    dut.io.l2.request.bits.uncache.expect(true)
                    deviceSeen = true
                }
                dut.clock.step()
            }
            assert(deviceSeen, "device instruction mapping did not use the uncached path")
        }
    }
}
