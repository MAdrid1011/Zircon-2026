import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class BackendPackageConstructionTop extends Module {
    private val backend = BackendParams()
    private val frontend = FrontendParams()
    val io = IO(new Bundle {
        val instruction = Input(new FrontendInstruction(frontend))
        val physical = Input(new BackendRenameInfo(backend))
        val allocation = Input(new BackendAllocation(backend))
        val base = Output(new BackendPackage(backend))
        val storeData = Output(new BackendPackage(backend))
    })
    io.base := BackendPackage.fromFrontend(io.instruction, io.physical, io.allocation, backend)
    io.storeData := BackendPackage.forStoreData(io.base)
}

class IssueQueueSpec extends AnyFreeSpec with ChiselSim {
    private val backend = BackendParams()

    private def clearEnqueue(entry: BackendPackage): Unit = BackendPackageTestUtils.clear(entry)

    private def initialize(dut: IssueQueue): Unit = {
        dut.io.enq.valid.poke(0)
        dut.io.enq.entries.foreach(clearEnqueue)
        dut.io.issue.ready.poke(false)
        dut.io.wakeup.foreach { wakeup =>
            wakeup.prd.poke(0)
            wakeup.specMask.poke(0)
        }
        dut.io.speculation.resolvedMask.poke(0)
        dut.io.speculation.failedMask.poke(0)
        dut.io.flush.poke(false)
        dut.io.csrGrant.foreach { grant =>
            grant.valid.poke(false)
            grant.bits.robIdx.poke(0)
        }
        dut.io.loadGrant.foreach { grant =>
            grant.valid.poke(false)
            grant.bits.robIdx.poke(0)
        }
        dut.io.completed.foreach { feedback =>
            feedback.valid.poke(false)
            feedback.bits.robIdx.poke(0)
            feedback.bits.uncache.poke(false)
        }
        dut.io.retry.foreach { feedback =>
            feedback.valid.poke(false)
            feedback.bits.robIdx.poke(0)
            feedback.bits.uncache.poke(false)
        }
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    private def packageEntry(
        entry: BackendPackage,
        rob: Int,
        fu: Int,
        op: Int = 0,
        sourceTag: Option[Int] = None,
        sourceReady: Boolean = true,
        sourceSpecMask: Int = 0,
    ): Unit = {
        clearEnqueue(entry)
        entry.robIdx.poke(rob)
        entry.fu.poke(fu)
        entry.op.poke(op)
        sourceTag.foreach { tag =>
            entry.prs(0).poke(tag)
            entry.sourceValid(0).poke(true)
            entry.sourceReady(0).poke(sourceReady)
            entry.sourceSpecMask(0).poke(sourceSpecMask)
        }
    }

    private def enqueue(
        dut: IssueQueue,
        items: Seq[(Int, Int, Int, Option[Int], Boolean)],
    ): Unit = {
        require(items.nonEmpty && items.size <= dut.q.enqueueWidth)
        dut.io.enq.valid.poke((1 << items.size) - 1)
        dut.io.enq.entries.foreach(clearEnqueue)
        items.zipWithIndex.foreach { case ((rob, fu, op, tag, ready), lane) =>
            packageEntry(dut.io.enq.entries(lane), rob, fu, op, tag, ready)
        }
        dut.clock.step()
        dut.io.enq.valid.poke(0)
    }

    "a single removal compacts younger entries and preserves two-wide append order" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 2)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(
                dut,
                Seq(
                    (1, DecodeUnit.ALU, 0, Some(3), false),
                    (2, DecodeUnit.ALU, 0, None, true)
                )
            )
            dut.io.occupancy.expect(2)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(2)

            dut.io.issue.ready.poke(true)
            dut.clock.step()
            dut.io.issue.valid.expect(false)
            dut.io.occupancy.expect(1)

            dut.io.wakeup(0).prd.poke(3)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.bits.robIdx.expect(1)

