import chisel3._
import chisel3.util._
import ZirconConfig.{DCacheParams, LoadPipelineParams}
import ZirconUtil.InheritFields

class LoadIQIO(p: LoadPipelineParams, withStore: Boolean = false) extends Bundle {
    val instPkg = Flipped(Decoupled(new BackendPackage(p.backend)))
    val std = if (withStore) Some(Flipped(Decoupled(new BackendPackage(p.backend)))) else None
}

class LoadWriteback(p: LoadPipelineParams) extends Bundle {
    val prd = UInt(p.tagWidth.W)
    val data = UInt(32.W)
}

class LoadRegfileReadIO(p: LoadPipelineParams) extends Bundle {
    val prj = Output(UInt(p.intWidth.W))
    val prjData = Input(UInt(32.W))
    val hold = Output(Bool())
}

class LoadRegfileIO(p: LoadPipelineParams, withStore: Boolean = false) extends Bundle {
    val rd = new LoadRegfileReadIO(p)
    val wr = Output(Valid(new LoadWriteback(p)))
    val std = if (withStore) Some(new StoreRegfileIO(p)) else None
}

class LoadROBIO(p: LoadPipelineParams) extends Bundle {
    val robIdx = UInt(CommitIndex.addressWidth(ZirconConfig.CommitParams().robEntries).W)
    val vaddr = UInt(32.W)
    val exception = UInt(4.W)
    val data = UInt(32.W)
}

class LoadSQQuery(p: LoadPipelineParams) extends DForwardQuery(DCacheParams(p.entries)) {
    val sqTail = UInt(log2Ceil(ZirconConfig.CommitParams().sqEntries * 2).W)
}

class LoadCompletionIO(p: LoadPipelineParams, withStore: Boolean = false) extends Bundle {
    val rob = Output(Valid(new LoadROBIO(p)))
    val sqQuery = Valid(new LoadSQQuery(p))
    val sqResult = Flipped(Valid(new DForwardResult(DCacheParams(p.entries))))
    val sbQuery = Valid(new DForwardQuery(DCacheParams(p.entries)))
    val sbResult = Flipped(Valid(new DForwardResult(DCacheParams(p.entries))))
    val storeAddress = if (withStore) Some(Decoupled(new StoreAddressResult(p))) else None
    val storeData = if (withStore) Some(Decoupled(new StoreDataResult(p))) else None
}

class LoadCommitIO(p: LoadPipelineParams, withStore: Boolean = false)
    extends LoadCompletionIO(p, withStore) {
    val flush = Input(Bool())
}

class LoadForwardIO(p: LoadPipelineParams) extends Bundle {
    val query = Flipped(Valid(new DForwardQuery(DCacheParams(p.entries))))
    val result = Valid(new DForwardResult(DCacheParams(p.entries)))
}

class LoadStoreTranslationIO extends Bundle {
    val request = Valid(new DStoreTranslationRequest)
    val response = Input(new DStoreTranslationResponse)
}

class LoadDataResponse(p: LoadPipelineParams) extends Bundle {
    val slot = UInt(p.slotWidth.W)
    val data = UInt(32.W)
}

class StoreAddressStage(p: LoadPipelineParams) extends Bundle {
    val item = new BackendPackage(p.backend)
    val vaddr = UInt(32.W)
    val permission = new DCachePermissionContext
}

class LoadCacheIO(p: LoadPipelineParams, withStore: Boolean = false, tlbEnabled: Boolean = false) extends Bundle {
    val req = Decoupled(new DLoadRequest(DCacheParams(p.entries)))
    val fixedLatency = Input(Bool())
    val wbSelect = Flipped(Valid(new DLoadWBSelect(DCacheParams(p.entries))))
    val rsp = Flipped(Valid(new LoadDataResponse(p)))
    val forward = new LoadForwardIO(p)
    val storeTranslation = if (withStore && tlbEnabled) Some(new LoadStoreTranslationIO) else None
}

