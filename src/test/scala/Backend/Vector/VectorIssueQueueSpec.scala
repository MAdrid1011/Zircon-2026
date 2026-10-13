import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class VectorIssueQueueSpec extends AnyFreeSpec with ChiselSim {
    "ordered memory issue keeps a younger ready Load behind an operand-blocked Store" in {
        simulate(new IssueQueue(q = IssueQueueParams(4, IssueQueueProfile.Vector,
            enqueueWidth = 1, wakeupPorts = 2), orderedIssue = true)) { dut =>
            VectorTestUtils.clear(dut.io.enq); VectorTestUtils.clear(dut.io.wakeup)
            VectorTestUtils.clear(dut.io.speculation); VectorTestUtils.clear(dut.io.vector.get.enqueue)
            VectorTestUtils.clear(dut.io.vector.get.wakeup)
            dut.io.issue.ready.poke(true); dut.io.flush.poke(false)
            dut.io.vector.get.ready.poke(((BigInt(1) << 64) - 1) & ~(BigInt(1) << 40))
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            for ((rob, tag, operation) <- Seq((3, 40, VectorExecution.Store), (4, 12, VectorExecution.Load))) {
                BackendPackageTestUtils.clear(dut.io.enq.candidates(0))
                dut.io.enq.valid.poke(1); dut.io.enq.selection(0).poke(1)
                dut.io.enq.candidates(0).fu.poke(DecodeUnit.Vector)
                dut.io.enq.candidates(0).robIdx.poke(rob)
                dut.io.vector.get.enqueue.control.execution.poke(operation)
                dut.io.vector.get.enqueue.groups.sourceCount(0).poke(1)
                dut.io.vector.get.enqueue.source(0)(0).poke(tag)
                dut.clock.step()
            }
            dut.io.enq.valid.poke(0)
            dut.io.issue.valid.expect(false); dut.clock.step(3); dut.io.issue.valid.expect(false)
            dut.io.vector.get.wakeup(0).valid.poke(true); dut.io.vector.get.wakeup(0).bits.poke(40)
            dut.clock.step(); dut.io.vector.get.wakeup(0).valid.poke(false)
            dut.io.issue.valid.expect(true); dut.io.issue.bits.robIdx.expect(3)
            dut.clock.step(); dut.io.issue.bits.robIdx.expect(4)
            dut.clock.step(); dut.io.occupancy.expect(0)
        }
    }
    "shared IQ selects oldest ready groups, including v0, and holds metadata under stalls" in {
        simulate(new IssueQueue(q = IssueQueueParams(4, IssueQueueProfile.Vector,
            enqueueWidth = 1, wakeupPorts = 2))) { dut =>
            VectorTestUtils.clear(dut.io.enq)
            VectorTestUtils.clear(dut.io.wakeup)
            VectorTestUtils.clear(dut.io.speculation)
            VectorTestUtils.clear(dut.io.vector.get.enqueue)
            VectorTestUtils.clear(dut.io.vector.get.wakeup)
            dut.io.issue.ready.poke(false)
            dut.io.flush.poke(false)
            val all = (BigInt(1) << 64) - 1
            dut.io.vector.get.ready.poke(all & ~(BigInt(1) << 0) & ~(BigInt(1) << 50))
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            def enqueue(rob: Int, tag: Int, masked: Boolean): Unit = {
                BackendPackageTestUtils.clear(dut.io.enq.candidates(0))
                VectorTestUtils.clear(dut.io.vector.get.enqueue)
                dut.io.enq.candidates(0).fu.poke(DecodeUnit.Vector)
                dut.io.enq.candidates(0).robIdx.poke(rob)
                dut.io.enq.selection(0).poke(1)
                dut.io.enq.valid.poke(1)
                val metadata = dut.io.vector.get.enqueue
                metadata.groups.sourceCount(0).poke(1)
                metadata.source(0)(0).poke(tag)
                metadata.groups.mask.poke(masked)
                metadata.mask.poke(0)
                metadata.destination.base.poke(rob)
                dut.clock.step(); dut.io.enq.valid.poke(0)
            }
            enqueue(1, 50, true)
            dut.io.issue.valid.expect(false)
            enqueue(2, 20, false)
            dut.io.issue.valid.expect(true); dut.io.issue.bits.robIdx.expect(2)
            dut.io.vector.get.issue.destination.base.expect(2)
            dut.clock.step() // Lock younger ready item while its consumer stalls.
            dut.io.vector.get.wakeup(0).valid.poke(true)
            dut.io.vector.get.wakeup(0).bits.poke(50)
            dut.io.vector.get.wakeup(1).valid.poke(true)
            dut.io.vector.get.wakeup(1).bits.poke(0)
            dut.clock.step()
            VectorTestUtils.clear(dut.io.vector.get.wakeup)
            dut.io.issue.bits.robIdx.expect(2)
            dut.io.vector.get.issue.destination.base.expect(2)
            dut.io.issue.ready.poke(true); dut.clock.step()
            dut.io.issue.bits.robIdx.expect(1)
            dut.io.vector.get.issue.source(0)(0).expect(50)
            dut.clock.step(); dut.io.occupancy.expect(0)
            // Wake at enqueue must not be lost when the ReadyBoard Q is old.
            dut.io.vector.get.wakeup(0).valid.poke(true)
            dut.io.vector.get.wakeup(0).bits.poke(50)
            dut.io.issue.ready.poke(false)
            enqueue(3, 50, false)
            VectorTestUtils.clear(dut.io.vector.get.wakeup)
            dut.io.issue.valid.expect(true)
            dut.io.flush.poke(true); dut.io.issue.valid.expect(false)
            dut.clock.step(); dut.io.flush.poke(false)
            dut.io.occupancy.expect(0); dut.io.freeCount.expect(4)
            enqueue(4, 50, false)
            dut.io.issue.valid.expect(false) // Reused slot cannot inherit old readiness.
            dut.io.vector.get.wakeup(0).valid.poke(true)
            dut.io.vector.get.wakeup(0).bits.poke(50)
            dut.clock.step(); VectorTestUtils.clear(dut.io.vector.get.wakeup)
            dut.io.issue.valid.expect(true); dut.io.issue.bits.robIdx.expect(4)
        }
    }
}
