import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FetchQueueIO(p: FrontendParams, dequeueWidth: Int) extends Bundle {
    val enq = Vec(p.fetchWidth, Flipped(Decoupled(new FetchQueueEntry(p))))
    val out = Vec(dequeueWidth, Decoupled(new FetchQueueEntry(p)))
    val flush = Input(Bool())
}

/** Four-wide instruction input and ordered multi-instruction output. */
class FetchQueue(p: FrontendParams, dequeueWidth: Int) extends Module {
    require(dequeueWidth > 0 && dequeueWidth <= p.fetchWidth)

    val io = IO(new FetchQueueIO(p, dequeueWidth))

    val queue = Module(new ClusterIndexFIFO(
        new FetchQueueEntry(p),
        p.fqDepth,
        p.fetchWidth,
        dequeueWidth,
        0,
        0,
        compactEnq = true,
    ))
    queue.io.enq <> io.enq
    io.out <> queue.io.deq
    queue.io.flush := io.flush
}
