import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.LoadPipelineParams

class LoadSpeculationSpec extends AnyFreeSpec with ChiselSim {
    "a cacheable Load wakes at RF and D1 and resolves on its predicted response cycle" in {
        val p = LoadPipelineParams(numFpPhys = 48)
        simulate(new LoadPipeline(p)) { dut =>
            dut.io.iq.instPkg.valid.poke(false)
            BackendPackageTestUtils.clear(dut.io.iq.instPkg.bits)
            dut.io.rf.rd.prjData.poke(0x1000)
            dut.io.cmt.sq.result.valid.poke(true)
            dut.io.cmt.sq.result.bits.data.poke(0)
            dut.io.cmt.sq.result.bits.mask.poke(0)
            dut.io.cmt.sq.result.bits.blocked.poke(false)
            dut.io.cmt.sb.result.valid.poke(true)
            dut.io.cmt.sb.result.bits.data.poke(0)
            dut.io.cmt.sb.result.bits.mask.poke(0)
            dut.io.cmt.sb.result.bits.blocked.poke(false)
            dut.io.cmt.flush.poke(false)
            dut.io.cache.req.ready.poke(true)
            dut.io.cache.fixedLatency.poke(true)
            dut.io.cache.wbSelect.valid.poke(false)
            dut.io.cache.wbSelect.bits.slot.poke(0)
            dut.io.cache.wbSelect.bits.exception.poke(0)
            dut.io.cache.wbSelect.bits.retry.poke(false)
            dut.io.cache.rsp.valid.poke(false)
            dut.io.cache.rsp.bits.slot.poke(0)
            dut.io.cache.rsp.bits.data.poke(0)
            dut.io.cache.rsp.bits.exception.poke(0)
            dut.io.cache.rsp.bits.retry.poke(false)
            dut.io.cache.forward.query.valid.poke(false)
            dut.io.cache.forward.query.bits.paddr.poke(0)
            dut.io.cache.forward.query.bits.slot.poke(0)
            dut.io.cache.forward.query.bits.mask.poke(0)
            dut.io.wk.grant.poke(1)

            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            val load = dut.io.iq.instPkg.bits
            BackendPackageTestUtils.clear(load)
            load.prj.poke(1)
            load.sourceValid(0).poke(true)
            load.sourceReady(0).poke(true)
            load.prd.poke(5)
            load.rdValid.poke(true)
            load.robIdx.poke(9)
            load.mtype.poke(2)
            dut.io.iq.instPkg.valid.poke(true)
            dut.clock.step()
            dut.io.iq.instPkg.valid.poke(false)

            dut.io.cache.req.valid.expect(true)
            val slot = dut.io.cache.req.bits.slot.peek().litValue
            dut.io.wk.wakeRF.prd.expect(5)
            dut.io.wk.wakeRF.specMask.expect(1)
            dut.clock.step()
            dut.io.wk.wakeD1.prd.expect(5)
            dut.io.wk.wakeD1.specMask.expect(1)
            dut.clock.step()

            dut.io.bypass.nextWb.valid.expect(true)
            dut.io.bypass.nextWb.bits.expect(5)

            dut.io.cache.wbSelect.valid.poke(true)
            dut.io.cache.wbSelect.bits.slot.poke(slot)
            dut.io.cache.wbSelect.bits.exception.poke(0)
            dut.io.cache.wbSelect.bits.retry.poke(false)
            dut.clock.step()
            dut.io.cache.wbSelect.valid.poke(false)
            dut.io.cache.rsp.valid.poke(true)
            dut.io.cache.rsp.bits.slot.poke(slot)
            dut.io.cache.rsp.bits.data.poke(0x12345678)
            dut.io.cache.rsp.bits.exception.poke(0)
            dut.io.cache.rsp.bits.retry.poke(false)
            dut.io.bypass.result.valid.expect(true)
            dut.io.bypass.result.bits.prd.expect(5)
            dut.io.bypass.result.bits.data.expect(0x12345678)
            dut.io.wk.result.valid.expect(true)
            dut.io.wk.result.bits.mask.expect(1)
            dut.io.wk.result.bits.failed.expect(false)
        }
    }
}
