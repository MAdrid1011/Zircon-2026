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
    // Single-half reads need no line assembly. Two small reply descriptors
    // overlap their SRAM latency without adding a data buffer or another
    // memory port. An accepted command retains the shared address/slot regs
    // until its request fires, including recovery upstream.
    val singlePending = RegInit(false.B)
    val singleUpper = Reg(Bool())
    val singleReplies = Module(new Queue(new Bundle {
        val slot = UInt(p.slotWidth.W)
        val upper = Bool()
    }, 2, pipe = true))
    val singleActive = singlePending || singleReplies.io.deq.valid
    val incomingSingle = io.load.request.bits.wide &&
        (io.load.request.bits.halfMask === 1.U || io.load.request.bits.halfMask === 2.U)
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
    val available = (state===idle || finishing) && !singleActive
    val selectStore = io.store.req.valid
    val lowActive = io.store.req.bits.lineMask(31,0).orR
    val highActive = io.store.req.bits.lineMask(63,32).orR
    val storeEmpty = selectStore && !lowActive && !highActive
    val storeSingle = selectStore && !(lowActive && highActive)
    val upperOnly = selectStore && !lowActive && highActive
    val firstValid = Mux(selectStore,io.store.req.valid,io.load.request.valid && !incomingSingle)
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
    io.load.request.ready := available && !selectStore && !incomingSingle
    val startLoad = io.load.request.fire && !incomingSingle
    val startStore = io.store.req.fire
    when(io.memory.response.fire && !singleReplies.io.deq.valid) {
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
    val singleIssue = singlePending && io.memory.request.ready
    val singleResponse = singleReplies.io.deq.valid && io.memory.response.valid
    val reserved = singleReplies.io.count +& singlePending.asUInt
    val singleRoom = reserved < 2.U || singleResponse
    when(incomingSingle) {
        io.load.request.ready := (state===idle || finishing) && !selectStore &&
            (!singlePending || singleIssue) && singleRoom
    }
    when(singlePending) {
        io.memory.request.valid := true.B
        io.memory.request.bits.address := (address | Mux(singleUpper,32.U,0.U)).pad(36)
        io.memory.request.bits.write := false.B
        io.memory.request.bits.data := 0.U
        io.memory.request.bits.strobe := 0.U
    }
    singleReplies.io.enq.valid := singleIssue
    singleReplies.io.enq.bits.slot := slot
    singleReplies.io.enq.bits.upper := singleUpper
    singleReplies.io.deq.ready := io.memory.response.valid
    when(singleReplies.io.deq.valid) {
        io.memory.response.ready := true.B
        io.load.response.valid := io.memory.response.valid
        io.load.response.bits.slot := singleReplies.io.deq.bits.slot
        io.load.response.bits.line := Mux(singleReplies.io.deq.bits.upper,
            Cat(io.memory.response.bits.data,0.U(256.W)),
            Cat(0.U(256.W),io.memory.response.bits.data))
        io.load.response.bits.exception := Mux(io.memory.response.bits.error.orR,5.U,0.U)
    }
    when(singleIssue) {
        assert(singleReplies.io.enq.ready, "Every issued single-half read reserves one reply descriptor")
        singlePending := false.B
    }
    when(io.load.request.fire && incomingSingle) {
        assert(!io.load.request.bits.paddr(5,0).orR && PeScratchpadBridge.matches(io.load.request.bits.paddr))
        address := io.load.request.bits.paddr
        slot := io.load.request.bits.slot
        singleUpper := io.load.request.bits.halfMask === 2.U
        singlePending := true.B
    }
    // Auxiliary replies use the same ordered port and never coexist with an
    // older assembled line or Store response.
    when(singleReplies.io.deq.valid) { assert(state===idle) }
    io.idle := state===idle && !singleActive
    io.loadPending := (state=/=idle && !write) || singleActive
}
