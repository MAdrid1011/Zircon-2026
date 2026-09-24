import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class StoreQueueSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: StoreQueue): Unit = {
        dut.io.request.valid.poke(0)
        dut.io.request.store.poke(0)
        dut.io.enqueue.valid.poke(0)
        dut.io.address.valid.poke(false)
        dut.io.data.valid.poke(false)
        dut.io.commit.foreach { port =>
            port.valid.poke(false)
            port.bits.poke(0)
        }
        dut.io.drain.ready.poke(false)
        dut.io.atomic.sqIdx.valid.poke(false)
        dut.io.atomic.sqIdx.bits.poke(0)
        dut.io.query.foreach { port =>
            port.request.valid.poke(false)
            port.request.bits.wordAddress.poke(0)
            port.request.bits.slot.poke(0)
            port.request.bits.mask.poke(0)
            port.request.bits.sqTail.poke(0)
        }
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
    }

    private def address(dut: StoreQueue, sqIdx: BigInt, robIdx: Int, paddr: Int, mask: Int): Unit = {
        dut.io.address.valid.poke(true)
        dut.io.address.bits.sqIdx.poke(sqIdx)
        dut.io.address.bits.robIdx.poke(robIdx)
        dut.io.address.bits.vaddr.poke(paddr)
        dut.io.address.bits.paddr.poke(paddr)
        dut.io.address.bits.size.poke(0)
        dut.io.address.bits.mask.poke(mask)
        dut.io.address.bits.exception.poke(0)
        dut.io.address.bits.uncache.poke(false)
    }

    private def data(dut: StoreQueue, sqIdx: BigInt, robIdx: Int, value: Int): Unit = {
        dut.io.data.valid.poke(true)
        dut.io.data.bits.sqIdx.poke(sqIdx)
        dut.io.data.bits.robIdx.poke(robIdx)
        dut.io.data.bits.size.poke(0)
        dut.io.data.bits.data.poke(value)
    }

    "flush preserves committed stores and discards the younger speculative suffix" in {
        simulate(new StoreQueue(dispatchWidth = 4)) { dut =>
            initialize(dut)
            dut.io.request.valid.poke(15)
            val units = Seq(
                ZirconConfig.DecodeUnit.Load,
                ZirconConfig.DecodeUnit.Store,
                ZirconConfig.DecodeUnit.Load,
                ZirconConfig.DecodeUnit.Store,
            )
            dut.io.request.store.poke(10)
            val tails = dut.io.allocation.map(_.tail.peek().litValue)
            val indices = dut.io.allocation.map(_.index.peek().litValue)
            assert(tails == Seq(0, 0, 1, 1))
            assert(indices(1) == 0 && indices(3) == 1)
            dut.io.enqueue.valid.poke(15)
            for (lane <- 0 until 4) {
                dut.io.enqueue.entries(lane).context.instruction.fu.poke(units(lane))
                dut.io.enqueue.entries(lane).allocation.robIdx.poke(20 + lane)
                dut.io.enqueue.entries(lane).allocation.sqIdx.poke(indices(lane))
            }
            dut.clock.step()
            dut.io.request.valid.poke(0)
            dut.io.enqueue.valid.poke(0)

            address(dut, indices(1), robIdx = 21, paddr = 0x1001, mask = 2)
            data(dut, indices(1), robIdx = 21, value = 0xaa)
            dut.io.completion(0).valid.expect(true)
            dut.clock.step()
            address(dut, indices(3), robIdx = 23, paddr = 0x1002, mask = 4)
            data(dut, indices(3), robIdx = 23, value = 0xbb)
            dut.clock.step()
            dut.io.address.valid.poke(false)
            dut.io.data.valid.poke(false)

            val query = dut.io.query(0).request
            query.valid.poke(true)
            query.bits.wordAddress.poke(0x1000 >> 2)
            query.bits.mask.poke(15)
            query.bits.sqTail.poke(2)
            dut.io.query(0).response.valid.expect(false)
            dut.clock.step()
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.mask.expect(6)
            dut.io.query(0).response.bits.data.expect(0x00bbaa00)
            dut.io.query(0).response.bits.blocked.expect(false)

            dut.io.commit(0).valid.poke(true)
            dut.io.commit(0).bits.poke(indices(1))
            dut.clock.step()
            dut.io.commit(0).valid.poke(false)
            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.drain.valid.expect(true)
            dut.io.drain.bits.paddr.expect(0x1001)
            dut.io.drain.bits.data.expect(0x0000aa00)
            dut.io.query(0).response.valid.expect(false)
            dut.clock.step()
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.mask.expect(2)

            dut.io.drain.ready.poke(true)
            dut.clock.step()
            dut.io.drain.ready.poke(false)
            dut.io.empty.expect(true)
        }
    }

    "data may arrive before the address without losing alignment or dependency blocking" in {
        simulate(new StoreQueue(dispatchWidth = 2)) { dut =>
            initialize(dut)
            dut.io.request.valid.poke(1)
            dut.io.request.store.poke(1)
            val sqIdx = dut.io.allocation(0).index.peek().litValue
            dut.io.enqueue.valid.poke(1)
            dut.io.enqueue.entries(0).context.instruction.fu.poke(ZirconConfig.DecodeUnit.Store)
            dut.io.enqueue.entries(0).allocation.robIdx.poke(9)
            dut.io.enqueue.entries(0).allocation.sqIdx.poke(sqIdx)
            dut.clock.step()
            dut.io.request.valid.poke(0)
            dut.io.enqueue.valid.poke(0)

            data(dut, sqIdx, robIdx = 9, value = 0x5a)
            dut.clock.step()
            dut.io.data.valid.poke(false)
            val query = dut.io.query(0).request
            query.valid.poke(true)
            query.bits.wordAddress.poke(0x2000 >> 2)
            query.bits.mask.poke(15)
            query.bits.sqTail.poke(1)
            dut.clock.step()
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.blocked.expect(true)

            address(dut, sqIdx, robIdx = 9, paddr = 0x2003, mask = 8)
            dut.io.completion(0).valid.expect(true)
            dut.clock.step()
            dut.io.address.valid.poke(false)
            dut.clock.step()
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.blocked.expect(false)
            dut.io.query(0).response.bits.mask.expect(8)
            dut.io.query(0).response.bits.data.expect(0x5a000000L)
        }
    }

    "an Atomic waits for Commit and bypasses the ordinary store drain" in {
        simulate(new StoreQueue(dispatchWidth = 2)) { dut =>
            initialize(dut)
            dut.io.request.valid.poke(1)
            dut.io.request.store.poke(1)
            val sqIdx = dut.io.allocation(0).index.peek().litValue
            dut.io.enqueue.valid.poke(1)
            dut.io.enqueue.entries(0).context.instruction.fu.poke(ZirconConfig.DecodeUnit.Atomic)
            dut.io.enqueue.entries(0).context.instruction.op.poke(4)
            dut.io.enqueue.entries(0).destination.prd.poke(11)
            dut.io.enqueue.entries(0).allocation.robIdx.poke(13)
            dut.io.enqueue.entries(0).allocation.sqIdx.poke(sqIdx)
            dut.clock.step()
            dut.io.request.valid.poke(0)
            dut.io.enqueue.valid.poke(0)

            address(dut, sqIdx, robIdx = 13, paddr = 0x3000, mask = 15)
            dut.io.address.bits.size.poke(2)
            data(dut, sqIdx, robIdx = 13, value = 0x55aa55aa)
            dut.io.data.bits.size.poke(2)
            dut.io.completion.foreach(_.valid.expect(false))
            dut.clock.step()
            dut.io.address.valid.poke(false)
            dut.io.data.valid.poke(false)

            dut.io.atomic.sqIdx.valid.poke(true)
            dut.io.atomic.sqIdx.bits.poke(sqIdx)
            dut.io.atomic.request.valid.expect(false)
            dut.clock.step()
            dut.io.atomic.request.valid.expect(true)
            dut.io.atomic.request.bits.robIdx.expect(13)
            dut.io.atomic.request.bits.prd.expect(11)
            dut.io.atomic.request.bits.paddr.expect(0x3000)
            dut.io.atomic.request.bits.data.expect(0x55aa55aa)
            dut.io.atomic.request.bits.op.expect(4)
            dut.io.drain.valid.expect(false)

            dut.io.commit(0).valid.poke(true)
            dut.io.commit(0).bits.poke(sqIdx)
            dut.clock.step()
            dut.io.commit(0).valid.poke(false)
            dut.io.atomic.sqIdx.valid.poke(false)
            dut.io.empty.expect(true)
            dut.io.drain.valid.expect(false)
        }
    }
}
