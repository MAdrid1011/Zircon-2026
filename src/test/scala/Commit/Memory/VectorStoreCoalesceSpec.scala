// SPDX-License-Identifier: MPL-2.0
// Adapted from Zircon-2026 VectorStoreBufferSpec.
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class VectorStoreCoalesceSpec extends AnyFreeSpec with ChiselSim {
    for ((base,expectedRequests) <- Seq((0x80001000L,4),(0x80001020L,5))) {
    s"adjacent groups at $base coalesce with masks, backpressure and committed recovery" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.collect.valid.poke(false)
            for (port <- dut.io.commit) { port.valid.poke(false); port.bits.poke(0) }
            dut.io.drain.start.valid.poke(false); dut.io.drain.start.bits.poke(true)
            dut.io.cache.request.ready.poke(false)
            dut.io.cache.response.valid.poke(false); dut.io.cache.response.bits.exception.poke(0)
            dut.io.flush.poke(false); dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            def collect(count: Int, identity: Int): Vector[(Long, BigInt, BigInt)] = {
                val tasks = Vector.tabulate(count) { index =>
                    val address = base + index * 32
                    val data = (0 until 32).foldLeft(BigInt(0)) { (v, byte) =>
                        v | (BigInt((index * 29 + byte * 11) & 255) << (byte * 8))
                    }
                    val mask = if (index == 1) BigInt(0) else BigInt("6fdeda7b", 16)
                    (address, data, mask)
                }
                for (((address, data, mask), index) <- tasks.zipWithIndex) {
                    dut.io.collect.bits.sqIdx.poke(identity); dut.io.collect.bits.robIdx.poke(7)
                    dut.io.collect.bits.index.poke(index); dut.io.collect.bits.last.poke(index == count - 1)
                    dut.io.collect.bits.address.poke(address); dut.io.collect.bits.data.poke(data)
                    dut.io.collect.bits.mask.poke(mask); dut.io.collect.valid.poke(true)
                    dut.io.collect.ready.expect(true); dut.clock.step()
                    dut.io.cache.request.valid.expect(false)
                }
                dut.io.collect.valid.poke(false)
                tasks
            }
            collect(2, 2)
            dut.io.drain.start.ready.expect(false)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
            dut.io.empty.expect(true)
            val tasks = collect(8, 4)
            dut.io.commit(0).valid.poke(true); dut.io.commit(0).bits.poke(4)
            dut.io.flush.poke(true); dut.clock.step()
            dut.io.commit(0).valid.poke(false); dut.io.flush.poke(false)
            dut.io.empty.expect(false); dut.io.drain.start.ready.expect(true)
            for (_ <- 0 until 3) { dut.io.cache.request.valid.expect(false); dut.clock.step() }
            dut.io.drain.start.valid.poke(true); dut.clock.step(); dut.io.drain.start.valid.poke(false)
            val expected = scala.collection.mutable.Map.empty[Long, Int]
            for ((address, data, mask) <- tasks; byte <- 0 until 32 if mask.testBit(byte))
                expected(address + byte) = ((data >> (byte * 8)) & 255).toInt
            val observed = scala.collection.mutable.Map.empty[Long, Int]
            var cycle = 0
            var responses = 0
            var acknowledge = -1
            var held: Option[(BigInt, BigInt, BigInt)] = None
            while (!dut.io.drain.done.peek().litToBoolean && cycle < 300) {
                dut.io.active.expect(true)
                dut.io.cache.request.ready.poke(cycle % 5 == 4)
                dut.io.cache.response.valid.poke(cycle == acknowledge)
                if (cycle == 12 || cycle == 33) dut.io.flush.poke(true) else dut.io.flush.poke(false)
                // A younger store cannot take over the single wide payload.
                dut.io.collect.bits.sqIdx.poke(5); dut.io.collect.valid.poke(true)
                dut.io.collect.ready.expect(false)
                if (dut.io.cache.request.valid.peek().litToBoolean) {
                    val r = dut.io.cache.request.bits
                    r.wide.expect(true); r.uncache.expect(false); r.writeCombine.expect(false)
                    val current = (r.paddr.peek().litValue, r.line.peek().litValue, r.lineMask.peek().litValue)
                    held.foreach(previous => assert(previous == current, "Backpressured wide request changed"))
                    if (dut.io.cache.request.ready.peek().litToBoolean) {
                        assert(acknowledge < cycle)
                        val (address, data, mask) = current
                        assert((address & 63) == 0 && mask != 0)
                        for (byte <- 0 until 64 if mask.testBit(byte))
                            observed(address.toLong + byte) = ((data >> (byte * 8)) & 255).toInt
                        acknowledge = cycle + 4
                        held = None
                    } else held = Some(current)
                }
                if (dut.io.cache.response.valid.peek().litToBoolean) {
                    dut.io.cache.response.ready.expect(true)
                    responses += 1
                }
                dut.clock.step(); cycle += 1
            }
            assert(cycle < 300 && responses == expectedRequests && observed == expected)
            dut.io.active.expect(0, "The next scalar Store owns the done cycle")
            dut.io.collect.valid.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.flush.poke(false); dut.clock.step(); dut.io.empty.expect(true)
            collect(1, 5)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.empty.expect(true)
            info(s"Verified $responses ordered cache writes over $cycle cycles")
        }
    }
    }
}