class LoadWakeupIO(p: LoadPipelineParams) extends Bundle {
    val request = Output(Bool())
    val grant = Input(UInt(p.backend.specWidth.W))
    val wakeRF = Output(new BackendWakeup(p.backend))
    val wakeD1 = Output(new BackendWakeup(p.backend))
    val wakeD2 = Output(new BackendWakeup(p.backend))
    val result = Output(Valid(new LoadSpeculationResult(p.backend)))
    val wakeWB = Output(new BackendWakeup(p.backend))
    val replay = Output(Valid(new BackendPackage(p.backend)))
}

class LoadPipelineIO(p: LoadPipelineParams, withStore: Boolean = false, tlbEnabled: Boolean = false) extends Bundle {
    val iq = new LoadIQIO(p, withStore)
    val rf = new LoadRegfileIO(p, withStore)
    val cmt = new LoadCommitIO(p, withStore)
    val cache = new LoadCacheIO(p, withStore, tlbEnabled)
    val wk = new LoadWakeupIO(p)
    val bypass = new BypassProducerPort(p.backend)
    val blockIssue = Input(Bool())
    val idle = Output(Bool())
    val translationControl = if (withStore && tlbEnabled) Some(Input(new AddressTranslationControl)) else None
}

/** RF/AGU share a cycle; the external DCache owns the D1, D2 and WB registers. */
class LoadPipeline(
    val p: LoadPipelineParams = LoadPipelineParams(),
    withStore: Boolean = false,
    val tlbEnabled: Boolean = false,
) extends Module {
    val io = IO(new LoadPipelineIO(p, withStore, tlbEnabled))
    val cacheLane: Int = if (withStore) 1 else 0

    // Bind requests, responses and forwarding together; shared flush/store remain top-level connections.
    def connectCache(cache: DCacheIO): Unit = {
        cache.load(cacheLane).req <> io.cache.req
        io.cache.fixedLatency := cache.load(cacheLane).fixedLatency
        io.cache.wbSelect <> cache.load(cacheLane).wbSelect
        io.cache.rsp.valid := cache.load(cacheLane).rsp.valid
        io.cache.rsp.bits.slot := cache.load(cacheLane).rsp.bits.slot
        io.cache.rsp.bits.data := cache.load(cacheLane).rsp.bits.data
        io.cache.forward.query := cache.forward(cacheLane).query
        cache.forward(cacheLane).result := io.cache.forward.result
        if (withStore && tlbEnabled) {
            require(cache.tlbEnabled, "A TLB-enabled LS1 requires a TLB-enabled DCache")
            cache.storeTranslation.get.request := io.cache.storeTranslation.get.request
            io.cache.storeTranslation.get.response := cache.storeTranslation.get.response
        }
    }

    val agu = Module(new BLevelPAdder32(carryOut = false))

    /* Issue and RF stage */
    val instPkgRF = Reg(new BackendPackage(p.backend))
    val validRF = RegInit(false.B)
    val indexRF = Reg(UInt(p.slotWidth.W))
    val heldRF = RegInit(false.B)
    val storeAddressIdle = WireDefault(true.B)
    io.idle := !validRF && storeAddressIdle

    // Context includes the RF reservation, so a cache stall cannot change the request identity.
    val pending = Reg(Vec(p.entries, new BackendPackage(p.backend)))
    val address = Reg(Vec(p.entries, UInt(32.W)))
    val valid = RegInit(VecInit.fill(p.entries)(false.B))
    val sent = RegInit(VecInit.fill(p.entries)(false.B))
    def killed(x: BackendPackage): Bool = io.cmt.flush

    val storeRF = instPkgRF.store
    val storeIS = io.iq.instPkg.bits.store
    val acceptedUnit = Mux(
        storeIS,
        io.iq.instPkg.bits.fu === ZirconConfig.DecodeUnit.Store.U ||
            io.iq.instPkg.bits.fu === ZirconConfig.DecodeUnit.Atomic.U,
        io.iq.instPkg.bits.fu === ZirconConfig.DecodeUnit.Load.U,
    )
    val storeAddressAccept = WireDefault(false.B)
    val addressAccept = io.cache.req.fire || storeAddressAccept

    val free = VecInit((0 until p.entries).map(i =>
        !valid(i) || (io.cache.rsp.valid && io.cache.rsp.bits.slot === i.U)
    )).asUInt
    val indexIS = PriorityEncoder(free)
    io.iq.instPkg.ready := acceptedUnit && !io.blockIssue && (storeIS || free.orR) &&
        (!validRF || addressAccept)

    io.rf.rd.prj := Mux(instPkgRF.prj(p.physWidth), 0.U, instPkgRF.prj(p.intWidth - 1, 0))
    io.rf.rd.hold := heldRF
    agu.io.src1 := io.rf.rd.prjData
    agu.io.src2 := instPkgRF.imm
    agu.io.cin := 0.U
    io.cache.req.valid := validRF && !storeRF && !killed(instPkgRF)
    io.cache.req.bits.vaddr := agu.io.res
    io.cache.req.bits.paddr := Cat(0.U(2.W), agu.io.res)
    io.cache.req.bits.slot := indexRF
    io.cache.req.bits.mtype := instPkgRF.mtype
    io.cache.req.bits.uncache := instPkgRF.uncache
    io.cache.req.bits.ioAuthorized := instPkgRF.ioAuthorized
    io.cache.req.bits.exception := Mux(instPkgRF.exception.valid, instPkgRF.exception.cause(3, 0), 0.U)
    io.cache.req.bits.translationMiss := false.B
    io.cache.req.bits.atomic := false.B

    io.wk.request := io.cache.req.valid && io.cache.fixedLatency && instPkgRF.rdVld &&
        !instPkgRF.exception.valid && !instPkgRF.uncache
    val speculateRF = io.cache.req.fire && io.wk.request && io.wk.grant.orR
    io.wk.wakeRF.prd := Mux(speculateRF, instPkgRF.prd, 0.U)
    io.wk.wakeRF.specMask := Mux(speculateRF, io.wk.grant, 0.U)
    val wakeD1Valid = RegNext(speculateRF, false.B)
    val wakeD1Prd = RegEnable(instPkgRF.prd, speculateRF)
    val wakeD1Mask = RegEnable(io.wk.grant, speculateRF)
    io.wk.wakeD1.prd := Mux(wakeD1Valid, wakeD1Prd, 0.U)
    io.wk.wakeD1.specMask := Mux(wakeD1Valid, wakeD1Mask, 0.U)
    val wakeD2Valid = RegNext(wakeD1Valid, false.B)
    val wakeD2Prd = RegEnable(wakeD1Prd, wakeD1Valid)
    val wakeD2Mask = RegEnable(wakeD1Mask, wakeD1Valid)
    io.wk.wakeD2.prd := Mux(wakeD2Valid, wakeD2Prd, 0.U)
    io.wk.wakeD2.specMask := Mux(wakeD2Valid, wakeD2Mask, 0.U)

    val expectedValid = RegInit(VecInit.fill(4)(false.B))
    val expectedSlot = Reg(Vec(4, UInt(p.slotWidth.W)))
    val expectedMask = Reg(Vec(4, UInt(p.backend.specWidth.W)))
    val expectedPrd = Reg(Vec(4, UInt(p.tagWidth.W)))
    when(io.cmt.flush) {
        expectedValid := VecInit.fill(4)(false.B)
    }.otherwise {
        expectedValid(0) := speculateRF
        expectedValid(1) := expectedValid(0)
        expectedValid(2) := expectedValid(1)
        expectedValid(3) := expectedValid(2)
        when(speculateRF) {
            expectedSlot(0) := indexRF
            expectedMask(0) := io.wk.grant
            expectedPrd(0) := instPkgRF.prd
        }
        when(expectedValid(0)) {
            expectedSlot(1) := expectedSlot(0)
            expectedMask(1) := expectedMask(0)
            expectedPrd(1) := expectedPrd(0)
        }
        when(expectedValid(1)) {
            expectedSlot(2) := expectedSlot(1)
            expectedMask(2) := expectedMask(1)
            expectedPrd(2) := expectedPrd(1)
        }
        when(expectedValid(2)) {
            expectedSlot(3) := expectedSlot(2)
            expectedMask(3) := expectedMask(2)
            expectedPrd(3) := expectedPrd(2)
        }
    }
    val predictionMatchesD2 = io.cache.wbSelect.valid && expectedValid(1) &&
        io.cache.wbSelect.bits.slot === expectedSlot(1) && !io.cache.wbSelect.bits.retry &&
        io.cache.wbSelect.bits.exception === 0.U
    val predictionMatchesWB = RegNext(predictionMatchesD2, false.B)
    io.wk.result.valid := expectedValid(2)
    io.wk.result.bits.mask := expectedMask(2)
    io.wk.result.bits.failed := !predictionMatchesWB

    when(validRF && !killed(instPkgRF) && !addressAccept && !heldRF) {
        heldRF := true.B
    }
    when(addressAccept || (validRF && killed(instPkgRF))) {
        validRF := false.B
        heldRF := false.B
    }

    for (i <- 0 until p.entries) {
        when((valid(i) && killed(pending(i))) || (io.cache.rsp.valid && io.cache.rsp.bits.slot === i.U)) {
            valid(i) := false.B
            sent(i) := false.B
        }
    }
    when(io.cache.req.fire) {
        sent(indexRF) := true.B
        address(indexRF) := agu.io.res
    }
    when(io.iq.instPkg.fire) {
        instPkgRF := io.iq.instPkg.bits
        validRF := true.B
        indexRF := indexIS
        heldRF := false.B
        when(!storeIS) {
            pending(indexIS) := io.iq.instPkg.bits
            valid(indexIS) := true.B
            sent(indexIS) := false.B
        }
        assert(io.iq.instPkg.bits.prj < p.numIntPhys.U)
        when(storeIS) {
            assert(io.iq.instPkg.bits.mtype <= 2.U)
            when(io.iq.instPkg.bits.fu === ZirconConfig.DecodeUnit.Store.U) {
                assert(!io.iq.instPkg.bits.rdVld)
            }
        }.otherwise {
            assert(VecInit(Seq(0, 1, 2, 4, 5).map(_.U === io.iq.instPkg.bits.mtype)).asUInt.orR)
        }
        when(!storeIS && io.iq.instPkg.bits.rdVld) {
            val rd = io.iq.instPkg.bits.prd(p.physWidth - 1, 0)
            when(io.iq.instPkg.bits.prd(p.physWidth)) {
                assert(rd < p.numFpPhys.U && io.iq.instPkg.bits.mtype === 2.U)
            }.otherwise { assert(rd =/= 0.U && rd < p.numIntPhys.U) }
        }
    }
    // Keep the inactive read address legal without gating the live RF address path.
    when(reset.asBool) { instPkgRF.prj := 0.U }

    if (withStore) {
        /* STA captures the AGU result before translation so DTLB/SQ backpressure
         * cannot return through the RF datapath to IssueQueue. Two entries sustain
         * one accepted address per cycle without a combinational dequeue bypass. */
        val addressStages = Reg(Vec(2, new StoreAddressStage(p)))
        val addressStageCount = RegInit(0.U(2.W))
        val addressStageValid = addressStageCount =/= 0.U
        val addressStageFull = addressStageCount === 2.U
        storeAddressIdle := !addressStageValid
        val addressInput = Wire(new StoreAddressStage(p))
        addressInput.item := instPkgRF
        addressInput.vaddr := agu.io.res
        addressInput.permission := 0.U.asTypeOf(new DCachePermissionContext)
        if (tlbEnabled) {
            val control = io.translationControl.get
            addressInput.permission.direct := !control.enabled || control.privilege === 3.U
            addressInput.permission.privilege := control.privilege
            addressInput.permission.mxr := control.mxr
            addressInput.permission.sum := control.sum
        } else {
            addressInput.permission.direct := true.B
        }
        storeAddressAccept := validRF && storeRF && !killed(instPkgRF) && !addressStageFull

        val addressItem = addressStages(0).item
        val addressVaddr = addressStages(0).vaddr
        val addr = io.cmt.storeAddress.get
        val atomicAddress = addressItem.fu === ZirconConfig.DecodeUnit.Atomic.U
        val lrAddress = atomicAddress && addressItem.op === 2.U
        val misaligned = (addressItem.mtype === 1.U && addressVaddr(0)) ||
            (addressItem.mtype === 2.U && addressVaddr(1, 0).orR)
        val translationReady = WireDefault(true.B)
        val translatedPaddr = WireDefault(Cat(0.U(2.W), addressVaddr))
        val translatedUncache = WireDefault(addressItem.uncache)
        val translatedException = WireDefault(0.U(4.W))
        if (tlbEnabled) {
            val translation = io.cache.storeTranslation.get
            translation.request.valid := addressStageValid && !io.cmt.flush
            translation.request.bits.vaddr := addressVaddr
            translation.request.bits.uncache := addressItem.uncache
            translation.request.bits.exception :=
                Mux(addressItem.exception.valid, addressItem.exception.cause(3, 0), 0.U)
            translation.request.bits.atomic := atomicAddress
            translation.request.bits.lr := lrAddress
            translation.request.bits.permission := addressStages(0).permission
            translationReady := !translation.response.miss
            translatedPaddr := translation.response.paddr
            translatedUncache := translation.response.uncache
            translatedException := translation.response.exception
        }
        addr.valid := addressStageValid && !io.cmt.flush && translationReady
        addr.bits.sqIdx := addressItem.sqIdx
        addr.bits.robIdx := addressItem.robIdx
        addr.bits.vaddr := addressVaddr
        addr.bits.paddr := translatedPaddr
        addr.bits.size := addressItem.mtype(1, 0)
        val baseMask = MuxLookup(addressItem.mtype, 0.U(4.W))(
            Seq(0.U -> 1.U, 1.U -> 3.U, 2.U -> 15.U)
        )
        addr.bits.mask := MuxLookup(addressVaddr(1, 0), 0.U(4.W))(Seq(
            0.U -> baseMask,
            1.U -> Cat(baseMask(2, 0), 0.U(1.W)),
            2.U -> Cat(baseMask(1, 0), 0.U(2.W)),
            3.U -> Cat(baseMask(0), 0.U(3.W)),
        ))
        addr.bits.exception := Mux(
            addressItem.exception.valid,
            addressItem.exception.cause(3, 0),
            Mux(misaligned, Mux(lrAddress, 4.U, 6.U), translatedException)
        )
        addr.bits.uncache := translatedUncache

        val addressStageRemove = addr.fire
        when(io.cmt.flush) {
            addressStageCount := 0.U
        }.otherwise {
            when(storeAddressAccept && addressStageRemove) {
                assert(addressStageCount === 1.U)
                addressStages(0) := addressInput
            }.elsewhen(storeAddressAccept) {
                when(addressStageCount === 0.U) {
                    addressStages(0) := addressInput
                }.otherwise {
                    addressStages(1) := addressInput
                }
                addressStageCount := addressStageCount + 1.U
            }.elsewhen(addressStageRemove) {
                when(addressStageCount === 2.U) {
                    addressStages(0) := addressStages(1)
                }
                addressStageCount := addressStageCount - 1.U
            }
        }
        assert(addressStageCount <= 2.U)
        when(storeAddressAccept) {
            assert(storeRF && (instPkgRF.fu === ZirconConfig.DecodeUnit.Store.U ||
                instPkgRF.fu === ZirconConfig.DecodeUnit.Atomic.U))
        }

        /* STD has an independent issue register and write-first PRF read ports. */
        val instDataRF = Reg(new BackendPackage(p.backend))
        val validDataRF = RegInit(false.B)
        val heldDataRF = RegInit(false.B)
        val killedDataRF = io.cmt.flush
        val std = io.iq.std.get
        val rf = io.rf.std.get
        val data = io.cmt.storeData.get
        val storeDataUnit = std.bits.fu === ZirconConfig.DecodeUnit.Store.U ||
            std.bits.fu === ZirconConfig.DecodeUnit.Atomic.U
        std.ready := storeDataUnit && !io.blockIssue && (!validDataRF || data.fire)
        rf.intAddr := Mux(instDataRF.prs(0)(p.physWidth), 0.U, instDataRF.prs(0)(p.physWidth - 1, 0))
        rf.fpAddr := Mux(instDataRF.prs(0)(p.physWidth), instDataRF.prs(0)(p.physWidth - 1, 0), 0.U)
        rf.hold := heldDataRF
        data.valid := validDataRF && !killedDataRF
        data.bits.sqIdx := instDataRF.sqIdx
        data.bits.robIdx := instDataRF.robIdx
        data.bits.size := instDataRF.size
        data.bits.data := Mux(instDataRF.prs(0)(p.physWidth), rf.fpData, rf.intData)
        when(data.valid && !data.ready && !heldDataRF) { heldDataRF := true.B }
        when(data.fire || (validDataRF && killedDataRF)) {
            validDataRF := false.B
            heldDataRF := false.B
        }
        when(std.fire) {
            instDataRF := std.bits
            validDataRF := true.B
            heldDataRF := false.B
            assert(std.bits.size <= 2.U)
            when(std.bits.prs(0)(p.physWidth)) {
                assert(std.bits.prs(0)(p.physWidth - 1, 0) < p.numFpPhys.U && std.bits.size === 2.U)
            }.otherwise { assert(std.bits.prs(0)(p.physWidth - 1, 0) < p.numIntPhys.U) }
        }
        when(reset.asBool) { instDataRF.prs(0) := 0.U }
    }

    /* D1: start SQ and SB lookup beside the cache-array lookup. */
    val forwardIndex = io.cache.forward.query.bits.slot
    val currentRequest = io.cache.req.fire && io.cache.req.bits.slot === forwardIndex
    val queryLive = valid(forwardIndex) && (sent(forwardIndex) || currentRequest) &&
        !killed(pending(forwardIndex))
    val forwardQuery = io.cache.forward.query.valid && queryLive
    io.cmt.sqQuery.valid := forwardQuery
    io.cmt.sbQuery.valid := forwardQuery
    io.cmt.sbQuery.bits := io.cache.forward.query.bits
    InheritFields(io.cmt.sqQuery.bits, io.cache.forward.query.bits)
    io.cmt.sqQuery.bits.sqTail := pending(forwardIndex).sqTail

    val resultIndex = io.cmt.sqResult.bits.slot
    val resultLive = valid(resultIndex) && sent(resultIndex) && !killed(pending(resultIndex))
    val resultsAligned = io.cmt.sqResult.bits.slot === io.cmt.sbResult.bits.slot
    io.cache.forward.result.valid := io.cmt.sqResult.valid && io.cmt.sbResult.valid &&
        resultsAligned && resultLive && !io.cmt.flush
    io.cache.forward.result.bits.slot := resultIndex
    io.cache.forward.result.bits.mask := io.cmt.sqResult.bits.mask | io.cmt.sbResult.bits.mask
    io.cache.forward.result.bits.blocked := io.cmt.sqResult.bits.blocked || io.cmt.sbResult.bits.blocked
    io.cache.forward.result.bits.data := VecInit((0 until 4).map(b =>
        Mux(
            io.cmt.sqResult.bits.mask(b),
            io.cmt.sqResult.bits.data(8 * b + 7, 8 * b),
            io.cmt.sbResult.bits.data(8 * b + 7, 8 * b)
        )
    )).asUInt
    when(io.cmt.sqResult.valid && io.cmt.sbResult.valid && !io.cmt.flush) {
        assert(resultsAligned, "SQ and SB forwarding responses must identify the same load")
    }

    /* D2 selects the context while DCache captures data; both arrive in WB together. */
    val indexD2WB = io.cache.wbSelect.bits.slot
    val selectD2WB = VecInit((0 until p.entries).map(i => indexD2WB === i.U))
    val contextD2WB = Mux1H(selectD2WB, pending)
    val contextValidD2WB = VecInit((0 until p.entries).map(i =>
        selectD2WB(i) && valid(i) && sent(i) && !killed(pending(i))
    )).asUInt.orR
    val instPkgWB = RegEnable(contextD2WB, io.cache.wbSelect.valid)
    val addressWB = RegEnable(Mux1H(selectD2WB, address), io.cache.wbSelect.valid)
    val indexWB = RegEnable(indexD2WB, io.cache.wbSelect.valid)
    val contextValidWB = RegEnable(contextValidD2WB, false.B, io.cache.wbSelect.valid)
    val exceptionWB = RegEnable(io.cache.wbSelect.bits.exception, io.cache.wbSelect.valid)
    val retryWB = RegEnable(io.cache.wbSelect.bits.retry, false.B, io.cache.wbSelect.valid)
    val uncacheWB = RegEnable(io.cache.wbSelect.bits.uncache, false.B, io.cache.wbSelect.valid)
    val writeResultWB = RegEnable(
        contextD2WB.rdVld && io.cache.wbSelect.bits.exception === 0.U && !io.cache.wbSelect.bits.retry,
        false.B,
        io.cache.wbSelect.valid
    )
    val validWB = io.cache.rsp.valid && contextValidWB && !killed(instPkgWB)

    io.rf.wr.valid := validWB && writeResultWB
    io.rf.wr.bits.prd := instPkgWB.prd
    io.rf.wr.bits.data := io.cache.rsp.bits.data
    io.cmt.rob.valid := validWB && !retryWB
    io.cmt.rob.bits.robIdx := instPkgWB.robIdx
    io.cmt.rob.bits.vaddr := addressWB
    io.cmt.rob.bits.exception := exceptionWB
    io.cmt.rob.bits.data := io.cache.rsp.bits.data
    io.wk.replay.valid := validWB && retryWB
    io.wk.replay.bits := instPkgWB
    io.wk.replay.bits.uncache := instPkgWB.uncache || uncacheWB
    io.wk.replay.bits.ioAuthorized := false.B

    // Queue and ReadyBoard flush priority discards a coincident wakeup, so the
    // event does not need the RF/ROB flush and context-valid guards.
    io.wk.wakeWB.prd := Mux(io.cache.rsp.valid && writeResultWB, instPkgWB.prd, 0.U)
    io.wk.wakeWB.specMask := 0.U
    // The speculative RF wake predicts a fixed response cycle. A miss or retry kills
    // dependent consumers through the same token instead of extending this timing path.
    io.bypass.nextWb.valid := expectedValid(1) && !io.cmt.flush
    io.bypass.nextWb.bits := expectedPrd(1)
    // Load data does not exist in the nextWb announcement cycle.
    io.bypass.nextResult.valid := false.B
    io.bypass.nextResult.bits := 0.U
    io.bypass.result := io.cache.rsp.bits.data

    when(io.cache.rsp.valid && !killed(instPkgWB)) {
        assert(io.cache.rsp.bits.slot === indexWB && validWB, "Cache response must match the selected WB context")
    }
    when(io.cache.wbSelect.valid) {
        assert(indexD2WB < p.entries.U && contextValidD2WB, "DCache WB select must name a live accepted load")
    }
    when(io.cache.forward.query.valid && !killed(pending(forwardIndex))) {
        assert(
            io.cache.forward.query.bits.slot < p.entries.U && forwardQuery,
            "Cache forwarding must name a live load"
        )
    }
}
