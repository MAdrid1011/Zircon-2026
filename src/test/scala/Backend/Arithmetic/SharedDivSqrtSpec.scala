import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class SharedDivSqrtSpec extends AnyFreeSpec with ChiselSim {
    case class Sample(op: Int, rm: Int, a: BigInt, b: BigInt, result: BigInt, flags: Int, tag: Int)

    private def samples: Vector[Sample] = {
        val input = scala.io.Source.fromResource("shared-divsqrt-softfloat.txt")
        try input.getLines().filterNot(_.startsWith("#")).filter(_.nonEmpty).zipWithIndex.map { case (line, tag) =>
                val f = line.split(" ").map(BigInt(_, 16))
                Sample(f(0).toInt, f(1).toInt, f(2), f(3), f(4), f(5).toInt, tag)
            }.toVector
        finally input.close()
    }

    private def iterations(x: Sample): Int = {
        if (x.op >= 4) {
            val ea = (x.a >> 23) & 255
            val eb = (x.b >> 23) & 255
            val az = (x.a & 0x7fffffffL) == 0
            val bz = (x.b & 0x7fffffffL) == 0
            val special = if (x.op == 5) az || ea == 255 || x.a.testBit(31)
            else az || bz || ea == 255 || eb == 255
            if (special) 0 else if (x.op == 5) 13 else 14
        } else {
            def magnitude(a: BigInt): BigInt =
                if (x.op % 2 == 0 && a.testBit(31)) (BigInt(1) << 32) - a else a
            val a = magnitude(x.a)
            val b = magnitude(x.b)
            val overflow = x.op % 2 == 0 && x.a == 0x80000000L && x.b == 0xffffffffL
            if (b == 0 || a == 0 || a < b || overflow) 0
            else (a.bitLength - b.bitLength + 1) / 2 + 1
        }
    }

    "four-stage mixed traffic matches the oracle and DivBusy exposes registered EX2 computation" in {
        simulate(new DivSqrtSRT4) { dut =>
            var pipe = Vector.fill[Option[Sample]](4)(None)
            var remaining = 0
            var cycles = 0
            var accepted = 0
            var retired = 0
            var cancelled = 0
            var maximumOutstanding = 0
            var retiredWhileBusy = 0

            def step(
                input: Option[Sample],
                ready: Boolean = true,
                flush: Boolean = false,
                reset: Boolean = false
            ): Boolean = {
                val x = input.getOrElse(Sample(5, 4, BigInt("bf800000", 16), 0, 0, 0, 0))
                dut.reset.poke(reset)
                dut.io.flush.poke(flush)
                dut.io.in.valid.poke(input.nonEmpty)
                dut.io.out.ready.poke(ready)
                dut.io.in.bits.src1.poke(x.a)
                dut.io.in.bits.src2.poke(x.b)
                dut.io.in.bits.op.poke(x.op)
                dut.io.in.bits.roundingMode.poke(x.rm)
                dut.io.in.bits.tag.poke(x.tag)
                def availability: Vector[Boolean] = {
                    val r4 = pipe(3).isEmpty || ready
                    val r3 = pipe(2).isEmpty || r4
                    val r2 = remaining == 0 && (pipe(1).isEmpty || r3)
                    Vector(pipe(0).isEmpty || r2, r2, r3, r4)
                }
                def check(): Unit = {
                    val active = !reset && !flush
                    val r = availability
                    dut.io.in.ready.expect(active && r(0))
                    // Check before and after the edge, even when reset or flush is asserted.
                    dut.io.divBusy.expect(remaining > 0)
                    dut.io.out.valid.expect(active && pipe(3).nonEmpty)
                    if (active) pipe(3).foreach { y =>
                        val clue = s"cycle=$cycles op=${y.op} rm=${y.rm} a=${y.a.toString(16)} b=${y.b.toString(16)}"
                        dut.io.out.bits.res.expect(y.result, clue)
                        dut.io.out.bits.fflags.expect(y.flags, clue)
                        dut.io.out.bits.tag.expect(y.tag, clue)
                    }
                }
                check()
                val r = availability
                val fire = !reset && !flush && r(0) && input.nonEmpty
                if (reset || flush) {
                    cancelled += pipe.count(_.nonEmpty)
                    pipe = Vector.fill(4)(None)
                    remaining = 0
                } else {
                    if (pipe(3).nonEmpty && ready) {
                        retired += 1
                        if (remaining > 0) retiredWhileBusy += 1
                    }
                    var next = pipe
                    if (r(3)) next = next.updated(3, pipe(2))
                    if (r(2)) next = next.updated(2, if (remaining == 0) pipe(1) else None)
                    if (r(1)) {
                        next = next.updated(1, pipe(0))
                        val n = pipe(0).map(iterations).getOrElse(0)
                        remaining = if (n == 0) 0 else n + 1
                    } else if (remaining > 0) remaining -= 1
                    if (r(0)) next = next.updated(0, input)
                    pipe = next
                    if (fire) accepted += 1
                    maximumOutstanding = maximumOutstanding.max(pipe.count(_.nonEmpty))
                }
                dut.clock.step()
                cycles += 1
                check()
                fire
            }
            def drain(): Unit = {
                var elapsed = 0
                while (pipe.exists(_.nonEmpty)) {
                    step(None)
                    elapsed += 1
                    assert(elapsed < 100)
                }
            }
            step(None, reset = true)
            val vectors = samples
            assert(vectors.map(_.op).toSet == (0 until 6).toSet)
            for (op <- 4 until 6) assert(vectors.filter(_.op == op).map(_.rm).toSet == (0 until 5).toSet)
            for (x <- vectors) {
                var waited = 0
                while (!step(Some(x))) {
                    waited += 1
                    assert(waited < 100)
                }
            }
            drain()
            assert(retired == vectors.size)

            val special = Sample(0, 0, 0, 0, BigInt("ffffffff", 16), 0, 10000)
            val long = Sample(
                4,
                0,
                BigInt("3f800000", 16),
                BigInt("40400000", 16),
                BigInt("3eaaaaab", 16),
                1,
                10001
            )
            // EX3/EX4 drain even when EX2 is iterating and EX1 holds a younger request.
            assert(step(Some(special)))
            assert(step(Some(special.copy(tag = 10002))))
            assert(step(Some(long)))
            assert(step(Some(long.copy(tag = 10003))))
            drain()
            assert(retiredWhileBusy > 0)

            // With no arithmetic iteration, completed R23 still blocks EX2 when EX3 is full.
            for (i <- 0 until 4) assert(step(Some(special.copy(tag = 11000 + i)), ready = false))
            for (_ <- 0 until 8) {
                assert(!step(Some(long), ready = false))
                dut.io.divBusy.expect(false)
            }
            // Backpressure may change ready combinationally, but must never change DivBusy.
            dut.io.out.ready.poke(true)
            dut.io.in.ready.expect(true)
            dut.io.divBusy.expect(false)
            dut.io.out.ready.poke(false)
            dut.io.in.ready.expect(false)
            dut.io.divBusy.expect(false)
            step(Some(long), ready = false, flush = true)
            drain()
            assert(maximumOutstanding == 4)

            for (reset <- Seq(false, true); offset <- 0 until 25) {
                assert(step(Some(long), ready = false))
                for (_ <- 0 until offset) step(None, ready = false)
                step(Some(special), ready = false, flush = !reset, reset = reset)
                for (_ <- 0 until 3) step(None)
            }
            val random = new scala.util.Random(20260910)
            var held: Option[Sample] = None
            for (i <- 0 until 5000) {
                if (held.isEmpty && random.nextBoolean()) held = Some(vectors(random.nextInt(vectors.size)))
                val flush = i % 193 == 7
                val reset = i % 701 == 5
                if (step(held, ready = i % 29 >= 9 && random.nextBoolean(), flush = flush, reset = reset))
                    held = None
                if (flush || reset) held = None
            }
            drain()
            assert(accepted == retired + cancelled)
            info(
                s"${vectors.size} independent vectors; $cycles cycles; four occupied slots; $retiredWhileBusy responses during DivBusy"
            )
        }
    }
}
