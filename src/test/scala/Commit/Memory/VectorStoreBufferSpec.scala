import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

/** Uses the same request selection and response ownership as Commit. */
class VectorStorePipelineHarness extends Module {
    val io = IO(new Bundle {
        val enqueue = Flipped(Decoupled(new CommittedStore))
        val collect = Flipped(Decoupled(new VectorStoreTask))
        val commit = Input(Vec(3,Valid(UInt(ZirconConfig.BackendParams().sqWidth.W))))
        val cache = new StoreBufferCacheIO
        val empty = Output(Bool())
    })
    val scalar = Module(new StoreBuffer(cp=ZirconConfig.CommitParams(vectorEnabled=true)))
    val vector = Module(new VectorStoreBuffer)
    val port = Module(new SharedStorePort)
    scalar.io.enqueue <> io.enqueue
    scalar.io.query.foreach { query => query.request := 0.U.asTypeOf(query.request) }
    vector.io.collect <> io.collect
    vector.io.commit := io.commit
    vector.io.flush := false.B
    vector.io.loadLine := 0.U
    vector.io.loadTail := 12.U
    vector.io.scalarLoadTail.foreach(_ := 12.U)
    vector.io.scalarLoadLine.foreach(_ := 0.U)
    scalar.io.scalarVectorClear.get := vector.io.scalarLoadClear
    vector.io.drain <> scalar.io.vector.get
    port.io.reserveAtomic := false.B
    port.io.atomicRequest.valid := false.B
    port.io.atomicRequest.bits := 0.U.asTypeOf(new DStoreRequest)
    port.io.atomicResponse.ready := true.B
    port.io.committedRequest.valid := scalar.io.store.request.valid || vector.io.cache.request.valid
    port.io.committedRequest.bits := Mux(vector.io.cache.request.valid,
        vector.io.cache.request.bits,scalar.io.store.request.bits)
    scalar.io.store.request.ready := port.io.committedRequest.ready
    vector.io.cache.request.ready := port.io.committedRequest.ready
    scalar.io.store.response.valid := port.io.committedResponse.valid && !vector.io.active
    scalar.io.store.response.bits := port.io.committedResponse.bits
    vector.io.cache.response.valid := port.io.committedResponse.valid && vector.io.active
    vector.io.cache.response.bits := port.io.committedResponse.bits
    port.io.committedResponse.ready := Mux(vector.io.active,
        vector.io.cache.response.ready,scalar.io.store.response.ready)
    io.cache.request <> port.io.cacheRequest
    port.io.cacheResponse <> io.cache.response
    io.empty := scalar.io.empty && vector.io.empty
}

