import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class TageRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.tageTagBits.W)
    val rankOH = UInt(p.fetchWidth.W)
    val counter = UInt(3.W)
    val useful = Bool()
}

class LoopRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.loopTagBits.W)
    val rankOH = UInt(p.fetchWidth.W)
    val tripCount = UInt(p.loopCountBits.W)
    val confidence = UInt(2.W)
    val observedCount = UInt(p.loopCountBits.W)
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

/** PC-only predictor hashes computed at PF and consumed with IF1's current history. */
class MorslPcHashes(p: FrontendParams) extends Bundle {
    val phtIndex = UInt(p.phtIndexBits.W)
    val scBiasIndex = UInt(p.scBiasIndexBits.W)
    val scThresholdIndex = UInt(p.scThresholdIndexBits.W)
    val loopIndex = UInt(p.loopIndexBits.W)
    val loopTag = UInt(p.loopTagBits.W)
    val scIndices = Vec(p.scCount, UInt(p.scIndexBits.W))
    val tageTags = Vec(p.tageCount, UInt(p.tageTagBits.W))
    val tageIndices = Vec(p.tageCount, UInt(p.tageIndexBits.W))
    val scBiasTag = UInt(10.W)
}

object MorslPcHashes {
    def fromPcWord(pcWord: UInt, p: FrontendParams): MorslPcHashes = {
        val hashes = Wire(new MorslPcHashes(p))
        hashes.phtIndex := FrontendMath.fold(pcWord, p.phtIndexBits)
        hashes.scBiasIndex := FrontendMath.fold(pcWord, p.scBiasIndexBits)
        hashes.scThresholdIndex := FrontendMath.fold(pcWord, p.scThresholdIndexBits)
        hashes.loopIndex := FrontendMath.fold(pcWord, p.loopIndexBits)
        hashes.loopTag := FrontendMath.fold(pcWord, p.loopTagBits)
        for (table <- 0 until p.scCount) {
            hashes.scIndices(table) := FrontendMath.fold(
                pcWord ^ (pcWord >> (table + 1)),
                p.scIndexBits,
            )
        }
        for (table <- 0 until p.tageCount) {
            hashes.tageTags(table) := FrontendMath.fold(pcWord, p.tageTagBits)
            hashes.tageIndices(table) := FrontendMath.fold(pcWord, p.tageIndexBits)
        }
        hashes.scBiasTag := FrontendMath.fold(pcWord, 10)
        hashes
    }
}

class MorslDirectionQueryIO(p: FrontendParams) extends Bundle {
    val pcHashes = Input(new MorslPcHashes(p))
    val slotRankOH = Input(Vec(p.fetchWidth, UInt(p.fetchWidth.W)))
    val prefetchPcWord = Input(UInt(30.W))
    val prefetch = Input(Bool())
    val folds = Input(Vec(p.tageCount, UInt(p.hashBits.W)))
    val advance = Input(UInt(p.fetchWidth.W))
    val fire = Input(Bool())
    val invalidate = Input(Bool())
}

