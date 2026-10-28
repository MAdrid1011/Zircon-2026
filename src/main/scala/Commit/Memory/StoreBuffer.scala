import chisel3._
import chisel3.util._
import ZirconConfig.{CommitParams, DCacheParams}

class StoreBufferCacheIO extends Bundle {
    val request = Decoupled(new DStoreRequest)
    val response = Flipped(Decoupled(new DStoreResponse))
}

class StoreBufferQueryIO(cache: DCacheParams) extends Bundle {
    val request = Flipped(Valid(new DForwardQuery(cache)))
    val response = Valid(new DForwardResult(cache))
}

class StoreBufferIO(cp: CommitParams, cache: DCacheParams) extends Bundle {
    val enqueue = Flipped(Decoupled(new CommittedStore))
    val store = new StoreBufferCacheIO
    val query = Vec(2, new StoreBufferQueryIO(cache))
    val responseError = Valid(UInt(4.W))
    val empty = Output(Bool())
    val scalarEmpty = Output(Bool())
    val scalarVectorClear = if(cp.vectorEnabled) Some(Input(Vec(2,Bool()))) else None
    val vector = if (cp.vectorEnabled) Some(new VectorStoreDrain) else None
}

/** Architectural stores wait here while DCache services them in program order. */
class StoreBuffer(
    val cp: CommitParams = CommitParams(),
    val cache: DCacheParams = DCacheParams(),
) extends Module {
    private val entries = cp.storeBufferEntries
    require(entries >= 2 && entries % 2 == 0)
    val io = IO(new StoreBufferIO(cp, cache))

    val queue = Module(new ClusterIndexFIFO(
        new CommittedStore,
        entries,
        2,
        2,
        entries,
        0,
        exposeDeqIndex = true,
        externallyGuardedCapacity = true,
    ))
    queue.io.flush := false.B
    queue.io.enq(0).valid := io.enqueue.fire
    queue.io.enq(0).bits := io.enqueue.bits
    queue.io.enq(1).valid := false.B
    queue.io.enq(1).bits := DontCare
    queue.io.deq(1).ready := false.B
    for (position <- 0 until entries) {
        queue.io.ridx(position).qidx := (1 << (position % 2)).U(2.W)
        queue.io.ridx(position).offset := (1 << (position / 2)).U((entries / 2).W)
        queue.io.ridx(position).high := false.B
    }
    val storage = queue.io.rdata
    def positionOH(index: ClusterEntry): UInt = VecInit((0 until entries).map { position =>
        index.qidx(position % 2) && index.offset(position / 2)
    }).asUInt
    val headOH = positionOH(queue.io.deqIdx.get(0))
    val tailOH = positionOH(queue.io.enqIdx(0))
    val valid = RegInit(VecInit.fill(entries)(false.B))
    val newestOH = RegInit(1.U(entries.W))
    val count = RegInit(0.U(log2Ceil(entries + 1).W))
    val outstanding = RegInit(false.B)
    val vectorOutstanding = if (cp.vectorEnabled) Some(RegInit(false.B)) else None

    val responseFire = Mux(vectorOutstanding.getOrElse(false.B),
        io.vector.map(_.done).getOrElse(false.B), io.store.response.fire)
    val replaceOutstanding = responseFire && count > 1.U
    io.enqueue.ready := count < entries.U || responseFire
    val offer = (headOH & valid.asUInt).orR && !outstanding || replaceOutstanding
    // An offered request is the successor while the current Store is still
    // outstanding. Select its payload from Q so response acceptance only
    // controls admission, without feeding back through address and PMA decode.
    val request = Mux(outstanding, queue.io.deq(1).bits, queue.io.deq(0).bits)
    when(offer) {
        assert(outstanding === replaceOutstanding,
            "An offered Store uses the successor exactly when replacing an outstanding Store")
    }
    val vectorRequest = if (cp.vectorEnabled) request.vector else false.B
    io.store.request.valid := offer && !vectorRequest
    io.vector.foreach { port =>
        port.start.valid := offer && vectorRequest
        port.start.bits := vectorRequest
    }
    io.store.request.bits.paddr := request.paddr
    io.store.request.bits.wide := false.B
    io.store.request.bits.line := 0.U
    io.store.request.bits.lineMask := 0.U
    io.store.request.bits.data := request.data
    io.store.request.bits.mask := request.mask
    io.store.request.bits.size := request.size
    io.store.request.bits.uncache := request.uncache
    io.store.request.bits.writeCombine := request.writeCombine
    io.store.response.ready := outstanding && !vectorOutstanding.getOrElse(false.B)
    val requestFire = io.store.request.fire || io.vector.map(_.start.fire).getOrElse(false.B)
    when(requestFire =/= responseFire) {
        outstanding := requestFire
    }
    vectorOutstanding.foreach(flag => when(requestFire) { flag := vectorRequest }
        .elsewhen(responseFire) { flag := false.B })
    queue.io.deq(0).ready := responseFire
    for (position <- 0 until entries) {
        when(responseFire && headOH(position)) { valid(position) := false.B }
        // A full buffer may recycle its old head on the same edge.
        when(io.enqueue.fire && tailOH(position)) { valid(position) := true.B }
    }
    when(io.enqueue.fire) {
        newestOH := tailOH
    }

    when(io.enqueue.fire =/= responseFire) {
        count := Mux(io.enqueue.fire, count + 1.U, count - 1.U)
    }
    io.empty := count === 0.U
    io.scalarEmpty := (if (cp.vectorEnabled) !VecInit((0 until entries).map(i =>
        valid(i) && !storage(i).vector)).asUInt.orR else io.empty)
    io.responseError.valid := io.store.response.fire && io.store.response.bits.exception.orR
    io.responseError.bits := io.store.response.bits.exception

    val newerThan = VecInit.tabulate(entries, entries) { (candidate, older) =>
        if (candidate == older) false.B
        else {
            val newestPositions = (0 until entries).filter { newest =>
                (newest - candidate + entries) % entries < (newest - older + entries) % entries
            }
            VecInit(newestPositions.map(newestOH(_))).asUInt.orR
        }
    }
    val forwardingData = Reg(Vec(entries, UInt(32.W)))
    for (position <- 0 until entries) {
        forwardingData(position) := storage(position).data
    }
    for (port <- 0 until 2) {
        val query = io.query(port).request
        val matches = Wire(Vec(entries, Bool()))
        for (position <- 0 until entries) {
            matches(position) := valid(position) && !storage(position).vector &&
                storage(position).paddr(33, 2) === query.bits.wordAddress
        }
        val bytes = Wire(Vec(4, UInt(8.W)))
        val mask = Wire(Vec(4, Bool()))
        val winnerStage = Reg(Vec(4, UInt(entries.W)))
        for (byte <- 0 until 4) {
            val hitMask = VecInit.tabulate(entries) { position =>
                matches(position) && storage(position).mask(byte) && query.bits.mask(byte)
            }.asUInt
            val winnerOH = VecInit.tabulate(entries) { candidate =>
                val newerHit = VecInit.tabulate(entries) { other =>
                    hitMask(other) && newerThan(other)(candidate)
                }.asUInt.orR
                hitMask(candidate) && !newerHit
            }
            winnerStage(byte) := winnerOH.asUInt
            bytes(byte) := Mux1H(winnerStage(byte).asBools, (0 until entries).map(position =>
                forwardingData(position)(8 * byte + 7, 8 * byte)))
            mask(byte) := hitMask.orR
            assert(PopCount(winnerOH) <= 1.U)
        }
        val resultValid = RegNext(query.valid, false.B)
        // The payload is sampled freely and qualified only by resultValid.
        val resultSlot = RegNext(query.bits.slot)
        val resultMask = RegNext(mask.asUInt)
        io.query(port).response.valid := resultValid
        io.query(port).response.bits.slot := resultSlot
        io.query(port).response.bits.data := bytes.asUInt
        io.query(port).response.bits.mask := resultMask
        io.query(port).response.bits.blocked := (if (cp.vectorEnabled)
            RegNext(Mux(PeScratchpadBridge.cachedTaskWord(query.bits.wordAddress),
                !io.scalarVectorClear.get(port),VecInit(storage.zipWithIndex.map { case (entry, index) =>
                valid(index) && entry.vector
            }).asUInt.orR), false.B) else false.B)
    }

    assert(count <= entries.U)
    assert(PopCount(newestOH) === 1.U)
    when(outstanding) { assert((headOH & valid.asUInt).orR) }
}
