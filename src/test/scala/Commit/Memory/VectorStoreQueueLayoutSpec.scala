import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

/** Check payload ownership while primary LMUL footprints change. */
class VectorStoreQueueLayoutSpec extends AnyFreeSpec with ChiselSim {
    "queue rows remain disjoint through full capacity, drain and layout changes" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { p => p.valid.poke(false); p.bits.poke(0) }
            dut.io.drain.start.valid.poke(false); dut.io.drain.start.bits.poke(true)
            dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            dut.io.cache.response.bits.exception.poke(0); dut.io.flush.poke(false)
            dut.io.loadLine.poke(0); dut.io.loadTail.poke(12)
            dut.io.scalarLoadLine.foreach(_.poke(0)); dut.io.scalarLoadTail.foreach(_.poke(12))
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for ((groups, round) <- Seq(1, 4, 1, 8, 2, 1).zipWithIndex) {
                val queued = if (groups == 1) 7 else if (groups <= 4) 4 else 0
                val base = 0xb0000000L + round * 4096L
                val expected = scala.collection.mutable.Map.empty[Long,Int]
                def collect(identity: Int, index: Int, last: Boolean, address: Long, byte: Int): Unit = {
                    val task = dut.io.collect.bits
                    task.sqIdx.poke(identity); task.robIdx.poke(identity)
                    task.index.poke(index); task.last.poke(last); task.address.poke(address)
                    task.data.poke((0 until 32).foldLeft(BigInt(0))((v, lane) => v | BigInt(byte) << (8 * lane)))
                    task.mask.poke(BigInt("ffffffff",16)); task.eew.poke(2)
                    task.scattered.poke(false); task.addressOnly.poke(false)
                    dut.io.collect.valid.poke(true); dut.io.collect.ready.expect(true)
                    dut.clock.step(); dut.io.collect.valid.poke(false)
                    for (offset <- 0 until 32) expected(address + offset) = byte
                }
                for (g <- 0 until groups) collect(0,g,g == groups-1,base+g*32,g+1)
                for (i <- 0 until queued) collect(i+1,0,true,base+1024+i*96,128+i)
                dut.io.collect.bits.index.poke(0); dut.io.collect.bits.last.poke(true)
                dut.io.collect.bits.sqIdx.poke(queued+1); dut.io.collect.valid.poke(true)
                dut.io.collect.ready.expect(false); dut.clock.step(2); dut.io.collect.valid.poke(false)
                for (batch <- (0 to queued).grouped(3)) {
                    for ((id, port) <- batch.zipWithIndex) {
                        dut.io.commit(port).valid.poke(true); dut.io.commit(port).bits.poke(id)
                    }
                    dut.clock.step(); dut.io.commit.foreach(_.valid.poke(false))
                }
                // Retirement grants are offered independently of cache
                // backpressure; a returning ACK may hand off to the next token.
                dut.io.drain.start.valid.poke(true)
                val observed = scala.collection.mutable.Map.empty[Long,Int]
                var pending: Option[Int] = None
                var cycles = 0
                var writes = 0
                while ((!dut.io.empty.peek().litToBoolean || pending.nonEmpty) && cycles < 300) {
                    val reply = pending.contains(cycles)
                    dut.io.cache.response.valid.poke(reply)
                    dut.io.cache.request.ready.poke(cycles % 4 != 1)
                    if (reply) { dut.io.cache.response.ready.expect(true); pending = None }
                    if (dut.io.cache.request.valid.peek().litToBoolean && dut.io.cache.request.ready.peek().litToBoolean) {
                        assert(pending.isEmpty)
                        val r = dut.io.cache.request.bits
                        val address = r.paddr.peek().litValue.toLong
                        val data = r.line.peek().litValue
                        val mask = r.lineMask.peek().litValue
                        for (byte <- 0 until 64 if mask.testBit(byte))
                            observed(address+byte) = ((data >> (byte*8)) & 255).toInt
                        writes += 1; pending = Some(cycles+2)
                    }
                    dut.clock.step(); cycles += 1
                }
                assert(cycles < 300 && observed == expected, s"primary groups=$groups")
                assert(writes == (groups+1)/2+queued)
                dut.io.drain.start.valid.poke(false); dut.io.cache.response.valid.poke(false)
                dut.io.cache.request.ready.poke(false); dut.clock.step(2); dut.io.empty.expect(true)
            }
        }
    }
}