class MorslDirectionIO(p: FrontendParams) extends Bundle {
    val query = new MorslDirectionQueryIO(p)
    // PHT is the IF1 fallback; TAGE and loop selection are retained for IF2.
    val phtDirections = Output(UInt(p.fetchWidth.W))
    val phtSlotDirections = Output(UInt(p.fetchWidth.W))
    val fastDirections = Output(UInt(p.fetchWidth.W))
    val fastSlotDirections = Output(UInt(p.fetchWidth.W))
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
    val tageStateWrite = Wire(Vec(p.tageCount, Bool()))
    val tageStateAllocate = Wire(Vec(p.tageCount, Bool()))
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
    val loopSpecCount = RegInit(VecInit.fill(p.loopSets)(0.U(p.loopCountBits.W)))
    val pendingLoopAdvance = RegInit(false.B)
    val pendingLoopIndexOH = Reg(UInt(p.loopSets.W))
    val pendingLoopCount = Reg(UInt(p.loopCountBits.W))

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
    val pcHashes = io.query.pcHashes
    val phtIndex = pcHashes.phtIndex
    val scBiasIndex = pcHashes.scBiasIndex
    val scThresholdIndex = pcHashes.scThresholdIndex
    val loopIndex = pcHashes.loopIndex
    val loopTag = pcHashes.loopTag
    val scIndices = Wire(Vec(p.scCount, UInt(p.scIndexBits.W)))
    for (table <- p.scHistoryIndices.indices) {
        val history = FrontendMath.fold(io.query.folds(p.scHistoryIndices(table)), p.scIndexBits)
        scIndices(table) := pcHashes.scIndices(table) ^ history ^ ((table + 1) * 21).U(p.scIndexBits.W)
    }
    val tageTags = VecInit((0 until p.tageCount).map(i =>
        (pcHashes.tageTags(i) ^ FrontendMath.fold(io.query.folds(i), p.tageTagBits))(p.tageTagBits - 1, 0)
    ))
    val tageIndices = VecInit((0 until p.tageCount).map { table =>
        pcHashes.tageIndices(table) ^ FrontendMath.fold(io.query.folds(table), p.tageIndexBits) ^
            (table + 1).U(p.tageIndexBits.W)
    })
    val phtPrefetchIndex = FrontendMath.fold(io.query.prefetchPcWord, p.phtIndexBits)
    val phtPredictionIndex = RegEnable(phtPrefetchIndex, io.query.prefetch)
    // Capture valid banks at the PF/IF1 edge with the SRAM read. The next-PC
    // path then ends at the index register, not at a bank-wide register D mux.
    val phtValidLowBits = math.min(6, p.phtIndexBits)
    val phtValidBankWidth = 1 << phtValidLowBits
    val phtValidBanks = VecInit((0 until p.phtSets / phtValidBankWidth).map { bank =>
        VecInit(phtValid.slice(bank * phtValidBankWidth,
            (bank + 1) * phtValidBankWidth)).asUInt
    })
    val phtPredictionBanks = RegEnable(phtValidBanks, io.query.prefetch)
    val phtPredictionBank = if (phtValidBanks.length == 1) phtPredictionBanks(0) else
        phtPredictionBanks(phtPredictionIndex(p.phtIndexBits - 1, phtValidLowBits))
    val phtPredictionValid = phtPredictionBank(phtPredictionIndex(phtValidLowBits - 1, 0))
    pht.io.predictEnable := io.query.prefetch
    pht.io.predictAddress := phtPrefetchIndex
    val phtRaw = pht.io.predictData.asTypeOf(Vec(p.fetchWidth, UInt(2.W)))
    val phtRData = Wire(Vec(p.fetchWidth, UInt(2.W)))
    for (rank <- 0 until p.fetchWidth) {
        phtRData(rank) := Mux(phtPredictionValid, phtRaw(rank), 1.U)
    }
    val phtDirections = VecInit(phtRData.map(_(1))).asUInt
    val phtSlotDirectionsRaw = VecInit(io.query.slotRankOH.map(rankOH =>
        Mux1H(rankOH.asBools, phtRaw.map(_(1)))
    )).asUInt
    val phtSlotDirections = Mux(phtPredictionValid, phtSlotDirectionsRaw, 0.U)

