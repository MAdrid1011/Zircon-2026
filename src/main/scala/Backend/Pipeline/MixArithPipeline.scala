import chisel3._
import chisel3.util._
import ZirconConfig.{DecodeUnit, FpMiscOp, SystemOp}

/** One mixed integer/FP issue path with a four-stage arithmetic pipeline. */
class MixArithPipeline extends Module {
    val io = IO(new MixArithPipelineIO)

    require((new MixArithTag).getWidth == MixArithConstants.tagWidth)

    val multiply = Module(new MulBooth2Wallce(MixArithConstants.tagWidth))
    val divide = Module(new DivSqrtSRT4(MixArithConstants.tagWidth))
    val fpLogic = Module(new FpLogic(MixArithConstants.tagWidth))
    val fpConvert = Module(new FpConvert(MixArithConstants.tagWidth))

    val flush = io.cmt.flush
    io.divideBusy := divide.io.divBusy

    /*
     * Pipeline map:
     *
     * Issue -> RF -> EX1 -> EX2 -> EX3 -> EX4 -> WB
     *
     * Every operation carries a compact MixArithTag. Short operations write result
     * early, then keep moving through alignment registers. Multiply and Divide contain
     * four registered EX stages and drive WB directly from their fourth register.
     * Divide holds EX2 and prevents younger operations from entering the execution pipe.
     */

    /* Issue and RF stage --------------------------------------------------------- */
    val packageRF = Reg(new MixArithIssue) // Issue/RF boundary
    val validRF = RegInit(false.B)
    val advanceRF = Wire(Bool())
    val liveRF = validRF && !flush

    for (source <- 0 until MixArithConstants.numSources) {
        val tag = packageRF.prs(source)
        val local = tag(MixArithConstants.localPhysWidth - 1, 0)
        val fp = tag(MixArithConstants.physTagWidth - 1)
        io.rf.fpRead(source).addr := Mux(
            liveRF && packageRF.sourceValid(source) && fp,
            local(MixArithConstants.fpPhysWidth - 1, 0),
            0.U,
        )
        if (source < 2) {
            io.rf.intRead(source).addr := Mux(liveRF && packageRF.sourceValid(source) && !fp, local, 0.U)
        }
    }

    val regfileValue = Wire(Vec(MixArithConstants.numSources, UInt(32.W)))
    for (source <- 0 until MixArithConstants.numSources) {
        val fp = packageRF.prs(source)(MixArithConstants.physTagWidth - 1)
        val value = if (source < 2) {
            Mux(fp, io.rf.fpRead(source).data, io.rf.intRead(source).data)
        } else {
            io.rf.fpRead(source).data
        }
        regfileValue(source) := value
    }

    io.cmt.rob.readIdx := packageRF.robIdx

    io.iq.ready := !flush && (!validRF || advanceRF)
    when(flush) {
        validRF := false.B
    }.elsewhen(io.iq.fire) {
        packageRF := io.iq.bits
        validRF := true.B
    }.elsewhen(advanceRF) {
        validRF := false.B
    }

    /* EX1 stage ------------------------------------------------------------------ */
    val packageEX1 = Reg(new MixArithIssue) // RF/EX1 boundary
    val sourceEX1 = Reg(Vec(MixArithConstants.numSources, UInt(32.W)))
    val validEX1 = RegInit(false.B)

    val selectCSR = packageEX1.fu === DecodeUnit.System.U &&
        packageEX1.op >= SystemOp.CSRRW.U && packageEX1.op <= SystemOp.CSRRCI.U
    val selectMultiply = packageEX1.fu === DecodeUnit.Multiply.U && packageEX1.op <= 10.U
    val selectDivide = packageEX1.fu === DecodeUnit.Divide.U && packageEX1.op <= 5.U
    val selectFpLogic = packageEX1.fu === DecodeUnit.FpMisc.U && packageEX1.op <= FpMiscOp.FMV_W_X.U
    val selectFpConvert = packageEX1.fu === DecodeUnit.FpMisc.U &&
        packageEX1.op >= FpMiscOp.FCVT_W_S.U && packageEX1.op <= FpMiscOp.FCVT_S_WU.U
    val dynamicRounding = packageEX1.roundingMode === 7.U
    val badRounding = dynamicRounding && io.csr.frm > 4.U
    val roundingMode = Mux(dynamicRounding, Mux(badRounding, 0.U, io.csr.frm), packageEX1.roundingMode)
    val executionPackage = WireDefault(packageEX1)
    when(badRounding) {
        executionPackage.exception.valid := true.B
        executionPackage.exception.cause := 2.U
        executionPackage.exception.tval := packageEX1.inst
    }
    def resultTag(pkg: MixArithIssue): MixArithTag = {
        val tag = Wire(new MixArithTag)
        tag.prd := pkg.prd
        tag.rdValid := pkg.rdValid
        tag.fpFlagsValid := pkg.fpFlagsValid
        tag.robIdx := pkg.robIdx(MixArithConstants.robAddressWidth - 1, 0)
        tag.exception := pkg.exception
        tag
    }
    def arithmeticResult(tag: UInt, data: UInt, fflags: UInt): MixArithResult = {
        val result = Wire(new MixArithResult)
        result.tag := tag.asTypeOf(new MixArithTag)
        result.data := data
        result.fflags := fflags
        result
    }

