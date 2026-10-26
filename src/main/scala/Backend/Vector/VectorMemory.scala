import chisel3._
import chisel3.util._
import ZirconConfig.{BackendParams, DCacheParams, VectorParams}

/** Vector task engine using the existing DCache line pipeline and MSHR.
  * Store collection finishes before ROB completion; ordered SQ drain owns every
  * outward write. A canceled Load keeps its accepted request until the response.
  */
class VectorMemory(val p: BackendParams = BackendParams(), val cache: DCacheParams = DCacheParams()) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorInstruction(p)))
        val read = Flipped(Vec(3, new VectorRegisterRead(VectorParams())))
        val scalarAddress = Output(UInt(p.tagWidth.W))
        val scalarData = Input(UInt(32.W))
        val scalarReadRequest = Output(Bool())
        val readRequest = Output(Bool())
        val readAvailable = Input(Bool())
        val request = Decoupled(new DLoadRequest(cache))
        val response = Flipped(Valid(new DLoadResponse(cache)))
        val cachePending = Input(Bool())
        val ordering = new VectorLoadOrdering(p)
        val store = Decoupled(new VectorStoreTask(p))
        val writeback = Output(Valid(new VectorTaskWriteback))
        val completion = Decoupled(new VectorCompletion(p))
        val flush = Input(Bool())
        val idle = Output(Bool())
    })
    val legacyIn = Wire(Decoupled(new VectorInstruction(p)))
    val idle :: operands :: strideOperand :: collect :: groupAddress :: gather :: indexOperand :: indexAddress :: checkAddress :: storeAddress :: request :: response :: merge :: writeback :: complete :: streamLoad :: streamDrain :: Nil = Enum(17)
    val state = RegInit(idle)
    val nextState = WireDefault(state)
    state := nextState
    val instruction = Reg(new VectorInstruction(p))
    val base = Reg(UInt(32.W))
    val elementAddress = Reg(UInt(32.W))
    val indexOffset = Reg(UInt(32.W))
    val step = Reg(UInt(32.W))
    val groupStep = Reg(UInt(32.W))
    val groupOffset = Reg(UInt(32.W))
    val position = Reg(UInt(5.W))
    val cachedLine = Reg(UInt(512.W))
    val cachedAddress = Reg(UInt(26.W))
    val cachedValid = RegInit(false.B)
    val mask = Reg(UInt(256.W))
    val group = RegInit(0.U(3.W))
    val groupOH = RegInit(1.U(8.W))
    val sourceReadAddress = RegInit(0.U(6.W))
    val oldReadAddress = RegInit(0.U(6.W))
    val sourceReadSelection = RegInit(1.U(64.W))
    val oldReadSelection = RegInit(1.U(64.W))
    val data = Reg(UInt(256.W))
    val byteMask = Reg(UInt(32.W))
    val address = Reg(UInt(32.W))
    val first = Reg(UInt(512.W))
    val second = RegInit(false.B)
    val discard = RegInit(false.B)
    // A complete unit-stride Store can collect its first register alongside
    // the scalar base. Reuse the ordinary SQ payload register; ordering and
    // outward memory writes remain the responsibility of the existing SQ.
    val fullStore = RegInit(false.B)
    def fullWordStore(task: VectorInstruction): Bool = {
        val whole = task.scalar.inst(27, 26) === 0.U && task.scalar.inst(24, 20) === 8.U
        val words = task.scalar.inst(31, 26) === 0.U && task.scalar.inst(24, 20) === 0.U &&
            task.vector.state.vtype(5, 3) === 2.U &&
            task.vector.state.vl === (task.vector.groups.sourceCount(0).pad(32) << 3)
        task.vector.control.execution === VectorExecution.Store.U &&
            task.scalar.inst(14, 12) === 6.U && (whole || words) &&
            !task.vector.groups.mask && task.vector.state.vstart === 0.U
    }
    // Full, aligned register groups need no old-destination collection. The
    // original cache slot travels with each task through hits, misses and retries.
    // One architecture instruction retains ownership until every group returns.
    require(cache.slotWidth >= 3)
    val streamRequestValid = RegInit(false.B)
    val streamRequestAddress = Reg(UInt(32.W))
    val streamRequestGroup = Reg(UInt(3.W))
    val streamReplay = RegInit(false.B)
    val streamExpected = Reg(UInt(8.W))
    val streamOutstanding = RegInit(0.U(8.W))
    val streamRetry = RegInit(0.U(8.W))
    val streamDone = RegInit(0.U(8.W))
    val streamWriteValid = RegInit(false.B)
    val streamWrite = Reg(new VectorTaskWriteback)
    // Reserve a response slot before issuing a line. Responses cannot stall;
    // each slot retains both halves until the single Load write port drains it.
    val streamLines = Module(new Queue(new Bundle {
        val line = UInt(512.W)
        val group = UInt(3.W)
        val context = UInt(2.W)
        val ticket = UInt(2.W)
    }, 4, flow = true, hasFlush = true))
    streamLines.io.flush.get := io.flush
    val streamCredits = RegInit(0.U(3.W))
    val streamHigh = RegInit(false.B)
    streamLines.io.enq.valid := state === streamLoad && io.response.valid && !io.response.bits.retry && !io.flush
    streamLines.io.enq.bits.line := io.response.bits.line
    streamLines.io.enq.bits.group := io.response.bits.slot(2, 0)
    streamLines.io.enq.bits.context := 0.U
    streamLines.io.enq.bits.ticket := 0.U
    streamLines.io.deq.ready := false.B
    val metadata = instruction.vector
    val store = metadata.control.execution === VectorExecution.Store.U
    val count = Mux(store, metadata.groups.sourceCount(0), metadata.destination.count)
    val last = group +& 1.U === count
    val decodedIndexWidth = Mux(instruction.scalar.inst(14, 12) === 0.U, 0.U,
        Mux(instruction.scalar.inst(14, 12) === 5.U, 1.U, 2.U))
    val indexWidth = Reg(UInt(2.W))
    val indexed = Reg(Bool())
    val eew = Reg(UInt(2.W))
    val whole = instruction.scalar.inst(27, 26) === 0.U && instruction.scalar.inst(24, 20) === 8.U
    val maskMemory = instruction.scalar.inst(27, 26) === 0.U && instruction.scalar.inst(24, 20) === 11.U
    // VLMAX is at most 256 and vstart is WARL to eight bits. Resolve whole and
    // mask transfer lengths at the existing scalar-operand boundary, rather
    // than repeating a 32-bit add/shift before every byte's policy comparison.
    val transferVl = Reg(UInt(9.W))
    val startElement = metadata.state.vstart(7, 0)
    val strided = instruction.scalar.inst(27, 26) === 2.U
    val fields = Reg(UInt(4.W))
    val elementAccess = Reg(Bool())
    val fullGroupLoad = !store && !elementAccess && !metadata.groups.mask &&
        metadata.state.vstart === 0.U && base(4, 0) === 0.U &&
        transferVl === ((count.pad(32) << 5) >> eew)
    val decodedEew = Mux(instruction.scalar.inst(26), metadata.state.vtype(5, 3), decodedIndexWidth)
    val exponent = metadata.state.vtype(2, 0).asSInt.pad(5) + decodedEew.zext.pad(5) -
        metadata.state.vtype(5, 3).zext.pad(5)
    val registerShift = Reg(UInt(2.W))
    val withinField = group & Cat(registerShift === 3.U, registerShift >= 2.U, registerShift >= 1.U)
    val field = group >> registerShift
    val registerElement = Mux(eew === 0.U, Cat(withinField, position),
        Mux(eew === 1.U, Cat(withinField, position(4, 1)), Cat(withinField, position(4, 2))))
    val indexGroup = Mux(indexWidth === 0.U, registerElement(7, 5),
        Mux(indexWidth === 1.U, registerElement(6, 4), registerElement(5, 3)))
    val indexReadAddress = RegInit(0.U(6.W))
    val indexReadSelection = RegInit(1.U(64.W))
    val indexLane = Mux(indexWidth === 0.U, registerElement(4, 2),
        Mux(indexWidth === 1.U, registerElement(3, 1), registerElement(2, 0)))
    val indexShift = Mux(indexWidth === 0.U, Cat(registerElement(1, 0), 0.U(3.W)),
        Mux(indexWidth === 1.U, Cat(registerElement(0), 0.U(4.W)), 0.U(5.W)))
    val elementBytes = (1.U(3.W) << eew)(2, 0)
    val nextPosition = position +& elementBytes
    val lastElement = nextPosition === 32.U
    val finalFullStore = state === writeback && fullStore && last
    legacyIn.ready := (state === idle ||
        finalFullStore && io.store.ready && io.completion.ready) && !io.flush
    io.idle := state === idle
    io.scalarReadRequest := RegNext(nextState === operands || nextState === strideOperand, false.B)
    // Scalar base/stride reads do not occupy the vector RF. Complete unmasked
    // Loads also need neither mask nor old-destination data at collection.
    val operandVl = Mux(whole, ((count.pad(9) << 5) >> decodedEew)(8, 0),
        Mux(maskMemory, (metadata.state.vl(8, 0) +& 7.U) >> 3, metadata.state.vl(8, 0)))
    val operandFullGroupLoad = !store && !strided && !instruction.scalar.inst(26) &&
        (whole || !instruction.scalar.inst(31, 29).orR) && !metadata.groups.mask &&
        metadata.state.vstart === 0.U && io.scalarData(4, 0) === 0.U &&
        operandVl === ((count.pad(9) << 5) >> decodedEew)
    // Resolve next-cycle RF ownership at the preceding edge. The full-group
    // predicate may depend on scalar data, but never enters the RF read mux.
    val nextReadRequest = nextState === operands &&
        Mux(legacyIn.fire, legacyIn.bits.vector.groups.mask || fullWordStore(legacyIn.bits),
            metadata.groups.mask || fullWordStore(instruction)) ||
        nextState === collect && Mux(state === operands, !operandFullGroupLoad, !fullGroupLoad) ||
        nextState === indexOperand
    io.readRequest := RegNext(nextReadRequest && !io.flush, false.B)
    io.scalarAddress := Mux(state === strideOperand, instruction.scalar.prs(1), instruction.scalar.prs(0))
    io.read(0).address := sourceReadAddress
    io.read(1).address := indexReadAddress
    io.read(2).address := oldReadAddress
    io.read(0).oneHot := sourceReadSelection
    io.read(1).oneHot := indexReadSelection
    io.read(2).oneHot := oldReadSelection
    io.ordering.tail := instruction.scalar.sqTail
    io.request.valid := state === request && io.ordering.clear && !io.flush
    io.request.bits := 0.U.asTypeOf(new DLoadRequest(cache))
    io.request.bits.wide := true.B
    io.request.bits.vaddr := BLevelPAdder32.sum(Cat(address(31, 6), 0.U(6.W)),
        Cat(0.U(25.W), second, 0.U(6.W)), 0.U(1.W))
    io.request.bits.paddr := io.request.bits.vaddr
    io.request.bits.mtype := 2.U
    when(state === streamLoad) {
        io.request.valid := streamRequestValid && streamCredits < 4.U && io.ordering.clear && !io.flush
        io.request.bits.vaddr := Cat(streamRequestAddress(31, 6), 0.U(6.W))
        io.request.bits.paddr := io.request.bits.vaddr
        io.request.bits.slot := streamRequestGroup
    }
    io.store.valid := (state === writeback || state === storeAddress) && store && !io.flush
    io.store.bits.sqIdx := instruction.scalar.sqIdx
    io.store.bits.robIdx := instruction.scalar.robIdx
    io.store.bits.index := group
    io.store.bits.last := last && state === writeback
    io.store.bits.address := address
    io.store.bits.data := data
    io.store.bits.mask := byteMask
    io.store.bits.scattered := elementAccess
    io.store.bits.addressOnly := state === storeAddress
    io.store.bits.element := position
    io.store.bits.eew := eew
    io.store.bits.fieldRegisterShift := registerShift
    io.store.bits.lastField := Mux(whole, 0.U, instruction.scalar.inst(31, 29))
    io.writeback.valid := state === writeback && !store && !io.flush
    io.writeback.bits.address := metadata.destination.physical(group)
    io.writeback.bits.data := data.asTypeOf(Vec(8, UInt(32.W)))
    io.writeback.bits.bytes := VecInit.fill(8)(15.U(4.W))
    io.writeback.bits.ready := true.B
    when(streamWriteValid) {
        io.writeback.valid := !io.flush
        io.writeback.bits := streamWrite
    }
    // Completion may accompany the last accepted SQ group. If ROB completion
    // stalls, complete retains the tag without submitting the Store twice.
    io.completion.valid := (state === complete || finalFullStore && io.store.ready) && !io.flush
    io.completion.bits := 0.U.asTypeOf(new VectorCompletion(p))
    io.completion.bits.robIdx := instruction.scalar.robIdx

    val old = io.read(2).data.asUInt
    val oldBytes = old.asTypeOf(Vec(32, UInt(8.W)))
    val sourceBytes = io.read(0).data.asUInt.asTypeOf(Vec(32, UInt(8.W)))
    val initialized = Wire(Vec(32, UInt(8.W)))
    val activeBytes = Wire(Vec(32, Bool()))
    for (byte <- 0 until 32) {
        val element = ((Cat(Mux(elementAccess, withinField, group), 0.U(5.W)) + byte.U) >> eew)(7, 0)
        val enabled = !metadata.groups.mask || mask(element)
        val active = element >= startElement && element < transferVl && enabled
        val fill = startElement < transferVl && element >= startElement && !whole &&
            (element >= transferVl && (maskMemory || metadata.state.vtype(6)) ||
                element < transferVl && !enabled && metadata.state.vtype(7))
        initialized(byte) := Mux(store, sourceBytes(byte),
            Mux(fill || !metadata.groups.sourceCount(2).orR, 255.U, oldBytes(byte)))
        activeBytes(byte) := active
    }
    val crossesLine = address(5, 0) > 32.U
    val joined = Cat(Mux(second, io.response.bits.line, 0.U(512.W)),
        Mux(second, first, io.response.bits.line))
    val loaded = (joined >> (address(5, 0) << 3))(255, 0).asTypeOf(Vec(32, UInt(8.W)))
    val heldBytes = data.asTypeOf(Vec(32, UInt(8.W)))
    streamWriteValid := false.B
    val streamReturned = state === streamLoad && io.response.valid
    val streamRetried = streamReturned && io.response.bits.retry
    when(io.flush) {
        fullStore := false.B
        streamCredits := 0.U
        streamHigh := false.B
    }.otherwise {
        when(state === streamLoad) {
            streamCredits := streamCredits + io.request.fire.asUInt - streamRetried.asUInt - streamLines.io.deq.fire.asUInt
        }
    }
    when(io.flush) {
        streamRequestValid := false.B
        when(state === response) { discard := true.B }
        .elsewhen(state === streamLoad || state === streamDrain) {
            nextState := Mux(io.cachePending, streamDrain, idle)
        }.otherwise { nextState := idle }
    }.otherwise {
        switch(state) {
            is(operands) {
                when(io.readAvailable) {
                    indexWidth := decodedIndexWidth
                    indexed := instruction.scalar.inst(26)
                    elementAccess := strided || instruction.scalar.inst(26) ||
                        !whole && instruction.scalar.inst(31, 29).orR
                    eew := decodedEew
                    transferVl := operandVl
                    registerShift := Mux(exponent > 0.S, exponent.asUInt(1, 0), 0.U)
                    fields := instruction.scalar.inst(31, 29) +& 1.U
                    base := io.scalarData
                    step := (instruction.scalar.inst(31, 29) +& 1.U) << decodedEew
                    // One register covers 32 bytes per field, independently of
                    // EEW. Capture strided groups at the scalar-operand edge.
                    groupStep := Cat(instruction.scalar.inst(31, 29) +& 1.U, 0.U(5.W))
                    mask := io.read(0).data.asUInt
                    sourceReadAddress := metadata.source(0)(0)
                    sourceReadSelection := UIntToOH(metadata.source(0)(0), 64)
                    nextState := Mux(strided, strideOperand, collect)
                    when(fullWordStore(instruction) && io.scalarData(4, 0) === 0.U) {
                        data := io.read(0).data.asUInt
                        byteMask := Fill(32, 1.U(1.W))
                        address := io.scalarData
                        second := false.B
                        position := 0.U
                        fullStore := true.B
                        nextState := writeback
                    }
                }
            }
            is(strideOperand) { when(io.readAvailable) {
                step := io.scalarData
                groupStep := (io.scalarData << Mux(eew === 0.U, 5.U, Mux(eew === 1.U, 4.U, 3.U)))(31, 0)
                nextState := collect
            } }
            is(collect) {
                when(io.readAvailable) {
                    data := initialized.asUInt
                    byteMask := activeBytes.asUInt
                    address := base + Cat(group, 0.U(5.W))
                    second := false.B
                    position := 0.U
                    // collect already precedes groupAddress for every element
                    // task. Resolve its small multiply at this existing edge.
                    val partial0 = Mux(withinField(0), groupStep, 0.U(32.W))
                    val partial1 = Mux(withinField(1), (groupStep << 1)(31, 0), 0.U(32.W))
                    val partial2 = Mux(withinField(2), (groupStep << 2)(31, 0), 0.U(32.W))
                    val productSum = partial0 ^ partial1 ^ partial2
                    val productCarry = (((partial0 & partial1) |
                        ((partial0 ^ partial1) & partial2)) << 1)(31, 0)
                    groupOffset := BLevelPAdder32.sum(productSum, productCarry, 0.U)
                    when(fullGroupLoad) {
                        streamRequestValid := true.B
                        streamRequestAddress := base
                        streamRequestGroup := 0.U
                        streamReplay := false.B
                        streamExpected := ((1.U(9.W) << count) - 1.U)(7, 0)
                        streamOutstanding := 0.U
                        streamRetry := 0.U
                        streamDone := 0.U
                        nextState := streamLoad
                    }.otherwise {
                        nextState := Mux(elementAccess, groupAddress, Mux(store || !activeBytes.asUInt.orR, writeback, request))
                    }
                }
            }
            is(groupAddress) {
                // The first element is a multiple of 8, 16 or 32; this is a
                // shift by SEW and a three-bit register index, not a 32x32 MUL.
                val fieldOffset = (field << eew).pad(32)
                val sum = base ^ groupOffset ^ fieldOffset
                val carry = (((base & groupOffset) | ((base ^ groupOffset) & fieldOffset)) << 1)(31, 0)
                elementAddress := BLevelPAdder32.sum(sum, carry, 0.U)
                nextState := gather
            }
            is(gather) {
                address := elementAddress
                when(!store && !byteMask(position)) {
                    position := nextPosition(4, 0)
                    elementAddress := BLevelPAdder32.sum(elementAddress, step, 0.U)
                    nextState := Mux(lastElement, writeback, gather)
                }.elsewhen(indexed) {
                    indexReadAddress := metadata.source(1)(indexGroup)
                    indexReadSelection := UIntToOH(metadata.source(1)(indexGroup), 64)
                    nextState := indexOperand
                }.elsewhen(store) { nextState := storeAddress }
                .elsewhen(cachedValid && cachedAddress === elementAddress(31, 6)) { nextState := merge }
                .otherwise { nextState := request }
            }
            is(indexOperand) { when(io.readAvailable) {
                val value = (io.read(1).data(indexLane) >> indexShift)(31, 0)
                indexOffset := Mux(indexWidth === 0.U, value & 255.U,
                    Mux(indexWidth === 1.U, value & 65535.U, value))
                nextState := indexAddress
            } }
            is(indexAddress) {
                val fieldOffset = (field << eew).pad(32)
                val sum = base ^ indexOffset ^ fieldOffset
                val carry = (((base & indexOffset) | ((base ^ indexOffset) & fieldOffset)) << 1)(31, 0)
                when(indexed) { address := BLevelPAdder32.sum(sum, carry, 0.U) }
                nextState := checkAddress
            }
            is(checkAddress) {
                nextState := Mux(store, storeAddress,
                    Mux(cachedValid && cachedAddress === address(31, 6), merge, request))
            }
            is(storeAddress) { when(io.store.fire) {
                position := nextPosition(4, 0)
                elementAddress := BLevelPAdder32.sum(elementAddress, step, 0.U)
                nextState := Mux(lastElement, writeback, gather)
            } }
            is(merge) {
                val fetched = (cachedLine >> (address(5, 0) << 3))(31, 0)
                val positioned = (fetched.pad(256) << (position << 3))(255, 0)
                val lowMask = Mux(eew === 0.U, 255.U(32.W), Mux(eew === 1.U, 65535.U(32.W), "hffffffff".U))
                val positionedMask = (lowMask.pad(256) << (position << 3))(255, 0)
                data := (data & ~positionedMask) | (positioned & positionedMask)
                position := nextPosition(4, 0)
                elementAddress := BLevelPAdder32.sum(elementAddress, step, 0.U)
                nextState := Mux(lastElement, writeback, gather)
            }
            is(request) { when(io.request.fire) { nextState := response } }
            is(writeback) {
                when(!store || io.store.fire) {
                    group := group + 1.U
                    groupOH := Cat(groupOH(6, 0), groupOH(7))
                    val following = group + 1.U
                    sourceReadAddress := metadata.source(0)(following)
                    sourceReadSelection := UIntToOH(metadata.source(0)(following), 64)
                    oldReadAddress := metadata.source(2)(following)
                    oldReadSelection := UIntToOH(metadata.source(2)(following), 64)
                    nextState := Mux(last,
                        Mux(fullStore && io.completion.ready, idle, complete), collect)
                }
            }
            is(complete) { when(io.completion.fire) { nextState := idle } }
            is(streamLoad) {
                val issued = Mux(io.request.fire, UIntToOH(streamRequestGroup, 8), 0.U)
                val returned = Mux(io.response.valid, UIntToOH(io.response.bits.slot(2, 0), 8), 0.U)
                val retry = Mux(io.response.bits.retry, returned, 0.U)
                def pair(first: UInt): Bool = !(base(5) ^ first(0)) && first +& 1.U < count
                val returnedGroup = io.response.bits.slot(2, 0)
                val returnedGroups = returned | Mux(pair(returnedGroup), (returned << 1)(7, 0), 0.U)
                streamOutstanding := (streamOutstanding | issued) & ~returned
                streamRetry := (streamRetry | retry) & ~issued
                streamDone := streamDone | Mux(io.response.valid && !io.response.bits.retry, returnedGroups, 0.U)
                when(io.request.fire) {
                    val following = streamRequestGroup +& Mux(pair(streamRequestGroup), 2.U, 1.U)
                    when(!streamReplay && following < count) {
                        streamRequestGroup := following(2, 0)
                        streamRequestAddress := streamRequestAddress + Mux(pair(streamRequestGroup), 64.U, 32.U)
                    }.otherwise { streamRequestValid := false.B }
                }
                when(!streamRequestValid && streamRetry.orR) {
                    val retryGroup = PriorityEncoder(streamRetry)
                    streamRequestGroup := retryGroup
                    streamRequestAddress := base + Cat(retryGroup, 0.U(5.W))
                    streamReplay := true.B
                    streamRequestValid := true.B
                }
                when(io.response.valid) {
                    val slot = io.response.bits.slot(2, 0)
                    assert(io.response.bits.wide && io.response.bits.exception === 0.U)
                    assert(streamOutstanding(slot), "Wide Load response retains its group owner")
                    when(!io.response.bits.retry) { assert(streamLines.io.enq.ready) }
                }
                when(streamLines.io.deq.valid) {
                    val firstGroup = streamLines.io.deq.bits.group
                    val slot = firstGroup + streamHigh.asUInt
                    streamWriteValid := true.B
                    streamWrite.address := metadata.destination.physical(slot)
                    streamWrite.data := Mux(streamHigh || base(5) ^ firstGroup(0),
                        streamLines.io.deq.bits.line(511, 256), streamLines.io.deq.bits.line(255, 0))
                        .asTypeOf(Vec(8, UInt(32.W)))
                    streamWrite.bytes := VecInit.fill(8)(15.U(4.W))
                    streamWrite.ready := true.B
                    streamLines.io.deq.ready := streamHigh || !pair(firstGroup)
                    streamHigh := !streamLines.io.deq.ready
                }
                when(streamDone === streamExpected && !streamWriteValid &&
                    !streamRequestValid && !streamOutstanding.orR && streamCredits === 0.U) { nextState := complete }
            }
            is(streamDrain) { when(!io.cachePending) { nextState := idle } }
        }
        // New admission wins over the outgoing Store's group/state updates.
        // The old SQ payload and ROB tag remain combinationally unchanged at
        // this edge, while the next instruction starts operand collection.
        when(legacyIn.fire) {
            instruction := legacyIn.bits
            val firstSource = Mux(legacyIn.bits.vector.groups.mask,
                legacyIn.bits.vector.mask, legacyIn.bits.vector.source(0)(0))
            sourceReadAddress := firstSource
            sourceReadSelection := UIntToOH(firstSource, 64)
            oldReadAddress := legacyIn.bits.vector.source(2)(0)
            oldReadSelection := UIntToOH(legacyIn.bits.vector.source(2)(0), 64)
            nextState := operands
            group := 0.U
            groupOH := 1.U
            discard := false.B
            cachedValid := false.B
            fullStore := false.B
        }
    }
    // Even a flush-cycle response releases the old owner. Do not leave a
    // discarded miss waiting for a second response after its slot is reused.
    when(state === response && io.response.valid) {
        when(discard || io.flush) { nextState := idle }
        .elsewhen(io.response.bits.retry) { nextState := request }
        .elsewhen(elementAccess) {
            cachedLine := io.response.bits.line
            cachedAddress := address(31, 6)
            cachedValid := true.B
            nextState := merge
        }.elsewhen(crossesLine && !second) {
            first := io.response.bits.line
            second := true.B
            nextState := request
        }.otherwise {
            data := VecInit((0 until 32).map(byte => Mux(byteMask(byte), loaded(byte), heldBytes(byte)))).asUInt
            nextState := writeback
        }
        assert(io.response.bits.wide && io.response.bits.exception === 0.U)
    }
    when(state === response && discard && !io.cachePending) { nextState := idle }
    when(legacyIn.fire) {
        assert(legacyIn.bits.vector.control.execution === VectorExecution.Load.U ||
            legacyIn.bits.vector.control.execution === VectorExecution.Store.U)
        val incoming = legacyIn.bits.vector
        assert(Mux(incoming.control.execution === VectorExecution.Store.U,
            incoming.groups.sourceCount(0), incoming.destination.count) =/= 0.U)
    }
    when(state =/= idle) { assert(groupOH === UIntToOH(group, 8)) }
    assert(streamCredits <= 4.U)
    when(streamReturned) { assert(streamCredits =/= 0.U) }
    // Four architectural Load owners share the original four-entry data FIFO.
    // Only instruction metadata grows; line storage and response credits are unchanged.
    val fastValid = RegInit(VecInit.fill(4)(false.B))
    val fastInstruction = Reg(Vec(4, new VectorInstruction(p)))
    val fastBase = Reg(Vec(4, UInt(32.W)))
    val fastBaseReady = RegInit(VecInit.fill(4)(false.B))
    val fastAligned = Reg(Vec(4, Bool()))
    val fastCursor = Reg(Vec(4, UInt(4.W)))
    val fastRetry = RegInit(VecInit.fill(4)(0.U(8.W)))
    val fastWritten = RegInit(VecInit.fill(4)(0.U(8.W)))
    val fastHead = RegInit(0.U(2.W))
    val fastTail = RegInit(0.U(2.W))
    val fastScalarPending = RegInit(false.B)
    val fastScalarOwner = Reg(UInt(2.W))
    val fastFallbackStarted = RegInit(false.B)
    val fastDrain = RegInit(false.B)
    val fastCount = VecInit(fastInstruction.map(_.vector.destination.count))
    val fastExpected = VecInit(fastCount.map(n => ((1.U(9.W) << n) - 1.U)(7, 0)))
    val fastOwned = fastValid.asUInt.orR
    val fastMode = fastOwned && !fastFallbackStarted && !fastDrain

    val ticketValid = RegInit(0.U(4.W))
    val ticketFlight = RegInit(0.U(4.W))
    val ticketContext = Reg(Vec(4, UInt(2.W)))
    val ticketGroup = Reg(Vec(4, UInt(3.W)))
    val ticketPair = Reg(Vec(4, Bool()))
    val fastHeld = RegInit(false.B)
    val fastHeldAddress = Reg(UInt(32.W))
    val fastHeldContext = Reg(UInt(2.W))
    val fastHeldGroup = Reg(UInt(3.W))
    val fastHeldTicket = Reg(UInt(2.W))
    val fastHeldPair = Reg(Bool())
    val fastHeldRetry = Reg(Bool())
    val fastHigh = RegInit(false.B)
    val fastWriteValid = RegInit(false.B)
    val fastWriteContext = Reg(UInt(2.W))
    val fastWriteGroup = Reg(UInt(3.W))
    fastWriteValid := false.B

    val incoming = io.in.bits.vector
    val incomingWhole = io.in.bits.scalar.inst(27, 26) === 0.U &&
        io.in.bits.scalar.inst(24, 20) === 8.U
    def unitWordLoad(task: VectorInstruction): Bool =
        task.scalar.inst(31, 26) === 0.U && task.scalar.inst(24, 20) === 0.U &&
            task.scalar.inst(14, 12) === 6.U &&
            task.vector.state.vtype(5, 3) === 2.U
    def shortWordLoad(task: VectorInstruction): Bool =
        unitWordLoad(task) && task.vector.destination.count === 1.U &&
            task.vector.state.vl.orR && task.vector.state.vl <= 4.U && task.vector.state.vtype(6)
    val incomingFullWords = unitWordLoad(io.in.bits) &&
        incoming.state.vl === (incoming.destination.count.pad(32) << 3)
    val incomingFast = incoming.control.execution === VectorExecution.Load.U &&
        (incomingWhole || incomingFullWords || shortWordLoad(io.in.bits)) &&
        !incoming.groups.mask && incoming.state.vstart === 0.U
    val fallbackRequest = fastOwned && fastBaseReady(fastHead) &&
        !fastAligned(fastHead) && !fastFallbackStarted && !fastDrain && state === idle
    val directLegacy = !fastOwned && !fastDrain && !incomingFast
    legacyIn.valid := !io.flush && (fallbackRequest || io.in.valid && directLegacy)
    legacyIn.bits := Mux(fallbackRequest, fastInstruction(fastHead), io.in.bits)
    val fastRoom = !fastValid(fastTail) && !fastScalarPending
    io.in.ready := !io.flush && !fastDrain && Mux(incomingFast,
        state === idle && !fastFallbackStarted && !fallbackRequest && fastRoom,
        !fastOwned && legacyIn.ready)
    val fastAdmit = io.in.fire && incomingFast
    val fastScalarCapture = fastScalarPending && io.readAvailable && !io.flush
    val fastFinished = fastOwned && fastBaseReady(fastHead) && fastAligned(fastHead) &&
        fastWritten(fastHead) === fastExpected(fastHead)
    val fastRelease = fastMode && fastFinished && io.completion.ready && !io.flush
    val fallbackRelease = fastFallbackStarted && io.completion.fire
    io.idle := state === idle && !fastOwned && !fastDrain

    when(fastMode || fastDrain) {
        io.readRequest := false.B
        io.scalarReadRequest := fastScalarPending && !fastDrain && !io.flush
        io.scalarAddress := fastInstruction(fastScalarOwner).scalar.prs(0)
        io.request.valid := fastHeld && io.ordering.clear && !io.flush && !fastDrain
        io.request.bits := 0.U.asTypeOf(new DLoadRequest(cache))
        io.request.bits.wide := true.B
        io.request.bits.vaddr := fastHeldAddress
        io.request.bits.paddr := fastHeldAddress
        io.request.bits.slot := fastHeldTicket
        io.request.bits.mtype := 2.U
        io.request.bits.halfMask := Mux(fastHeldPair, 0.U,
            Mux(fastBase(fastHeldContext)(5) ^ fastHeldGroup(0), 2.U, 1.U))
        io.ordering.tail := fastInstruction(Mux(fastHeld, fastHeldContext, fastHead)).scalar.sqTail
        io.completion.valid := fastFinished && !io.flush && !fastDrain
        io.completion.bits := 0.U.asTypeOf(new VectorCompletion(p))
        io.completion.bits.robIdx := fastInstruction(fastHead).scalar.robIdx
    }
    when(fastDrain) { io.writeback.valid := false.B }

    val issueFast = fastMode && io.request.fire
    val issuedTicket = UIntToOH(fastHeldTicket, 4)
    // Prepare behind an accepted request, keeping its old payload stable until
    // fire. Admission must see the advanced cursor and reserve a different
    // ticket; otherwise the same group or response owner would be reused.
    val prepareCursor = VecInit((0 until 4).map(i =>
        Mux(issueFast && fastHeldContext === i.U && !fastHeldRetry,
            fastHeldGroup +& Mux(fastHeldPair, 2.U, 1.U), fastCursor(i))))
    val prepareRetry = VecInit((0 until 4).map(i => fastRetry(i) &
        ~Mux(issueFast && fastHeldContext === i.U, UIntToOH(fastHeldGroup, 8), 0.U)))
    val freeTickets = ~(ticketValid | Mux(issueFast, issuedTicket, 0.U))
    val fastTodo = VecInit((0 until 4).map(i => fastValid(i) && fastBaseReady(i) && fastAligned(i) &&
        (prepareRetry(i).orR || prepareCursor(i) < fastCount(i))))
    val contextOrder = VecInit((0 until 4).map(i => (fastHead + i.U)(1, 0)))
    val todoByAge = VecInit(contextOrder.map(index => fastTodo(index)))
    val selectedContext = contextOrder(PriorityEncoder(todoByAge))
    val canPrepare = fastMode && fastBaseReady(fastHead) && fastAligned(fastHead) &&
        fastTodo.asUInt.orR && (!fastHeld || issueFast) && freeTickets.orR && !io.flush
    val selectedRetry = prepareRetry(selectedContext).orR
    val selectedGroup = Mux(selectedRetry, PriorityEncoder(prepareRetry(selectedContext)),
        prepareCursor(selectedContext)(2, 0))
    val selectedPair = !(fastBase(selectedContext)(5) ^ selectedGroup(0)) &&
        selectedGroup +& 1.U < fastCount(selectedContext)
    val responseTicket = io.response.bits.slot(1, 0)
    val responseOH = UIntToOH(responseTicket, 4)
    val replyFast = fastMode && io.response.valid && !io.flush
    val retryFast = replyFast && io.response.bits.retry
    val repliedContext = ticketContext(responseTicket)
    val repliedGroup = ticketGroup(responseTicket)
    val drainFastLine = fastMode && streamLines.io.deq.valid && !io.flush
    val queuedContext = streamLines.io.deq.bits.context
    val queuedGroup = streamLines.io.deq.bits.group
    val queuedPair = !(fastBase(queuedContext)(5) ^ queuedGroup(0)) &&
        queuedGroup +& 1.U < fastCount(queuedContext)
    val finishFastLine = drainFastLine && (fastHigh || !queuedPair)
    val releaseTicket = Mux(retryFast, responseOH, 0.U) |
        Mux(finishFastLine, UIntToOH(streamLines.io.deq.bits.ticket, 4), 0.U)
    val actualFastWrite = streamWriteValid && fastWriteValid && !io.flush

    when(fastMode || fastDrain) {
        streamLines.io.enq.valid := replyFast && !io.response.bits.retry
        streamLines.io.enq.bits.line := io.response.bits.line
        streamLines.io.enq.bits.group := repliedGroup
        streamLines.io.enq.bits.context := repliedContext
        streamLines.io.enq.bits.ticket := responseTicket
        streamLines.io.deq.ready := finishFastLine
    }
    when(canPrepare) {
        fastHeld := true.B
        fastHeldContext := selectedContext
        fastHeldGroup := selectedGroup
        fastHeldTicket := PriorityEncoder(freeTickets)
        fastHeldPair := selectedPair
        fastHeldRetry := selectedRetry
        fastHeldAddress := Cat((fastBase(selectedContext) + Cat(selectedGroup, 0.U(5.W)))(31, 6), 0.U(6.W))
    }
    when(issueFast) {
        fastHeld := canPrepare
        ticketContext(fastHeldTicket) := fastHeldContext
        ticketGroup(fastHeldTicket) := fastHeldGroup
        ticketPair(fastHeldTicket) := fastHeldPair
        assert(!ticketValid(fastHeldTicket), "Load ticket is free at real request admission")
    }
    when(replyFast) {
        assert(io.response.bits.slot < 4.U && ticketFlight(responseTicket), "Response retains an in-flight ticket")
        assert(io.response.bits.wide && io.response.bits.exception === 0.U)
        assert(fastValid(repliedContext), "Response retains its architectural Load owner")
        when(!io.response.bits.retry) {
            assert(streamLines.io.enq.ready, "Reserved response ticket protects FIFO capacity")
            assert(ticketPair(responseTicket) === (!(fastBase(repliedContext)(5) ^ repliedGroup(0)) &&
                repliedGroup +& 1.U < fastCount(repliedContext)))
        }
    }
    when(drainFastLine) {
        assert(ticketValid(streamLines.io.deq.bits.ticket) && (!ticketFlight(streamLines.io.deq.bits.ticket) ||
            replyFast && !io.response.bits.retry && responseTicket === streamLines.io.deq.bits.ticket))
        streamWriteValid := true.B
        fastWriteValid := true.B
        fastWriteContext := queuedContext
        fastWriteGroup := queuedGroup + fastHigh.asUInt
        streamWrite.address := fastInstruction(queuedContext).vector.destination.physical(queuedGroup + fastHigh.asUInt)
        val line = streamLines.io.deq.bits.line
        val ordinary = Mux(fastHigh || fastBase(queuedContext)(5) ^ queuedGroup(0),
            streamLines.io.deq.bits.line(511, 256), streamLines.io.deq.bits.line(255, 0))
        // An unmasked short SEW32 load with tail-agnostic policy needs no old
        // destination read. Select one aligned 16-byte quarter of the existing
        // response line and fill inactive lanes; tickets, ownership and the
        // response FIFO are exactly the same as for a whole-register load.
        val quarter = MuxLookup(fastBase(queuedContext)(5, 4), line(127, 0))(Seq(
            1.U -> line(255, 128), 2.U -> line(383, 256), 3.U -> line(511, 384)))
            .asTypeOf(Vec(4, UInt(32.W)))
        val short = VecInit((0 until 8).map(lane =>
            if (lane < 4) Mux(lane.U < fastInstruction(queuedContext).vector.state.vl,
                quarter(lane), "hffffffff".U) else "hffffffff".U(32.W)))
        streamWrite.data := Mux(shortWordLoad(fastInstruction(queuedContext)), short.asUInt, ordinary)
            .asTypeOf(Vec(8, UInt(32.W)))
        streamWrite.bytes := VecInit.fill(8)(15.U(4.W))
        streamWrite.ready := true.B
        fastHigh := !finishFastLine
    }
    when(fastScalarCapture) { fastScalarPending := false.B }
    when(fastAdmit) {
        fastScalarPending := true.B
        fastScalarOwner := fastTail
        fastTail := fastTail + 1.U
    }
    when(fallbackRequest && legacyIn.fire) { fastFallbackStarted := true.B }
    when(fastRelease || fallbackRelease) {
        fastHead := fastHead + 1.U
        fastFallbackStarted := false.B
    }
    for(i <- 0 until 4) {
        val retrySet = Mux(retryFast && repliedContext === i.U,
            UIntToOH(repliedGroup, 8), 0.U)
        val retryClear = Mux(issueFast && fastHeldContext === i.U,
            UIntToOH(fastHeldGroup, 8), 0.U)
        fastRetry(i) := (fastRetry(i) | retrySet) & ~retryClear
        when(issueFast && fastHeldContext === i.U && !fastHeldRetry) {
            fastCursor(i) := fastHeldGroup +& Mux(fastHeldPair, 2.U, 1.U)
        }
        when(actualFastWrite && fastWriteContext === i.U) {
            assert(fastValid(i) && !fastWritten(i)(fastWriteGroup), "PRF group writes once to its live owner")
            fastWritten(i) := fastWritten(i) | UIntToOH(fastWriteGroup, 8)
        }
        when(fastScalarCapture && fastScalarOwner === i.U) {
            fastBase(i) := io.scalarData
            fastBaseReady(i) := true.B
            fastAligned(i) := Mux(shortWordLoad(fastInstruction(i)),
                io.scalarData(3, 0) === 0.U, io.scalarData(4, 0) === 0.U)
        }
        when((fastRelease || fallbackRelease) && fastHead === i.U) { fastValid(i) := false.B }
        when(fastAdmit && fastTail === i.U) {
            fastValid(i) := true.B
            fastInstruction(i) := io.in.bits
            fastBaseReady(i) := false.B
            fastCursor(i) := 0.U
            fastRetry(i) := 0.U
            fastWritten(i) := 0.U
            assert(!fastValid(i), "Instruction context cannot be reused before completion acceptance")
        }
    }
    when(fastMode) {
        ticketValid := (ticketValid | Mux(issueFast, issuedTicket, 0.U)) & ~releaseTicket
        ticketFlight := (ticketFlight | Mux(issueFast, issuedTicket, 0.U)) &
            ~Mux(replyFast, responseOH, 0.U)
    }
    when(fastDrain && !io.cachePending) { fastDrain := false.B }
    when(io.flush) {
        fastDrain := (fastMode || fastDrain) && io.cachePending
        fastValid.foreach(_ := false.B)
        fastBaseReady.foreach(_ := false.B)
        fastRetry.foreach(_ := 0.U)
        fastWritten.foreach(_ := 0.U)
        fastHead := 0.U
        fastTail := 0.U
        fastScalarPending := false.B
        fastFallbackStarted := false.B
        fastHeld := false.B
        fastHigh := false.B
        fastWriteValid := false.B
        ticketValid := 0.U
        ticketFlight := 0.U
    }
    assert((ticketFlight & ~ticketValid) === 0.U)
    when(fastRelease) {
        assert(!fastScalarPending || fastScalarOwner =/= fastHead)
        for(t <- 0 until 4) {
            assert(!ticketValid(t) || ticketContext(t) =/= fastHead,
                "Completion cannot abandon a response/line owner")
        }
        assert(!fastHeld || fastHeldContext =/= fastHead)
    }
    // Native SRAM has one physical address space. Its Load may pass complete
    // non-overlapping vector Stores; cached, scalar and unknown-address Stores
    // retain the conservative ordering path, including possible SRAM aliases.
    io.ordering.native := PeScratchpadBridge.matches(io.request.bits.paddr)
    io.ordering.line := io.request.bits.paddr(31, 6)
}
