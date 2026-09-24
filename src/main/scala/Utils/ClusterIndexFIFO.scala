import chisel3._
import chisel3.util._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/** FIFO IO carries one-hot positions; old pipeline bundles may carry binary positions. */
class ClusterEntry(wOffset: Int, wQidx: Int) extends Bundle {
    val offset = UInt(wOffset.W)
    val qidx = UInt(wQidx.W)
    val high = UInt(1.W)
    def getAge: UInt = high ## offset ## qidx
    def apply(offset: UInt, qidx: UInt, high: UInt): ClusterEntry = {
        val entry = Wire(new ClusterEntry(wOffset, wQidx))
        entry.offset := offset
        entry.qidx := qidx
        entry.high := high
        entry
    }
}

class ClusterIndexFIFOIO[T <: Data](
    gen: T,
    n: Int,
    len: Int,
    ew: Int,
    dw: Int,
    rw: Int,
    ww: Int,
    exposeDeqIndex: Boolean,
)
    extends Bundle {
    val enq = Vec(ew, Flipped(Decoupled(gen)))
    val enqIdx = Output(Vec(ew, new ClusterEntry(len, n)))
    val deq = Vec(dw, Decoupled(gen))
    val deqIdx = if (exposeDeqIndex) Some(Output(Vec(dw, new ClusterEntry(len, n)))) else None
    val ridx = Input(Vec(rw, new ClusterEntry(len, n)))
    val rdata = Output(Vec(rw, gen))
    val widx = Input(Vec(ww, new ClusterEntry(len, n)))
    val wen = Input(Vec(ww, Bool()))
    val wdata = Input(Vec(ww, gen))
    val flush = Input(Bool())
}

/** Banked FIFO with optional stable compaction directly into the bank write muxes.
  * Dequeue transfers must remain an ordered prefix. Random write targets are exclusive.
  */
