import chisel3._
import chisel3.util._
import ZirconConfig.DCacheParams

class PeScratchpadPort extends Bundle {
    val request = Decoupled(new Bundle {
        val address = UInt(36.W)
        val write = Bool()
        val data = UInt(256.W)
        val strobe = UInt(32.W)
    })
    val response = Flipped(Decoupled(new Bundle {
        val data = UInt(256.W)
        val error = UInt(2.W)
    }))
}
object PeScratchpadBridge {
    def matches(address: UInt): Bool = address(33,20)==="hb00".U
}

/** Cache-line requests share a 256-bit SRAM port. One 32-byte register holds
  * either a Store's upper half or a Load's returned lower half. */
class PeScratchpadBridge(p: DCacheParams = DCacheParams(8,directPhysical=true)) extends Module {
    val io = IO(new Bundle {
        val load = new Bundle {
            val request = Flipped(Decoupled(new DLoadRequest(p)))
            val response = Valid(new DLoadResponse(p))
        }
        val store = new DCacheStoreIO
        val memory = new PeScratchpadPort
        val idle = Output(Bool())
        val loadPending = Output(Bool())
    })
    val idle :: low :: high :: replies :: Nil = Enum(4)
    val state = RegInit(idle)
    val write = Reg(Bool())
    val address = Reg(UInt(34.W))
    val slot = Reg(UInt(p.slotWidth.W))
    val half = Reg(UInt(256.W))
    val highMask = Reg(UInt(32.W))
    val firstReturned = RegInit(false.B)
    val single = RegInit(false.B)
    val empty = RegInit(false.B)
    val errors = RegInit(0.U(2.W))
    // Two reply descriptors overlap full-line and single-half reads. Full
    // lines reuse the existing half register for assembly; payload storage
    // and the SRAM port do not grow. Accepted addresses survive recovery.
    val readPending = RegInit(false.B)
    val readUpper = Reg(Bool())
    val readFull = Reg(Bool())
    val issueHigh = RegInit(false.B)
    val returnHigh = RegInit(false.B)
    val readReplies = Module(new Queue(new Bundle {
        val slot = UInt(p.slotWidth.W)
        val upper = Bool()
        val full = Bool()
    }, 2, pipe = true))
    val readActive = readPending || readReplies.io.deq.valid
    val incomingStream = io.load.request.bits.wide
    val lastReply = state===replies && (empty || (single || firstReturned) && io.memory.response.valid)
    io.load.response.valid := lastReply && !write
    io.load.response.bits := 0.U.asTypeOf(new DLoadResponse(p))
    io.load.response.bits.wide := true.B
    io.load.response.bits.slot := slot
    io.load.response.bits.line := Cat(io.memory.response.bits.data,half)
    io.load.response.bits.uncache := true.B
    io.load.response.bits.exception := Mux((errors|io.memory.response.bits.error).orR,5.U,0.U)
    io.store.rsp.valid := lastReply && write
    io.store.rsp.bits.exception := Mux(!empty && (errors|io.memory.response.bits.error).orR,7.U,0.U)
    io.memory.response.ready := !lastReply || !write || io.store.rsp.ready
    val finishing = lastReply && io.memory.response.ready
    val available = (state===idle || finishing) && !readActive
    val selectStore = io.store.req.valid
    val lowActive = io.store.req.bits.lineMask(31,0).orR
    val highActive = io.store.req.bits.lineMask(63,32).orR
    val storeEmpty = selectStore && !lowActive && !highActive
    val storeSingle = selectStore && !(lowActive && highActive)
    val upperOnly = selectStore && !lowActive && highActive
    val firstValid = Mux(selectStore,io.store.req.valid,io.load.request.valid && !incomingStream)
    io.memory.request.valid := state===low || state===high || available && firstValid && !storeEmpty
    val incomingAddress = Mux(selectStore,io.store.req.bits.paddr,io.load.request.bits.paddr)
    // A masked Store still has one logical completion. Its live half alone
    // owns an SRAM request/reply; an all-masked Store completes without I/O.
    // Loads retain the full-line contract and always drain both replies.
    val firstAddress = incomingAddress | Mux(upperOnly,32.U,0.U)
    io.memory.request.bits.address := Mux(available,firstAddress,
        address + Mux(state===high,32.U,0.U)).pad(36)
    io.memory.request.bits.write := Mux(available,selectStore,write)
    io.memory.request.bits.data := Mux(available,
        Mux(selectStore,Mux(upperOnly,io.store.req.bits.line(511,256),io.store.req.bits.line(255,0)),0.U),Mux(write,half,0.U))
    io.memory.request.bits.strobe := Mux(available,
        Mux(selectStore,Mux(upperOnly,io.store.req.bits.lineMask(63,32),io.store.req.bits.lineMask(31,0)),0.U),Mux(write,highMask,0.U))
    io.store.req.ready := available && (storeEmpty || io.memory.request.ready)
    // Reads acquire their address even when SRAM is stalled. This retains an
    // accepted speculative request until both replies drain across recovery.
    io.load.request.ready := available && !selectStore && !incomingStream
    val startLoad = io.load.request.fire && !incomingStream
    val startStore = io.store.req.fire
    when(io.memory.response.fire && !readReplies.io.deq.valid) {
        assert(state===high || state===replies)
        when(single) {
            assert(state===replies)
            state := idle
            firstReturned := false.B
        }.elsewhen(!firstReturned) {
            firstReturned := true.B
            errors := io.memory.response.bits.error
            when(!write) { half := io.memory.response.bits.data }
        }.otherwise {
            assert(state===replies)
            state := idle
            firstReturned := false.B
        }
    }
    when(empty && state===replies && io.store.rsp.fire) { state := idle }
    when(state===low && io.memory.request.fire) { state := Mux(single,replies,high) }
    when(state===high && io.memory.request.fire) { state := replies }
    when(startLoad || startStore) {
        assert(!incomingAddress(5,0).orR && PeScratchpadBridge.matches(incomingAddress))
        when(startStore) { assert(io.store.req.bits.wide) }
        state := Mux(storeEmpty,replies,Mux(io.memory.request.fire,Mux(storeSingle,replies,high),low))
        address := incomingAddress
        write := selectStore
        slot := io.load.request.bits.slot
        errors := 0.U
        firstReturned := false.B
        single := storeSingle
        empty := storeEmpty
        when(startStore) {
            half := io.store.req.bits.line(511,256)
            highMask := io.store.req.bits.lineMask(63,32)
        }
    }
    val readIssue = readPending && io.memory.request.ready
    val finalIssue = readIssue && (!readFull || issueHigh)
    val readResponse = readReplies.io.deq.valid && io.memory.response.valid &&
        (!readReplies.io.deq.bits.full || returnHigh)
    // After a full line's first request, its queued descriptor already owns
    // the second half; do not reserve a second descriptor for that same line.
    val reserved = readReplies.io.count +& (readPending && !issueHigh).asUInt
    val readRoom = reserved < 2.U || readResponse
    when(incomingStream) {
        io.load.request.ready := (state===idle || finishing) && !selectStore &&
            (!readPending || finalIssue) && readRoom
    }
    when(readPending) {
        io.memory.request.valid := true.B
        io.memory.request.bits.address := (address | Mux(readUpper || issueHigh,32.U,0.U)).pad(36)
        io.memory.request.bits.write := false.B
        io.memory.request.bits.data := 0.U
        io.memory.request.bits.strobe := 0.U
    }
    readReplies.io.enq.valid := readIssue && !issueHigh
    readReplies.io.enq.bits.slot := slot
    readReplies.io.enq.bits.upper := readUpper
    readReplies.io.enq.bits.full := readFull
    readReplies.io.deq.ready := readResponse
    when(readReplies.io.deq.valid) {
        io.memory.response.ready := true.B
        io.load.response.valid := readResponse
        io.load.response.bits.slot := readReplies.io.deq.bits.slot
        io.load.response.bits.line := Mux(readReplies.io.deq.bits.full,
            Cat(io.memory.response.bits.data,half),Mux(readReplies.io.deq.bits.upper,
                Cat(io.memory.response.bits.data,0.U(256.W)),
                Cat(0.U(256.W),io.memory.response.bits.data)))
        val accumulated = Mux(readReplies.io.deq.bits.full && returnHigh,errors,0.U) |
            io.memory.response.bits.error
        io.load.response.bits.exception := Mux(accumulated.orR,5.U,0.U)
        when(io.memory.response.valid) {
            when(readReplies.io.deq.bits.full && !returnHigh) {
                half := io.memory.response.bits.data
                errors := io.memory.response.bits.error
                returnHigh := true.B
            }.otherwise { returnHigh := false.B }
        }
    }
    when(readIssue) {
        when(!issueHigh) {
            assert(readReplies.io.enq.ready, "Every issued line reserves one reply descriptor")
        }
        when(readFull && !issueHigh) { issueHigh := true.B }
            .otherwise { readPending := false.B; issueHigh := false.B }
    }
    when(io.load.request.fire && incomingStream) {
        assert(!io.load.request.bits.paddr(5,0).orR && PeScratchpadBridge.matches(io.load.request.bits.paddr))
        address := io.load.request.bits.paddr
        slot := io.load.request.bits.slot
        readUpper := io.load.request.bits.halfMask === 2.U
        readFull := io.load.request.bits.halfMask === 0.U || io.load.request.bits.halfMask === 3.U
        issueHigh := false.B
        readPending := true.B
    }
    // Auxiliary replies use the same ordered port and never coexist with an
    // older assembled line or Store response.
    when(readReplies.io.deq.valid) { assert(state===idle) }
    assert(reserved <= 2.U)
    io.idle := state===idle && !readActive
    io.loadPending := (state=/=idle && !write) || readActive
}
