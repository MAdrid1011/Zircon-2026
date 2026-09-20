import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.LoadPipelineParams

class LoadStoreControlSpec extends AnyFreeSpec with ChiselSim {
    "full load contexts leave STA/STD independent, with unequal non-power-of-two PRFs" in {
        // This fixture isolates reservation/issue control; cache acceptance and PRF values are driven inputs.
        val p = LoadPipelineParams(numIntPhys = 10, numFpPhys = 33)
        simulate(new LoadStorePipeline(p)) { dut =>
            val a = dut.io.iq.instPkg
            val s = dut.io.iq.std.get
            a.valid.poke(false); s.valid.poke(false)
            BackendPackageTestUtils.clear(a.bits)
            BackendPackageTestUtils.clear(s.bits)
            a.bits.fu.poke(ZirconConfig.DecodeUnit.Load)
            s.bits.fu.poke(ZirconConfig.DecodeUnit.Store)
            a.bits.prj.poke(9); a.bits.prd.poke(1); a.bits.rdVld.poke(true)
            a.bits.imm.poke(0); a.bits.mtype.poke(2); a.bits.robIdx.poke(0); a.bits.sqTail.poke(0); a.bits.exception.valid.poke(false); a.bits.uncache.poke(false)
            a.bits.ioAuthorized.poke(false); a.bits.store.poke(false); a.bits.sqIdx.poke(0)
            s.bits.prs(0).poke(0); s.bits.sqIdx.poke(0); s.bits.robIdx.poke(0); s.bits.size.poke(2)
            dut.io.rf.rd.prjData.poke(0x1000)
            dut.io.rf.std.get.intData.poke(BigInt("89abcdef", 16))
            dut.io.rf.std.get.fpData.poke(BigInt("7f800001", 16))
            dut.io.wk.grant.poke(0)
            dut.io.cmt.storeAddress.get.ready.poke(true); dut.io.cmt.storeData.get.ready.poke(true)
            dut.io.cmt.flush.poke(false);
            for (r <- Seq(dut.io.cmt.sqResult, dut.io.cmt.sbResult)) {
                r.valid.poke(true); r.bits.data.poke(0); r.bits.mask.poke(0); r.bits.blocked.poke(false)
            }
            dut.io.cache.req.ready.poke(true)
            dut.io.cache.fixedLatency.poke(true)
            dut.io.cache.wbSelect.valid.poke(false)
            dut.io.cache.wbSelect.bits.slot.poke(0)
            dut.io.cache.wbSelect.bits.exception.poke(0)
            dut.io.cache.wbSelect.bits.retry.poke(false)
            dut.io.cache.wbSelect.bits.uncache.poke(false)
            dut.io.cache.rsp.valid.poke(false)
            dut.io.cache.rsp.bits.slot.poke(0)
            dut.io.cache.rsp.bits.data.poke(0)
            dut.io.cache.forward.query.valid.poke(false)
            dut.io.cache.forward.query.bits.wordAddress.poke(0); dut.io.cache.forward.query.bits.slot.poke(0)
            dut.io.cache.forward.query.bits.mask.poke(15)
            dut.clock.step()
            for (i <- 0 until p.entries) {
                a.valid.poke(true); a.bits.robIdx.poke(i); a.bits.imm.poke(i * 4)
                a.ready.expect(true); dut.clock.step()
            }
            a.valid.poke(false); dut.io.cache.req.valid.expect(true); dut.clock.step()
            a.ready.expect(false)
            dut.io.cache.req.ready.poke(false)
            for (i <- 0 until 12) {
                a.valid.poke(true); a.bits.store.poke(true); a.bits.rdVld.poke(false)
                a.bits.fu.poke(ZirconConfig.DecodeUnit.Store)
                a.bits.sqIdx.poke(i); a.bits.robIdx.poke(20 + i); a.bits.imm.poke(i * 4)
                s.valid.poke(true); s.bits.sqIdx.poke(i); s.bits.robIdx.poke(20 + i)
                s.bits.prs(0).poke(if (i % 2 == 0) 9 else (1 << p.physWidth) | 32)
                a.ready.expect(true); s.ready.expect(true)
                dut.io.cache.req.valid.expect(false)
                dut.clock.step()
                dut.io.cmt.storeAddress.get.valid.expect(true)
                dut.io.cmt.storeAddress.get.bits.sqIdx.expect(i)
                dut.io.cmt.storeAddress.get.bits.paddr.expect(0x1000 + 4 * i)
                dut.io.cmt.storeData.get.valid.expect(true)
                dut.io.cmt.storeData.get.bits.sqIdx.expect(i)
                dut.io.cmt.storeData.get.bits.data.expect(BigInt(if (i % 2 == 0) "89abcdef" else "7f800001", 16))
                dut.io.rf.std.get.intAddr.expect(if (i % 2 == 0) 9 else 0)
                dut.io.rf.std.get.fpAddr.expect(if (i % 2 == 0) 0 else 32)
                dut.io.rf.wr.valid.expect(false); dut.io.cmt.rob.valid.expect(false)
            }
            a.valid.poke(false); s.valid.poke(false); dut.clock.step()
            a.bits.store.poke(false); a.bits.fu.poke(ZirconConfig.DecodeUnit.Load)
            a.bits.rdVld.poke(true); a.ready.expect(false)
            // Global recovery rejects new speculative work on its edge and frees every occupied load context.
            dut.io.cmt.flush.poke(true); a.valid.poke(true)
            a.ready.expect(false); dut.clock.step()
            dut.io.cmt.flush.poke(false)
            a.ready.expect(true); dut.clock.step()
            a.valid.poke(false)
            dut.io.cache.req.valid.expect(true)
        }
    }
}