class ClusterIndexFIFO[T <: Data: TypeTag: ClassTag](
    gen: T,
    num: Int,
    ew: Int,
    dw: Int,
    rw: Int,
    ww: Int,
    isFlst: Boolean = false,
    rstVal: Option[Seq[T]] = None,
    compactEnq: Boolean = false,
    exposeDeqIndex: Boolean = false,
    writePayloadOnFlush: Boolean = false,
    registeredDeq: Boolean = false,
) extends Module {
    require(num > 0 && ew > 0 && dw > 0, "ClusterIndexFIFO depth and transfer widths must be positive")
    require(rw >= 0 && ww >= 0, "ClusterIndexFIFO random port counts must be nonnegative")
    val n: Int = math.max(ew, dw)
    require(num % n == 0, "ClusterIndexFIFO depth must be divisible by bank count")
    require(rstVal.forall(_.size == num), "ClusterIndexFIFO reset contents must match depth")
    val len: Int = num / n
    val io = IO(new ClusterIndexFIFOIO(gen, n, len, ew, dw, rw, ww, exposeDeqIndex))

    private val banks = Seq.tabulate(n) { bank =>
        Module(new IndexFIFO(
            gen,
            len,
            rw,
            ww,
            isFlst,
            rstVal.map(_.slice(bank * len, (bank + 1) * len)),
            writePayloadOnFlush,
            registeredDeq,
        )).io
    }
    private val enqBase = RegInit(1.U(n.W))
    private val deqBase = RegInit(1.U(n.W))
    private val deqData = if (registeredDeq) Some(Reg(Vec(dw, gen))) else None
    private val deqValid = if (registeredDeq) Some(RegInit(VecInit.fill(dw)(false.B))) else None
    private def hasMethod(name: String): Boolean = {
        val member = typeOf[T].member(TermName(name))
        member != NoSymbol && member.isMethod
    }
    private val hasWrite = hasMethod("write")
    private val allEnqReady = banks.map(_.enq.ready).reduce(_ && _)
    private val allDeqValid = if (isFlst) banks.map(_.deq.valid).reduce(_ && _) else true.B

    // Sparse mode moves only a narrow one-hot selector through the input lanes.
    // Payload travels directly from its original input to a single bank mux.
    private val enqSelect = Wire(Vec(ew, UInt(n.W)))
    private val enqBaseNext = Wire(UInt(n.W))
    if (compactEnq) {
        var next = enqBase
        for (lane <- 0 until ew) {
            enqSelect(lane) := next
            next = Mux(io.enq(lane).valid, FIFOUtil.rotate(next, 1), next)
        }
        enqBaseNext := Mux(allEnqReady, next, enqBase)
    } else {
        enqSelect.zipWithIndex.foreach { case (select, lane) => select := FIFOUtil.rotate(enqBase, lane) }
        enqBaseNext := FIFOUtil.advance(enqBase, PopCount(io.enq.map(_.fire)), ew)
        when(!io.flush) {
            FIFOUtil.assertPrefix(
                io.enq.map(_.fire).toSeq,
                "ClusterIndexFIFO enqueue must be a prefix unless compactEnq=true"
            )
        }
    }
    io.enq.foreach(_.ready := allEnqReady)
    for (bank <- 0 until n) {
        val hits = io.enq.indices.map(lane => enqSelect(lane)(bank) && io.enq(lane).valid)
        banks(bank).enq.valid := VecInit(hits).asUInt.orR && allEnqReady
        banks(bank).enq.bits := Mux1H(hits, io.enq.map(_.bits))
    }
    io.enqIdx.zip(enqSelect).foreach { case (idx, select) =>
        idx.qidx := select
        idx.offset := Mux1H(select, banks.map(_.enqIdx))
        idx.high := Mux1H(select, banks.map(_.enqHigh))
    }

    private val deqSelect = Seq.tabulate(dw)(lane => FIFOUtil.rotate(deqBase, lane))
    for (lane <- 0 until dw) {
        val select = deqSelect(lane)
        io.deq(lane).valid := deqValid.map(_(lane)).getOrElse(
            Mux1H(select, banks.map(_.deq.valid)) && allDeqValid
        )
        io.deq(lane).bits := deqData.map(_(lane)).getOrElse(Mux1H(select, banks.map(_.deq.bits)))
        if (exposeDeqIndex) {
            io.deqIdx.get(lane).qidx := select
            io.deqIdx.get(lane).offset := Mux1H(select, banks.map(_.deqIdx))
            io.deqIdx.get(lane).high := Mux1H(select, banks.map(_.deqHigh))
        }
    }
    for (bank <- 0 until n) {
        banks(bank).deq.ready :=
            VecInit((0 until dw).map(lane => deqSelect(lane)(bank) && io.deq(lane).ready)).asUInt.orR && allDeqValid
    }
    when(!io.flush) {
        FIFOUtil.assertPrefix(io.deq.map(_.fire).toSeq, "ClusterIndexFIFO dequeue transfers must be a prefix")
    }
    private val deqBaseNext = FIFOUtil.advance(deqBase, PopCount(io.deq.map(_.fire)), dw)
    if (registeredDeq) {
        val popCount = PopCount(io.deq.map(_.fire))
        val popCountOH = VecInit.tabulate(dw + 1)(count => popCount === count.U)
        for (lane <- 0 until dw) {
            val candidates = (0 to dw).map { candidateCount =>
                val candidateBase = FIFOUtil.rotate(deqBase, candidateCount)
                val select = FIFOUtil.rotate(candidateBase, lane)
                val poppedBanks = if (candidateCount == 0) 0.U(n.W) else
                    (0 until candidateCount).map(offset => FIFOUtil.rotate(deqBase, offset)).reduce(_ | _)
                val bankPopped = (poppedBanks & select).orR
                val candidateData = WireDefault(Mux1H(select, banks.map { bank =>
                    Mux(
                        bankPopped,
                        bank.deqCandidates.get(1).bits,
                        bank.deqCandidates.get(0).bits,
                    )
                }))
                if (ww > 0) {
                    val candidateOffset = Mux1H(select, banks.map { bank =>
                        Mux(
                            bankPopped,
                            bank.deqCandidateIdx.get(1),
                            bank.deqCandidateIdx.get(0),
                        )
                    })
                    val writeHits = io.wen.indices.map { port =>
                        io.wen(port) && (io.widx(port).qidx & select).orR &&
                            (io.widx(port).offset & candidateOffset).orR
                    }
                    assert(PopCount(writeHits) <= 1.U, "Registered dequeue entry cannot receive multiple writes")
                    when(VecInit(writeHits).asUInt.orR) {
                        val update = Mux1H(writeHits, io.wdata)
                        if (hasWrite) candidateData.asInstanceOf[{ def write(data: T): Unit }].write(update)
                        else candidateData := update
                    }
                }
                val candidateValid = Mux1H(select, banks.map { bank =>
                    Mux(
                        bankPopped,
                        bank.deqCandidates.get(1).valid,
                        bank.deqCandidates.get(0).valid,
                    )
                })
                candidateValid -> candidateData
            }
            when(io.flush) {
                deqValid.get(lane) := false.B
            }.otherwise {
                deqValid.get(lane) := Mux1H(popCountOH, candidates.map(_._1))
                deqData.get(lane) := Mux1H(popCountOH, candidates.map(_._2))
            }
        }
    }
    when(io.flush) {
        if (isFlst) {
            enqBase := enqBaseNext
            // Include returns accepted on this edge, matching each bank's tailNext.
            deqBase := enqBaseNext
        } else {
            enqBase := 1.U
            deqBase := 1.U
        }
    }.otherwise {
        enqBase := enqBaseNext
        deqBase := deqBaseNext
    }

    banks.foreach { bank =>
        bank.flush := io.flush
        bank.ridx := io.ridx.map(_.offset)
        bank.widx := io.widx.map(_.offset)
        bank.wdata := io.wdata
    }
    for (port <- 0 until rw) {
        io.rdata(port) := Mux1H(io.ridx(port).qidx, banks.map(_.rdata(port)))
    }
    for (port <- 0 until ww) {
        when(io.wen(port) && !io.flush) {
            assert(PopCount(io.widx(port).qidx) === 1.U, "ClusterIndexFIFO write bank must be one-hot")
        }
        for (bank <- 0 until n) banks(bank).wen(port) := io.wen(port) && io.widx(port).qidx(bank)
    }
}

object ClusterIndexFIFO {
    def apply[T <: Data: TypeTag: ClassTag](gen: T, num: Int, ew: Int, dw: Int, rw: Int, ww: Int): ClusterIndexFIFO[T] =
        new ClusterIndexFIFO(gen, num, ew, dw, rw, ww)
}
