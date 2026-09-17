import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class CommitSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: Commit): Unit = {
        dut.io.frontend.ftq.enq.valid.poke(false)
        dut.io.middleend.request.valid.poke(0)
        dut.io.middleend.request.store.poke(0)
        dut.io.middleend.enqueue.valid.poke(0)

        dut.io.backend.arith0.rob.readIdx.poke(0)
        dut.io.backend.arith0.rob.complete.valid.poke(false)
        dut.io.backend.arith0.branch.update.valid.poke(false)
        dut.io.backend.arith0.csr.req.valid.poke(false)
        dut.io.backend.arith0.csr.commit.poke(false)
        dut.io.backend.arith1.rob.readIdx.poke(0)
        dut.io.backend.arith1.rob.complete.valid.poke(false)
        dut.io.backend.arith1.branch.update.valid.poke(false)
        dut.io.backend.mixRob.readIdx.poke(0)
        dut.io.backend.mixRob.complete.valid.poke(false)
        for (port <- Seq(dut.io.backend.ls0, dut.io.backend.ls1)) {
            port.rob.valid.poke(false)
            port.sqQuery.valid.poke(false)
            port.sbQuery.valid.poke(false)
        }
        dut.io.backend.ls1.storeAddress.get.valid.poke(false)
        dut.io.backend.ls1.storeData.get.valid.poke(false)
        dut.io.backend.store.req.ready.poke(false)
        dut.io.backend.store.rsp.valid.poke(false)
        dut.io.backend.store.rsp.bits.exception.poke(0)

        dut.io.environment.privilege.poke(3)
        dut.io.environment.time.poke(0)
        dut.io.environment.interrupt.software.poke(false)
        dut.io.environment.interrupt.timer.poke(false)
        dut.io.environment.interrupt.external.poke(false)
        dut.io.environment.interrupt.supervisorExternal.poke(false)
        dut.io.environment.memoryIdle.poke(true)
        dut.io.environment.maintenance.done.poke(false)
        dut.reset.poke(true)
        dut.clock.step()
        dut.reset.poke(false)
    }

    private def enqueueFtq(dut: Commit): Unit = {
        val entry = dut.io.frontend.ftq.enq.bits
        dut.io.frontend.ftq.enq.valid.poke(true)
        entry.fetchToken.poke(7)
        entry.record.train.pc.poke(0x1000)
        entry.record.train.mask.poke(1)
        entry.record.train.taken.poke(0)
        entry.record.train.kinds(0).poke(FrontendCfi.Conditional)
        entry.record.train.targets(0).poke(0x1004)
        entry.record.nextPc.poke(0x1004)
        dut.io.frontend.ftq.enq.ready.expect(true)
    }

    private def enqueueBranch(dut: Commit, robIdx: BigInt): Unit = {
        val entry = dut.io.middleend.enqueue.entries(0)
        dut.io.middleend.request.valid.poke(1)
        dut.io.middleend.request.store.poke(0)
        dut.io.middleend.enqueue.valid.poke(1)
        entry.context.fetchToken.poke(7)
        entry.context.ftqIdx.poke(0)
        entry.context.slot.poke(0)
        entry.context.packetEnd.poke(true)
        entry.context.instruction.pc.poke(0x1000)
        entry.context.instruction.inst.poke(0x63)
        entry.context.instruction.fu.poke(ZirconConfig.DecodeUnit.Branch)
        entry.context.instruction.op.poke(0)
        entry.context.instruction.exception.valid.poke(false)
        entry.context.instruction.exception.cause.poke(0)
        entry.context.instruction.exception.tval.poke(0)
        entry.destination.rd.poke(0)
        entry.destination.isFp.poke(false)
        entry.destination.prd.poke(0)
        entry.destination.pprd.poke(0)
        entry.allocation.robIdx.poke(robIdx)
        entry.allocation.sqTail.poke(0)
        entry.allocation.sqIdx.poke(0)
    }

    "a Branch-pipeline ALU instruction completes without a predictor update" in {
        simulate(new Commit(issue = ZirconConfig.IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            enqueueFtq(dut)
            val robIdx = dut.io.middleend.allocation(0).robIdx.peek().litValue
            enqueueBranch(dut, robIdx)
            dut.clock.step()
            dut.io.frontend.ftq.enq.valid.poke(false)
            dut.io.middleend.request.valid.poke(0)
            dut.io.middleend.enqueue.valid.poke(0)

            dut.io.backend.arith1.rob.complete.valid.poke(true)
            dut.io.backend.arith1.rob.complete.bits.robIdx.poke(robIdx)
            dut.io.backend.arith1.rob.complete.bits.exception.valid.poke(false)
            dut.io.backend.arith1.rob.complete.bits.exception.cause.poke(0)
            dut.io.backend.arith1.rob.complete.bits.exception.tval.poke(0)
            dut.io.backend.arith1.branch.update.valid.poke(false)
            dut.clock.step()
            dut.io.backend.arith1.rob.complete.valid.poke(false)

            dut.clock.step()
            dut.io.frontend.ftq.retire.valid.expect(true)
            dut.io.frontend.rob.redirect.valid.expect(false)
        }
    }

    "branch recovery is selected in order and broadcast one cycle later" in {
        simulate(new Commit(issue = ZirconConfig.IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            enqueueFtq(dut)
            dut.io.middleend.request.valid.poke(1)
            dut.io.middleend.request.store.poke(0)
            val robIdx = dut.io.middleend.allocation(0).robIdx.peek().litValue
            enqueueBranch(dut, robIdx)
            dut.clock.step()
            dut.io.frontend.ftq.enq.valid.poke(false)
            dut.io.middleend.request.valid.poke(0)
            dut.io.middleend.enqueue.valid.poke(0)

            dut.io.backend.arith1.rob.readIdx.poke(robIdx)
            dut.io.backend.arith1.rob.complete.valid.poke(true)
            dut.io.backend.arith1.rob.complete.bits.robIdx.poke(robIdx)
            dut.io.backend.arith1.rob.complete.bits.exception.valid.poke(false)
            dut.io.backend.arith1.rob.complete.bits.exception.cause.poke(0)
            dut.io.backend.arith1.rob.complete.bits.exception.tval.poke(0)
            dut.io.backend.arith1.branch.update.valid.poke(true)
            dut.io.backend.arith1.branch.update.bits.robIdx.poke(robIdx)
            dut.io.backend.arith1.branch.update.bits.taken.poke(true)
            dut.io.backend.arith1.branch.update.bits.target.poke(0x2000)
            dut.io.backend.arith1.branch.update.bits.predFail.poke(true)
            dut.clock.step()
            dut.io.backend.arith1.rob.complete.valid.poke(false)
            dut.io.backend.arith1.branch.update.valid.poke(false)

            dut.io.middleend.flush.expect(false)
            dut.clock.step()
            dut.io.middleend.flush.expect(false)
            dut.io.frontend.rob.redirect.valid.expect(false)
            dut.clock.step()

            dut.io.middleend.flush.expect(true)
            dut.io.middleend.restore.expect(true)
            dut.io.backend.flush.expect(true)
            dut.io.frontend.rob.redirect.valid.expect(true)
            dut.io.frontend.rob.redirect.bits.pc.expect(0x2000)
            dut.io.frontend.ftq.retire.valid.expect(true)
            dut.clock.step()
            dut.io.middleend.flush.expect(false)
            dut.io.frontend.rob.redirect.valid.expect(false)
        }
    }

    "a head exception produces one delayed recovery pulse without retiring the faulting instruction" in {
        simulate(new Commit(issue = ZirconConfig.IssueParams(dispatchWidth = 3))) { dut =>
            initialize(dut)
            enqueueFtq(dut)
            dut.io.middleend.request.valid.poke(1)
            dut.io.middleend.request.store.poke(0)
            val robIdx = dut.io.middleend.allocation(0).robIdx.peek().litValue
            enqueueBranch(dut, robIdx)
            val instruction = dut.io.middleend.enqueue.entries(0).context.instruction
            instruction.exception.valid.poke(true)
            instruction.exception.cause.poke(2)
            instruction.exception.tval.poke(0x63)
            dut.clock.step()

            dut.io.frontend.ftq.enq.valid.poke(false)
            dut.io.middleend.request.valid.poke(0)
            dut.io.middleend.enqueue.valid.poke(0)
            dut.io.middleend.flush.expect(false)
            dut.clock.step()

            dut.io.middleend.flush.expect(true)
            dut.io.middleend.retire(0).valid.expect(false)
            dut.io.frontend.ftq.retire.valid.expect(false)
            dut.io.frontend.rob.redirect.valid.expect(true)
            dut.io.frontend.rob.redirect.bits.pc.expect(0)
            dut.clock.step()

            dut.io.middleend.flush.expect(false)
            dut.io.frontend.rob.redirect.valid.expect(false)
        }
    }
}
