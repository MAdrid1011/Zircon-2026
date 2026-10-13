import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class VectorArithmeticSpec extends AnyFreeSpec with ChiselSim {
    case class Sample(op: Int, rm: Int, a: BigInt, b: BigInt, c: BigInt, res: BigInt, flags: Int)
    case class Task(samples: Seq[Sample], active: Int, tag: Int, alu: Boolean = false,
                    logic: Boolean = false, minmax: Boolean = false, maximum: Boolean = false,
                    accumulate: Boolean = false, negate: Boolean = false)
    private def samples: Vector[Sample] = {
        val source = scala.io.Source.fromResource("shared-multiply-softfloat.txt")
        try source.getLines().filter(s => s.nonEmpty && !s.startsWith("#")).map { line =>
            val f = line.split(" ").map(BigInt(_, 16))
            Sample(f(0).toInt, f(1).toInt, f(2), f(3), f(4), f(5), f(6).toInt)
        }.toVector finally source.close()
    }
    "eight shared lanes sustain FMA each cycle and preserve tags, masking, stalls and cancellation" in {
        simulate(new VectorArithmetic) { dut =>
            val rng = new Random(20261013)
            val oracle = samples
            var pipe = Vector.fill[Option[Task]](6)(None)
            var cycle = 0
            var accepted = 0
            var completed = 0
            def tick(input: Option[Task], ready: Boolean = true, flush: Boolean = false): Unit = {
                val task = input.getOrElse(Task(Seq.fill(8)(oracle.head), 255, 0))
                val req = dut.io.in.bits
                req.fpLogic.poke(task.logic); req.minmax.poke(task.minmax); req.maximum.poke(task.maximum)
                req.integerAccumulate.poke(task.accumulate); req.negateProduct.poke(task.negate)
                dut.io.in.valid.poke(input.nonEmpty); dut.io.out.ready.poke(ready); dut.io.flush.poke(flush)
                req.multiply.poke(!task.alu && !task.logic); req.aluOp.poke(task.samples.head.op)
                req.multiplyOp.poke(if (task.alu || task.logic) 0 else task.samples.head.op)
                req.fp.poke(!task.alu && !task.logic && task.samples.head.op >= 4)
                req.roundingMode.poke(task.samples.head.rm); req.active.poke(task.active); req.tag.poke(task.tag)
                for ((sample, lane) <- task.samples.zipWithIndex) {
                    req.source(0)(lane).poke(sample.a); req.source(1)(lane).poke(sample.b); req.source(2)(lane).poke(sample.c)
                }
                val outputAdvance = pipe.last.isEmpty || ready
                val advance = pipe(4).isEmpty || outputAdvance
                dut.io.in.ready.expect(advance && !flush)
                dut.io.out.valid.expect(pipe.last.nonEmpty && !flush)
                if (!flush) pipe.last.foreach { expected =>
                    dut.io.out.bits.tag.expect(expected.tag)
                    dut.io.out.bits.active.expect(expected.active)
                    for ((sample, lane) <- expected.samples.zipWithIndex if (expected.active & (1 << lane)) != 0)
                        dut.io.out.bits.data(lane).expect(sample.res, s"cycle=$cycle lane=$lane op=${sample.op}")
                    val flags = expected.samples.zipWithIndex.filter { case (_, i) => (expected.active & (1 << i)) != 0 }
                        .map(_._1.flags).foldLeft(0)(_ | _)
                    dut.io.out.bits.fflags.expect(flags)
                    if (ready) completed += 1
                }
                if (flush) pipe = Vector.fill(6)(None)
                else {
                    val productPipe = if (advance) input +: pipe.take(4) else pipe.take(5)
                    pipe = productPipe :+ (if (outputAdvance) pipe(4) else pipe(5))
                    if (advance && input.nonEmpty) accepted += 1
                }
                dut.clock.step(); cycle += 1
            }
            val fma = oracle.filter(s => s.op == 7 && s.rm == 0)
            for (tag <- 0 until 128)
                tick(Some(Task(Seq.fill(8)(fma(tag % fma.size)), 255, tag)))
            for (_ <- 0 until 6) tick(None)
            assert(accepted == 128 && completed == 128, "Eight independent FMA results must complete every cycle")
            val logicSource = scala.io.Source.fromResource("fp-units-softfloat.txt")
            val logicOracle = try logicSource.getLines().filter(line => line.nonEmpty && !line.startsWith("#"))
                .map { line =>
                    val f = line.split(" ").map(BigInt(_, 16))
                    Sample(f(0).toInt, f(1).toInt, f(2), f(3), 0, f(4), f(5).toInt)
                }.filter(_.op <= 10).toVector finally logicSource.close()
            for ((op, values) <- logicOracle.groupBy(_.op).toSeq.sortBy(_._1); offset <- values.indices by 8) {
                tick(Some(Task((0 until 8).map(i => values((offset + i) % values.size)),
                    rng.nextInt(256), cycle & 65535, logic = true)), ready = cycle % 7 != 0)
                tick(None)
            }
            for (unsigned <- Seq(false, true); maximum <- Seq(false, true); _ <- 0 until 32) {
                val op = (if (unsigned) ZirconConfig.EXEOp.SLTU else ZirconConfig.EXEOp.SLT).litValue.toInt
                val selected = Seq.fill(8) {
                    val a = BigInt(32, rng); val b = BigInt(32, rng)
                    val less = if (unsigned) a < b else a.toInt < b.toInt
                    Sample(op, 0, a, b, 0, if (less ^ maximum) a else b, 0)
                }
                tick(Some(Task(selected, 255, cycle & 65535, alu = true, minmax = true, maximum = maximum)))
            }
            val groups = oracle.groupBy(s => (s.op, s.rm)).toSeq.sortBy(_._1)
            for (((op, rm), values) <- groups; offset <- values.indices by 8) {
                val selected = (0 until 8).map(i => values((offset + i) % values.size))
                tick(Some(Task(selected, rng.nextInt(256), (cycle + 128) & 65535)))
            }
            val mask = (BigInt(1) << 32) - 1
            for (negate <- Seq(false, true); tag <- 0 until 128) {
                val lanes = Seq.fill(8) {
                    val a = BigInt(32, rng); val b = BigInt(32, rng); val c = BigInt(32, rng)
                    Sample(0, 0, a, b, c, (if (negate) c - a * b else c + a * b) & mask, 0)
                }
                tick(Some(Task(lanes, 255, 30000 + tag, accumulate = true, negate = negate)))
            }
            val aluOps = Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 13)
            for (tag <- 0 until 256) {
                val op = aluOps(tag % aluOps.size)
                val lanes = Seq.fill(8) {
                    val a = BigInt(32, rng); val b = BigInt(32, rng)
                    def signed(x: BigInt): BigInt = if (x.testBit(31)) x - (BigInt(1) << 32) else x
                    val result = op match {
                        case 0 => a + b
                        case 1 => a << (b.toInt & 31)
                        case 2 => if (signed(a) < signed(b)) BigInt(1) else BigInt(0)
                        case 3 => if (a < b) BigInt(1) else BigInt(0)
                        case 4 => a ^ b
                        case 5 => a >> (b.toInt & 31)
                        case 6 => a | b
                        case 7 => a & b
                        case 8 => a - b
                        case 13 => signed(a) >> (b.toInt & 31)
                    }
                    Sample(op, 0, a, b, 0, result & mask, 0)
                }
                val task = Task(lanes, rng.nextInt(256), tag + 40000, alu = true)
                // Hold the producer task until accepted; output stalls affect every lane equally.
                val hold = rng.nextInt(6)
                for (_ <- 0 until hold) tick(None, ready = false)
                tick(Some(task))
                if (tag % 47 == 17) tick(None, flush = true)
            }
            for (_ <- 0 until 6) tick(None)
            info(s"$cycle cycles; eight-lane SoftFloat/BigInt, 128 consecutive FMA tasks, stall and flush coverage")
        }
    }
}
