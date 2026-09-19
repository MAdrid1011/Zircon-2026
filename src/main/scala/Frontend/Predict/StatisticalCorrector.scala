import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class ScBiasRow(p: FrontendParams) extends Bundle {
    val tag = UInt(10.W)
    val counters = Vec(p.fetchWidth, UInt(3.W))
    val confidence = Vec(p.fetchWidth, UInt(3.W))
}

class ScRow(p: FrontendParams) extends Bundle {
    val counters = Vec(p.fetchWidth, UInt(p.scCounterBits.W))
}

class ScBiasPredictionRow(p: FrontendParams) extends Bundle {
    val tag = UInt(10.W)
    val counters = Vec(p.fetchWidth, UInt(3.W))
}

class FrontendCorrectorRead(p: FrontendParams) extends Bundle {
    val biasValid = Bool()
    val biasRow = new ScBiasPredictionRow(p)
    val biasStrong = Vec(p.fetchWidth, Bool())
    val scValid = Vec(p.scCount, UInt(p.fetchWidth.W))
    val scRows = Vec(p.scCount, new ScRow(p))
    val scThreshold = UInt(7.W)
}

class StatisticalCorrectorIO(p: FrontendParams) extends Bundle {
    val earlyDirections = Input(UInt(p.fetchWidth.W))
    val meta = Input(new FrontendDirectionMeta(p))
    val read = Input(new FrontendCorrectorRead(p))
    val directions = Output(UInt(p.fetchWidth.W))
    val scPredictions = Output(UInt(p.fetchWidth.W))
    val scLowMargin = Output(UInt(p.fetchWidth.W))
}

/** Compact TAGE-SC-L-inspired Multi-GEHL corrector evaluated in IF2. */
class StatisticalCorrector(p: FrontendParams) extends RawModule {
    val io = IO(new StatisticalCorrectorIO(p))

    val biasDirections = Wire(Vec(p.fetchWidth, Bool()))
    val directions = Wire(Vec(p.fetchWidth, Bool()))
    val scPredictions = Wire(Vec(p.fetchWidth, Bool()))
    val scLowMargin = Wire(Vec(p.fetchWidth, Bool()))
    val scoreBits = p.scCounterBits + log2Ceil(p.scCount + 2) + 2
    val counterCenter = ((BigInt(1) << p.scCounterBits) - 1).S((p.scCounterBits + 2).W)

    def addTree(values: Seq[SInt]): SInt = {
        require(values.nonEmpty)
        if (values.size == 1) values.head
        else addTree(values.grouped(2).map {
            case Seq(left, right) => left +& right
            case Seq(left) => left
        }.toSeq)
    }

    def contribution(counter: UInt): SInt =
        Cat(0.U(1.W), counter, 0.U(1.W)).asSInt - counterCenter

    for (rank <- 0 until p.fetchWidth) {
        /* Proven PC Bias */
        val biasRow = io.read.biasRow
        val counterStrong = biasRow.counters(rank) <= 1.U || biasRow.counters(rank) >= 6.U
        val useBias = io.read.biasValid && biasRow.tag === io.meta.scBiasTag && counterStrong &&
            io.read.biasStrong(rank)
        biasDirections(rank) := Mux(useBias, biasRow.counters(rank)(2), io.earlyDirections(rank))

        /* Multi-GEHL Statistical Correction */
        val components = (0 until p.scCount).map { table =>
            val valid = io.read.scValid(table)(rank)
            Mux(valid, contribution(io.read.scRows(table).counters(rank)), 0.S((p.scCounterBits + 2).W))
        }
        val baseMagnitude = MuxLookup(io.meta.tageConfidence(rank), 8.U(6.W))(Seq(
            1.U -> 12.U(6.W),
            2.U -> 16.U(6.W),
        ))
        val baseVote = Mux(biasDirections(rank), baseMagnitude.zext, -baseMagnitude.zext)
        val score = Wire(SInt(scoreBits.W))
        score := addTree(components :+ baseVote)
        val magnitude = Mux(score < 0.S, (-score).asUInt, score.asUInt)
        val scPrediction = score >= 0.S
        val protectHigh = io.meta.tageConfidence(rank) === 2.U && magnitude < (io.read.scThreshold >> 1)
        val protectMedium = io.meta.tageConfidence(rank) === 1.U && magnitude < (io.read.scThreshold >> 2)

        scPredictions(rank) := scPrediction
        scLowMargin(rank) := magnitude < io.read.scThreshold
        val corrected = Mux(
            scPrediction =/= biasDirections(rank) && (protectHigh || protectMedium),
            biasDirections(rank),
            scPrediction,
        )
        directions(rank) := Mux(io.meta.loopValid(rank), io.meta.loopPredictions(rank), corrected)
    }

    io.directions := directions.asUInt
    io.scPredictions := scPredictions.asUInt
    io.scLowMargin := scLowMargin.asUInt
}
