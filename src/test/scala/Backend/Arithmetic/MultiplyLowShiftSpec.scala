import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class MultiplyLowShiftProbe extends Module {
    val io = IO(new Bundle {
        val other = Input(UInt(24.W))
        val amount = Input(UInt(3.W))
        val right = Input(Bool())
        val result = Output(UInt(31.W))
    })
    val window = Cat(0.U(56.W), io.other)
    val shift = Cat(0.U(4.W), io.amount)
    val shifted = Mux(
        io.right,
        SharedMultiplyLogic.rightJam(window, shift, 80),
        SharedMultiplyLogic.leftTruncate(window, shift, 80),
    )
    io.result := shifted(30, 0)
}

class MultiplyLowShiftSpec extends AnyFreeSpec with ChiselSim {
    "low alignment matches left shift and right-jam for every distance" in {
        simulate(new MultiplyLowShiftProbe) { dut =>
            val random = new Random(20260927L)
            val values = Seq(0, 1, 0x800000, 0xffffff) ++ Seq.fill(128)(random.nextInt(1 << 24))
            for (value <- values; amount <- 0 until 8; right <- Seq(false, true)) {
                val expected = if (right) {
                    val lost = if (amount == 0) 0 else value & ((1 << amount) - 1)
                    (value >> amount) | (if (lost != 0) 1 else 0)
                } else value << amount
                dut.io.other.poke(value)
                dut.io.amount.poke(amount)
                dut.io.right.poke(right)
                dut.io.result.expect(expected)
                dut.clock.step()
            }
        }
    }
}
