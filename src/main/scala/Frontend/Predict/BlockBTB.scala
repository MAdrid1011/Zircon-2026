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

class BlockBTBIO(p: FrontendParams, sets: Int, ways: Int, synchronousPayload: Boolean = false) extends Bundle {
    val indexOH = Input(UInt(sets.W))
    val lookupIndex = if (synchronousPayload) Some(Input(UInt(log2Ceil(sets).W))) else None
    val lookupTag = Input(UInt((32 - p.blockBits - log2Ceil(sets)).W))
    val prefetch = if (synchronousPayload) Some(Flipped(Valid(UInt(log2Ceil(sets).W)))) else None
    val trainTagGroups = Input(Vec(math.min(8, sets), UInt((32 - p.blockBits - log2Ceil(sets)).W)))
    val hits = Output(Vec(ways, Bool()))
    val raw = Output(new FrontendBtbRaw(p, sets, ways))
    val train = Flipped(Valid(new BtbTraining(p, sets)))
}

/** A block shares a tag; training preserves unexecuted slots in a matching row. */
class BlockBTB(
    p: FrontendParams,
    sets: Int,
    ways: Int,
    ramBackend: DualPortRamBackend = DualPortRamBackend.Vivado,
    synchronousPayload: Boolean = false,
) extends Module {
    require(!synchronousPayload || ramBackend != DualPortRamBackend.Registers)
    val io = IO(new BlockBTBIO(p, sets, ways, synchronousPayload))

    /* Storage */
    // All slots in a row share one block tag. Valid bits reset independently of data.
    val indexBits = log2Ceil(sets)
    val tagBits = 32 - p.blockBits - indexBits
    val rowsPerTagGroup = sets / math.min(8, sets)
    val tagMem = if (synchronousPayload && ramBackend == DualPortRamBackend.Vivado) {
        Some(Seq.fill(ways)(Mem(sets, UInt(tagBits.W))))
    } else None
    val tags = if (tagMem.isEmpty) Some(Seq.fill(ways)(Reg(Vec(sets, UInt(tagBits.W))))) else None
    val payloadMem = if (!synchronousPayload && ramBackend == DualPortRamBackend.Vivado) {
        Some(Seq.fill(ways)(Seq.fill(p.fetchWidth)(Mem(sets, UInt(34.W)))))
    } else None
    val payloadReg = if (!synchronousPayload && payloadMem.isEmpty) {
        Some(Seq.fill(ways)(Reg(Vec(sets, new FrontendBtbLine(p)))))
    } else None
    val payloadSramRead = if (synchronousPayload) {
        Some(Wire(Vec(ways, Vec(p.fetchWidth, UInt(34.W)))))
    } else None
    val valid = Seq.fill(ways)(RegInit(VecInit.fill(sets)(0.U(p.fetchWidth.W))))
    val replacement = RegInit(VecInit.fill(sets)(false.B))
    /* Prediction Read */
    assert(PopCount(io.indexOH) <= 1.U)
    for (way <- 0 until ways) {
        val selectedTag = if (tagMem.isDefined) tagMem.get(way).read(io.lookupIndex.get)
            else Mux1H(io.indexOH.asBools, tags.get(way))
        val selectedValid = Mux1H(io.indexOH.asBools, valid(way))
        io.hits(way) := selectedValid.orR && selectedTag === io.lookupTag
        io.raw.tags(way) := selectedTag
        if (synchronousPayload) {
            for (slot <- 0 until p.fetchWidth) {
                val data = payloadSramRead.get(way)(slot)
                io.raw.lines(way).kinds(slot) := data(33, 31)
                io.raw.lines(way).targets(slot) := data(30, 1)
                io.raw.lines(way).backward(slot) := data(0)
            }
        } else if (payloadMem.isDefined) {
            val readIndex = OHToUInt(io.indexOH)
            for (slot <- 0 until p.fetchWidth) {
                val data = payloadMem.get(way)(slot).read(readIndex)
                io.raw.lines(way).kinds(slot) := data(33, 31)
                io.raw.lines(way).targets(slot) := data(30, 1)
                io.raw.lines(way).backward(slot) := data(0)
            }
        } else {
            io.raw.lines(way) := Mux1H(io.indexOH.asBools, payloadReg.get(way))
        }
        io.raw.lines(way).valid := selectedValid
    }

    /* Training Lookup and Way Selection */
    val train = io.train.bits
    val tagGroup = FrontendMath.read(
        io.trainTagGroups.toSeq,
        if (rowsPerTagGroup == 1) train.index
        else train.index(indexBits - 1, log2Ceil(rowsPerTagGroup)),
    )
    val trainTags = (0 until ways).map(way =>
        if (tagMem.isDefined) tagMem.get(way).read(train.index)
        else Mux1H(train.indexOH.asBools, tags.get(way))
    )
    val writeHits = VecInit((0 until ways).map(w =>
        Mux1H(train.indexOH.asBools, valid(w)).orR &&
            trainTags(w) === train.tag
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
    val rowWriteWays = Wire(Vec(sets, UInt(ways.W)))
    for (row <- 0 until sets) {
        // Keep training-way selection within its candidate row.  The global
        // selection above remains for the protocol assertions, but feeding it
        // into every payload D input created a wide train-index/occupancy cone.
        val rowHits = VecInit((0 until ways).map(way =>
            if (tagMem.isDefined) train.indexOH(row) &&
                valid(way)(row).orR && trainTags(way) === tagGroup
            else valid(way)(row).orR && tags.get(way)(row) === io.trainTagGroups(row / rowsPerTagGroup)
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
        rowWriteWays(row) := UIntToOH(rowWriteWay, ways)
        for (way <- 0 until ways) {
            when(io.train.valid && train.indexOH(row) && rowWriteWay === way.U &&
                (train.cfi.orR || rowHits.asUInt.orR)) {
                if (tags.isDefined) {
                    when(train.cfi.orR) { tags.get(way)(row) := io.trainTagGroups(row / rowsPerTagGroup) }
                }
                valid(way)(row) := (Mux(rowHits(way), valid(way)(row), 0.U) & ~train.mask) | train.cfi
                if (payloadReg.isDefined) {
                    for (slot <- 0 until p.fetchWidth) {
                        // Payload belonging to a cleared ordinary slot is unobservable.
                        when(train.cfi(slot)) {
                            payloadReg.get(way)(row).kinds(slot) := train.kinds(slot)
                            payloadReg.get(way)(row).targets(slot) := train.targetWords(slot)
                            payloadReg.get(way)(row).backward(slot) := train.backward(slot)
                        }
                    }
                }
                replacement(row) := !rowWriteWay(0)
            }
        }
    }
    tagMem.foreach { memories =>
        val selectedWay = Mux1H(train.indexOH.asBools, rowWriteWays)
        for (way <- 0 until ways) {
            when(io.train.valid && train.cfi.orR && selectedWay(way)) {
                memories(way).write(train.index, tagGroup)
            }
        }
    }
    if (synchronousPayload) {
        val selectedWay = Mux1H(train.indexOH.asBools, rowWriteWays)
        for (way <- 0 until ways; slot <- 0 until p.fetchWidth) {
            val writeSlot = io.train.valid && selectedWay(way) && train.cfi(slot)
            val writeData = Cat(train.kinds(slot), train.targetWords(slot), train.backward(slot))
            if (ramBackend == DualPortRamBackend.BSG) {
                val useMacro = sys.env.get("ZIRCON_USE_EXTERNAL_BSG_RAM").contains("true")
                val ram = if (useMacro) Module(new PredictorBsgFakeramMacro(sets, 34)).io
                    else Module(new PredictorBsgFakeram(sets, 34)).io
                ram.clock := clock
                ram.csb0 := !writeSlot
                ram.csb1 := !io.prefetch.get.valid
                ram.web0 := !writeSlot
                ram.addr0 := train.index
                ram.addr1 := io.prefetch.get.bits
                ram.din0 := writeData
                payloadSramRead.get(way)(slot) := ram.dout1
            } else {
                val ram = Module(new XilinxTrueDualPortReadFirst1ClockRam(34, sets))
                ram.io.clka := clock
                ram.io.addra := train.index
                ram.io.addrb := io.prefetch.get.bits
                ram.io.dina := writeData
                ram.io.dinb := 0.U
                ram.io.wea := writeSlot
                ram.io.web := false.B
                ram.io.ena := writeSlot
                ram.io.enb := io.prefetch.get.valid
                payloadSramRead.get(way)(slot) := ram.io.doutb
            }
        }
    }
    payloadMem.foreach { memories =>
        val selectedWay = Mux1H(train.indexOH.asBools, rowWriteWays)
        for (way <- 0 until ways; slot <- 0 until p.fetchWidth) {
            when(io.train.valid && selectedWay(way) && train.cfi(slot)) {
                memories(way)(slot).write(
                    train.index,
                    Cat(train.kinds(slot), train.targetWords(slot), train.backward(slot)),
                )
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
