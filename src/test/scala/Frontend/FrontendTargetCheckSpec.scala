import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class FrontendTargetCheckTestTop(width: Int) extends Module {
    val io = IO(new Bundle {
        val a = Input(UInt(width.W))
        val b = Input(UInt(width.W))
        val expected = Input(UInt(width.W))
        val matches = Output(Bool())
    })
    io.matches := FrontendMath.sumMatches(io.a, io.b, io.expected)
}

class FrontendTargetCheckSpec extends AnyFreeSpec with ChiselSim {
    for (width <- Seq(1, 4)) {
        s"sum equality checks every $width-bit input combination without a propagated carry" in {
            simulate(new FrontendTargetCheckTestTop(width)) { d =>
                val limit = 1 << width
                for (a <- 0 until limit; b <- 0 until limit; expected <- 0 until limit) {
                    d.io.a.poke(a); d.io.b.poke(b); d.io.expected.poke(expected)
                    d.io.matches.expect(((a + b) & (limit - 1)) == expected)
                }
            }
        }
    }
    "32-bit sum equality preserves long carries, wraparound, and rejects changed target bits" in {
        simulate(new FrontendTargetCheckTestTop(32)) { d =>
            val rng = new scala.util.Random(20260910)
            val directed = Seq(
                0L -> 0L,
                0xffffffffL -> 1L,
                0x7fffffffL -> 1L,
                0x80000000L -> 0x80000000L,
                0xffff0000L -> 0x10000L
            )
            val random = Seq.fill(1000)((rng.nextLong() & 0xffffffffL) -> (rng.nextLong() & 0xffffffffL))
            for ((a, b) <- directed ++ random) {
                val sum = (a + b) & 0xffffffffL
                d.io.a.poke(a); d.io.b.poke(b); d.io.expected.poke(sum)
                d.io.matches.expect(true)
                d.io.expected.poke(sum ^ (1L << rng.nextInt(32)))
                d.io.matches.expect(false)
            }
        }
    }
}
