import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class StatisticalCorrectorTestTop(p: FrontendParams) extends Module {
    val io = IO(new StatisticalCorrectorIO(p))
    val corrector = Module(new StatisticalCorrector(p))
    corrector.io.earlyDirections := io.earlyDirections
    corrector.io.meta := io.meta
    corrector.io.read := io.read
    io.directions := corrector.io.directions
    io.scPredictions := corrector.io.scPredictions
    io.scLowMargin := corrector.io.scLowMargin
}

class StatisticalCorrectorSpec extends AnyFreeSpec with ChiselSim {
    "parallel bias candidates match the original score and protection rule" in {
        val p = FrontendParams()
        simulate(new StatisticalCorrectorTestTop(p)) { d =>
            val random = new scala.util.Random(20260926L)
            for (sample <- 0 until 1024) {
                val tag = random.nextInt(1024)
                val biasTag = if (sample % 2 == 0) tag else tag ^ 1
                val biasValid = sample % 4 != 0
                val threshold = random.nextInt(128)
                val early = random.nextInt(1 << p.fetchWidth)
                val loopValid = random.nextInt(1 << p.fetchWidth)
                val loopPredictions = random.nextInt(1 << p.fetchWidth)
                val biasCounters = Seq.fill(p.fetchWidth)(random.nextInt(8))
                val biasStrong = Seq.fill(p.fetchWidth)(random.nextBoolean())
                val confidence = Seq.fill(p.fetchWidth)(random.nextInt(4))
                val scValid = Seq.fill(p.scCount)(random.nextInt(1 << p.fetchWidth))
                val scCounters = Seq.fill(p.scCount, p.fetchWidth)(random.nextInt(1 << p.scCounterBits))

                d.io.meta.poke(0.U.asTypeOf(new FrontendDirectionMeta(p)))
                d.io.read.poke(0.U.asTypeOf(new FrontendCorrectorRead(p)))
                d.io.earlyDirections.poke(early)
                d.io.meta.scBiasTag.poke(tag)
                d.io.meta.loopValid.poke(loopValid)
                d.io.meta.loopPredictions.poke(loopPredictions)
                d.io.read.biasValid.poke(biasValid)
                d.io.read.biasRow.tag.poke(biasTag)
                d.io.read.scThreshold.poke(threshold)
                for (rank <- 0 until p.fetchWidth) {
                    d.io.meta.tageConfidence(rank).poke(confidence(rank))
                    d.io.read.biasRow.counters(rank).poke(biasCounters(rank))
                    d.io.read.biasStrong(rank).poke(biasStrong(rank))
                }
                for (table <- 0 until p.scCount) {
                    d.io.read.scValid(table).poke(scValid(table))
                    for (rank <- 0 until p.fetchWidth) {
                        d.io.read.scRows(table).counters(rank).poke(scCounters(table)(rank))
                    }
                }

                var expectedDirections = 0
                var expectedPredictions = 0
                var expectedLowMargin = 0
                for (rank <- 0 until p.fetchWidth) {
                    val biasCounter = biasCounters(rank)
                    val strongCounter = biasCounter <= 1 || biasCounter >= 6
                    val useBias = biasValid && biasTag == tag && strongCounter && biasStrong(rank)
                    val biasDirection = if (useBias) (biasCounter & 4) != 0 else (early & (1 << rank)) != 0
                    val magnitude = confidence(rank) match {
                        case 1 => 12
                        case 2 => 16
                        case _ => 8
                    }
                    val componentSum = (0 until p.scCount).map { table =>
                        if ((scValid(table) & (1 << rank)) != 0)
                            2 * scCounters(table)(rank) - ((1 << p.scCounterBits) - 1)
                        else 0
                    }.sum
                    val score = componentSum + (if (biasDirection) magnitude else -magnitude)
                    val prediction = score >= 0
                    val absScore = math.abs(score)
                    val protect =
                        (confidence(rank) == 2 && absScore < (threshold >> 1)) ||
                            (confidence(rank) == 1 && absScore < (threshold >> 2))
                    val corrected = if (prediction != biasDirection && protect) biasDirection else prediction
                    val direction =
                        if ((loopValid & (1 << rank)) != 0) (loopPredictions & (1 << rank)) != 0
                        else corrected
                    if (direction) expectedDirections |= 1 << rank
                    if (prediction) expectedPredictions |= 1 << rank
                    if (absScore < threshold) expectedLowMargin |= 1 << rank
                }
                d.io.directions.expect(expectedDirections)
                d.io.scPredictions.expect(expectedPredictions)
                d.io.scLowMargin.expect(expectedLowMargin)
            }
        }
    }
}
