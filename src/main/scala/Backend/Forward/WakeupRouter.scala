import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

class WakeupRouterIO(p: BackendParams) extends Bundle {
    val arith0Issue = Input(new BackendWakeup(p))
    val arith0WB = Input(new BackendWakeup(p))
    val arith1Issue = Input(new BackendWakeup(p))
    val arith1WB = Input(new BackendWakeup(p))
    val mixEX2 = Input(new BackendWakeup(p))
    val mixEX3 = Input(new BackendWakeup(p))
    val mixWB = Input(new BackendWakeup(p))
    val load0D1 = Input(new BackendWakeup(p))
    val load1D1 = Input(new BackendWakeup(p))
    val load0WB = Input(new BackendWakeup(p))
    val load1WB = Input(new BackendWakeup(p))
    val compute = Output(Vec(8, new BackendWakeup(p)))
    val memory = Output(Vec(8, new BackendWakeup(p)))
}

/** Select wakeup timing for the consumer class without adding a validity bit. */
class WakeupRouter(val p: BackendParams = BackendParams()) extends Module {
    val io = IO(new WakeupRouterIO(p))

    io.compute := VecInit(Seq(
        io.arith0Issue,
        io.arith1Issue,
        io.mixEX2,
        io.mixWB,
        io.load0D1,
        io.load1D1,
        io.load0WB,
        io.load1WB,
    ))
    io.memory := VecInit(Seq(
        io.arith0WB,
        io.arith1WB,
        io.mixEX3,
        io.mixWB,
        io.load0D1,
        io.load1D1,
        io.load0WB,
        io.load1WB,
    ))

    for (event <- Seq(
        io.arith0Issue,
        io.arith0WB,
        io.arith1Issue,
        io.arith1WB,
        io.mixEX2,
        io.mixEX3,
        io.mixWB,
        io.load0D1,
        io.load1D1,
        io.load0WB,
        io.load1WB,
    )) {
        when(event.prd === 0.U) {
            assert(event.specMask === 0.U, "An empty wakeup cannot carry speculation state")
        }
    }
}
