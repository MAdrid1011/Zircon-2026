import chisel3._
import chisel3.util._
import ZirconConfig.{ICacheParams, L2CacheParams}

class L2InstructionStageRequest(c: ICacheParams) extends Bundle {
    val ptw = Bool()
    val paddr = UInt(34.W)
    val uncache = Bool()
    val victimValid = Bool()
    val victimLine = UInt((34 - c.offsetBits).W)
    val victimData = UInt(c.lineBits.W)
}

class L2DataStageRequest(p: L2CacheParams) extends Bundle {
    val ptw = Bool()
    val paddr = UInt(34.W)
    val write = Bool()
    val uncache = Bool()
    val size = UInt(2.W)
    val data = UInt(p.lineBits.W)
    val mask = UInt(4.W)
    val victimValid = Bool()
    val victimLine = UInt((34 - p.offsetBits).W)
    val victimData = UInt(p.lineBits.W)
    val victimDirty = Bool()
    val victimOnly = Bool()
}

/** A dual-channel, three-stage, victim-biased non-inclusive L2 cache.
  *
  * ICache/ITLB own the read-only SRAM port and DCache/DTLB own the read-write
  * SRAM port. Each L1 cache has same-cycle priority over its PTW. S1 registers
  * the request and launches the SRAM read, S2 compares tags and captures the
  * selected line, and S3 responds or enters the shared refill/victim/writeback
  * engine. The engine serializes lower-memory traffic and array writes without
  * serializing independent I/D hit lookups.
  */