    // Cover the cycle in which a new Divide moves from its private EX1 register into EX2.
    val divideEnteringEX2 = RegInit(false.B)
    val canLaunchEX1 = !divideEnteringEX2 && !divide.io.divBusy
    val selectedReady = Mux1H(Seq(
        selectCSR -> true.B,
        selectMultiply -> multiply.io.in.ready,
        selectDivide -> divide.io.in.ready,
        selectFpLogic -> fpLogic.io.in.ready,
        selectFpConvert -> fpConvert.io.in.ready
    ))
    val liveEX1 = validEX1 && !flush
    val launchEX1 = liveEX1 && canLaunchEX1
    val fireEX1 = launchEX1 && selectedReady
    val readyEX1 = !validEX1 || fireEX1
    advanceRF := liveRF && readyEX1

    // A fixed-latency result can wake compute consumers two cycles before WB and
    // memory consumers one cycle before WB. Divide uses its actual WB because EX2
    // has a variable residence time.
    val csrIllegal = selectCSR && io.csr.rsp.bits.illegal
    val earlyWake = fireEX1 && !selectDivide && packageEX1.rdValid &&
        !packageEX1.exception.valid && !csrIllegal
    val earlyWakeValid = RegInit(VecInit.fill(3)(false.B))
    val earlyWakeTag = Reg(Vec(3, UInt(MixArithConstants.physTagWidth.W)))
    when(flush) {
        earlyWakeValid := VecInit.fill(3)(false.B)
    }.otherwise {
        earlyWakeValid(0) := earlyWake
        earlyWakeValid(1) := earlyWakeValid(0)
        earlyWakeValid(2) := earlyWakeValid(1)
        when(earlyWake) { earlyWakeTag(0) := packageEX1.prd }
        when(earlyWakeValid(0)) { earlyWakeTag(1) := earlyWakeTag(0) }
        when(earlyWakeValid(1)) { earlyWakeTag(2) := earlyWakeTag(1) }
    }

    for (source <- 0 until MixArithConstants.numSources) {
        io.bypass.consumer.query(source).prs := packageRF.prs(source)
    }
    io.bypass.consumer.advance := advanceRF

    val sourceValue = Wire(Vec(MixArithConstants.numSources, UInt(32.W)))
    for (source <- 0 until MixArithConstants.numSources) {
        sourceValue(source) := Mux(
            io.bypass.consumer.value(source).valid,
            io.bypass.consumer.value(source).bits,
            sourceEX1(source),
        )
    }
    when(flush) {
        validEX1 := false.B
    }.elsewhen(advanceRF) {
        packageEX1 := packageRF
        sourceEX1 := regfileValue
        validEX1 := true.B
    }.elsewhen(fireEX1) {
        validEX1 := false.B
    }.elsewhen(liveEX1) {
        // Fold a transient WB value into the held package so the next cycle needs only one source mux.
        for (source <- 0 until MixArithConstants.numSources) {
            when(io.bypass.consumer.value(source).valid) {
                sourceEX1(source) := sourceValue(source)
            }
        }
    }

    /* Ordered CSR result, then EX2/EX3/EX4 alignment ----------------------------- */
    val packageCSR_EX2 = Reg(new MixArithResult) // EX1/EX2 boundary
    val packageCSR_EX3 = Reg(new MixArithResult) // EX2/EX3 boundary
    val packageCSR_EX4 = Reg(new MixArithResult) // EX3/EX4 boundary
    val validCSR_EX2 = RegInit(false.B)
    val validCSR_EX3 = RegInit(false.B)
    val validCSR_EX4 = RegInit(false.B)

