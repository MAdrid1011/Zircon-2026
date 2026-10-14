import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class VectorStoreBufferSpec extends AnyFreeSpec with ChiselSim {
    "scattered descriptors share wide storage and drain masked elements only after commit" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { port => port.valid.poke(false); port.bits.poke(0) }
            dut.io.drain.start.valid.poke(false); dut.io.drain.start.bits.poke(true)
            dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.cache.response.bits.exception.poke(0); dut.io.flush.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for (eew <- 0 to 2) {
                val bytes = 1 << eew
                val expected = scala.collection.mutable.Map.empty[Long, Int]
                for (group <- 0 until 2) {
                    val task = dut.io.collect.bits
                    task.scattered.poke(true); task.eew.poke(eew); task.sqIdx.poke(2)
                    task.fieldRegisterShift.poke(1); task.lastField.poke(0)
                    task.robIdx.poke(7); task.index.poke(group); task.last.poke(false)
                    task.addressOnly.poke(true); dut.io.collect.valid.poke(true)
                    for (position <- 0 until 32 by bytes) {
                        val address = 0x80003000L + group * 256 + (32 - position) * 8
                        task.element.poke(position); task.address.poke(address)
                        dut.io.collect.ready.expect(true); dut.io.cache.request.valid.expect(false); dut.clock.step()
                        if (position % (3 * bytes) != bytes) for (byte <- 0 until bytes)
                            expected(address + byte) = (group * 17 + position + byte) & 255
                    }
                    val data = (0 until 32).foldLeft(BigInt(0))((v, byte) => v | BigInt((group * 17 + byte) & 255) << (byte * 8))
                    val mask = (0 until 32).filter(byte => (byte / bytes) % 3 != 1)
                        .foldLeft(BigInt(0))((v, byte) => v.setBit(byte))
                    task.addressOnly.poke(false); task.last.poke(group == 1)
                    task.data.poke(data); task.mask.poke(mask)
                    dut.io.collect.ready.expect(true); dut.clock.step(); dut.io.collect.valid.poke(false)
                }
                dut.io.drain.start.ready.expect(false)
                dut.io.commit(0).valid.poke(true); dut.io.commit(0).bits.poke(2); dut.clock.step()
                dut.io.commit(0).valid.poke(false)
                dut.io.drain.start.valid.poke(true); dut.io.drain.start.ready.expect(true); dut.clock.step()
                dut.io.drain.start.valid.poke(false)
                val observed = scala.collection.mutable.Map.empty[Long, Int]
                var cycle = 0; var acknowledge = -1
                while (!dut.io.drain.done.peek().litToBoolean && cycle < 2000) {
                    dut.io.active.expect(true)
                    dut.io.cache.request.ready.poke(cycle % 4 == 3)
                    dut.io.cache.response.valid.poke(cycle == acknowledge)
                    dut.io.flush.poke(cycle == 7)
                    if (dut.io.cache.request.valid.peek().litToBoolean && dut.io.cache.request.ready.peek().litToBoolean) {
                        val request = dut.io.cache.request.bits
                        val address = request.paddr.peek().litValue.toLong
                        val mask = request.lineMask.peek().litValue; val data = request.line.peek().litValue
                        for (byte <- 0 until 64 if mask.testBit(byte)) observed(address + byte) = ((data >> (byte * 8)) & 255).toInt
                        acknowledge = cycle + 3
                    }
                    dut.clock.step(); cycle += 1
                }
                assert(cycle < 2000 && observed == expected, s"scatter EEW=$bytes bytes")
                dut.io.active.expect(0, "The next scalar Store owns the done cycle")
                dut.io.flush.poke(false); dut.io.cache.response.valid.poke(false); dut.clock.step()
                dut.io.empty.expect(true)
            }
        }
    }
    "ordered segment stores visit elements before fields when addresses overlap" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { port => port.valid.poke(false); port.bits.poke(0) }
            dut.io.drain.start.valid.poke(false); dut.io.drain.start.bits.poke(true)
            dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.cache.response.bits.exception.poke(0); dut.io.flush.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for (eew <- 0 to 2; fields <- 1 to 3; registerShift <- 0 to 1) {
                val bytes = 1 << eew
                val registers = 1 << registerShift
                val count = fields * registers
                val addresses = Array.ofDim[Long](count, 32)
                val values = Array.ofDim[Int](count, 32)
                def enabled(element: Int): Boolean = element % 3 != 1
                for (group <- 0 until count) {
                    val field = group / registers
                    val block = group % registers
                    val task = dut.io.collect.bits
                    task.scattered.poke(true); task.eew.poke(eew); task.sqIdx.poke(2)
                    task.fieldRegisterShift.poke(registerShift); task.lastField.poke(fields - 1)
                    task.index.poke(group); task.last.poke(false)
                    task.addressOnly.poke(true); dut.io.collect.valid.poke(true)
                    for (position <- 0 until 32 by bytes) {
                        val element = block * (32 / bytes) + position / bytes
                        val address = 0x80003000L + ((element / 2) + field) * bytes
                        task.element.poke(position); task.address.poke(address)
                        addresses(group)(position) = address
                        dut.io.collect.ready.expect(true); dut.clock.step()
                    }
                    for (byte <- 0 until 32) values(group)(byte) = (group * 37 + byte * 11) & 255
                    val data = (0 until 32).foldLeft(BigInt(0))((v, byte) => v | BigInt(values(group)(byte)) << (byte * 8))
                    val mask = (0 until 32).filter(byte => enabled(block * (32 / bytes) + byte / bytes))
                        .foldLeft(BigInt(0))((v, byte) => v.setBit(byte))
                    task.addressOnly.poke(false); task.last.poke(group == count - 1)
                    task.data.poke(data); task.mask.poke(mask)
                    dut.io.collect.ready.expect(true); dut.clock.step(); dut.io.collect.valid.poke(false)
                }
                val expected = for {
                    element <- Vector.range(0, registers * (32 / bytes)) if enabled(element)
                    field <- 0 until fields
                    byte <- 0 until bytes
                } yield {
                    val group = field * registers + element / (32 / bytes)
                    val position = (element % (32 / bytes)) * bytes
                    (addresses(group)(position) + byte, values(group)(position + byte))
                }
                dut.io.commit(0).valid.poke(true); dut.io.commit(0).bits.poke(2); dut.clock.step()
                dut.io.commit(0).valid.poke(false)
                dut.io.drain.start.valid.poke(true); dut.io.drain.start.ready.expect(true); dut.clock.step()
                dut.io.drain.start.valid.poke(false)
                val observed = scala.collection.mutable.ArrayBuffer.empty[(Long, Int)]
                var cycle = 0; var acknowledge = -1
                while (!dut.io.drain.done.peek().litToBoolean && cycle < 5000) {
                    dut.io.active.expect(true)
                    dut.io.cache.request.ready.poke(cycle % 4 == 3)
                    dut.io.cache.response.valid.poke(cycle == acknowledge)
                    if (dut.io.cache.request.valid.peek().litToBoolean && dut.io.cache.request.ready.peek().litToBoolean) {
                        val request = dut.io.cache.request.bits
                        val address = request.paddr.peek().litValue.toLong
                        val mask = request.lineMask.peek().litValue; val data = request.line.peek().litValue
                        for (byte <- 0 until 64 if mask.testBit(byte))
                            observed += ((address + byte, ((data >> (byte * 8)) & 255).toInt))
                        acknowledge = cycle + 3
                    }
                    dut.clock.step(); cycle += 1
                }
                assert(cycle < 5000 && observed.toVector == expected,
                    s"element/field order EEW=$bytes NF=$fields registers=$registers")
                dut.io.active.expect(0, "The next scalar Store owns the done cycle")
                dut.io.cache.response.valid.poke(false); dut.clock.step(); dut.io.empty.expect(true)
            }
        }
    }
    "wide data waits for retirement and ordered SB grant, and survives flush until final cache acknowledgement" in {
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
                    val address = 0x8000103cL + index * 32
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
            assert(cycle < 300 && responses > 8 && observed == expected)
            dut.io.active.expect(0, "The next scalar Store owns the done cycle")
            dut.io.collect.valid.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.flush.poke(false); dut.clock.step(); dut.io.empty.expect(true)
            collect(1, 5)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.empty.expect(true)
            info(s"Verified $responses ordered cache writes over $cycle cycles")
        }
    }
}