    /* Provider Selection */
    val directions = Wire(Vec(p.fetchWidth, Bool()))
    val tageDirections = Wire(Vec(p.fetchWidth, Bool()))
    val alternateDirections = Wire(Vec(p.fetchWidth, Bool()))
    val tageProviders = Wire(Vec(p.fetchWidth, UInt(p.providerBits.W)))
    val tageConfidence = Wire(Vec(p.fetchWidth, UInt(2.W)))
    val loopProviders = Wire(Vec(p.fetchWidth, Bool()))
    val loopPredictions = Wire(Vec(p.fetchWidth, Bool()))
    val storedAheadLoopCount = Mux1H(aheadLoopIndexOH.asBools, loopSpecCount)
    val pendingAheadLoop = pendingLoopAdvance && (pendingLoopIndexOH & aheadLoopIndexOH).orR
    val aheadLoopCount = Mux(pendingAheadLoop, pendingLoopCount, storedAheadLoopCount)
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
    // IF1 only uses the four shortest tagged tables. This recovers local and
    // medium-history correlation over PHT without putting the full six-table TAGE
    // selection cone on the NPC/ICache request path.
    val fastTageCount = math.min(4, p.tageCount)
    val fastDirections = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        val fastHits = (0 until fastTageCount).map(table =>
            tageTagMatches(table) && aheadTageRows(table).rankOH(rank)
        )
        val fastProvider = longest(fastHits)
        val fastSelect = fastProvider.asBools :+ !VecInit(fastHits).asUInt.orR
        fastDirections(rank) := Mux1H(
            fastSelect,
            aheadTageRows.take(fastTageCount).map(_.counter(2)) :+ phtDirections(rank),
        )
    }
    val fastSlotDirections = Wire(Vec(p.fetchWidth, Bool()))
    for (slot <- 0 until p.fetchWidth) {
        val rankOH = io.query.slotRankOH(slot)
        val fastHits = (0 until fastTageCount).map(table =>
            tageTagMatches(table) && (aheadTageRows(table).rankOH & rankOH).orR
        )
        val fastProvider = longest(fastHits)
        fastSlotDirections(slot) := Mux1H(
            fastProvider.asBools :+ !VecInit(fastHits).asUInt.orR,
            aheadTageRows.take(fastTageCount).map(_.counter(2)) :+
                Mux1H(rankOH.asBools, phtDirections.asBools),
        )
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
    io.phtDirections := phtDirections
    io.phtSlotDirections := phtSlotDirections
    io.fastDirections := fastDirections.asUInt
    io.fastSlotDirections := fastSlotDirections.asUInt
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
    io.meta.scBiasTag := pcHashes.scBiasTag
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
    // Training SRAM reads start one cycle before their update. Prepare every
    // PC/kind-derived field beside that read instead of rebuilding it from the
    // delayed raw packet on the table-write cycle.
    val trainRead = io.trainRead.bits
    val readConditionals = VecInit((0 until p.fetchWidth).map(slot =>
        trainRead.mask(slot) && FrontendCfi.conditional(trainRead.kinds(slot))
    ))
    val readRanks = Wire(Vec(p.fetchWidth, Bool()))
    val readDirections = Wire(Vec(p.fetchWidth, Bool()))
    val readBackward = Wire(Vec(p.fetchWidth, Bool()))
    for (rank <- 0 until p.fetchWidth) {
        val hits = (0 until p.fetchWidth).map { slot =>
            val rankBefore = if (slot == 0) 0.U else PopCount(readConditionals.take(slot))
            readConditionals(slot) && rankBefore === rank.U
        }
        readRanks(rank) := hits.reduce(_ || _)
        readDirections(rank) := Mux1H(hits, trainRead.taken.asBools)
        readBackward(rank) := (0 until p.fetchWidth).map { slot =>
            hits(slot) && FrontendMath.backwardBranch(
                FrontendMath.slotPc(Cat(trainRead.pcWord, 0.U(2.W)), slot, p),
                trainRead.targetLow(slot),
            )
        }.reduce(_ || _)
    }
    val trainRanks = RegEnable(readRanks, io.trainRead.valid)
    val trainDirections = RegEnable(readDirections, io.trainRead.valid)
    val trainBackward = RegEnable(readBackward, io.trainRead.valid)
    val trainPhtIndex = RegEnable(
        FrontendMath.fold(trainRead.pcWord, p.phtIndexBits),
        io.trainRead.valid,
    )
    val trainScBiasIndexOH = RegEnable(
        UIntToOH(FrontendMath.fold(trainRead.pcWord, p.scBiasIndexBits), p.scBiasSets),
        io.trainRead.valid,
    )
    val readTageIndices = VecInit((0 until p.tageCount).map(table =>
        io.trainRead.bits.meta.tageIndices(table)
    ))
    val readTageIndexOH = VecInit((0 until p.tageCount).map(table =>
        UIntToOH(readTageIndices(table), p.tageSets)
    ))
    val trainTageIndices = RegEnable(
        VecInit((0 until p.tageCount).map(table => io.trainRead.bits.meta.tageIndices(table))),
        io.trainRead.valid,
    )
    val trainTageIndexOH = RegEnable(readTageIndexOH, io.trainRead.valid)
    val readTageValidState = Wire(Vec(p.tageCount, Bool()))
    for (table <- 0 until p.tageCount) {
        val currentValid = Mux1H(readTageIndexOH(table), tageValid(table))
        val sameAsWrite = (readTageIndexOH(table) & trainTageIndexOH(table)).orR
        readTageValidState(table) := Mux(
            sameAsWrite && tageStateWrite(table) && tageStateAllocate(table),
            true.B,
            currentValid,
        )
    }
    val trainTageValidState = RegEnable(readTageValidState, io.trainRead.valid)
    val readTageErrors = readRanks.asUInt & (trainRead.earlyDirections ^ readDirections.asUInt)
    val readTageErrorRank = PriorityEncoder(readTageErrors)
    val trainTageErrors = RegEnable(readTageErrors, io.trainRead.valid)
    val trainTageErrorRank = RegEnable(readTageErrorRank, io.trainRead.valid)
    val trainTageErrorProvider = RegEnable(
        trainRead.meta.tageProviders(readTageErrorRank),
        io.trainRead.valid,
    )
    val trainTageUsefulUpdate = RegEnable(
        VecInit((0 until p.tageCount).map { table =>
            VecInit((0 until p.fetchWidth).map { rank =>
                trainRead.meta.tageProviders(rank) === (table + 1).U &&
                    trainRead.earlyDirections(rank) =/= trainRead.meta.alternateDirections(rank)
            }).asUInt
        }),
        io.trainRead.valid,
    )
    val trainTageUsefulValue = RegEnable(
        ~(trainRead.earlyDirections ^ readDirections.asUInt),
        io.trainRead.valid,
    )
    val trainScBiasTag = RegEnable(FrontendMath.fold(trainRead.pcWord, 10), io.trainRead.valid)
    val trainLoopIndex = train.meta.loopIndex
    val trainLoopTag = RegEnable(FrontendMath.fold(trainRead.pcWord, p.loopTagBits), io.trainRead.valid)

    /* Loop Predictor Training */
    loopTable.io.updateReadEnable := io.trainRead.valid
    loopTable.io.updateReadAddress := io.trainRead.bits.meta.loopIndex
    val loopTrainRow = loopTable.io.updateReadData.asTypeOf(new LoopRow(p))
    val loopTrainValid = loopValid(trainLoopIndex)
    val loopTrainObserved = loopTrainRow.observedCount
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
    loopNext.observedCount := loopObservedNext
    loopTable.io.updateWriteEnable := loopWrite
    loopTable.io.updateWriteAddress := trainLoopIndex
    loopTable.io.updateWriteData := loopNext.asUInt
    when(loopWrite) {
        loopValid(trainLoopIndex) := true.B
    }
    val resetTrainedLoop = loopWrite && !loopMatch
    val trainLoopIndexOH = UIntToOH(trainLoopIndex, p.loopSets)
    pendingLoopAdvance := advanceLoop && !io.query.invalidate &&
        !(resetTrainedLoop && (trainLoopIndexOH & aheadLoopIndexOH).orR)
    pendingLoopIndexOH := aheadLoopIndexOH
    pendingLoopCount := advancedLoopCount
    for (entry <- 0 until p.loopSets) {
        when(resetTrainedLoop && trainLoopIndexOH(entry)) {
            loopSpecCount(entry) := 0.U
        }.elsewhen(io.query.invalidate) {
            loopSpecCount(entry) := 0.U
        }.elsewhen(pendingLoopAdvance && pendingLoopIndexOH(entry)) {
            loopSpecCount(entry) := pendingLoopCount
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
    val tageTrainValid = trainTageValidState
    val tageAllocatable = VecInit((0 until p.tageCount).map(table =>
        (table + 1).U > trainTageErrorProvider && (
            !tageTrainValid(table) || !tageTrainRows(table).useful
        )
    ))
    val tageAllocate = PriorityEncoderOH(tageAllocatable)
    for (table <- 0 until p.tageCount) {
        val old = tageTrainRows(table)
        val next = WireDefault(old)
        val nextUseful = WireDefault(old.useful)
        val oldRankOH = old.rankOH
        val actualBit = (trainDirections.asUInt & oldRankOH).orR
        val updateUseful = (trainTageUsefulUpdate(table) & oldRankOH).orR
        val usefulValue = (trainTageUsefulValue & oldRankOH).orR
        val matching = tageTrainValid(table) && old.tag === train.meta.tageTags(table) &&
            (trainRanks.asUInt & oldRankOH).orR
        val ageUseful = trainTageErrors.orR && !tageAllocatable.asUInt.orR &&
            (table + 1).U > trainTageErrorProvider
        val allocate = trainTageErrors.orR && tageAllocate(table)
        when(matching) {
            next.counter := FrontendMath.sat(old.counter, actualBit)
            when(updateUseful) {
                nextUseful := usefulValue
            }
        }
        when(ageUseful) {
            nextUseful := false.B
        }
        when(allocate) {
            next.tag := train.meta.tageTags(table)
            next.rankOH := UIntToOH(trainTageErrorRank, p.fetchWidth)
            next.counter := Mux(trainDirections(trainTageErrorRank), 4.U, 3.U)
            nextUseful := false.B
        }
        val update = train.meta.aheadValid && (matching || ageUseful || allocate)
        val write = trainCommit && update
        tageStateWrite(table) := write
        tageStateAllocate(table) := allocate
        next.useful := nextUseful
        tageTables(table).io.updateWriteEnable := write
        tageTables(table).io.updateWriteAddress := trainTageIndices(table)
        tageTables(table).io.updateWriteData := next.asUInt
        for (entry <- 0 until p.tageSets) {
            when(write && trainTageIndexOH(table)(entry)) {
                when(allocate) {
                    tageValid(table)(entry) := true.B
                }
            }
        }
    }

    /* SC Bias Training */
    for (entry <- 0 until p.scBiasSets) {
        val old = scBiasTable(entry)
        val next = WireDefault(old)
        val matched = scBiasValid(entry) && old.tag === trainScBiasTag
        next.tag := trainScBiasTag
        for (rank <- 0 until p.fetchWidth) {
            when(!matched) {
                next.counters(rank) := Mux(trainRanks(rank) && trainDirections(rank), 4.U, 3.U)
                next.confidence(rank) := 0.U
            }.elsewhen(trainRanks(rank)) {
                val correct = old.counters(rank)(2) === trainDirections(rank)
                next.counters(rank) := FrontendMath.sat(old.counters(rank), trainDirections(rank))
                when(!correct || train.earlyDirections(rank) =/= trainDirections(rank)) {
                    next.confidence(rank) := FrontendMath.sat(old.confidence(rank), correct)
                }
            }
        }
        when(trainCommit && trainRanks.asUInt.orR && trainScBiasIndexOH(entry)) {
            scBiasTable(entry) := next
            scBiasValid(entry) := true.B
        }
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
