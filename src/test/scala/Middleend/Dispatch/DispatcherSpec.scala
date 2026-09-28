import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class DispatcherSpec extends AnyFreeSpec with ChiselSim {
    private def selected(dut: Dispatcher, queue: Int, position: Int): BackendPackage = {
        val route = dut.io.enqueue(queue)
        val selection = route.selection(position).peek().litValue.toInt
        assert(Integer.bitCount(selection) == 1)
        route.candidates(Integer.numberOfTrailingZeros(selection))
    }

    private def setFreeCount(dut: Dispatcher, index: Int, count: Int): Unit = {
        val available = math.min(count, dut.issue.dispatchWidth)
        dut.io.freePrefix(index).poke((BigInt(1) << available) - 1)
    }

    private def initialize(dut: Dispatcher): Unit = {
        dut.io.in.valid.poke(0)
        dut.io.in.entries.foreach(BackendPackageTestUtils.clear)
        dut.io.dispatchClass.foreach { route =>
            route.shortArith.poke(false)
            route.mixArith.poke(false)
            route.load.poke(false)
            route.storeOrAtomic.poke(false)
            route.noIssue.poke(false)
        }
        dut.io.resourcePrefix.poke((BigInt(1) << dut.issue.dispatchWidth) - 1)
        dut.io.clearPreference.poke(false)
        dut.issue.queueParams.indices.foreach(index =>
            setFreeCount(dut, index, dut.issue.queueParams(index).entries)
        )
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def instruction(dut: Dispatcher, lane: Int, rob: Int, fu: Int, op: Int = 0): Unit = {
        val item = dut.io.in.entries(lane)
        BackendPackageTestUtils.clear(item)
        item.robIdx.poke(rob)
        item.fu.poke(fu)
        item.op.poke(op)
        val route = dut.io.dispatchClass(lane)
        route.shortArith.poke(fu == DecodeUnit.ALU || fu == DecodeUnit.Branch)
        route.mixArith.poke(
            fu == DecodeUnit.Multiply || fu == DecodeUnit.Divide || fu == DecodeUnit.FpMisc ||
                (fu == DecodeUnit.System && op >= SystemOp.CSRRW && op <= SystemOp.CSRRCI)
        )
        route.load.poke(fu == DecodeUnit.Load)
        route.storeOrAtomic.poke(fu == DecodeUnit.Store || fu == DecodeUnit.Atomic)
        route.noIssue.poke(false)
    }

    "two ordinary ALUs use both low-latency pipelines while their queues are empty" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 10, DecodeUnit.ALU)
            instruction(dut, 1, 11, DecodeUnit.ALU)

            dut.io.accepted.expect(3)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            selected(dut, IssueQueueIndex.Arith0, 0).robIdx.expect(10)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(1)
            selected(dut, IssueQueueIndex.Arith1, 0).robIdx.expect(11)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
        }
    }

    "three-wide dispatch compacts alternating routes" in {
        val issue = IssueParams(dispatchWidth = 3)
        simulate(new Dispatcher(issue = issue)) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(7)
            instruction(dut, 0, 12, DecodeUnit.Load)
            instruction(dut, 1, 13, DecodeUnit.Branch)
            instruction(dut, 2, 14, DecodeUnit.Load)

            dut.io.accepted.expect(7)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            selected(dut, IssueQueueIndex.Arith0, 0).robIdx.expect(13)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            val loads = Seq(
                selected(dut, IssueQueueIndex.Load, 0).robIdx.peek().litValue,
                selected(dut, IssueQueueIndex.LoadStoreAddress, 0).robIdx.peek().litValue,
            ).sorted
            assert(loads == Seq(BigInt(12), BigInt(14)))
        }
    }

    "three-wide dispatch retains a group when a route lacks capacity" in {
        val issue = IssueParams(dispatchWidth = 3)
        simulate(new Dispatcher(issue = issue)) { dut =>
            initialize(dut)
            setFreeCount(dut, IssueQueueIndex.Load, 1)
            setFreeCount(dut, IssueQueueIndex.LoadStoreAddress, 1)
            dut.io.in.valid.poke(7)
            instruction(dut, 0, 15, DecodeUnit.Load)
            instruction(dut, 1, 16, DecodeUnit.Load)
            instruction(dut, 2, 17, DecodeUnit.Load)

            dut.io.accepted.expect(0)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
        }
    }

    "inactive younger classes cannot affect live-lane routing or preference" in {
        simulate(new Dispatcher(issue = IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            instruction(dut, 0, 100, DecodeUnit.Load)
            instruction(dut, 1, 101, DecodeUnit.Load)
            instruction(dut, 2, 102, DecodeUnit.Load)
            dut.io.in.valid.poke(1)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
            dut.clock.step()

            dut.io.in.valid.poke(3)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            dut.io.accepted.expect(3)
        }
    }

    "inactive younger classes cannot consume capacity in a selected prefix" in {
        simulate(new Dispatcher(issue = IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            instruction(dut, 0, 103, DecodeUnit.ALU)
            instruction(dut, 1, 104, DecodeUnit.Load)
            instruction(dut, 2, 105, DecodeUnit.Load)
            setFreeCount(dut, IssueQueueIndex.Load, 0)
            setFreeCount(dut, IssueQueueIndex.LoadStoreAddress, 0)
            dut.io.in.valid.poke(1)

            dut.io.accepted.expect(1)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
        }
    }

    "successive single-lane groups alternate arithmetic and Load routes" in {
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

            setFreeCount(dut, IssueQueueIndex.Load, 6)
            setFreeCount(dut, IssueQueueIndex.LoadStoreAddress, 6)
            instruction(dut, 0, 20, DecodeUnit.Load)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(0)
            dut.clock.step()

            instruction(dut, 0, 21, DecodeUnit.Load)
            dut.io.enqueue(IssueQueueIndex.Load).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
        }
    }

    "stalled groups do not rotate routes and flush clears preferences" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 22, DecodeUnit.ALU)
            dut.clock.step()
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(1)

            setFreeCount(dut, IssueQueueIndex.Arith1, 0)
            dut.io.accepted.expect(0)
            dut.clock.step(2)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(0)
            setFreeCount(dut, IssueQueueIndex.Arith1, 3)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(1)

            dut.io.clearPreference.poke(true)
            dut.clock.step()
            dut.io.clearPreference.poke(false)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
        }
    }

    "a partial resource prefix retains the complete dispatch group" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.resourcePrefix.poke(1)
            dut.io.in.valid.poke(3)
            instruction(dut, 0, 12, DecodeUnit.ALU)
            instruction(dut, 1, 13, DecodeUnit.ALU)

            dut.io.accepted.expect(0)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(0)
            dut.io.enqueue(IssueQueueIndex.Arith1).valid.expect(0)
        }
    }

    "resource admission matches every three-wide prefix pair" in {
        simulate(new Dispatcher(issue = IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            dut.io.dispatchClass.foreach(_.noIssue.poke(true))
            for (requested <- 0 to 3; permitted <- 0 to 3) {
                val valid = (1 << requested) - 1
                dut.io.in.valid.poke(valid)
                dut.io.resourcePrefix.poke((1 << permitted) - 1)
                dut.io.accepted.expect(if (requested <= permitted) valid else 0)
                dut.clock.step()
            }
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
            selected(dut, IssueQueueIndex.Arith1, 0).robIdx.expect(21)
            dut.io.enqueue(IssueQueueIndex.Arith0).valid.expect(1)
            selected(dut, IssueQueueIndex.Arith0, 0).robIdx.expect(20)
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
                selected(dut, IssueQueueIndex.Load, 0).robIdx.peek().litValue,
                selected(dut, IssueQueueIndex.LoadStoreAddress, 0).robIdx.peek().litValue,
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
            dut.io.in.entries(0).sourceReady(1).poke(false)
            dut.io.in.entries(0).sourceSpecMask(1).poke(0)

            dut.io.accepted.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            selected(dut, IssueQueueIndex.LoadStoreAddress, 0).prs(0).expect(5)
            dut.io.enqueue(IssueQueueIndex.StoreData).valid.expect(1)
            selected(dut, IssueQueueIndex.StoreData, 0).prs(0).expect(70)
            selected(dut, IssueQueueIndex.StoreData, 0).sourceValid(0).expect(true)
            selected(dut, IssueQueueIndex.StoreData, 0).sourceReady(0).expect(false)
            selected(dut, IssueQueueIndex.StoreData, 0).sourceSpecMask(0).expect(0)
            selected(dut, IssueQueueIndex.StoreData, 0).sourceValid(1).expect(false)
        }
    }

    "an Atomic creates address and integer data tasks" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 41, DecodeUnit.Atomic, op = 0)
            dut.io.in.entries(0).prs(0).poke(6)
            dut.io.in.entries(0).prs(1).poke(9)
            dut.io.in.entries(0).sourceValid(0).poke(true)
            dut.io.in.entries(0).sourceValid(1).poke(true)
            dut.io.in.entries(0).sourceReady(0).poke(true)
            dut.io.in.entries(0).sourceReady(1).poke(true)

            dut.io.accepted.expect(1)
            dut.io.enqueue(IssueQueueIndex.LoadStoreAddress).valid.expect(1)
            selected(dut, IssueQueueIndex.LoadStoreAddress, 0).prs(0).expect(6)
            dut.io.enqueue(IssueQueueIndex.StoreData).valid.expect(1)
            selected(dut, IssueQueueIndex.StoreData, 0).prs(0).expect(9)
            selected(dut, IssueQueueIndex.StoreData, 0).fu.expect(DecodeUnit.Atomic)
        }
    }

    "an ALU cannot overflow into MixArith" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            setFreeCount(dut, IssueQueueIndex.Arith0, 0)
            setFreeCount(dut, IssueQueueIndex.Arith1, 0)
            setFreeCount(dut, IssueQueueIndex.MixArith, 3)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 50, DecodeUnit.ALU)

            dut.io.accepted.expect(0)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(0)
        }
    }

    "a native Mix operation uses MixArith independently of the ALU queues" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            setFreeCount(dut, IssueQueueIndex.Arith0, 0)
            setFreeCount(dut, IssueQueueIndex.Arith1, 0)
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 55, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt)

            dut.io.accepted.expect(1)
            dut.io.enqueue(IssueQueueIndex.MixArith).valid.expect(1)
            selected(dut, IssueQueueIndex.MixArith, 0).robIdx.expect(55)
        }
    }

    "a visible native Mix operation prevents an older ALU from occupying its only route" in {
        simulate(new Dispatcher) { dut =>
            initialize(dut)
            setFreeCount(dut, IssueQueueIndex.Arith0, 0)
            setFreeCount(dut, IssueQueueIndex.Arith1, 0)
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
            setFreeCount(dut, IssueQueueIndex.Arith1, 0)
            setFreeCount(dut, IssueQueueIndex.Arith0, 0)
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
            dut.issue.queueParams.indices.foreach(index => setFreeCount(dut, index, 0))
            dut.io.in.valid.poke(1)
            instruction(dut, 0, 90, DecodeUnit.None)
            dut.io.in.entries(0).exception.valid.poke(true)
            dut.io.dispatchClass(0).noIssue.poke(true)

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
