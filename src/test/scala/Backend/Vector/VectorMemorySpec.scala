import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class VectorMemoryHarness extends Module {
    private val cache = DCacheParams(directPhysical = true)
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorInstruction))
        val initialize = new VectorRegisterWrite(VectorParams())
        val address = Input(UInt(6.W))
        val data = Output(Vec(8, UInt(32.W)))
        val base = Input(UInt(32.W))
        val stride = Input(UInt(32.W))
        val readAvailable = Input(Bool())
        val ordered = Input(Bool())
        val store = Decoupled(new VectorStoreTask)
        val completion = Decoupled(new VectorCompletion)
        val lower = new DCacheL2IO
        val request = Output(Valid(new DLoadRequest(cache)))
        val scalarForwardQuery = Output(Bool())
        val pending = Output(Bool())
        val idle = Output(Bool())
        val writeback = Output(Valid(new VectorTaskWriteback))
        val flush = Input(Bool())
    })
    val memory = Module(new VectorMemory(cache = cache))
    val file = Module(new VectorRegisterFile)
    val dcache = Module(new DCache(p = cache))
    memory.io.in <> io.in
    memory.io.scalarData := Mux(memory.io.scalarAddress === 2.U, io.stride, io.base)
    memory.io.readAvailable := io.readAvailable
    memory.io.read <> file.io.read
    memory.io.ordering.clear := io.ordered
    memory.io.flush := io.flush
    memory.io.request <> dcache.io.load(1).req
    memory.io.response := dcache.io.load(1).rsp
    memory.io.cachePending := dcache.io.load(1).widePending
    io.store <> memory.io.store
    io.completion <> memory.io.completion
    io.writeback := memory.io.writeback
    io.idle := memory.io.idle
    io.pending := dcache.io.load(1).widePending
    io.request.valid := memory.io.request.fire
    io.request.bits := memory.io.request.bits
    io.scalarForwardQuery := dcache.io.forward(1).query.valid
    dcache.io.load(0).req.valid := false.B
    dcache.io.load(0).req.bits := 0.U.asTypeOf(new DLoadRequest(cache))
    dcache.io.forward.foreach(_.result := 0.U.asTypeOf(Valid(new DForwardResult(cache))))
    dcache.io.store.req.valid := false.B
    dcache.io.store.req.bits := 0.U.asTypeOf(new DStoreRequest)
    dcache.io.store.rsp.ready := true.B
    dcache.io.maintenance.request := false.B
    dcache.io.maintenance.invalidate := false.B
    dcache.io.flush := io.flush
    io.lower <> dcache.io.l2
    file.io.write(0).valid := memory.io.writeback.valid
    file.io.write(0).address := memory.io.writeback.bits.address
    file.io.write(0).data := memory.io.writeback.bits.data
    file.io.write(0).bytes := memory.io.writeback.bits.bytes
    file.io.write(1) := io.initialize
    val observation = Module(new VectorRegisterFile)
    observation.io.write := file.io.write
    observation.io.read.foreach { read =>
        read.address := io.address
        read.oneHot := UIntToOH(io.address, 64)
    }
    io.data := observation.io.read(0).data
}

