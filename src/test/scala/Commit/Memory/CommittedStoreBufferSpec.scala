import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class CommittedStoreBufferSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: StoreBuffer): Unit = {
        dut.io.enqueue.valid.poke(false)
        dut.io.store.request.ready.poke(false)
        dut.io.store.response.valid.poke(false)
        dut.io.store.response.bits.exception.poke(0)
        dut.io.query.foreach { port =>
            port.request.valid.poke(false)
            port.request.bits.wordAddress.poke(0)
            port.request.bits.slot.poke(0)
            port.request.bits.mask.poke(0)
        }
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
    }

    private def enqueue(dut: StoreBuffer, paddr: Int, data: Int, mask: Int): Unit = {
        dut.io.enqueue.valid.poke(true)
        dut.io.enqueue.bits.paddr.poke(paddr)
        dut.io.enqueue.bits.data.poke(data)
        dut.io.enqueue.bits.mask.poke(mask)
        dut.io.enqueue.bits.size.poke(0)
        dut.io.enqueue.bits.uncache.poke(false)
        dut.io.enqueue.ready.expect(true)
        dut.clock.step()
        dut.io.enqueue.valid.poke(false)
    }

    private def drainOne(dut: StoreBuffer): Unit = {
        dut.io.store.request.ready.poke(true)
        dut.io.store.request.valid.expect(true)
        dut.clock.step()
        dut.io.store.request.ready.poke(false)
        dut.io.store.response.valid.poke(true)
        dut.io.store.response.ready.expect(true)
        dut.clock.step()
        dut.io.store.response.valid.poke(false)
    }

    "forwards the youngest committed byte until DCache acknowledges it" in {
        simulate(new StoreBuffer) { dut =>
            initialize(dut)
            enqueue(dut, paddr = 0x1001, data = 0x00001100, mask = 2)
            enqueue(dut, paddr = 0x1001, data = 0x00002200, mask = 2)

            val query = dut.io.query(0).request
            query.valid.poke(true)
            query.bits.wordAddress.poke(0x1000 >> 2)
            query.bits.mask.poke(15)
            dut.io.query(0).response.valid.expect(false)
            dut.clock.step()
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.mask.expect(2)
            dut.io.query(0).response.bits.data.expect(0x00002200)

            dut.io.store.request.ready.poke(true)
            dut.io.store.request.valid.expect(true)
            dut.io.store.request.bits.data.expect(0x00001100)
            dut.clock.step()
            dut.io.store.request.valid.expect(false)
            dut.io.store.response.valid.poke(true)
            dut.io.store.response.ready.expect(true)
            dut.io.store.request.valid.expect(true)
            dut.io.store.request.bits.data.expect(0x00002200)
            dut.clock.step()
            dut.io.store.response.valid.poke(false)
            dut.io.query(0).response.bits.data.expect(0x00002200)
            dut.io.query(0).response.bits.mask.expect(2)

            dut.io.store.response.valid.poke(true)
            dut.io.store.response.bits.exception.poke(7)
            dut.io.responseError.valid.expect(true)
            dut.io.responseError.bits.expect(7)
            dut.clock.step()
            dut.io.store.response.valid.poke(false)
            dut.io.empty.expect(true)
            dut.clock.step()
            dut.io.query(0).response.bits.mask.expect(0)
        }
    }

    "merges bytes from the youngest matching stores after pointer wraparound" in {
        simulate(new StoreBuffer) { dut =>
            initialize(dut)
            enqueue(dut, paddr = 0x3000, data = 0x01010101, mask = 15)
            enqueue(dut, paddr = 0x3004, data = 0x02020202, mask = 15)
            enqueue(dut, paddr = 0x2000, data = 0x00000011, mask = 1)
            enqueue(dut, paddr = 0x2000, data = 0x00002200, mask = 2)
            drainOne(dut)
            drainOne(dut)

            val query0 = dut.io.query(0).request
            query0.valid.poke(true)
            query0.bits.wordAddress.poke(0x2000 >> 2)
            query0.bits.slot.poke(1)
            query0.bits.mask.poke(15)
            dut.io.enqueue.valid.poke(true)
            dut.io.enqueue.bits.paddr.poke(0x2000)
            dut.io.enqueue.bits.data.poke(0x00330000)
            dut.io.enqueue.bits.mask.poke(4)
            dut.io.enqueue.bits.size.poke(0)
            dut.io.enqueue.bits.uncache.poke(false)
            dut.clock.step()
            query0.valid.poke(false)
            dut.io.enqueue.valid.poke(false)
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.slot.expect(1)
            dut.io.query(0).response.bits.mask.expect(3)
            dut.io.query(0).response.bits.data.expect(0x00002211)

            enqueue(dut, paddr = 0x2000, data = 0x44000000, mask = 8)
            val query1 = dut.io.query(1).request
            query0.valid.poke(true)
            query0.bits.slot.poke(2)
            query0.bits.mask.poke(15)
            query1.valid.poke(true)
            query1.bits.wordAddress.poke(0x2000 >> 2)
            query1.bits.slot.poke(3)
            query1.bits.mask.poke(10)
            dut.clock.step()
            query0.valid.poke(false)
            query1.valid.poke(false)
            dut.io.query(0).response.valid.expect(true)
            dut.io.query(0).response.bits.slot.expect(2)
            dut.io.query(0).response.bits.mask.expect(15)
            dut.io.query(0).response.bits.data.expect(0x44332211L)
            dut.io.query(1).response.valid.expect(true)
            dut.io.query(1).response.bits.slot.expect(3)
            dut.io.query(1).response.bits.mask.expect(10)
            dut.io.query(1).response.bits.data.expect(0x44002200)
        }
    }
}
