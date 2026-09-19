import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class DispatcherSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: Dispatcher): Unit = {
        dut.io.in.valid.poke(0)
        dut.io.in.entries.foreach(BackendPackageTestUtils.clear)
        dut.io.memoryEntries.foreach(BackendPackageTestUtils.clear)
        dut.io.resourcePrefix.poke((BigInt(1) << dut.issue.dispatchWidth) - 1)
        dut.io.freeCount.zip(dut.issue.queueParams).foreach { case (count, queue) => count.poke(queue.entries) }
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
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith0).entries(0).robIdx.expect(10)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith1).entries(0).robIdx.expect(11)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
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
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith0).entries(0).robIdx.expect(13)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            val loads = Seq(
                dut.io.enqueue(IssueQueueIndex.Load).entries(0).robIdx.peek().litValue,
                dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).entries(0).robIdx.peek().litValue,
            ).sorted
            assert(loads == Seq(BigInt(12), BigInt(14)))
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
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            val dispatched = Seq(
                dut.io.enqueue(IssueQueueIndex.Load).entries(0).robIdx.peek().litValue,
                dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).entries(0).robIdx.peek().litValue,
            ).sorted
            assert(dispatched == Seq(BigInt(15), BigInt(16)))
        }
    }

    "three-wide dispatch balances single arithmetic and Load operations" in {
        simulate(new Dispatcher(issue = IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 18, DecodeUnit.ALU)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(0)
            dut.clock.step()

            instruction(dut, 0, 19, DecodeUnit.ALU)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(1)
            dut.clock.step()

            dut.io.freeCount(IssueQueueIndex.Load).poke(6)
            dut.io.freeCount(IssueQueueIndex.LoadStoreAddress).poke(6)
            instruction(dut, 0, 20, DecodeUnit.Load)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
            dut.clock.step()

            instruction(dut, 0, 21, DecodeUnit.Load)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
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
            val outputCount = dut.io.enqueue(IssueQueueIndex.Arith0).valid.peek().litValue +
                dut.io.enqueue(IssueQueueIndex.Arith1).valid.peek().litValue +
                dut.io.enqueue(IssueQueueIndex.MixArith).valid.peek().litValue
            assert(outputCount == 1)
            val selected = Seq(dut.io.enqueue(IssueQueueIndex.Arith0), dut.io.enqueue(IssueQueueIndex.Arith1), dut.io.enqueue(IssueQueueIndex.MixArith))
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
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith1).entries(0).robIdx.expect(21)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith0).entries(0).robIdx.expect(20)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
        }
    }

    "two Loads use both address pipelines without a lane-by-lane allocation" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 30, DecodeUnit.Load)
            instruction(dut, 1, 31, DecodeUnit.Load)

            dut.io.accepted.expect(3)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            val loads = Seq(
                dut.io.enqueue(IssueQueueIndex.Load).entries(0).robIdx.peek().litValue,
                dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).entries(0).robIdx.peek().litValue,
            ).sorted
            assert(loads == Seq(BigInt(30), BigInt(31)))
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
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).entries(0).prs(0).expect(5)
            dut.io.enqueue(IssueQueueIndex.StoreData).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).prs(0).expect(70)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).sourceValid(0).expect(true)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).sourceReady(0).expect(false)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).sourceSpecMask(0).expect(0)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).sourceValid(1).expect(false)
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
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).entries(0).prs(0).expect(6)
            dut.io.enqueue(IssueQueueIndex.StoreData).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).prs(0).expect(9)
            dut.io.enqueue(IssueQueueIndex.StoreData).entries(0).fu.expect(DecodeUnit.Atomic)
        }
    }

    "an ALU cannot overflow into MixArith" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.freeCount(IssueQueueIndex.MixArith).poke(3)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 50, DecodeUnit.ALU)

            dut.io.accepted.expect(0)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
        }
    }

    "a native Mix operation uses MixArith independently of the ALU queues" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.freeCount(IssueQueueIndex.Arith0).poke(0)
            dut.io.freeCount(IssueQueueIndex.Arith1).poke(0)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 55, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt)

            dut.io.accepted.expect(1)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.MixArith).entries(0).robIdx.expect(55)
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
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
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
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
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
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.StoreData).valid.expect(0)
        }
    }
}