class VectorStoreBufferSpec extends AnyFreeSpec with ChiselSim {
    "scattered descriptors share wide storage and drain masked elements only after commit" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.loadLine.poke(0)
            dut.io.loadTail.poke(12)
            dut.io.scalarLoadTail.foreach(_.poke(12))
            dut.io.scalarLoadLine.foreach(_.poke(0))
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
                        // Exercise first/second fragments, including the 32-bit wrap.
                        val address = if (group == 0) 0x8000303fL + (32 - position) * 128
                            else 0xffffffc0L + ((position * 13 + 63) & 63)
                        task.element.poke(position); task.address.poke(address)
                        dut.io.collect.ready.expect(true); dut.io.cache.request.valid.expect(false); dut.clock.step()
                        if (position % (3 * bytes) != bytes) for (byte <- 0 until bytes)
                            expected((address + byte) & 0xffffffffL) = (group * 17 + position + byte) & 255
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
            dut.io.loadLine.poke(0)
            dut.io.loadTail.poke(12)
            dut.io.scalarLoadTail.foreach(_.poke(12))
            dut.io.scalarLoadLine.foreach(_.poke(0))
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
            dut.io.loadLine.poke(0)
            dut.io.loadTail.poke(12)
            dut.io.scalarLoadTail.foreach(_.poke(12))
            dut.io.scalarLoadLine.foreach(_.poke(0))
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
            var drained = false
            while (!drained && cycle < 300) {
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
                drained = dut.io.drain.done.peek().litToBoolean
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
    "single-group Stores queue in unused payload groups and recover an uncommitted suffix" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.loadLine.poke(0)
            dut.io.loadTail.poke(12)
            dut.io.scalarLoadTail.foreach(_.poke(12))
            dut.io.scalarLoadLine.foreach(_.poke(0))
            dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { event => event.valid.poke(false); event.bits.poke(0) }
            dut.io.drain.start.valid.poke(false); dut.io.drain.start.bits.poke(true)
            dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.cache.response.bits.exception.poke(0)
            dut.io.flush.poke(false); dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            def send(identity: Int): Unit = {
                val task = dut.io.collect.bits
                task.sqIdx.poke(identity); task.robIdx.poke(identity)
                task.index.poke(0); task.last.poke(true)
                task.address.poke(0xb0000000L + identity * 96L)
                task.data.poke(BigInt(f"${identity}%02x" * 32, 16))
                task.mask.poke(BigInt("f7ffefff",16))
                task.scattered.poke(false); task.addressOnly.poke(false)
                dut.io.collect.valid.poke(true); dut.io.collect.ready.expect(true)
                dut.io.cache.request.valid.expect(false)
                dut.clock.step(); dut.io.collect.valid.poke(false)
            }
            def retire(identities: Int*): Unit = {
                for ((identity, lane) <- identities.zipWithIndex) {
                    dut.io.commit(lane).valid.poke(true); dut.io.commit(lane).bits.poke(identity)
                }
                dut.clock.step(); dut.io.commit.foreach(_.valid.poke(false))
            }
            def drain(identity: Int): Unit = {
                dut.io.drain.start.ready.expect(true)
                dut.io.drain.start.valid.poke(true); dut.clock.step(); dut.io.drain.start.valid.poke(false)
                var cycle = 0
                var replyAt = -1
                var writes = Vector.empty[(BigInt,BigInt,BigInt)]
                var held: Option[(BigInt,BigInt,BigInt)] = None
                var drained = false
                while (!drained && cycle < 100) {
                    val ready = cycle % 4 != 1
                    dut.io.cache.request.ready.poke(ready)
                    dut.io.cache.response.valid.poke(replyAt == cycle)
                    val bits = dut.io.cache.request.bits
                    val signature = (bits.paddr.peek().litValue,bits.line.peek().litValue,bits.lineMask.peek().litValue)
                    val valid = dut.io.cache.request.valid.peek().litToBoolean
                    held.foreach(old => assert(valid && signature == old))
                    held = if (valid && !ready) Some(signature) else None
                    if (valid && ready) {
                        assert(replyAt < 0)
                        writes :+= signature
                        replyAt = cycle + 3
                    }
                    if (replyAt == cycle && dut.io.cache.response.ready.peek().litToBoolean) replyAt = -1
                    drained = dut.io.drain.done.peek().litToBoolean
                    dut.clock.step(); cycle += 1
                }
                assert(cycle < 100 && writes.size == 1)
                val address = BigInt(0xb0000000L + identity * 96L)
                val offset = (address & 63).toInt
                assert(writes.head._1 == (address & ~BigInt(63)))
                assert(writes.head._2 == (BigInt(f"${identity}%02x" * 32,16) << (offset * 8)))
                assert(writes.head._3 == (BigInt("f7ffefff",16) << offset))
                dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
                dut.clock.step()
            }
            for (identity <- 1 to 8) send(identity)
            for (identity <- 1 to 8) {
                dut.io.loadLine.poke((0xb0000000L + identity * 96L) >> 6)
                dut.io.loadClear.expect(false)
            }
            dut.io.loadLine.poke(0xb0040000L >> 6); dut.io.loadClear.expect(true)
            dut.io.scalarLoadLine(0).poke(0xb0040000L >> 6)
            dut.io.scalarLoadClear(0).expect(true)
            dut.io.scalarLoadLine(1).poke((0xb0000000L+96L) >> 6)
            dut.io.scalarLoadClear(1).expect(false)
            // An older Load can precede a matching younger speculative Store.
            dut.io.scalarLoadTail(1).poke(1); dut.io.scalarLoadClear(1).expect(true)
            dut.io.scalarLoadTail(1).poke(2); dut.io.scalarLoadClear(1).expect(false)
            dut.io.scalarLoadTail(1).poke(12)
            dut.io.collect.bits.sqIdx.poke(9); dut.io.collect.valid.poke(true)
            dut.io.collect.ready.expect(false); dut.clock.step(3); dut.io.collect.valid.poke(false)
            retire(1,2,3); retire(4,5,6); retire(7,8)
            dut.io.scalarLoadTail(1).poke(1); dut.io.scalarLoadClear(1).expect(false)
            dut.io.scalarLoadTail(1).poke(12)
            drain(1)
            dut.io.loadLine.poke((0xb0000000L + 96L) >> 6); dut.io.loadClear.expect(true)
            send(9); retire(9)
            for (identity <- 2 to 9) drain(identity)
            dut.io.empty.expect(true)
            // Keep a retired prefix, including a retirement on the recovery
            // edge; drop only later speculative payloads and reuse their slots.
            for (identity <- 10 to 14) send(identity)
            retire(10,11)
            dut.io.commit(0).valid.poke(true); dut.io.commit(0).bits.poke(12)
            dut.io.flush.poke(true); dut.clock.step()
            dut.io.flush.poke(false); dut.io.commit(0).valid.poke(false)
            dut.io.loadLine.poke((0xb0000000L + 12*96L) >> 6); dut.io.loadClear.expect(false)
            dut.io.loadLine.poke((0xb0000000L + 13*96L) >> 6); dut.io.loadClear.expect(true)
            for (identity <- 10 to 12) drain(identity)
            dut.io.empty.expect(true)
            for (identity <- 15 to 19) send(identity)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
            dut.io.empty.expect(true); dut.io.cache.request.valid.expect(false)
            send(20); retire(20); drain(20)
            dut.io.empty.expect(true)
            // Cached vector Stores can alias a native SRAM window outside
            // this core. Keep them conservative even at a different line.
            dut.io.collect.bits.sqIdx.poke(21); dut.io.collect.bits.robIdx.poke(21)
            dut.io.collect.bits.address.poke(0x80001000L)
            dut.io.collect.valid.poke(true); dut.clock.step(); dut.io.collect.valid.poke(false)
            dut.io.loadTail.poke(22)
            dut.io.loadLine.poke(0xb0040000L >> 6); dut.io.loadClear.expect(false)
            dut.io.loadTail.poke(20); dut.io.loadClear.expect(true)
            dut.io.loadTail.poke(0); dut.io.loadClear.expect(false)
            dut.io.loadTail.poke(22)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
            dut.io.loadClear.expect(true)
        }
    }

    "retired native words sustain one Store per cycle through the existing SB grant" in {
        simulate(new VectorStorePipelineHarness) { dut =>
            VectorTestUtils.clear(dut.io.enqueue.bits); VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.enqueue.valid.poke(false); dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { p => p.valid.poke(false); p.bits.poke(0) }
            dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.cache.response.bits.exception.poke(0)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for(index <- 0 until 4) {
                val task=dut.io.collect.bits
                task.sqIdx.poke(index+1); task.robIdx.poke(index+1)
                task.index.poke(0); task.last.poke(true); task.address.poke(0xb0001000L+index*32)
                task.data.poke(BigInt(f"${index+1}%02x"*32,16)); task.mask.poke(BigInt("ffffffff",16))
                dut.io.collect.valid.poke(true); dut.io.collect.ready.expect(true); dut.clock.step()
            }
            dut.io.collect.valid.poke(false)
            for(i <- 0 until 3) { dut.io.commit(i).valid.poke(true); dut.io.commit(i).bits.poke(i+1) }
            dut.clock.step(); dut.io.commit.foreach(_.valid.poke(false))
            dut.io.commit(0).valid.poke(true); dut.io.commit(0).bits.poke(4)
            dut.clock.step(); dut.io.commit(0).valid.poke(false)
            for(_ <- 0 until 4) {
                dut.io.enqueue.bits.vector.poke(true); dut.io.enqueue.valid.poke(true)
                dut.io.enqueue.ready.expect(true); dut.clock.step()
            }
            dut.io.enqueue.valid.poke(false)
            var pending:Option[Int]=None
            var cycles=Vector.empty[Int]
            var cycle=0
            while((!dut.io.empty.peek().litToBoolean || pending.nonEmpty) && cycle<40) {
                dut.io.cache.request.ready.poke(true)
                val reply=pending.contains(cycle)
                dut.io.cache.response.valid.poke(reply)
                if(reply) { dut.io.cache.response.ready.expect(true);pending=None }
                if(dut.io.cache.request.valid.peek().litToBoolean) {
                    assert(pending.isEmpty)
                    val index=cycles.size
                    dut.io.cache.request.bits.paddr.expect((0xb0001000L+index*32)& ~63L)
                    val offset=if(index%2==0)0 else 256
                    dut.io.cache.request.bits.line.expect(BigInt(f"${index+1}%02x"*32,16)<<offset)
                    dut.io.cache.request.bits.lineMask.expect(BigInt("ffffffff",16)<<(offset/8))
                    cycles :+= cycle;pending=Some(cycle+1)
                }
                dut.clock.step();cycle+=1
            }
            assert(cycles.size==4 && cycles.sliding(2).forall(p=>p(1)==p(0)+1),s"Native Store bubbles: $cycles")
        }
    }

    "the shared Store port hands acknowledged wide tokens to scalar and queued vector successors" in {
        simulate(new VectorStorePipelineHarness) { dut =>
            VectorTestUtils.clear(dut.io.enqueue.bits); dut.io.enqueue.valid.poke(false)
            VectorTestUtils.clear(dut.io.collect.bits); dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { p => p.valid.poke(false); p.bits.poke(0) }
            dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.cache.response.bits.exception.poke(0)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val addresses=Seq(0xb0000100L,0xb0000120L,0xb0000180L)
            for (index <- 0 until 3) {
                val task=dut.io.collect.bits
                task.sqIdx.poke(index+1); task.robIdx.poke(index+1)
                task.index.poke(0); task.last.poke(true); task.address.poke(addresses(index))
                task.data.poke(BigInt(f"${index+1}%02x"*32,16)); task.mask.poke(BigInt("ffffffff",16))
                dut.io.collect.valid.poke(true); dut.io.collect.ready.expect(true); dut.clock.step()
                dut.io.collect.valid.poke(false)
                dut.io.commit(index).valid.poke(true); dut.io.commit(index).bits.poke(index+1)
            }
            dut.clock.step(); dut.io.commit.foreach(_.valid.poke(false))
            val tokens=Seq((true,0L,0),(false,0x9000L,11),(true,0L,0),(true,0L,0))
            for ((wide,address,data) <- tokens) {
                dut.io.enqueue.bits.vector.poke(wide); dut.io.enqueue.bits.paddr.poke(address)
                dut.io.enqueue.bits.data.poke(data); dut.io.enqueue.bits.mask.poke(15)
                dut.io.enqueue.bits.size.poke(2); dut.io.enqueue.valid.poke(true)
                dut.io.enqueue.ready.expect(true); dut.clock.step(); dut.io.enqueue.valid.poke(false)
            }
            var pending: Option[Int]=None
            var observed=Vector.empty[(Boolean,BigInt,BigInt,BigInt)]
            var held: Option[(Boolean,BigInt,BigInt,BigInt)]=None
            var cycle=0
            while ((!dut.io.empty.peek().litToBoolean || pending.nonEmpty) && cycle < 100) {
                val reply=pending.contains(cycle)
                dut.io.cache.response.valid.poke(reply)
                val ready=(pending.isEmpty || reply) && cycle%5!=1
                dut.io.cache.request.ready.poke(ready)
                val request=dut.io.cache.request.bits
                val valid=dut.io.cache.request.valid.peek().litToBoolean
                val wide=request.wide.peek().litToBoolean
                val signature=(wide,request.paddr.peek().litValue,
                    if(wide) request.line.peek().litValue else request.data.peek().litValue,
                    if(wide) request.lineMask.peek().litValue else request.mask.peek().litValue)
                held.foreach(old=>assert(valid && signature==old))
                held=if(valid && !ready)Some(signature)else None
                if(reply) { dut.io.cache.response.ready.expect(true); pending=None }
                if(valid && ready) {
                    assert(pending.isEmpty)
                    observed :+= signature; pending=Some(cycle+2)
                }
                dut.clock.step(); cycle+=1
            }
            assert(cycle<100 && observed.size==4)
            assert(observed.map(_._1)==Vector(true,false,true,true))
            assert(observed(1)==((false,BigInt(0x9000),BigInt(11),BigInt(15))))
            for ((entry,index) <- Seq(observed(0),observed(2),observed(3)).zipWithIndex) {
                val offset=(addresses(index)&63).toInt
                assert(entry._2==BigInt(addresses(index) & ~63L))
                assert(entry._3==(BigInt(f"${index+1}%02x"*32,16)<<(offset*8)))
                assert(entry._4==(BigInt("ffffffff",16)<<offset))
            }
        }
    }

}
