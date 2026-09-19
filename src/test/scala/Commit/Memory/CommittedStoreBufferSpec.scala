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
}
