import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class TcBiasRow(p: FrontendParams) extends Bundle {
    val tag = UInt(10.W)
    val counters = Vec(p.fetchWidth, UInt(3.W))
    val confidence = Vec(p.fetchWidth, UInt(3.W))
}

class TcHistoryRow(p: FrontendParams) extends Bundle {
    val tag = UInt(8.W)
    val rank = UInt(p.slotBits.W)
    val counter = UInt(3.W)
    val confidence = UInt(4.W)
}

class IndirectTargetRow extends Bundle {
    val tag = UInt(8.W)
    val target = UInt(30.W)
}

class FrontendCorrectorRead(p: FrontendParams) extends Bundle {
    val biasValid = Bool()
    val biasRow = new TcBiasRow(p)
    val historyValid = Vec(2, Bool())
    val historyRows = Vec(2, new TcHistoryRow(p))
    val indirectValid = Bool()
    val indirectRow = new IndirectTargetRow
}

/** Select confident corrections for each conditional-branch rank in IF2. */
class TaggedCorrector(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val earlyDirections = Input(UInt(p.fetchWidth.W))
        val meta = Input(new FrontendDirectionMeta(p))
        val read = Input(new FrontendCorrectorRead(p))
        val biasDirections = Output(UInt(p.fetchWidth.W))
        val directions = Output(UInt(p.fetchWidth.W))
    })
    val biasDirections = Wire(Vec(p.fetchWidth, Bool()))
    val directions = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        /* PC Bias */
        val biasRow = io.read.biasRow
        val biasStrong = biasRow.counters(rank) <= 1.U || biasRow.counters(rank) >= 6.U
        val useBias = io.read.biasValid && biasRow.tag === io.meta.tcBiasTag && biasStrong &&
            biasRow.confidence(rank) >= 4.U
        biasDirections(rank) := Mux(useBias, biasRow.counters(rank)(2), io.earlyDirections(rank))

        /* History Correction */
        // A confident history hit overrides the PC-bias prediction; otherwise keep the earlier result.
        val hits = (0 until 2).map { way =>
            val historyRow = io.read.historyRows(way)
            io.read.historyValid(way) && historyRow.tag === io.meta.tcHistoryTag && historyRow.rank === rank.U &&
            (historyRow.counter <= 1.U || historyRow.counter >= 6.U) && historyRow.confidence >= 8.U
        }
        directions(rank) :=
            Mux(hits.reduce(_ || _), PriorityMux(hits.zip(io.read.historyRows.map(_.counter(2)))), biasDirections(rank))
    }
    io.biasDirections := biasDirections.asUInt
    io.directions := directions.asUInt
}
