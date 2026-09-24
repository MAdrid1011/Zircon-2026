import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class IndirectTargetRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.ittageTagBits.W)
    val slotOH = UInt(p.fetchWidth.W)
    val target = UInt(30.W)
    val confidence = UInt(2.W)
}

class IndirectTargetQueryIO(p: FrontendParams) extends Bundle {
    val pcWord = Input(UInt(30.W))
    val folds = Input(Vec(p.tageCount, UInt(p.hashBits.W)))
    val fire = Input(Bool())
    val invalidate = Input(Bool())
}

class IndirectTargetPredictorIO(p: FrontendParams) extends Bundle {
    val query = new IndirectTargetQueryIO(p)
    val meta = Output(new FrontendIndirectMeta(p))
    val lookupMeta = Output(new FrontendIndirectMeta(p))
    val trainRead = Flipped(Valid(new FrontendTraining(p)))
    val train = Flipped(Valid(new FrontendTraining(p)))
}

/** Compact ITTAGE-like target predictor. The Main BTB supplies the base target. */
class IndirectTargetPredictor(
    p: FrontendParams,
    ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
) extends Module {
    val io = IO(new IndirectTargetPredictorIO(p))

    val tables = Seq.fill(p.ittageCount)(
        Module(new PredictorTableRam(p.ittageSets, (new IndirectTargetRow(p)).getWidth, ramBackend))
    )
    val valid = Seq.fill(p.ittageCount)(RegInit(VecInit.fill(p.ittageSets)(false.B)))
    val useful = Seq.fill(p.ittageCount)(RegInit(VecInit.fill(p.ittageSets)(false.B)))

    val aheadRows = Wire(Vec(p.ittageCount, new IndirectTargetRow(p)))
    val aheadValid = Reg(Vec(p.ittageCount, Bool()))
    val aheadIndices = Reg(Vec(p.ittageCount, UInt(p.ittageIndexBits.W)))
    val ahead = RegInit(false.B)

    def longest(candidates: Seq[Bool]): Seq[Bool] = {
        require(candidates.nonEmpty)
        val hits = VecInit(candidates).asUInt
        var suffix = hits
        var distance = 1
        while (distance < candidates.size) {
            suffix = suffix | (suffix >> distance).pad(candidates.size)
            distance *= 2
        }
        (hits & ~(suffix >> 1).pad(candidates.size)).asBools
    }

    val pcWord = io.query.pcWord
    val indices = VecInit(p.ittageHistoryIndices.zipWithIndex.map { case (history, table) =>
        FrontendMath.fold(pcWord ^ io.query.folds(history) ^ (table + 1).U, p.ittageIndexBits)
    })
    val tags = VecInit(p.ittageHistoryIndices.zipWithIndex.map { case (history, table) =>
        val mixed = FrontendMath.fold(pcWord, p.ittageTagBits) ^
            FrontendMath.fold(io.query.folds(history), p.ittageTagBits) ^ (table + 1).U
        mixed(p.ittageTagBits - 1, 0)
    })

    io.meta := 0.U.asTypeOf(new FrontendIndirectMeta(p))
    io.meta.indices := aheadIndices
    io.meta.tags := tags
    io.meta.aheadValid := ahead
    val alternateValid = Wire(Vec(p.fetchWidth, Bool()))
    val currentHitMasks = Wire(Vec(p.fetchWidth, UInt(p.ittageCount.W)))
    val tableMatches = (0 until p.ittageCount).map(table =>
        ahead && aheadValid(table) && aheadRows(table).tag === tags(table)
    )
    for (slot <- 0 until p.fetchWidth) {
        val hits = (0 until p.ittageCount).map { table =>
            tableMatches(table) && aheadRows(table).slotOH(slot)
        }
        currentHitMasks(slot) := VecInit(hits).asUInt
        val provider = longest(hits)
        val alternateHits = hits.zip(provider).map { case (hit, selected) => hit && !selected }
        val alternate = longest(alternateHits)
        val hasProvider = hits.reduce(_ || _)
        val hasAlternate = alternateHits.reduce(_ || _)
        io.meta.providers(slot) := Mux(
            hasProvider,
            Mux1H(provider.zipWithIndex.map { case (selected, table) =>
                selected -> (table + 1).U(p.ittageProviderBits.W)
            }),
            0.U
        )
        io.meta.providerTargets(slot) := Cat(Mux1H(provider.zip(aheadRows.map(_.target))), 0.U(2.W))
        io.meta.providerConfidence(slot) := Mux1H(provider.zip(aheadRows.map(_.confidence)))
        alternateValid(slot) := hasAlternate
        io.meta.alternateTargets(slot) := Cat(Mux1H(alternate.zip(aheadRows.map(_.target))), 0.U(2.W))
    }
    io.meta.alternateValid := alternateValid.asUInt

    // ITTAGE targets are consumed in IF2. Capture raw rows and hit masks at
    // the existing IF1/IF2 boundary, then perform the wide target mux there.
    val lookupRows = Reg(Vec(p.ittageCount, new IndirectTargetRow(p)))
    val lookupHitMasks = Reg(Vec(p.fetchWidth, UInt(p.ittageCount.W)))
    val lookupIndices = Reg(Vec(p.ittageCount, UInt(p.ittageIndexBits.W)))
    val lookupTags = Reg(Vec(p.ittageCount, UInt(p.ittageTagBits.W)))
    val lookupAhead = Reg(Bool())
    when(io.query.fire) {
        lookupRows := aheadRows
        lookupHitMasks := currentHitMasks
        lookupIndices := aheadIndices
        lookupTags := tags
        lookupAhead := ahead
    }

    io.lookupMeta := 0.U.asTypeOf(new FrontendIndirectMeta(p))
    io.lookupMeta.indices := lookupIndices
    io.lookupMeta.tags := lookupTags
    io.lookupMeta.aheadValid := lookupAhead
    val lookupAlternateValid = Wire(Vec(p.fetchWidth, Bool()))
    for (slot <- 0 until p.fetchWidth) {
        val provider = longest(lookupHitMasks(slot).asBools)
        val alternateHits = lookupHitMasks(slot).asBools.zip(provider).map { case (hit, selected) =>
            hit && !selected
        }
        val alternate = longest(alternateHits)
        val hasProvider = lookupHitMasks(slot).orR
        val hasAlternate = alternateHits.reduce(_ || _)
        io.lookupMeta.providers(slot) := Mux(
            hasProvider,
            Mux1H(provider.zipWithIndex.map { case (selected, table) =>
                selected -> (table + 1).U(p.ittageProviderBits.W)
            }),
            0.U,
        )
        io.lookupMeta.providerTargets(slot) := Cat(Mux1H(provider.zip(lookupRows.map(_.target))), 0.U(2.W))
        io.lookupMeta.providerConfidence(slot) := Mux1H(provider.zip(lookupRows.map(_.confidence)))
        lookupAlternateValid(slot) := hasAlternate
        io.lookupMeta.alternateTargets(slot) := Cat(Mux1H(alternate.zip(lookupRows.map(_.target))), 0.U(2.W))
    }
    io.lookupMeta.alternateValid := lookupAlternateValid.asUInt

    for (table <- 0 until p.ittageCount) {
        tables(table).io.predictEnable := io.query.fire
        tables(table).io.predictAddress := indices(table)
        aheadRows(table) := tables(table).io.predictData.asTypeOf(new IndirectTargetRow(p))
    }
    when(io.query.fire) {
        ahead := true.B
        for (table <- 0 until p.ittageCount) {
            aheadValid(table) := valid(table)(indices(table))
            aheadIndices(table) := indices(table)
        }
    }
    when(io.query.invalidate) { ahead := false.B }

    val train = io.train.bits
    val trainSlots = VecInit((0 until p.fetchWidth).map { slot =>
        train.mask(slot) && train.taken(slot) && train.kinds(slot) === FrontendCfi.Indirect.U
    })
    val trainSlot = PriorityEncoder(trainSlots)
    val trainValid = io.train.valid && train.meta.ittage.aheadValid && trainSlots.asUInt.orR
    when(io.train.valid) {
        assert(PopCount(trainSlots) <= 1.U, "At most one ordinary indirect jump can train ITTAGE per block")
    }

    val trainRows = (0 until p.ittageCount).map { table =>
        tables(table).io.updateReadEnable := io.trainRead.valid
        tables(table).io.updateReadAddress := io.trainRead.bits.meta.ittage.indices(table)
        tables(table).io.updateReadData.asTypeOf(new IndirectTargetRow(p))
    }
    val trainMatches = VecInit((0 until p.ittageCount).map { table =>
        valid(table)(train.meta.ittage.indices(table)) &&
            trainRows(table).tag === train.meta.ittage.tags(table) &&
            (trainRows(table).slotOH & UIntToOH(trainSlot, p.fetchWidth)).orR
    })
    val requestedProvider = train.meta.ittage.providers(trainSlot)
    val providerMatches = VecInit((0 until p.ittageCount).map { table =>
        trainMatches(table) && requestedProvider === (table + 1).U
    })
    val providerPresent = providerMatches.asUInt.orR
    val provider = Mux(providerPresent, requestedProvider, 0.U)
    val actualTarget = train.targets(trainSlot)
    val providerTarget = train.meta.ittage.providerTargets(trainSlot)
    val alternateTarget = train.meta.ittage.alternateTargets(trainSlot)
    val predictedTarget = train.meta.ittage.predictedTargets(trainSlot)
    val providerCorrect = providerPresent && providerTarget === actualTarget
    val mispredicted = predictedTarget =/= actualTarget
    val needAllocation = mispredicted && !providerCorrect
    val allocatable = VecInit((0 until p.ittageCount).map { table =>
        (table + 1).U > provider && (
            !valid(table)(train.meta.ittage.indices(table)) || !useful(table)(train.meta.ittage.indices(table))
        )
    })
    val allocate = PriorityEncoderOH(allocatable)

    for (table <- 0 until p.ittageCount) {
        val old = trainRows(table)
        val next = WireDefault(old)
        val nextUseful = WireDefault(useful(table)(train.meta.ittage.indices(table)))
        val isProvider = providerMatches(table)
        val doAllocate = needAllocation && allocate(table)
        val ageUseful = needAllocation && !allocatable.asUInt.orR && (table + 1).U > provider
        when(isProvider) {
            when(providerTarget === actualTarget) {
                next.confidence := FrontendMath.sat(old.confidence, true.B)
            }.elsewhen(old.confidence =/= 0.U) {
                next.confidence := FrontendMath.sat(old.confidence, false.B)
            }.otherwise {
                next.target := actualTarget(31, 2)
            }
            when(providerTarget =/= alternateTarget) {
                nextUseful := providerTarget === actualTarget
            }
        }
        when(ageUseful) { nextUseful := false.B }
        when(doAllocate) {
            next.tag := train.meta.ittage.tags(table)
            next.slotOH := UIntToOH(trainSlot, p.fetchWidth)
            next.target := actualTarget(31, 2)
            next.confidence := 0.U
            nextUseful := false.B
        }
        val update = trainValid && (isProvider || ageUseful || doAllocate)
        val write = update
        tables(table).io.updateWriteEnable := write
        tables(table).io.updateWriteAddress := train.meta.ittage.indices(table)
        tables(table).io.updateWriteData := next.asUInt
        when(write) {
            useful(table)(train.meta.ittage.indices(table)) := nextUseful
            when(doAllocate) {
                valid(table)(train.meta.ittage.indices(table)) := true.B
            }
        }
    }
}
