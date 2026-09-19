import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

class WakeupRouterIO(p: BackendParams) extends Bundle {
    val arithIssue = Input(Vec(2, new BackendWakeup(p)))
    val arithRF = Input(Vec(2, new BackendWakeup(p)))
    val mixEX2 = Input(new BackendWakeup(p))
    val mixEX3 = Input(new BackendWakeup(p))
    val mixWB = Input(new BackendWakeup(p))
    val loadD1 = Input(Vec(2, new BackendWakeup(p)))
    val loadWB = Input(Vec(2, new BackendWakeup(p)))
    val compute = Output(Vec(8, new BackendWakeup(p)))
    val memory = Output(Vec(8, new BackendWakeup(p)))
}

/** Select wakeup timing for the consumer class without adding a validity bit. */
class WakeupRouter(val p: BackendParams = BackendParams()) extends RawModule {
    val io = IO(new WakeupRouterIO(p))

    io.compute := VecInit(io.arithIssue.toSeq ++ Seq(
        io.mixEX2,
        io.mixWB,
    ) ++ io.loadD1.toSeq ++ io.loadWB.toSeq)
    io.memory := VecInit(io.arithRF.toSeq ++ Seq(
        io.mixEX3,
        io.mixWB,
    ) ++ io.loadD1.toSeq ++ io.loadWB.toSeq)
}
