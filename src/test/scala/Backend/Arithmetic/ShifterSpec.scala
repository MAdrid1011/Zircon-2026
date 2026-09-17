import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class ShifterSpec extends AnyFreeSpec with ChiselSim {
    "32-bit shifter matches logical and arithmetic right shifts for every shift amount" in {
        simulate(new Shifter.Shifter) { dut =>
            val mask = (BigInt(1) << 32) - 1
            val random = new Random(2026L)
            val patterns = Seq("0", "1", "ffffffff", "80000000", "7fffffff", "aaaaaaaa", "55555555")
                .map(BigInt(_, 16)) ++ (0 until 32).map(i => BigInt(1) << i) ++
                Seq.fill(128)(BigInt(32, random))
            var checked = 0
            for (src <- patterns; shift <- 0 until 32; signed <- Seq(false, true)) {
                val value = if (signed && src.testBit(31)) src - (BigInt(1) << 32) else src
                dut.io.src.poke(src)
                dut.io.shf.poke(shift)
                dut.io.sgn.poke(signed)
                dut.io.res.expect((value >> shift) & mask, s"src=0x${src.toString(16)} shift=$shift signed=$signed")
                checked += 1
            }
            info(s"Validated $checked shifter input vectors")
        }
    }
}
