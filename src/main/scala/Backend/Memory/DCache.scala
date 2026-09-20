// Adapted from Zircon-2024; the original and migration baseline remain under src/test.
import chisel3._
import chisel3.util._
import ZirconConfig.Cache._
import ZirconConfig.DCacheParams
import ZirconUtil.InheritFields

class DCacheExecuteStage(p: DCacheParams) extends DLoadRequest(p) {
    val hit = UInt(l1Way.W)
    val tags = Vec(l1Way, UInt((34 - l1Index - l1Offset).W))
    val lines = Vec(l1Way, UInt(l1LineBits.W))
    val validWays = UInt(l1Way.W)
    val dirtyWays = UInt(l1Way.W)
    val lruWay = UInt(l1Way.W)
    val generation = Vec(l1Way, UInt(8.W))
    val forwardValid = Bool()
    val forwardData = UInt(32.W)
    val forwardMask = UInt(4.W)
    val forwardBlocked = Bool()
}

class DCacheLookupStage(p: DCacheParams) extends Bundle {
    val cacheIndex = UInt(l1Index.W)
    val paddr = UInt(34.W)
    val slot = UInt(p.slotWidth.W)
    val mtype = UInt(3.W)
    val uncache = Bool()
    val ioAuthorized = Bool()
    val exception = UInt(4.W)
    val translationMiss = Bool()
    val forwardValid = Bool()
    val forwardData = UInt(32.W)
    val forwardMask = UInt(4.W)
    val forwardBlocked = Bool()
}

class DCacheStoreLookupResult extends Bundle {
    val hit = UInt(l1Way.W)
    val victimWay = UInt(l1Way.W)
    val victimValid = Bool()
    val victimLine = UInt((34 - l1Offset).W)
    val victimData = UInt(l1LineBits.W)
    val victimDirty = Bool()
}

