import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

class WriteCombineQueueSpec extends AnyFreeSpec with ChiselSim {
    private def init(dut: WriteCombineQueue): Unit = {
        dut.io.enq.valid.poke(false)
        dut.io.enq.bits.poke(0.U.asTypeOf(new DStoreRequest))
        dut.io.out.ready.poke(false)
        dut.io.rsp.valid.poke(false)
        dut.io.rsp.bits.poke(0.U.asTypeOf(new DMemoryResponse))
        dut.io.seal.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def enqueue(dut: WriteCombineQueue, address: Int, data: BigInt): Unit = {
        dut.io.enq.bits.paddr.poke(address)
        dut.io.enq.bits.data.poke(data)
        dut.io.enq.bits.mask.poke(15)
        dut.io.enq.bits.size.poke(2)
        dut.io.enq.bits.uncache.poke(true)
        dut.io.enq.bits.writeCombine.poke(true)
        dut.io.enq.valid.poke(true)
        dut.io.enq.ready.expect(true)
        dut.clock.step()
        dut.io.enq.valid.poke(false)
    }

    "merge same beat and emit consecutive beats with per-beat strobes" in {
        simulate(new WriteCombineQueue) { dut =>
            init(dut)
            enqueue(dut, 0x2000, BigInt("11223344", 16))
            enqueue(dut, 0x2004, BigInt("55667788", 16))
            enqueue(dut, 0x2008, BigInt("99aabbcc", 16))
            dut.io.seal.poke(true)
            dut.clock.step()
            dut.io.out.valid.expect(true)
            dut.io.out.bits.paddr.expect(0x2000)
            dut.io.out.bits.burstBeats.expect(2)
            dut.io.out.bits.burstMask.expect(BigInt("0fff", 16))
            dut.io.out.bits.data.expect(BigInt("0000000099aabbcc5566778811223344", 16))
            dut.io.out.ready.poke(true)
            dut.clock.step()
            dut.io.out.ready.poke(false)
            dut.io.busy.expect(true)
            dut.io.rsp.valid.poke(true)
            dut.clock.step()
            dut.io.rsp.valid.poke(false)
            dut.io.empty.expect(true)
        }
    }

    "close a burst on a non-contiguous next store" in {
        simulate(new WriteCombineQueue) { dut =>
            init(dut)
            enqueue(dut, 0x3000, BigInt("1", 16))
            dut.io.enq.bits.paddr.poke(0x3010)
            dut.io.enq.bits.data.poke(2)
            dut.io.enq.bits.mask.poke(15)
            dut.io.enq.bits.size.poke(2)
            dut.io.enq.bits.uncache.poke(true)
            dut.io.enq.bits.writeCombine.poke(true)
            dut.io.enq.valid.poke(true)
            dut.io.enq.ready.expect(false)
            dut.clock.step()
            dut.io.enq.valid.poke(false)
            dut.io.out.valid.expect(true)
            dut.io.out.bits.burstBeats.expect(1)
        }
    }

    "close a burst at a 4 KiB boundary" in {
        simulate(new WriteCombineQueue) { dut =>
            init(dut)
            enqueue(dut, 0x3ff8, BigInt("1", 16))
            dut.io.enq.bits.paddr.poke(0x4000)
            dut.io.enq.bits.data.poke(2)
            dut.io.enq.bits.mask.poke(15)
            dut.io.enq.bits.size.poke(2)
            dut.io.enq.bits.uncache.poke(true)
            dut.io.enq.bits.writeCombine.poke(true)
            dut.io.enq.valid.poke(true)
            dut.io.enq.ready.expect(false)
            dut.clock.step()
            dut.io.enq.valid.poke(false)
            dut.io.out.valid.expect(true)
            dut.io.out.bits.paddr.expect(0x3ff8)
            dut.io.out.bits.burstBeats.expect(1)
        }
    }

    "byte overwrites, backpressure and responses preserve every accepted store" in {
        simulate(new WriteCombineQueue) { dut =>
            init(dut)
            val random = new Random(20261014)
            val stores = (0 until 256).map { index =>
                val group = index / 16
                val address = 0x4ff0 + group * 0x100 + (index % 16 / 3) * 8 + (index % 2) * 4
                (address, BigInt(32, random), random.nextInt(15) + 1)
            }
            val acceptedBytes = mutable.Map.empty[Int, Int]
            var sent = 0
            var responseDelay = -1
            var stalledPayload: Option[Seq[BigInt]] = None
            var bursts = 0
            var cycle = 0
            while ((sent < stores.size || !dut.io.empty.peek().litToBoolean || responseDelay >= 0) && cycle < 4096) {
                val pending = stores.lift(sent)
                dut.io.enq.valid.poke(pending.nonEmpty)
                pending.foreach { case (address, value, strobe) =>
                    dut.io.enq.bits.paddr.poke(address)
                    dut.io.enq.bits.data.poke(value)
                    dut.io.enq.bits.mask.poke(strobe)
                }
                val outputReady = random.nextInt(4) == 0
                dut.io.out.ready.poke(outputReady)
                dut.io.rsp.valid.poke(responseDelay == 0)
                dut.io.seal.poke(sent == stores.size)
                val outputValid = dut.io.out.valid.peek().litToBoolean
                val outputPayload = Seq(dut.io.out.bits.paddr, dut.io.out.bits.data,
                    dut.io.out.bits.burstMask, dut.io.out.bits.burstBeats).map(_.peek().litValue)
                stalledPayload.foreach(value => assert(outputValid && value == outputPayload))
                stalledPayload = if (outputValid && !outputReady) Some(outputPayload) else None
                if (outputValid && outputReady) {
                    assert(responseDelay < 0)
                    val base = dut.io.out.bits.paddr.peek().litValue.toInt
                    val beats = dut.io.out.bits.burstBeats.peek().litValue.toInt
                    val strobes = dut.io.out.bits.burstMask.peek().litValue
                    val values = dut.io.out.bits.data.peek().litValue
                    assert(beats >= 1 && beats <= 8)
                    assert((base >>> 12) == ((base + beats * 8 - 1) >>> 12))
                    for (byte <- 0 until beats * 8) {
                        val enabled = strobes.testBit(byte)
                        assert(enabled == acceptedBytes.contains(base + byte))
                        if (enabled) assert(((values >> (byte * 8)) & 255).toInt == acceptedBytes(base + byte))
                    }
                    assert(acceptedBytes.keys.forall(address => address >= base && address < base + beats * 8))
                    acceptedBytes.clear()
                    responseDelay = random.nextInt(5) + 2
                    bursts += 1
                }
                if (pending.nonEmpty && dut.io.enq.ready.peek().litToBoolean) {
                    val (address, value, strobe) = pending.get
                    for (byte <- 0 until 4 if (strobe & (1 << byte)) != 0) {
                        acceptedBytes(address + byte) = ((value >> (byte * 8)) & 255).toInt
                    }
                    sent += 1
                }
                if (responseDelay == 0) {
                    dut.io.rsp.ready.expect(true)
                    responseDelay = -1
                } else if (responseDelay > 0) responseDelay -= 1
                dut.clock.step()
                cycle += 1
            }
            assert(sent == stores.size && acceptedBytes.isEmpty && bursts > 16)
            dut.io.empty.expect(true)
            info(s"$cycle cycles, $sent stores and $bursts checked bursts")
        }
    }
}
