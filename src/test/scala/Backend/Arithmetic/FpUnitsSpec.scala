import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class FpUnitsSpec extends AnyFreeSpec with ChiselSim {
    case class Sample(op: Int, rm: Int, a: BigInt, b: BigInt, result: BigInt, flags: Int)

    private def samples: Vector[Sample] = {
        val source = scala.io.Source.fromResource("fp-units-softfloat.txt")
        try source.getLines().filter(line => line.nonEmpty && !line.startsWith("#")).map { line =>
                val fields = line.split(" ").map(BigInt(_, 16))
                Sample(fields(0).toInt, fields(1).toInt, fields(2), fields(3), fields(4), fields(5).toInt)
            }.toVector
        finally source.close()
    }

    private def exercise(
        clock: Clock,
        reset: Reset,
        flush: Bool,
        inValid: Bool,
        inReady: Bool,
        src1: UInt,
        src2: Option[UInt],
        op: UInt,
        rm: Option[UInt],
        inTag: UInt,
        outValid: Bool,
        outReady: Bool,
        result: UInt,
        flags: UInt,
        dstIsFp: Bool,
        outTag: UInt,
        depth: Int,
        tagWidth: Int
    ): Unit = {
        val vectors = new Random(20260914).shuffle(samples.filter(x => (x.op >= 11) == (depth == 2)))
        assert(vectors.map(_.op).toSet == (if (depth == 2) (11 to 14).toSet else (0 to 10).toSet))
        if (depth == 2) for (code <- 11 to 14) {
            assert(vectors.filter(_.op == code).map(_.rm).toSet == (0 to 4).toSet)
        }
        var pipe = Vector.fill[Option[(Sample, Int)]](depth)(None)
        var cycle = 0
        var retired = 0
        var accepted = 0
        var killed = 0
        var stalls = 0
        def step(input: Option[Sample], ready: Boolean = true, kill: Boolean = false, rst: Boolean = false): Boolean = {
            val data = input.getOrElse(vectors.head)
            val tag = accepted & ((1 << tagWidth) - 1)
            reset.poke(rst); flush.poke(kill)
            inValid.poke(input.nonEmpty); outReady.poke(ready)
            src1.poke(data.a); src2.foreach(_.poke(data.b))
            op.poke(data.op); rm.foreach(_.poke(data.rm)); inTag.poke(tag)
            val active = !rst && !kill
            val lastReady = pipe.last.isEmpty || ready
            val firstReady = if (depth == 1) lastReady else pipe.head.isEmpty || lastReady
            inReady.expect((active && firstReady).B, s"input ready, cycle $cycle")
            outValid.expect((active && pipe.last.nonEmpty).B, s"output valid, cycle $cycle")
            if (active) pipe.last.foreach { case (expected, expectedTag) =>
                val clue = s"cycle=$cycle op=${expected.op} rm=${expected.rm} a=${expected.a.toString(16)}"
                result.expect(expected.result, clue)
                flags.expect(expected.flags, clue)
                outTag.expect(expectedTag, clue)
                dstIsFp.expect((expected.op <= 4 || expected.op == 10 || expected.op >= 13).B, clue)
            }
            val fire = active && firstReady && input.nonEmpty
            if (!active) {
                killed += pipe.count(_.nonEmpty)
                pipe = Vector.fill(depth)(None)
            } else {
                if (ready && pipe.last.nonEmpty) retired += 1
                if (!ready && pipe.last.nonEmpty) stalls += 1
                if (depth == 2 && lastReady) pipe = pipe.updated(1, pipe.head)
                if (firstReady) pipe = pipe.updated(0, input.map(_ -> tag))
                if (fire) accepted += 1
            }
            assert(accepted == retired + killed + pipe.count(_.nonEmpty))
            clock.step()
            cycle += 1
            fire
        }

        step(None, rst = true)
        vectors.foreach(x => assert(step(Some(x))))
        for (_ <- 0 until depth) step(None)
        assert(retired == vectors.size)

        // Hold the pending request unchanged until accepted; flush may cancel it.
        val random = new Random(918273)
        var pending: Option[Sample] = None
        for (i <- 0 until 4000) {
            if (pending.isEmpty && random.nextBoolean()) pending = Some(vectors(i % vectors.size))
            val kill = i % 137 == 19
            val rst = i % 811 == 32
            if (step(pending, ready = i % 29 >= 11 && random.nextBoolean(), kill = kill, rst = rst) || kill || rst) {
                pending = None
            }
        }
        step(None, kill = true)
        // Kill each occupancy, including full pipes under output backpressure.
        for (occupancy <- 0 to depth; resetKill <- Seq(false, true); ready <- Seq(false, true)) {
            for (i <- 0 until occupancy) assert(step(Some(vectors(i)), ready = false))
            for (_ <- 0 until 5) step(None, ready = false)
            step(Some(vectors.last), ready = ready, kill = !resetKill, rst = resetKill)
            for (_ <- 0 until depth + 1) step(None)
            assert(pipe.forall(_.isEmpty))
        }
        info(
            s"$cycle cycles; ${vectors.size} oracle vectors; $accepted accepted; $retired retired; $killed killed; $stalls stalls"
        )
    }

    "FpLogic preserves all eleven operations and cancellation with a one-bit tag" in {
        simulate(new FpLogic(tagWidth = 1)) { dut =>
            exercise(
                dut.clock,
                dut.reset,
                dut.io.flush,
                dut.io.in.valid,
                dut.io.in.ready,
                dut.io.in.bits.src1,
                Some(dut.io.in.bits.src2),
                dut.io.in.bits.op,
                None,
                dut.io.in.bits.tag,
                dut.io.out.valid,
                dut.io.out.ready,
                dut.io.out.bits.res,
                dut.io.out.bits.fflags,
                dut.io.out.bits.dstIsFp,
                dut.io.out.bits.tag,
                depth = 1,
                tagWidth = 1
            )
        }
    }

    "FpConvert is a two-stage elastic pipeline for every conversion and rounding mode" in {
        simulate(new FpConvert(tagWidth = 7)) { dut =>
            exercise(
                dut.clock,
                dut.reset,
                dut.io.flush,
                dut.io.in.valid,
                dut.io.in.ready,
                dut.io.in.bits.src1,
                None,
                dut.io.in.bits.op,
                Some(dut.io.in.bits.roundingMode),
                dut.io.in.bits.tag,
                dut.io.out.valid,
                dut.io.out.ready,
                dut.io.out.bits.res,
                dut.io.out.bits.fflags,
                dut.io.out.bits.dstIsFp,
                dut.io.out.bits.tag,
                depth = 2,
                tagWidth = 7
            )
        }
    }
}
