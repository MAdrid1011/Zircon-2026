import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class BlockBTBLookupTestTop(p: FrontendParams, sets: Int, ways: Int) extends Module {
    val io = IO(new BlockBTBLookupIO(p, sets, ways))
    val lookup = Module(new BlockBTBLookup(p, sets, ways))
    lookup.io.tag := io.tag
    lookup.io.raw := io.raw
    io.line := lookup.io.line
}

class FrontendBtbSpec extends AnyFreeSpec with ChiselSim {
    for (ways <- Seq(1, 2)) {
        s"a $ways-way BTB lookup rejects a valid row with a different tag" in {
            val p = FrontendParams()
            simulate(new BlockBTBLookupTestTop(p, 8, ways)) { d =>
                val pc = 0x80000000L
                val tag = pc >> 7
                d.io.tag.poke(tag)
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
                    d.io.tag.poke((pc + 384) >> 7)
                    d.io.line.valid.expect(0)
                    d.io.tag.poke(tag)
                    d.io.raw.lines(w).valid.poke(0)
                    d.io.line.valid.expect(0)
                    d.io.raw.lines(w).valid.poke(15)
                    d.io.raw.tags(w).poke(tag + w + 1)
                }
            }
        }
    }
}
