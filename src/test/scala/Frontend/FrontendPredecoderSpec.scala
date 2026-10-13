import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FrontendPredecoderTestTop(vectorEnabled: Boolean = false) extends Module {
    val p = FrontendParams(rasDepth = 2)
    val io = IO(new Bundle {
        val pc = Input(UInt(32.W))
        val instructions = Input(Vec(4, UInt(32.W)))
        val faults = Input(UInt(4.W))
        val directions = Input(UInt(4.W))
        val rasTop = Input(UInt(32.W))
        val rasValid = Input(Bool())
        val earlyNext = Input(UInt(32.W))
        val earlyTaken = Input(UInt(4.W))
        val earlyMask = Input(UInt(4.W))
        val earlyKinds = Input(Vec(4, UInt(3.W)))
        val out = Output(new FrontendPackage(p))
        val prediction = Output(new FrontendPrediction(p))
        val changed = Output(Bool())
        val commitBackward = Output(UInt(4.W))
    })
    val pd = Module(new PreDecoders(p, vectorEnabled))
    val packet = WireDefault(0.U.asTypeOf(new FrontendPackage(p)))
    packet.startPc := io.pc
    packet.predict.range := 15.U
    packet.predict.directions := io.directions
    packet.predict.before.top := io.rasTop(31, 2)
    packet.predict.before.count := io.rasValid
    packet.predict.early.nextPc := io.earlyNext
    packet.predict.early.mask := io.earlyMask
    packet.predict.early.taken := io.earlyTaken
    packet.predict.early.kinds := io.earlyKinds
    for (i <- 0 until 4) {
        val fields = Module(new PredecodeFields)
        fields.io.inst := io.instructions(i)
        packet.predict.fields(i) := fields.io.fields
        packet.instructions(i).inst := io.instructions(i)
        packet.instructions(i).fault := io.faults(i)
        packet.predict.meta.ittage.providers(i) := (i + 1).U
        packet.predict.meta.ittage.providerTargets(i) := (0x1000 + i * 16).U
        packet.predict.meta.ittage.alternateTargets(i) := (0x2000 + i * 16).U
        packet.predict.meta.ittage.predictedTargets(i) := (0x3000 + i * 16).U
    }
    pd.io.in := packet
    io.out := pd.io.out
    io.prediction := pd.io.prediction
    io.changed := pd.io.changed
    io.commitBackward := VecInit((0 until 4).map(i =>
        FrontendCfi.conditional(pd.io.prediction.kinds(i)) &&
            FrontendMath.backwardBranch(FrontendMath.slotPc(io.pc, i, p), pd.io.prediction.targets(i))
    )).asUInt
}