            dut.io.enq.valid.poke(3)
            packageEntry(dut.io.enq.entries(0), 3, DecodeUnit.ALU)
            packageEntry(dut.io.enq.entries(1), 4, DecodeUnit.Branch)
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.io.issue.bits.robIdx.expect(3)
            dut.io.occupancy.expect(2)
            dut.clock.step()
            dut.io.issue.bits.robIdx.expect(4)
        }
    }

    "backpressure locks a younger selection when an older dependency wakes" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(
                dut,
                Seq(
                    (10, DecodeUnit.ALU, 0, Some(5), false),
                    (11, DecodeUnit.ALU, 0, None, true)
                )
            )
            dut.io.issue.bits.robIdx.expect(11)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(5)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(11)
            dut.io.issue.ready.poke(true)
            dut.clock.step()
            dut.io.issue.bits.robIdx.expect(10)
        }
    }

    "a locked instruction is blocked when delayed queue maintenance observes a failed token" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.enq.valid.poke(1)
            packageEntry(
                dut.io.enq.entries(0),
                12,
                DecodeUnit.ALU,
                sourceTag = Some(5),
                sourceReady = true,
                sourceSpecMask = 1,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(12)
            dut.clock.step()

            dut.io.speculation.failedMask.poke(1)
            // The execution boundary consumes and kills this presentation. The
            // IQ maintenance view observes the failure on the following cycle.
            dut.io.issue.valid.expect(true)
            dut.clock.step()
            dut.io.issue.valid.expect(false)

            dut.io.speculation.failedMask.poke(0)
            dut.io.wakeup(0).prd.poke(5)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.valid.expect(true)
        }
    }

    "commit flush clears every queued instruction even with a coincident enqueue" in {
        val q = IssueQueueParams(8, IssueQueueProfile.MixArith, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(
                dut,
                Seq(
                    (20, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt, None, true),
                    (21, DecodeUnit.Divide, DivideOp.DIV, None, true)
                )
            )
            enqueue(
                dut,
                Seq(
                    (22, DecodeUnit.Multiply, MultiplyOp.MULH.litValue.toInt, None, true),
                    (23, DecodeUnit.Divide, DivideOp.DIVU, None, true)
                )
            )
            dut.io.enq.valid.poke(3)
            packageEntry(dut.io.enq.entries(0), 24, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt)
            packageEntry(dut.io.enq.entries(1), 25, DecodeUnit.Divide, DivideOp.REM)
            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.io.flush.poke(false)
            dut.io.occupancy.expect(0)
            dut.io.issue.valid.expect(false)

            enqueue(dut, Seq((26, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt, None, true)))
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(26)
        }
    }

    "CSR authorization preserves older order and names the exact instruction" in {
        val q = IssueQueueParams(6, IssueQueueProfile.MixArith, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(
                dut,
                Seq(
                    (30, DecodeUnit.System, SystemOp.CSRRW, None, true),
                    (31, DecodeUnit.Multiply, MultiplyOp.MUL.litValue.toInt, None, true)
                )
            )
            dut.io.issue.ready.poke(true)
            dut.io.issue.valid.expect(false)

            dut.io.csrGrant.get.valid.poke(true)
            dut.io.csrGrant.get.bits.robIdx.poke(29)
            dut.io.issue.valid.expect(false)
            dut.io.csrGrant.get.bits.robIdx.poke(30)
            // Authorization terminates at a per-entry register before it can
            // participate in selection and compact-queue removal.
            dut.io.issue.valid.expect(false)
            dut.clock.step()
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(30)
            dut.clock.step()
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(31)
        }
    }

    "MixArith waits for every speculative operand to resolve before issue" in {
        val q = IssueQueueParams(8, IssueQueueProfile.MixArith, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            dut.io.enq.valid.poke(1)
            packageEntry(
                dut.io.enq.entries(0),
                42,
                DecodeUnit.Multiply,
                MultiplyOp.MUL.litValue.toInt,
                sourceTag = Some(6),
                sourceReady = true,
                sourceSpecMask = 1,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.io.issue.valid.expect(false)

            dut.io.speculation.resolvedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(42)
        }
    }

    "MixArith keeps a failed speculative operand blocked until a certain wakeup" in {
        val q = IssueQueueParams(8, IssueQueueProfile.MixArith, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            dut.io.enq.valid.poke(1)
            packageEntry(
                dut.io.enq.entries(0),
                43,
                DecodeUnit.Multiply,
                MultiplyOp.MUL.litValue.toInt,
                sourceTag = Some(7),
                sourceReady = true,
                sourceSpecMask = 1,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.io.issue.valid.expect(false)

            dut.io.speculation.failedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.failedMask.poke(0)
            dut.io.issue.valid.expect(false)

            dut.io.wakeup(0).prd.poke(7)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(43)
        }
    }

    "Load Retry re-arms the original slot and completion retires it" in {
        val q = IssueQueueParams(6, IssueQueueProfile.Load, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(
                dut,
                Seq(
                    (50, DecodeUnit.Load, 2, None, true),
                    (51, DecodeUnit.Load, 2, None, true),
                ),
            )
            dut.io.issue.ready.poke(true)
            dut.io.issue.bits.robIdx.expect(50)
            dut.clock.step()
            dut.io.occupancy.expect(2)
            dut.io.issue.bits.robIdx.expect(51)
            dut.clock.step()
            dut.io.issue.valid.expect(false)

            dut.io.retry.get.valid.poke(true)
            dut.io.retry.get.bits.robIdx.poke(50)
            dut.clock.step()
            dut.io.retry.get.valid.poke(false)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(50)
            dut.clock.step()

            dut.io.completed.get.valid.poke(true)
            dut.io.completed.get.bits.robIdx.poke(51)
            dut.clock.step()
            dut.io.completed.get.valid.poke(false)
            dut.io.occupancy.expect(1)
            dut.io.completed.get.valid.poke(true)
            dut.io.completed.get.bits.robIdx.poke(50)
            dut.clock.step()
            dut.io.occupancy.expect(0)
        }
    }

    "uncached Load waits for its exact commit grant and marks the authorized retry" in {
        val q = IssueQueueParams(6, IssueQueueProfile.Load, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(dut, Seq((52, DecodeUnit.Load, 2, None, true)))
            dut.io.issue.ready.poke(true)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.ioAuthorized.expect(false)
            dut.clock.step()

            dut.io.retry.get.valid.poke(true)
            dut.io.retry.get.bits.robIdx.poke(52)
            dut.io.retry.get.bits.uncache.poke(true)
            dut.clock.step()
            dut.io.retry.get.valid.poke(false)
            dut.io.issue.valid.expect(false)

            dut.io.loadGrant.get.valid.poke(true)
            dut.io.loadGrant.get.bits.robIdx.poke(51)
            dut.io.issue.valid.expect(false)
            dut.io.loadGrant.get.bits.robIdx.poke(52)
            dut.io.issue.valid.expect(false)
            dut.clock.step()
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(52)
            dut.io.issue.bits.ioAuthorized.expect(true)
        }
    }

    "LS1 compacts two holes using only the next two adjacent entries" in {
        val q = IssueQueueParams(8, IssueQueueProfile.LoadStoreAddress, wakeupPorts = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            enqueue(
                dut,
                Seq(
                    (52, DecodeUnit.Load, 2, None, true),
                    (53, DecodeUnit.Store, 0, Some(5), false),
                ),
            )
            enqueue(
                dut,
                Seq(
                    (54, DecodeUnit.Store, 0, None, true),
                    (55, DecodeUnit.Store, 0, None, true),
                ),
            )

            dut.io.issue.ready.poke(true)
            dut.io.issue.bits.robIdx.expect(52)
            dut.clock.step()
            dut.io.occupancy.expect(4)
            dut.io.issue.bits.robIdx.expect(54)

            dut.io.completed.get.valid.poke(true)
            dut.io.completed.get.bits.robIdx.poke(52)
            dut.io.enq.valid.poke(3)
            packageEntry(dut.io.enq.entries(0), 56, DecodeUnit.Store)
            packageEntry(dut.io.enq.entries(1), 57, DecodeUnit.Store)
            dut.clock.step()
            dut.io.completed.get.valid.poke(false)
            dut.io.enq.valid.poke(0)

            dut.io.occupancy.expect(4)
            dut.io.issue.bits.robIdx.expect(55)
            dut.clock.step()
            dut.io.issue.bits.robIdx.expect(56)
            dut.clock.step()
            dut.io.issue.bits.robIdx.expect(57)
            dut.clock.step()
            dut.io.issue.valid.expect(false)

            dut.io.wakeup(0).prd.poke(5)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(53)
        }
    }

    "speculative issue leaves the compact queue and uses its internal replay queue" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 4)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)

            enqueue(dut, Seq((60, DecodeUnit.ALU, 0, Some(7), false)))
            dut.io.wakeup(0).prd.poke(7)
            dut.io.wakeup(0).specMask.poke(1)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.clock.step()
            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(1)
            dut.io.issue.valid.expect(false)
            dut.io.speculation.resolvedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(0)

            enqueue(dut, Seq((61, DecodeUnit.ALU, 0, Some(8), false)))
            dut.io.wakeup(0).prd.poke(8)
            dut.io.wakeup(0).specMask.poke(2)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(2)
            dut.io.speculation.failedMask.poke(2)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(1)
            dut.io.issue.valid.expect(false)
            dut.io.wakeup(0).prd.poke(8)
            dut.io.issue.valid.expect(false)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(61)
            dut.clock.step()
            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(0)
        }
    }

    "a replay selected with a newly failed token remains available for recovery" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)

            enqueue(dut, Seq((19, DecodeUnit.ALU, 0, Some(7), false)))
            dut.io.wakeup(0).prd.poke(7)
            dut.io.wakeup(0).specMask.poke(1)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.clock.step()
            dut.io.replayOccupancy.expect(1)

            dut.io.speculation.resolvedMask.poke(1)
            dut.io.speculation.failedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.clock.step()
            dut.io.issue.valid.expect(false)
            dut.io.replayOccupancy.expect(1)

            dut.io.wakeup(0).prd.poke(7)
            dut.io.wakeup(0).specMask.poke(2)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(19)

            // The execution boundary kills this presentation using the raw
            // failure. Delayed IQ maintenance must retain the replay entry.
            dut.io.speculation.resolvedMask.poke(2)
            dut.io.speculation.failedMask.poke(2)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.clock.step()
            dut.io.issue.valid.expect(false)
            dut.io.replayOccupancy.expect(1)

            dut.io.wakeup(0).prd.poke(7)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(19)
            dut.clock.step()
            dut.io.replayOccupancy.expect(0)
        }
    }

    "a certain wakeup wins when checkpoint creation sees a delayed failure" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            dut.io.enq.valid.poke(3)
            packageEntry(dut.io.enq.entries(0), 20, DecodeUnit.ALU)
            packageEntry(
                dut.io.enq.entries(1),
                21,
                DecodeUnit.ALU,
                sourceTag = Some(7),
                sourceReady = true,
                sourceSpecMask = 1,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)

            // The older instruction occupies the raw-failure cycle. The target
            // reaches issue one cycle later when queue maintenance sees failure.
            dut.io.issue.bits.robIdx.expect(20)
            dut.io.speculation.resolvedMask.poke(1)
            dut.io.speculation.failedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.wakeup(0).prd.poke(7)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(21)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)

            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(1)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(21)
            dut.clock.step()
            dut.io.replayOccupancy.expect(0)
        }
    }

    "one successful token releases several replay checkpoints without compacting the main queue" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 4)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            enqueue(
                dut,
                Seq(
                    (6, DecodeUnit.ALU, 0, Some(9), false),
                    (7, DecodeUnit.Branch, 0, Some(9), false),
                ),
            )
            dut.io.wakeup(0).prd.poke(9)
            dut.io.wakeup(0).specMask.poke(1)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)

            dut.io.issue.bits.robIdx.expect(6)
            dut.clock.step()
            dut.io.issue.bits.robIdx.expect(7)
            dut.clock.step()
            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(2)

            dut.io.speculation.resolvedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.replayOccupancy.expect(0)
        }
    }

    "a full replay queue blocks another speculative issue until one checkpoint resolves" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            dut.io.enq.valid.poke(3)
            packageEntry(
                dut.io.enq.entries(0),
                16,
                DecodeUnit.ALU,
                sourceTag = Some(10),
                sourceSpecMask = 1,
            )
            packageEntry(
                dut.io.enq.entries(1),
                17,
                DecodeUnit.ALU,
                sourceTag = Some(11),
                sourceSpecMask = 2,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)

            dut.io.issue.bits.robIdx.expect(16)
            dut.clock.step()
            dut.io.occupancy.expect(1)
            dut.io.replayOccupancy.expect(1)
            dut.io.issue.valid.expect(false)

            dut.io.speculation.resolvedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(17)
            dut.clock.step()
            dut.io.occupancy.expect(0)
            dut.io.replayOccupancy.expect(1)
        }
    }

    "a full replay queue skips an older speculative entry for a younger certain entry" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 1)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)

            dut.io.enq.valid.poke(1)
            packageEntry(
                dut.io.enq.entries(0),
                54,
                DecodeUnit.ALU,
                sourceTag = Some(10),
                sourceSpecMask = 1,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.clock.step()
            dut.io.replayOccupancy.expect(1)

            dut.io.enq.valid.poke(3)
            packageEntry(
                dut.io.enq.entries(0),
                55,
                DecodeUnit.ALU,
                sourceTag = Some(11),
                sourceSpecMask = 2,
            )
            packageEntry(dut.io.enq.entries(1), 56, DecodeUnit.ALU)
            dut.clock.step()
            dut.io.enq.valid.poke(0)

            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(56)
            dut.clock.step()
            dut.io.issue.valid.expect(false)

            dut.io.speculation.resolvedMask.poke(1)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.issue.valid.expect(true)
            dut.io.issue.bits.robIdx.expect(55)
        }
    }

    "commit flush clears every replay checkpoint" in {
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 1, replayEntries = 4)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            dut.io.enq.valid.poke(3)
            packageEntry(
                dut.io.enq.entries(0),
                56,
                DecodeUnit.ALU,
                sourceTag = Some(12),
                sourceSpecMask = 1,
            )
            packageEntry(
                dut.io.enq.entries(1),
                57,
                DecodeUnit.Branch,
                sourceTag = Some(13),
                sourceSpecMask = 2,
            )
            dut.clock.step()
            dut.io.enq.valid.poke(0)
            dut.clock.step(2)
            dut.io.replayOccupancy.expect(2)

            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.replayOccupancy.expect(0)
        }
    }

    "random issue, wakeup, flush and dual enqueue preserve the compact queue model" in {
        case class ReferenceEntry(id: Int, rob: Int, tag: Option[Int], ready: Boolean)

        val random = new scala.util.Random(0x2026)
        val q = IssueQueueParams(6, IssueQueueProfile.ArithBranch, wakeupPorts = 2)
        simulate(new IssueQueue(backend, q)) { dut =>
            initialize(dut)
            dut.io.issue.ready.poke(true)
            var model = Vector.empty[ReferenceEntry]
            var nextId = 1

            for (_ <- 0 until 400) {
                dut.io.occupancy.expect(model.size)
                val wakeTags = random.shuffle((1 to 12).toList).take(random.nextInt(3)).toSet
                for (port <- 0 until q.wakeupPorts) {
                    val tag = wakeTags.toSeq.lift(port)
                    dut.io.wakeup(port).prd.poke(tag.getOrElse(0))
                    dut.io.wakeup(port).specMask.poke(0)
                }

                val flush = model.nonEmpty && random.nextInt(17) == 0
                dut.io.flush.poke(flush)

                val maxEnqueue = if (flush) 0 else math.min(q.enqueueWidth, q.entries - model.size)
                val enqueueCount = random.nextInt(maxEnqueue + 1)
                val newEntries = Vector.tabulate(enqueueCount) { lane =>
                    val id = nextId
                    nextId += 1
                    val tag = if (random.nextBoolean()) Some(1 + random.nextInt(12)) else None
                    val ready = tag.isEmpty || random.nextBoolean()
                    val rob = id & ((1 << backend.robWidth) - 1)
                    packageEntry(
                        dut.io.enq.entries(lane),
                        rob,
                        if (random.nextBoolean()) DecodeUnit.ALU else DecodeUnit.Branch,
                        sourceTag = tag,
                        sourceReady = ready,
                    )
                    dut.io.enq.entries(lane).inst.poke(id)
                    ReferenceEntry(id, rob, tag, ready)
                }
                for (lane <- enqueueCount until q.enqueueWidth) clearEnqueue(dut.io.enq.entries(lane))
                dut.io.enq.valid.poke((1 << enqueueCount) - 1)

                val selected = model.indexWhere(entry => entry.tag.isEmpty || entry.ready)
                val expectedIssue = !flush && selected >= 0
                dut.io.issue.valid.expect(expectedIssue)
                if (expectedIssue) {
                    dut.io.issue.bits.inst.expect(model(selected).id)
                    dut.io.issue.bits.robIdx.expect(model(selected).rob)
                }

                val awakened = model.map { entry =>
                    entry.copy(ready = entry.ready || entry.tag.exists(wakeTags.contains))
                }
                model = if (flush) Vector.empty else {
                        val survivors = if (selected >= 0) awakened.patch(selected, Nil, 1) else awakened
                        survivors ++ newEntries.map { entry =>
                            entry.copy(ready = entry.ready || entry.tag.exists(wakeTags.contains))
                        }
                }
                dut.clock.step()
            }
        }
    }

    "BackendPackage construction preserves frontend semantics and remaps STD data" in {
        simulate(new BackendPackageConstructionTop) { dut =>
            dut.io.instruction.pc.poke("h80001000".U)
            dut.io.instruction.inst.poke("h0020a223".U)
            for (source <- 0 until 3) {
                dut.io.instruction.rinfo.src(source).index.poke(source + 1)
                dut.io.instruction.rinfo.src(source).isFp.poke(false)
                dut.io.instruction.rinfo.src(source).valid.poke(source < 2)
                dut.io.physical.prs(source).poke(10 + source)
                dut.io.physical.sourceReady(source).poke(source != 1)
                dut.io.physical.sourceSpecMask(source).poke(0)
            }
            dut.io.instruction.rinfo.dest.index.poke(0)
            dut.io.instruction.rinfo.dest.isFp.poke(false)
            dut.io.instruction.rinfo.dest.valid.poke(false)
            dut.io.instruction.kind.poke(0)
            dut.io.instruction.predictedTaken.poke(false)
            dut.io.instruction.predictedValue.poke(4)
            dut.io.instruction.fault.poke(false)
            dut.io.instruction.fu.poke(DecodeUnit.Store)
            dut.io.instruction.op.poke(2)
            dut.io.instruction.src1Sel.poke(DecodeSource.Register)
            dut.io.instruction.src2Imm.poke(true)
            dut.io.instruction.imm.poke(4)
            dut.io.instruction.rm.poke(0)
            dut.io.instruction.aq.poke(false)
            dut.io.instruction.rl.poke(false)
            dut.io.instruction.exception.valid.poke(false)
            dut.io.instruction.exception.cause.poke(0)
            dut.io.instruction.exception.tval.poke(0)
            dut.io.physical.prd.poke(0)
            dut.io.allocation.robIdx.poke(7)
            dut.io.allocation.sqTail.poke(5)
            dut.io.allocation.sqIdx.poke(6)

            dut.io.base.store.expect(true)
            dut.io.base.prs(0).expect(10)
            dut.io.base.prs(1).expect(11)
            dut.io.base.sourceReady(0).expect(true)
            dut.io.base.sourceReady(1).expect(false)
            dut.io.base.sourceReady(2).expect(true)
            dut.io.base.mtype.expect(2)
            dut.io.base.size.expect(2)
            dut.io.base.robIdx.expect(7)
            dut.io.base.sqIdx.expect(6)
            dut.io.base.pc.expect("h80001000".U)
            dut.io.storeData.prs(0).expect(11)
            dut.io.storeData.sourceValid(0).expect(true)
            dut.io.storeData.sourceReady(0).expect(false)
            dut.io.storeData.sourceValid(1).expect(false)
            dut.io.storeData.sourceReady(1).expect(true)
            dut.io.storeData.size.expect(2)
        }
    }
}
