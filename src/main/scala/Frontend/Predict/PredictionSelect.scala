import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FrontendPredictionSelectIO(p: FrontendParams) extends Bundle {
    val pcBlock = Input(UInt((32 - p.blockBits).W))
    val range = Input(UInt(p.fetchWidth.W))
    val kinds = Input(Vec(p.fetchWidth, UInt(3.W)))
    val control = Input(Vec(p.fetchWidth, Bool()))
    val conditional = Input(Vec(p.fetchWidth, Bool()))
    val backward = Input(UInt(p.fetchWidth.W))
    val targets = Input(Vec(p.fetchWidth, UInt(32.W)))
    val directions = Input(UInt(p.fetchWidth.W))
    val prediction = Output(new FrontendPrediction(p))
}

/** Select the earliest taken CFI while retaining the untruncated slot descriptions. */
class FrontendPredictionSelect(p: FrontendParams, assumeAlignedTargets: Boolean = false) extends RawModule {
    val io = IO(new FrontendPredictionSelectIO(p))
    /* Conditional Rank to Instruction Slot */
    // Only an in-range conditional branch consumes a direction bit.
    val candidates = Wire(Vec(p.fetchWidth, Bool()))
    for (i <- 0 until p.fetchWidth) {
        // Decode the rank independently; a late PHT result crosses only one selection level.
        val direction = if (i == 0) io.directions(0)
        else {
            val rank = PopCount((0 until i).map(j => io.range(j) && io.conditional(j)))
            Mux1H((0 to i).map(value => (rank === value.U) -> io.directions(value)))
        }
        candidates(i) := io.range(i) && io.control(i) && (!io.conditional(i) || direction)
    }
    // A parallel prefix OR includes the first taken slot and discards its suffix.
    var seen = candidates.asUInt
    var distance = 1
    while (distance < p.fetchWidth) {
        seen = seen | (seen << distance)(p.fetchWidth - 1, 0)
        distance *= 2
    }
    val prior = if (p.fetchWidth == 1) 0.U(1.W) else Cat(seen(p.fetchWidth - 2, 0), false.B)
    val mask = io.range & ~prior
    val taken = mask & candidates.asUInt

    /* Block Prediction */
    io.prediction.kinds := io.kinds
    io.prediction.targets := io.targets
    io.prediction.taken := taken
    io.prediction.mask := mask
    io.prediction.backward := io.backward & io.conditional.asUInt
    val target = Mux1H(taken.asBools, io.targets)
    val sequential = Cat(io.pcBlock + 1.U, 0.U(p.blockBits.W))
    // Execution handles an actually taken misaligned target; do not issue an unaligned speculative fetch.
    io.prediction.nextPc := (if (assumeAlignedTargets) {
        Mux1H(taken.asBools :+ !taken.orR, io.targets :+ sequential)
    } else {
        val useTarget = taken.orR && target(1, 0) === 0.U
        Mux(useTarget, target, sequential)
    })
}
