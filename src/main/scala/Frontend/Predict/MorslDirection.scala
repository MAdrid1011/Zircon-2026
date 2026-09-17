import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class TageRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.tageTagBits.W)
    val rank = UInt(p.slotBits.W)
    val counter = UInt(3.W)
    val useful = Bool()
}

/** MORSL-derived rank-tagged tables with predecessor indexing and delayed TC selection.
  * Learning rules are project-specific; this is not the original CBP submission.
  */
class MorslDirection(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val query = new Bundle {
            val pc = Input(UInt(32.W))
            val folds = Input(Vec(p.tageCount, UInt(p.hashBits.W)))
            val loop = Input(UInt(8.W))
            val fire = Input(Bool())
            val invalidate = Input(Bool())
        }
        val directions = Output(UInt(p.fetchWidth.W))
        val meta = Output(new FrontendDirectionMeta(p))
        val tcRead = Output(new FrontendCorrectorRead(p))
        val train = Flipped(Valid(new FrontendTraining(p)))
    })

    /* Prediction Tables */
    // PC-indexed PHT: one 2-bit counter per conditional rank, used when TAGE has no provider.
    val pht = Mem(p.phtSets, Vec(p.fetchWidth, UInt(2.W)))
    val phtValid = RegInit(VecInit.fill(p.phtSets)(false.B))
    // Tagged tables are ordered by increasing history length; each row stores one rank.
    val tageTables = Seq.fill(p.tageCount)(Mem(p.tageSets, new TageRow(p)))
    val tageValid = Seq.fill(p.tageCount)(RegInit(VecInit.fill(p.tageSets)(false.B)))
    // TC uses a PC-bias table and a two-way history table.
    val tcBiasTable = Mem(p.tcSets, new TcBiasRow(p))
    val tcBiasValid = RegInit(VecInit.fill(p.tcSets)(false.B))
    val tcHistoryTables = Seq.fill(2)(Mem(p.tcSets, new TcHistoryRow(p)))
    val tcHistoryValid = Seq.fill(2)(RegInit(VecInit.fill(p.tcSets)(false.B)))
    val tcHistoryReplace = RegInit(VecInit.fill(p.tcSets)(false.B))

    /* Ahead Registers */
    // Predecessor-read rows for the next block. Saved valid bits are not tag-match results.
    val aheadTageRows = Reg(Vec(p.tageCount, new TageRow(p)))
    val aheadTageValid = Reg(Vec(p.tageCount, Bool()))
    val aheadTageIndices = Reg(Vec(p.tageCount, UInt(p.tageIndexBits.W)))
    val aheadTcHistoryRows = Reg(Vec(2, new TcHistoryRow(p)))
    val aheadTcHistoryValid = Reg(Vec(2, Bool()))
    val aheadTcHistoryIndex = Reg(UInt(p.tcIndexBits.W))
    val aheadValid = RegInit(false.B)

    /* Query Indices and Tags */
    val pcWord = io.query.pc(31, 2)
    val phtIndex = FrontendMath.fold(pcWord, p.phtIndexBits)
    val tcBiasIndex = FrontendMath.fold(pcWord, p.tcIndexBits)
    val tcHistoryIndex = FrontendMath.fold(pcWord ^ io.query.folds.last ^ io.query.loop, p.tcIndexBits)
    val tageTags = VecInit((0 until p.tageCount).map(i =>
        (FrontendMath.fold(pcWord, p.tageTagBits) ^ io.query.folds(i))(p.tageTagBits - 1, 0)
    ))
    val phtRaw = pht.read(phtIndex)
    val phtRData = Wire(Vec(p.fetchWidth, UInt(2.W)))
    for (rank <- 0 until p.fetchWidth) {
        phtRData(rank) := Mux(phtValid(phtIndex), phtRaw(rank), 1.U)
    }

    /* Provider Selection */
    val directions = Wire(Vec(p.fetchWidth, Bool()))
    val alternateDirections = Wire(Vec(p.fetchWidth, Bool()))
    val tageProviders = Wire(Vec(p.fetchWidth, UInt(p.providerBits.W)))
    // Tables are ordered from short to long history. Keep selection parallel and one-hot.
    def longest(candidates: Seq[Bool]): Seq[Bool] = candidates.indices.map { i =>
        candidates(i) && !candidates.drop(i + 1).foldLeft(false.B)(_ || _)
    }
    for (rank <- 0 until p.fetchWidth) {
        val hits = (0 until p.tageCount).map(table =>
            aheadValid && aheadTageValid(table) && aheadTageRows(table).tag === tageTags(table) &&
                aheadTageRows(table).rank === rank.U
        )
        val provider = longest(hits)
        val alternateHits = hits.zip(provider).map { case (hit, selected) => hit && !selected }
        val alternateProvider = longest(alternateHits)
        val predictions = aheadTageRows.map(_.counter(2))
        directions(rank) := Mux1H(provider.zip(predictions) :+ (!hits.reduce(_ || _) -> phtRData(rank)(1)))
        alternateDirections(rank) :=
            Mux1H(alternateProvider.zip(predictions) :+ (!alternateHits.reduce(_ || _) -> phtRData(rank)(1)))
        tageProviders(rank) := Mux(
            hits.reduce(_ || _),
            Mux1H(provider.zipWithIndex.map { case (selected, i) => selected -> (i + 1).U(p.providerBits.W) }),
            0.U
        )
    }

    /* Prediction Metadata */
    // Retain the lookup keys until commit; training must not use a newer speculative history.
    io.directions := directions.asUInt
    io.meta.phtIndex := phtIndex
    io.meta.tageIndices := aheadTageIndices
    io.meta.tageTags := tageTags
    io.meta.tageProviders := tageProviders
    io.meta.alternateDirections := alternateDirections.asUInt
    io.meta.aheadValid := aheadValid
    io.meta.tcBiasIndex := tcBiasIndex
    io.meta.tcBiasTag := FrontendMath.fold(pcWord, 10)
    io.meta.tcHistoryIndex := aheadTcHistoryIndex
    io.meta.tcHistoryTag := FrontendMath.fold(pcWord ^ io.query.loop ^ io.query.folds.head, 8)
    io.tcRead.biasRow := tcBiasTable.read(tcBiasIndex)
    io.tcRead.biasValid := tcBiasValid(tcBiasIndex)
    io.tcRead.historyRows := aheadTcHistoryRows
    io.tcRead.historyValid := VecInit(aheadTcHistoryValid.map(_ && aheadValid))

    /* Ahead Read */
    // Stalls hold all ahead rows and indices; recovery wins over a same-cycle read.
    when(io.query.fire) {
        aheadValid := true.B
        for (table <- 0 until p.tageCount) {
            val tageIndex = FrontendMath.fold(pcWord ^ io.query.folds(table) ^ (table + 1).U, p.tageIndexBits)
            aheadTageRows(table) := tageTables(table).read(tageIndex)
            aheadTageValid(table) := tageValid(table)(tageIndex)
            aheadTageIndices(table) := tageIndex
        }
        for (way <- 0 until 2) {
            aheadTcHistoryRows(way) := tcHistoryTables(way).read(tcHistoryIndex)
            aheadTcHistoryValid(way) := tcHistoryValid(way)(tcHistoryIndex)
        }
        aheadTcHistoryIndex := tcHistoryIndex
    }
    when(io.query.invalidate) { aheadValid := false.B }

    /* Committed Slot to Conditional Rank */
    val train = io.train.bits
    val trainPcWord = train.pc(31, 2)
    val trainPhtIndex = FrontendMath.fold(trainPcWord, p.phtIndexBits)
    val trainTcBiasIndex = FrontendMath.fold(trainPcWord, p.tcIndexBits)
    val trainTcBiasTag = FrontendMath.fold(trainPcWord, 10)
    val trainRanks = Wire(Vec(p.fetchWidth, Bool()))
    val trainDirections = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        val hits = (0 until p.fetchWidth).map { slot =>
            val rankBefore = if (slot == 0) 0.U
            else PopCount((0 until slot).map(j =>
                train.mask(j) && FrontendCfi.conditional(train.kinds(j))
            ))
            train.mask(slot) && FrontendCfi.conditional(train.kinds(slot)) && rankBefore === rank.U
        }
        trainRanks(rank) := hits.reduce(_ || _)
        trainDirections(rank) := Mux1H(hits, train.taken.asBools)
    }

    /* PHT Training */
    val phtTrainRaw = pht.read(trainPhtIndex)
    val phtTrain = Wire(Vec(p.fetchWidth, UInt(2.W)))
    val phtNext = Wire(Vec(p.fetchWidth, UInt(2.W)))
    for (rank <- 0 until p.fetchWidth) {
        phtTrain(rank) := Mux(phtValid(trainPhtIndex), phtTrainRaw(rank), 1.U)
        phtNext(rank) := Mux(
            trainRanks(rank),
            FrontendMath.sat(phtTrain(rank), trainDirections(rank)),
            phtTrain(rank)
        )
    }
    when(io.train.valid && trainRanks.asUInt.orR) {
        pht.write(trainPhtIndex, phtNext)
        phtValid(trainPhtIndex) := true.B
    }

    /* TAGE Training and Allocation */
    // Allocate beyond the first mispredicted rank's provider; age useful bits if all candidates are busy.
    val tageTrainRows = (0 until p.tageCount).map(table =>
        tageTables(table).read(train.meta.tageIndices(table))
    )
    val tageTrainValid = (0 until p.tageCount).map(table =>
        tageValid(table)(train.meta.tageIndices(table))
    )
    val tageErrors = trainRanks.asUInt & (train.earlyDirections ^ trainDirections.asUInt)
    val tageErrorRank = PriorityEncoder(tageErrors)
    val tageErrorProvider = train.meta.tageProviders(tageErrorRank)
    val tageAllocatable = VecInit((0 until p.tageCount).map(table =>
        (table + 1).U > tageErrorProvider && (
            !tageTrainValid(table) || !tageTrainRows(table).useful
        )
    ))
    val tageAllocate = PriorityEncoderOH(tageAllocatable)
    for (table <- 0 until p.tageCount) {
        val old = tageTrainRows(table)
        val next = WireDefault(old)
        val oldRank = if (p.fetchWidth == 1) 0.U else old.rank
        val earlyBit = if (p.fetchWidth == 1) train.earlyDirections(0) else train.earlyDirections(oldRank)
        val alternateBit =
            if (p.fetchWidth == 1) train.meta.alternateDirections(0) else train.meta.alternateDirections(oldRank)
        val matching = tageTrainValid(table) && old.tag === train.meta.tageTags(table) && trainRanks(oldRank)
        val ageUseful = tageErrors.orR && !tageAllocatable.asUInt.orR && (table + 1).U > tageErrorProvider
        val allocate = tageErrors.orR && tageAllocate(table)
        when(matching) {
            next.counter := FrontendMath.sat(old.counter, trainDirections(oldRank))
            when(train.meta.tageProviders(oldRank) === (table + 1).U && earlyBit =/= alternateBit) {
                next.useful := earlyBit === trainDirections(oldRank)
            }
        }
        when(ageUseful) {
            next.useful := false.B
        }
        when(allocate) {
            next.tag := train.meta.tageTags(table)
            next.rank := tageErrorRank
            next.counter := Mux(trainDirections(tageErrorRank), 4.U, 3.U)
            next.useful := false.B
        }
        when(io.train.valid && train.meta.aheadValid && (matching || ageUseful || allocate)) {
            tageTables(table).write(train.meta.tageIndices(table), next)
            when(allocate) {
                tageValid(table)(train.meta.tageIndices(table)) := true.B
            }
        }
    }

    /* TC Bias Training */
    val tcBiasTrain = tcBiasTable.read(trainTcBiasIndex)
    val tcBiasNext = WireDefault(tcBiasTrain)
    val tcBiasMatched = tcBiasValid(trainTcBiasIndex) && tcBiasTrain.tag === trainTcBiasTag
    tcBiasNext.tag := trainTcBiasTag
    for (rank <- 0 until p.fetchWidth) {
        when(!tcBiasMatched) {
            tcBiasNext.counters(rank) := Mux(trainRanks(rank) && trainDirections(rank), 4.U, 3.U)
            tcBiasNext.confidence(rank) := 0.U
        }.elsewhen(trainRanks(rank)) {
            val correct = tcBiasTrain.counters(rank)(2) === trainDirections(rank)
            tcBiasNext.counters(rank) := FrontendMath.sat(tcBiasTrain.counters(rank), trainDirections(rank))
            when(!correct || train.earlyDirections(rank) =/= trainDirections(rank)) {
                tcBiasNext.confidence(rank) := FrontendMath.sat(tcBiasTrain.confidence(rank), correct)
            }
        }
    }
    when(io.train.valid && trainRanks.asUInt.orR) {
        tcBiasTable.write(trainTcBiasIndex, tcBiasNext)
        tcBiasValid(trainTcBiasIndex) := true.B
    }

    /* TC History Training */
    // Train one rank: prefer a bias error, otherwise use the first committed conditional branch.
    val historyErrors = trainRanks.asUInt & (train.biasDirections ^ trainDirections.asUInt)
    val historyRank = if (p.fetchWidth == 1) 0.U
    else Mux(historyErrors.orR, PriorityEncoder(historyErrors), PriorityEncoder(trainRanks))
    val historyBiasBit = if (p.fetchWidth == 1) train.biasDirections(0) else train.biasDirections(historyRank)
    val tcHistoryTrain = (0 until 2).map(way => tcHistoryTables(way).read(train.meta.tcHistoryIndex))
    val tcHistoryTrainValid = (0 until 2).map(way => tcHistoryValid(way)(train.meta.tcHistoryIndex))
    val historyMatches = VecInit((0 until 2).map { way =>
        tcHistoryTrainValid(way) && tcHistoryTrain(way).tag === train.meta.tcHistoryTag &&
        tcHistoryTrain(way).rank === historyRank
    })
    val historyWay =
        Mux(
            historyMatches.asUInt.orR,
            PriorityEncoder(historyMatches),
            tcHistoryReplace(train.meta.tcHistoryIndex).asUInt
        )
    for (way <- 0 until 2) {
        val old = tcHistoryTrain(way)
        val next = WireDefault(old)
        when(historyMatches(way)) {
            val correct = old.counter(2) === trainDirections(historyRank)
            next.counter := FrontendMath.sat(old.counter, trainDirections(historyRank))
            when(!correct || historyBiasBit =/= trainDirections(historyRank)) {
                next.confidence := FrontendMath.sat(old.confidence, correct)
            }
        }.otherwise {
            next.tag := train.meta.tcHistoryTag
            next.rank := historyRank
            next.counter := Mux(trainDirections(historyRank), 4.U, 3.U)
            next.confidence := 0.U
        }
        when(io.train.valid && train.meta.aheadValid && trainRanks.asUInt.orR && historyWay === way.U) {
            tcHistoryTables(way).write(train.meta.tcHistoryIndex, next)
            tcHistoryValid(way)(train.meta.tcHistoryIndex) := true.B
            tcHistoryReplace(train.meta.tcHistoryIndex) := !historyWay(0)
        }
    }
}