class VectorMemorySpec extends AnyFreeSpec with ChiselSim {
    "shared cache loads and Store collection handle EEW, LMUL, mask, crossings and cancellation" in {
        simulate(new VectorMemoryHarness) { dut =>
            VectorTestUtils.clear(dut.io.in.bits); VectorTestUtils.clear(dut.io.initialize)
            VectorTestUtils.clear(dut.io.lower.rsp.bits)
            dut.io.in.valid.poke(false); dut.io.address.poke(0); dut.io.base.poke(0)
            dut.io.stride.poke(0)
            dut.io.readAvailable.poke(true); dut.io.ordered.poke(true)
            dut.io.store.ready.poke(true); dut.io.completion.ready.poke(true)
            dut.io.lower.req.ready.poke(true); dut.io.lower.rsp.valid.poke(false)
            dut.io.flush.poke(false); dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val old = BigInt("17" * 32, 16)
            val vectorMask = (0 until 256).filter(_ % 3 != 1).foldLeft(BigInt(0))((v, i) => v.setBit(i))
            for (address <- 0 until 64) {
                dut.io.initialize.valid.poke(true); dut.io.initialize.address.poke(address)
                val value = if (address == 0) vectorMask else old
                for (lane <- 0 until 8) {
                    dut.io.initialize.data(lane).poke((value >> (32 * lane)) & 0xffffffffL)
                    dut.io.initialize.bytes(lane).poke(15)
                }
                dut.clock.step()
            }
            dut.io.initialize.valid.poke(false)
            def byteAt(address: BigInt): Int = ((address * 19 + (address >> 6) * 7) & 255).toInt
            def line(address: BigInt): BigInt = (0 until 64).foldLeft(BigInt(0)) { (v, b) =>
                v | (BigInt(byteAt(address + b)) << (8 * b))
            }
            var cycle = 0
            var delay = 7
            var lowerResponse: Option[(Int, BigInt)] = None
            var stores = Vector.empty[(Int, BigInt, BigInt, BigInt, Boolean)]
            var scatterAddresses = Map.empty[(Int, Int), BigInt]
            var requests = 0
            var writes = 0
            var requestCycles = Vector.empty[Int]
            var writeCycles = Vector.empty[Int]
            def tick(): Unit = {
                assert(!dut.io.scalarForwardQuery.peek().litToBoolean,
                    "wide loads must not enter the scalar SQ/SB forwarding network")
                dut.io.lower.rsp.valid.poke(lowerResponse.exists(_._1 <= cycle))
                dut.io.lower.rsp.bits.data.poke(lowerResponse.map(_._2).getOrElse(BigInt(0)))
                if (dut.io.lower.rsp.valid.peek().litToBoolean && dut.io.lower.rsp.ready.peek().litToBoolean)
                    lowerResponse = None
                if (dut.io.lower.req.valid.peek().litToBoolean && dut.io.lower.req.ready.peek().litToBoolean) {
                    assert(lowerResponse.isEmpty)
                    lowerResponse = Some((cycle + delay, line(dut.io.lower.req.bits.paddr.peek().litValue)))
                }
                if (dut.io.request.valid.peek().litToBoolean) {
                    requests += 1
                    requestCycles :+= cycle
                }
                if (dut.io.writeback.valid.peek().litToBoolean) {
                    writes += 1
                    writeCycles :+= cycle
                }
                if (dut.io.store.valid.peek().litToBoolean && dut.io.store.ready.peek().litToBoolean) {
                    val b = dut.io.store.bits
                    if (b.addressOnly.peek().litToBoolean)
                        scatterAddresses += (b.index.peek().litValue.toInt, b.element.peek().litValue.toInt) -> b.address.peek().litValue
                    else stores :+= ((b.index.peek().litValue.toInt, b.address.peek().litValue,
                        b.data.peek().litValue, b.mask.peek().litValue, b.last.peek().litToBoolean))
                }
                dut.clock.step(); cycle += 1
            }
            def waitUntil(condition: => Boolean, maximum: Int = 3000): Unit = {
                var waited = 0
                while (!condition && waited < maximum) { tick(); waited += 1 }
                assert(waited < maximum, s"Timeout at cycle $cycle")
            }
            def begin(eew: Int, count: Int, base: Long, vl: Int, start: Int,
                      masked: Boolean, policy: Int, store: Boolean = false, stride: Option[Int] = None,
                      lumop: Int = 0, fields: Int = 1, indexWidth: Option[Int] = None): Unit = {
                val in = dut.io.in.bits
                VectorTestUtils.clear(in)
                in.scalar.robIdx.poke(7); in.scalar.sqIdx.poke(2); in.scalar.sqTail.poke(3)
                in.scalar.prs(1).poke(2)
                in.scalar.inst.poke((if (masked) 0L else 1L << 25) |
                    ((Seq(0L, 5L, 6L)(indexWidth.getOrElse(eew))) << 12) |
                    (lumop.toLong << 20) |
                    (if (indexWidth.nonEmpty) 1L << 26 else if (stride.nonEmpty) 2L << 26 else 0L) |
                    ((fields.toLong - 1) << 29) | (if (store) 0x27 else 0x07))
                in.vector.control.execution.poke(if (store) VectorExecution.Store else VectorExecution.Load)
                in.vector.destination.count.poke(if (store) 0 else count)
                in.vector.groups.sourceCount(0).poke(if (store) count else 0)
                in.vector.groups.sourceCount(2).poke(if (store) 0 else count)
                in.vector.groups.mask.poke(masked); in.vector.mask.poke(0)
                in.vector.state.vtype.poke((eew << 3) | (policy << 6) |
                    (if (count / fields == 8) 3 else if (count / fields == 4) 2 else if (count / fields == 2) 1 else 0))
                in.vector.state.vl.poke(vl); in.vector.state.vstart.poke(start)
                for (group <- 0 until 8) {
                    in.vector.destination.physical(group).poke(32 + group)
                    in.vector.source(0)(group).poke(32 + group)
                    in.vector.source(1)(group).poke(48 + group)
                    in.vector.source(2)(group).poke(10 + group)
                }
                dut.io.base.poke(base); dut.io.in.valid.poke(true)
                dut.io.stride.poke(stride.map(value => BigInt(value) & 0xffffffffL).getOrElse(BigInt(0)))
                waitUntil(dut.io.in.ready.peek().litToBoolean); tick(); dut.io.in.valid.poke(false)
            }
            for (eew <- 0 to 2; stride <- Seq(0, 8, -12, 128); masked <- Seq(false, true)) {
                val count = 2
                val elements = 32 >> eew
                val base = 0x8000803cL
                val vl = count * elements - 3
                begin(eew, count, base, vl, 2, masked, 0, stride = Some(stride)); finish()
                for (group <- 0 until count) {
                    dut.io.address.poke(32 + group)
                    val expected = (0 until 32).foldLeft(BigInt(0)) { (v, byte) =>
                        val element = group * elements + (byte >> eew)
                        val active = element >= 2 && element < vl && (!masked || vectorMask.testBit(element))
                        val value = if (active) byteAt(BigInt(base + element * stride + (byte & ((1 << eew) - 1)))) else 23
                        v | BigInt(value) << (8 * byte)
                    }
                    for (lane <- 0 until 8) dut.io.data(lane).expect((expected >> (lane * 32)) & 0xffffffffL)
                }
            }
            def finish(): Unit = {
                dut.io.completion.ready.poke(false)
                waitUntil(dut.io.completion.valid.peek().litToBoolean)
                dut.io.completion.bits.robIdx.expect(7)
                val before = writes
                tick(); tick(); assert(writes == before)
                dut.io.completion.ready.poke(true); tick()
            }
            // Full continuous groups stream through the original DCache port.
            // Cold groups exercise MSHR retries; a second pass requests each
            // cache line once and drains its halves through the existing port.
            for (eew <- 0 to 2; count <- Seq(1, 2, 8); offset <- Seq(0, 32)) {
                val base = 0x8000c000L + offset
                val vl = count * (32 >> eew)
                for (pass <- 0 until 2) {
                    requestCycles = Vector.empty
                    writeCycles = Vector.empty
                    begin(eew, count, base, vl, 0, false, 0); finish()
                    for (group <- 0 until count) {
                        dut.io.address.poke(32 + group)
                        val expected = (0 until 32).foldLeft(BigInt(0)) { (value, byte) =>
                            value | BigInt(byteAt(BigInt(base + 32 * group + byte))) << (8 * byte)
                        }
                        for (lane <- 0 until 8)
                            dut.io.data(lane).expect((expected >> (32 * lane)) & 0xffffffffL)
                    }
                    if (pass == 1) {
                        assert(requestCycles.size == (count + (offset / 32) + 1) / 2)
                        assert(requestCycles.take(4).sliding(2).forall(pair => pair.size < 2 || pair(1) == pair(0) + 1),
                            s"Available line-response credits must sustain consecutive requests: $requestCycles")
                        assert(writeCycles.size == count && writeCycles.sliding(2).forall(pair =>
                            pair.size < 2 || pair(1) == pair(0) + 1),
                            s"Wide hit writebacks must be consecutive: $writeCycles")
                    }
                }
            }
            // A flushed burst cannot publish results or release the instruction
            // owner while the original cache miss still has a response pending.
            val beforeRequests = requests
            delay = 13
            begin(2, 8, 0x80012020L, 64, 0, false, 0)
            waitUntil(requests >= beforeRequests + 2)
            val beforeWrites = writes
            dut.io.flush.poke(true); tick(); dut.io.flush.poke(false)
            waitUntil(dut.io.idle.peek().litToBoolean)
            assert(writes == beforeWrites)
            dut.io.completion.valid.expect(false)
            delay = 7
            for (eew <- 0 to 2; count <- Seq(1, 2, 8); masked <- Seq(false, true); policy <- Seq(0, 3)) {
                val maximum = count * (32 >> eew)
                val vl = maximum - 3
                val base = 0x80000400L + (4 << eew)
                val start = 2
                val expected = Array.fill(count)(BigInt(0))
                for (group <- 0 until count; byte <- 0 until 32) {
                    val element = (group * 32 + byte) >> eew
                    val enabled = !masked || vectorMask.testBit(element)
                    val active = element >= start && element < vl && enabled
                    val fill = element >= start && (element >= vl && (policy & 1) != 0 ||
                        element < vl && !enabled && (policy & 2) != 0)
                    val value = if (active) byteAt(BigInt(base + group * 32 + byte)) else if (fill) 255 else 23
                    expected(group) |= BigInt(value) << (8 * byte)
                }
                dut.io.readAvailable.poke(false); dut.io.ordered.poke(false)
                val before = requests
                begin(eew, count, base, vl, start, masked, policy)
                for (_ <- 0 until 3) tick()
                assert(requests == before)
                dut.io.readAvailable.poke(true); for (_ <- 0 until 4) tick()
                assert(requests == before)
                dut.io.ordered.poke(true); finish()
                for (group <- 0 until count) {
                    dut.io.address.poke(32 + group)
                    for (lane <- 0 until 8) dut.io.data(lane).expect((expected(group) >> (lane * 32)) & 0xffffffffL)
                }
                stores = Vector.empty
                dut.io.store.ready.poke(false)
                begin(eew, count, base, vl, start, masked, policy, store = true)
                waitUntil(dut.io.store.valid.peek().litToBoolean)
                tick(); tick(); dut.io.store.bits.index.expect(0)
                dut.io.store.ready.poke(true); finish()
                assert(stores.size == count)
                for (((index, address, data, mask, last), group) <- stores.zipWithIndex) {
                    val expectedMask = (0 until 32).filter { byte =>
                        val element = (group * 32 + byte) >> eew
                        element >= start && element < vl && (!masked || vectorMask.testBit(element))
                    }.foldLeft(BigInt(0))((value, byte) => value.setBit(byte))
                    assert(index == group && address == base + group * 32 && data == expected(group))
                    assert(mask == expectedMask && last == (group == count - 1))
                }
            }
            // VL=0 preserves the old destination and issues no cache request.
            for (eew <- 0 to 2; fields <- Seq(1, 2, 3); stride <- Seq(None, Some(0), Some(36), Some(-12)); store <- Seq(false, true)) {
                val count = 2 * fields; val perReg = 32 >> eew
                val vl = 2 * perReg - 3; val base = 0x8000a000L
                stores = Vector.empty; scatterAddresses = Map.empty
                begin(eew, count, base, vl, 2, true, 0, store = store, stride = stride, fields = fields); finish()
                for (group <- 0 until count) {
                    val field = group / 2; val within = group % 2
                    if (store && (fields > 1 || stride.nonEmpty)) {
                        for (position <- 0 until 32 by (1 << eew)) {
                            val element = within * perReg + (position >> eew)
                            assert(scatterAddresses((group, position)) == base + element * stride.getOrElse(fields << eew) + (field << eew))
                        }
                        val mask = (0 until 32).filter { byte =>
                            val element = within * perReg + (byte >> eew)
                            element >= 2 && element < vl && vectorMask.testBit(element)
                        }.foldLeft(BigInt(0))((v, byte) => v.setBit(byte))
                        assert(stores(group)._4 == mask)
                    } else if (!store) {
                        dut.io.address.poke(32 + group)
                        val expected = (0 until 32).foldLeft(BigInt(0)) { (v, byte) =>
                            val element = within * perReg + (byte >> eew)
                            val active = element >= 2 && element < vl && vectorMask.testBit(element)
                            val address = base + element * stride.getOrElse(fields << eew) + (field << eew) + (byte & ((1 << eew) - 1))
                            val value = if (active) byteAt(BigInt(address)) else 23
                            v | BigInt(value) << (byte * 8)
                        }
                        for (lane <- 0 until 8) dut.io.data(lane).expect((expected >> (lane * 32)) & 0xffffffffL,
                            s"segment eew=$eew fields=$fields stride=$stride group=$group")
                    }
                }
            }
            for (eew <- 0 to 2; indexWidth <- 0 to 2; store <- Seq(false, true)) {
                val count = 1; val perReg = 32 >> eew
                val indices = (0 until perReg).map(element => BigInt((element * 28) % 112))
                val packed = indices.zipWithIndex.foldLeft(BigInt(0)) { case (v, (index, element)) => v | (index << (element * (8 << indexWidth))) }
                for (group <- 0 until 4) {
                    dut.io.initialize.valid.poke(true); dut.io.initialize.address.poke(48 + group)
                    for (lane <- 0 until 8) {
                        dut.io.initialize.data(lane).poke((packed >> (group * 256 + lane * 32)) & 0xffffffffL)
                        dut.io.initialize.bytes(lane).poke(15)
                    }
                    tick()
                }
                dut.io.initialize.valid.poke(false); stores = Vector.empty; scatterAddresses = Map.empty
                val base = 0x8000c000L
                begin(eew, count, base, perReg - 2, 1, false, 0, store = store, indexWidth = Some(indexWidth)); finish()
                if (store) for (element <- indices.indices) assert(scatterAddresses((0, element << eew)) == base + indices(element))
                else {
                    dut.io.address.poke(32)
                    val expected = (0 until 32).foldLeft(BigInt(0)) { (v, byte) =>
                        val element = byte >> eew
                        val value = if (element >= 1 && element < perReg - 2)
                            byteAt(BigInt(base) + indices(element) + (byte & ((1 << eew) - 1))) else 23
                        v | BigInt(value) << (byte * 8)
                    }
                    for (lane <- 0 until 8) dut.io.data(lane).expect((expected >> (lane * 32)) & 0xffffffffL)
                }
            }
            val beforeZero = requests
            begin(2, 1, 0x80000400L, 0, 0, false, 3); finish()
            assert(requests == beforeZero)
            dut.io.address.poke(32); dut.io.data.foreach(_.expect(0x17171717))
            for (eew <- 0 to 2; count <- Seq(1, 2, 4, 8); start <- Seq(0, 3)) {
                val base = 0x80002004L
                // VL=1 must not truncate a whole-register transfer.
                begin(eew, count, base, 1, start, false, 3, lumop = 8); finish()
                for (group <- 0 until count) {
                    dut.io.address.poke(32 + group)
                    val expected = (0 until 32).foldLeft(BigInt(0)) { (v, byte) =>
                        val value = if ((group * 32 + byte) >> eew < start) 23 else byteAt(BigInt(base + group * 32 + byte))
                        v | (BigInt(value) << (byte * 8))
                    }
                    for (lane <- 0 until 8) dut.io.data(lane).expect((expected >> (32 * lane)) & 0xffffffffL)
                }
                stores = Vector.empty
                begin(eew, count, base, 1, start, false, 3, store = true, lumop = 8); finish()
                assert(stores.size == count)
                for ((item, group) <- stores.zipWithIndex) {
                    val expectedMask = (0 until 32).filter(byte => ((group * 32 + byte) >> eew) >= start)
                        .foldLeft(BigInt(0))((v, byte) => v.setBit(byte))
                    assert(item._2 == base + group * 32 && item._4 == expectedMask)
                }
            }
            // Cancel before lower acceptance, then cancel an accepted delayed miss.
            for (accepted <- Seq(false, true)) {
                delay = 40
                dut.io.lower.req.ready.poke(accepted)
                begin(2, 1, if (accepted) 0x80005000L else 0x80004000L, 8, 0, false, 3)
                waitUntil(if (accepted) lowerResponse.nonEmpty else dut.io.lower.req.valid.peek().litToBoolean)
                val writesBefore = writes
                dut.io.flush.poke(true); tick(); dut.io.flush.poke(false)
                if (accepted) {
                    dut.io.idle.expect(false); dut.io.in.ready.expect(false)
                }
                waitUntil(dut.io.idle.peek().litToBoolean)
                assert(writes == writesBefore)
                dut.io.lower.req.ready.poke(true)
                begin(2, 1, 0x80000400L, 8, 0, false, 3); finish()
            }
            info(s"Verified vector memory over $cycle cycles, $requests cache requests, $writes PRF writes")
        }
    }
}
