import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class WakeupRouterTestTop extends Module {
    val io = IO(new WakeupRouterIO(ZirconConfig.BackendParams()))
    val router = Module(new WakeupRouter)
    router.io <> io
}

class WakeupRouterSpec extends AnyFreeSpec with ChiselSim {
    "consumer classes receive the agreed pipeline stages and p0 marks empty" in {
        simulate(new WakeupRouterTestTop) { dut =>
            val inputs = Seq(
                dut.io.arithIssue(0),
                dut.io.arithRF(0),
                dut.io.arithIssue(1),
                dut.io.arithRF(1),
                dut.io.mixEX2,
                dut.io.mixEX3,
                dut.io.mixWB,
            ) ++ dut.io.loadD1 ++ dut.io.loadWB
            inputs.foreach { input =>
                input.prd.poke(0)
                input.specMask.poke(0)
            }
            Seq(
                dut.io.arithIssue(0) -> 1,
                dut.io.arithRF(0) -> 2,
                dut.io.arithIssue(1) -> 3,
                dut.io.arithRF(1) -> 4,
                dut.io.mixEX2 -> 5,
                dut.io.mixEX3 -> 6,
                dut.io.mixWB -> 10,
                dut.io.loadD1(0) -> 8,
                dut.io.loadD1(1) -> 66,
                dut.io.loadWB(0) -> 9,
                dut.io.loadWB(1) -> 67,
            ).foreach { case (input, tag) =>
                input.prd.poke(tag)
                input.specMask.poke(if (tag == 7) 4 else 0)
            }

            Seq(1, 3, 5, 10, 8, 66, 9, 67).zipWithIndex.foreach { case (tag, index) =>
                dut.io.compute(index).prd.expect(tag)
            }
            Seq(2, 4, 6, 10, 8, 66, 9, 67).zipWithIndex.foreach { case (tag, index) =>
                dut.io.memory(index).prd.expect(tag)
            }

            dut.io.mixWB.prd.poke(0)
            dut.io.mixWB.specMask.poke(0)
            dut.io.compute(2).prd.expect(5)
            dut.io.compute(3).prd.expect(0)
        }
    }
}
