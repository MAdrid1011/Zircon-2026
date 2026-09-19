import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class MainBTBIO(p: FrontendParams) extends Bundle {
    val query = Flipped(Valid(UInt(log2Ceil(p.btbSets).W)))
    val raw = Output(new FrontendBtbRaw(p, p.btbSets, p.btbWays))
    val train = Flipped(Valid(new BtbTraining(p)))
    val readSkipped = if (p.observe) Some(Output(Bool())) else None
}

/** Synchronous main BTB. Metadata stays in flops; two interleaved RAM banks hold masked slot payloads. */
class MainBTB(p: FrontendParams) extends Module {
    val io = IO(new MainBTBIO(p))

    /* Tag and Valid Storage */
    val indexBits = log2Ceil(p.btbSets)
    val tagBits = 32 - p.blockBits - indexBits
    val tags = Seq.fill(p.btbWays)(Reg(Vec(p.btbSets, UInt(tagBits.W))))
    val valid = Seq.fill(p.btbWays)(RegInit(VecInit.fill(p.btbSets)(0.U(p.fetchWidth.W))))
    val replacement = RegInit(VecInit.fill(p.btbSets)(false.B))
    def index(pc: UInt): UInt = pc(p.blockBits + indexBits - 1, p.blockBits)
    def tag(pc: UInt): UInt = pc(31, p.blockBits + indexBits)
    def row(idx: UInt): UInt = if (p.btbSets == 2) 0.U(1.W) else idx(indexBits - 1, 1)

    /* Training Lookup */
    // Metadata updates immediately, so consecutive partial updates see the newest allocation.
    val train = io.train.bits
    val trainPc = Cat(train.pcBlock, 0.U(p.blockBits.W))
    val trainIndex = index(trainPc)
    val hits = VecInit((0 until p.btbWays).map(w =>
        FrontendMath.read(valid(w).toSeq, trainIndex).orR &&
            FrontendMath.read(tags(w).toSeq, trainIndex) === tag(trainPc)
    ))
    val occupied = VecInit((0 until p.btbWays).map(w => FrontendMath.read(valid(w).toSeq, trainIndex).orR))
    val way = if (p.btbWays == 1) 0.U
    else Mux(
        hits.asUInt.orR,
        PriorityEncoder(hits),
        Mux(!occupied.asUInt.andR, PriorityEncoder(~occupied.asUInt), replacement(trainIndex).asUInt)
    )
    val cfi = VecInit((0 until p.fetchWidth).map(i => train.mask(i) && train.kinds(i) =/= 0.U)).asUInt
    val write = io.train.valid && (cfi.orR || hits.asUInt.orR)
    when(io.train.valid) { assert(PopCount(hits) <= 1.U, "Main BTB tags must have at most one matching way") }
    for (r <- 0 until p.btbSets; w <- 0 until p.btbWays) {
        when(write && trainIndex === r.U && way === w.U) {
            tags(w)(r) := tag(trainPc)
            valid(w)(r) := (Mux(hits(w), valid(w)(r), 0.U) & ~train.mask) | cfi
            replacement(r) := !way(0)
        }
    }

    /* Pending Payload Write */
    // One write drains every cycle. Only executed slots are overwritten; no payload read-modify-write is needed.
    val writeValid = RegNext(write, false.B)
    val writeIndex = RegEnable(trainIndex, write)
    val writeMask = RegEnable(
        VecInit((0 until p.btbWays).flatMap(w =>
            (0 until p.fetchWidth).map(i => way === w.U && train.mask(i))
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
    for (w <- 0 until p.btbWays) {
        io.raw.tags(w) := RegEnable(FrontendMath.read(tags(w).toSeq, queryIndex), read)
        io.raw.lines(w).valid := RegEnable(
            Mux(conflict, 0.U, FrontendMath.read(valid(w).toSeq, queryIndex)),
            0.U,
            io.query.valid
        )
        for (i <- 0 until p.fetchWidth) {
            val slot = data((w * p.fetchWidth + i + 1) * 33 - 1, (w * p.fetchWidth + i) * 33)
            io.raw.lines(w).targets(i) := slot(29, 0)
            io.raw.lines(w).kinds(i) := slot(32, 30)
            io.raw.lines(w).backward(i) := false.B
        }
    }

    if (p.observe) { io.readSkipped.get := io.query.valid && conflict }
}
