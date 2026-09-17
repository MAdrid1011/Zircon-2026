import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

/** The middle end accepts complete masked packets; global recovery suppresses old output. */
class FetchQueue(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val enq = Flipped(Decoupled(new FrontendPackage(p)))
        val out = Decoupled(new FrontendPackage(p))
        val flush = Input(Bool())
    })
    val queue = Module(new ClusterIndexFIFO(new FrontendPackage(p), p.fqDepth, 1, 1, 0, 0))
    queue.io.flush := io.flush
    queue.io.enq(0).bits := io.enq.bits
    queue.io.enq(0).valid := io.enq.valid && !io.flush
    io.enq.ready := queue.io.enq(0).ready && !io.flush
    io.out.valid := queue.io.deq(0).valid && !io.flush
    io.out.bits := queue.io.deq(0).bits
    queue.io.deq(0).ready := io.out.ready && !io.flush
}
