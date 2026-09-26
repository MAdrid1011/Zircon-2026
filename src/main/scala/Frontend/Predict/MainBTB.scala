import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class MainBTBIO(p: FrontendParams) extends Bundle {
    val query = Flipped(Valid(UInt(log2Ceil(p.btbSets).W)))
    val queryTag = Input(UInt((32 - p.blockBits - log2Ceil(p.btbSets)).W))
    val raw = Output(new FrontendBtbRaw(p, p.btbSets, p.btbWays))
    val hits = Output(UInt(p.btbWays.W))
    val train = Flipped(Valid(new BtbTraining(p, p.btbSets)))
    val trainTagGroups = Input(Vec(math.min(8, p.btbSets), UInt((32 - p.blockBits - log2Ceil(p.btbSets)).W)))
    val readSkipped = if (p.observe) Some(Output(Bool())) else None
}

/** Synchronous main BTB. Metadata stays in flops; two interleaved RAM banks hold masked slot payloads. */
class MainBTB(p: FrontendParams) extends Module {
    val io = IO(new MainBTBIO(p))

    /* Tag and Valid Storage */
    val indexBits = log2Ceil(p.btbSets)
    val tagBits = 32 - p.blockBits - indexBits
    val rowsPerTagGroup = p.btbSets / math.min(8, p.btbSets)
    val tags = Seq.fill(p.btbWays)(Reg(Vec(p.btbSets, UInt(tagBits.W))))
    val valid = Seq.fill(p.btbWays)(RegInit(VecInit.fill(p.btbSets)(0.U(p.fetchWidth.W))))
    val replacement = RegInit(VecInit.fill(p.btbSets)(false.B))
    def row(idx: UInt): UInt = if (p.btbSets == 2) 0.U(1.W) else idx(indexBits - 1, 1)

    /* Training Lookup */
    // Metadata updates immediately, so consecutive partial updates see the newest allocation.
    val train = io.train.bits
    val trainIndex = train.index
    // Keep the entire metadata update cone local to each set.  A global
    // train-index lookup is still needed for the pipelined payload RAM write,
    // but must not become the enable of every tag or valid D input.
    val rowHits = (0 until p.btbSets).map { r =>
        VecInit((0 until p.btbWays).map(w =>
            valid(w)(r).orR && tags(w)(r) === io.trainTagGroups(r / rowsPerTagGroup)
        )).asUInt
    }
    val rowOccupied = (0 until p.btbSets).map { r =>
        VecInit((0 until p.btbWays).map(w => valid(w)(r).orR))
    }
    val rowAllocationWayOH = (0 until p.btbSets).map { r =>
        if (p.btbWays == 1) 1.U(1.W) else {
            val rowReplacementWay = replacement(r)
            Mux(
                !rowOccupied(r)(0),
                1.U(p.btbWays.W),
                Mux(!rowOccupied(r)(1), 2.U(p.btbWays.W), Cat(rowReplacementWay, !rowReplacementWay)),
            )
        }
    }
    val rowSelectedWayOH = (0 until p.btbSets).map { r =>
        Mux(rowHits(r).orR, rowHits(r), rowAllocationWayOH(r))
    }
    val selectedRowHasHit = Mux1H(train.indexOH.asBools, rowHits.map(_.orR))
    val selectedWayOH = Mux1H(train.indexOH.asBools, rowSelectedWayOH)
    val write = io.train.valid && (train.cfi.orR || selectedRowHasHit)
    when(io.train.valid) {
        assert(PopCount(train.indexOH) === 1.U, "Main BTB training index must be one-hot")
        for (r <- 0 until p.btbSets) {
            when(train.indexOH(r)) {
                assert(PopCount(rowHits(r)) <= 1.U, "Main BTB tags must have at most one matching way")
                assert(PopCount(rowAllocationWayOH(r)) === 1.U, "Main BTB allocation way must be one-hot")
                assert(PopCount(rowSelectedWayOH(r)) === 1.U, "Main BTB selected way must be one-hot")
            }
        }
    }
    for (r <- 0 until p.btbSets) {
        for (w <- 0 until p.btbWays) {
            when(io.train.valid && train.cfi.orR && train.indexOH(r) &&
                !rowHits(r).orR && rowAllocationWayOH(r)(w)) {
                tags(w)(r) := io.trainTagGroups(r / rowsPerTagGroup)
            }
            when(io.train.valid && train.indexOH(r) && rowSelectedWayOH(r)(w) &&
                (train.cfi.orR || rowHits(r).orR)) {
                valid(w)(r) := (Mux(rowHits(r)(w), valid(w)(r), 0.U) & ~train.mask) | train.cfi
                replacement(r) := (w == 0).B
            }
        }
    }

