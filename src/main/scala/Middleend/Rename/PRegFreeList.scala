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
class PRegFreeList(numPhys: Int, renameWidth: Int, commitWidth: Int) extends Module {
    private val indexWidth = log2Ceil(numPhys)
    private val depth = numPhys - 32
    private val pointerWidth = math.max(1, log2Ceil(depth))
    require(depth >= math.max(renameWidth, commitWidth))

    val io = IO(new PRegFreeListIO(numPhys, renameWidth, commitWidth))

    def advance(base: UInt, amount: UInt, maximum: Int): UInt = {
        Mux1H((0 to maximum).map { step =>
            val sum = base +& step.U
            val wrapped = Mux(sum >= depth.U, sum - depth.U, sum)
            (amount === step.U) -> wrapped(pointerWidth - 1, 0)
        })
    }

    val entries = RegInit(VecInit((32 until numPhys).map(_.U(indexWidth.W))))
    val head = RegInit(0.U(pointerWidth.W))
    val tail = RegInit(0.U(pointerWidth.W))
    val count = RegInit(depth.U(log2Ceil(depth + 1).W))

    val allocateCount = PopCount(io.allocate)
    val previewCount = PopCount(io.previewAllocate)
    val releaseCount = PopCount(io.release.map(_.valid))
    val postWritebackCount = count - allocateCount
    val acceptedCountOH = (0 to renameWidth).map(previewCount === _.U)
    val entryAtOffset = (0 until renameWidth * 2).map { offset =>
        entries(advance(head, offset.U, renameWidth * 2 - 1))
    }

    val requestCount = PopCount(io.request)
    io.available := Mux1H(acceptedCountOH.zipWithIndex.map { case (accepted, n) =>
        accepted -> (count >= (n.U +& requestCount))
    })
    io.availablePrefix := VecInit((0 until renameWidth).map { lane =>
        val needed = PopCount(io.request.take(lane + 1))
        Mux1H(acceptedCountOH.zipWithIndex.map { case (accepted, n) =>
            accepted -> (count >= (n.U +& needed))
        })
    }).asUInt
    for (lane <- 0 until renameWidth) {
        val older = if (lane == 0) 0.U else PopCount(io.request.take(lane))
        val candidate = (0 to renameWidth).map { accepted =>
            Mux1H((0 to lane).map { rank =>
                (older === rank.U) -> entryAtOffset(accepted + rank)
            })
        }
        io.prd(lane) := Mux1H(acceptedCountOH.zip(candidate).map { case (select, tag) =>
            (select && io.request(lane)) -> tag
        })
    }

    for (lane <- 0 until commitWidth) {
        val older = if (lane == 0) 0.U else PopCount(io.release.take(lane).map(_.valid))
        val index = advance(tail, older, commitWidth - 1)
        when(io.release(lane).valid) {
            entries(index) := io.release(lane).bits
        }
    }

    val headNext = advance(head, allocateCount, renameWidth)
    val tailNext = advance(tail, releaseCount, commitWidth)

    when(io.restore) {
        head := tailNext
        tail := tailNext
        count := depth.U
    }.otherwise {
        head := headNext
        tail := tailNext
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
