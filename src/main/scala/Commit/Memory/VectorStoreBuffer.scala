import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

class VectorStoreDrain extends Bundle {
    val start = Decoupled(Bool())
    val done = Input(Bool())
}

/** Wide payload side buffer. The original SQ/SB token owns program order;
  * this buffer survives retirement and is released only after its last write.
  */
class VectorStoreBuffer(val p: BackendParams = BackendParams(), observe: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val collect = Flipped(Decoupled(new VectorStoreTask(p)))
        val commit = Input(Vec(3, Valid(UInt(p.sqWidth.W))))
        val drain = Flipped(new VectorStoreDrain)
        val cache = new StoreBufferCacheIO
        val flush = Input(Bool())
        val empty = Output(Bool())
        val active = Output(Bool())
        val loadLine = Input(UInt(26.W))
        val loadTail = Input(UInt(p.sqWidth.W))
        val loadClear = Output(Bool())
        val scalarLoadLine = Input(Vec(2,UInt(26.W)))
        val scalarLoadTail = Input(Vec(2,UInt(p.sqWidth.W)))
        val scalarLoadClear = Output(Vec(2,Bool()))
        val observation = if (observe) Some(Output(new VectorStoreTrace)) else None
    })
    val payload = Reg(Vec(8, new VectorStoreTask(p)))
    val nextLineAddress = Reg(Vec(8, UInt(26.W)))
    val adjacentPair = RegInit(VecInit.fill(8)(false.B))
    val contiguousMasks = Reg(Vec(8, UInt(128.W)))
    val elementAddresses = Mem(256, UInt(32.W))
    val scattered = Reg(Bool())
    val eew = Reg(UInt(2.W))
    val fieldRegisterShift = Reg(UInt(2.W))
    val lastField = Reg(UInt(3.W))
    val position = RegInit(0.U(5.W))
    val elementAddress = Reg(UInt(32.W))
    val nextElementLine = Reg(UInt(26.W))
    val valid = RegInit(false.B)
    val committed = RegInit(false.B)
    val filled = RegInit(false.B)
    val identity = Reg(UInt(p.sqWidth.W))
    val count = Reg(UInt(4.W))
    val nextCollect = Reg(UInt(3.W))
    val group = Reg(UInt(3.W))
    val groupOH = RegInit(1.U(8.W))
    val second = RegInit(false.B)
    val idle :: readElementAddress :: prepare :: response :: done :: Nil = Enum(5)
    val state = RegInit(idle)
    val active = RegInit(false.B)
    val finishNow = WireDefault(false.B)
    val commitHit = io.commit.map(event => event.valid && event.bits === identity).reduce(_ || _)
    // When the active instruction uses at most four groups, its unused upper
    // payload registers queue single- or two-group Stores. No data storage
    // is added. Vector memory issues in program order; SQ/SB still determines
    // retirement and grants each queued instruction its own outward drain.
    val pendingValid = RegInit(VecInit.fill(4)(false.B))
    val pendingCommitted = RegInit(VecInit.fill(4)(false.B))
    val pendingFilled = RegInit(VecInit.fill(4)(false.B))
    val pendingTwo = RegInit(false.B)
    val queueWritePending = RegInit(false.B)
    val queueWriteSlot = Reg(UInt(2.W))
    val queueWriteIdentity = Reg(UInt(p.sqWidth.W))
    val pendingHead = RegInit(0.U(2.W))
    val pendingTail = RegInit(0.U(2.W))
    val pendingCount = RegInit(0.U(3.W))
    val pendingCommitHit = VecInit((0 until 4).map(i => io.commit.map(event =>
        event.valid && event.bits === payload(i + 4).sqIdx).reduce(_ || _)))
    val retained = VecInit((0 until 4).map(i => pendingValid(i) &&
        (pendingCommitted(i) || pendingCommitHit(i))))
    val promote = (!valid || state === done || finishNow) && pendingCount =/= 0.U &&
        pendingFilled(pendingHead) && (!io.flush || retained(pendingHead))
    val incomingSingle = io.collect.bits.index === 0.U && io.collect.bits.last &&
        !io.collect.bits.scattered && !io.collect.bits.addressOnly
    val incomingTwo = io.collect.bits.index===0.U && !io.collect.bits.last &&
        !io.collect.bits.scattered && !io.collect.bits.addressOnly &&
        io.collect.bits.fieldRegisterShift===1.U && io.collect.bits.lastField===0.U
    val queueFollow = queueWritePending && io.collect.bits.sqIdx===queueWriteIdentity &&
        io.collect.bits.index===1.U && io.collect.bits.last && !io.collect.bits.addressOnly
    val queueRoom = pendingCount < Mux(incomingTwo,2.U,4.U) || promote
    val sameLayout = pendingCount===0.U || pendingTwo===incomingTwo
    val queueStart = valid && filled && count<=4.U && (incomingSingle || incomingTwo) &&
        !queueWritePending && sameLayout && queueRoom && io.collect.bits.sqIdx=/=identity &&
        (!(state===done || finishNow) || pendingCount=/=0.U)
    val canQueue = queueStart || queueFollow
    io.collect.ready := !io.flush &&
        ((!valid && pendingCount === 0.U) || !filled && io.collect.bits.sqIdx === identity || canQueue)
    val queueCollect = io.collect.fire && canQueue
    val queueBegin = queueCollect && !queueFollow
    val primaryCollect = io.collect.fire && !canQueue
    val pendingStep = Mux(pendingTwo,2.U(2.W),1.U(2.W))
    def conflicts(slot: Int,line: UInt): Bool = payload(slot).mask.orR &&
        (!PeScratchpadBridge.matches(payload(slot).address.pad(34)) ||
            payload(slot).address(31,6) === line ||
            contiguousMasks(slot)(127,64).orR && nextLineAddress(slot) === line)
    // Compare only the existing eight payload addresses. Scattered, partial
    // collection and non-native Stores stay conservative; ordinary native
    // Loads need neither forwarding data nor a second memory port.
    def older(sqIdx: UInt,tail: UInt,retired: Bool): Bool = {
        val entries = ZirconConfig.CommitParams().sqEntries
        val distance = Mux(tail>=sqIdx,tail- sqIdx,(tail +& (2*entries).U)-sqIdx)
        retired || distance=/=0.U && distance<=entries.U
    }
    def clearFor(line: UInt,tail: UInt): Bool = {
        val primaryConflict = VecInit((0 until 8).map(i => i.U < count && conflicts(i,line))).asUInt.orR
        val pendingConflict = VecInit((0 until 4).map(i => {
            val owner = Mux(pendingTwo,(i & 2).U(2.W),i.U(2.W))
            pendingValid(owner) &&
                older(payload(Cat(1.U(1.W),owner)).sqIdx,tail,
                    pendingCommitted(owner) || pendingCommitHit(owner)) &&
                (!pendingFilled(owner) || conflicts(i+4,line))
        })).asUInt.orR
        // Younger speculative Stores cannot block an older Load: their ROB
        // retirement may itself depend on that Load completing. Retired tokens
        // remain older even when their SQ ring identities have been recycled.
        (!valid || !older(identity,tail,committed || commitHit) ||
            filled && !scattered && !primaryConflict) && !pendingConflict
    }
    io.loadClear := clearFor(io.loadLine,io.loadTail)
    for(port <- 0 until 2) { io.scalarLoadClear(port) := clearFor(io.scalarLoadLine(port),io.scalarLoadTail(port)) }
    io.drain.start.ready := valid && committed && filled && state === idle ||
        finishNow && pendingCount =/= 0.U && pendingValid(pendingHead) && pendingFilled(pendingHead) &&
            (pendingCommitted(pendingHead) || pendingCommitHit(pendingHead))
    io.drain.done := state === done || finishNow
    io.empty := !valid && pendingCount===0.U
    io.active := active
    io.observation.foreach { trace =>
        trace.count := count
        trace.addresses := VecInit(payload.map(_.address)).asUInt
        trace.data := VecInit(payload.map(_.data)).asUInt
        trace.masks := VecInit(payload.map(_.mask)).asUInt
        trace.scattered := scattered
        trace.eew := eew
        trace.fieldRegisterShift := fieldRegisterShift
        trace.lastField := lastField
        for (group <- 0 until 8) {
            trace.elementAddresses(group) := VecInit((0 until 32).map(index =>
                elementAddresses(group * 32 + index))).asUInt
        }
    }
    io.cache.response.ready := state === response
    val item = Mux1H(groupOH, payload)
    val address = Mux(scattered, elementAddress, item.address)
    // Two adjacent register groups already occupy one cache line. Reuse the
    // collected payload to issue their combined data and byte mask together.
    val nextItem = Mux1H(Cat(groupOH(6,0),groupOH(7)),payload)
    val pair = !scattered && !second && (groupOH & adjacentPair.asUInt).orR
    val step = Mux(pair,2.U(2.W),1.U(2.W))
    val elementBytes = (1.U(3.W) << eew)(2, 0)
    val dataMask = Mux(eew === 0.U, 255.U(32.W), Mux(eew === 1.U, 65535.U(32.W), "hffffffff".U))
    val narrowData = (item.data >> (position << 3))(31, 0) & dataMask
    val narrowMask = (item.mask >> position)(3, 0) & Mux(eew === 0.U, 1.U, Mux(eew === 1.U, 3.U, 15.U))
    val shiftedData = (Mux(scattered, narrowData.pad(256), item.data).pad(1024) << (address(5, 0) << 3))(1023, 0)
    val scatteredMask = (narrowMask.pad(128) << elementAddress(5, 0))(127, 0)
    val shiftedMask = Mux(scattered, scatteredMask, Mux1H(groupOH, contiguousMasks))
    val lineMask = Mux(pair,Cat(nextItem.mask,item.mask),Mux(second, shiftedMask(127, 64), shiftedMask(63, 0)))
    val hasSecond = shiftedMask(127, 64).orR && !second
    val last = group +& step === count
    // The final accepted cache acknowledgement is already the architectural
    // drain boundary. Return its SB token at that edge, allowing a queued
    // instruction to acquire the drain without done/idle/start bubbles.
    finishNow := state === response && !scattered && !hasSecond && last && io.cache.response.fire
    val fieldStride = (1.U(4.W) << fieldRegisterShift)(3, 0)
    val withinField = group & Cat(fieldRegisterShift === 3.U,
        fieldRegisterShift >= 2.U, fieldRegisterShift >= 1.U)
    val currentField = group >> fieldRegisterShift
    def advance(): Unit = {
        when(hasSecond) {
            second := true.B
            state := prepare
        }.otherwise {
            second := false.B
            when(scattered) {
                // Segment payload is stored by field, but outward writes visit
                // all fields of one element before moving to the next element.
                // This preserves ordered indexed stores even when addresses alias.
                val lastPosition = position +& elementBytes === 32.U
                val finalField = currentField === lastField
                val nextGroup = Mux(!finalField, group + fieldStride,
                    Mux(lastPosition, withinField + 1.U, withinField))(2, 0)
                group := nextGroup
                groupOH := UIntToOH(nextGroup, 8)
                when(finalField) {
                    position := Mux(lastPosition, 0.U, position + elementBytes)
                }
                state := readElementAddress
                when(finalField && lastPosition && withinField +& 1.U === fieldStride) {
                    state := done
                    active := false.B
                }
            }.otherwise {
                position := 0.U
                group := group + step
                groupOH := Mux(pair,Cat(groupOH(5,0),groupOH(7,6)),Cat(groupOH(6,0),groupOH(7)))
                state := Mux(last, done, Mux(scattered, readElementAddress, prepare))
                when(last) { active := false.B }
            }
        }
    }
    when(primaryCollect) {
        when(!valid) {
            assert(io.collect.bits.index === 0.U)
            identity := io.collect.bits.sqIdx
            valid := true.B
            committed := false.B
            scattered := io.collect.bits.scattered
            eew := io.collect.bits.eew
            fieldRegisterShift := io.collect.bits.fieldRegisterShift
            lastField := io.collect.bits.lastField
            adjacentPair.foreach(_ := false.B)
        }.otherwise {
            assert(io.collect.bits.index === nextCollect)
            assert(!committed)
        }
        when(io.collect.bits.addressOnly) {
            elementAddresses(Cat(io.collect.bits.index, io.collect.bits.element)) := io.collect.bits.address
            nextCollect := io.collect.bits.index
        }.otherwise {
            payload(io.collect.bits.index) := io.collect.bits
            contiguousMasks(io.collect.bits.index) :=
                (io.collect.bits.mask.pad(128) << io.collect.bits.address(5, 0))(127, 0)
            nextLineAddress(io.collect.bits.index) := BLevelPAdder32.sum(
                Cat(io.collect.bits.address(31, 6), 0.U(6.W)), 64.U(32.W), 0.U(1.W))(31, 6)
            // A successor is known while its payload arrives. No address
            // comparison or increment remains after the drain group mux.
            for (previous <- 0 until 7) {
                when(io.collect.bits.index === (previous + 1).U) {
                    val aligned = !payload(previous).address(5, 0).orR
                    val adjacent = io.collect.bits.address ===
                        Cat(payload(previous).address(31, 6), 32.U(6.W))
                    adjacentPair(previous) := aligned && adjacent
                }
            }
            nextCollect := io.collect.bits.index + 1.U
        }
        when(io.collect.bits.last) {
            count := io.collect.bits.index +& 1.U
            filled := true.B
        }
    }
    when(valid && commitHit) {
        assert(filled)
        committed := true.B
    }
    when(io.drain.start.fire) {
        active := true.B
        group := 0.U
        groupOH := 1.U
        position := 0.U
        second := false.B
        state := Mux(scattered, readElementAddress, prepare)
    }
    when(state === readElementAddress) {
        val selectedAddress = elementAddresses(Cat(group, position))
        elementAddress := selectedAddress
        // Prepare both line addresses at the existing element-read boundary.
        // The second-fragment selector must not precede a cache-address adder.
        nextElementLine := selectedAddress(31, 6) + 1.U
        state := prepare
    }
    io.cache.request.bits := 0.U.asTypeOf(new DStoreRequest)
    io.cache.request.bits.wide := true.B
    val contiguousLine = Mux(second, Mux1H(groupOH, nextLineAddress), item.address(31, 6))
    val scatteredAddress = Cat(Mux(second, nextElementLine, elementAddress(31, 6)), 0.U(6.W))
    io.cache.request.bits.paddr := Mux(scattered, scatteredAddress,
        Cat(contiguousLine, 0.U(6.W))).pad(34)
    io.cache.request.bits.line := Mux(pair,Cat(nextItem.data,item.data),Mux(second, shiftedData(1023, 512), shiftedData(511, 0)))
    io.cache.request.bits.lineMask := lineMask
    io.cache.request.bits.size := 2.U
    io.cache.request.valid := state === prepare && lineMask.orR
    // A native, aligned register word needs only a half-line selection. Start
    // it on the SB grant edge, including a queued successor of the returning
    // token. The existing payload remains stable if the cache applies pressure;
    // no additional line buffer or speculative outward write is introduced.
    val startItem = Mux(finishNow,payload(Cat(1.U(1.W),pendingHead)),payload(0))
    val startSingle = Mux(finishNow,!pendingTwo,count===1.U && !scattered)
    val immediateStart = io.drain.start.fire && startSingle &&
        PeScratchpadBridge.matches(startItem.address.pad(34)) &&
        !startItem.address(4,0).orR && startItem.mask.andR
    when(immediateStart) {
        io.cache.request.valid := true.B
        io.cache.request.bits.paddr := Cat(startItem.address(31,6),0.U(6.W)).pad(34)
        io.cache.request.bits.line := Mux(startItem.address(5),
            Cat(startItem.data,0.U(256.W)),Cat(0.U(256.W),startItem.data))
        io.cache.request.bits.lineMask := Mux(startItem.address(5),
            Cat(startItem.mask,0.U(32.W)),Cat(0.U(32.W),startItem.mask))
    }
    when(state === prepare && !lineMask.orR) { advance() }
    when(io.cache.request.fire) { state := response }
    when(io.cache.response.fire) {
        assert(io.cache.response.bits.exception === 0.U)
        advance()
    }
    when(state === done || finishNow) {
        valid := false.B
        committed := false.B
        filled := false.B
        state := idle
        active := false.B
    }
    when(promote) {
        val slot = Cat(1.U(1.W), pendingHead)
        payload(0) := payload(slot)
        contiguousMasks(0) := contiguousMasks(slot)
        nextLineAddress(0) := nextLineAddress(slot)
        when(pendingTwo) {
            val nextSlot=Cat(1.U(1.W),(pendingHead+1.U)(1,0))
            payload(1) := payload(nextSlot)
            contiguousMasks(1) := contiguousMasks(nextSlot)
            nextLineAddress(1) := nextLineAddress(nextSlot)
            adjacentPair(0) := !payload(slot).address(5,0).orR &&
                payload(nextSlot).address===Cat(payload(slot).address(31,6),32.U(6.W))
        }.otherwise { adjacentPair(0) := false.B }
        identity := payload(slot).sqIdx
        count := Mux(pendingTwo,2.U,1.U)
        nextCollect := Mux(pendingTwo,2.U,1.U)
        valid := true.B
        committed := pendingCommitted(pendingHead) || pendingCommitHit(pendingHead)
        filled := true.B
        scattered := false.B
        eew := payload(slot).eew
        fieldRegisterShift := payload(slot).fieldRegisterShift
        lastField := payload(slot).lastField
        pendingHead := pendingHead + pendingStep
        when(finishNow && io.drain.start.fire) {
            state := prepare
            active := true.B
            group := 0.U
            groupOH := 1.U
            position := 0.U
            second := false.B
        }
    }
    for (i <- 0 until 4) {
        when(pendingValid(i) && pendingCommitHit(i)) { pendingCommitted(i) := true.B }
        when(promote && pendingHead === i.U) { pendingValid(i) := false.B }
    }
    when(queueCollect) {
        // Each pending instruction owns one or two of the four upper rows.
        // The second group completes that reservation before promotion; no
        // additional payload RAM, SQ token or speculative cache write exists.
        val firstSlot=Mux(pendingCount===0.U && incomingTwo,
            Cat(pendingTail(1),0.U(1.W)),pendingTail)
        val row=Mux(queueFollow,queueWriteSlot,firstSlot)
        val slot=Cat(1.U(1.W),row)
        payload(slot) := io.collect.bits
        contiguousMasks(slot) :=
            (io.collect.bits.mask.pad(128) << io.collect.bits.address(5, 0))(127, 0)
        nextLineAddress(slot) := BLevelPAdder32.sum(
            Cat(io.collect.bits.address(31,6),0.U(6.W)),64.U(32.W),0.U)(31,6)
        when(queueBegin) {
            pendingValid(row) := true.B
            pendingFilled(row) := incomingSingle
            pendingCommitted(row) := false.B
            pendingTail := row+Mux(incomingTwo,2.U,1.U)
            when(pendingCount===0.U) { pendingHead := row; pendingTwo := incomingTwo }
            queueWritePending := incomingTwo
            queueWriteSlot := row+1.U
            queueWriteIdentity := io.collect.bits.sqIdx
        }.otherwise {
            pendingFilled((row-1.U)(1,0)) := true.B
            queueWritePending := false.B
        }
    }
    when(queueBegin =/= promote) {
        pendingCount := Mux(queueBegin,pendingCount+1.U,pendingCount-1.U)
    }
    // Retired stores retain ownership across recovery, including a retirement
    // on the recovery edge. No cache request exists before SB accepts the token.
    when(io.flush && valid && !committed && !commitHit) {
        assert(state === idle)
        valid := false.B
        filled := false.B
    }
    when(io.flush) {
        // Retirement is an in-order prefix. Recovery discards only the
        // speculative suffix, including a queued Store collected before its
        // retirement; same-edge retirements retain their data and identity.
        val kept = PopCount(retained)
        pendingCount := kept - promote.asUInt
        pendingTail := pendingHead + Mux(pendingTwo,kept<<1,kept)
        queueWritePending := false.B
        for (i <- 0 until 4) {
            pendingValid(i) := retained(i) && !(promote && pendingHead === i.U)
        }
    }
    when(immediateStart) {
        // This assignment follows promotion and the old acknowledgement's
        // state updates. A blocked request resumes through the ordinary path.
        state := Mux(io.cache.request.fire,response,prepare)
        active := true.B
        group := 0.U
        groupOH := 1.U
        position := 0.U
        second := false.B
    }
    assert(pendingCount === PopCount(pendingValid))
    when(pendingCount =/= 0.U) { assert(!valid || filled && count<=4.U) }
    when(queueWritePending) { assert(pendingTwo && pendingCount=/=0.U) }
    when(state =/= idle) {
        assert(valid && committed && filled)
        assert(groupOH === UIntToOH(group, 8))
    }
    // The done cycle returns the token to SB, which may immediately offer its
    // next scalar request. Payload ownership has already ended at that edge.
    assert(active === (state =/= idle && state =/= done))
}
