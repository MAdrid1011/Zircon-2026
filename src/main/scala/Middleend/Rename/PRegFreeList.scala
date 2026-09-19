import chisel3._
import chisel3.util._

class PRegFreeListIO(numPhys: Int, renameWidth: Int, commitWidth: Int) extends Bundle {
    private val indexWidth = log2Ceil(numPhys)
    val request = Input(Vec(renameWidth, Bool()))
    val allocate = Input(Vec(renameWidth, Bool()))
    val available = Output(Bool())
    val availablePrefix = Output(UInt(renameWidth.W))
    val prd = Output(Vec(renameWidth, UInt(indexWidth.W)))
    val release = Input(Vec(commitWidth, Valid(UInt(indexWidth.W))))
    val restore = Input(Bool())
}

/** Resource-exchange ring derived from Zircon-2024.
  *
  * Divisible geometries keep the original banked allocation order and compact
  * wiring. Other geometries use a flat ring so physical-register count and
  * pipeline width can vary independently. Both forms restore speculative state
  * by moving the allocation head to the post-commit tail.
  */
class PRegFreeList(numPhys: Int, renameWidth: Int, commitWidth: Int) extends Module {
    private val width = log2Ceil(numPhys)
    private val depth = numPhys - 32
    private val banks = math.max(renameWidth, commitWidth)
    require(depth >= banks)

    val io = IO(new PRegFreeListIO(numPhys, renameWidth, commitWidth))

    if (depth % banks == 0) {
        val fList = Module(new ClusterIndexFIFO(
            UInt(width.W),
            depth,
            commitWidth,
            renameWidth,
            0,
            0,
            isFlst = true,
            rstVal = Some((32 until numPhys).map(_.U(width.W))),
            compactEnq = false,
        ))
        io.available := fList.io.deq.map(_.valid).reduce(_ && _)
        io.availablePrefix := Fill(renameWidth, io.available)

        var next = 1.U(renameWidth.W)
        val select = io.request.map { request =>
            val hit = Mux(request, next, 0.U(renameWidth.W))
            next = Mux(request, FIFOUtil.rotate(next, 1), next)
            hit
        }
        io.prd.zip(select).foreach { case (prd, mask) =>
            prd := Mux1H(mask, fList.io.deq.map(_.bits))
        }
        fList.io.deq.zipWithIndex.foreach { case (port, index) =>
            port.ready := select.zip(io.allocate).map { case (lane, allocate) =>
                lane(index) && allocate
            }.reduce(_ || _)
        }

        var nextReturn = 1.U(commitWidth.W)
        val returnSelect = io.release.map { release =>
            val hit = Mux(release.valid, nextReturn, 0.U(commitWidth.W))
            nextReturn = Mux(release.valid, FIFOUtil.rotate(nextReturn, 1), nextReturn)
            hit
        }
        fList.io.enq.zipWithIndex.foreach { case (port, index) =>
            val hits = returnSelect.map(_(index))
            port.valid := hits.reduce(_ || _)
            port.bits := Mux1H(hits, io.release.map(_.bits))
        }
        fList.io.flush := io.restore
    } else {
        val pointerWidth = math.max(1, log2Ceil(depth))

        def advance(base: UInt, amount: UInt, maximum: Int): UInt = {
            Mux1H((0 to maximum).map { step =>
                val sum = base +& step.U
                val wrapped = Mux(sum >= depth.U, sum - depth.U, sum)
                (amount === step.U) -> wrapped(pointerWidth - 1, 0)
            })
        }

        val entries = RegInit(VecInit((32 until numPhys).map(_.U(width.W))))
        val head = RegInit(0.U(pointerWidth.W))
        val tail = RegInit(0.U(pointerWidth.W))
        val count = RegInit(depth.U(log2Ceil(depth + 1).W))

        val requestCount = PopCount(io.request)
        val allocateCount = PopCount(io.allocate)
        val releaseCount = PopCount(io.release.map(_.valid))
        io.available := count >= requestCount
        io.availablePrefix := VecInit((0 until renameWidth).map { lane =>
            count >= PopCount(io.request.take(lane + 1))
        }).asUInt

        for (lane <- 0 until renameWidth) {
            val rank = if (lane == 0) 0.U else PopCount(io.request.take(lane))
            val index = advance(head, rank, renameWidth - 1)
            io.prd(lane) := Mux(io.request(lane), entries(index), 0.U)
        }
        for (lane <- 0 until commitWidth) {
            val rank = if (lane == 0) 0.U else PopCount(io.release.take(lane).map(_.valid))
            val index = advance(tail, rank, commitWidth - 1)
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
            count := count + releaseCount - allocateCount
        }
        when(!io.restore) {
            assert(count + releaseCount >= allocateCount, "FreeList occupancy underflow")
            assert(count + releaseCount - allocateCount <= depth.U, "FreeList occupancy overflow")
        }
    }

    for (lane <- 0 until renameWidth) {
        assert(!io.allocate(lane) || io.request(lane), "FreeList allocation must name a requested lane")
        when(io.allocate(lane)) {
            assert(io.availablePrefix(lane), "FreeList allocation exceeds available physical registers")
        }
    }
    assert(PopCount(io.allocate) <= PopCount(io.request), "FreeList cannot allocate more registers than requested")
}
