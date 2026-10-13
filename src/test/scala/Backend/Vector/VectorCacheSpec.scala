import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.DCacheParams

class VectorCacheSpec extends AnyFreeSpec with ChiselSim {
    "shared DCache carries whole lines on miss/hit and merges masked wide stores" in {
        simulate(new DCache(p = DCacheParams(directPhysical = true))) { dut =>
            for (load <- dut.io.load) {
                VectorTestUtils.clear(load.req.bits); load.req.valid.poke(false)
            }
            VectorTestUtils.clear(dut.io.store.req.bits); dut.io.store.req.valid.poke(false)
            for (forward <- dut.io.forward) {
                VectorTestUtils.clear(forward.result.bits); forward.result.valid.poke(false)
            }
            VectorTestUtils.clear(dut.io.l2.rsp.bits); dut.io.l2.rsp.valid.poke(false)
            dut.io.store.rsp.ready.poke(true); dut.io.l2.req.ready.poke(true)
            dut.io.maintenance.request.poke(false); dut.io.maintenance.invalidate.poke(false)
            dut.io.flush.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val original = (0 until 64).foldLeft(BigInt(0))((value, byte) => value | (BigInt(byte + 1) << (byte * 8)))
            var response: Option[(Int, BigInt)] = None
            var requests = 0
            var cycle = 0
            def tick(): Unit = {
                dut.io.l2.rsp.valid.poke(response.exists(_._1 <= cycle))
                dut.io.l2.rsp.bits.data.poke(response.map(_._2).getOrElse(BigInt(0)))
                dut.io.l2.rsp.bits.error.poke(false); dut.io.l2.rsp.bits.dirty.poke(false)
                if (dut.io.l2.rsp.valid.peek().litToBoolean && dut.io.l2.rsp.ready.peek().litToBoolean) response = None
                if (dut.io.l2.req.valid.peek().litToBoolean) {
                    requests += 1
                    response = Some((cycle + 5, original))
                }
                dut.clock.step(); cycle += 1
            }
            def load(address: Long): BigInt = {
                val req = dut.io.load(0).req
                VectorTestUtils.clear(req.bits)
                req.bits.wide.poke(true); req.bits.paddr.poke(address); req.bits.vaddr.poke(address)
                req.bits.mtype.poke(2); req.valid.poke(true)
                var wait = 0
                while (!req.ready.peek().litToBoolean && wait < 100) { tick(); wait += 1 }
                assert(wait < 100); tick(); req.valid.poke(false)
                wait = 0
                while (!dut.io.load(0).rsp.valid.peek().litToBoolean && wait < 100) { tick(); wait += 1 }
                assert(wait < 100)
                dut.io.load(0).rsp.bits.wide.expect(true)
                dut.io.load(0).rsp.bits.retry.expect(false)
                dut.io.load(0).rsp.bits.exception.expect(0)
                val result = dut.io.load(0).rsp.bits.line.peek().litValue
                tick(); result
            }
            val address = 0x80000400L
            assert(load(address) == original)
            assert(requests == 1)
            assert(load(address) == original && requests == 1)
            val update = BigInt("a5" * 64, 16)
            val mask = BigInt("0000ffff0000ffff", 16)
            val store = dut.io.store.req
            VectorTestUtils.clear(store.bits)
            store.bits.wide.poke(true); store.bits.paddr.poke(address)
            store.bits.line.poke(update); store.bits.lineMask.poke(mask); store.bits.size.poke(2)
            store.valid.poke(true)
            while (!store.ready.peek().litToBoolean) tick()
            tick(); store.valid.poke(false)
            var wait = 0
            while (!dut.io.store.rsp.valid.peek().litToBoolean && wait < 100) { tick(); wait += 1 }
            assert(wait < 100); dut.io.store.rsp.bits.exception.expect(0); tick()
            val expected = (0 until 64).foldLeft(original) { (value, byte) =>
                if (!mask.testBit(byte)) value else {
                    val bits = BigInt(255) << (byte * 8)
                    (value & ~bits) | (update & bits)
                }
            }
            assert(load(address) == expected && requests == 1)
            // A wide store miss installs the same byte merge, through the shared MSHR.
            store.bits.paddr.poke(address + 64); store.valid.poke(true)
            while (!store.ready.peek().litToBoolean) tick()
            tick(); store.valid.poke(false); wait = 0
            while (!dut.io.store.rsp.valid.peek().litToBoolean && wait < 100) { tick(); wait += 1 }
            assert(wait < 100); dut.io.store.rsp.bits.exception.expect(0); tick()
            assert(load(address + 64) == expected && requests == 2)
        }
    }
}