class FrontendPredecoderSpec extends AnyFreeSpec with ChiselSim {
    "performance PD supplies scalar operand domains for vector arithmetic, configuration and memory" in {
        simulate(new FrontendPredecoderTestTop(vectorEnabled = true)) { d =>
            d.io.pc.poke(0x80001000L); d.io.directions.poke(0)
            d.io.rasTop.poke(0); d.io.rasValid.poke(false)
            d.io.earlyNext.poke(0x80001010L); d.io.earlyTaken.poke(0)
            d.io.earlyMask.poke(15); d.io.earlyKinds.foreach(_.poke(0))
            def arithmetic(funct: Int, form: Int, rs1: Int = 3, rd: Int = 8): BigInt =
                (BigInt(funct) << 26) | (1 << 25) | (8 << 20) |
                    (rs1 << 15) | (form << 12) | (rd << 7) | 0x57
            // Each domain is unused, integer or FP. The expectations describe
            // ISA operands independently of the hardware classification helper.
            val cases = Seq(
                ("vadd.vv", arithmetic(0, 0), Seq(0, 0, 0), 0),
                ("vadd.vi", arithmetic(0, 3), Seq(0, 0, 0), 0),
                ("vadd.vx", arithmetic(0, 4), Seq(1, 0, 0), 0),
                ("vadd.vx x0", arithmetic(0, 4, rs1 = 0), Seq(0, 0, 0), 0),
                ("vfmul.vf", arithmetic(0x24, 5), Seq(2, 0, 0), 0),
                ("vfmul.vf f0", arithmetic(0x24, 5, rs1 = 0), Seq(2, 0, 0), 0),
                ("vmul.vx", arithmetic(0x25, 6), Seq(1, 0, 0), 0),
                ("vmv.x.s", arithmetic(0x10, 2, rs1 = 0), Seq(0, 0, 0), 1),
                ("vmv.x.s x0", arithmetic(0x10, 2, rs1 = 0, rd = 0), Seq(0, 0, 0), 0),
                ("vfmv.f.s f0", arithmetic(0x10, 1, rs1 = 0, rd = 0), Seq(0, 0, 0), 2),
                ("vcpop.m", arithmetic(0x10, 2, rs1 = 16), Seq(0, 0, 0), 1),
                ("vfirst.m", arithmetic(0x10, 2, rs1 = 17), Seq(0, 0, 0), 1),
                ("vsetvli", arithmetic(0, 7), Seq(1, 0, 0), 1),
                ("vsetivli", arithmetic(0x30, 7), Seq(0, 0, 0), 1),
                ("vsetvl", arithmetic(0x20, 7) & ~(BigInt(1) << 25), Seq(1, 1, 0), 1),
                ("vle32.v", BigInt(0x0201e407L), Seq(1, 0, 0), 0),
                ("vlse32.v", BigInt(0x0a81e407L), Seq(1, 1, 0), 0),
                ("vluxei32.v", BigInt(0x0681e407L), Seq(1, 0, 0), 0),
                ("vse32.v", BigInt(0x0201e427L), Seq(1, 0, 0), 0),
                ("vsse32.v", BigInt(0x0a81e427L), Seq(1, 1, 0), 0),
                ("flw", BigInt(0x0001a407L), Seq(1, 0, 0), 2),
                ("fsw", BigInt(0x0081a027L), Seq(1, 2, 0), 0),
            )
            for ((name, word, sources, destination) <- cases) {
                d.io.instructions.foreach(_.poke(word)); d.io.faults.poke(0)
                for (slot <- 0 until 4) {
                    val rinfo = d.io.out.instructions(slot).rinfo
                    for ((domain, index) <- sources.zipWithIndex) {
                        rinfo.src(index).valid.expect((domain != 0).B, name)
                        if (domain != 0) rinfo.src(index).isFp.expect((domain == 2).B, name)
                    }
                    rinfo.dest.valid.expect((destination != 0).B, name)
                    if (destination != 0) rinfo.dest.isFp.expect((destination == 2).B, name)
                }
                d.io.faults.poke(15)
                d.io.out.instructions.foreach { instruction =>
                    instruction.rinfo.src.foreach(_.valid.expect(false))
                    instruction.rinfo.dest.valid.expect(false)
                }
            }
        }
    }

    "PD retains only the indirect slot needed for commit training" in {
        simulate(new FrontendPredecoderTestTop) { d =>
            d.io.pc.poke(0x80001000L)
            d.io.directions.poke(0)
            d.io.rasTop.poke(0)
            d.io.rasValid.poke(false)
            d.io.earlyNext.poke(0x80001010L)
            d.io.earlyTaken.poke(0)
            d.io.earlyMask.poke(15)
            d.io.earlyKinds.foreach(_.poke(0))
            for (slot <- 0 until 4) {
                d.io.faults.poke(0)
                d.io.instructions.zipWithIndex.foreach { case (word, i) =>
                    word.poke(if (i == slot) 0x10067 else 0x13)
                }
                val meta = d.io.out.record.train.meta.ittage
                meta.provider.expect(slot + 1)
                meta.providerTarget.expect(0x1000 + slot * 16)
                meta.alternateTarget.expect(0x2000 + slot * 16)
                meta.predictedTarget.expect(0x3000 + slot * 16)
                d.io.faults.poke(1 << slot)
                meta.provider.expect(0)
                meta.providerTarget.expect(0)
            }
        }
    }

