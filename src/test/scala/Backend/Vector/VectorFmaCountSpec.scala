// SPDX-License-Identifier: MPL-2.0
// Adapted from Zircon-2026 VectorComputeSpec.
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class VectorFmaCountSpec extends AnyFreeSpec with ChiselSim {
    "masked and tail elements retain their FMA count under completion backpressure" in {
        simulate(new VectorComputeHarness) { dut =>
            dut.io.registerReady.poke((BigInt(1) << 64) - 1)
            VectorTestUtils.clear(dut.io.initialize); VectorTestUtils.clear(dut.io.in.bits)
            dut.io.in.valid.poke(false); dut.io.address.poke(0); dut.io.roundingMode.poke(0)
            dut.io.scalarData.poke(BigInt("3f800000",16)); dut.io.flush.poke(false)
            dut.io.completion.ready.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for (address <- Seq(0) ++ (4 until 8) ++ (12 until 16)) {
                dut.io.initialize.valid.poke(true); dut.io.initialize.address.poke(address)
                for (lane <- 0 until 8) {
                    dut.io.initialize.data(lane).poke(
                        if (address == 0 && lane == 0) BigInt("55555555",16)
                        else if (address >= 4 && address < 8) BigInt("40000000",16) else BigInt(0))
                    dut.io.initialize.bytes(lane).poke(15)
                }
                dut.clock.step()
            }
            dut.io.initialize.valid.poke(false)
            val input=dut.io.in.bits
            input.scalar.inst.poke((BigInt(0x2c) << 26) | (5 << 12) | 0x57)
            input.scalar.prs(0).poke(65)
            input.vector.control.execution.poke(VectorExecution.Multiply)
            input.vector.control.operation.poke(MultiplyOp.FMADD.litValue)
            input.vector.control.scalar.poke(true); input.vector.control.floating.poke(true)
            input.vector.control.writesVector.poke(true); input.vector.control.readsDestination.poke(true)
            input.vector.destination.count.poke(4)
            input.vector.state.vtype.poke(0xd2); input.vector.state.vl.poke(19)
            input.vector.state.vstart.poke(3)
            input.vector.groups.mask.poke(true); input.vector.mask.poke(0)
            input.vector.groups.sourceCount(0).poke(4); input.vector.groups.sourceCount(2).poke(4)
            for (group <- 0 until 4) {
                input.vector.source(0)(group).poke(4+group)
                input.vector.source(2)(group).poke(12+group)
                input.vector.destination.physical(group).poke(32+group)
            }
            dut.io.in.valid.poke(true)
            var cycles=0
            while (!dut.io.in.ready.peek().litToBoolean && cycles<20) { dut.clock.step(); cycles+=1 }
            assert(dut.io.in.ready.peek().litToBoolean)
            dut.clock.step(); dut.io.in.valid.poke(false)
            cycles=0
            while (!dut.io.completion.valid.peek().litToBoolean && cycles<100) { dut.clock.step(); cycles+=1 }
            assert(dut.io.completion.valid.peek().litToBoolean)
            // Only even elements 4,6,...,18 are active: eight scalar FMAs.
            for (_ <- 0 until 5) {
                dut.io.completion.valid.expect(true)
                dut.io.completion.bits.retirement.fmaElements.expect(8)
                dut.clock.step()
            }
            dut.io.completion.ready.poke(true); dut.clock.step()
            dut.io.completion.valid.expect(false)
        }
    }

    "independent LMUL4 VF FMAs collect without scalar preparation bubbles" in {
        simulate(new VectorComputeHarness) { dut =>
            dut.io.registerReady.poke((BigInt(1) << 64) - 1)
            VectorTestUtils.clear(dut.io.initialize); VectorTestUtils.clear(dut.io.in.bits)
            dut.io.in.valid.poke(false); dut.io.address.poke(0); dut.io.roundingMode.poke(0)
            dut.io.scalarData.poke(BigInt("3f800000", 16)); dut.io.flush.poke(false)
            dut.io.completion.ready.poke(true)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for (address <- 4 until 20) {
                dut.io.initialize.valid.poke(true); dut.io.initialize.address.poke(address)
                for (lane <- 0 until 8) {
                    dut.io.initialize.data(lane).poke(if (address < 8) BigInt("40000000", 16) else BigInt(0))
                    dut.io.initialize.bytes(lane).poke(15)
                }
                dut.clock.step()
            }
            dut.io.initialize.valid.poke(false)
            val input = dut.io.in.bits
            input.scalar.inst.poke((BigInt(0x2c) << 26) | (1 << 25) | (5 << 12) | 0x57)
            input.vector.control.execution.poke(VectorExecution.Multiply)
            input.vector.control.operation.poke(MultiplyOp.FMADD.litValue)
            input.vector.control.scalar.poke(true); input.vector.control.floating.poke(true)
            input.vector.control.writesVector.poke(true); input.vector.control.readsDestination.poke(true)
            input.vector.destination.count.poke(4)
            input.vector.state.vtype.poke(0xd2); input.vector.state.vl.poke(32)
            input.vector.groups.sourceCount(0).poke(4); input.vector.groups.sourceCount(2).poke(4)
            for (group <- 0 until 8) {
                input.vector.source(0)(group).poke(4 + group)
                input.vector.source(2)(group).poke(12 + group)
                input.vector.destination.physical(group).poke(32 + group)
            }
            var accepted = Vector.empty[Int]
            var writes = Vector.empty[Int]
            var completions = 0
            for (cycle <- 0 until 100 if completions < 8) {
                dut.io.in.valid.poke(accepted.size < 8)
                input.scalar.robIdx.poke(accepted.size)
                input.scalar.prs(0).poke(65 + accepted.size)
                if (accepted.size < 8 && dut.io.in.ready.peek().litToBoolean) accepted :+= cycle
                if (dut.io.writeback.valid.peek().litToBoolean) {
                    writes :+= cycle
                    for (lane <- 0 until 8) dut.io.writeback.bits.data(lane).expect(BigInt("40000000", 16))
                }
                if (dut.io.completion.valid.peek().litToBoolean) {
                    dut.io.completion.bits.retirement.fmaElements.expect(32)
                    completions += 1
                }
                dut.clock.step()
            }
            assert(accepted.size == 8 && completions == 8 && writes.size == 32)
            assert(accepted.sliding(2).forall(pair => pair(1) - pair(0) == 4), s"VF acceptance: $accepted")
            assert(writes.sliding(2).forall(pair => pair(1) - pair(0) == 1), s"VF writeback: $writes")
        }
    }

}
