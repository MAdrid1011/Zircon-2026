import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class TageRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.tageTagBits.W)
    val rankOH = UInt(p.fetchWidth.W)
    val counter = UInt(3.W)
}

class LoopRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.loopTagBits.W)
    val rankOH = UInt(p.fetchWidth.W)
    val tripCount = UInt(p.loopCountBits.W)
    val confidence = UInt(2.W)
}

/** Direction-training fields used by MORSL, with address bits narrowed at its boundary. */
class MorslTraining(p: FrontendParams) extends Bundle {
    val pcWord = UInt(30.W)
    val mask = UInt(p.fetchWidth.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val taken = UInt(p.fetchWidth.W)
    val targetLow = Vec(p.fetchWidth, UInt(13.W))
    val meta = new FrontendTrainingMeta(p)
    val earlyDirections = UInt(p.fetchWidth.W)
}

class MorslDirectionQueryIO(p: FrontendParams) extends Bundle {
    val pcWord = Input(UInt(30.W))
    val prefetchPcWord = Input(UInt(30.W))
    val prefetch = Input(Bool())
    val folds = Input(Vec(p.tageCount, UInt(p.hashBits.W)))
    val advance = Input(UInt(p.fetchWidth.W))
    val fire = Input(Bool())
    val invalidate = Input(Bool())
}

class MorslDirectionIO(p: FrontendParams) extends Bundle {
    val query = new MorslDirectionQueryIO(p)
    val directions = Output(UInt(p.fetchWidth.W))
    val meta = Output(new FrontendDirectionMeta(p))
    val scRead = Output(new FrontendCorrectorRead(p))
    val trainRead = Flipped(Valid(new MorslTraining(p)))
    val train = Flipped(Valid(new MorslTraining(p)))
    val dbg = if (p.observe) Some(Output(new MorslDirectionDebugIO)) else None
}

/** MORSL-derived rank-tagged tables with predecessor indexing and delayed SC selection.
  * Learning rules are project-specific; this is not the original CBP submission.
  */
class MorslDirection(
    p: FrontendParams,
    ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
) extends Module {
    val io = IO(new MorslDirectionIO(p))

    /* Prediction Tables */
    // PC-indexed PHT: one 2-bit counter per conditional rank, used when TAGE has no provider.
    val pht = Module(new PredictorTableRam(p.phtSets, p.fetchWidth * 2, ramBackend))
    val phtValid = RegInit(VecInit.fill(p.phtSets)(false.B))
    // Tagged tables are ordered by increasing history length; each row stores one rank.
    val tageTables = Seq.fill(p.tageCount)(
        Module(new PredictorTableRam(p.tageSets, (new TageRow(p)).getWidth, ramBackend))
    )
    val tageValid = Seq.fill(p.tageCount)(RegInit(VecInit.fill(p.tageSets)(false.B)))
    val tageUseful = Seq.fill(p.tageCount)(RegInit(VecInit.fill(p.tageSets)(false.B)))
    // The proven PC-bias table anchors a compact Multi-GEHL statistical corrector.
    val scBiasTable = Reg(Vec(p.scBiasSets, new ScBiasRow(p)))
    val scBiasValid = RegInit(VecInit.fill(p.scBiasSets)(false.B))
    val scTables = Seq.fill(p.scCount)(
        Module(new PredictorTableRam(p.scSets, (new ScRow(p)).getWidth, ramBackend))
    )
    val scValid = Seq.fill(p.scCount)(RegInit(VecInit.fill(p.scSets)(0.U(p.fetchWidth.W))))
    val scThresholds = RegInit(VecInit.fill(p.scThresholdSets)(32.U(6.W)))
    val scGlobalThreshold = RegInit(64.U(7.W))
    val loopTable = Module(new PredictorTableRam(p.loopSets, (new LoopRow(p)).getWidth, ramBackend))
    val loopValid = RegInit(VecInit.fill(p.loopSets)(false.B))
    val loopObservedCount = RegInit(VecInit.fill(p.loopSets)(0.U(p.loopCountBits.W)))
    val loopSpecCount = RegInit(VecInit.fill(p.loopSets)(0.U(p.loopCountBits.W)))

    /* Ahead Registers */
    // Predecessor-read rows for the next block. Saved valid bits are not tag-match results.
    val aheadTageRows = Wire(Vec(p.tageCount, new TageRow(p)))
    val aheadTageValid = Reg(Vec(p.tageCount, Bool()))
    val aheadTageIndices = Reg(Vec(p.tageCount, UInt(p.tageIndexBits.W)))
    val aheadScRows = Wire(Vec(p.scCount, new ScRow(p)))
    val aheadScValid = Reg(Vec(p.scCount, UInt(p.fetchWidth.W)))
    val aheadScIndices = Reg(Vec(p.scCount, UInt(p.scIndexBits.W)))
    val aheadLoopRow = Wire(new LoopRow(p))
    val aheadLoopValid = Reg(Bool())
    val aheadLoopIndex = Reg(UInt(p.loopIndexBits.W))
    val aheadLoopIndexOH = Reg(UInt(p.loopSets.W))
    val aheadValid = RegInit(false.B)

    /* Query Indices and Tags */
    val pcWord = io.query.pcWord
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
    val tageIndices = VecInit((0 until p.tageCount).map { table =>
        FrontendMath.fold(pcWord ^ io.query.folds(table) ^ (table + 1).U, p.tageIndexBits)
    })
    val phtPrefetchIndex = FrontendMath.fold(io.query.prefetchPcWord, p.phtIndexBits)
    val phtPredictionIndex = RegEnable(phtPrefetchIndex, io.query.prefetch)
    pht.io.predictEnable := io.query.prefetch
    pht.io.predictAddress := phtPrefetchIndex
    val phtRaw = pht.io.predictData.asTypeOf(Vec(p.fetchWidth, UInt(2.W)))
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
    val aheadLoopCount = Mux1H(aheadLoopIndexOH.asBools, loopSpecCount)
    val loopPrediction = aheadLoopCount =/= aheadLoopRow.tripCount
    val tageTagMatches = (0 until p.tageCount).map(table =>
        aheadValid && aheadTageValid(table) && aheadTageRows(table).tag === tageTags(table)
    )
    val loopTagMatch = aheadValid && aheadLoopValid && aheadLoopRow.tag === loopTag &&
        aheadLoopRow.tripCount.orR && aheadLoopRow.confidence.andR
    // Tables are ordered from short to long history. A parallel suffix network isolates the longest hit.
    def longest(candidates: Seq[Bool]): UInt = {
        require(candidates.nonEmpty)
        val hits = VecInit(candidates).asUInt
        var suffix = hits
        var distance = 1
        while (distance < candidates.size) {
            suffix = suffix | (suffix >> distance).pad(candidates.size)
            distance *= 2
        }
        hits & ~(suffix >> 1).pad(candidates.size)
    }
    // Select the longest-history value with a balanced tree. Direction bits
    // do not need to traverse provider one-hot generation and a second mux.
    def longestBool(hits: Seq[Bool], values: Seq[Bool]): (Bool, Bool) = {
        require(hits.nonEmpty && hits.size == values.size)
        if (hits.size == 1) {
            (hits.head, values.head)
        } else {
            val split = hits.size / 2
            val (lowValid, lowValue) = longestBool(hits.take(split), values.take(split))
            val (highValid, highValue) = longestBool(hits.drop(split), values.drop(split))
            (lowValid || highValid, Mux(highValid, highValue, lowValue))
        }
    }
    for (rank <- 0 until p.fetchWidth) {
        val hits = (0 until p.tageCount).map(table =>
            tageTagMatches(table) && aheadTageRows(table).rankOH(rank)
        )
        val hitMask = VecInit(hits).asUInt
        val provider = longest(hits)
        val alternateHits = hitMask & ~provider
        val alternateProvider = longest(alternateHits.asBools)
        val predictions = aheadTageRows.map(_.counter(2))
        val (hasProvider, providerPrediction) = longestBool(hits, predictions)
        val hasAlternate = alternateHits.orR
        val providerCounter = Mux1H(provider, aheadTageRows.map(_.counter))
        tageDirections(rank) := Mux(hasProvider, providerPrediction, phtRData(rank)(1))
        alternateDirections(rank) :=
            Mux(hasAlternate, Mux1H(alternateProvider, predictions), phtRData(rank)(1))
        tageProviders(rank) := Mux(
            hasProvider,
            Mux1H(provider, (1 to p.tageCount).map(_.U(p.providerBits.W))),
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
        loopProviders(rank) := loopTagMatch && aheadLoopRow.rankOH(rank)
        loopPredictions(rank) := loopPrediction
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
    val scBiasPrediction = scBiasTable(scBiasIndex)
    io.scRead.biasRow.tag := scBiasPrediction.tag
    io.scRead.biasRow.counters := scBiasPrediction.counters
    for (rank <- 0 until p.fetchWidth) {
        io.scRead.biasStrong(rank) := Mux1H((0 until p.scBiasSets).map { set =>
            (scBiasIndex === set.U) -> scBiasTable(set).confidence(rank)(2)
        })
    }
    io.scRead.biasValid := scBiasValid(scBiasIndex)
    io.scRead.scRows := aheadScRows
    for (table <- 0 until p.scCount) {
        io.scRead.scValid(table) := Mux(aheadValid, aheadScValid(table), 0.U)
    }
    val localThreshold = Cat(0.U(1.W), scThresholds(scThresholdIndex)).asSInt - 32.S(7.W)
    val globalThreshold = scGlobalThreshold(6, 3).zext - 8.S(6.W)
    val threshold = p.scInitialThreshold.S(9.W) + localThreshold + globalThreshold
    io.scRead.scThreshold := Mux(threshold < 4.S, 4.U, Mux(threshold > 63.S, 63.U, threshold.asUInt))

    /* Ahead Read */
    // Stalls hold all ahead rows and indices; recovery wins over a same-cycle read.
    for (table <- 0 until p.tageCount) {
        tageTables(table).io.predictEnable := io.query.fire
        tageTables(table).io.predictAddress := tageIndices(table)
        aheadTageRows(table) := tageTables(table).io.predictData.asTypeOf(new TageRow(p))
    }
    for (table <- 0 until p.scCount) {
        scTables(table).io.predictEnable := io.query.fire
        scTables(table).io.predictAddress := scIndices(table)
        aheadScRows(table) := scTables(table).io.predictData.asTypeOf(new ScRow(p))
    }
    loopTable.io.predictEnable := io.query.fire
    loopTable.io.predictAddress := loopIndex
    aheadLoopRow := loopTable.io.predictData.asTypeOf(new LoopRow(p))
    when(io.query.fire) {
        aheadValid := true.B
        for (table <- 0 until p.tageCount) {
            aheadTageValid(table) := tageValid(table)(tageIndices(table))
            aheadTageIndices(table) := tageIndices(table)
        }
        for (table <- 0 until p.scCount) {
            aheadScValid(table) := scValid(table)(scIndices(table))
            aheadScIndices(table) := scIndices(table)
        }
        aheadLoopValid := loopValid(loopIndex)
        aheadLoopIndex := loopIndex
        aheadLoopIndexOH := UIntToOH(loopIndex, p.loopSets)
    }
    when(io.query.invalidate) { aheadValid := false.B }

    val advanceLoop = io.query.fire && (loopProviders.asUInt & io.query.advance).orR
    val advancedLoopCount = Mux(loopPrediction, FrontendMath.sat(aheadLoopCount, true.B), 0.U)

    /* Committed Slot to Conditional Rank */
    val train = io.train.bits
    val trainPcWord = train.pcWord
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
    loopTable.io.updateReadEnable := io.trainRead.valid
    loopTable.io.updateReadAddress := io.trainRead.bits.meta.loopIndex
    val loopTrainRow = loopTable.io.updateReadData.asTypeOf(new LoopRow(p))
    val loopTrainValid = loopValid(trainLoopIndex)
    val loopTrainObserved = loopObservedCount(trainLoopIndex)
    val trainBackward = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        val backward = (0 until p.fetchWidth).map { slot =>
            val rankBefore = if (slot == 0) 0.U
            else PopCount((0 until slot).map(j => train.mask(j) && FrontendCfi.conditional(train.kinds(j))))
            train.mask(slot) && FrontendCfi.conditional(train.kinds(slot)) && rankBefore === rank.U &&
                FrontendMath.backwardBranch(
                    FrontendMath.slotPc(Cat(train.pcWord, 0.U(2.W)), slot, p),
                    train.targetLow(slot),
                )
        }
        trainBackward(rank) := backward.reduce(_ || _)
    }
    val loopCandidate = trainRanks.asUInt & trainBackward.asUInt
    val loopRankOH = PriorityEncoderOH(loopCandidate)
    val loopMatch = loopTrainValid && loopTrainRow.tag === trainLoopTag &&
        (loopCandidate & loopTrainRow.rankOH).orR
    val loopNext = WireDefault(loopTrainRow)
    val loopObservedNext = WireDefault(loopTrainObserved)
    val loopSelectedRankOH = Mux(loopMatch, loopTrainRow.rankOH, loopRankOH)
    val loopTaken = (trainDirections.asUInt & loopSelectedRankOH).orR
    when(loopMatch) {
        when(loopTaken) {
            loopObservedNext := FrontendMath.sat(loopTrainObserved, true.B)
        }.otherwise {
            when(loopTrainObserved >= 2.U) {
                when(loopTrainRow.tripCount === loopTrainObserved) {
                    loopNext.confidence := FrontendMath.sat(loopTrainRow.confidence, true.B)
                }.otherwise {
                    loopNext.tripCount := loopTrainObserved
                    loopNext.confidence := 0.U
                }
            }.otherwise {
                loopNext.tripCount := 0.U
                loopNext.confidence := 0.U
            }
            loopObservedNext := 0.U
        }
    }.otherwise {
        loopNext.tag := trainLoopTag
        loopNext.rankOH := loopRankOH
        loopNext.tripCount := 0.U
        loopObservedNext := Mux(loopTaken, 1.U, 0.U)
        loopNext.confidence := 0.U
    }
    val loopWrite = io.train.valid && loopCandidate.orR
    loopTable.io.updateWriteEnable := loopWrite
    loopTable.io.updateWriteAddress := trainLoopIndex
    loopTable.io.updateWriteData := loopNext.asUInt
    when(loopWrite) {
        loopValid(trainLoopIndex) := true.B
        loopObservedCount(trainLoopIndex) := loopObservedNext
    }
    val resetTrainedLoop = loopWrite && !loopMatch
    val trainLoopIndexOH = UIntToOH(trainLoopIndex, p.loopSets)
    for (entry <- 0 until p.loopSets) {
        when(resetTrainedLoop && trainLoopIndexOH(entry)) {
            loopSpecCount(entry) := 0.U
        }.elsewhen(io.query.invalidate) {
            loopSpecCount(entry) := 0.U
        }.elsewhen(advanceLoop && aheadLoopIndexOH(entry)) {
            loopSpecCount(entry) := advancedLoopCount
        }
    }
    when(aheadValid) { assert(PopCount(aheadLoopIndexOH) === 1.U) }

    /* PHT Training */
    pht.io.updateReadEnable := io.trainRead.valid
    pht.io.updateReadAddress := FrontendMath.fold(io.trainRead.bits.pcWord, p.phtIndexBits)
    val phtTrainRaw = pht.io.updateReadData.asTypeOf(Vec(p.fetchWidth, UInt(2.W)))
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
    val trainCommit = io.train.valid
    pht.io.updateWriteEnable := trainCommit && trainRanks.asUInt.orR
    pht.io.updateWriteAddress := trainPhtIndex
    pht.io.updateWriteData := phtNext.asUInt
    when(trainCommit && trainRanks.asUInt.orR) {
        phtValid(trainPhtIndex) := true.B
    }
    when(io.query.fire) {
        assert(phtPredictionIndex === phtIndex, "PHT response must match the IF1 request")
    }

    /* TAGE Training and Allocation */
    // Allocate beyond the first mispredicted rank's provider; age useful bits if all candidates are busy.
    val tageTrainRows = (0 until p.tageCount).map { table =>
        tageTables(table).io.updateReadEnable := io.trainRead.valid
        tageTables(table).io.updateReadAddress := io.trainRead.bits.meta.tageIndices(table)
        tageTables(table).io.updateReadData.asTypeOf(new TageRow(p))
    }
    val tageTrainValid = (0 until p.tageCount).map(table =>
        tageValid(table)(train.meta.tageIndices(table))
    )
    val tageErrors = trainRanks.asUInt & (train.earlyDirections ^ trainDirections.asUInt)
    val tageErrorRank = PriorityEncoder(tageErrors)
    val tageErrorProvider = train.meta.tageProviders(tageErrorRank)
    val tageAllocatable = VecInit((0 until p.tageCount).map(table =>
        (table + 1).U > tageErrorProvider && (
            !tageTrainValid(table) || !tageUseful(table)(train.meta.tageIndices(table))
        )
    ))
    val tageAllocate = PriorityEncoderOH(tageAllocatable)
    for (table <- 0 until p.tageCount) {
        val old = tageTrainRows(table)
        val next = WireDefault(old)
        val nextUseful = WireDefault(tageUseful(table)(train.meta.tageIndices(table)))
        val oldRankOH = old.rankOH
        val earlyBit = (train.earlyDirections & oldRankOH).orR
        val alternateBit = (train.meta.alternateDirections & oldRankOH).orR
        val actualBit = (trainDirections.asUInt & oldRankOH).orR
        val oldProvider = Mux1H(oldRankOH.asBools, train.meta.tageProviders)
        val matching = tageTrainValid(table) && old.tag === train.meta.tageTags(table) &&
            (trainRanks.asUInt & oldRankOH).orR
        val ageUseful = tageErrors.orR && !tageAllocatable.asUInt.orR && (table + 1).U > tageErrorProvider
        val allocate = tageErrors.orR && tageAllocate(table)
        when(matching) {
            next.counter := FrontendMath.sat(old.counter, actualBit)
            when(oldProvider === (table + 1).U && earlyBit =/= alternateBit) {
                nextUseful := earlyBit === actualBit
            }
        }
        when(ageUseful) {
            nextUseful := false.B
        }
        when(allocate) {
            next.tag := train.meta.tageTags(table)
            next.rankOH := UIntToOH(tageErrorRank, p.fetchWidth)
            next.counter := Mux(trainDirections(tageErrorRank), 4.U, 3.U)
            nextUseful := false.B
        }
        val update = train.meta.aheadValid && (matching || ageUseful || allocate)
        val write = trainCommit && update
        tageTables(table).io.updateWriteEnable := write
        tageTables(table).io.updateWriteAddress := train.meta.tageIndices(table)
        tageTables(table).io.updateWriteData := next.asUInt
        when(write) {
            tageUseful(table)(train.meta.tageIndices(table)) := nextUseful
            when(allocate) {
                tageValid(table)(train.meta.tageIndices(table)) := true.B
            }
        }
    }

    /* SC Bias Training */
    val scBiasTrain = scBiasTable(trainScBiasIndex)
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
    when(trainCommit && trainRanks.asUInt.orR) {
        scBiasTable(trainScBiasIndex) := scBiasNext
        scBiasValid(trainScBiasIndex) := true.B
    }

    /* Multi-GEHL Training */
    val scErrors = trainRanks.asUInt & (train.meta.scPredictions ^ trainDirections.asUInt)
    val scUpdates = trainRanks.asUInt & (scErrors | train.meta.scLowMargin)
    for (table <- 0 until p.scCount) {
        val index = train.meta.scIndices(table)
        scTables(table).io.updateReadEnable := io.trainRead.valid
        scTables(table).io.updateReadAddress := io.trainRead.bits.meta.scIndices(table)
        val old = scTables(table).io.updateReadData.asTypeOf(new ScRow(p))
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
        val update = train.meta.aheadValid && scUpdates.orR
        val write = trainCommit && update
        scTables(table).io.updateWriteEnable := write
        scTables(table).io.updateWriteAddress := index
        scTables(table).io.updateWriteData := next.asUInt
        when(write) {
            scValid(table)(index) := valid | scUpdates
        }
    }

    // O-GEHL-style threshold adaptation: errors train farther from zero; correct low-margin sums train less.
    when(trainCommit && train.meta.aheadValid && scUpdates.orR) {
        val increase = scErrors.orR
        val thresholdIndex = train.meta.scThresholdIndex
        scThresholds(thresholdIndex) := FrontendMath.sat(scThresholds(thresholdIndex), increase)
        scGlobalThreshold := FrontendMath.sat(scGlobalThreshold, increase)
    }

    if (p.observe) {
        val loopTrainingCount = RegInit(0.U(64.W))
        val loopProviderCount = RegInit(0.U(64.W))
        val loopCorrectCount = RegInit(0.U(64.W))
        val trainedProviders = trainRanks.asUInt & train.meta.loopValid
        when(trainCommit) {
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
}

class MorslDirectionDebugIO extends Bundle {
    val loopTraining = UInt(64.W)
    val loopProvider = UInt(64.W)
    val loopCorrect = UInt(64.W)
}
