import chisel3._
import chisel3.simulator.PeekPokeAPI
import org.scalatest.freespec.AnyFreeSpec
import chisel3.simulator.scalatest.ChiselSim

class SharedStorePortSpec extends AnyFreeSpec with ChiselSim with PeekPokeAPI {
    private def initialize(dut: SharedStorePort): Unit = {
        dut.io.reserveAtomic.poke(false)
        dut.io.atomicRequest.valid.poke(false)
        dut.io.atomicRequest.bits.poke(0.U.asTypeOf(new DStoreRequest))
        dut.io.atomicResponse.ready.poke(false)
        dut.io.committedRequest.valid.poke(false)
        dut.io.committedRequest.bits.poke(0.U.asTypeOf(new DStoreRequest))
        dut.io.committedResponse.ready.poke(false)
        dut.io.cacheRequest.ready.poke(false)
        dut.io.cacheResponse.valid.poke(false)
        dut.io.cacheResponse.bits.exception.poke(0)
    }

    "keep a committed response routed to its request owner when atomic reservation changes" in {
        simulate(new SharedStorePort) { dut =>
            initialize(dut)
            dut.io.committedRequest.valid.poke(true)
            dut.io.committedRequest.bits.paddr.poke(0x1000)
            dut.io.cacheRequest.ready.poke(true)
            dut.io.cacheRequest.valid.expect(true)
            dut.io.cacheRequest.bits.paddr.expect(0x1000)
            dut.clock.step()

            dut.io.committedRequest.valid.poke(false)
            dut.io.reserveAtomic.poke(true)
            dut.io.committedResponse.ready.poke(true)
            dut.io.cacheResponse.valid.poke(true)
            dut.io.cacheResponse.bits.exception.poke(7)
            dut.io.committedResponse.valid.expect(true)
            dut.io.committedResponse.bits.exception.expect(7)
            dut.io.atomicResponse.valid.expect(false)
            dut.io.cacheResponse.ready.expect(true)
            dut.clock.step()
        }
    }

    "block younger committed stores throughout an atomic sequence" in {
        simulate(new SharedStorePort) { dut =>
            initialize(dut)
            dut.io.reserveAtomic.poke(true)
            dut.io.committedRequest.valid.poke(true)
            dut.io.cacheRequest.ready.poke(true)
            dut.io.cacheRequest.valid.expect(false)
            dut.io.committedRequest.ready.expect(false)
        }
    }

    "replace a committed response with an atomic request in one cycle" in {
        simulate(new SharedStorePort) { dut =>
            initialize(dut)
            dut.io.committedRequest.valid.poke(true)
            dut.io.cacheRequest.ready.poke(true)
            dut.clock.step()

            dut.io.committedRequest.valid.poke(false)
            dut.io.reserveAtomic.poke(true)
            dut.io.atomicRequest.valid.poke(true)
            dut.io.atomicRequest.bits.paddr.poke(0x2000)
            dut.io.committedResponse.ready.poke(true)
            dut.io.cacheResponse.valid.poke(true)
            dut.io.committedResponse.valid.expect(true)
            dut.io.atomicResponse.valid.expect(false)
            dut.io.cacheRequest.valid.expect(true)
            dut.io.cacheRequest.bits.paddr.expect(0x2000)
            dut.clock.step()

            dut.io.atomicRequest.valid.poke(false)
            dut.io.committedResponse.ready.poke(false)
            dut.io.atomicResponse.ready.poke(true)
            dut.io.cacheResponse.valid.poke(true)
            dut.io.committedResponse.valid.expect(false)
            dut.io.atomicResponse.valid.expect(true)
            dut.io.cacheResponse.ready.expect(true)
            dut.clock.step()
        }
    }
}
