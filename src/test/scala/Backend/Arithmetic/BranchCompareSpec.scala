import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class BranchCompareSpec extends AnyFreeSpec with ChiselSim {
    "JALR prediction comparison preserves the cleared target bit" in {
        simulate(new Branch) { dut =>
            val rng = new scala.util.Random(20260927)
            val mask = 0xffffffffL
            val sources = Seq(0L, 1L, 0x7fffffffL, 0x80000000L, mask) ++
                Seq.fill(500)(rng.nextInt().toLong & mask)
            val immediates = Seq(0L, 1L, 2L, 0x7fffffffL, mask)
            for (source <- sources; imm <- immediates) {
                val target = ((source + imm) & mask) & ~1L
                dut.io.src1.poke(source)
                dut.io.src2.poke(0)
                dut.io.op.poke(0x1a)
                dut.io.pc.poke(0x80000000L)
                dut.io.imm.poke(imm)
                for (prediction <- Seq(target, target ^ 1L, target ^ 2L)) {
                    dut.io.predOffset.poke(prediction)
                    dut.io.jumpTgt.expect(target)
                    dut.io.predFail.expect(prediction != target)
                }
            }
        }
    }

    "conditional direction and misprediction match RV32 comparisons" in {
        simulate(new Branch) { dut =>
            val rng = new scala.util.Random(20260926)
            val ops = Seq(0x18, 0x19, 0x1c, 0x1d, 0x1e, 0x1f)
            val edge = Seq(0L, 1L, 0x7fffffffL, 0x80000000L, 0xffffffffL)
            val values = for (a <- edge; b <- edge) yield (a, b)
            val random = Seq.fill(500)((rng.nextInt().toLong & 0xffffffffL,
                rng.nextInt().toLong & 0xffffffffL))
            for ((a, b) <- values ++ random; op <- ops) {
                val signedA = a.toInt
                val signedB = b.toInt
                val taken = op match {
                    case 0x18 => a == b
                    case 0x19 => a != b
                    case 0x1c => signedA < signedB
                    case 0x1d => signedA >= signedB
                    case 0x1e => a < b
                    case 0x1f => a >= b
                }
                dut.io.src1.poke(a)
                dut.io.src2.poke(b)
                dut.io.op.poke(op)
                dut.io.pc.poke(0x80000000L)
                dut.io.imm.poke(8)
                dut.io.predOffset.poke(4)
                dut.io.realJp.expect(taken)
                dut.io.predFail.expect(taken)
            }
        }
    }
}
