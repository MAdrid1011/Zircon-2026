import chisel3._
import chisel3.util._

class PRegFreeListIO(numPhys: Int, renameWidth: Int, commitWidth: Int) extends Bundle {
    private val indexWidth = log2Ceil(numPhys)
    val request = Input(Vec(renameWidth, Bool()))
    val previewAllocate = Input(Vec(renameWidth, Bool()))
    // Q-side Rename writes accepted by Dispatch on this edge.
    val allocate = Input(Vec(renameWidth, Bool()))
    val available = Output(Bool())
    val availablePrefix = Output(UInt(renameWidth.W))
    // Bit n grants n+1 destinations after the held Q-side preview.
    val capacityPrefix = Output(UInt(renameWidth.W))
    val prd = Output(Vec(renameWidth, UInt(indexWidth.W)))
    val release = Input(Vec(commitWidth, Valid(UInt(indexWidth.W))))
    val restore = Input(Bool())
}

/**
  * Physical-register free list with a write-first allocation view.
  *
 * The Q-side preview and the D-side request share one edge. `prd` is computed
 * as if the held Dispatch group were accepted; only actual acceptance moves
 * the ring head. A stalled group therefore cannot consume physical tags.
  */
class PRegFreeList(numPhys: Int, renameWidth: Int, commitWidth: Int, numArch: Int = 32,
                   maskUnusedTags: Boolean = true) extends Module {
    private val indexWidth = log2Ceil(numPhys)
    private val depth = numPhys - numArch
    require(depth >= math.max(renameWidth, commitWidth))

    val io = IO(new PRegFreeListIO(numPhys, renameWidth, commitWidth))
    private val banks = (math.max(renameWidth, commitWidth) to depth).find(depth % _ == 0).get
    private val rows = depth / banks
    private val lookahead = renameWidth * 2
    private val extraReads = math.max(0, lookahead - banks)
    val free = Module(new ClusterIndexFIFO(
        UInt(indexWidth.W),
        depth,
        commitWidth,
        banks,
        extraReads,
        0,
        isFlst = true,
        rstVal = Some((0 until depth).map { index =>
            (numArch + (index % rows) * banks + index / rows).U(indexWidth.W)
        }),
        compactEnq = true,
        exposeDeqIndex = true,
        partialFreeListDeq = true,
    ))
    free.io.flush := io.restore
    for (lane <- 0 until commitWidth) {
        free.io.enq(lane).valid := io.release(lane).valid
        free.io.enq(lane).bits := io.release(lane).bits
    }
    val count = RegInit(depth.U(log2Ceil(depth + 1).W))

    val allocateCount = PopCount(io.allocate)
    val previewCount = PopCount(io.previewAllocate)
    val releaseCount = PopCount(io.release.map(_.valid))
    val postWritebackCount = count - allocateCount
    for (lane <- 0 until banks) {
        free.io.deq(lane).ready := (if (lane < renameWidth) allocateCount > lane.U else false.B)
    }
    for (extra <- 0 until extraReads) {
        val index = free.io.deqIdx.get(extra)
        free.io.ridx(extra).qidx := index.qidx
        free.io.ridx(extra).offset := FIFOUtil.rotate(index.offset, 1)
        free.io.ridx(extra).high := index.high
    }
    val entryAtOffset = free.io.deq.map(_.bits).toSeq ++ free.io.rdata.toSeq

    // Occupancy and the held preview are available before instruction decode.
    // Select constant thresholds here; late requests only select capacity bits.
    val previewClasses = VecInit((0 to renameWidth).map(previewCount === _.U))
    val capacity = VecInit((1 to renameWidth).map { destinations =>
        Mux1H(previewClasses, (0 to renameWidth).map { held =>
            count >= (held + destinations).U
        })
    })
    io.capacityPrefix := capacity.asUInt
    val emptyRequestCapacity = if (renameWidth == 1) true.B
        else Mux1H(previewClasses, (0 to renameWidth).map(held => count >= held.U))
    def permits(requests: Seq[Bool]): Bool = {
        val needed = PopCount(requests)
        Mux1H((0 to requests.size).map(needed === _.U),
            Seq(emptyRequestCapacity) ++ capacity.take(requests.size))
    }
    io.available := permits(io.request.toSeq)
    io.availablePrefix := VecInit((0 until renameWidth).map { lane =>
        permits(io.request.take(lane + 1))
    }).asUInt
    for (lane <- 0 until renameWidth) {
        val older = if (lane == 0) 0.U else PopCount(io.request.take(lane))
        val offset = previewCount +& older
        val select = (0 until lookahead).map { position =>
            (if (maskUnusedTags) io.request(lane) else true.B) && offset === position.U
        }
        io.prd(lane) := Mux1H(select, entryAtOffset.take(lookahead))
    }

    when(io.restore) {
        count := depth.U
    }.otherwise {
        count := postWritebackCount + releaseCount
    }

    when(!io.restore) {
        assert(count >= allocateCount, "FreeList Q-side allocation underflow")
        assert(postWritebackCount + releaseCount <= depth.U, "FreeList occupancy overflow")
    }
    for (lane <- 0 until renameWidth) {
        when(io.allocate(lane)) {
            assert(count >= PopCount(io.allocate.take(lane + 1)), "FreeList allocation exceeds physical registers")
        }
    }
}
