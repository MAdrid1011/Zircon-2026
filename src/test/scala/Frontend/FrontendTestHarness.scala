import chisel3._
import ZirconConfig.{FrontendParams, ICacheParams}

/** Connect the production frontend to the real Commit-owned FTQ for joint verification. */
class FrontendTestHarness(p: FrontendParams, c: ICacheParams) extends Module {
    val io = IO(new Bundle {
        val mmu = new IMMUIO(p)
        val mem = new FrontendMemoryIO(c)
        val middle = new FrontendMiddleIO(p)
        val commit = new FtqCommitIO(p)
        val observe = if (p.observe) Some(new FrontendObserveIO(p)) else None
    })
    val frontend = Module(new Frontend(p, c, tlbEnabled = false))
    val ftq = Module(new FetchTargetQueue(p))
    io.mmu <> frontend.io.mmu
    io.mem <> frontend.io.mem
    io.middle <> frontend.io.middle
    ftq.io.frontend <> frontend.io.commit.ftq
    ftq.io.commit <> io.commit
    frontend.io.commit.rob.redirect <> io.commit.redirect
    if (p.observe) io.observe.get := frontend.io.observe.get
}
