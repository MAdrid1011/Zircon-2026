import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

class LoadSpeculationTrackerIO(p: BackendParams) extends Bundle {
    val request = Input(Vec(2, Bool()))
    val allocate = Input(Vec(2, Bool()))
    val grant = Output(Vec(2, UInt(p.specWidth.W)))
    val result = Input(Vec(2, Valid(new LoadSpeculationResult(p))))
    val flush = Input(Bool())
    val resolution = Output(new SpeculationResolution(p))
    val active = Output(UInt(p.specWidth.W))
}

/** Allocate unique short-lived Load prediction tokens to the two memory lanes. */
class LoadSpeculationTracker(val p: BackendParams = BackendParams()) extends Module {
    val io = IO(new LoadSpeculationTrackerIO(p))

    val active = RegInit(0.U(p.specWidth.W))
    val free = ~active
    val lane0Grant = PriorityEncoderOH(free)
    val remaining = free & ~Mux(io.request(0), lane0Grant, 0.U)
    val lane1Grant = PriorityEncoderOH(remaining)
    io.grant(0) := Mux(io.request(0), lane0Grant, 0.U)
    io.grant(1) := Mux(io.request(1), lane1Grant, 0.U)

    val resolvedMask = io.result.map(result => Mux(result.valid, result.bits.mask, 0.U)).reduce(_ | _)
    val failedMask = io.result.map(result =>
        Mux(result.valid && result.bits.failed, result.bits.mask, 0.U)
    ).reduce(_ | _)
    val allocatedMask = io.allocate.zip(io.grant).map { case (allocate, grant) =>
        Mux(allocate, grant, 0.U)
    }.reduce(_ | _)

    io.resolution.resolvedMask := resolvedMask
    io.resolution.failedMask := failedMask
    io.active := active

    when(io.flush) {
        active := 0.U
    }.otherwise {
        active := (active & ~resolvedMask) | allocatedMask
    }

    assert((failedMask & ~resolvedMask) === 0.U)
    assert((allocatedMask & active) === 0.U, "Load speculation token was allocated twice")
    assert(!(io.allocate(0) && io.allocate(1)) || !(io.grant(0) & io.grant(1)).orR)
    for (lane <- 0 until 2) {
        when(io.allocate(lane)) {
            assert(io.grant(lane).orR, "A speculative Load allocation requires a free token")
        }
        when(io.result(lane).valid) {
            assert(io.result(lane).bits.mask.orR)
            assert((io.result(lane).bits.mask & active).orR, "Load resolved an inactive speculation token")
        }
    }
}
