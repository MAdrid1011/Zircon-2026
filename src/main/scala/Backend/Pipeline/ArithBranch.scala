import chisel3._
import chisel3.util._
import ZirconConfig.{DecodeSource, DecodeUnit}

class ArithBranchCommitIO extends Bundle {
    val rob = new ArithRobIO
    val branch = new ArithBranchContextIO
    val flush = Input(Bool())
}

class ArithBranchIO extends Bundle {
    val iq = Flipped(Decoupled(new BackendPackage))
    val rf = new ArithRegfileIO
    val cmt = new ArithBranchCommitIO
    val bypass = new PipelineBypassPort(2)
    val wakeup = new ArithWakeupIO
    val speculation = Input(new SpeculationResolution)
}

/** Fixed-latency RF/EX/WB pipe for integer ALU and control-flow instructions. */
class ArithBranch extends Module {
    val io = IO(new ArithBranchIO)

    val alu = Module(new ALU)
    val branch = Module(new Branch)

    def specFailed(x: BackendPackage): Bool =
        (x.sourceSpecMask.reduce(_ | _) & io.speculation.failedMask).orR
    def killed(x: BackendPackage): Bool = io.cmt.flush || specFailed(x)

    /* Issue and RF stage --------------------------------------------------------- */
    val packageRF = RegInit(0.U.asTypeOf(new BackendPackage)) // Issue/RF boundary
    val validRF = RegInit(false.B)
    val liveRF = validRF && !killed(packageRF)
    val inputKilled = io.cmt.flush || specFailed(io.iq.bits)

    io.iq.ready := !inputKilled
    io.rf.read(0).addr := packageRF.prj(ArithConstants.physWidth - 1, 0)
    io.rf.read(1).addr := packageRF.prk(ArithConstants.physWidth - 1, 0)
    io.cmt.rob.readIdx := packageRF.robIdx

    val issueProduces = io.iq.fire && io.iq.bits.rdValid && !io.iq.bits.exception.valid
    io.wakeup.wakeIssue.prd := Mux(issueProduces, io.iq.bits.prd, 0.U)
    io.wakeup.wakeIssue.specMask := Mux(issueProduces, io.iq.bits.sourceSpecMask.reduce(_ | _), 0.U)
    val rfProduces = liveRF && packageRF.rdValid && !packageRF.exception.valid
    io.wakeup.wakeRF.prd := Mux(rfProduces, packageRF.prd, 0.U)
    io.wakeup.wakeRF.specMask := Mux(rfProduces, packageRF.sourceSpecMask.reduce(_ | _), 0.U)

    validRF := io.iq.fire
    when(io.iq.fire) {
        packageRF := io.iq.bits
        for (source <- 0 until 3) {
            packageRF.sourceSpecMask(source) := io.iq.bits.sourceSpecMask(source) &
                ~io.speculation.resolvedMask
        }
        assert(
            io.iq.bits.exception.valid || io.iq.bits.fu === DecodeUnit.ALU.U ||
                io.iq.bits.fu === DecodeUnit.Branch.U,
            "ArithBranch accepts only ALU and Branch instructions"
        )
        assert(!io.iq.bits.prj(ArithConstants.tagWidth - 1) &&
            !io.iq.bits.prk(ArithConstants.tagWidth - 1))
        assert(io.iq.bits.prj < ArithConstants.numIntPhys.U && io.iq.bits.prk < ArithConstants.numIntPhys.U)
        when(io.iq.bits.rdValid) {
            assert(io.iq.bits.prd =/= 0.U && io.iq.bits.prd < ArithConstants.numIntPhys.U)
        }
    }.elsewhen(io.speculation.resolvedMask.orR) {
        for (source <- 0 until 3) {
            packageRF.sourceSpecMask(source) := packageRF.sourceSpecMask(source) &
                ~io.speculation.resolvedMask
        }
    }

    /* EX stage ------------------------------------------------------------------- */
    val packageEX = RegInit(0.U.asTypeOf(new BackendPackage)) // RF/EX boundary
    val validEX = RegInit(false.B)
    val liveEX = validEX && !killed(packageEX)

    validEX := liveRF
    when(liveRF) {
        packageEX := packageRF
        packageEX.source1 := io.rf.read(0).data
        packageEX.source2 := io.rf.read(1).data
        packageEX.pc := io.cmt.rob.pc
        for (source <- 0 until 3) {
            packageEX.sourceSpecMask(source) := packageRF.sourceSpecMask(source) &
                ~io.speculation.resolvedMask
        }
    }.elsewhen(io.speculation.resolvedMask.orR) {
        for (source <- 0 until 3) {
            packageEX.sourceSpecMask(source) := packageEX.sourceSpecMask(source) &
                ~io.speculation.resolvedMask
        }
    }

