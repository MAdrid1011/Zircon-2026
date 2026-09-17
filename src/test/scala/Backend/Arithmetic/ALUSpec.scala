import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class ALUSpec extends AnyFreeSpec with ChiselSim {
    "ALU preserves Zircon-2024 operation encodings and matches integer arithmetic" in {
        simulate(new ALU) { dut =>
            val mask = (BigInt(1) << 32) - 1
            def signed(value: BigInt): BigInt = if (value.testBit(31)) value - (BigInt(1) << 32) else value
            def flag(condition: Boolean): BigInt = if (condition) BigInt(1) else BigInt(0)
            // Use literal operation encodings so an accidental change in Config is observable.
            def reference(op: Int, a: BigInt, b: BigInt): BigInt = {
                val shift = (b & 31).toInt
                val result = op match {
                    case 0x00 => a + b
                    case 0x01 => a << shift
                    case 0x02 => flag(signed(a) < signed(b))
                    case 0x03 => flag(a < b)
                    case 0x04 => a ^ b
                    case 0x05 => a >> shift
                    case 0x06 => a | b
                    case 0x07 => a & b
                    case 0x08 => a - b
                    case 0x0d => signed(a) >> shift
                    case _ => a + 4 // Includes JAL/JALR; src1 is the PC supplied by the caller.
                }
                result & mask
            }
            var checked = 0
            def check(op: Int, a: BigInt, b: BigInt): Unit = {
                dut.io.op.poke(op)
                dut.io.src1.poke(a)
                dut.io.src2.poke(b)
                dut.io.res.expect(reference(op, a, b), s"op=$op a=0x${a.toString(16)} b=0x${b.toString(16)}")
                checked += 1
            }

            val edges = Seq(
                "0",
                "1",
                "2",
                "4",
                "1f",
                "20",
                "7fffffff",
                "80000000",
                "80000001",
                "fffffffe",
                "ffffffff",
                "aaaaaaaa",
                "55555555"
            ).map(BigInt(_, 16))
            for (op <- 0 until 32; a <- edges; b <- edges) check(op, a, b)

            // Exercise all shift amounts and prove src2's upper bits do not affect the amount.
            for (
                op <- Seq(0x01, 0x05, 0x0d); a <- edges; shift <- 0 until 32;
                upper <- Seq(BigInt(0), BigInt("ffffffe0", 16))
            ) {
                check(op, a, upper | shift)
            }

            for (bit <- 1 to 32) {
                val run = (BigInt(1) << bit) - 1
                check(0x00, run, 1)
                check(0x08, 0, run)
                check(0x02, run, run)
                check(0x03, run, run)
            }

            val random = new Random(2026L)
            for (_ <- 0 until 512; op <- 0 until 32) {
                check(op, BigInt(32, random), BigInt(32, random))
            }
            info(s"Validated $checked ALU input vectors")
        }
    }
}
