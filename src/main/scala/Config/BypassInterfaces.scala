import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

/** One RF-stage operand lookup against all results promised for the next WB cycle. */
class BypassQuery(val p: BackendParams = BackendParams()) extends Bundle {
    val prs = UInt(p.tagWidth.W)
}

/** Consumer half of a scheduled WB-to-EX/EX1 bypass port. */
class BypassConsumerPort(val numSources: Int, val p: BackendParams = BackendParams()) extends Bundle {
    require(numSources > 0)

    val query = Output(Vec(numSources, new BypassQuery(p)))
    val advance = Output(Bool())
    val value = Input(Vec(numSources, Valid(UInt(32.W))))
    val capture = Input(Vec(numSources, Valid(UInt(32.W))))
    val valueFpZero = Input(Vec(numSources, Bool()))
    val captureFpZero = Input(Vec(numSources, Bool()))
}

/** A producer announces the tag that will accompany its registered result next cycle. */
class BypassProducerPort(val p: BackendParams = BackendParams()) extends Bundle {
    val nextWb = Output(Valid(UInt(p.tagWidth.W)))
    val nextResult = Output(Valid(UInt(32.W)))
    val result = Output(UInt(32.W))
}

/** The registered WB value paired with its one-cycle-ahead tag announcement. */
class BypassSource(val p: BackendParams = BackendParams()) extends Bundle {
    val nextWb = Valid(UInt(p.tagWidth.W))
    val nextResult = Valid(UInt(32.W))
    val result = UInt(32.W)
}

/** A compute pipeline consumes operands in EX/EX1 and produces one result in WB. */
class PipelineBypassPort(val numSources: Int, val p: BackendParams = BackendParams()) extends Bundle {
    val consumer = new BypassConsumerPort(numSources, p)
    val producer = new BypassProducerPort(p)
}
