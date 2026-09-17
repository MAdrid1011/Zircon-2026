import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ReorderBufferSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: ReorderBuffer): Unit = {
        dut.io.request.valid.poke(0)
        dut.io.request.store.poke(0)
        dut.io.enqueue.valid.poke(0)
        dut.io.pop.poke(0)
        dut.io.clear.poke(false)
        dut.io.readIdx.foreach(_.poke(0))
        dut.io.completion.foreach { port =>
            port.valid.poke(false)
            port.bits.robIdx.poke(0)
            port.bits.exception.valid.poke(false)
            port.bits.exception.cause.poke(0)
            port.bits.exception.tval.poke(0)
            port.bits.fflags.poke(0)
            port.bits.fpFlagsValid.poke(false)
        }
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
    }

    private def enqueueLane(dut: ReorderBuffer, lane: Int, token: Int, pc: Int, robIdx: BigInt): Unit = {
        val entry = dut.io.enqueue.entries(lane)
        entry.context.fetchToken.poke(token)
        entry.context.ftqIdx.poke(0)
        entry.context.slot.poke(lane)
        entry.context.packetEnd.poke(true)
        entry.context.instruction.pc.poke(pc)
        entry.context.instruction.inst.poke(0x13)
        entry.context.instruction.fu.poke(ZirconConfig.DecodeUnit.ALU)
        entry.context.instruction.op.poke(0)
        entry.context.instruction.exception.valid.poke(false)
        entry.context.instruction.exception.cause.poke(0)
        entry.context.instruction.exception.tval.poke(0)
        entry.destination.rd.poke(lane + 1)
        entry.destination.isFp.poke(false)
        entry.destination.prd.poke(lane + 10)
        entry.destination.pprd.poke(lane + 6)
        entry.allocation.robIdx.poke(robIdx)
        entry.allocation.sqTail.poke(0)
        entry.allocation.sqIdx.poke(0)
    }

    private def complete(dut: ReorderBuffer, port: Int, robIdx: BigInt): Unit = {
        dut.io.completion(port).valid.poke(true)
        dut.io.completion(port).bits.robIdx.poke(robIdx)
        dut.io.completion(port).bits.data.poke(0)
        dut.io.completion(port).bits.exception.valid.poke(false)
        dut.io.completion(port).bits.exception.cause.poke(0)
        dut.io.completion(port).bits.exception.tval.poke(0)
        dut.io.completion(port).bits.fflags.poke(0)
        dut.io.completion(port).bits.fpFlagsValid.poke(false)
    }

    "out-of-order completion exposes only a completed retirement prefix" in {
        simulate(new ReorderBuffer(dispatchWidth = 3)) { dut =>
            initialize(dut)
            dut.io.request.valid.poke(7)
            dut.io.request.store.poke(0)
            dut.io.availablePrefix.expect(7)
            val identities = dut.io.allocation.map(_.peek().litValue)
            assert(identities.distinct.size == 3)
            dut.io.enqueue.valid.poke(7)
            for (lane <- 0 until 3) {
                enqueueLane(dut, lane, token = 11, pc = 0x1000 + lane * 4, identities(lane))
            }
            dut.clock.step()
            dut.io.request.valid.poke(0)
            dut.io.enqueue.valid.poke(0)
            dut.io.readIdx(0).poke(identities(2))
            dut.io.readPc(0).expect(0x1008)

            complete(dut, 0, identities(1))
            complete(dut, 1, identities(2))
            dut.clock.step()
            dut.io.completion(0).valid.poke(false)
            dut.io.completion(1).valid.poke(false)
            dut.io.head(0).bits.complete.expect(false)
            dut.io.head(1).bits.complete.expect(true)
            dut.io.head(2).bits.complete.expect(true)

            complete(dut, 0, identities(0))
            dut.clock.step()
            dut.io.completion(0).valid.poke(false)
            for (lane <- 0 until 3) {
                dut.io.head(lane).valid.expect(true)
                dut.io.head(lane).bits.complete.expect(true)
                dut.io.headIdx(lane).expect(identities(lane))
            }
            dut.io.pop.poke(7)
            dut.clock.step()
            dut.io.pop.poke(0)
            dut.io.head(0).valid.expect(false)
        }
    }

    "clear resets allocation generation after discarding speculative entries" in {
        simulate(new ReorderBuffer(dispatchWidth = 2)) { dut =>
            initialize(dut)
            dut.io.request.valid.poke(3)
            val first = dut.io.allocation(0).peek().litValue
            dut.io.enqueue.valid.poke(3)
            for (lane <- 0 until 2) {
                enqueueLane(dut, lane, token = 1, pc = 0x2000 + lane * 4, dut.io.allocation(lane).peek().litValue)
            }
            dut.clock.step()
            dut.io.request.valid.poke(0)
            dut.io.enqueue.valid.poke(0)
            dut.io.clear.poke(true)
            dut.clock.step()
            dut.io.clear.poke(false)
            dut.io.request.valid.poke(1)
            dut.io.allocation(0).expect(first)
            dut.io.head(0).valid.expect(false)
        }
    }
}
