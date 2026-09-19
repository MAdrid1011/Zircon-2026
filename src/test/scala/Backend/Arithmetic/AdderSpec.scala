import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/** Give every copied adder the same test interface without changing its implementation. */
class AdderHarness(width: Int) extends Module {
    val io = IO(new AdderIO(width))
    width match {
        case 4 => io <> Module(new BLevelAdder4).io
        case 5 => io <> Module(new BLevelAdder5).io
        case 32 => io <> Module(new BLevelPAdder32).io
        case 33 => io <> Module(new BLevelPAdder33).io
        case 64 => io <> Module(new BLevelPAdder64).io
        case _ => throw new IllegalArgumentException(s"Unsupported adder width: $width")
    }
}

class AdderSpec extends AnyFreeSpec with ChiselSim {
    for (width <- Seq(4, 5, 32, 33, 64)) {
        s"${width}-bit adder matches unsigned addition including carry-out" in {
            simulate(new AdderHarness(width)) { dut =>
                val mask = (BigInt(1) << width) - 1
                var checked = 0
                def check(a: BigInt, b: BigInt, cin: Int): Unit = {
                    val sum = a + b + cin
                    val context = s"width=$width a=0x${a.toString(16)} b=0x${b.toString(16)} cin=$cin"
                    dut.io.src1.poke(a)
                    dut.io.src2.poke(b)
                    dut.io.cin.poke(cin)
                    dut.io.res.expect(sum & mask, context)
                    dut.io.cout.get.expect(sum >> width, context)
                    checked += 1
                }

                if (width <= 5) {
                    for (a <- 0 until (1 << width); b <- 0 until (1 << width); cin <- 0 to 1) {
                        check(BigInt(a), BigInt(b), cin)
                    }
                } else {
                    val edges = Seq(
                        BigInt(0),
                        BigInt(1),
                        mask,
                        mask - 1,
                        BigInt(1) << (width - 1),
                        (BigInt(1) << (width - 1)) - 1,
                        BigInt("aaaaaaaaaaaaaaaa", 16) & mask,
                        BigInt("5555555555555555", 16) & mask
                    )
                    for (a <- edges; b <- edges; cin <- 0 to 1) check(a, b, cin)

                    // Carry propagation through every contiguous interval, including 4/16/32-bit boundaries.
                    for (start <- 0 until width; end <- start until width) {
                        val run = ((BigInt(1) << (end - start + 1)) - 1) << start
                        val trigger = BigInt(1) << start
                        check(run, trigger, 0)
                        check(trigger, run, 0)
                        if (start == 0) check(run, 0, 1)
                    }

                    val random = new Random(2026L + width)
                    for (_ <- 0 until 2048) {
                        check(BigInt(width, random), BigInt(width, random), random.nextInt(2))
                    }
                }
                info(s"Validated $checked input vectors at width $width")
            }
        }
    }
}
