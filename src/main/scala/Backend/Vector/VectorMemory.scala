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
    }, 4, flow = true, hasFlush = true))
    streamLines.io.flush.get := io.flush
    val streamCredits = RegInit(0.U(3.W))
    val streamHigh = RegInit(false.B)
    streamLines.io.enq.valid := state === streamLoad && io.response.valid && !io.response.bits.retry && !io.flush
    streamLines.io.enq.bits.line := io.response.bits.line
    streamLines.io.enq.bits.group := io.response.bits.slot(2, 0)
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
    io.in.ready := state === idle && !io.flush
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
        Mux(io.in.fire, io.in.bits.vector.groups.mask, metadata.groups.mask) ||
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
    io.completion.valid := state === complete && !io.flush
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
        when(io.in.fire) {
            instruction := io.in.bits
            val firstSource = Mux(io.in.bits.vector.groups.mask,
                io.in.bits.vector.mask, io.in.bits.vector.source(0)(0))
            sourceReadAddress := firstSource
            sourceReadSelection := UIntToOH(firstSource, 64)
            oldReadAddress := io.in.bits.vector.source(2)(0)
            oldReadSelection := UIntToOH(io.in.bits.vector.source(2)(0), 64)
            nextState := operands
            group := 0.U
            groupOH := 1.U
            discard := false.B
            cachedValid := false.B
        }
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
                    groupOffset := (withinField * groupStep)(31, 0)
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
                    elementAddress := elementAddress + step
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
                elementAddress := elementAddress + step
                nextState := Mux(lastElement, writeback, gather)
            } }
            is(merge) {
                val fetched = (cachedLine >> (address(5, 0) << 3))(31, 0)
                val positioned = (fetched.pad(256) << (position << 3))(255, 0)
                val lowMask = Mux(eew === 0.U, 255.U(32.W), Mux(eew === 1.U, 65535.U(32.W), "hffffffff".U))
                val positionedMask = (lowMask.pad(256) << (position << 3))(255, 0)
                data := (data & ~positionedMask) | (positioned & positionedMask)
                position := nextPosition(4, 0)
                elementAddress := elementAddress + step
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
                    nextState := Mux(last, complete, collect)
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
    when(io.in.fire) {
        assert(io.in.bits.vector.control.execution === VectorExecution.Load.U ||
            io.in.bits.vector.control.execution === VectorExecution.Store.U)
        val incoming = io.in.bits.vector
        assert(Mux(incoming.control.execution === VectorExecution.Store.U,
            incoming.groups.sourceCount(0), incoming.destination.count) =/= 0.U)
    }
    when(state =/= idle) { assert(groupOH === UIntToOH(group, 8)) }
    assert(streamCredits <= 4.U)
    when(streamReturned) { assert(streamCredits =/= 0.U) }
}
