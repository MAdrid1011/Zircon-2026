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

class BtbTraining(p: FrontendParams) extends Bundle {
    val pcBlock = UInt((32 - p.blockBits).W)
    val mask = UInt(p.fetchWidth.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val targetWords = Vec(p.fetchWidth, UInt(30.W))
}

class BlockBTBIO(p: FrontendParams, sets: Int, ways: Int) extends Bundle {
    val index = Input(UInt(log2Ceil(sets).W))
    val raw = Output(new FrontendBtbRaw(p, sets, ways))
    val train = Flipped(Valid(new BtbTraining(p)))
}

/** A block shares a tag; training preserves unexecuted slots in a matching row. */
class BlockBTB(p: FrontendParams, sets: Int, ways: Int) extends Module {
    val io = IO(new BlockBTBIO(p, sets, ways))

    /* Storage */
    // All slots in a row share one block tag. Valid bits reset independently of data.
    val indexBits = log2Ceil(sets)
    val tagBits = 32 - p.blockBits - indexBits
    val tags = Seq.fill(ways)(Reg(Vec(sets, UInt(tagBits.W))))
    val payload = Seq.fill(ways)(Reg(Vec(sets, new FrontendBtbLine(p))))
    val valid = Seq.fill(ways)(RegInit(VecInit.fill(sets)(0.U(p.fetchWidth.W))))
    val replacement = RegInit(VecInit.fill(sets)(false.B))
    def index(pc: UInt): UInt = pc(p.blockBits + indexBits - 1, p.blockBits)
    def tag(pc: UInt): UInt = pc(31, p.blockBits + indexBits)

    /* Prediction Read */
    for (way <- 0 until ways) {
        io.raw.tags(way) := FrontendMath.read(tags(way).toSeq, io.index)
        io.raw.lines(way) := FrontendMath.read(payload(way).toSeq, io.index)
        io.raw.lines(way).valid := FrontendMath.read(valid(way).toSeq, io.index)
    }

    /* Training Lookup and Way Selection */
    val train = io.train.bits
    val trainPc = Cat(train.pcBlock, 0.U(p.blockBits.W))
    val writeIndex = index(trainPc)
    val writeHits = VecInit((0 until ways).map(w =>
        FrontendMath.read(valid(w).toSeq, writeIndex).orR &&
            FrontendMath.read(tags(w).toSeq, writeIndex) === tag(trainPc)
    ))
    val writeOccupied = VecInit((0 until ways).map(w => FrontendMath.read(valid(w).toSeq, writeIndex).orR))
    // Reuse a matching way, then an empty way, then the replacement way.
    val writeWay = if (ways == 1) 0.U
    else Mux(
        writeHits.asUInt.orR,
        PriorityEncoder(writeHits),
        Mux(!writeOccupied.asUInt.andR, PriorityEncoder(~writeOccupied.asUInt), replacement(writeIndex).asUInt)
    )
    val writeCfi = VecInit((0 until p.fetchWidth).map(i => train.mask(i) && train.kinds(i) =/= 0.U)).asUInt
    when(io.train.valid) {
        assert(PopCount(writeHits) <= 1.U, "BTB tags must have at most one matching way")
    }

    /* Training Write */
    // A matching row keeps the unexecuted suffix; ordinary instructions clear stale CFI bits.
    for (row <- 0 until sets; way <- 0 until ways) {
        when(io.train.valid && writeIndex === row.U && writeWay === way.U && (writeCfi.orR || writeHits.asUInt.orR)) {
            tags(way)(row) := tag(trainPc)
            valid(way)(row) := (Mux(writeHits(way), valid(way)(row), 0.U) & ~train.mask) | writeCfi
            for (slot <- 0 until p.fetchWidth) {
                when(train.mask(slot)) {
                    payload(way)(row).kinds(slot) := train.kinds(slot)
                    payload(way)(row).targets(slot) := train.targetWords(slot)
                    payload(way)(row).backward(slot) := FrontendCfi.conditional(train.kinds(slot)) &&
                        FrontendMath.backwardBranch(
                            FrontendMath.slotPc(trainPc, slot, p),
                            Cat(train.targetWords(slot), 0.U(2.W)),
                        )
                }
            }
            replacement(row) := !writeWay(0)
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
