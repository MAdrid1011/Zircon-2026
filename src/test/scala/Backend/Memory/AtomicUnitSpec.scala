import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class AtomicUnitSpec extends AnyFreeSpec with ChiselSim with PeekPokeAPI {
    private def initialize(dut: AtomicUnit): Unit = {
        dut.io.request.valid.poke(false)
        dut.io.response.ready.poke(true)
        dut.io.committedStore.valid.poke(false)
        dut.io.committedStore.bits.poke(0)
        dut.io.load.request.ready.poke(false)
        dut.io.load.response.valid.poke(false)
        dut.io.load.forwardQuery.valid.poke(false)
        dut.io.store.request.ready.poke(false)
        dut.io.store.response.valid.poke(false)
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
    }

    private def request(dut: AtomicUnit, op: Int, address: Long, data: Long): Unit = {
        dut.io.request.valid.poke(true)
        dut.io.request.bits.robIdx.poke(9)
        dut.io.request.bits.prd.poke(7)
        dut.io.request.bits.vaddr.poke(address)
        dut.io.request.bits.paddr.poke(address)
        dut.io.request.bits.data.poke(data)
        dut.io.request.bits.op.poke(op)
        dut.io.request.bits.uncache.poke(false)
        dut.io.request.bits.exception.poke(0)
        dut.io.request.ready.expect(true)
        dut.clock.step()
        dut.io.request.valid.poke(false)
    }

    private def load(dut: AtomicUnit, value: Long): Unit = {
        dut.io.load.request.valid.expect(true)
        dut.io.load.request.bits.mtype.expect(2)
        dut.io.load.request.ready.poke(true)
        dut.clock.step()
        dut.io.load.request.ready.poke(false)
        dut.io.load.response.valid.poke(true)
        dut.io.load.response.bits.data.poke(value)
        dut.io.load.response.bits.exception.poke(0)
        dut.io.load.response.bits.retry.poke(false)
        dut.clock.step()
        dut.io.load.response.valid.poke(false)
    }

    private def store(dut: AtomicUnit, expected: Long): Unit = {
        dut.io.store.request.valid.expect(true)
        dut.io.store.request.bits.data.expect(expected & 0xffffffffL)
        dut.io.store.request.bits.mask.expect(15)
        dut.io.store.request.ready.poke(true)
        dut.clock.step()
        dut.io.store.request.ready.poke(false)
        dut.io.store.response.valid.poke(true)
        dut.io.store.response.bits.exception.poke(0)
        dut.clock.step()
        dut.io.store.response.valid.poke(false)
    }

    private def response(dut: AtomicUnit, expected: Long): Unit = {
        dut.io.response.valid.expect(true)
        dut.io.writeResult.expect(true)
        dut.io.response.bits.data.expect(expected & 0xffffffffL)
        dut.io.response.bits.exception.expect(0)
        dut.clock.step()
        dut.io.busy.expect(false)
    }

    private def committedStore(dut: AtomicUnit, address: Long): Unit = {
        dut.io.committedStore.valid.poke(true)
        dut.io.committedStore.bits.poke(address)
        dut.clock.step()
        dut.io.committedStore.valid.poke(false)
    }

    "all AMO word operations return the old word and generate the specified new word" in {
        val cases = Seq(
            (0, 0x80000003L, 5L, 0x80000008L),
            (1, 0x12345678L, 0xa5a5a5a5L, 0xa5a5a5a5L),
            (4, 0x0f0f00ffL, 0x00ffff00L, 0x0ff0ffffL),
            (8, 0x0f0f00ffL, 0x00ffff00L, 0x0fffffffL),
            (12, 0x0f0f00ffL, 0x00ffff00L, 0x000f0000L),
            (16, 0x80000000L, 1L, 0x80000000L),
            (20, 0x80000000L, 1L, 1L),
            (24, 0x80000000L, 1L, 1L),
            (28, 0x80000000L, 1L, 0x80000000L),
        )
        simulate(new AtomicUnit) { dut =>
            initialize(dut)
            for (((op, oldValue, operand, newValue), index) <- cases.zipWithIndex) {
                request(dut, op, 0x80001000L + index * 4, operand)
                load(dut, oldValue)
                store(dut, newValue)
                response(dut, oldValue)
            }
        }
    }

    "AMO datapath matches signed and unsigned software results across operands" in {
        val mask = 0xffffffffL
        val random = new scala.util.Random(20260926L)
        val operands = Seq(0L, 1L, 0x7fffffffL, 0x80000000L, mask) ++
            Seq.fill(64)(random.nextLong() & mask)
        val operations = Seq(0, 1, 4, 8, 12, 16, 20, 24, 28)
        simulate(new AtomicUnit) { dut =>
            initialize(dut)
            for (op <- operations; (oldValue, index) <- operands.zipWithIndex) {
                val operand = operands((index * 17 + 3) % operands.size)
                val signedLess = operand.toInt < oldValue.toInt
                val unsignedLess = java.lang.Long.compareUnsigned(operand, oldValue) < 0
                val newValue = op match {
                    case 0 => (oldValue + operand) & mask
                    case 1 => operand
                    case 4 => oldValue ^ operand
                    case 8 => oldValue | operand
                    case 12 => oldValue & operand
                    case 16 => if (signedLess) operand else oldValue
                    case 20 => if (signedLess) oldValue else operand
                    case 24 => if (unsignedLess) operand else oldValue
                    case 28 => if (unsignedLess) oldValue else operand
                }
                request(dut, op, 0x80001000L + index * 4, operand)
                load(dut, oldValue)
                store(dut, newValue)
                response(dut, oldValue)
            }
        }
    }

    "LR and SC preserve one word reservation and SC clears it" in {
        simulate(new AtomicUnit) { dut =>
            initialize(dut)
            request(dut, op = 2, address = 0x80002000L, data = 0)
            load(dut, 0x13579bdfL)
            response(dut, 0x13579bdfL)

            request(dut, op = 3, address = 0x80002000L, data = 0x2468ace0L)
            store(dut, 0x2468ace0L)
            response(dut, 0)

            request(dut, op = 3, address = 0x80002000L, data = 0x11111111L)
            dut.io.store.request.valid.expect(false)
            response(dut, 1)
        }
    }

    "only a committed store to the reserved word invalidates LR" in {
        simulate(new AtomicUnit) { dut =>
            initialize(dut)
            request(dut, op = 2, address = 0x80002000L, data = 0)
            load(dut, 0x13579bdfL)
            response(dut, 0x13579bdfL)
            committedStore(dut, 0x80003000L)
            dut.clock.step(3)
            request(dut, op = 3, address = 0x80002000L, data = 0x2468ace0L)
            store(dut, 0x2468ace0L)
            response(dut, 0)

            request(dut, op = 2, address = 0x80002000L, data = 0)
            load(dut, 0x13579bdfL)
            response(dut, 0x13579bdfL)
            committedStore(dut, 0x80002001L)
            request(dut, op = 3, address = 0x80002000L, data = 0x11111111L)
            dut.io.store.request.valid.expect(false)
            response(dut, 1)
        }
    }

    "faults and an unwritable destination never assert the PRF write pulse" in {
        simulate(new AtomicUnit) { dut =>
            initialize(dut)
            dut.io.response.ready.poke(false)
            dut.io.request.valid.poke(true)
            dut.io.request.bits.robIdx.poke(9)
            dut.io.request.bits.prd.poke(7)
            dut.io.request.bits.vaddr.poke(0x80003000L)
            dut.io.request.bits.paddr.poke(0x80003000L)
            dut.io.request.bits.data.poke(0)
            dut.io.request.bits.op.poke(2)
            dut.io.request.bits.uncache.poke(false)
            dut.io.request.bits.exception.poke(5)
            dut.clock.step()
            dut.io.request.valid.poke(false)
            dut.io.response.valid.expect(true)
            dut.io.response.bits.exception.expect(5)
            dut.io.writeResult.expect(false)
            dut.io.response.ready.poke(true)
            dut.io.writeResult.expect(false)
            dut.clock.step()

            request(dut, op = 2, address = 0x80003004L, data = 0)
            dut.io.load.request.ready.poke(true)
            dut.clock.step()
            dut.io.load.request.ready.poke(false)
            dut.io.load.response.valid.poke(true)
            dut.io.load.response.bits.data.poke(0)
            dut.io.load.response.bits.exception.poke(5)
            dut.io.load.response.bits.retry.poke(false)
            dut.clock.step()
            dut.io.load.response.valid.poke(false)
            dut.io.response.valid.expect(true)
            dut.io.writeResult.expect(false)
            dut.clock.step()

            dut.io.request.valid.poke(true)
            dut.io.request.bits.prd.poke(0)
            dut.io.request.bits.exception.poke(0)
            dut.io.request.bits.op.poke(3)
            dut.clock.step()
            dut.io.request.valid.poke(false)
            dut.io.response.valid.expect(true)
            dut.io.writeResult.expect(false)
            dut.clock.step()

            request(dut, op = 1, address = 0x80003008L, data = 1)
            load(dut, 0x2468ace0L)
            dut.io.store.request.ready.poke(true)
            dut.clock.step()
            dut.io.store.request.ready.poke(false)
            dut.io.store.response.valid.poke(true)
            dut.io.store.response.bits.exception.poke(7)
            dut.clock.step()
            dut.io.store.response.valid.poke(false)
            dut.io.response.valid.expect(true)
            dut.io.response.bits.exception.expect(7)
            dut.io.writeResult.expect(false)
        }
    }

    "a held successful response writes only on its accepted cycle" in {
        simulate(new AtomicUnit) { dut =>
            initialize(dut)
            request(dut, op = 2, address = 0x80004000L, data = 0)
            load(dut, 0x13579bdfL)
            dut.io.response.ready.poke(false)
            dut.io.response.valid.expect(true)
            dut.io.writeResult.expect(false)
            dut.clock.step(2)
            dut.io.writeResult.expect(false)
            dut.io.response.ready.poke(true)
            dut.io.writeResult.expect(true)
            dut.clock.step()
            dut.io.writeResult.expect(false)
        }
    }
}
