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
    val errors = RegInit(0.U(2.W))
    val lastReply = state===replies && firstReturned && io.memory.response.valid
    io.load.response.valid := lastReply && !write
    io.load.response.bits := 0.U.asTypeOf(new DLoadResponse(p))
    io.load.response.bits.wide := true.B
    io.load.response.bits.slot := slot
    io.load.response.bits.line := Cat(io.memory.response.bits.data,half)
    io.load.response.bits.uncache := true.B
    io.load.response.bits.exception := Mux((errors|io.memory.response.bits.error).orR,5.U,0.U)
    io.store.rsp.valid := lastReply && write
    io.store.rsp.bits.exception := Mux((errors|io.memory.response.bits.error).orR,7.U,0.U)
    io.memory.response.ready := !lastReply || !write || io.store.rsp.ready
    val finishing = lastReply && io.memory.response.ready
    val available = state===idle || finishing
    val selectStore = io.store.req.valid
    val firstValid = Mux(selectStore,io.store.req.valid,io.load.request.valid)
    io.memory.request.valid := state===low || state===high || available && firstValid
    val incomingAddress = Mux(selectStore,io.store.req.bits.paddr,io.load.request.bits.paddr)
    io.memory.request.bits.address := Mux(available,incomingAddress,
        address + Mux(state===high,32.U,0.U)).pad(36)
    io.memory.request.bits.write := Mux(available,selectStore,write)
    io.memory.request.bits.data := Mux(available,
        Mux(selectStore,io.store.req.bits.line(255,0),0.U),Mux(write,half,0.U))
    io.memory.request.bits.strobe := Mux(available,
        Mux(selectStore,io.store.req.bits.lineMask(31,0),0.U),Mux(write,highMask,0.U))
    io.store.req.ready := available && io.memory.request.ready
    // Reads acquire their address even when SRAM is stalled. This retains an
    // accepted speculative request until both replies drain across recovery.
    io.load.request.ready := available && !selectStore
    val startLoad = io.load.request.fire
    val startStore = io.store.req.fire
    when(io.memory.response.fire) {
        assert(state===high || state===replies)
        when(!firstReturned) {
            firstReturned := true.B
            errors := io.memory.response.bits.error
            when(!write) { half := io.memory.response.bits.data }
        }.otherwise {
            assert(state===replies)
            state := idle
            firstReturned := false.B
        }
    }
    when(state===low && io.memory.request.fire) { state := high }
    when(state===high && io.memory.request.fire) { state := replies }
    when(startLoad || startStore) {
        assert(!incomingAddress(5,0).orR && PeScratchpadBridge.matches(incomingAddress))
        when(startStore) { assert(io.store.req.bits.wide) }
        state := Mux(io.memory.request.fire,high,low)
        address := incomingAddress
        write := selectStore
        slot := io.load.request.bits.slot
        errors := 0.U
        firstReturned := false.B
        when(startStore) {
            half := io.store.req.bits.line(511,256)
            highMask := io.store.req.bits.lineMask(63,32)
        }
    }
    io.idle := state===idle
    io.loadPending := state=/=idle && !write
}