class L2Cache(
    val ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
    val c: ICacheParams = ICacheParams(),
    val p: L2CacheParams = L2CacheParams(),
    val observe: Boolean = false,
) extends Module {
    val io = IO(new L2CacheIO(c, p, observe))
    require(c.lineBytes == p.lineBytes)
    if (ramBackend == DualPortRamBackend.BSG) {
        require(p.sets == 16 && p.tagBits <= 25)
    }

    private def index(address: UInt): UInt = address(p.offsetBits + p.indexBits - 1, p.offsetBits)
    private def tag(address: UInt): UInt = address(33, p.offsetBits + p.indexBits)
    private def lineAddress(address: UInt): UInt = Cat(address(33, p.offsetBits), 0.U(p.offsetBits.W))
    private def plruVictim(state: UInt): UInt = Mux(
        !state(2),
        Mux(!state(1), 1.U(4.W), 2.U(4.W)),
        Mux(!state(0), 4.U(4.W), 8.U(4.W))
    )
    private def plruUpdate(state: UInt, way: UInt): UInt = Mux1H(
        way,
        Seq(
            Cat(1.U(1.W), 1.U(1.W), state(0)),
            Cat(1.U(1.W), 0.U(1.W), state(0)),
            Cat(0.U(1.W), state(1), 1.U(1.W)),
            Cat(0.U(1.W), state(1), 0.U(1.W))
        )
    )

    val engineStates = Enum(9)
    val engineIdle = engineStates(0)
    val engineVictimRead = engineStates(1)
    val engineVictimLookup = engineStates(2)
    val engineWritebackSend = engineStates(3)
    val engineWritebackWait = engineStates(4)
    val engineVictimInstall = engineStates(5)
    val engineMemorySend = engineStates(6)
    val engineMemoryWait = engineStates(7)
    val engineFinish = engineStates(8)
    val engineState = RegInit(engineIdle)
    val enginePreferI = RegInit(false.B)

    val engineSourceI = Reg(Bool())
    val enginePtw = Reg(Bool())
    val enginePaddr = Reg(UInt(34.W))
    val engineWrite = Reg(Bool())
    val engineUncache = Reg(Bool())
    val engineSize = Reg(UInt(2.W))
    val engineData = Reg(UInt(p.lineBits.W))
    val engineMask = Reg(UInt(8.W))
    val engineVictimValid = Reg(Bool())
    val engineVictimLine = Reg(UInt((34 - p.offsetBits).W))
    val engineVictimAddress = Cat(engineVictimLine, 0.U(p.offsetBits.W))
    val engineVictimData = Reg(UInt(p.lineBits.W))
    val engineVictimDirty = Reg(Bool())
    val engineTargetHit = Reg(Bool())
    val engineTargetWay = Reg(UInt(p.ways.W))
    val engineTargetDirty = Reg(Bool())
    val engineVictimWay = Reg(UInt(p.ways.W))
    val engineVictimWrite = RegInit(false.B)
    val engineWritebackPaddr = Reg(UInt(34.W))
    val engineWritebackData = Reg(UInt(p.lineBits.W))
    val engineResponseData = Reg(UInt(p.lineBits.W))
    val engineResponseDirty = RegInit(false.B)
    val engineResponseError = RegInit(false.B)
    val engineBackground = RegInit(false.B)

    // A clean ICache target can return before its displaced line is installed.
    // The existing engine drains this buffer with the normal quota and writeback policy.
    val instructionVictimValid = RegInit(false.B)
    val instructionVictimLine = Reg(UInt((34 - p.offsetBits).W))
    val instructionVictimAddress = Cat(instructionVictimLine, 0.U(p.offsetBits.W))
    val instructionVictimData = Reg(UInt(p.lineBits.W))
    val instructionVictimPop = Wire(Bool())
    val instructionVictimAvailable = !instructionVictimValid || instructionVictimPop

    val engineVictimPort = engineState === engineVictimRead || engineState === engineVictimLookup
    val engineArrayWrite = engineState === engineVictimInstall
    val engineArrayRecovery = RegNext(engineArrayWrite, false.B)
    // Re-read the held S2 address after a victim lookup changes a synchronous RAM port.
    val iVictimPortRecovery = RegNext(engineVictimPort && engineSourceI, false.B)
    val dVictimPortRecovery = RegNext(engineVictimPort && !engineSourceI, false.B)
    val iPipelineBlocked = engineArrayWrite || engineArrayRecovery ||
        (engineVictimPort && engineSourceI) || iVictimPortRecovery
    val dPipelineBlocked = engineArrayWrite || engineArrayRecovery ||
        (engineVictimPort && !engineSourceI) || dVictimPortRecovery

    // ==================== Three-stage channel registers ====================
    val iS1Valid = RegInit(false.B)
    val iS1 = Reg(new L2InstructionStageRequest(c))
    val iS2Valid = RegInit(false.B)
    val iS2 = Reg(new L2InstructionStageRequest(c))
    val iS3Valid = RegInit(false.B)
    val iS3 = Reg(new L2InstructionStageRequest(c))
    val iS3Hit = RegInit(false.B)
    val iS3Way = Reg(UInt(p.ways.W))
    val iS3Lines = Reg(Vec(p.ways, UInt(p.lineBits.W)))
    val iS3DirtyWays = Reg(UInt(p.ways.W))
    val iS3Started = RegInit(false.B)
    val iS3Done = RegInit(false.B)
    val iS3Result = Reg(UInt(p.lineBits.W))
    val iS3Error = RegInit(false.B)

    val dS1Valid = RegInit(false.B)
    val dS1 = Reg(new L2DataStageRequest(p))
    val dS2Valid = RegInit(false.B)
    val dS2 = Reg(new L2DataStageRequest(p))
    val dS3Valid = RegInit(false.B)
    val dS3 = Reg(new L2DataStageRequest(p))
    val dS3Hit = RegInit(false.B)
    val dS3Way = Reg(UInt(p.ways.W))
    val dS3Lines = Reg(Vec(p.ways, UInt(p.lineBits.W)))
    val dS3DirtyWays = Reg(UInt(p.ways.W))
    val dS3Started = RegInit(false.B)
    val dS3Done = RegInit(false.B)
    val dS3Result = Reg(UInt(p.lineBits.W))
    val dS3ResultDirty = RegInit(false.B)
    val dS3Error = RegInit(false.B)

    io.icache.response.valid := iS3Valid && iS3Done && !iS3.ptw
    val iS3SelectedData = Mux1H(iS3Way, iS3Lines)
    def selectPteBits(line: UInt, address: UInt): UInt = Mux1H(
        VecInit.tabulate(p.lineBytes / 4)(word => address(p.offsetBits - 1, 2) === word.U),
        line.asTypeOf(Vec(p.lineBytes / 4, UInt(32.W))).map(word => Cat(word(31, 10), word(7, 0))),
    )
    val dS3SelectedData = Mux1H(dS3Way, dS3Lines)
    val dS3SelectedDirty = dS3Hit && Mux1H(dS3Way, dS3DirtyWays.asBools)
    io.icache.response.bits.data := Mux(iS3Started, iS3Result, iS3SelectedData)
    io.icache.response.bits.error := iS3Error
    io.iptw.rsp.valid := iS3Valid && iS3Done && iS3.ptw
    io.iptw.rsp.bits.pte := Mux(
        iS3Started,
        Cat(iS3Result(31, 10), iS3Result(7, 0)),
        selectPteBits(iS3SelectedData, iS3.paddr)
    ).asTypeOf(new Sv32Pte)
    io.iptw.rsp.bits.error := iS3Error
    io.dcache.rsp.valid := dS3Valid && dS3Done && !dS3.ptw
    io.dcache.rsp.bits.data := Mux(dS3Started, dS3Result, dS3SelectedData)
    io.dcache.rsp.bits.dirty := Mux(dS3Started, dS3ResultDirty, dS3SelectedDirty)
    io.dcache.rsp.bits.error := dS3Error
    io.dptw.rsp.valid := dS3Valid && dS3Done && dS3.ptw
    io.dptw.rsp.bits.pte := Mux(
        dS3Started,
        Cat(dS3Result(31, 10), dS3Result(7, 0)),
        selectPteBits(dS3SelectedData, dS3.paddr)
    ).asTypeOf(new Sv32Pte)
    io.dptw.rsp.bits.error := dS3Error

    val iS3ResponseFire = io.icache.response.fire || io.iptw.rsp.fire
    val dS3ResponseFire = io.dcache.rsp.fire || io.dptw.rsp.fire
    val iS3Available = !iS3Valid || iS3ResponseFire
    val dS3Available = !dS3Valid || dS3ResponseFire
    val iS2Advance = iS2Valid && iS3Available && !iPipelineBlocked
    val dS2Advance = dS2Valid && dS3Available && !dPipelineBlocked
    val iS2Available = !iS2Valid || iS2Advance
    val dS2Available = !dS2Valid || dS2Advance
    val iS1Advance = iS1Valid && iS2Available && !iPipelineBlocked
    val dS1Advance = dS1Valid && dS2Available && !dPipelineBlocked
    val iS1Available = !iS1Valid || iS1Advance
    val dS1Available = !dS1Valid || dS1Advance

    val iPtwDeferred = RegInit(false.B)
    val dPtwDeferred = RegInit(false.B)
    val iPtwPriority = iPtwDeferred && io.iptw.req.valid
    val dPtwPriority = dPtwDeferred && io.dptw.req.valid
    io.icache.request.ready := iS1Available && !iPtwPriority
    io.iptw.req.ready := iS1Available && (iPtwPriority || !io.icache.request.valid)
    io.dcache.req.ready := dS1Available && !dPtwPriority
    io.dptw.req.ready := dS1Available && (dPtwPriority || !io.dcache.req.valid)

    when(!io.iptw.req.valid || io.iptw.req.fire) {
        iPtwDeferred := false.B
    }.elsewhen(io.icache.request.fire) {
        iPtwDeferred := true.B
    }
    when(!io.dptw.req.valid || io.dptw.req.fire) {
        dPtwDeferred := false.B
    }.elsewhen(io.dcache.req.fire) {
        dPtwDeferred := true.B
    }

    val iInputFire = io.icache.request.fire || io.iptw.req.fire
    val iInput = WireDefault(0.U.asTypeOf(new L2InstructionStageRequest(c)))
    when(io.icache.request.fire) {
        iInput.ptw := false.B
        iInput.paddr := io.icache.request.bits.paddr
        iInput.uncache := io.icache.request.bits.uncache
        iInput.victimValid := io.icache.request.bits.victimValid
        iInput.victimLine := io.icache.request.bits.victimLine
        iInput.victimData := io.icache.request.bits.victimData
    }.elsewhen(io.iptw.req.fire) {
        iInput.ptw := true.B
        iInput.paddr := io.iptw.req.bits.paddr
    }
    val dInputFire = io.dcache.req.fire || io.dptw.req.fire
    val dInput = WireDefault(0.U.asTypeOf(new L2DataStageRequest(p)))
    when(io.dcache.req.fire) {
        dInput.ptw := false.B
        dInput.paddr := io.dcache.req.bits.paddr
        dInput.write := io.dcache.req.bits.write
        dInput.uncache := io.dcache.req.bits.uncache
        dInput.size := io.dcache.req.bits.size
        dInput.data := io.dcache.req.bits.data
        dInput.mask := io.dcache.req.bits.mask
        dInput.victimValid := io.dcache.req.bits.victimValid
        dInput.victimLine := io.dcache.req.bits.victimLine
        dInput.victimData := io.dcache.req.bits.victimData
        dInput.victimDirty := io.dcache.req.bits.victimDirty
        dInput.victimOnly := io.dcache.req.bits.victimOnly
    }.elsewhen(io.dptw.req.fire) {
        dInput.ptw := true.B
        dInput.paddr := io.dptw.req.bits.paddr
        dInput.size := 2.U
    }

    when(iS1Advance) {
        iS1Valid := false.B
    }
    when(iInputFire) {
        iS1Valid := true.B
        iS1 := iInput
    }
    when(dS1Advance) {
        dS1Valid := false.B
    }
    when(dInputFire) {
        dS1Valid := true.B
        dS1 := dInput
    }
    when(iS2Advance) {
        iS2Valid := false.B
    }
    when(iS1Advance) {
        iS2Valid := true.B
        iS2 := iS1
    }
    when(dS2Advance) {
        dS2Valid := false.B
    }
    when(dS1Advance) {
        dS2Valid := true.B
        dS2 := dS1
    }

    // ==================== Dual-port Tag/Data lookup ====================
    val tagTab = VecInit.fill(p.ways)(
        Module(new DualPortMaskedRam(p.sets, 1, p.tagBits, ramBackend, readWritePort = 1)).io
    )
    val dataTab = VecInit.fill(p.ways)(
        Module(new DualPortMaskedRam(p.sets, p.lineBytes, 8, ramBackend, readWritePort = 1)).io
    )
    val validTab = VecInit.fill(p.ways)(Module(new AsyncRegRam(Bool(), p.sets, 2, 2, Some(false.B))).io)
    val dirtyTab = VecInit.fill(p.ways)(Module(new AsyncRegRam(Bool(), p.sets, 1, 2, Some(false.B))).io)
    val instructionTab = VecInit.fill(p.ways)(Module(new AsyncRegRam(Bool(), p.sets, 1, 2, Some(false.B))).io)
    val plruTab = Module(new AsyncRegRam(UInt(3.W), p.sets, 1, 2, Some(0.U(3.W)))).io

    val iReadAddress = Mux(iS2Valid && !iS2Advance, iS2.paddr, iS1.paddr)
    val dReadAddress = Mux(dS2Valid && !dS2Advance, dS2.paddr, dS1.paddr)
    val iReadEnable = (iS2Valid && !iS2Advance) || iS1Advance
    val dReadEnable = (dS2Valid && !dS2Advance) || dS1Advance
    val iMetaAddress = Mux(engineVictimPort && engineSourceI, engineVictimAddress, iS2.paddr)
    val dMetaAddress = Mux(engineVictimPort && !engineSourceI, engineVictimAddress, dS2.paddr)

    val iTags = VecInit(tagTab.map(_.douta))
    val iLines = VecInit(dataTab.map(_.douta))
    val dTags = VecInit(tagTab.map(_.doutb))
    val dLines = VecInit(dataTab.map(_.doutb))
    val iValidWays = VecInit(validTab.map(_.rdata(0))).asUInt
    val dValidWays = VecInit(validTab.map(_.rdata(1))).asUInt
    val iDirtyWays = VecInit(dirtyTab.map(_.rdata(0))).asUInt
    val dDirtyWays = VecInit(dirtyTab.map(_.rdata(1))).asUInt
    val iInstructionWays = VecInit(instructionTab.map(_.rdata(0))).asUInt
    val dInstructionWays = VecInit(instructionTab.map(_.rdata(1))).asUInt
    val iHit = VecInit((0 until p.ways).map(way => iValidWays(way) && iTags(way) === tag(iS2.paddr))).asUInt
    val dHit = VecInit((0 until p.ways).map(way => dValidWays(way) && dTags(way) === tag(dS2.paddr))).asUInt
    val iHitDirty = iHit.orR && Mux1H(iHit, iDirtyWays.asBools)
    val dHitDirty = dHit.orR && Mux1H(dHit, dDirtyWays.asBools)
    val iFast = !iS2.uncache && iHit.orR &&
        (iS2.ptw || !iS2.victimValid || instructionVictimAvailable)
    val dFast = !dS2.victimOnly && !dS2.uncache && dHit.orR && (dS2.ptw || !dS2.victimValid)
    val iFastConsume = iS2Advance && iFast && !iS2.ptw && !iHitDirty
    val dFastConsume = dS2Advance && dFast && !dS2.ptw

    when(iS3ResponseFire && !iS2Advance) {
        iS3Valid := false.B
    }
    when(iS2Advance) {
        iS3Valid := true.B
        iS3 := iS2
        iS3Hit := iHit.orR
        iS3Way := iHit
        iS3Lines := iLines
        iS3DirtyWays := iDirtyWays
        iS3Started := false.B
        iS3Done := iFast
        iS3Result := 0.U
        iS3Error := false.B
    }
    when(dS3ResponseFire && !dS2Advance) {
        dS3Valid := false.B
    }
    when(dS2Advance) {
        dS3Valid := true.B
        dS3 := dS2
        dS3Hit := dHit.orR
        dS3Way := dHit
        dS3Lines := dLines
        dS3DirtyWays := dDirtyWays
        dS3Started := false.B
        dS3Done := dFast
        dS3Result := 0.U
        dS3ResultDirty := false.B
        dS3Error := false.B
    }

    // ==================== Shared S3 miss/victim engine ====================
    val iWaiting = iS3Valid && !iS3Done && !iS3Started
    val dWaiting = dS3Valid && !dS3Done && !dS3Started
    val selectI = iWaiting && (!dWaiting || enginePreferI)
    val selectD = dWaiting && !selectI
    val selectInstructionVictim = !iWaiting && !dWaiting && instructionVictimValid
    instructionVictimPop := engineState === engineIdle && selectInstructionVictim
    val selectedPtw = Mux(selectInstructionVictim, false.B, Mux(selectI, iS3.ptw, dS3.ptw))
    val selectedPaddr = Mux(
        selectInstructionVictim,
        instructionVictimAddress,
        Mux(selectI, iS3.paddr, dS3.paddr)
    )
    val selectedWrite = !selectInstructionVictim && !selectI && dS3.write
    val selectedUncache = !selectInstructionVictim && Mux(selectI, iS3.uncache, dS3.uncache)
    val selectedSize = Mux(selectInstructionVictim || selectI, 2.U, dS3.size)
    val selectedMask = Mux(
        selectInstructionVictim || selectI,
        0.U(8.W),
        Mux(dS3.paddr(2), Cat(dS3.mask, 0.U(4.W)), Cat(0.U(4.W), dS3.mask))
    )
    val selectedRequestData = Mux(
        selectInstructionVictim || selectI,
        0.U,
        Mux(dS3.paddr(2), dS3.data << 32, dS3.data)
    )
    val selectedData = Mux(selectInstructionVictim || selectI, 0.U, dS3.data)
    val selectedVictimValid = Mux(
        selectInstructionVictim,
        true.B,
        Mux(selectI, iS3.victimValid && !iS3.ptw, dS3.victimValid)
    )
    val selectedVictimPaddr = Mux(
        selectInstructionVictim,
        instructionVictimAddress,
        Mux(
            selectI,
            Cat(iS3.victimLine, 0.U(p.offsetBits.W)),
            Cat(dS3.victimLine, 0.U(p.offsetBits.W)),
        )
    )
    val selectedVictimData = Mux(
        selectInstructionVictim,
        instructionVictimData,
        Mux(selectI, iS3.victimData, dS3.victimData)
    )
    val selectedVictimDirty = !selectInstructionVictim && !selectI && dS3.victimDirty
    val selectedVictimOnly = selectInstructionVictim || (!selectI && dS3.victimOnly)
    val selectedHit = !selectInstructionVictim && Mux(selectI, iS3Hit, dS3Hit)
    val selectedWay = Mux(selectInstructionVictim, 0.U, Mux(selectI, iS3Way, dS3Way))
    val selectedHitData = Mux(
        selectInstructionVictim,
        0.U,
        Mux(selectI, iS3SelectedData, dS3SelectedData)
    )
    val selectedHitDirty = Mux(
        selectInstructionVictim,
        false.B,
        Mux(selectI, iS3Hit && Mux1H(iS3Way, iS3DirtyWays.asBools), dS3SelectedDirty)
    )

    when(engineState === engineIdle && (selectI || selectD || selectInstructionVictim)) {
        engineSourceI := selectI || selectInstructionVictim
        engineBackground := selectInstructionVictim
        enginePtw := selectedPtw
        enginePaddr := selectedPaddr
        engineWrite := selectedWrite
        engineUncache := selectedUncache
        engineSize := selectedSize
        engineData := selectedRequestData
        engineMask := selectedMask
        engineVictimValid := selectedVictimValid
        engineVictimLine := selectedVictimPaddr(33, p.offsetBits)
        engineVictimData := selectedVictimData
        engineVictimDirty := selectedVictimDirty
        engineTargetHit := selectedHit && !selectedVictimOnly
        engineTargetWay := selectedWay
        engineTargetDirty := selectedHitDirty
        engineResponseData := selectedHitData
        engineResponseDirty := selectedHit && selectedHitDirty && !selectI && !selectedPtw
        engineResponseError := false.B
        engineVictimWrite := false.B
        engineState := Mux(
            selectedVictimOnly || (!selectedUncache && !selectedPtw && selectedHit && selectedVictimValid),
            engineVictimRead,
            engineMemorySend
        )
        enginePreferI := !selectI
        when(!selectInstructionVictim) {
            when(selectI) {
                iS3Started := true.B
            }.otherwise {
                dS3Started := true.B
            }
        }
    }

    val engineValidWays = Mux(engineSourceI, iValidWays, dValidWays)
    val engineDirtyWays = Mux(engineSourceI, iDirtyWays, dDirtyWays)
    val engineInstructionWays = Mux(engineSourceI, iInstructionWays, dInstructionWays)
    val engineTags = Mux(engineSourceI, iTags, dTags)
    val engineLines = Mux(engineSourceI, iLines, dLines)
    val engineLookupHit = VecInit((0 until p.ways).map { way =>
        engineValidWays(way) && engineTags(way) === tag(engineVictimAddress)
    }).asUInt
    val enginePlru = Mux(engineSourceI, plruTab.rdata(0), plruTab.rdata(1))
    val engineConsumeTarget = engineTargetHit && (!engineSourceI || !engineTargetDirty)

    val engineComplete = WireDefault(false.B)
    val engineCompleteData = WireDefault(engineResponseData)
    val engineCompleteDirty = WireDefault(engineResponseDirty)
    val engineCompleteError = WireDefault(engineResponseError)

    io.memory.req.valid := engineState === engineMemorySend || engineState === engineWritebackSend
    io.memory.req.bits := 0.U.asTypeOf(new L2MemoryRequest(p))
    io.memory.req.bits.paddr := Mux(
        engineState === engineWritebackSend,
        engineWritebackPaddr,
        Mux(engineUncache || enginePtw, enginePaddr, lineAddress(enginePaddr))
    )
    io.memory.req.bits.write := Mux(engineState === engineWritebackSend, true.B, engineWrite)
    io.memory.req.bits.uncache := engineState =/= engineWritebackSend && (engineUncache || enginePtw)
    io.memory.req.bits.size := Mux(engineState === engineWritebackSend, 2.U, engineSize)
    io.memory.req.bits.data := Mux(engineState === engineWritebackSend, engineWritebackData, engineData)
    io.memory.req.bits.mask := Mux(
        engineState === engineWritebackSend,
        255.U,
        engineMask
    )
    io.memory.rsp.ready := engineState === engineMemoryWait || engineState === engineWritebackWait

    switch(engineState) {
        is(engineVictimRead) {
            engineState := engineVictimLookup
        }
        is(engineVictimLookup) {
            assert(PopCount(engineLookupHit) <= 1.U, "L2Cache: multiple victim hits")
            val sameSet = index(engineVictimAddress) === index(enginePaddr)
            val protectedTargetWay = Mux(engineTargetHit && sameSet, engineTargetWay, 0.U)
            val oppositeTargetWaiting = Mux(engineSourceI, dWaiting && dS3Hit, iWaiting && iS3Hit)
            val oppositeTargetAddress = Mux(engineSourceI, dS3.paddr, iS3.paddr)
            val oppositeTargetWay = Mux(engineSourceI, dS3Way, iS3Way)
            val protectedOppositeWay = Mux(
                oppositeTargetWaiting && index(engineVictimAddress) === index(oppositeTargetAddress),
                oppositeTargetWay,
                0.U
            )
            val protectedWays = protectedTargetWay | protectedOppositeWay
            val allWays = ((BigInt(1) << p.ways) - 1).U(p.ways.W)
            val allowedWays = allWays & ~protectedWays
            val invalidWays = allowedWays & ~engineValidWays
            val plruWay = plruVictim(enginePlru)
            val fallbackWay = Mux((plruWay & allowedWays).orR, plruWay, PriorityEncoderOH(allowedWays))
            val instructionCandidates = engineInstructionWays & engineValidWays & allowedWays
            val instructionOccupancy = (engineInstructionWays & engineValidWays).asBools
            val instructionLimit = instructionOccupancy.combinations(p.maxInstructionWays)
                .map(_.reduce(_ && _)).reduce(_ || _)
            val instructionWay = Mux(
                (plruWay & instructionCandidates).orR,
                plruWay,
                PriorityEncoderOH(instructionCandidates)
            )
            val dataCandidates = ~engineInstructionWays & engineValidWays & allowedWays
            val dataWay = Mux(
                (plruWay & dataCandidates).orR,
                plruWay,
                PriorityEncoderOH(dataCandidates)
            )
            val replacementWay = Mux(
                invalidWays.orR,
                PriorityEncoderOH(invalidWays),
                Mux(
                    engineSourceI && instructionLimit && instructionCandidates.orR,
                    instructionWay,
                    Mux(!engineSourceI && dataCandidates.orR, dataWay, fallbackWay)
                )
            )
            val selectedVictimWay = Mux(engineLookupHit.orR, engineLookupHit, replacementWay)
            val preserveDirtyCopy = engineLookupHit.orR && Mux1H(engineLookupHit, engineDirtyWays.asBools) &&
                !engineVictimDirty
            val replaceDirty = !engineLookupHit.orR && Mux1H(replacementWay, engineDirtyWays.asBools) &&
                Mux1H(replacementWay, engineValidWays.asBools)
            engineVictimWay := selectedVictimWay
            engineVictimWrite := !preserveDirtyCopy
            when(engineTargetHit && sameSet) {
                assert(
                    !(selectedVictimWay & engineTargetWay).orR,
                    "L2Cache: victim insertion selected the requested line"
                )
            }
            when(!engineLookupHit.orR && protectedOppositeWay.orR) {
                assert(
                    !(replacementWay & protectedOppositeWay).orR,
                    "L2Cache: victim insertion selected an in-flight opposite-channel hit"
                )
            }
            when(replaceDirty) {
                engineWritebackPaddr := Cat(
                    Mux1H(replacementWay, engineTags),
                    index(engineVictimAddress),
                    0.U(p.offsetBits.W)
                )
                engineWritebackData := Mux1H(replacementWay, engineLines)
                engineState := engineWritebackSend
            }.otherwise {
                engineState := engineVictimInstall
            }
        }
        is(engineWritebackSend) {
            when(io.memory.req.fire) {
                engineState := engineWritebackWait
            }
        }
        is(engineWritebackWait) {
            when(io.memory.rsp.fire) {
                when(io.memory.rsp.bits.error) {
                    engineResponseError := true.B
                    engineState := engineFinish
                }.otherwise {
                    engineState := engineVictimInstall
                }
            }
        }
        is(engineVictimInstall) {
            engineState := engineFinish
        }
        is(engineMemorySend) {
            when(io.memory.req.fire) {
                engineState := engineMemoryWait
            }
        }
        is(engineMemoryWait) {
            when(io.memory.rsp.fire) {
                engineResponseData := io.memory.rsp.bits.data
                engineResponseError := io.memory.rsp.bits.error
                engineResponseDirty := false.B
                when(!io.memory.rsp.bits.error && !engineUncache && !enginePtw && engineVictimValid) {
                    engineState := engineVictimRead
                }.otherwise {
                    engineState := engineFinish
                }
            }
        }
        is(engineFinish) {
            engineComplete := true.B
            engineState := engineIdle
        }
    }

    when(engineComplete) {
        when(!engineBackground) {
            when(engineSourceI) {
                iS3Done := true.B
                iS3Result := engineCompleteData
                iS3Error := engineCompleteError
            }.otherwise {
                dS3Done := true.B
                dS3Result := engineCompleteData
                dS3ResultDirty := engineCompleteDirty
                dS3Error := engineCompleteError
            }
        }
    }

    when(instructionVictimPop) {
        instructionVictimValid := false.B
    }
    when(iS2Advance && iFast && !iS2.ptw && iS2.victimValid) {
        instructionVictimValid := true.B
        instructionVictimLine := iS2.victimLine
        instructionVictimData := iS2.victimData
    }

    // ==================== Array ports and metadata updates ====================
    val engineInstall = engineState === engineVictimInstall
    for (way <- 0 until p.ways) {
        val installWay = engineInstall && engineVictimWrite && engineVictimWay(way)
        val consumeEngineTarget = engineInstall && engineConsumeTarget && engineTargetWay(way)

        tagTab(way).clka := clock
        tagTab(way).addra := Mux(engineVictimPort && engineSourceI, index(engineVictimAddress), index(iReadAddress))
        tagTab(way).ena := Mux(engineVictimPort && engineSourceI, true.B, iReadEnable && !engineArrayWrite)
        tagTab(way).wea := 0.U
        tagTab(way).dina := 0.U
        tagTab(way).addrb := Mux(
            engineVictimPort && !engineSourceI || engineInstall,
            index(engineVictimAddress),
            index(dReadAddress)
        )
        tagTab(way).enb := Mux(
            engineInstall,
            engineVictimWrite,
            Mux(engineVictimPort && !engineSourceI, true.B, dReadEnable)
        )
        tagTab(way).web := installWay.asUInt
        tagTab(way).dinb := tag(engineVictimAddress)

        dataTab(way).clka := clock
        dataTab(way).addra := Mux(engineVictimPort && engineSourceI, index(engineVictimAddress), index(iReadAddress))
        dataTab(way).ena := Mux(engineVictimPort && engineSourceI, true.B, iReadEnable && !engineArrayWrite)
        dataTab(way).wea := 0.U
        dataTab(way).dina := 0.U
        dataTab(way).addrb := Mux(
            engineVictimPort && !engineSourceI || engineInstall,
            index(engineVictimAddress),
            index(dReadAddress)
        )
        dataTab(way).enb := Mux(
            engineInstall,
            engineVictimWrite,
            Mux(engineVictimPort && !engineSourceI, true.B, dReadEnable)
        )
        dataTab(way).web := Fill(p.lineBytes, installWay)
        dataTab(way).dinb := engineVictimData

        validTab(way).raddr(0) := index(iMetaAddress)
        validTab(way).raddr(1) := index(dMetaAddress)
        validTab(way).wen(0) := Mux(engineInstall, installWay, iFastConsume && iHit(way))
        validTab(way).waddr(0) := Mux(engineInstall, index(engineVictimAddress), index(iS2.paddr))
        validTab(way).wdata(0) := engineInstall
        validTab(way).wen(1) := Mux(engineInstall, consumeEngineTarget, dFastConsume && dHit(way))
        validTab(way).waddr(1) := Mux(engineInstall, index(enginePaddr), index(dS2.paddr))
        validTab(way).wdata(1) := false.B

        dirtyTab(way).raddr(0) := index(iMetaAddress)
        dirtyTab(way).raddr(1) := index(dMetaAddress)
        dirtyTab(way).wen(0) := installWay
        dirtyTab(way).waddr(0) := index(engineVictimAddress)
        dirtyTab(way).wdata(0) := engineVictimDirty

        instructionTab(way).raddr(0) := index(iMetaAddress)
        instructionTab(way).raddr(1) := index(dMetaAddress)
        instructionTab(way).wen(0) := installWay
        instructionTab(way).waddr(0) := index(engineVictimAddress)
        instructionTab(way).wdata(0) := engineSourceI
    }
    plruTab.raddr(0) := index(iMetaAddress)
    plruTab.raddr(1) := index(dMetaAddress)
    plruTab.wen(0) := engineInstall
    plruTab.waddr(0) := index(engineVictimAddress)
    plruTab.wdata(0) := plruUpdate(enginePlru, engineVictimWay)

    io.idle := !iS1Valid && !iS2Valid && !iS3Valid &&
        !dS1Valid && !dS2Valid && !dS3Valid && !instructionVictimValid && engineState === engineIdle

    when(iS2Advance) {
        assert(PopCount(iHit) <= 1.U, "L2Cache: multiple ICache hits")
    }
    when(dS2Advance) {
        assert(PopCount(dHit) <= 1.U, "L2Cache: multiple DCache/PTW hits")
    }
    when(io.dcache.req.fire && !io.dcache.req.bits.uncache) {
        assert(!io.dcache.req.bits.write, "L2Cache: cached DCache acquisition cannot be a direct write")
    }
    when(io.icache.request.fire) {
        assert(!io.icache.request.bits.victimValid || !io.icache.request.bits.uncache)
    }

    /* Simulation-only counters stay after the cache datapath and invariants. */
    if (observe) {
        val instructionVisits = RegInit(0.U(64.W))
        val instructionHits = RegInit(0.U(64.W))
        val instructionMisses = RegInit(0.U(64.W))
        val dataVisits = RegInit(0.U(64.W))
        val dataHits = RegInit(0.U(64.W))
        val dataMisses = RegInit(0.U(64.W))
        val instructionVictimInsertions = RegInit(0.U(64.W))
        val dataVictimInsertions = RegInit(0.U(64.W))
        val lowerMemoryReads = RegInit(0.U(64.W))
        val lowerMemoryWrites = RegInit(0.U(64.W))
        val engineBusyCycles = RegInit(0.U(64.W))
        val instructionVisit = iS2Advance && !iS2.ptw && !iS2.uncache
        val dataVisit = dS2Advance && !dS2.ptw && !dS2.uncache && !dS2.victimOnly
        when(instructionVisit) {
            instructionVisits := instructionVisits + 1.U
            when(iHit.orR) { instructionHits := instructionHits + 1.U }
                .otherwise { instructionMisses := instructionMisses + 1.U }
        }
        when(dataVisit) {
            dataVisits := dataVisits + 1.U
            when(dHit.orR) { dataHits := dataHits + 1.U }
                .otherwise { dataMisses := dataMisses + 1.U }
        }
        when(iS2Advance && !iS2.ptw && iS2.victimValid) {
            instructionVictimInsertions := instructionVictimInsertions + 1.U
        }
        when(dS2Advance && !dS2.ptw && dS2.victimValid) {
            dataVictimInsertions := dataVictimInsertions + 1.U
        }
        when(io.memory.req.fire) {
            when(io.memory.req.bits.write) { lowerMemoryWrites := lowerMemoryWrites + 1.U }
                .otherwise { lowerMemoryReads := lowerMemoryReads + 1.U }
        }
        when(engineState =/= engineIdle) { engineBusyCycles := engineBusyCycles + 1.U }
        io.performance.get.instructionVisits := instructionVisits
        io.performance.get.instructionHits := instructionHits
        io.performance.get.instructionMisses := instructionMisses
        io.performance.get.dataVisits := dataVisits
        io.performance.get.dataHits := dataHits
        io.performance.get.dataMisses := dataMisses
        io.performance.get.instructionVictimInsertions := instructionVictimInsertions
        io.performance.get.dataVictimInsertions := dataVictimInsertions
        io.performance.get.lowerMemoryReads := lowerMemoryReads
        io.performance.get.lowerMemoryWrites := lowerMemoryWrites
        io.performance.get.engineBusyCycles := engineBusyCycles
    }
}