    io.csr.req.valid := fireEX1 && selectCSR
    io.csr.req.bits.addr := packageEX1.imm(11, 0)
    io.csr.req.bits.op := packageEX1.op
    io.csr.req.bits.source := packageEX1.imm(16, 12)
    io.csr.req.bits.data := sourceValue(0)
    io.csr.req.bits.rdZero := packageEX1.rdZero
    io.csr.commit := fireEX1 && selectCSR
    when(flush) {
        validCSR_EX2 := false.B
        validCSR_EX3 := false.B
        validCSR_EX4 := false.B
    }.otherwise {
        validCSR_EX4 := validCSR_EX3
        validCSR_EX3 := validCSR_EX2
        validCSR_EX2 := fireEX1 && selectCSR
        when(validCSR_EX3) { packageCSR_EX4 := packageCSR_EX3 }
        when(validCSR_EX2) { packageCSR_EX3 := packageCSR_EX2 }
        when(fireEX1 && selectCSR) {
            packageCSR_EX2.tag := resultTag(packageEX1)
            packageCSR_EX2.data := io.csr.rsp.bits.data
            packageCSR_EX2.fflags := 0.U
            packageCSR_EX2.tag.fpFlagsValid := false.B
            when(io.csr.rsp.bits.illegal) {
                packageCSR_EX2.tag.exception.valid := true.B
                packageCSR_EX2.tag.exception.cause := 2.U
                packageCSR_EX2.tag.exception.tval := packageEX1.inst
            }
        }
    }

    /* Multiply EX1-EX4 ----------------------------------------------------------- */
    multiply.io.in.valid := launchEX1 && selectMultiply
    multiply.io.in.bits.src1 := sourceValue(0)
    multiply.io.in.bits.src2 := sourceValue(1)
    multiply.io.in.bits.src3 := sourceValue(2)
    multiply.io.in.bits.op := packageEX1.op(3, 0)
    multiply.io.in.bits.roundingMode := roundingMode
    multiply.io.in.bits.tag := resultTag(executionPackage).asUInt
    multiply.io.flush := flush
    multiply.io.out.ready := true.B

    /* Divide EX1-EX4; EX2 may iterate for multiple cycles ------------------------ */
    divide.io.in.valid := launchEX1 && selectDivide
    divide.io.in.bits.src1 := sourceValue(0)
    divide.io.in.bits.src2 := sourceValue(1)
    divide.io.in.bits.op := packageEX1.op(2, 0)
    divide.io.in.bits.roundingMode := roundingMode
    divide.io.in.bits.tag := resultTag(executionPackage).asUInt
    divide.io.flush := flush
    divide.io.out.ready := true.B

    when(flush) {
        divideEnteringEX2 := false.B
    }.otherwise {
        divideEnteringEX2 := divide.io.in.fire
    }
    assert(!divide.io.in.fire || fireEX1 && selectDivide)

    /* FpLogic EX1 result, then EX2/EX3/EX4 alignment ----------------------------- */
    fpLogic.io.in.valid := launchEX1 && selectFpLogic
    fpLogic.io.in.bits.src1 := sourceValue(0)
    fpLogic.io.in.bits.src2 := sourceValue(1)
    fpLogic.io.in.bits.op := packageEX1.op(3, 0)
    fpLogic.io.in.bits.tag := resultTag(executionPackage).asUInt
    fpLogic.io.flush := flush

    val packageFpLogic_EX3 = Reg(new MixArithResult) // EX2/EX3 boundary
    val packageFpLogic_EX4 = Reg(new MixArithResult) // EX3/EX4 boundary
    val validFpLogic_EX3 = RegInit(false.B)
    val validFpLogic_EX4 = RegInit(false.B)
    fpLogic.io.out.ready := true.B

    val packageFpLogic_EX2 = arithmeticResult(
        fpLogic.io.out.bits.tag,
        fpLogic.io.out.bits.res,
        fpLogic.io.out.bits.fflags,
    )

