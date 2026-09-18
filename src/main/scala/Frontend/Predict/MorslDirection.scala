import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class TageRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.tageTagBits.W)
    val rank = UInt(p.slotBits.W)
    val counter = UInt(3.W)
    val useful = Bool()
}

class LoopRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.loopTagBits.W)
    val rank = UInt(p.slotBits.W)
    val tripCount = UInt(p.loopCountBits.W)
    val observedCount = UInt(p.loopCountBits.W)
    val confidence = UInt(2.W)
}

/** MORSL-derived rank-tagged tables with predecessor indexing and delayed SC selection.
  * Learning rules are project-specific; this is not the original CBP submission.
  */
class MorslDirection(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val query = new Bundle {
            val pc = Input(UInt(32.W))
            val folds = Input(Vec(p.tageCount, UInt(p.hashBits.W)))
            val advance = Input(UInt(p.fetchWidth.W))
            val fire = Input(Bool())
            val invalidate = Input(Bool())
        }
        val directions = Output(UInt(p.fetchWidth.W))
        val meta = Output(new FrontendDirectionMeta(p))
        val scRead = Output(new FrontendCorrectorRead(p))
        val train = Flipped(Valid(new FrontendTraining(p)))
        val dbg = if (p.observe) Some(Output(new Bundle {
            val loopTraining = UInt(64.W)
            val loopProvider = UInt(64.W)
            val loopCorrect = UInt(64.W)
        })) else None
    })

    /* Prediction Tables */
    // PC-indexed PHT: one 2-bit counter per conditional rank, used when TAGE has no provider.
    val pht = Mem(p.phtSets, Vec(p.fetchWidth, UInt(2.W)))
    val phtValid = RegInit(VecInit.fill(p.phtSets)(false.B))
    // Tagged tables are ordered by increasing history length; each row stores one rank.
    val tageTables = Seq.fill(p.tageCount)(Mem(p.tageSets, new TageRow(p)))
    val tageValid = Seq.fill(p.tageCount)(RegInit(VecInit.fill(p.tageSets)(false.B)))
    // The proven PC-bias table anchors a compact Multi-GEHL statistical corrector.
    val scBiasTable = Mem(p.scBiasSets, new ScBiasRow(p))
    val scBiasValid = RegInit(VecInit.fill(p.scBiasSets)(false.B))
    val scTables = Seq.fill(p.scCount)(Mem(p.scSets, new ScRow(p)))
    val scValid = Seq.fill(p.scCount)(RegInit(VecInit.fill(p.scSets)(0.U(p.fetchWidth.W))))
    val scThresholds = RegInit(VecInit.fill(p.scThresholdSets)(32.U(6.W)))
    val scGlobalThreshold = RegInit(64.U(7.W))
    val loopTable = Mem(p.loopSets, new LoopRow(p))
    val loopValid = RegInit(VecInit.fill(p.loopSets)(false.B))
    val loopSpecCount = RegInit(VecInit.fill(p.loopSets)(0.U(p.loopCountBits.W)))

    /* Ahead Registers */
    // Predecessor-read rows for the next block. Saved valid bits are not tag-match results.
    val aheadTageRows = Reg(Vec(p.tageCount, new TageRow(p)))
    val aheadTageValid = Reg(Vec(p.tageCount, Bool()))
    val aheadTageIndices = Reg(Vec(p.tageCount, UInt(p.tageIndexBits.W)))
    val aheadScRows = Reg(Vec(p.scCount, new ScRow(p)))
    val aheadScValid = Reg(Vec(p.scCount, UInt(p.fetchWidth.W)))
    val aheadScIndices = Reg(Vec(p.scCount, UInt(p.scIndexBits.W)))
    val aheadLoopRow = Reg(new LoopRow(p))
    val aheadLoopValid = Reg(Bool())
    val aheadLoopIndex = Reg(UInt(p.loopIndexBits.W))
    val aheadValid = RegInit(false.B)

    /* Query Indices and Tags */
    val pcWord = io.query.pc(31, 2)
    val phtIndex = FrontendMath.fold(pcWord, p.phtIndexBits)
    val scBiasIndex = FrontendMath.fold(pcWord, p.scBiasIndexBits)
    val scThresholdIndex = FrontendMath.fold(pcWord, p.scThresholdIndexBits)
    val loopIndex = FrontendMath.fold(pcWord, p.loopIndexBits)
    val loopTag = FrontendMath.fold(pcWord, p.loopTagBits)
    val scIndices = Wire(Vec(p.scCount, UInt(p.scIndexBits.W)))
    for (table <- p.scHistoryIndices.indices) {
        val history = FrontendMath.fold(io.query.folds(p.scHistoryIndices(table)), p.scIndexBits)
        val pcHash = FrontendMath.fold(pcWord ^ (pcWord >> (table + 1)), p.scIndexBits)
        scIndices(table) := pcHash ^ history ^ ((table + 1) * 21).U(p.scIndexBits.W)
    }
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
    val tageDirections = Wire(Vec(p.fetchWidth, Bool()))
    val alternateDirections = Wire(Vec(p.fetchWidth, Bool()))
    val tageProviders = Wire(Vec(p.fetchWidth, UInt(p.providerBits.W)))
    val tageConfidence = Wire(Vec(p.fetchWidth, UInt(2.W)))
    val loopProviders = Wire(Vec(p.fetchWidth, Bool()))
    val loopPredictions = Wire(Vec(p.fetchWidth, Bool()))
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
        val hasProvider = hits.reduce(_ || _)
        val providerCounter = Mux1H(provider.zip(aheadTageRows.map(_.counter)))
        tageDirections(rank) := Mux1H(provider.zip(predictions) :+ (!hits.reduce(_ || _) -> phtRData(rank)(1)))
        alternateDirections(rank) :=
            Mux1H(alternateProvider.zip(predictions) :+ (!alternateHits.reduce(_ || _) -> phtRData(rank)(1)))
        tageProviders(rank) := Mux(
            hasProvider,
            Mux1H(provider.zipWithIndex.map { case (selected, i) => selected -> (i + 1).U(p.providerBits.W) }),
            0.U
        )
        val providerHigh = providerCounter <= 1.U || providerCounter >= 6.U
        val providerMedium = providerCounter === 2.U || providerCounter === 5.U
        val phtHigh = phtRData(rank) === 0.U || phtRData(rank) === 3.U
        tageConfidence(rank) := Mux(
            hasProvider,
            Mux(providerHigh, 2.U, Mux(providerMedium, 1.U, 0.U)),
            Mux(phtHigh, 2.U, 0.U),
        )
        loopProviders(rank) := aheadValid && aheadLoopValid && aheadLoopRow.tag === loopTag &&
            aheadLoopRow.rank === rank.U && aheadLoopRow.tripCount.orR && aheadLoopRow.confidence.andR
        loopPredictions(rank) := loopSpecCount(aheadLoopIndex) =/= aheadLoopRow.tripCount
        directions(rank) := Mux(loopProviders(rank), loopPredictions(rank), tageDirections(rank))
    }

    /* Prediction Metadata */
    // Retain the lookup keys until commit; training must not use a newer speculative history.
    io.directions := directions.asUInt
    io.meta := 0.U.asTypeOf(new FrontendDirectionMeta(p))
    io.meta.phtIndex := phtIndex
    io.meta.tageIndices := aheadTageIndices
    io.meta.tageTags := tageTags
    io.meta.tageProviders := tageProviders
    io.meta.tageConfidence := tageConfidence
    io.meta.tageDirections := tageDirections.asUInt
    io.meta.alternateDirections := alternateDirections.asUInt
    io.meta.aheadValid := aheadValid
    io.meta.scBiasTag := FrontendMath.fold(pcWord, 10)
    io.meta.scIndices := aheadScIndices
    io.meta.scThresholdIndex := scThresholdIndex
    io.meta.loopIndex := aheadLoopIndex
    io.meta.loopValid := loopProviders.asUInt
    io.meta.loopPredictions := loopPredictions.asUInt
    io.scRead.biasRow := scBiasTable.read(scBiasIndex)
    io.scRead.biasValid := scBiasValid(scBiasIndex)
    io.scRead.scRows := aheadScRows
    for (table <- 0 until p.scCount) {
        io.scRead.scValid(table) := Mux(aheadValid, aheadScValid(table), 0.U)
    }
    val localThreshold = Cat(0.U(1.W), scThresholds(scThresholdIndex)).asSInt - 32.S(7.W)
    val globalThreshold = (Cat(0.U(1.W), scGlobalThreshold).asSInt - 64.S(8.W)) >> 3
    val threshold = p.scInitialThreshold.S(9.W) + localThreshold + globalThreshold
    io.scRead.scThreshold := Mux(threshold < 4.S, 4.U, Mux(threshold > 63.S, 63.U, threshold.asUInt))

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
        for (table <- 0 until p.scCount) {
            aheadScRows(table) := scTables(table).read(scIndices(table))
            aheadScValid(table) := scValid(table)(scIndices(table))
            aheadScIndices(table) := scIndices(table)
        }
        aheadLoopRow := loopTable.read(loopIndex)
        aheadLoopValid := loopValid(loopIndex)
        aheadLoopIndex := loopIndex
    }
    when(io.query.invalidate) { aheadValid := false.B }

    val advanceLoop = io.query.fire && loopProviders.asUInt.orR &&
        (io.query.advance & UIntToOH(aheadLoopRow.rank, p.fetchWidth)).orR
    when(io.query.invalidate) {
        loopSpecCount := VecInit.fill(p.loopSets)(0.U)
    }.elsewhen(advanceLoop) {
        loopSpecCount(aheadLoopIndex) := Mux(
            loopPredictions(aheadLoopRow.rank),
            FrontendMath.sat(loopSpecCount(aheadLoopIndex), true.B),
            0.U,
        )
    }

    /* Committed Slot to Conditional Rank */
    val train = io.train.bits
    val trainPcWord = train.pc(31, 2)
    val trainPhtIndex = FrontendMath.fold(trainPcWord, p.phtIndexBits)
    val trainScBiasIndex = FrontendMath.fold(trainPcWord, p.scBiasIndexBits)
    val trainScBiasTag = FrontendMath.fold(trainPcWord, 10)
    val trainLoopIndex = train.meta.loopIndex
    val trainLoopTag = FrontendMath.fold(trainPcWord, p.loopTagBits)
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

    /* Loop Predictor Training */
    val loopTrainRow = loopTable.read(trainLoopIndex)
    val loopTrainValid = loopValid(trainLoopIndex)
    val trainBackward = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        val backward = (0 until p.fetchWidth).map { slot =>
            val rankBefore = if (slot == 0) 0.U
            else PopCount((0 until slot).map(j => train.mask(j) && FrontendCfi.conditional(train.kinds(j))))
            train.mask(slot) && FrontendCfi.conditional(train.kinds(slot)) && rankBefore === rank.U &&
                FrontendMath.backwardBranch(FrontendMath.slotPc(train.pc, slot, p), train.targets(slot))
        }
        trainBackward(rank) := backward.reduce(_ || _)
    }
    val loopCandidate = trainRanks.asUInt & trainBackward.asUInt
    val loopRank = PriorityEncoder(loopCandidate)
    val loopMatch = loopTrainValid && loopTrainRow.tag === trainLoopTag && loopCandidate(loopTrainRow.rank)
    val loopNext = WireDefault(loopTrainRow)
    val loopTaken = trainDirections(Mux(loopMatch, loopTrainRow.rank, loopRank))
    when(loopMatch) {
        when(loopTaken) {
            loopNext.observedCount := FrontendMath.sat(loopTrainRow.observedCount, true.B)
        }.otherwise {
            when(loopTrainRow.observedCount >= 2.U) {
                when(loopTrainRow.tripCount === loopTrainRow.observedCount) {
                    loopNext.confidence := FrontendMath.sat(loopTrainRow.confidence, true.B)
                }.otherwise {
                    loopNext.tripCount := loopTrainRow.observedCount
                    loopNext.confidence := 0.U
                }
            }.otherwise {
                loopNext.tripCount := 0.U
                loopNext.confidence := 0.U
            }
            loopNext.observedCount := 0.U
        }
    }.otherwise {
        loopNext.tag := trainLoopTag
        loopNext.rank := loopRank
        loopNext.tripCount := 0.U
        loopNext.observedCount := Mux(loopTaken, 1.U, 0.U)
        loopNext.confidence := 0.U
    }
    when(io.train.valid && loopCandidate.orR) {
        loopTable.write(trainLoopIndex, loopNext)
        loopValid(trainLoopIndex) := true.B
        when(!loopMatch) { loopSpecCount(trainLoopIndex) := 0.U }
    }

    if (p.observe) {
        val loopTrainingCount = RegInit(0.U(64.W))
        val loopProviderCount = RegInit(0.U(64.W))
        val loopCorrectCount = RegInit(0.U(64.W))
        val trainedProviders = trainRanks.asUInt & train.meta.loopValid
        when(io.train.valid) {
            loopTrainingCount := loopTrainingCount + PopCount(loopCandidate)
            loopProviderCount := loopProviderCount + PopCount(trainedProviders)
            loopCorrectCount := loopCorrectCount + PopCount(
                trainedProviders & ~(train.meta.loopPredictions ^ trainDirections.asUInt)
            )
        }
        io.dbg.get.loopTraining := loopTrainingCount
        io.dbg.get.loopProvider := loopProviderCount
        io.dbg.get.loopCorrect := loopCorrectCount
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

    /* SC Bias Training */
    val scBiasTrain = scBiasTable.read(trainScBiasIndex)
    val scBiasNext = WireDefault(scBiasTrain)
    val scBiasMatched = scBiasValid(trainScBiasIndex) && scBiasTrain.tag === trainScBiasTag
    scBiasNext.tag := trainScBiasTag
    for (rank <- 0 until p.fetchWidth) {
        when(!scBiasMatched) {
            scBiasNext.counters(rank) := Mux(trainRanks(rank) && trainDirections(rank), 4.U, 3.U)
            scBiasNext.confidence(rank) := 0.U
        }.elsewhen(trainRanks(rank)) {
            val correct = scBiasTrain.counters(rank)(2) === trainDirections(rank)
            scBiasNext.counters(rank) := FrontendMath.sat(scBiasTrain.counters(rank), trainDirections(rank))
            when(!correct || train.earlyDirections(rank) =/= trainDirections(rank)) {
                scBiasNext.confidence(rank) := FrontendMath.sat(scBiasTrain.confidence(rank), correct)
            }
        }
    }
    when(io.train.valid && trainRanks.asUInt.orR) {
        scBiasTable.write(trainScBiasIndex, scBiasNext)
        scBiasValid(trainScBiasIndex) := true.B
    }

    /* Multi-GEHL Training */
    val scErrors = trainRanks.asUInt & (train.meta.scPredictions ^ trainDirections.asUInt)
    val scUpdates = trainRanks.asUInt & (scErrors | train.meta.scLowMargin)
    for (table <- 0 until p.scCount) {
        val index = train.meta.scIndices(table)
        val old = scTables(table).read(index)
        val valid = scValid(table)(index)
        val next = WireDefault(old)
        for (rank <- 0 until p.fetchWidth) {
            when(scUpdates(rank)) {
                next.counters(rank) := Mux(
                    valid(rank),
                    FrontendMath.sat(old.counters(rank), trainDirections(rank)),
                    Mux(trainDirections(rank), (BigInt(1) << (p.scCounterBits - 1)).U,
                        ((BigInt(1) << (p.scCounterBits - 1)) - 1).U),
                )
            }
        }
        when(io.train.valid && train.meta.aheadValid && scUpdates.orR) {
            scTables(table).write(index, next)
            scValid(table)(index) := valid | scUpdates
        }
    }

    // O-GEHL-style threshold adaptation: errors train farther from zero; correct low-margin sums train less.
    when(io.train.valid && train.meta.aheadValid && scUpdates.orR) {
        val increase = scErrors.orR
        val thresholdIndex = train.meta.scThresholdIndex
        scThresholds(thresholdIndex) := FrontendMath.sat(scThresholds(thresholdIndex), increase)
        scGlobalThreshold := FrontendMath.sat(scGlobalThreshold, increase)
    }
}
