import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class CacheIntegrationSpec extends AnyFreeSpec with ChiselSim {
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case s: svsim.verilator.Backend.CompilationSettings =>
            s.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case s => s
    }
    "cached lane-1 loads retain their SRAM address while a posted write blocks a normal store" in {
        simulate(new DCache(DualPortRamBackend.Registers)) { dut =>
            val driver = new DCacheDriver(dut)
            import driver._
            tick()
            issue(Some(load(0x3b20)))
            drain()
            val expectedReads = reads
            lowerReady = false
            dut.io.store.req.bits.writeCombine.poke(true)
            issue(write = Some(Store(0xa2000000L, 0x12345678, uncache = true)))
            tick()
            dut.io.store.req.bits.writeCombine.poke(false)
            val blocked = Store(0xa000002cL, 1, uncache = true)
            for (_ <- 0 until 8) {
                val accepted = tick(Seq(None, Some(load(0x3b20))), Some(blocked))
                assert(!accepted(2), "Strongly ordered doorbell must wait for the posted burst")
            }
            for (_ <- 0 until 16) {
                val accepted = tick(write = Some(blocked))
                assert(!accepted(2))
            }
            assert(expected.isEmpty, "Cached loads must complete while the doorbell is blocked")
            assert(reads == expectedReads, "Blocked Store must not turn cached hits into misses")
        }
    }
    "a posted burst waits for an earlier cached refill even when L2 accepts requests" in {
        simulate(new DCache(DualPortRamBackend.Registers)) { dut =>
            val driver = new DCacheDriver(dut)
            import driver._
            latency = 40
            tick()
            issue(Some(load(0x3b80)))
            while(transaction.isEmpty && cycles<100) tick()
            assert(transaction.nonEmpty && reads==1)
            dut.io.store.req.bits.writeCombine.poke(true)
            issue(write=Some(Store(0xa2001000L,0x12345678,uncache=true)))
            dut.io.store.req.bits.writeCombine.poke(false)
            for(_ <- 0 until 20) tick()
            assert(writes==0,"The refill retains the untagged lower response channel")
            drain()
            assert(writes==1 && loads==1 && stores==1)
            issue(None,Some(load(0x3b80)))
            drain()
            assert(reads==1,"The refill data must remain cached after the burst response")
            lowerReady=false
            issue(Some(load(0x4b80)))
            for(_ <- 0 until 8) tick()
            assert(dut.io.l2.req.valid.peek().litToBoolean)
            dut.io.store.req.bits.writeCombine.poke(true)
            issue(write=Some(Store(0xa2001008L,0x87654321L,uncache=true)))
            dut.io.store.req.bits.writeCombine.poke(false)
            for(_ <- 0 until 12) tick()
            lowerReady=true
            drain()
            assert(reads==2 && writes==2 && loads==3 && stores==2)
        }
    }
}