    when(flush) {
        validFpLogic_EX3 := false.B
        validFpLogic_EX4 := false.B
    }.otherwise {
        validFpLogic_EX4 := validFpLogic_EX3
        validFpLogic_EX3 := fpLogic.io.out.valid
        when(validFpLogic_EX3) { packageFpLogic_EX4 := packageFpLogic_EX3 }
        when(fpLogic.io.out.valid) { packageFpLogic_EX3 := packageFpLogic_EX2 }
    }

    /* FpConvert EX1-EX2 result, then EX3/EX4 alignment --------------------------- */
    fpConvert.io.in.valid := launchEX1 && selectFpConvert
    fpConvert.io.in.bits.src1 := sourceValue(0)
    fpConvert.io.in.bits.op := packageEX1.op(3, 0)
    fpConvert.io.in.bits.roundingMode := roundingMode
    fpConvert.io.in.bits.tag := resultTag(executionPackage).asUInt
    fpConvert.io.flush := flush

    val packageFpConvert_EX4 = Reg(new MixArithResult) // EX3/EX4 boundary
    val validFpConvert_EX4 = RegInit(false.B)
    fpConvert.io.out.ready := true.B

    val packageFpConvert_EX3 = arithmeticResult(
        fpConvert.io.out.bits.tag,
        fpConvert.io.out.bits.res,
        fpConvert.io.out.bits.fflags,
    )

    when(flush) {
        validFpConvert_EX4 := false.B
    }.otherwise {
        validFpConvert_EX4 := fpConvert.io.out.valid
        when(fpConvert.io.out.valid) { packageFpConvert_EX4 := packageFpConvert_EX3 }
    }

    /* Short-unit EX4/WB alignment ------------------------------------------------ */
    val shortValid = VecInit(Seq(validCSR_EX4, validFpLogic_EX4, validFpConvert_EX4))
    val shortPackage = Mux1H(
        shortValid,
        Seq(packageCSR_EX4, packageFpLogic_EX4, packageFpConvert_EX4)
    )
    val packageShortWB = Reg(new MixArithResult)
    val validShortWB = RegInit(false.B)
    when(flush) {
        validShortWB := false.B
    }.otherwise {
        validShortWB := shortValid.asUInt.orR
        when(shortValid.asUInt.orR) { packageShortWB := shortPackage }
    }
    assert(flush || PopCount(shortValid) <= 1.U, "MixArith short units must occupy distinct EX4 cycles")

    /* WB stage: fourth-stage results are mutually exclusive by construction. ----- */
    val packageMultiplyWB = arithmeticResult(
        multiply.io.out.bits.tag,
        multiply.io.out.bits.res,
        multiply.io.out.bits.fflags,
    )
    val packageDivideWB = arithmeticResult(
        divide.io.out.bits.tag,
        divide.io.out.bits.res,
        divide.io.out.bits.fflags,
    )

    // WB presence comes only from registered stage-valid bits. Flush gates architectural
    // effects separately so it cannot enter the global Bypass data-selection cone.
    val wbPresent = VecInit(Seq(validShortWB, multiply.io.present, divide.io.present))
    val packageWB = Mux1H(wbPresent, Seq(packageShortWB, packageMultiplyWB, packageDivideWB))
    val validWB = wbPresent.asUInt.orR && !flush
    assert(flush || PopCount(wbPresent) <= 1.U, "MixArith permits only one ordered WB result per cycle")

    val destinationFp = packageWB.tag.prd(MixArithConstants.physTagWidth - 1)
    val destination = packageWB.tag.prd(MixArithConstants.localPhysWidth - 1, 0)

    val successfulWrite = validWB && packageWB.tag.rdValid && !packageWB.tag.exception.valid
    io.rf.intWrite.valid := successfulWrite && !destinationFp
    io.rf.intWrite.bits.addr := destination
    io.rf.intWrite.bits.data := packageWB.data
    io.rf.fpWrite.valid := successfulWrite && destinationFp
    io.rf.fpWrite.bits.addr := destination(MixArithConstants.fpPhysWidth - 1, 0)
    io.rf.fpWrite.bits.data := packageWB.data

    io.cmt.rob.complete.valid := validWB
    io.cmt.rob.complete.bits.prd := packageWB.tag.prd
    io.cmt.rob.complete.bits.rdValid := packageWB.tag.rdValid
    io.cmt.rob.complete.bits.data := packageWB.data
    io.cmt.rob.complete.bits.fflags := packageWB.fflags
    io.cmt.rob.complete.bits.fpFlagsValid := packageWB.tag.fpFlagsValid
    io.cmt.rob.complete.bits.robIdx := packageWB.tag.robIdx
    io.cmt.rob.complete.bits.exception := packageWB.tag.exception

