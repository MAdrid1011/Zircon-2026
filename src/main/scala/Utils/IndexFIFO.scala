import chisel3._
import chisel3.util._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/** Constant rotations are wiring, including the single-entry case. */
object FIFOUtil {
    def rotate(value: UInt, amount: Int): UInt = {
        val width = value.getWidth
        val shift = amount % width
        if (shift == 0) value else Cat(value(width - shift - 1, 0), value(width - 1, width - shift))
    }

    def advance(value: UInt, count: UInt, maxCount: Int): UInt =
        Mux1H((0 to maxCount).map(i => (count === i.U) -> rotate(value, i)))

    def assertPrefix(fires: Seq[Bool], message: String): Unit =
        fires.indices.drop(1).foreach(i => assert(!fires(i) || fires(i - 1), message))
}

class IndexFIFOIO[T <: Data](gen: T, n: Int, rw: Int, ww: Int) extends Bundle {
    val enq = Flipped(Decoupled(gen))
    val enqIdx = Output(UInt(n.W))
    val enqHigh = Output(Bool())
    val deq = Decoupled(gen)
    val deqIdx = Output(UInt(n.W))
    val deqHigh = Output(Bool())
    val ridx = Input(Vec(rw, UInt(n.W)))
    val rdata = Output(Vec(rw, gen))
    val widx = Input(Vec(ww, UInt(n.W)))
    val wen = Input(Vec(ww, Bool()))
    val wdata = Input(Vec(ww, gen))
    val flush = Input(Bool())
}

/** One-hot indexed bank. FreeList mode restores the allocation head to the recycle tail. */
class IndexFIFO[T <: Data: TypeTag: ClassTag](
    gen: T,
    n: Int,
    rw: Int,
    ww: Int,
    isFlst: Boolean = false,
    rstVal: Option[Seq[T]] = None,
) extends Module {
    require(n > 0, "IndexFIFO depth must be positive")
    require(rw >= 0 && ww >= 0, "IndexFIFO random port counts must be nonnegative")
    require(rstVal.forall(_.size == n), "IndexFIFO reset contents must match depth")
    val io = IO(new IndexFIFOIO(gen, n, rw, ww))

    // Keep the 2024 field-update hooks for ROBEntry/BDBEntry migration.
    private def hasMethod(name: String): Boolean = {
        val member = typeOf[T].member(TermName(name))
        member != NoSymbol && member.isMethod
    }
    private val hasEnqueue = hasMethod("enqueue")
    private val hasWrite = hasMethod("write")
    private val q = RegInit(if (isFlst && rstVal.isDefined) VecInit(rstVal.get)
    else VecInit.fill(n)(0.U.asTypeOf(gen)))
    private val head = RegInit(1.U(n.W))
    private val tail = RegInit(1.U(n.W))
    private val headHigh = RegInit(false.B)
    private val tailHigh = RegInit(false.B)
    private val empty = RegInit((!isFlst).B)
    private val full = RegInit(false.B)

    // Outputs describe the pre-edge state even during flush. This avoids a path
    // from flush back into a caller's commit-valid / redirect decision.
    io.enq.ready := (if (isFlst) true.B else !full)
    io.deq.valid := !empty
    val push = io.enq.fire
    val pop = io.deq.fire
    val headNext = Mux(pop, FIFOUtil.rotate(head, 1), head)
    val tailNext = Mux(push, FIFOUtil.rotate(tail, 1), tail)
    val headHighNext = headHigh ^ (pop && head(n - 1))
    val tailHighNext = tailHigh ^ (push && tail(n - 1))

    when(io.flush) {
        if (isFlst) {
            head := tailNext
            tail := tailNext
            headHigh := tailHighNext
            tailHigh := tailHighNext
            empty := false.B
        } else {
            head := 1.U
            tail := 1.U
            headHigh := false.B
            tailHigh := false.B
            empty := true.B
        }
        full := false.B
    }.otherwise {
        head := headNext
        tail := tailNext
        headHigh := headHighNext
        tailHigh := tailHighNext
        when(push =/= pop) {
            empty := pop && headNext === tailNext
            if (!isFlst) full := push && headNext === tailNext
        }
    }

    io.enqIdx := tail
    io.enqHigh := tailHigh
    io.deqIdx := head
    io.deqHigh := headHigh
    io.deq.bits := Mux1H(head, q)
    io.rdata.zip(io.ridx).foreach { case (data, idx) => data := Mux1H(idx, q) }

    for (port <- 0 until ww) {
        when(io.wen(port) && !io.flush) {
            assert(PopCount(io.widx(port)) === 1.U, "IndexFIFO write offset must be one-hot")
        }
    }
    q.zipWithIndex.foreach { case (entry, row) =>
        // A FreeList accepts committed returns on its recovery edge. An ordinary
        // queue discards enqueue and random writes on its clear edge.
        when(push && tail(row) && (if (isFlst) true.B else !io.flush)) {
            if (hasEnqueue) entry.asInstanceOf[{ def enqueue(data: T): Unit }].enqueue(io.enq.bits)
            else entry := io.enq.bits
        }
        if (ww > 0) {
            val hits = io.wen.zip(io.widx).map { case (wen, idx) => wen && idx(row) }
            when(!io.flush) {
                assert(PopCount(hits) <= 1.U, "IndexFIFO simultaneous random writes must target distinct entries")
            }
            when(VecInit(hits).asUInt.orR && !io.flush) {
                val data = Mux1H(hits, io.wdata)
                // Preserve field-level precedence over enqueue on the same row.
                if (hasWrite) entry.asInstanceOf[{ def write(data: T): Unit }].write(data)
                else entry := data
            }
        }
    }
}
