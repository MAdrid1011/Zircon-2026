import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{DecodeSource, DecodeUnit}

class ArithBranchSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: ArithBranch): Unit = {
        dut.io.iq.valid.poke(false)
        BackendPackageTestUtils.clear(dut.io.iq.bits)
        dut.io.rf.read.foreach(_.data.poke(0))
        dut.io.cmt.rob.pc.poke(0)
        dut.io.cmt.flush.poke(false)
        dut.io.speculation.resolvedMask.poke(0)
        dut.io.speculation.failedMask.poke(0)
        for (source <- 0 until 2) {
            dut.io.bypass.consumer.value(source).valid.poke(false)
            dut.io.bypass.consumer.value(source).bits.poke(0)
            dut.io.bypass.consumer.valueFpZero(source).poke(true)
            dut.io.bypass.consumer.capture(source).valid.poke(false)
            dut.io.bypass.consumer.capture(source).bits.poke(0)
            dut.io.bypass.consumer.captureFpZero(source).poke(true)
        }
    }

    "a failed speculative WB may write its unavailable destination but cannot publish it" in {
        simulate(new ArithBranch) { dut =>
            initialize(dut)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            BackendPackageTestUtils.clear(dut.io.iq.bits)
            dut.io.iq.bits.prd.poke(5)
            dut.io.iq.bits.rdValid.poke(true)
            dut.io.iq.bits.fu.poke(DecodeUnit.ALU)
            dut.io.iq.bits.op.poke(0)
            dut.io.iq.bits.src1Sel.poke(DecodeSource.Register)
            dut.io.iq.bits.sourceSpecMask(0).poke(1)
            dut.io.iq.valid.poke(true)
            dut.clock.step()

            dut.io.iq.valid.poke(false)
            dut.clock.step(2)

            dut.io.speculation.resolvedMask.poke(1)
            dut.io.speculation.failedMask.poke(1)
            dut.io.rf.write.valid.expect(true)
            dut.io.rf.write.bits.prd.expect(5)
            dut.io.wakeup.wakeWB.prd.expect(0)
            dut.io.cmt.rob.complete.valid.expect(false)
            dut.io.cmt.branch.update.valid.expect(false)
        }
    }

    "a flush kills architectural completion without gating the invisible FTQ payload update" in {
        simulate(new ArithBranch) { dut =>
            initialize(dut)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            BackendPackageTestUtils.clear(dut.io.iq.bits)
            dut.io.iq.bits.fu.poke(DecodeUnit.Branch)
            dut.io.iq.bits.op.poke(0)
            dut.io.iq.valid.poke(true)
            dut.clock.step()

            dut.io.iq.valid.poke(false)
            dut.clock.step(2)
            dut.io.cmt.flush.poke(true)
            dut.io.cmt.rob.complete.valid.expect(false)
            dut.io.cmt.branch.update.valid.expect(true)
        }
    }

    "RF wakeup is registered with its producer qualification and speculation mask" in {
        simulate(new ArithBranch) { dut =>
            initialize(dut)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            BackendPackageTestUtils.clear(dut.io.iq.bits)
            dut.io.iq.bits.fu.poke(DecodeUnit.ALU)
            dut.io.iq.bits.prd.poke(7)
            dut.io.iq.bits.rdValid.poke(true)
            dut.io.iq.bits.sourceSpecMask(0).poke(3)
            dut.io.speculation.resolvedMask.poke(2)
            dut.io.iq.valid.poke(true)
            dut.clock.step()
            dut.io.wakeup.wakeRF.prd.expect(7)
            dut.io.wakeup.wakeRF.specMask.expect(1)

            // Failure in the RF cycle does not retract the stable tag; the
            // consumer uses the mask and resolution to keep it not-ready.
            dut.io.speculation.failedMask.poke(1)
            dut.io.wakeup.wakeRF.prd.expect(7)
            dut.io.wakeup.wakeRF.specMask.expect(1)

            BackendPackageTestUtils.clear(dut.io.iq.bits)
            dut.io.iq.bits.fu.poke(DecodeUnit.ALU)
            dut.io.iq.bits.prd.poke(8)
            dut.io.iq.bits.rdValid.poke(true)
            dut.io.iq.bits.exception.valid.poke(true)
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.clock.step()
            dut.io.wakeup.wakeRF.prd.expect(0)
            dut.io.wakeup.wakeRF.specMask.expect(0)

            dut.io.iq.bits.exception.valid.poke(false)
            dut.io.iq.bits.sourceSpecMask(0).poke(1)
            dut.io.speculation.failedMask.poke(1)
            dut.clock.step()
            dut.io.wakeup.wakeRF.prd.expect(0)

            dut.io.speculation.failedMask.poke(0)
            dut.clock.step()
            dut.io.wakeup.wakeRF.prd.expect(0)
            dut.clock.step()
            dut.io.wakeup.wakeRF.prd.expect(8)
            dut.io.wakeup.wakeRF.specMask.expect(1)
            dut.io.iq.valid.poke(false)
            dut.clock.step()
            dut.io.wakeup.wakeRF.prd.expect(0)
            dut.io.wakeup.wakeRF.specMask.expect(0)
        }
    }
}