class DCache(
    val ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
    val p: DCacheParams = DCacheParams(),
    val tlbEnabled: Boolean = false,
    val observe: Boolean = false,
) extends Module {
    val io = IO(new DCacheIO(p, tlbEnabled, observe))
    require(l1Way == 2)

    val dtlb = if (tlbEnabled) Some(Module(new DataTLB)) else None
    if (tlbEnabled) {
        val manage = io.tlb.get
        val translation = dtlb.get
        val activeAsid = RegInit(0.U(9.W))
        val asidChanged = activeAsid =/= manage.control.asid
        translation.io.refill := manage.refill
        translation.io.scopeUpdate.valid := asidChanged
        translation.io.scopeUpdate.bits.asid := manage.control.asid
        translation.io.flush := manage.flush
        translation.io.lookup.foreach { lookup =>
            lookup.valid := false.B
            lookup.bits.vaddr := 0.U
        }
        when(asidChanged) {
            activeAsid := manage.control.asid
        }
        io.tlbMiss.get.foreach { miss =>
            miss.valid := false.B
            miss.bits.vaddr := 0.U
            miss.bits.store := false.B
        }
        io.storeTranslation.get.response := 0.U.asTypeOf(new DStoreTranslationResponse)
        io.storeTranslation.get.response.miss := true.B
    }

    // ==================== Address and data helpers ====================
    def index(address: UInt): UInt = address(l1Index + l1Offset - 1, l1Offset)
    def lookupAddress(request: DLoadRequest): UInt = if (tlbEnabled) request.vaddr else request.paddr
    def offset(address: UInt): UInt = address(l1Offset - 1, 0)
    def tag(address: UInt): UInt = address(33, l1Index + l1Offset)
    def byteMask(size: UInt): UInt =
        MuxLookup(size, 0.U(4.W))(Seq(0.U -> 1.U, 1.U -> 3.U, 2.U -> 15.U))
    def shiftMaskLeft(mask: UInt, amount: UInt): UInt = MuxLookup(amount, 0.U(4.W))(Seq(
        0.U -> mask,
        1.U -> Cat(mask(2, 0), 0.U(1.W)),
        2.U -> Cat(mask(1, 0), 0.U(2.W)),
        3.U -> Cat(mask(0), 0.U(3.W)),
    ))
    def accessMask(request: DLoadRequest): UInt =
        shiftMaskLeft(byteMask(request.mtype(1, 0)), request.paddr(1, 0))
    def misaligned(address: UInt, size: UInt): Bool =
        size === 3.U || (size === 1.U && address(0)) || (size === 2.U && address(1, 0).orR)
    def lineWord(line: UInt, address: UInt): UInt =
        line.asTypeOf(Vec(l1Line / 4, UInt(32.W)))(address(l1Offset - 1, 2))
    def shiftMaskRight(mask: UInt, amount: UInt): UInt = MuxLookup(amount, 0.U(4.W))(Seq(
        0.U -> mask,
        1.U -> Cat(0.U(1.W), mask(3, 1)),
        2.U -> Cat(0.U(2.W), mask(3, 2)),
        3.U -> Cat(0.U(3.W), mask(3)),
    ))
    def extend(data: UInt, mtype: UInt): UInt = MuxLookup(mtype(1, 0), data)(Seq(
        0.U -> Cat(Fill(24, data(7) && !mtype(2)), data(7, 0)),
        1.U -> Cat(Fill(16, data(15) && !mtype(2)), data(15, 0))
    ))
    def preserved(uncache: Bool, ioAuthorized: Bool): Bool = uncache && ioAuthorized

    // ==================== Cache arrays ====================
    // Load 0 owns RAM A. Load 1 shares RAM B with committed stores. Refill uses
    // Tag A and Data B and is a registered scheduling state.
    val tagTab = VecInit.fill(l1Way)(
        Module(new DualPortMaskedRam(l1IndexNum, 1, 34 - l1Index - l1Offset, ramBackend)).io
    )
    val dataTab = VecInit.fill(l1Way)(
        Module(new DualPortMaskedRam(l1IndexNum, l1Line, 8, ramBackend, readWritePort = 1)).io
    )
    val validTab = VecInit.fill(l1Way)(
        Module(new AsyncRegRam(Bool(), l1IndexNum, 1, 3, Some(false.B))).io
    )
    val dirtyTab = VecInit.fill(l1Way)(
        Module(new AsyncRegRam(Bool(), l1IndexNum, 2, 3, Some(false.B))).io
    )
    val lruTab = Module(new AsyncRegRam(UInt(l1Way.W), l1IndexNum, 4, 3, Some(1.U(l1Way.W)))).io
    val cacheGeneration = RegInit(VecInit.fill(l1IndexNum)(VecInit.fill(l1Way)(0.U(8.W))))

    // ==================== Registered interface boundaries ====================
    val requestBuffer = Reg(Vec(2, new DLoadRequest(p)))
    val requestBufferValid = RegInit(VecInit.fill(2)(false.B))
    val responseWB = Reg(Vec(2, new DLoadResponse(p)))
    val responseValid = RegInit(VecInit.fill(2)(false.B))
    val recovering = RegNext(io.flush, false.B)

    for (lane <- 0 until 2) {
        // Neither request payload nor request valid participates in ready.
        io.load(lane).req.ready := !requestBufferValid(lane) && !recovering && !io.maintenance.request
        io.load(lane).rsp.valid := responseValid(lane)
        io.load(lane).rsp.bits := responseWB(lane)
    }

    // ==================== Load lookup and execute registers ====================
    // S2 names the active synchronous RAM output. If S3 cannot accept it, a
    // fresh bit is cleared and the same fixed port rereads the address later.
    val lookup = Reg(Vec(2, new DCacheLookupStage(p)))
    val lookupValid = RegInit(VecInit.fill(2)(false.B))
    val lookupFresh = RegInit(VecInit.fill(2)(false.B))

    // S3 owns forwarding, hit completion, retry generation and MSHR allocation.
    val execute = Reg(Vec(2, new DCacheExecuteStage(p)))
    val executeValid = RegInit(VecInit.fill(2)(false.B))

    val hitNow = Wire(Vec(2, UInt(l1Way.W)))
    val linesNow = Wire(Vec(2, Vec(l1Way, UInt(l1LineBits.W))))
    val tagsNow = Wire(Vec(2, Vec(l1Way, UInt((34 - l1Index - l1Offset).W))))
    val validWaysNow = Wire(Vec(2, UInt(l1Way.W)))
    val dirtyWaysNow = Wire(Vec(2, UInt(l1Way.W)))
    for (lane <- 0 until 2) {
        hitNow(lane) := VecInit((0 until l1Way).map { way =>
            val ramTag = if (lane == 0) tagTab(way).douta else tagTab(way).doutb
            ramTag === tag(lookup(lane).paddr) && validTab(way).rdata(lane)
        }).asUInt
        linesNow(lane) := VecInit(dataTab.map(ram => if (lane == 0) ram.douta else ram.doutb))
        tagsNow(lane) := VecInit(tagTab.map(ram => if (lane == 0) ram.douta else ram.doutb))
        validWaysNow(lane) := VecInit(validTab.map(_.rdata(lane))).asUInt
        dirtyWaysNow(lane) := VecInit(dirtyTab.map(_.rdata(lane))).asUInt
        validTab.foreach(_.raddr(lane) := lookup(lane).cacheIndex)
        dirtyTab.foreach(_.raddr(lane) := lookup(lane).cacheIndex)
        lruTab.raddr(lane) := lookup(lane).cacheIndex
    }

    // ==================== Miss and store state ====================
    val missUnit = Module(new DCacheMissUnit(p))
    missUnit.io.flush := io.flush

    val Seq(
        storeIdle,
        storeBuffered,
        storeLookup,
        storeResolve,
        storeWrite,
        storeMissWait,
        storeRespond
    ) = Enum(7)
    val storeState = RegInit(storeIdle)
    val storeRequest = Reg(new DStoreRequest)
    val storeLookupResult = Reg(new DCacheStoreLookupResult)
    val storeResponse = Reg(new DStoreResponse)

    val storeResponseFire = io.store.rsp.fire
    val storeCanReplaceResponse = storeState === storeRespond && io.store.rsp.ready
    io.store.req.ready := (storeState === storeIdle || storeCanReplaceResponse) &&
        !recovering && !io.maintenance.request
    io.store.rsp.valid := storeState === storeRespond
    io.store.rsp.bits := storeResponse

    val storeDirectLookup = io.store.req.fire && !missUnit.io.busy
    val storeLookupIssue = storeDirectLookup || (storeState === storeBuffered && !missUnit.io.busy)
    val storeLookupAddress = Mux(storeDirectLookup, io.store.req.bits.paddr, storeRequest.paddr)
    val storeArrayWrite = WireDefault(false.B)
    val storeHitNow = VecInit((0 until l1Way).map { way =>
        tagTab(way).doutb === tag(storeRequest.paddr) && validTab(way).rdata(2)
    }).asUInt
    val storeValidWays = VecInit(validTab.map(_.rdata(2))).asUInt
    val storeDirtyWays = VecInit(dirtyTab.map(_.rdata(2))).asUInt
    val storeInvalidWays = ~storeValidWays & ((BigInt(1) << l1Way) - 1).U(l1Way.W)
    val storeVictimWay = Mux(storeInvalidWays.orR, PriorityEncoderOH(storeInvalidWays), lruTab.rdata(2))
    val storeVictimValid = (storeVictimWay & storeValidWays).orR
    val storeVictimDirty = Mux1H(storeVictimWay, storeDirtyWays.asBools)
    val storeVictimPaddr = Cat(Mux1H(storeVictimWay, tagTab.map(_.doutb)), index(storeRequest.paddr), 0.U(l1Offset.W))
    val storeVictimData = Mux1H(storeVictimWay, dataTab.map(_.doutb))
    val storeException = Mux(
        misaligned(storeRequest.paddr, storeRequest.size) || !storeRequest.mask.orR ||
            (storeRequest.uncache &&
                storeRequest.mask =/= (byteMask(storeRequest.size) << storeRequest.paddr(1, 0))),
        6.U,
        0.U
    )
    val storeWord = storeRequest.paddr(l1Offset - 1, 2)
    val storeLineMask = VecInit.tabulate(l1Line) { byte =>
        storeWord === (byte / 4).U && storeRequest.mask(byte % 4)
    }.asUInt
    val maintenanceStates = Enum(6)
    val maintenanceIdle = maintenanceStates(0)
    val maintenanceReadState = maintenanceStates(1)
    val maintenanceLookup = maintenanceStates(2)
    val maintenanceSend = maintenanceStates(3)
    val maintenanceWait = maintenanceStates(4)
    val maintenanceDone = maintenanceStates(5)
    val maintenanceState = RegInit(maintenanceIdle)
    val maintenanceSet = RegInit(0.U(l1Index.W))
    val maintenanceWay = RegInit(0.U(log2Ceil(l1Way).W))
    val maintenanceShouldInvalidate = RegInit(false.B)
    val maintenanceRead = maintenanceState === maintenanceReadState
    val maintenanceActive = maintenanceState =/= maintenanceIdle && maintenanceState =/= maintenanceDone
    val maintenanceWaySelect = UIntToOH(maintenanceWay, l1Way)
    val maintenanceValid = Mux1H(maintenanceWaySelect, validTab.map(_.rdata(2)))
    val maintenanceDirty = Mux1H(maintenanceWaySelect, dirtyTab.map(_.rdata(2)))
    val maintenanceTag = Mux1H(maintenanceWaySelect, tagTab.map(_.doutb))
    val maintenanceData = Mux1H(maintenanceWaySelect, dataTab.map(_.doutb))
    val maintenanceInvalidate = WireDefault(false.B)
    val maintenanceClean = WireDefault(false.B)
    val maintenanceRequest = Wire(Decoupled(new DMemoryRequest))
    maintenanceRequest.valid := maintenanceState === maintenanceSend
    maintenanceRequest.bits := 0.U.asTypeOf(new DMemoryRequest)
    maintenanceRequest.bits.paddr := Cat(maintenanceTag, maintenanceSet, 0.U(l1Offset.W))
    maintenanceRequest.bits.victimValid := true.B
    maintenanceRequest.bits.victimLine := maintenanceRequest.bits.paddr(33, l1Offset)
    maintenanceRequest.bits.victimData := maintenanceData
    maintenanceRequest.bits.victimDirty := true.B
    maintenanceRequest.bits.victimOnly := true.B
    io.maintenance.done := maintenanceState === maintenanceDone

    def advanceMaintenance(): Unit = {
        when(maintenanceWay === (l1Way - 1).U) {
            maintenanceWay := 0.U
            when(maintenanceSet === (l1IndexNum - 1).U) {
                maintenanceState := maintenanceDone
            }.otherwise {
                maintenanceSet := maintenanceSet + 1.U
                maintenanceState := maintenanceReadState
            }
        }.otherwise {
            maintenanceWay := maintenanceWay + 1.U
            maintenanceState := maintenanceReadState
        }
    }

    val pipelineIdle =
        !(requestBufferValid.asUInt.orR || lookupValid.asUInt.orR || executeValid.asUInt.orR ||
            responseValid.asUInt.orR || missUnit.io.busy || storeState =/= storeIdle)
    switch(maintenanceState) {
        is(maintenanceIdle) {
            when(io.maintenance.request && pipelineIdle) {
                maintenanceSet := 0.U
                maintenanceWay := 0.U
                maintenanceShouldInvalidate := io.maintenance.invalidate
                maintenanceState := maintenanceReadState
            }
        }
        is(maintenanceReadState) {
            maintenanceState := maintenanceLookup
        }
        is(maintenanceLookup) {
            when(maintenanceValid && maintenanceDirty) {
                maintenanceState := maintenanceSend
            }.otherwise {
                maintenanceInvalidate := maintenanceValid && maintenanceShouldInvalidate
                advanceMaintenance()
            }
        }
        is(maintenanceSend) {
            when(maintenanceRequest.fire) { maintenanceState := maintenanceWait }
        }
        is(maintenanceWait) {
            when(io.l2.rsp.fire) {
                maintenanceInvalidate := !io.l2.rsp.bits.error && maintenanceShouldInvalidate
                maintenanceClean := !io.l2.rsp.bits.error
                advanceMaintenance()
            }
        }
        is(maintenanceDone) {
            when(!io.maintenance.request) { maintenanceState := maintenanceIdle }
        }
    }

    validTab.foreach(_.raddr(2) := Mux(maintenanceActive, maintenanceSet, index(storeRequest.paddr)))
    dirtyTab.foreach(_.raddr(2) := Mux(maintenanceActive, maintenanceSet, index(storeRequest.paddr)))
    lruTab.raddr(2) := Mux(maintenanceActive, maintenanceSet, index(storeRequest.paddr))

    // ==================== S3 forwarding and completion ====================
    val effectiveForwardValid = VecInit((0 until 2).map(lane => execute(lane).forwardValid))
    val effectiveForwardData = Wire(Vec(2, UInt(32.W)))
    val effectiveForwardMask = Wire(Vec(2, UInt(4.W)))
    val effectiveForwardBlocked = Wire(Vec(2, Bool()))
    for (lane <- 0 until 2) {
        effectiveForwardData(lane) := execute(lane).forwardData
        effectiveForwardMask(lane) := execute(lane).forwardMask
        effectiveForwardBlocked(lane) := execute(lane).forwardBlocked
    }
    val forwardComplete = VecInit((0 until 2).map(lane => executeValid(lane) && effectiveForwardValid(lane)))
    val fullForward = VecInit((0 until 2).map { lane =>
        (effectiveForwardMask(lane) & accessMask(execute(lane))) === accessMask(execute(lane))
    })
    val requiresMemory = VecInit((0 until 2).map { lane =>
        forwardComplete(lane) && !execute(lane).translationMiss &&
        execute(lane).exception === 0.U && !effectiveForwardBlocked(lane) &&
        !fullForward(lane) && Mux(
            execute(lane).uncache,
            execute(lane).ioAuthorized,
            !execute(lane).hit.orR
        )
    })
    val staleLookup = VecInit((0 until 2).map { lane =>
        val changedWays = VecInit((0 until l1Way).map { way =>
            execute(lane).generation(way) =/= cacheGeneration(index(execute(lane).paddr))(way)
        }).asUInt
        val relevantWays = Mux(execute(lane).hit.orR, execute(lane).hit, Fill(l1Way, 1.U))
        forwardComplete(lane) && execute(lane).exception === 0.U && !execute(lane).uncache &&
        !fullForward(lane) && (changedWays & relevantWays).orR
    })
    val needsMiss = VecInit((0 until 2).map(lane => requiresMemory(lane) && !staleLookup(lane)))

    val missRoundRobin = RegInit(false.B)
    val bothNeedMiss = needsMiss.asUInt.andR
    val selectedMissLane = Mux(bothNeedMiss, missRoundRobin, needsMiss(1))
    val selectedExecute = Mux(selectedMissLane, execute(1), execute(0))
    val selectedValidWays = Mux(selectedMissLane, execute(1).validWays, execute(0).validWays)
    val selectedLruWay = Mux(selectedMissLane, execute(1).lruWay, execute(0).lruWay)
    val invalidWays = ~selectedValidWays & ((BigInt(1) << l1Way) - 1).U(l1Way.W)
    val victimWay = Mux(invalidWays.orR, PriorityEncoderOH(invalidWays), selectedLruWay)
    val storeResolveHit = storeState === storeResolve && storeException === 0.U &&
        !storeRequest.uncache && storeLookupResult.hit.orR
    storeArrayWrite := storeResolveHit
    val storeWriteVictimConflict = storeResolveHit &&
        index(selectedExecute.paddr) === index(storeRequest.paddr) &&
        (victimWay & storeLookupResult.hit).orR
    val storeResolveAllowsLoadMiss = storeState === storeResolve &&
        (storeException =/= 0.U || (storeResolveHit && !storeWriteVictimConflict))
    val canAllocateMiss = !missUnit.io.busy &&
        (storeState === storeIdle || storeResolveAllowsLoadMiss) && !storeDirectLookup && !io.flush
    val offerMiss = needsMiss.asUInt.orR && canAllocateMiss

    missUnit.io.allocate.valid := offerMiss
    missUnit.io.allocate.bits := 0.U.asTypeOf(new DCacheMissAllocate(p))
    InheritFields(missUnit.io.allocate.bits, selectedExecute)
    missUnit.io.allocate.bits.lane := selectedMissLane
    missUnit.io.allocate.bits.way := Mux(selectedExecute.uncache, 0.U, victimWay)
    missUnit.io.allocate.bits.forwardData := Mux(selectedMissLane, effectiveForwardData(1), effectiveForwardData(0))
    missUnit.io.allocate.bits.forwardMask :=
        Mux(selectedMissLane, effectiveForwardMask(1), effectiveForwardMask(0)) & accessMask(selectedExecute)
    missUnit.io.allocate.bits.victimValid := (victimWay & selectedValidWays).orR && !selectedExecute.uncache
    missUnit.io.allocate.bits.victimLine :=
        Cat(Mux1H(victimWay, selectedExecute.tags), index(selectedExecute.paddr))
    missUnit.io.allocate.bits.victimData := Mux1H(victimWay, selectedExecute.lines)
    missUnit.io.allocate.bits.victimDirty := Mux1H(victimWay, selectedExecute.dirtyWays.asBools)

    val storeNeedsMiss = storeState === storeResolve && storeException === 0.U &&
        (storeRequest.uncache || !storeLookupResult.hit.orR)
    when(storeNeedsMiss) {
        missUnit.io.allocate.valid := true.B
        missUnit.io.allocate.bits := 0.U.asTypeOf(new DCacheMissAllocate(p))
        missUnit.io.allocate.bits.store := true.B
        missUnit.io.allocate.bits.paddr := storeRequest.paddr
        missUnit.io.allocate.bits.uncache := storeRequest.uncache
        missUnit.io.allocate.bits.ioAuthorized := true.B
        missUnit.io.allocate.bits.way := Mux(storeRequest.uncache, 0.U, storeLookupResult.victimWay)
        missUnit.io.allocate.bits.storeData := storeRequest.data
        missUnit.io.allocate.bits.storeMask := storeRequest.mask
        missUnit.io.allocate.bits.storeSize := storeRequest.size
        missUnit.io.allocate.bits.victimValid := storeLookupResult.victimValid && !storeRequest.uncache
        missUnit.io.allocate.bits.victimLine := storeLookupResult.victimLine
        missUnit.io.allocate.bits.victimData := storeLookupResult.victimData
        missUnit.io.allocate.bits.victimDirty := storeLookupResult.victimDirty
    }

    val missCompletionForLane = VecInit((0 until 2).map { lane =>
        missUnit.io.complete.valid && !missUnit.io.complete.bits.store &&
        missUnit.io.complete.bits.lane === (lane == 1).B
    })
    val localResponseValid = Wire(Vec(2, Bool()))
    val localResponse = Wire(Vec(2, new DLoadResponse(p)))
    val localResponseFire = Wire(Vec(2, Bool()))
    val executeRelease = Wire(Vec(2, Bool()))
    val responseInputValid = Wire(Vec(2, Bool()))
    val responseInput = Wire(Vec(2, new DLoadResponse(p)))

    for (lane <- 0 until 2) {
        val selectedForAllocation = offerMiss && selectedMissLane === (lane == 1).B
        val alignedWord = Mux(
            execute(lane).hit.orR,
            lineWord(Mux1H(execute(lane).hit, execute(lane).lines), execute(lane).paddr),
            0.U
        )
        val byteOffset = execute(lane).paddr(1, 0)
        val memoryWord = alignedWord >> (byteOffset << 3)
        val shiftedForwardData = effectiveForwardData(lane) >> (byteOffset << 3)
        val shiftedForwardMask = shiftMaskRight(effectiveForwardMask(lane), byteOffset)
        val mergedWord = VecInit((0 until 4).map { byte =>
            Mux(
                shiftedForwardMask(byte),
                shiftedForwardData(8 * byte + 7, 8 * byte),
                memoryWord(8 * byte + 7, 8 * byte)
            )
        }).asUInt

        localResponseValid(lane) := forwardComplete(lane) && !selectedForAllocation && !io.flush
        localResponse(lane) := 0.U.asTypeOf(new DLoadResponse(p))
        localResponse(lane).slot := execute(lane).slot
        localResponse(lane).data := extend(mergedWord, execute(lane).mtype)
        localResponse(lane).exception := execute(lane).exception
        localResponse(lane).retry := execute(lane).exception === 0.U &&
            (execute(lane).translationMiss || effectiveForwardBlocked(lane) ||
                (execute(lane).uncache && !execute(lane).ioAuthorized) ||
                requiresMemory(lane) || staleLookup(lane))
        localResponse(lane).uncache := execute(lane).uncache && !execute(lane).translationMiss

        responseInputValid(lane) := missCompletionForLane(lane) || localResponseValid(lane)
        responseInput(lane) := localResponse(lane)
        when(missCompletionForLane(lane)) {
            responseInput(lane) := missUnit.io.complete.bits.response
        }
        localResponseFire(lane) := localResponseValid(lane) && !missCompletionForLane(lane)
        executeRelease(lane) := localResponseFire(lane) ||
            (offerMiss && missUnit.io.allocate.fire && selectedMissLane === (lane == 1).B)
    }
    missUnit.io.complete.ready := !io.flush

    for (lane <- 0 until 2) {
        // Select LoadPipeline metadata in parallel with capturing the DCache WB data.
        io.load(lane).wbSelect.valid := responseInputValid(lane) && !io.flush
        io.load(lane).wbSelect.bits.slot := responseInput(lane).slot
        io.load(lane).wbSelect.bits.exception := responseInput(lane).exception
        io.load(lane).wbSelect.bits.retry := responseInput(lane).retry
        io.load(lane).wbSelect.bits.uncache := responseInput(lane).uncache
    }

    // Each lane has one fixed WB register. Its current response is consumed every
    // cycle, while a new local or miss response replaces it at the same edge.
    when(io.flush) {
        for (lane <- 0 until 2) {
            // The current WB value is visible in this cycle and retires at this edge.
            // Ordinary responses are rejected by LoadPipeline; authorized IO may complete.
            responseValid(lane) := false.B
        }
    }.otherwise {
        for (lane <- 0 until 2) {
            responseValid(lane) := responseInputValid(lane)
            when(responseInputValid(lane)) {
                responseWB(lane) := responseInput(lane)
            }
        }
    }

    when(offerMiss && missUnit.io.allocate.fire && bothNeedMiss) {
        missRoundRobin := !missRoundRobin
    }

    // ==================== Load input and RAM scheduling ====================
    val executeAvailable = VecInit((0 until 2).map(lane => !executeValid(lane) || executeRelease(lane)))
    val lookupResponseMatch = VecInit((0 until 2).map { lane =>
        io.forward(lane).result.valid && io.forward(lane).result.bits.slot === lookup(lane).slot
    })
    val lookupForwardValid = VecInit((0 until 2).map { lane =>
        lookup(lane).forwardValid || lookupResponseMatch(lane)
    })
    val lookupMove = VecInit((0 until 2).map { lane =>
        lookupValid(lane) && lookupFresh(lane) && lookupForwardValid(lane) && executeAvailable(lane)
    })
    val lookupAvailable = VecInit((0 until 2).map(lane => !lookupValid(lane) || lookupMove(lane)))
    val inputFire = VecInit((0 until 2).map(lane => io.load(lane).req.fire))
    val candidateValid = Wire(Vec(2, Bool()))
    val rawCandidate = Wire(Vec(2, new DLoadRequest(p)))
    val candidate = Wire(Vec(2, new DLoadRequest(p)))
    val arrayBlocked = Wire(Vec(2, Bool()))
    val lookupReissue = Wire(Vec(2, Bool()))
    val loadIssue = Wire(Vec(2, Bool()))
    val arrayRead = Wire(Vec(2, Bool()))
    val arrayAddress = Wire(Vec(2, UInt(34.W)))
    val installActive = missUnit.io.install.valid

    for (lane <- 0 until 2) {
        candidateValid(lane) := requestBufferValid(lane) || inputFire(lane)
        rawCandidate(lane) := Mux(requestBufferValid(lane), requestBuffer(lane), io.load(lane).req.bits)
        candidate(lane) := rawCandidate(lane)
        arrayBlocked(lane) := maintenanceActive || installActive || storeArrayWrite ||
            (if (lane == 1) storeLookupIssue else false.B)
        lookupReissue(lane) := lookupValid(lane) && !lookupFresh(lane) && executeAvailable(lane) &&
            !arrayBlocked(lane) && !io.flush
        loadIssue(lane) := candidateValid(lane) && lookupAvailable(lane) && !arrayBlocked(lane) && !io.flush
        io.load(lane).fixedLatency := io.load(lane).req.ready && !lookupValid(lane) &&
            !arrayBlocked(lane) && !io.flush
        arrayRead(lane) := loadIssue(lane) || lookupReissue(lane)
        arrayAddress(lane) := Mux(
            lookupReissue(lane),
            Cat(lookup(lane).cacheIndex, 0.U(l1Offset.W)),
            lookupAddress(candidate(lane))
        )
    }

    if (tlbEnabled) {
        val manage = io.tlb.get
        val translation = dtlb.get
        val asidChanged = translation.io.scopeUpdate.valid
        val direct = !manage.control.enabled || manage.control.privilege === 3.U
        val storeRequestValid = io.storeTranslation.get.request.valid && !loadIssue(1)

        for (lane <- 0 until 2) {
            translation.io.lookup(lane).valid := loadIssue(lane)
            translation.io.lookup(lane).bits.vaddr := rawCandidate(lane).vaddr
            val hit = translation.io.response(lane).hit && !asidChanged && !manage.flush
            val miss = rawCandidate(lane).exception === 0.U && !direct && !hit
            val permissionFault = !direct && hit &&
                !MMUPermission.data(translation.io.response(lane), manage.control, false.B)
            val accessFault = !direct && hit && translation.io.response(lane).pma === PMAAttribute.invalid
            candidate(lane).paddr := Mux(
                direct,
                Cat(0.U(2.W), rawCandidate(lane).vaddr),
                translation.io.response(lane).paddr
            )
            candidate(lane).uncache := Mux(
                direct,
                PMA.attribute(Cat(0.U(2.W), rawCandidate(lane).vaddr)) =/= PMAAttribute.cached,
                translation.io.response(lane).pma =/= PMAAttribute.cached
            )
            candidate(lane).exception := Mux(
                rawCandidate(lane).exception =/= 0.U,
                rawCandidate(lane).exception,
                Mux(
                    direct && !PMA.readable(Cat(0.U(2.W), rawCandidate(lane).vaddr)),
                    5.U,
                    Mux(permissionFault, 13.U, Mux(accessFault, 5.U, 0.U)),
                )
            )
            candidate(lane).translationMiss := miss
            io.tlbMiss.get(lane).valid := loadIssue(lane) && miss
            io.tlbMiss.get(lane).bits.vaddr := rawCandidate(lane).vaddr
        }

        when(storeRequestValid) {
            translation.io.lookup(1).valid := true.B
            translation.io.lookup(1).bits.vaddr := io.storeTranslation.get.request.bits.vaddr
            val hit = translation.io.response(1).hit && !asidChanged && !manage.flush
            val miss = io.storeTranslation.get.request.bits.exception === 0.U && !direct && !hit
            val readPermitted = MMUPermission.data(translation.io.response(1), manage.control, false.B)
            val writePermitted = MMUPermission.data(translation.io.response(1), manage.control, true.B)
            val permissionFault = !direct && hit && Mux(
                io.storeTranslation.get.request.bits.atomic,
                !readPermitted || (!io.storeTranslation.get.request.bits.lr && !writePermitted),
                !writePermitted,
            )
            val accessFault = !direct && hit && translation.io.response(1).pma === PMAAttribute.invalid
            val directAccessFault = Mux(
                io.storeTranslation.get.request.bits.atomic,
                !PMA.readable(Cat(0.U(2.W), io.storeTranslation.get.request.bits.vaddr)) ||
                    (!io.storeTranslation.get.request.bits.lr &&
                        !PMA.writable(Cat(0.U(2.W), io.storeTranslation.get.request.bits.vaddr))),
                !PMA.writable(Cat(0.U(2.W), io.storeTranslation.get.request.bits.vaddr)),
            )
            val faultCause = Mux(io.storeTranslation.get.request.bits.lr, 5.U, 7.U)
            val pageFaultCause = Mux(io.storeTranslation.get.request.bits.lr, 13.U, 15.U)
            io.storeTranslation.get.response.paddr := Mux(
                direct,
                Cat(0.U(2.W), io.storeTranslation.get.request.bits.vaddr),
                translation.io.response(1).paddr
            )
            io.storeTranslation.get.response.uncache := Mux(
                direct,
                PMA.attribute(Cat(0.U(2.W), io.storeTranslation.get.request.bits.vaddr)) =/=
                    PMAAttribute.cached,
                translation.io.response(1).pma =/= PMAAttribute.cached
            )
            io.storeTranslation.get.response.exception := Mux(
                io.storeTranslation.get.request.bits.exception =/= 0.U,
                io.storeTranslation.get.request.bits.exception,
                Mux(
                    direct && directAccessFault,
                    faultCause,
                    Mux(permissionFault, pageFaultCause, Mux(accessFault, faultCause, 0.U)),
                )
            )
            io.storeTranslation.get.response.miss := miss
            io.tlbMiss.get(1).valid := miss
            io.tlbMiss.get(1).bits.vaddr := io.storeTranslation.get.request.bits.vaddr
            io.tlbMiss.get(1).bits.store := true.B
        }
    }

    for (lane <- 0 until 2) {
        val needsForward = !candidate(lane).translationMiss && !candidate(lane).uncache &&
            candidate(lane).exception === 0.U &&
            !misaligned(candidate(lane).paddr, candidate(lane).mtype(1, 0))
        io.forward(lane).query.valid := loadIssue(lane) && needsForward
        io.forward(lane).query.bits.wordAddress := candidate(lane).paddr(33, 2)
        io.forward(lane).query.bits.slot := candidate(lane).slot
        io.forward(lane).query.bits.mask := accessMask(candidate(lane))
        when(io.forward(lane).result.valid && !io.flush) {
            assert(
                lookupValid(lane) && io.forward(lane).result.bits.slot === lookup(lane).slot,
                "Forwarding response must match the active DCache lookup"
            )
        }
    }

    // ==================== Load pipeline state updates ====================
    when(io.flush) {
        for (lane <- 0 until 2) {
            when(requestBufferValid(lane) && !preserved(requestBuffer(lane).uncache, requestBuffer(lane).ioAuthorized)) {
                requestBufferValid(lane) := false.B
            }
            when(lookupValid(lane) && !preserved(lookup(lane).uncache, lookup(lane).ioAuthorized)) {
                lookupValid(lane) := false.B
                lookupFresh(lane) := false.B
            }.elsewhen(lookupValid(lane)) {
                lookupFresh(lane) := false.B
            }
            when(executeValid(lane) && !preserved(execute(lane).uncache, execute(lane).ioAuthorized)) {
                executeValid(lane) := false.B
            }
            when(inputFire(lane) && preserved(io.load(lane).req.bits.uncache, io.load(lane).req.bits.ioAuthorized)) {
                requestBuffer(lane) := io.load(lane).req.bits
                requestBufferValid(lane) := true.B
            }
        }
    }.otherwise {
        for (lane <- 0 until 2) {
            when(requestBufferValid(lane) && loadIssue(lane)) {
                requestBufferValid(lane) := false.B
            }
            when(inputFire(lane) && !loadIssue(lane)) {
                requestBuffer(lane) := io.load(lane).req.bits
                requestBufferValid(lane) := true.B
            }

            when(lookupMove(lane)) {
                execute(lane) := 0.U.asTypeOf(new DCacheExecuteStage(p))
                InheritFields(execute(lane), lookup(lane))
                execute(lane).hit := hitNow(lane)
                execute(lane).tags := tagsNow(lane)
                execute(lane).lines := linesNow(lane)
                execute(lane).validWays := validWaysNow(lane)
                execute(lane).dirtyWays := dirtyWaysNow(lane)
                execute(lane).lruWay := lruTab.rdata(lane)
                execute(lane).generation := cacheGeneration(index(lookup(lane).paddr))
                execute(lane).forwardValid := lookupForwardValid(lane)
                execute(lane).forwardData := Mux(
                    lookup(lane).forwardValid,
                    lookup(lane).forwardData,
                    io.forward(lane).result.bits.data
                )
                execute(lane).forwardMask := Mux(
                    lookup(lane).forwardValid,
                    lookup(lane).forwardMask,
                    io.forward(lane).result.bits.mask
                )
                execute(lane).forwardBlocked := Mux(
                    lookup(lane).forwardValid,
                    lookup(lane).forwardBlocked,
                    io.forward(lane).result.bits.blocked
                )
                when(lookup(lane).exception === 0.U && misaligned(lookup(lane).paddr, lookup(lane).mtype(1, 0))) {
                    execute(lane).exception := 4.U
                }
                executeValid(lane) := true.B
            }.elsewhen(executeRelease(lane)) {
                executeValid(lane) := false.B
            }

            when(loadIssue(lane)) {
                lookup(lane) := 0.U.asTypeOf(new DCacheLookupStage(p))
                InheritFields(lookup(lane), candidate(lane))
                lookup(lane).cacheIndex := index(lookupAddress(candidate(lane)))
                lookup(lane).forwardValid := candidate(lane).translationMiss || candidate(lane).uncache ||
                    candidate(lane).exception =/= 0.U ||
                    misaligned(candidate(lane).paddr, candidate(lane).mtype(1, 0))
                lookupValid(lane) := true.B
                lookupFresh(lane) := true.B
            }.elsewhen(lookupMove(lane)) {
                lookupValid(lane) := false.B
                lookupFresh(lane) := false.B
            }.elsewhen(lookupReissue(lane)) {
                lookupFresh(lane) := true.B
            }.elsewhen(lookupValid(lane) && lookupFresh(lane)) {
                lookupFresh(lane) := false.B
            }
            when(lookupResponseMatch(lane) && !lookupMove(lane) && !loadIssue(lane)) {
                lookup(lane).forwardValid := true.B
                lookup(lane).forwardData := io.forward(lane).result.bits.data
                lookup(lane).forwardMask := io.forward(lane).result.bits.mask
                lookup(lane).forwardBlocked := io.forward(lane).result.bits.blocked
            }
        }
    }

    if (tlbEnabled) {
        for (lane <- 0 until 2) {
            when(loadIssue(lane) && !candidate(lane).translationMiss) {
                assert(
                    candidate(lane).paddr(l1Offset + l1Index - 1, 0) ===
                        candidate(lane).vaddr(l1Offset + l1Index - 1, 0),
                    "DCache: translation changed VIPT page-offset index bits"
                )
            }
        }
    }

    // ==================== Store pipeline ====================
    when(io.store.req.fire) {
        storeRequest := io.store.req.bits
        storeState := Mux(missUnit.io.busy, storeBuffered, storeLookup)
    }
    when(storeLookupIssue) {
        storeState := storeLookup
    }
    when(storeState === storeLookup) {
        // Capture every SRAM result unconditionally. The tag comparison only
        // terminates at the narrow hit register and cannot control MissUnit state.
        storeLookupResult.hit := storeHitNow
        storeLookupResult.victimWay := storeVictimWay
        storeLookupResult.victimValid := storeVictimValid
        storeLookupResult.victimLine := storeVictimPaddr(33, l1Offset)
        storeLookupResult.victimData := storeVictimData
        storeLookupResult.victimDirty := storeVictimDirty
        storeState := storeResolve
    }
    when(storeState === storeResolve) {
        when(storeException =/= 0.U) {
            storeResponse.exception := storeException
            storeState := storeRespond
        }.elsewhen(!storeRequest.uncache && storeLookupResult.hit.orR) {
            storeResponse.exception := 0.U
            storeState := storeRespond
        }.elsewhen(missUnit.io.allocate.fire) {
            storeState := storeMissWait
        }
    }
    when(storeState === storeMissWait && missUnit.io.complete.valid && missUnit.io.complete.bits.store) {
        storeResponse := missUnit.io.complete.bits.storeResponse
        storeState := storeRespond
    }
    when(storeResponseFire) {
        storeState := Mux(io.store.req.fire, Mux(missUnit.io.busy, storeBuffered, storeLookup), storeIdle)
    }

    // ==================== Lower-memory transaction ====================
    io.l2.req.valid := Mux(maintenanceActive, maintenanceRequest.valid, missUnit.io.memory.req.valid)
    io.l2.req.bits := Mux(maintenanceActive, maintenanceRequest.bits, missUnit.io.memory.req.bits)
    maintenanceRequest.ready := maintenanceActive && io.l2.req.ready
    missUnit.io.memory.req.ready := !maintenanceActive && io.l2.req.ready
    missUnit.io.memory.rsp.valid := !maintenanceActive && io.l2.rsp.valid
    missUnit.io.memory.rsp.bits := io.l2.rsp.bits
    io.l2.rsp.ready := Mux(maintenanceActive, maintenanceState === maintenanceWait, missUnit.io.memory.rsp.ready)

    // ==================== Refill and array connections ====================
    missUnit.io.install.ready := !io.flush
    val installFire = missUnit.io.install.fire
    val installAddress = Cat(missUnit.io.install.bits.line, 0.U(l1Offset.W))
    for (way <- 0 until l1Way) {
        tagTab(way).clka := clock
        tagTab(way).addra := Mux(installFire, index(installAddress), index(arrayAddress(0)))
        tagTab(way).ena := installFire || arrayRead(0)
        tagTab(way).wea := installFire && missUnit.io.install.bits.way(way)
        tagTab(way).dina := tag(installAddress)
        tagTab(way).addrb := Mux(
            maintenanceRead,
            maintenanceSet,
            Mux(storeLookupIssue, index(storeLookupAddress), index(arrayAddress(1)))
        )
        tagTab(way).enb := maintenanceRead || storeLookupIssue || arrayRead(1)
        tagTab(way).web := 0.U
        tagTab(way).dinb := 0.U

        dataTab(way).clka := clock
        dataTab(way).addra := index(arrayAddress(0))
        dataTab(way).ena := arrayRead(0)
        dataTab(way).wea := 0.U
        dataTab(way).dina := 0.U
        dataTab(way).addrb := Mux(
            maintenanceRead,
            maintenanceSet,
            Mux(
                installFire,
                index(installAddress),
                Mux(
                    storeLookupIssue,
                    index(storeLookupAddress),
                    Mux(storeArrayWrite, index(storeRequest.paddr), index(arrayAddress(1)))
                )
            )
        )
        dataTab(way).enb := maintenanceRead || installFire || storeLookupIssue || storeArrayWrite || arrayRead(1)
        dataTab(way).web := Mux(
            installFire,
            Fill(l1Line, missUnit.io.install.bits.way(way)),
            Mux(
                storeArrayWrite && !storeRequest.uncache && storeLookupResult.hit(way),
                storeLineMask,
                0.U
            )
        )
        dataTab(way).dinb := Mux(
            installFire,
            missUnit.io.install.bits.data,
            Fill(l1Line / 4, storeRequest.data)
        )

        validTab(way).wen(0) := maintenanceInvalidate && maintenanceWay === way.U ||
            (installFire && missUnit.io.install.bits.way(way))
        validTab(way).waddr(0) := Mux(
            maintenanceInvalidate,
            maintenanceSet,
            index(installAddress)
        )
        validTab(way).wdata(0) := !maintenanceInvalidate

        dirtyTab(way).wen(0) := (maintenanceInvalidate || maintenanceClean) && maintenanceWay === way.U ||
            (installFire && missUnit.io.install.bits.way(way))
        dirtyTab(way).waddr(0) := Mux(
            maintenanceInvalidate || maintenanceClean,
            maintenanceSet,
            index(installAddress)
        )
        dirtyTab(way).wdata(0) := Mux(
            maintenanceInvalidate || maintenanceClean,
            false.B,
            missUnit.io.install.bits.dirty,
        )
        dirtyTab(way).wen(1) := storeArrayWrite && storeLookupResult.hit(way)
        dirtyTab(way).waddr(1) := index(storeRequest.paddr)
        dirtyTab(way).wdata(1) := true.B
    }

    for (lane <- 0 until 2) {
        val hitCompletion = localResponseFire(lane) && execute(lane).exception === 0.U &&
            !execute(lane).uncache && execute(lane).hit.orR
        lruTab.wen(lane) := hitCompletion
        lruTab.waddr(lane) := index(execute(lane).paddr)
        lruTab.wdata(lane) := ~execute(lane).hit
    }
    lruTab.wen(2) := storeArrayWrite && storeLookupResult.hit.orR
    lruTab.waddr(2) := index(storeRequest.paddr)
    lruTab.wdata(2) := ~storeLookupResult.hit
    lruTab.wen(3) := installFire
    lruTab.waddr(3) := index(installAddress)
    lruTab.wdata(3) := ~missUnit.io.install.bits.way
    when(installFire) {
        val installSet = index(installAddress)
        for (way <- 0 until l1Way) {
            when(missUnit.io.install.bits.way(way)) {
                cacheGeneration(installSet)(way) := cacheGeneration(installSet)(way) + 1.U
            }
        }
    }
    when(storeArrayWrite) {
        val storeSet = index(storeRequest.paddr)
        for (way <- 0 until l1Way) {
            when(storeLookupResult.hit(way)) {
                cacheGeneration(storeSet)(way) := cacheGeneration(storeSet)(way) + 1.U
            }
        }
    }

    io.idle := pipelineIdle && maintenanceState === maintenanceIdle

    assert(!(installFire && storeArrayWrite), "DCache: refill and committed store write overlap")
    for (lane <- 0 until 2) {
        when(lookupMove(lane)) {
            assert(PopCount(hitNow(lane)) <= 1.U, "DCache: multiple hits")
        }
    }

    /* Simulation-only counters stay after the cache datapath and invariants. */
    if (observe) {
        val loadVisits = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadHits = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadMisses = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetries = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryTranslation = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryForwardBlocked = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryUncachedOrder = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryStaleLookup = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryMissBusy = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryStoreConflict = RegInit(VecInit.fill(2)(0.U(64.W)))
        val loadRetryLaneConflict = RegInit(VecInit.fill(2)(0.U(64.W)))
        val storeVisits = RegInit(0.U(64.W))
        val storeHits = RegInit(0.U(64.W))
        val storeMisses = RegInit(0.U(64.W))
        val missBusyCycles = RegInit(0.U(64.W))
        for (lane <- 0 until 2) {
            val cachedLookup = lookupMove(lane) && lookup(lane).exception === 0.U &&
                !lookup(lane).translationMiss && !lookup(lane).uncache
            when(cachedLookup) {
                loadVisits(lane) := loadVisits(lane) + 1.U
                when(hitNow(lane).orR) { loadHits(lane) := loadHits(lane) + 1.U }
            }
            when(offerMiss && missUnit.io.allocate.fire && selectedMissLane === (lane == 1).B &&
                !selectedExecute.uncache) {
                loadMisses(lane) := loadMisses(lane) + 1.U
            }
            when(localResponseFire(lane) && localResponse(lane).retry) {
                loadRetries(lane) := loadRetries(lane) + 1.U
                when(execute(lane).translationMiss) {
                    loadRetryTranslation(lane) := loadRetryTranslation(lane) + 1.U
                }.elsewhen(effectiveForwardBlocked(lane)) {
                    loadRetryForwardBlocked(lane) := loadRetryForwardBlocked(lane) + 1.U
                }.elsewhen(execute(lane).uncache && !execute(lane).ioAuthorized) {
                    loadRetryUncachedOrder(lane) := loadRetryUncachedOrder(lane) + 1.U
                }.elsewhen(staleLookup(lane)) {
                    loadRetryStaleLookup(lane) := loadRetryStaleLookup(lane) + 1.U
                }.elsewhen(missUnit.io.busy) {
                    loadRetryMissBusy(lane) := loadRetryMissBusy(lane) + 1.U
                }.elsewhen(storeState =/= storeIdle && !storeResolveAllowsLoadMiss) {
                    loadRetryStoreConflict(lane) := loadRetryStoreConflict(lane) + 1.U
                }.otherwise {
                    loadRetryLaneConflict(lane) := loadRetryLaneConflict(lane) + 1.U
                }
            }
        }
        val cachedStoreLookup = storeState === storeLookup && storeException === 0.U && !storeRequest.uncache
        when(cachedStoreLookup) {
            storeVisits := storeVisits + 1.U
            when(storeHitNow.orR) { storeHits := storeHits + 1.U }
        }
        when(storeNeedsMiss && missUnit.io.allocate.fire && !storeRequest.uncache) {
            storeMisses := storeMisses + 1.U
        }
        when(missUnit.io.busy) { missBusyCycles := missBusyCycles + 1.U }
        io.performance.get.loadVisits := loadVisits
        io.performance.get.loadHits := loadHits
        io.performance.get.loadMisses := loadMisses
        io.performance.get.loadRetries := loadRetries
        io.performance.get.loadRetryTranslation := loadRetryTranslation
        io.performance.get.loadRetryForwardBlocked := loadRetryForwardBlocked
        io.performance.get.loadRetryUncachedOrder := loadRetryUncachedOrder
        io.performance.get.loadRetryStaleLookup := loadRetryStaleLookup
        io.performance.get.loadRetryMissBusy := loadRetryMissBusy
        io.performance.get.loadRetryStoreConflict := loadRetryStoreConflict
        io.performance.get.loadRetryLaneConflict := loadRetryLaneConflict
        io.performance.get.storeVisits := storeVisits
        io.performance.get.storeHits := storeHits
        io.performance.get.storeMisses := storeMisses
        io.performance.get.missBusyCycles := missBusyCycles
        io.debug.get.requestBufferValid := requestBufferValid.asUInt
        io.debug.get.lookupValid := lookupValid.asUInt
        io.debug.get.lookupFresh := lookupFresh.asUInt
        io.debug.get.executeValid := executeValid.asUInt
        io.debug.get.responseValid := responseValid.asUInt
        io.debug.get.forwardQueryValid := io.forward(1).query.valid
        io.debug.get.forwardResultValid := io.forward(1).result.valid
        io.debug.get.lookupResponseMatch := lookupResponseMatch(1)
        io.debug.get.lookupMove := lookupMove(1)
        io.debug.get.executeRelease := executeRelease(1)
        io.debug.get.missBusy := missUnit.io.busy
        io.debug.get.storeState := storeState
        io.debug.get.flush := io.flush
    }
}
