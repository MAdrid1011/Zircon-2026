import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

/** Convert one retired packet into committed predictor state and training data. */
class CommitRecovery(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val valid = Input(Bool())
        val redirect = Input(Bool())
        val retire = Input(new FrontendRetirement(p))
        val ftq = Input(new FrontendFtqEntry(p))
        val ftqIdx = Input(UInt(p.ftqBits.W))
        val state = Valid(new FrontendStateEvent(p))
        val train = Valid(new FrontendTraining(p))
    })
    val retirement = io.retire
    val record = io.ftq.record
    val headMatches = retirement.ftqIdx === io.ftqIdx && retirement.fetchToken === io.ftq.fetchToken
    val prediction = Wire(new FrontendPrediction(p))
    prediction.kinds := record.train.kinds
    prediction.targets := retirement.targets
    prediction.mask := retirement.mask
    prediction.taken := retirement.taken & retirement.mask
    prediction.nextPc := retirement.nextPc
    prediction.backward := VecInit((0 until p.fetchWidth).map(i =>
        FrontendCfi.conditional(prediction.kinds(i)) &&
            FrontendMath.backwardBranch(FrontendMath.slotPc(record.train.pc, i, p), retirement.targets(i))
    )).asUInt
    val mismatch = retirement.nextPc =/= record.nextPc || retirement.mask =/= record.train.mask ||
        ((retirement.taken ^ record.train.taken) & retirement.mask).orR
    io.state.valid := io.valid
    io.state.bits.pc := record.train.pc
    io.state.bits.prediction := prediction
    io.train.valid := io.valid
    io.train.bits.pc := record.train.pc
    io.train.bits.mask := retirement.mask
    io.train.bits.kinds := record.train.kinds
    io.train.bits.taken := retirement.taken
    io.train.bits.targets := retirement.targets
    io.train.bits.meta := record.train.meta
    io.train.bits.earlyDirections := record.train.earlyDirections
    io.train.bits.biasDirections := record.train.biasDirections
    when(io.valid) {
        assert(headMatches, "Retirement must reference the oldest FTQ index and fetch token")
        assert(!mismatch || io.redirect, "Backend must redirect when the retired prediction disagrees")
        assert(
            (retirement.mask & ~record.train.mask) === 0.U && retirement.mask.orR,
            "Retirement cannot execute instructions that were not delivered"
        )
        val control = VecInit(record.train.kinds.map(_ =/= 0.U)).asUInt
        assert(
            (retirement.mask & control & ~io.ftq.resolved) === 0.U,
            "Every retired control-flow slot must provide its executed target"
        )
        assert(PopCount(retirement.taken & retirement.mask) <= 1.U)
    }
}
