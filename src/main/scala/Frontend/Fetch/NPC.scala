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
    val token = RegInit(0.U(32.W))
    val pending = Wire(Valid(new FrontendRedirect))
    pending.valid := pendingValid
    pending.bits.pc := pendingPc
    val sources = Seq(io.cmt, io.pd, pending, io.pr)
    val selected = Wire(Valid(new FrontendRedirect))
    selected.valid := sources.map(_.valid).reduce(_ || _)
    // Decode priority before the late prediction address arrives.
    val grants = sources.indices.map(i => sources(i).valid && !sources.take(i).map(_.valid).foldLeft(false.B)(_ || _))
    selected.bits := Mux1H(grants, sources.map(_.bits))
    io.request.valid := io.space && selected.valid
    io.request.bits.pc := selected.bits.pc
    io.request.bits.token := token
    when(selected.valid) {
        pendingValid := !io.request.fire
        pendingPc := selected.bits.pc
    }
    when(io.request.fire) {
        token := token + 1.U
        assert(io.request.bits.pc(1, 0) === 0.U, "Fetch requests require IALIGN=32")
    }
}
