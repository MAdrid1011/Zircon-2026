import chisel3._
import chisel3.util._
import ZirconConfig._

class CommittedStore extends Bundle {
    val paddr = UInt(34.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
    val size = UInt(2.W)
    val uncache = Bool()
}

class StoreQueueEntry(bp: BackendParams) extends Bundle {
    val valid = Bool()
    val identity = UInt(bp.sqWidth.W)
    val robIdx = UInt(CommitIndex.addressWidth(CommitParams().robEntries).W)
    val committed = Bool()
    val addressValid = Bool()
    val dataValid = Bool()
    val vaddr = UInt(32.W)
    val paddr = UInt(34.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
    val size = UInt(2.W)
    val exception = UInt(4.W)
    val uncache = Bool()
    val atomic = Bool()
    val atomicOp = UInt(5.W)
    val prd = UInt(bp.tagWidth.W)
}

class PendingAtomic(bp: BackendParams) extends Bundle {
    val robIdx = UInt(CommitIndex.addressWidth(CommitParams().robEntries).W)
    val prd = UInt(bp.tagWidth.W)
    val vaddr = UInt(32.W)
    val paddr = UInt(34.W)
    val data = UInt(32.W)
    val op = UInt(5.W)
    val uncache = Bool()
    val exception = UInt(4.W)
}

class StoreQueueAllocation(bp: BackendParams) extends Bundle {
    val tail = UInt(bp.sqWidth.W)
    val index = UInt(bp.sqWidth.W)
}

class StoreQueueAtomicIO(bp: BackendParams) extends Bundle {
    val sqIdx = Input(Valid(UInt(bp.sqWidth.W)))
    val request = Output(Valid(new PendingAtomic(bp)))
}

class StoreQueueQueryIO(load: LoadPipelineParams) extends Bundle {
    val request = Flipped(Valid(new LoadSQQuery(load)))
    val response = Valid(new DForwardResult(DCacheParams(load.entries)))
}

class StoreQueueIO(
    fp: FrontendParams,
    bp: BackendParams,
    load: LoadPipelineParams,
    dispatchWidth: Int,
    cp: CommitParams,
) extends Bundle {
    val request = Input(new MiddleendCommitRequest(fp, dispatchWidth))
    val availablePrefix = Output(UInt(dispatchWidth.W))
    val allocation = Output(Vec(dispatchWidth, new StoreQueueAllocation(bp)))
    val enqueue = Input(new MiddleendCommitEnqueue(fp, bp, dispatchWidth))
    val address = Flipped(Decoupled(new StoreAddressResult(load)))
    val data = Flipped(Decoupled(new StoreDataResult(load)))
    val completion = Output(Vec(2, Valid(new ROBWrite(CommitIndex.addressWidth(cp.robEntries)))))
    val commit = Input(Vec(cp.width, Valid(UInt(bp.sqWidth.W))))
    val drain = Decoupled(new CommittedStore)
    val atomic = new StoreQueueAtomicIO(bp)
    val query = Vec(2, new StoreQueueQueryIO(load))
    val flush = Input(Bool())
    val empty = Output(Bool())
    val committedEmpty = Output(Bool())
}

/** Speculative Store queue with a committed boundary that survives recovery. */
class StoreQueue(
    val fp: FrontendParams = FrontendParams(),
    val bp: BackendParams = BackendParams(),
    val load: LoadPipelineParams = LoadPipelineParams(numIntPhys = 64, numFpPhys = 40),
    val dispatchWidth: Int = IssueParams().dispatchWidth,
    val cp: CommitParams = CommitParams(),
) extends Module {
    private val entries = cp.sqEntries
    private val ringEntries = entries * 2
    private val pointerWidth = log2Ceil(ringEntries)
    private val slotWidth = log2Ceil(entries)
    require(bp.sqWidth >= pointerWidth)
    require(bp == load.backend)

    val io = IO(new StoreQueueIO(fp, bp, load, dispatchWidth, cp))
    val storage = Reg(Vec(entries, new StoreQueueEntry(bp)))
    val valid = RegInit(VecInit.fill(entries)(false.B))
    val head = RegInit(0.U(pointerWidth.W))
    val headSlotOH = RegInit(1.U(entries.W))
    val commitTail = RegInit(0.U(pointerWidth.W))
    val tail = RegInit(0.U(pointerWidth.W))
    val allocated = RegInit(0.U(log2Ceil(entries + 1).W))
    val committed = RegInit(0.U(log2Ceil(entries + 1).W))

    private def next(pointer: UInt): UInt = Mux(pointer === (ringEntries - 1).U, 0.U, pointer + 1.U)
    private def slot(pointer: UInt): UInt =
        Mux(pointer >= entries.U, pointer - entries.U, pointer)(slotWidth - 1, 0)
    private def extended(pointer: UInt): UInt = pointer.pad(bp.sqWidth)
    private def narrow(pointer: UInt): UInt = pointer(pointerWidth - 1, 0)
    private def advance(pointer: UInt, amount: UInt, maximum: Int): UInt = {
        val choices = (0 to maximum).map { count =>
            val value = (0 until count).foldLeft(pointer)((current, _) => next(current))
            count.U -> value
        }
        MuxLookup(amount, pointer)(choices)
    }
    private def retreat(pointer: UInt, amount: Int): UInt =
        Mux(pointer >= amount.U, pointer - amount.U, pointer + (ringEntries - amount).U)(pointerWidth - 1, 0)
    private def alignStoreData(data: UInt, byteOffset: UInt): UInt = MuxLookup(byteOffset, data)(Seq(
        1.U -> Cat(data(23, 0), 0.U(8.W)),
        2.U -> Cat(data(15, 0), 0.U(16.W)),
        3.U -> Cat(data(7, 0), 0.U(24.W)),
    ))

    val requestStores = Wire(Vec(dispatchWidth, Bool()))
    val available = Wire(Vec(dispatchWidth, Bool()))
    var allocationPointer = tail
    for (lane <- 0 until dispatchWidth) {
        val request = io.request.store(lane)
        requestStores(lane) := request
        io.allocation(lane).tail := extended(allocationPointer)
        io.allocation(lane).index := extended(allocationPointer)
        allocationPointer = Mux(request, next(allocationPointer), allocationPointer)
        val storesThroughLane = PopCount(requestStores.take(lane + 1))
        available(lane) := VecInit((0 to lane + 1).map { count =>
            storesThroughLane === count.U && allocated <= (entries - count).U
        }).asUInt.orR
    }
    io.availablePrefix := available.asUInt

    val acceptedStores = Wire(Vec(dispatchWidth, Bool()))
    var enqueuePointer = tail
    val writeStores = Wire(Vec(dispatchWidth, Bool()))
    val writeStoreClass = io.request.store.asBools
    var writePointer = tail
    for (lane <- 0 until dispatchWidth) {
        val incoming = io.enqueue.entries(lane)
        val isStore = incoming.context.instruction.fu === DecodeUnit.Store.U
        val isAtomic = incoming.context.instruction.fu === DecodeUnit.Atomic.U
        acceptedStores(lane) := io.enqueue.valid(lane) && writeStoreClass(lane) && !io.flush
        // Payload prewrite may run before another Commit resource admits this
        // group, but it must never cross this queue's own free-space boundary.
        val writeRank = PopCount(writeStoreClass.take(lane + 1))
        writeStores(lane) := io.enqueue.writeValid(lane) &&
            allocated + writeRank <= entries.U && writeStoreClass(lane)
        val index = slot(enqueuePointer)
        val writeIndex = slot(writePointer)
        when(writeStores(lane)) {
            storage(writeIndex).identity := extended(writePointer)
            storage(writeIndex).robIdx := incoming.allocation.robIdx
            storage(writeIndex).committed := false.B
            storage(writeIndex).addressValid := false.B
            storage(writeIndex).dataValid := false.B
            storage(writeIndex).exception := 0.U
            storage(writeIndex).atomic := isAtomic
            storage(writeIndex).atomicOp := incoming.context.instruction.op
            storage(writeIndex).prd := incoming.destination.prd
        }
        when(acceptedStores(lane)) { valid(index) := true.B }
        enqueuePointer = Mux(acceptedStores(lane), next(enqueuePointer), enqueuePointer)
        writePointer = Mux(writeStores(lane), next(writePointer), writePointer)
        when(io.request.valid(lane)) {
            assert(io.request.store(lane) === (isStore || isAtomic))
        }
    }
    val enqueueCount = PopCount(acceptedStores)

    io.address.ready := !io.flush
    io.data.ready := !io.flush
    val addressPointer = narrow(io.address.bits.sqIdx)
    val dataPointer = narrow(io.data.bits.sqIdx)
    val addressIndex = slot(addressPointer)
    val dataIndex = slot(dataPointer)
    val addressEntry = storage(addressIndex)
    val dataEntry = storage(dataIndex)
    val dataByteOffset = VecInit(storage.map(_.paddr(1, 0)))(dataIndex)
    when(io.address.fire) {
        assert(valid(addressIndex) && addressEntry.identity === io.address.bits.sqIdx)
        assert(addressEntry.robIdx === io.address.bits.robIdx)
        addressEntry.addressValid := true.B
        addressEntry.vaddr := io.address.bits.vaddr
        addressEntry.paddr := io.address.bits.paddr
        addressEntry.mask := io.address.bits.mask
        addressEntry.size := io.address.bits.size
        addressEntry.exception := io.address.bits.exception
        addressEntry.uncache := io.address.bits.uncache
        when(addressEntry.dataValid) {
            addressEntry.data := alignStoreData(addressEntry.data, io.address.bits.paddr(1, 0))
        }
    }
    when(io.data.fire) {
        assert(valid(dataIndex) && dataEntry.identity === io.data.bits.sqIdx)
        assert(dataEntry.robIdx === io.data.bits.robIdx)
        dataEntry.dataValid := true.B
        val sameCycleAddress = io.address.fire && io.address.bits.sqIdx === io.data.bits.sqIdx
        val alignmentOffset = Mux(sameCycleAddress, io.address.bits.paddr(1, 0), dataByteOffset)
        dataEntry.data := Mux(
            dataEntry.addressValid || sameCycleAddress,
            alignStoreData(io.data.bits.data, alignmentOffset),
            io.data.bits.data,
        )
        dataEntry.size := io.data.bits.size
    }

    val addressGetsData = valid(dataIndex) && io.data.fire && io.address.fire &&
        io.data.bits.sqIdx === io.address.bits.sqIdx
    val completionNext = Wire(Vec(2, Valid(new ROBWrite(CommitIndex.addressWidth(cp.robEntries)))))
    completionNext(0).valid := io.address.fire && !addressEntry.atomic &&
        (io.address.bits.exception.orR || addressEntry.dataValid || addressGetsData)
    completionNext(0).bits.address := io.address.bits.robIdx
    completionNext(0).bits.complete := true.B
    completionNext(0).bits.data := 0.U
    completionNext(0).bits.mispredicted := false.B
    completionNext(0).bits.exception.valid := io.address.bits.exception.orR
    completionNext(0).bits.exception.cause := io.address.bits.exception
    completionNext(0).bits.exception.tval := io.address.bits.vaddr
    completionNext(0).bits.fflags := 0.U
    completionNext(0).bits.fpFlagsValid := false.B
    completionNext(1).valid := io.data.fire && !dataEntry.atomic && dataEntry.addressValid &&
        !dataEntry.exception.orR && !(io.address.fire && io.address.bits.sqIdx === io.data.bits.sqIdx)
    completionNext(1).bits.address := io.data.bits.robIdx
    completionNext(1).bits.complete := true.B
    completionNext(1).bits.data := 0.U
    completionNext(1).bits.mispredicted := false.B
    completionNext(1).bits.exception := 0.U.asTypeOf(new BackendException)
    completionNext(1).bits.fflags := 0.U
    completionNext(1).bits.fpFlagsValid := false.B

    // Completion is architectural only at the ROB write edge. Registering the
    // narrow event keeps SQ entry selection and merge logic off every ROB bank.
    val completionValid = RegInit(VecInit.fill(2)(false.B))
    val completionBits = Reg(Vec(2, new ROBWrite(CommitIndex.addressWidth(cp.robEntries))))
    // Flush clears validity; the payload sampled on that edge is unobservable.
    completionBits := VecInit(completionNext.map(_.bits))
    when(io.flush) {
        completionValid := VecInit.fill(2)(false.B)
    }.otherwise {
        completionValid := VecInit(completionNext.map(_.valid))
    }
    for (port <- 0 until 2) {
        io.completion(port).valid := completionValid(port) && !io.flush
        io.completion(port).bits := completionBits(port)
    }

    val commitCount = PopCount(io.commit.map(_.valid))
    for (lane <- 0 until cp.width) {
        when(io.commit(lane).valid) {
            val pointer = narrow(io.commit(lane).bits)
            val index = slot(pointer)
            assert(valid(index) && storage(index).identity === io.commit(lane).bits)
            storage(index).committed := true.B
        }
    }
    when(!io.flush) {
        for (lane <- 0 until cp.width) {
            val expected = advance(commitTail, PopCount(io.commit.take(lane).map(_.valid)), cp.width)
            when(io.commit(lane).valid) {
                assert(io.commit(lane).bits === extended(expected), "Stores must become architectural in program order")
            }
        }
    }

    val headEntry = Mux1H(headSlotOH.asBools, storage)
    val headValid = (headSlotOH & valid.asUInt).orR
    val requestedAtomic = io.atomic.sqIdx.valid && headValid && headEntry.atomic &&
        headEntry.identity === io.atomic.sqIdx.bits
    val atomicReady = requestedAtomic && headEntry.addressValid && headEntry.dataValid
    val pendingAtomic = Reg(new PendingAtomic(bp))
    val pendingAtomicIdentity = Reg(UInt(bp.sqWidth.W))
    val pendingAtomicValid = RegInit(false.B)
    when(io.flush || !io.atomic.sqIdx.valid) {
        pendingAtomicValid := false.B
    }.elsewhen(atomicReady) {
        pendingAtomicValid := true.B
        pendingAtomicIdentity := headEntry.identity
        pendingAtomic.robIdx := headEntry.robIdx
        pendingAtomic.prd := headEntry.prd
        pendingAtomic.vaddr := headEntry.vaddr
        pendingAtomic.paddr := headEntry.paddr
        pendingAtomic.data := headEntry.data
        pendingAtomic.op := headEntry.atomicOp
        pendingAtomic.uncache := headEntry.uncache
        pendingAtomic.exception := headEntry.exception
    }.elsewhen(pendingAtomicIdentity =/= io.atomic.sqIdx.bits) {
        pendingAtomicValid := false.B
    }
    io.atomic.request.valid := pendingAtomicValid && io.atomic.sqIdx.valid &&
        pendingAtomicIdentity === io.atomic.sqIdx.bits
    io.atomic.request.bits := pendingAtomic
    io.drain.valid := headValid && !headEntry.atomic && headEntry.committed &&
        headEntry.addressValid && headEntry.dataValid &&
        !headEntry.exception.orR
    io.drain.bits.paddr := headEntry.paddr
    io.drain.bits.data := headEntry.data
    io.drain.bits.mask := headEntry.mask
    io.drain.bits.size := headEntry.size
    io.drain.bits.uncache := headEntry.uncache

    val atomicRetire = VecInit(io.commit.map { event =>
        val entry = storage(slot(narrow(event.bits)))
        event.valid && valid(slot(narrow(event.bits))) && entry.atomic && entry.identity === event.bits
    }).asUInt.orR
    when(atomicRetire) {
        assert(headValid && headEntry.atomic)
    }
    val removeHead = io.drain.fire || atomicRetire
    when(removeHead) {
        for (index <- 0 until entries) {
            when(headSlotOH(index)) {
                valid(index) := false.B
                storage(index).committed := false.B
            }
        }
    }
    val removeCount = removeHead.asUInt
    val committedAfterEvents = committed + commitCount - removeCount
    when(io.flush) {
        for (index <- 0 until entries) {
            when(valid(index) && !storage(index).committed) { valid(index) := false.B }
        }
        tail := commitTail
        allocated := committed - removeCount
    }.otherwise {
        tail := advance(tail, enqueueCount, dispatchWidth)
        allocated := allocated + enqueueCount - removeCount
    }
    commitTail := advance(commitTail, commitCount, cp.width)
    when(removeHead) {
        head := next(head)
        headSlotOH := FIFOUtil.rotate(headSlotOH, 1)
    }
    committed := committedAfterEvents
    io.empty := allocated === 0.U
    io.committedEmpty := committed === 0.U

    val queryData = Wire(Vec(entries, UInt(32.W)))
    val queryAddressValid = Wire(Vec(entries, Bool()))
    val queryPaddr = Wire(Vec(entries, UInt(34.W)))
    val queryMask = Wire(Vec(entries, UInt(4.W)))
    val queryDataValid = Wire(Vec(entries, Bool()))
    for (index <- 0 until entries) {
        val entry = storage(index)
        val addressHit = io.address.fire && valid(index) && entry.identity === io.address.bits.sqIdx
        val dataHit = io.data.fire && valid(index) && entry.identity === io.data.bits.sqIdx
        val sameCycleAddress = addressHit && io.address.bits.sqIdx === io.data.bits.sqIdx
        queryAddressValid(index) := entry.addressValid || addressHit
        queryPaddr(index) := Mux(addressHit, io.address.bits.paddr, entry.paddr)
        queryMask(index) := Mux(addressHit, io.address.bits.mask, entry.mask)
        queryDataValid(index) := entry.dataValid || dataHit
        queryData(index) := entry.data
        when(addressHit && entry.dataValid) {
            queryData(index) := alignStoreData(entry.data, io.address.bits.paddr(1, 0))
        }
        when(dataHit) {
            val offset = Mux(sameCycleAddress, io.address.bits.paddr(1, 0), entry.paddr(1, 0))
            queryData(index) := Mux(
                entry.addressValid || sameCycleAddress,
                alignStoreData(io.data.bits.data, offset),
                io.data.bits.data,
            )
        }
    }
    val dataStage = Reg(Vec(entries, UInt(32.W)))
    val dataValidStage = Reg(UInt(entries.W))
    dataStage := queryData
    dataValidStage := queryDataValid.asUInt

    for (port <- 0 until 2) {
        val query = io.query(port).request
        val boundaryOH = query.bits.sqTailOH
        val boundary = OHToUInt(boundaryOH)
        val boundarySlotOH = VecInit((0 until entries).map { index =>
            boundaryOH(index) || boundaryOH(index + entries)
        }).asUInt
        // The preceding 1..entries ring positions are older; the smaller distance wins.
        val age = VecInit(storage.map { entry =>
            val identity = entry.identity(pointerWidth - 1, 0)
            Mux(boundary >= identity, boundary - identity,
                boundary + ringEntries.U - identity)(pointerWidth - 1, 0)
        })
        val active = VecInit((0 until entries).map { index =>
            valid(index) && age(index) =/= 0.U && age(index) <= entries.U
        })
        val wordMatch = VecInit((0 until entries).map { index =>
            queryAddressValid(index) && queryPaddr(index)(33, 2) === query.bits.wordAddress
        })
        val winnerStage = Reg(Vec(4, UInt(entries.W)))
        val unknownStage = Reg(Bool())
        val resultSlot = Reg(chiselTypeOf(query.bits.slot))
        val resultValid = RegInit(false.B)
        val beforeBoundary = VecInit((0 until entries).map { index =>
            if (index == entries - 1) false.B
            else boundarySlotOH(entries - 1, index + 1).orR
        }).asUInt
        for (byte <- 0 until 4) {
            val hits = VecInit((0 until entries).map { index =>
                active(index) && wordMatch(index) && queryMask(index)(byte) && query.bits.mask(byte)
            }).asUInt
            val lowerHits = hits & beforeBoundary
            val lowerWinner = Reverse(PriorityEncoderOH(Reverse(lowerHits)))
            val wrapWinner = Reverse(PriorityEncoderOH(Reverse(hits)))
            winnerStage(byte) := Mux(lowerHits.orR, lowerWinner, wrapWinner)
        }
        unknownStage := VecInit((0 until entries).map { index =>
            active(index) && !queryAddressValid(index)
        }).asUInt.orR
        resultSlot := query.bits.slot
        when(io.flush) {
            resultValid := false.B
        }.otherwise {
            resultValid := query.valid
        }
        val resultBytes = Wire(Vec(4, UInt(8.W)))
        val resultMask = Wire(Vec(4, Bool()))
        val blockedBytes = Wire(Vec(4, Bool()))
        for (byte <- 0 until 4) {
            val select = winnerStage(byte)
            val dataBytes = Wire(Vec(entries, UInt(8.W)))
            for (index <- 0 until entries) {
                dataBytes(index) := dataStage(index)(8 * byte + 7, 8 * byte)
            }
            resultBytes(byte) := Mux1H(select.asBools, dataBytes)
            resultMask(byte) := select.orR
            blockedBytes(byte) := (select & ~dataValidStage).orR
            when(resultValid) { assert(PopCount(select) <= 1.U) }
        }
        io.query(port).response.valid := resultValid
        io.query(port).response.bits.slot := resultSlot
        io.query(port).response.bits.data := resultBytes.asUInt
        io.query(port).response.bits.mask := resultMask.asUInt
        io.query(port).response.bits.blocked := unknownStage || blockedBytes.asUInt.orR
        when(query.valid) {
            assert(PopCount(boundaryOH) === 1.U, "SQ query tail must be one-hot")
        }
    }

    when(!io.flush) {
        assert(allocated <= entries.U && committed <= allocated)
        assert((io.enqueue.valid & ~io.request.valid) === 0.U)
        assert((io.enqueue.valid & ~io.enqueue.writeValid) === 0.U)
    }
    assert(PopCount(headSlotOH) === 1.U && headSlotOH === UIntToOH(slot(head), entries))
    for (index <- 0 until entries) {
        when(valid(index)) {
            assert(slot(narrow(storage(index).identity)) === index.U,
                "SQ entry identity must match its physical slot")
        }
    }
}
