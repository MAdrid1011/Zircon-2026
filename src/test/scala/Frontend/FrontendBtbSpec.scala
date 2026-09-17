import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FrontendBtbSpec extends AnyFreeSpec with ChiselSim {
    for (ways <- Seq(1, 2)) {
        s"a $ways-way BTB lookup rejects a valid row with a different tag" in {
            val p = FrontendParams()
            simulate(new BlockBTBLookup(p, 8, ways)) { d =>
                val pc = 0x80000000L
                val tag = pc >> 7
                d.io.pc.poke(pc)
                for (w <- 0 until ways) {
                    d.io.raw.tags(w).poke(tag + w + 1)
                    d.io.raw.lines(w).valid.poke(15)
                    d.io.raw.lines(w).kinds.foreach(_.poke(1))
                    d.io.raw.lines(w).targets.foreach(_.poke(0x20000001L + w))
                    d.io.raw.lines(w).backward.foreach(_.poke(true))
                }
                d.io.line.valid.expect(0)
                for (w <- 0 until ways) {
                    d.io.raw.tags(w).poke(tag)
                    d.io.line.valid.expect(15)
                    d.io.line.targets(0).expect(0x20000001L + w)
                    d.io.pc.poke(pc + 384)
                    d.io.line.valid.expect(0)
                    d.io.pc.poke(pc)
                    d.io.raw.lines(w).valid.poke(0)
                    d.io.line.valid.expect(0)
                    d.io.raw.lines(w).valid.poke(15)
                    d.io.raw.tags(w).poke(tag + w + 1)
                }
            }
        }
    }
}
