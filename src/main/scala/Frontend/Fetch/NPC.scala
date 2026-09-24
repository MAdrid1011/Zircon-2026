import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class NPCIO(p: FrontendParams) extends Bundle {
    val cmt = Flipped(Valid(new FrontendRedirect))
    val pd = Flipped(Valid(new FrontendRedirect))
    val pr = Flipped(Valid(new FrontendRedirect))
    val space = Input(Bool())
    val request = Decoupled(new FrontendFetchRequest(p))
}

/** Full-address arbitration and retention of an unaccepted destination. PC is external. */
class NPC(p: FrontendParams) extends Module {
    val io = IO(new NPCIO(p))
    val pendingPc = RegInit(p.resetPc.U(32.W))
    val pendingValid = RegInit(true.B)
    val pending = Wire(Valid(new FrontendRedirect))
    pending.valid := pendingValid
    pending.bits.pc := pendingPc
    val selected = Wire(Valid(new FrontendRedirect))
    val overrideValid = io.cmt.valid || io.pd.valid || pending.valid
    val overrideBits = Mux(
        io.cmt.valid,
        io.cmt.bits,
        Mux(io.pd.valid, io.pd.bits, pending.bits),
    )
    selected.valid := overrideValid || io.pr.valid
    // Prediction arrives late; keep it behind only the final redirect override mux.
    selected.bits := Mux(overrideValid, overrideBits, io.pr.bits)
    io.request.valid := io.space && selected.valid
    io.request.bits.pc := selected.bits.pc
    when(selected.valid) {
        pendingValid := !io.request.fire
        pendingPc := selected.bits.pc
    }
    when(io.request.fire) { assert(io.request.bits.pc(1, 0) === 0.U, "Fetch requests require IALIGN=32") }
}
