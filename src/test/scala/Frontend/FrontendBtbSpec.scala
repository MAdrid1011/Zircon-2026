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
    for (backend <- Seq(DualPortRamBackend.Vivado, DualPortRamBackend.BSG)) {
        s"a synchronous fast BTB reads in PF and preserves its row while PF stalls ($backend)" in {
        val p = FrontendParams()
        simulate(new BlockBTB(p, p.fastBtbSets, 1, backend,
            synchronousPayload = true)) { d =>
            val index = 3
            val tag = 0x100
            def train(slot: Int, target: Int, newTag: Int = tag): Unit = {
                d.io.train.valid.poke(true)
                d.io.train.bits.index.poke(index)
                d.io.train.bits.indexOH.poke(BigInt(1) << index)
                d.io.train.bits.tag.poke(newTag)
                d.io.trainTagGroups.foreach(_.poke(newTag))
                d.io.train.bits.mask.poke(BigInt(1) << slot)
                d.io.train.bits.cfi.poke(BigInt(1) << slot)
                d.io.train.bits.backward.poke(0)
                for (i <- 0 until p.fetchWidth) {
                    d.io.train.bits.kinds(i).poke(if (i == slot) 1 else 0)
                    d.io.train.bits.targetWords(i).poke(if (i == slot) target else 0)
                }
                d.clock.step()
                d.io.train.valid.poke(false)
            }
            d.io.indexOH.poke(BigInt(1) << index)
            d.io.lookupIndex.get.poke(index)
            d.io.lookupTag.poke(tag)
            d.io.trainTagGroups.foreach(_.poke(tag))
            d.io.prefetch.get.valid.poke(false)
            d.io.prefetch.get.bits.poke(index)
            d.io.train.valid.poke(false)
            d.clock.step()

            train(0, 0x12345)
            d.io.prefetch.get.valid.poke(true)
            d.clock.step()
            d.io.prefetch.get.valid.poke(false)
            d.io.hits(0).expect(true)
            d.io.raw.lines(0).targets(0).expect(0x12345)
            d.io.raw.lines(0).valid.expect(1)

            train(2, 0x23456)
            d.io.raw.lines(0).targets(0).expect(0x12345)
            d.io.raw.lines(0).targets(2).expect(0x23456)
            d.io.raw.lines(0).valid.expect(5)
            d.clock.step()
            d.io.raw.lines(0).targets(0).expect(0x12345)
            d.io.raw.lines(0).targets(2).expect(0x23456)

            train(1, 0x34567, tag + 1)
            d.io.prefetch.get.valid.poke(true)
            d.clock.step()
            d.io.prefetch.get.valid.poke(false)
            d.io.lookupTag.poke(tag)
            d.io.hits(0).expect(false)
            d.io.lookupTag.poke(tag + 1)
            d.io.hits(0).expect(true)
            d.io.raw.lines(0).valid.expect(2)
            d.io.raw.lines(0).targets(1).expect(0x34567)
        }
    }
    }

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
