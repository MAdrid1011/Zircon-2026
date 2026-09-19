import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class IndirectTargetRow(p: FrontendParams) extends Bundle {
    val tag = UInt(p.ittageTagBits.W)
    val slot = UInt(p.slotBits.W)
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
    val train = Flipped(Valid(new FrontendTraining(p)))
}

/** Compact ITTAGE-like target predictor. The Main BTB supplies the base target. */
class IndirectTargetPredictor(p: FrontendParams) extends Module {
    val io = IO(new IndirectTargetPredictorIO(p))

    val tables = Seq.fill(p.ittageCount)(
        Module(new AsyncRegRam(new IndirectTargetRow(p), p.ittageSets, 1, 2))
    )
    val valid = Seq.fill(p.ittageCount)(RegInit(VecInit.fill(p.ittageSets)(false.B)))
    val useful = Seq.fill(p.ittageCount)(RegInit(VecInit.fill(p.ittageSets)(false.B)))

    val aheadRows = Reg(Vec(p.ittageCount, new IndirectTargetRow(p)))
    val aheadValid = Reg(Vec(p.ittageCount, Bool()))
    val aheadIndices = Reg(Vec(p.ittageCount, UInt(p.ittageIndexBits.W)))
    val ahead = RegInit(false.B)

    def longest(candidates: Seq[Bool]): Seq[Bool] = candidates.indices.map { i =>
        candidates(i) && !candidates.drop(i + 1).foldLeft(false.B)(_ || _)
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
    for (slot <- 0 until p.fetchWidth) {
        val hits = (0 until p.ittageCount).map { table =>
            ahead && aheadValid(table) && aheadRows(table).tag === tags(table) &&
                aheadRows(table).slot === slot.U
        }
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

    for (table <- 0 until p.ittageCount) {
        tables(table).io.raddr(0) := indices(table)
    }
    when(io.query.fire) {
        ahead := true.B
        for (table <- 0 until p.ittageCount) {
            aheadRows(table) := tables(table).io.rdata(0)
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
        tables(table).io.raddr(1) := train.meta.ittage.indices(table)
        tables(table).io.rdata(1)
    }
    val trainMatches = VecInit((0 until p.ittageCount).map { table =>
        valid(table)(train.meta.ittage.indices(table)) &&
            trainRows(table).tag === train.meta.ittage.tags(table) && trainRows(table).slot === trainSlot
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
            next.slot := trainSlot
            next.target := actualTarget(31, 2)
            next.confidence := 0.U
            nextUseful := false.B
        }
        val write = trainValid && (isProvider || ageUseful || doAllocate)
        tables(table).io.wen(0) := write
        tables(table).io.waddr(0) := train.meta.ittage.indices(table)
        tables(table).io.wdata(0) := next
        when(write) {
            useful(table)(train.meta.ittage.indices(table)) := nextUseful
            when(doAllocate) {
                valid(table)(train.meta.ittage.indices(table)) := true.B
            }
        }
    }
}
