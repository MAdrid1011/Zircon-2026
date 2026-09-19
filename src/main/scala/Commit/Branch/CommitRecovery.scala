import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class CommitRecoveryRetirement(p: FrontendParams) extends Bundle {
    val mask = UInt(p.fetchWidth.W)
    val taken = UInt(p.fetchWidth.W)
    val targets = Vec(p.fetchWidth, UInt(32.W))
    val nextPc = UInt(32.W)
}

class CommitRecoveryRecord(p: FrontendParams) extends Bundle {
    val pcWord = UInt(30.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val meta = new FrontendTrainingMeta(p)
    val earlyDirections = UInt(p.fetchWidth.W)
}

class CommitRecoveryIO(p: FrontendParams) extends Bundle {
    val valid = Input(Bool())
    val retire = Input(new CommitRecoveryRetirement(p))
    val record = Input(new CommitRecoveryRecord(p))
    val state = Valid(new FrontendStateEvent(p))
    val train = Valid(new FrontendTraining(p))
}

/** Convert one retired packet into committed predictor state and training data. */
class CommitRecovery(p: FrontendParams) extends RawModule {
    val io = IO(new CommitRecoveryIO(p))
    val retirement = io.retire
    val record = io.record
    val prediction = Wire(new FrontendPrediction(p))
    prediction.kinds := record.kinds
    prediction.targets := retirement.targets
    prediction.mask := retirement.mask
    prediction.taken := retirement.taken & retirement.mask
    prediction.nextPc := retirement.nextPc
    prediction.backward := VecInit((0 until p.fetchWidth).map(i =>
        FrontendCfi.conditional(prediction.kinds(i)) &&
            FrontendMath.backwardBranch(FrontendMath.slotPc(Cat(record.pcWord, 0.U(2.W)), i, p), retirement.targets(i))
    )).asUInt
    io.state.valid := io.valid
    io.state.bits.pcWord := record.pcWord
    io.state.bits.prediction := prediction
    io.train.valid := io.valid
    io.train.bits.pcWord := record.pcWord
    io.train.bits.mask := retirement.mask
    io.train.bits.kinds := record.kinds
    io.train.bits.taken := retirement.taken
    io.train.bits.targets := retirement.targets
    io.train.bits.meta := record.meta
    io.train.bits.earlyDirections := record.earlyDirections
}
