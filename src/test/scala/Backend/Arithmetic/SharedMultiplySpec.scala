import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class SharedMultiplySpec extends AnyFreeSpec with ChiselSim {
    case class Sample(op: Int, rm: Int, a: BigInt, b: BigInt, c: BigInt, res: BigInt, flags: Int, tag: Int)
    private def samples: Vector[Sample] = {
        val input = scala.io.Source.fromResource("shared-multiply-softfloat.txt")
        try input.getLines().filterNot(_.startsWith("#")).filter(_.nonEmpty).zipWithIndex.map { case (line, tag) =>
                val f = line.split(" ").map(BigInt(_, 16))
                Sample(f(0).toInt, f(1).toInt, f(2), f(3), f(4), f(5), f(6).toInt, tag)
            }.toVector
        finally input.close()
    }
    "all eleven operations match pinned SoftFloat/BigInt vectors with four-stage mixed traffic, hold and flush" in {
        simulate(new MulBooth2Wallce) { dut =>
            var pipe = Vector.fill[Option[Sample]](4)(None)
            var cycle = 0
            var retired = 0
            var stalls = 0
            def step(
                input: Option[Sample],
                ready: Boolean = true,
                flush: Boolean = false,
                reset: Boolean = false
            ): Boolean = {
                val x = input.getOrElse(Sample(0, 0, 123, 456, 789, 0, 0, 0))
                dut.reset.poke(reset); dut.io.flush.poke(flush)
                dut.io.in.valid.poke(input.nonEmpty); dut.io.out.ready.poke(ready)
                dut.io.in.bits.src1.poke(x.a); dut.io.in.bits.src2.poke(x.b); dut.io.in.bits.src3.poke(x.c)
                dut.io.in.bits.op.poke(x.op); dut.io.in.bits.roundingMode.poke(x.rm); dut.io.in.bits.tag.poke(x.tag)
                def check(): Unit = {
                    dut.io.in.ready.expect((!reset && !flush && (pipe(3).isEmpty || ready)).B, s"ready cycle $cycle")
                    dut.io.out.valid.expect((!reset && !flush && pipe(3).nonEmpty).B, s"valid cycle $cycle")
                    if (!reset && !flush) pipe(3).foreach { y =>
                        val clue =
                            s"cycle=$cycle op=${y.op} rm=${y.rm} a=${y.a.toString(16)} b=${y.b.toString(16)} c=${y.c.toString(16)}"
                        dut.io.out.bits.res.expect(y.res, clue)
                        dut.io.out.bits.fflags.expect(y.flags, clue)
                        dut.io.out.bits.tag.expect(y.tag, clue)
                        dut.io.out.bits.dstIsFp.expect((y.op >= 4).B, clue)
                    }
                }
                check()
                val advance = pipe(3).isEmpty || ready
                val fire = !reset && !flush && advance && input.nonEmpty
                if (!reset && !flush && ready && pipe(3).nonEmpty) retired += 1
                if (reset || flush) pipe = Vector.fill(4)(None)
                else if (advance) pipe = input +: pipe.take(3)
                else stalls += 1
                dut.clock.step(); cycle += 1; check()
                fire
            }
            step(None, reset = true)
            val vectors = new Random(20260909).shuffle(samples)
            assert(vectors.map(_.op).toSet == (0 until 11).toSet)
            for (op <- 4 until 11) assert(vectors.filter(_.op == op).map(_.rm).toSet == (0 until 5).toSet)
            for (x <- vectors) assert(step(Some(x)))
            for (_ <- 0 until 4) step(None)
            assert(retired == vectors.size)
            val rng = new Random(987654)
            for (i <- 0 until 3000) {
                step(
                    if (rng.nextBoolean()) Some(vectors(i % vectors.size)) else None,
                    ready = i % 29 >= 9 && rng.nextBoolean(),
                    flush = i % 193 == 7,
                    reset = i % 701 == 5
                )
            }
            // Explicit flush while a full pipe is held; no result can escape.
            for (x <- vectors.take(4)) step(Some(x))
            for (_ <- 0 until 8) step(None, ready = false)
            step(Some(vectors.head), ready = false, flush = true)
            for (_ <- 0 until 5) step(None)
            // Integer coverage independent of the fixture generator.
            val mask = (BigInt(1) << 32) - 1
            def signed(a: BigInt): BigInt = if (a.testBit(31)) a - (BigInt(1) << 32) else a
            for (i <- 0 until 1024; op <- 0 until 4) {
                val a = BigInt(32, rng); val b = BigInt(32, rng)
                val p = (if (op == 3) a else signed(a)) * (if (op >= 2) b else signed(b))
                step(Some(Sample(
                    op,
                    0,
                    a,
                    b,
                    BigInt(32, rng),
                    (if (op == 0) p else p >> 32) & mask,
                    0,
                    10000 + i * 4 + op
                )))
            }
            for (_ <- 0 until 4) step(None)
            info(
                s"$cycle cycles; ${vectors.size} pinned oracle vectors; 4096 additional integer cases; $stalls held cycles"
            )
        }
    }
}
