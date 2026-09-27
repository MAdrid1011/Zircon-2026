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
}

/** Architectural stores wait here while DCache services them in program order. */
class StoreBuffer(
    val cp: CommitParams = CommitParams(),
    val cache: DCacheParams = DCacheParams(),
) extends Module {
    private val entries = cp.storeBufferEntries
    private val indexWidth = log2Ceil(entries)
    val io = IO(new StoreBufferIO(cp, cache))

    val storage = Reg(Vec(entries, new CommittedStore))
    val valid = RegInit(VecInit.fill(entries)(false.B))
    val head = RegInit(0.U(indexWidth.W))
    val tail = RegInit(0.U(indexWidth.W))
    val newestOH = RegInit(1.U(entries.W))
    val count = RegInit(0.U(log2Ceil(entries + 1).W))
    val outstanding = RegInit(false.B)

    private def next(index: UInt): UInt = Mux(index === (entries - 1).U, 0.U, index + 1.U)
    val responseFire = io.store.response.fire
    val followingHead = next(head)
    val replaceOutstanding = responseFire && count > 1.U
    io.enqueue.ready := count < entries.U || responseFire
    io.store.request.valid := valid(head) && !outstanding || replaceOutstanding
    val requestIndex = Mux(replaceOutstanding, followingHead, head)
    io.store.request.bits.paddr := storage(requestIndex).paddr
    io.store.request.bits.data := storage(requestIndex).data
    io.store.request.bits.mask := storage(requestIndex).mask
    io.store.request.bits.size := storage(requestIndex).size
    io.store.request.bits.uncache := storage(requestIndex).uncache
    io.store.response.ready := outstanding
    when(io.store.request.fire =/= responseFire) {
        outstanding := io.store.request.fire
    }
    when(responseFire) {
        valid(head) := false.B
        head := followingHead
    }
    // When a full buffer pops and pushes together, the new tail reuses the old
    // head slot. Keep this write after the response clear so the new entry wins.
    when(io.enqueue.fire) {
        storage(tail) := io.enqueue.bits
        valid(tail) := true.B
        newestOH := UIntToOH(tail, entries)
        tail := next(tail)
    }

    when(io.enqueue.fire =/= responseFire) {
        count := Mux(io.enqueue.fire, count + 1.U, count - 1.U)
    }
    io.empty := count === 0.U
    io.responseError.valid := responseFire && io.store.response.bits.exception.orR
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
            matches(position) := valid(position) && storage(position).paddr(33, 2) === query.bits.wordAddress
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
        io.query(port).response.bits.blocked := false.B
    }

    assert(count <= entries.U)
    assert(PopCount(newestOH) === 1.U)
    when(outstanding) { assert(valid(head)) }
}
