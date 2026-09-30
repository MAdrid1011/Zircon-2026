import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

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
}
