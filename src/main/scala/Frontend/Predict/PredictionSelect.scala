import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

/** Select the earliest taken CFI while retaining the untruncated slot descriptions. */
class FrontendPredictionSelect(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val pc = Input(UInt(32.W))
        val range = Input(UInt(p.fetchWidth.W))
        val kinds = Input(Vec(p.fetchWidth, UInt(3.W)))
        val control = Input(Vec(p.fetchWidth, Bool()))
        val conditional = Input(Vec(p.fetchWidth, Bool()))
        val backward = Input(UInt(p.fetchWidth.W))
        val targets = Input(Vec(p.fetchWidth, UInt(32.W)))
        val directions = Input(UInt(p.fetchWidth.W))
        val prediction = Output(new FrontendPrediction(p))
    })
    /* Conditional Rank to Instruction Slot */
    // Only an in-range conditional branch consumes a direction bit.
    val candidates = Wire(Vec(p.fetchWidth, Bool()))
    val taken = Wire(Vec(p.fetchWidth, Bool()))
    val mask = Wire(Vec(p.fetchWidth, Bool()))
    for (i <- 0 until p.fetchWidth) {
        // Decode the rank independently; a late PHT result crosses only one selection level.
        val direction = if (i == 0) io.directions(0)
        else Mux1H(
            UIntToOH(PopCount((0 until i).map(j => io.range(j) && io.conditional(j))), i + 1),
            (0 to i).map(io.directions(_))
        )
        candidates(i) := io.range(i) && io.control(i) && (!io.conditional(i) || direction)
        // Include the first taken slot and discard its suffix.
        mask(i) := io.range(i) && (if (i == 0) true.B else !candidates.take(i).reduce(_ || _))
        taken(i) := mask(i) && candidates(i)
    }

    /* Block Prediction */
    io.prediction.kinds := io.kinds
    io.prediction.targets := io.targets
    io.prediction.taken := taken.asUInt
    io.prediction.mask := mask.asUInt
    io.prediction.backward := io.backward & io.conditional.asUInt
    val target = Mux1H(taken, io.targets)
    // Execution handles an actually taken misaligned target; do not issue an unaligned speculative fetch.
    io.prediction.nextPc := Mux(taken.asUInt.orR && target(1, 0) === 0.U, target, FrontendMath.sequential(io.pc, p))
}
