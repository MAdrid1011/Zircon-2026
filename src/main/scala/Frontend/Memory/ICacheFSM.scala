// Copied from Zircon-2024 b9f7b2b, then adapted for explicit request and response handshakes.
import chisel3._
import chisel3.util._

class ICacheFSMCacheIO extends Bundle {
    val rreq = Input(Bool())
    val uncache = Input(Bool())
    val fault = Input(Bool())
    val hit = Input(UInt(2.W))
    val lru = Input(UInt(2.W))
    val flush = Input(Bool())
    val stall = Input(Bool())
    val consumed = Input(Bool())
    val responseReady = Input(Bool())
    val cmiss = Output(Bool())
    val tagvWe = Output(UInt(2.W))
    val memWe = Output(UInt(2.W))
    val addrOH = Output(UInt(3.W))
    val r1H = Output(UInt(2.W))
    val lruUpd = Output(UInt(2.W))
    val start = Output(Bool())
    val ready = Output(Bool())
}

class ICacheFSML2IO extends Bundle {
    val rreq = Output(Bool())
    val ready = Input(Bool())
    val rrsp = Input(Bool())
    val error = Input(Bool())
    val more = Input(Bool())
    val pending = Output(Bool())
}

class ICacheFSM extends Module {
    val io = IO(new Bundle {
        val cc = new ICacheFSMCacheIO
        val l2 = new ICacheFSML2IO
    })

    // Keep the original miss, installation and two-phase RAM recovery sequence.
    val mIdle :: mMiss :: mRefill :: mWait :: Nil = Enum(4)
    val mState = RegInit(mIdle)
    val lruReg = Reg(UInt(2.W))
    val readIssued = RegInit(false.B)
    val requestHeld = RegInit(false.B)
    val waited = RegNext(mState === mWait, false.B)

    io.cc.cmiss := false.B
    io.cc.tagvWe := 0.U
    io.cc.memWe := 0.U
    io.cc.addrOH := Mux(io.cc.stall, 2.U, 1.U)
    io.cc.r1H := Mux(mState === mWait, 2.U, 1.U)
    io.cc.lruUpd := 0.U
    io.cc.start := false.B
    io.cc.ready := mState === mIdle
    io.l2.rreq := false.B
    io.l2.pending := readIssued

    switch(mState) {
        is(mIdle) {
            when(io.cc.rreq && !io.cc.flush && !io.cc.fault) {
                when(io.cc.uncache || !io.cc.hit.orR) {
                    mState := mMiss
                    lruReg := io.cc.lru
                    io.cc.start := true.B
                }.elsewhen(io.cc.consumed) {
                    io.cc.lruUpd := ~io.cc.hit
                }
            }
        }
        is(mMiss) {
            // Once presented, retain the lower request through backpressure and flush.
            io.l2.rreq := !readIssued && (requestHeld || (io.cc.rreq && !io.cc.flush))
            when(io.l2.rreq) {
                requestHeld := !io.l2.ready
                when(io.l2.ready) { readIssued := true.B }
            }
            when((!io.cc.rreq || io.cc.flush) && !requestHeld && !readIssued) { mState := mWait }
            when(io.l2.rrsp) {
                readIssued := false.B
                when(!io.l2.more) {
                    // A redirected cacheable fetch no longer needs a response, but its returned line remains useful.
                    mState := Mux(io.cc.uncache || io.l2.error, mWait, mRefill)
                }
            }
        }
        is(mRefill) {
            mState := mWait
            io.cc.addrOH := 4.U
            io.cc.lruUpd := ~lruReg
            io.cc.tagvWe := lruReg
            io.cc.memWe := lruReg
        }
        is(mWait) {
            // Restore the held IF1 address before releasing miss. Hold the IF2 result until consumed.
            io.cc.cmiss := !waited
            io.cc.ready := waited && (!io.cc.rreq || io.cc.responseReady || io.cc.flush)
            io.cc.addrOH := Mux(io.cc.ready && !io.cc.stall, 1.U, 2.U)
            when(io.cc.ready) { mState := mIdle }
        }
    }
}
