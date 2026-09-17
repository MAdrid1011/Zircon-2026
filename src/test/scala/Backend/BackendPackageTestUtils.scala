import chisel3._

object BackendPackageTestUtils extends chisel3.simulator.PeekPokeAPI {
    def clear(x: BackendPackage): Unit = {
        for (source <- 0 until 3) {
            x.prs(source).poke(0)
            x.sourceValid(source).poke(false)
            x.sourceReady(source).poke(false)
            x.sourceSpecMask(source).poke(0)
            x.source(source).poke(0)
        }
        x.prd.poke(0)
        x.rdValid.poke(false)
        x.fu.poke(0)
        x.op.poke(0)
        x.src1Sel.poke(0)
        x.src2Imm.poke(false)
        x.imm.poke(0)
        x.roundingMode.poke(0)
        x.inst.poke(0)
        x.rdZero.poke(false)
        x.robIdx.poke(0)
        x.sqTail.poke(0)
        x.sqIdx.poke(0)
        x.exception.valid.poke(false)
        x.exception.cause.poke(0)
        x.exception.tval.poke(0)
        x.uncache.poke(false)
        x.ioAuthorized.poke(false)
        x.store.poke(false)
        x.mtype.poke(0)
        x.size.poke(0)
        x.fpFlagsValid.poke(false)
        x.pc.poke(0)
        x.predictedTaken.poke(false)
        x.predictedValue.poke(0)
        x.result.poke(0)
        x.fflags.poke(0)
        x.resultIsFp.poke(false)
        x.branchTaken.poke(false)
        x.branchTarget.poke(0)
        x.predFail.poke(false)
    }
}