    "PD handles signed return offsets, address carries, empty RAS, and fetch faults" in {
        simulate(new FrontendPredecoderTestTop) { d =>
            d.io.directions.poke(0)
            val rng = new scala.util.Random(20260910)
            val boundaryAddresses = Seq(0L, 0xffcL, 0x1000L, 0x7ffffffcL, 0x80000000L, 0xfffffffcL)
            val offsets = Seq(-2048, -2047, -4, -1, 0, 1, 4, 2047)
            for (n <- 0 until 400) {
                val slot = n % 4
                val pc = 0x80001000L
                val top = if (n < 48) boundaryAddresses(n / 8) else rng.nextLong() & 0xfffffffcL
                val offset = if (n < 48) offsets(n % 8) else rng.nextInt(4096) - 2048
                val valid = n < 48 || rng.nextBoolean()
                val fault = n >= 48 && rng.nextInt(8) == 0
                val inst = ((offset.toLong & 4095) << 20) | (1 << 15) | 0x67
                d.io.pc.poke(pc)
                d.io.rasTop.poke(top)
                d.io.rasValid.poke(valid)
                d.io.faults.poke(if (fault) 1 << slot else 0)
                d.io.instructions.zipWithIndex.foreach { case (word, i) => word.poke(if (i == slot) inst else 0x13) }
                val target = if (valid) (top + offset) & 0xfffffffeL else pc + slot * 4 + 4
                val finalMask = (1 << (slot + 1)) - 1
                val finalTaken = 1 << slot
                val finalNext = if ((target & 3) != 0) pc + 16 else target
                val earlyTaken = if (rng.nextBoolean()) 1 << slot else 0
                val earlyMask = if (earlyTaken != 0) (1 << (slot + 1)) - 1 else 15
                val earlyKind = if (earlyTaken != 0) { if (rng.nextBoolean()) 6 else 4 }
                else 0
                val earlyNext = if (earlyTaken == 0) pc + 16 else finalNext ^ (if (rng.nextBoolean()) 4 else 0)
                d.io.earlyNext.poke(earlyNext)
                d.io.earlyTaken.poke(earlyTaken)
                d.io.earlyMask.poke(earlyMask)
                d.io.earlyKinds.zipWithIndex.foreach { case (k, i) => k.poke(if (i == slot) earlyKind else 0) }
                d.io.out.mask.expect(finalMask)
                d.io.prediction.taken.expect(finalTaken)
                d.io.out.nextPc.expect(finalNext)
                d.io.changed.expect(finalNext != earlyNext || finalMask != earlyMask ||
                    finalTaken != earlyTaken || earlyKind != 6)
                d.io.out.instructions(slot).kind.expect(if (fault) 0 else 6)
                d.io.out.instructions(slot).rinfo.src(0).valid.expect(!fault)
                if (!fault) d.io.out.instructions(slot).predictedValue.expect(target)
            }
        }
    }
    "PD and commit classify backward conditional branches across 32-bit address wrap" in {
        simulate(new FrontendPredecoderTestTop) { d =>
            d.io.directions.poke(1)
            d.io.rasTop.poke(0); d.io.rasValid.poke(false); d.io.faults.poke(0)
            d.io.earlyTaken.poke(0); d.io.earlyMask.poke(15)
            d.io.earlyKinds.foreach(_.poke(0))
            for (pc <- Seq(0L, 0x80000000L, 0xfffffff0L); offset <- Seq(-4096, -4, -2, 0, 2, 4, 4094)) {
                val bits = offset & 8191
                val inst =
                    ((bits.toLong >> 12) << 31) |
                        (((bits >> 5) & 63) << 25) |
                        (2 << 20) |
                        (1 << 15) |
                        (((bits >> 1) & 15) << 8) |
                        (((bits >> 11) & 1) << 7) | 0x63
                d.io.pc.poke(pc)
                d.io.earlyNext.poke((pc + 16) & 0xffffffffL)
                d.io.instructions.zipWithIndex.foreach { case (word, i) => word.poke(if (i == 0) inst else 0x13) }
                val target = (pc + offset) & 0xffffffffL
                d.io.prediction.targets(0).expect(target)
                d.io.prediction.taken.expect(1)
                d.io.prediction.backward.expect(if (offset <= 0) 1 else 0)
                d.io.commitBackward.expect(if (offset <= 0) 1 else 0)
                d.io.out.nextPc.expect(if ((target & 3) == 0) target else (pc + 16) & 0xffffffffL)
            }
        }
    }
}
