import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class WakeupRouterSpec extends AnyFreeSpec with ChiselSim {
    "consumer classes receive the agreed pipeline stages and p0 marks empty" in {
        simulate(new WakeupRouter) { dut =>
            val inputs = Seq(
                dut.io.arith0Issue,
                dut.io.arith0WB,
                dut.io.arith1Issue,
                dut.io.arith1WB,
                dut.io.mixEX2,
                dut.io.mixEX3,
                dut.io.mixWB,
                dut.io.load0D1,
                dut.io.load1D1,
                dut.io.load0WB,
                dut.io.load1WB,
            )
            inputs.foreach { input =>
                input.prd.poke(0)
                input.specMask.poke(0)
            }
            Seq(
                dut.io.arith0Issue -> 1,
                dut.io.arith0WB -> 2,
                dut.io.arith1Issue -> 3,
                dut.io.arith1WB -> 4,
                dut.io.mixEX2 -> 5,
                dut.io.mixEX3 -> 6,
                dut.io.mixWB -> 10,
                dut.io.load0D1 -> 8,
                dut.io.load1D1 -> 66,
                dut.io.load0WB -> 9,
                dut.io.load1WB -> 67,
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