    /* Pending Payload Write */
    // One write drains every cycle. Only executed slots are overwritten; no payload read-modify-write is needed.
    val writeValid = RegNext(write, false.B)
    val writeIndex = RegEnable(trainIndex, write)
    val writeMask = RegEnable(
        VecInit((0 until p.btbWays).flatMap(w =>
            (0 until p.fetchWidth).map(i => selectedWayOH(w) && train.mask(i))
        )).asUInt,
        write
    )
    val writeData = RegEnable(
        VecInit((0 until p.btbWays).flatMap(_ =>
            (0 until p.fetchWidth).map(i =>
                Cat(train.kinds(i), train.targetWords(i))
            )
        )).asUInt,
        write
    )

    /* IF1 RAM Request */
    val queryIndex = io.query.bits
    // A conflicting main lookup becomes a miss; IF1 keeps running with the fast BTB prediction.
    val conflict = writeValid && queryIndex(0) === writeIndex(0)
    val read = io.query.valid && !conflict
    val banks = Seq.fill(2)(Module(new SinglePortMaskedRam(p.btbSets / 2, p.btbWays * p.fetchWidth, 33)))
    for (b <- 0 until 2) {
        val writing = writeValid && writeIndex(0) === b.U
        banks(b).io.clock := clock
        // Read ahead of acceptance; late PD/queue control must not drive the RAM enable.
        banks(b).io.enable := !reset.asBool && (writing || queryIndex(0) === b.U)
        banks(b).io.write := writing
        banks(b).io.address := Mux(writing, row(writeIndex), row(queryIndex))
        banks(b).io.dataIn := writeData
        banks(b).io.mask := writeMask
    }

    /* IF2 Response and Stall Hold */
    // The RAM read crosses the IF1/IF2 edge. Save its response before a later write can disturb its output.
    val readBank = RegEnable(queryIndex(0), read)
    val readIssued = RegNext(read, false.B)
    val readData = Mux(readBank, banks(1).io.dataOut, banks(0).io.dataOut)
    val heldData = RegEnable(readData, readIssued)
    val data = Mux(readIssued, readData, heldData)
    val hit = Wire(Vec(p.btbWays, Bool()))
    for (w <- 0 until p.btbWays) {
        val selectedTag = FrontendMath.read(tags(w).toSeq, queryIndex)
        val selectedValid = FrontendMath.read(valid(w).toSeq, queryIndex)
        io.raw.tags(w) := RegEnable(selectedTag, read)
        io.raw.lines(w).valid := RegEnable(
            Mux(conflict, 0.U, selectedValid),
            0.U,
            io.query.valid
        )
        hit(w) := RegEnable(!conflict && selectedValid.orR && selectedTag === io.queryTag,
            false.B, io.query.valid)
        for (i <- 0 until p.fetchWidth) {
            val slot = data((w * p.fetchWidth + i + 1) * 33 - 1, (w * p.fetchWidth + i) * 33)
            io.raw.lines(w).targets(i) := slot(29, 0)
            io.raw.lines(w).kinds(i) := slot(32, 30)
            io.raw.lines(w).backward(i) := false.B
        }
    }
    io.hits := hit.asUInt

    if (p.observe) { io.readSkipped.get := io.query.valid && conflict }
}