    io.wakeup.valid := successfulWrite
    io.wakeup.bits := packageWB.tag.prd
    val divideWake = divide.io.out.valid && packageDivideWB.tag.rdValid && !packageDivideWB.tag.exception.valid
    io.wakeEX2.valid := earlyWakeValid(1) || divideWake
    io.wakeEX2.bits := Mux(earlyWakeValid(1), earlyWakeTag(1), packageDivideWB.tag.prd)
    io.wakeEX3.valid := earlyWakeValid(2) || divideWake
    io.wakeEX3.bits := Mux(earlyWakeValid(2), earlyWakeTag(2), packageDivideWB.tag.prd)

    // Select on the WB-register input side so valid, tag and data all leave WB directly.
    val bypassInputValid = VecInit(Seq(
        shortValid.asUInt.orR,
        multiply.io.wbInput.valid,
        divide.io.wbInput.valid
    ))
    val bypassInput = Mux1H(
        bypassInputValid,
        Seq(shortPackage.data, multiply.io.wbInput.bits.res, divide.io.wbInput.bits.res)
    )
    val bypassTag = Mux1H(
        bypassInputValid,
        Seq(
            MixArithWakeTag.from(shortPackage.tag),
            multiply.io.wbInput.bits.tag,
            divide.io.wbInput.bits.tag,
        )
    )
    val bypassDataWB = Reg(UInt(32.W))
    when(!flush) {
        when(bypassInputValid.asUInt.orR) {
            bypassDataWB := bypassInput
        }
    }
    io.bypass.producer.result := bypassDataWB
    io.bypass.producer.nextWb.valid := !flush && bypassInputValid.asUInt.orR && bypassTag.rdValid &&
        !bypassTag.exceptionValid
    io.bypass.producer.nextWb.bits := bypassTag.prd
    assert(flush || PopCount(bypassInputValid) <= 1.U, "MixArith permits only one WB input per cycle")

    // Keep the IQ path off the combinational ready chain.
    io.available := RegNext(io.iq.ready, false.B)

    when(io.iq.fire) {
        val csr = io.iq.bits.fu === DecodeUnit.System.U &&
            io.iq.bits.op >= SystemOp.CSRRW.U && io.iq.bits.op <= SystemOp.CSRRCI.U
        val supported = csr ||
            (io.iq.bits.fu === DecodeUnit.Multiply.U && io.iq.bits.op <= 10.U) ||
            (io.iq.bits.fu === DecodeUnit.Divide.U && io.iq.bits.op <= 5.U) ||
            (io.iq.bits.fu === DecodeUnit.FpMisc.U && io.iq.bits.op <= FpMiscOp.FCVT_S_WU.U)
        assert(supported, "MixArith accepted an unsupported functional operation")
        for (source <- 0 until MixArithConstants.numSources) {
            when(io.iq.bits.sourceValid(source)) {
                val tag = io.iq.bits.prs(source)
                val local = tag(MixArithConstants.localPhysWidth - 1, 0)
                when(tag(MixArithConstants.physTagWidth - 1)) {
                    assert(local < MixArithConstants.numFpPhys.U, "MixArith FP source is out of range")
                }.otherwise {
                    assert(
                        local =/= 0.U && local < MixArithConstants.numIntPhys.U,
                        "MixArith integer source is out of range"
                    )
                }
            }
        }
        when(io.iq.bits.sourceValid(2)) {
            assert(
                io.iq.bits.prs(2)(MixArithConstants.physTagWidth - 1),
                "MixArith third source must use the FP register file"
            )
        }
        when(io.iq.bits.rdValid) {
            val local = io.iq.bits.prd(MixArithConstants.localPhysWidth - 1, 0)
            when(io.iq.bits.prd(MixArithConstants.physTagWidth - 1)) {
                assert(local < MixArithConstants.numFpPhys.U, "MixArith FP destination is out of range")
            }.otherwise {
                assert(
                    local =/= 0.U && local < MixArithConstants.numIntPhys.U,
                    "MixArith integer destination is out of range"
                )
            }
        }
    }

}
