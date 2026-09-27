import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FrontendBtbLine(p: FrontendParams) extends Bundle {
    val valid = UInt(p.fetchWidth.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val targets = Vec(p.fetchWidth, UInt(30.W))
    val backward = Vec(p.fetchWidth, Bool())
}

class FrontendBtbRaw(p: FrontendParams, sets: Int, ways: Int) extends Bundle {
    val tags = Vec(ways, UInt((32 - p.blockBits - log2Ceil(sets)).W))
    val lines = Vec(ways, new FrontendBtbLine(p))
}

class BtbTraining(p: FrontendParams, sets: Int) extends Bundle {
    val index = UInt(log2Ceil(sets).W)
    val indexOH = UInt(sets.W)
    val tag = UInt((32 - p.blockBits - log2Ceil(sets)).W)
    val mask = UInt(p.fetchWidth.W)
    val cfi = UInt(p.fetchWidth.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val targetWords = Vec(p.fetchWidth, UInt(30.W))
    val backward = UInt(p.fetchWidth.W)
}

class BlockBTBIO(p: FrontendParams, sets: Int, ways: Int) extends Bundle {
    val indexOH = Input(UInt(sets.W))
    val lookupTag = Input(UInt((32 - p.blockBits - log2Ceil(sets)).W))
    val trainTagGroups = Input(Vec(math.min(8, sets), UInt((32 - p.blockBits - log2Ceil(sets)).W)))
    val hits = Output(Vec(ways, Bool()))
    val raw = Output(new FrontendBtbRaw(p, sets, ways))
    val train = Flipped(Valid(new BtbTraining(p, sets)))
}

/** A block shares a tag; training preserves unexecuted slots in a matching row. */
class BlockBTB(p: FrontendParams, sets: Int, ways: Int) extends Module {
    val io = IO(new BlockBTBIO(p, sets, ways))

    /* Storage */
    // All slots in a row share one block tag. Valid bits reset independently of data.
    val indexBits = log2Ceil(sets)
    val tagBits = 32 - p.blockBits - indexBits
    val rowsPerTagGroup = sets / math.min(8, sets)
    val tags = Seq.fill(ways)(Reg(Vec(sets, UInt(tagBits.W))))
    val payload = Seq.fill(ways)(Reg(Vec(sets, new FrontendBtbLine(p))))
    val valid = Seq.fill(ways)(RegInit(VecInit.fill(sets)(0.U(p.fetchWidth.W))))
    val replacement = RegInit(VecInit.fill(sets)(false.B))
    /* Prediction Read */
    assert(PopCount(io.indexOH) <= 1.U)
    for (way <- 0 until ways) {
        val selectedTag = Mux1H(io.indexOH.asBools, tags(way))
        val selectedValid = Mux1H(io.indexOH.asBools, valid(way))
        io.hits(way) := selectedValid.orR && selectedTag === io.lookupTag
        io.raw.tags(way) := selectedTag
        io.raw.lines(way) := Mux1H(io.indexOH.asBools, payload(way))
        io.raw.lines(way).valid := selectedValid
    }

    /* Training Lookup and Way Selection */
    val train = io.train.bits
    val writeHits = VecInit((0 until ways).map(w =>
        Mux1H(train.indexOH.asBools, valid(w)).orR &&
            Mux1H(train.indexOH.asBools, tags(w)) === train.tag
    ))
    val writeOccupied = VecInit((0 until ways).map(w => Mux1H(train.indexOH.asBools, valid(w)).orR))
    // Reuse a matching way, then an empty way, then the replacement way.
    val writeWay = if (ways == 1) 0.U
    else Mux(
        writeHits.asUInt.orR,
        PriorityEncoder(writeHits),
        Mux(
            !writeOccupied.asUInt.andR,
            PriorityEncoder(~writeOccupied.asUInt),
            Mux1H(train.indexOH.asBools, replacement).asUInt,
        )
    )
    when(io.train.valid) {
        assert(PopCount(writeHits) <= 1.U, "BTB tags must have at most one matching way")
        assert(PopCount(train.indexOH) === 1.U, "BTB training index must be one-hot")
    }

    /* Training Write */
    // A matching row keeps the unexecuted suffix; ordinary instructions clear stale CFI bits.
    for (row <- 0 until sets) {
        // Keep training-way selection within its candidate row.  The global
        // selection above remains for the protocol assertions, but feeding it
        // into every payload D input created a wide train-index/occupancy cone.
        val rowHits = VecInit((0 until ways).map(way =>
            valid(way)(row).orR && tags(way)(row) === io.trainTagGroups(row / rowsPerTagGroup)
        ))
        val rowOccupied = VecInit((0 until ways).map(way => valid(way)(row).orR))
        val rowWriteWay = if (ways == 1) 0.U else Mux(
            rowHits.asUInt.orR,
            PriorityEncoder(rowHits),
            Mux(
                !rowOccupied.asUInt.andR,
                PriorityEncoder(~rowOccupied.asUInt),
                replacement(row).asUInt,
            )
        )
        for (way <- 0 until ways) {
            when(io.train.valid && train.indexOH(row) && rowWriteWay === way.U &&
                (train.cfi.orR || rowHits.asUInt.orR)) {
                when(train.cfi.orR) { tags(way)(row) := io.trainTagGroups(row / rowsPerTagGroup) }
                valid(way)(row) := (Mux(rowHits(way), valid(way)(row), 0.U) & ~train.mask) | train.cfi
                for (slot <- 0 until p.fetchWidth) {
                    // Payload belonging to a cleared ordinary slot is unobservable.
                    when(train.cfi(slot)) {
                        payload(way)(row).kinds(slot) := train.kinds(slot)
                        payload(way)(row).targets(slot) := train.targetWords(slot)
                        payload(way)(row).backward(slot) := train.backward(slot)
                    }
                }
                replacement(row) := !rowWriteWay(0)
            }
        }
    }
}

class BlockBTBLookupIO(p: FrontendParams, sets: Int, ways: Int) extends Bundle {
    val tag = Input(UInt((32 - p.blockBits - log2Ceil(sets)).W))
    val raw = Input(new FrontendBtbRaw(p, sets, ways))
    val line = Output(new FrontendBtbLine(p))
}

/** Resolve tags after the table read; IF2 can register raw ways before this lookup. */
class BlockBTBLookup(p: FrontendParams, sets: Int, ways: Int) extends RawModule {
    val io = IO(new BlockBTBLookupIO(p, sets, ways))
    val hits = io.raw.tags.zip(io.raw.lines).map { case (tag, line) =>
        line.valid.orR && tag === io.tag
    }
    io.line := Mux1H(hits, io.raw.lines)
    // Mux1H with one input passes the payload through, even when its select is false.
    io.line.valid := Mux(hits.reduce(_ || _), Mux1H(hits, io.raw.lines.map(_.valid)), 0.U)
}
