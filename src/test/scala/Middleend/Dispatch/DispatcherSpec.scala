import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class DispatcherSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: Dispatcher): Unit = {
        dut.io.in.valid.poke(0)
        dut.io.in.entries.foreach(BackendPackageTestUtils.clear)
        dut.io.memoryEntries.foreach(BackendPackageTestUtils.clear)
        dut.io.resourcePrefix.poke((BigInt(1) << dut.issue.dispatchWidth) - 1)
        val capacities = Seq(
            dut.issue.arith0Entries,
            dut.issue.arith1Entries,
            dut.issue.mixArithEntries,
            dut.issue.loadEntries,
            dut.issue.loadStoreAddressEntries,
            dut.issue.storeDataEntries,
        )
        dut.io.freeCount.zip(capacities).foreach { case (count, capacity) => count.poke(capacity) }
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def instruction(dut: Dispatcher, lane: Int, rob: Int, fu: Int, op: Int = 0): Unit = {
        val item = dut.io.in.entries(lane)
        val memoryItem = dut.io.memoryEntries(lane)
        BackendPackageTestUtils.clear(item)
        BackendPackageTestUtils.clear(memoryItem)
        item.robIdx.poke(rob)
        item.fu.poke(fu)
        item.op.poke(op)
        memoryItem.robIdx.poke(rob)
        memoryItem.fu.poke(fu)
        memoryItem.op.poke(op)
    }

    "two ordinary ALUs use both low-latency pipelines while their queues are empty" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 10, DecodeUnit.ALU)
            instruction(dut, 1, 11, DecodeUnit.ALU)

            dut.io.accepted.expect(3)
            dut.io.arith0.valid.expect(1)
            dut.io.arith0.entries(0).robIdx.expect(10)
            dut.io.arith1.valid.expect(1)
            dut.io.arith1.entries(0).robIdx.expect(11)
            dut.io.mixArith.valid.expect(0)
        }
    }

    "three-wide dispatch accepts one parallel plan and compacts every queue output" in {
        val issue = IssueParams(dispatchWidth = 3)
        simulate(new Dispatcher(issue = issue)) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(7)
            instruction(dut, 0, 12, DecodeUnit.Load)
            instruction(dut, 1, 13, DecodeUnit.Branch)
            instruction(dut, 2, 14, DecodeUnit.Load)

            dut.io.accepted.expect(7)
            dut.io.arith1.valid.expect(1)
            dut.io.arith1.entries(0).robIdx.expect(13)
            dut.io.load.valid.expect(1)
            dut.io.load.entries(0).robIdx.expect(12)
            dut.io.loadStoreAddress.valid.expect(1)
            dut.io.loadStoreAddress.entries(0).robIdx.expect(14)
        }
    }

    "three-wide dispatch selects the largest feasible ordered prefix" in {
        val issue = IssueParams(dispatchWidth = 3)
        simulate(new Dispatcher(issue = issue)) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Load).poke(1)
            dut.io.freeCount(IssueQueueIndex.LoadStoreAddress).poke(1)
            dut.io.in.valid.poke(7)
            instruction(dut, 0, 15, DecodeUnit.Load)
            instruction(dut, 1, 16, DecodeUnit.Load)
            instruction(dut, 2, 17, DecodeUnit.Load)

            dut.io.accepted.expect(3)
            dut.io.load.valid.expect(1)
            dut.io.loadStoreAddress.valid.expect(1)
            val dispatched = Seq(
                dut.io.load.entries(0).robIdx.peek().litValue,
                dut.io.loadStoreAddress.entries(0).robIdx.peek().litValue,
            ).sorted
            assert(dispatched == Seq(BigInt(15), BigInt(16)))
        }
    }

    "a single-lane resource prefix reuses the lane-zero route from a feasible pair" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.resourcePrefix.poke(1)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 12, DecodeUnit.ALU)
            instruction(dut, 1, 13, DecodeUnit.ALU)

            dut.io.accepted.expect(1)
            val outputCount = dut.io.arith0.valid.peek().litValue +
                dut.io.arith1.valid.peek().litValue +
                dut.io.mixArith.valid.peek().litValue
            assert(outputCount == 1)
            val selected = Seq(dut.io.arith0, dut.io.arith1, dut.io.mixArith)
                .find(_.valid.peek().litValue == 1).get
            selected.entries(0).robIdx.expect(12)
        }
    }

    "a Branch and an ALU use both low-latency pipelines without pressure" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 20, DecodeUnit.Branch)
            instruction(dut, 1, 21, DecodeUnit.ALU)

            dut.io.accepted.expect(3)
            dut.io.arith1.valid.expect(1)
            dut.io.arith1.entries(0).robIdx.expect(21)
            dut.io.arith0.valid.expect(1)
            dut.io.arith0.entries(0).robIdx.expect(20)
            dut.io.mixArith.valid.expect(0)
        }
    }

    "two Loads use both address pipelines without a lane-by-lane allocation" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 30, DecodeUnit.Load)
            instruction(dut, 1, 31, DecodeUnit.Load)

            dut.io.accepted.expect(3)
            dut.io.load.valid.expect(1)
            dut.io.load.entries(0).robIdx.expect(30)
            dut.io.loadStoreAddress.valid.expect(1)
            dut.io.loadStoreAddress.entries(0).robIdx.expect(31)
        }
    }

    "a Store atomically creates address and remapped data tasks" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 40, DecodeUnit.Store)
            dut.io.in.entries(0).prs(0).poke(5)
            dut.io.in.entries(0).prs(1).poke(70)
            dut.io.in.entries(0).sourceValid(1).poke(true)
            dut.io.in.entries(0).sourceReady(1).poke(true)
            dut.io.in.entries(0).sourceSpecMask(1).poke(4)
            dut.io.memoryEntries(0).prs(0).poke(5)
            dut.io.memoryEntries(0).prs(1).poke(70)
            dut.io.memoryEntries(0).sourceValid(1).poke(true)
            dut.io.memoryEntries(0).sourceReady(1).poke(false)
            dut.io.memoryEntries(0).sourceSpecMask(1).poke(0)

            dut.io.accepted.expect(1)
            dut.io.loadStoreAddress.valid.expect(1)
            dut.io.loadStoreAddress.entries(0).prs(0).expect(5)
            dut.io.storeData.valid.expect(1)
            dut.io.storeData.entries(0).prs(0).expect(70)
            dut.io.storeData.entries(0).sourceValid(0).expect(true)
            dut.io.storeData.entries(0).sourceReady(0).expect(false)
            dut.io.storeData.entries(0).sourceSpecMask(0).expect(0)
            dut.io.storeData.entries(0).sourceValid(1).expect(false)
        }
    }

    "an Atomic creates address and integer data tasks" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 41, DecodeUnit.Atomic, op = 0)
            dut.io.memoryEntries(0).prs(0).poke(6)
            dut.io.memoryEntries(0).prs(1).poke(9)
            dut.io.memoryEntries(0).sourceValid(0).poke(true)
            dut.io.memoryEntries(0).sourceValid(1).poke(true)
            dut.io.memoryEntries(0).sourceReady(0).poke(true)
            dut.io.memoryEntries(0).sourceReady(1).poke(true)

            dut.io.accepted.expect(1)
            dut.io.loadStoreAddress.valid.expect(1)
            dut.io.loadStoreAddress.entries(0).prs(0).expect(6)
            dut.io.storeData.valid.expect(1)
            dut.io.storeData.entries(0).prs(0).expect(9)
            dut.io.storeData.entries(0).fu.expect(DecodeUnit.Atomic)
        }
    }

    "MixArith accepts at most one overflow ALU and preserves two native slots" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.freeCount(IssueQueueIndex.MixArith).poke(3)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 50, DecodeUnit.ALU)
            instruction(dut, 1, 51, DecodeUnit.ALU)

            dut.io.accepted.expect(1)
            dut.io.mixArith.valid.expect(1)
            dut.io.mixArith.entries(0).robIdx.expect(50)

            dut.io.freeCount(IssueQueueIndex.MixArith).poke(2)
            dut.io.accepted.expect(0)
        }
    }

    "short-queue pressure proactively sends an ALU to MixArith" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(dut.issue.arith0Entries / 2)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(dut.issue.arith1Entries / 2)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 55, DecodeUnit.ALU)

            dut.io.accepted.expect(1)
            dut.io.mixArith.valid.expect(1)
            dut.io.mixArith.entries(0).robIdx.expect(55)
            dut.io.arith0.valid.expect(0)
            dut.io.arith1.valid.expect(0)
        }
    }

    "a visible native Mix operation prevents an older ALU from occupying its only route" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 60, DecodeUnit.ALU)
            instruction(dut, 1, 61, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt)

            dut.io.accepted.expect(0)
            dut.io.mixArith.valid.expect(0)
        }
    }

    "a native-Mix burst protects the queue until a flush resets the performance state" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            for (rob <- 70 until 74) {
                instruction(dut, 0, rob, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt)
                dut.io.accepted.expect(1)
                dut.clock.step()
            }
            dut.io.in.valid.poke(0)
            dut.clock.step()

            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 74, DecodeUnit.ALU)
            dut.io.accepted.expect(0)

            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.accepted.expect(1)
            dut.io.mixArith.valid.expect(1)
        }
    }

    "Mix protection decays while Dispatch is blocked and cannot become permanent" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            for (rob <- 75 until 79) {
                instruction(dut, 0, rob, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt)
                dut.clock.step()
            }
            dut.io.in.valid.poke(0)
            dut.clock.step()

            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 79, DecodeUnit.ALU)
            dut.io.accepted.expect(0)
            dut.clock.step(20)
            dut.io.accepted.expect(1)
            dut.io.mixArith.valid.expect(1)
        }
    }

    "an unavailable older instruction prevents a younger instruction from bypassing Dispatch" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 80, DecodeUnit.Branch)
            instruction(dut, 1, 81, DecodeUnit.Load)

            dut.io.accepted.expect(0)
            dut.io.load.valid.expect(0)
            dut.io.loadStoreAddress.valid.expect(0)
        }
    }

    "an exception consumes ordered resources without entering an issue queue" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount.foreach(_.poke(0))
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 90, DecodeUnit.None)
            dut.io.in.entries(0).exception.valid.poke(true)

            dut.io.accepted.expect(1)
            dut.io.arith0.valid.expect(0)
            dut.io.arith1.valid.expect(0)
            dut.io.mixArith.valid.expect(0)
            dut.io.load.valid.expect(0)
            dut.io.loadStoreAddress.valid.expect(0)
            dut.io.storeData.valid.expect(0)
        }
    }
}
