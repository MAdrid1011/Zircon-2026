import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class IndirectTargetPredictorSpec extends AnyFreeSpec with ChiselSim {
    private def checkTargets(backend: DualPortRamBackend): Unit = {
        val p = FrontendParams(fetchWidth = 4, ittageSets = 8)
        simulate(new IndirectTargetPredictor(p, backend)) { d =>
            case class Lookup(
                indices: Seq[BigInt],
                tags: Seq[BigInt],
                providers: Seq[BigInt],
                providerTargets: Seq[BigInt],
                alternateValid: BigInt,
                alternateTargets: Seq[BigInt],
                confidence: Seq[BigInt],
            )

            val pc = BigInt("80000820", 16)
            val targetA = BigInt("80000824", 16)
            val targetB = BigInt("800008a4", 16)
            val baseTarget = BigInt("80000844", 16)

            def clearTrain(): Unit = {
                d.io.train.valid.poke(false)
                d.io.train.bits.poke(0.U.asTypeOf(new FrontendTraining(p)))
                d.io.trainRead.valid.poke(false)
                d.io.trainRead.bits.poke(0.U.asTypeOf(new FrontendTraining(p)))
            }

            def lookup(history: BigInt): Lookup = {
                clearTrain()
                d.io.query.pcWord.poke(pc >> 2)
                for (i <- 0 until p.tageCount) {
                    d.io.query.folds(i).poke(history + i)
                }
                d.io.query.fire.poke(true)
                d.io.query.invalidate.poke(false)
                d.clock.step()
                d.io.query.fire.poke(false)
                Lookup(
                    (0 until p.ittageCount).map(i => d.io.meta.indices(i).peek().litValue),
                    (0 until p.ittageCount).map(i => d.io.meta.tags(i).peek().litValue),
                    (0 until p.fetchWidth).map(i => d.io.meta.providers(i).peek().litValue),
                    (0 until p.fetchWidth).map(i => d.io.meta.providerTargets(i).peek().litValue),
                    d.io.meta.alternateValid.peek().litValue,
                    (0 until p.fetchWidth).map(i => d.io.meta.alternateTargets(i).peek().litValue),
                    (0 until p.fetchWidth).map(i => d.io.meta.providerConfidence(i).peek().litValue),
                )
            }

            def train(meta: Lookup, actual: BigInt, predicted: BigInt, collideRead: Boolean = false): Unit = {
                d.io.train.bits.poke(0.U.asTypeOf(new FrontendTraining(p)))
                d.io.train.bits.pcWord.poke(pc >> 2)
                d.io.train.bits.mask.poke(1)
                d.io.train.bits.kinds(0).poke(FrontendCfi.Indirect)
                d.io.train.bits.taken.poke(1)
                d.io.train.bits.targets(0).poke(actual)
                d.io.train.bits.meta.ittage.aheadValid.poke(true)
                for (i <- 0 until p.ittageCount) {
                    d.io.train.bits.meta.ittage.indices(i).poke(meta.indices(i))
                    d.io.train.bits.meta.ittage.tags(i).poke(meta.tags(i))
                }
                for (i <- 0 until p.fetchWidth) {
                    d.io.train.bits.meta.ittage.providers(i).poke(meta.providers(i))
                    d.io.train.bits.meta.ittage.providerTargets(i).poke(meta.providerTargets(i))
                    d.io.train.bits.meta.ittage.alternateTargets(i).poke(
                        if (((meta.alternateValid >> i) & 1) != 0) meta.alternateTargets(i) else baseTarget
                    )
                    d.io.train.bits.meta.ittage.predictedTargets(i).poke(if (i == 0) predicted else BigInt(0))
                }
                d.io.trainRead.bits.poke(d.io.train.bits.peek())
                d.io.trainRead.valid.poke(true)
                d.clock.step()
                d.io.trainRead.valid.poke(collideRead)
                d.io.train.valid.poke(true)
                d.clock.step()
                if (collideRead) {
                    // The colliding read must see the just-allocated valid bit
                    // and row on the next edge, so this provider hit trains
                    // confidence instead of silently missing the row.
                    d.io.trainRead.valid.poke(false)
                    d.io.train.bits.meta.ittage.providers(0).poke(1)
                    d.io.train.bits.meta.ittage.providerTargets(0).poke(actual)
                    d.io.train.bits.meta.ittage.predictedTargets(0).poke(actual)
                    d.clock.step()
                }
                clearTrain()
            }

            d.io.query.invalidate.poke(false)
            clearTrain()

            val emptyA = lookup(0x11)
            assert(emptyA.providers.head == 0)
            train(emptyA, targetA, baseTarget, collideRead = true)
            val weakA = lookup(0x11)
            assert(weakA.providers.head != 0 && weakA.providerTargets.head == targetA &&
                weakA.confidence.head > 0)
            train(weakA, targetA, baseTarget)

            val emptyB = lookup(0x62)
            assert(emptyB.providers.head == 0)
            train(emptyB, targetB, baseTarget)
            val weakB = lookup(0x62)
            assert(weakB.providers.head != 0 && weakB.providerTargets.head == targetB)
            train(weakB, targetB, baseTarget)

            val learnedA = lookup(0x11)
            val learnedB = lookup(0x62)
            assert(learnedA.providerTargets.head == targetA && learnedA.confidence.head > 0)
            assert(learnedB.providerTargets.head == targetB && learnedB.confidence.head > 0)
        }
    }

    "register RAM: different histories retain different indirect targets" in {
        checkTargets(DualPortRamBackend.Registers)
    }

    "BSG RAM: different histories retain different indirect targets" in {
        checkTargets(DualPortRamBackend.BSG)
    }
}