    val execute = liveEX && !packageEX.exception.valid
    io.bypass.consumer.query(0).prs := packageRF.prj
    io.bypass.consumer.query(1).prs := packageRF.prk
    io.bypass.consumer.advance := liveRF
    val source1 = Mux(io.bypass.consumer.value(0).valid, io.bypass.consumer.value(0).bits, packageEX.source1)
    val source2 = Mux(io.bypass.consumer.value(1).valid, io.bypass.consumer.value(1).bits, packageEX.source2)
    val operand1 = MuxLookup(packageEX.src1Sel, source1)(Seq(
        DecodeSource.PC.U -> packageEX.pc,
        DecodeSource.Zero.U -> 0.U
    ))
    val operand2 = Mux(packageEX.src2Imm, packageEX.imm, source2)

    alu.io.op := packageEX.op
    alu.io.src1 := operand1
    alu.io.src2 := operand2
    branch.io.op := packageEX.op
    branch.io.src1 := source1
    branch.io.src2 := source2
    branch.io.pc := packageEX.pc
    branch.io.imm := packageEX.imm
    branch.io.predOffset := packageEX.predictedValue

    val packageAfterEX = WireDefault(packageEX)
    packageAfterEX.result := alu.io.res
    packageAfterEX.branchTaken := branch.io.realJp
    packageAfterEX.branchTarget := branch.io.jumpTgt
    packageAfterEX.predFail := branch.io.predFail || (branch.io.realJp =/= packageEX.predictedTaken)
    when(execute && packageEX.fu === DecodeUnit.Branch.U && branch.io.targetMisaligned) {
        packageAfterEX.exception.valid := true.B
        packageAfterEX.exception.cause := 0.U
        packageAfterEX.exception.tval := branch.io.jumpTgt
    }

    /* WB stage ------------------------------------------------------------------- */
    val packageWB = RegInit(0.U.asTypeOf(new BackendPackage)) // EX/WB boundary
    val validWB = RegInit(false.B)
    val bypassValidWB = RegInit(false.B)
    val bypassWB = Reg(new BypassResult)
    val liveWB = validWB && !killed(packageWB)

    validWB := liveEX
    bypassValidWB := liveEX && packageAfterEX.rdValid && !packageAfterEX.exception.valid
    bypassWB.prd := packageAfterEX.prd
    bypassWB.data := packageAfterEX.result
    when(liveEX) {
        packageWB := packageAfterEX
        for (source <- 0 until 3) {
            packageWB.sourceSpecMask(source) := packageAfterEX.sourceSpecMask(source) &
                ~io.speculation.resolvedMask
        }
    }.elsewhen(io.speculation.resolvedMask.orR) {
        for (source <- 0 until 3) {
            packageWB.sourceSpecMask(source) := packageWB.sourceSpecMask(source) &
                ~io.speculation.resolvedMask
        }
    }

    val successfulWrite = liveWB && packageWB.rdValid && !packageWB.exception.valid
    io.rf.write.valid := successfulWrite
    io.rf.write.bits.prd := packageWB.prd(ArithConstants.physWidth - 1, 0)
    io.rf.write.bits.data := packageWB.result
    // Every Bypass field is a direct WB register output. Eligibility is computed in EX.
    io.bypass.producer.result.valid := bypassValidWB
    io.bypass.producer.result.bits := bypassWB
    io.bypass.producer.nextWb.valid := liveEX && packageAfterEX.rdValid && !packageAfterEX.exception.valid
    io.bypass.producer.nextWb.bits := packageAfterEX.prd
    io.wakeup.wakeWB.prd := Mux(successfulWrite, packageWB.prd, 0.U)
    io.wakeup.wakeWB.specMask := 0.U

    io.cmt.rob.complete.valid := liveWB
    io.cmt.rob.complete.bits.robIdx := packageWB.robIdx
    io.cmt.rob.complete.bits.data := packageWB.result
    io.cmt.rob.complete.bits.exception := packageWB.exception

    io.cmt.branch.update.valid := liveWB && packageWB.fu === DecodeUnit.Branch.U &&
        !packageWB.exception.valid
    io.cmt.branch.update.bits.robIdx := packageWB.robIdx
    io.cmt.branch.update.bits.taken := packageWB.branchTaken
    io.cmt.branch.update.bits.target := packageWB.branchTarget
    io.cmt.branch.update.bits.predFail := packageWB.predFail

    when(liveWB) {
        assert(
            !(packageWB.sourceSpecMask.reduce(_ | _) & ~io.speculation.resolvedMask).orR,
            "ArithBranch cannot complete while a Load prediction is unresolved",
        )
    }

}
