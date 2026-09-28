import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class MainBTBComparison(p: FrontendParams, backend: DualPortRamBackend) extends Module {
    val io = IO(new Bundle {
        val query = Flipped(Valid(UInt(32.W)))
        val train = Flipped(Valid(new Bundle {
            val pc = UInt(32.W)
            val mask = UInt(p.fetchWidth.W)
            val kinds = Vec(p.fetchWidth, UInt(3.W))
            val targets = Vec(p.fetchWidth, UInt(32.W))
        }))
        val equal = Output(Bool())
        val parallelHitEqual = Output(Bool())
        val readSkipped = Output(Bool())
    })
    val dut = Module(new MainBTB(p, backend))
    val reference = Module(new BlockBTB(p, p.btbSets, p.btbWays))
    dut.io.query.valid := io.query.valid
    dut.io.query.bits := io.query.bits(p.blockBits + log2Ceil(p.btbSets) - 1, p.blockBits)
    dut.io.queryTag := io.query.bits(31, p.blockBits + log2Ceil(p.btbSets))
    reference.io.indexOH := UIntToOH(
        io.query.bits(p.blockBits + log2Ceil(p.btbSets) - 1, p.blockBits),
        p.btbSets,
    )
    reference.io.lookupTag := io.query.bits(31, p.blockBits + log2Ceil(p.btbSets))
    io.parallelHitEqual := (0 until p.btbWays).map { way =>
        reference.io.hits(way) ===
            (reference.io.raw.lines(way).valid.orR &&
                reference.io.raw.tags(way) === reference.io.lookupTag)
    }.reduce(_ && _)
    val stagedTrain = RegEnable(io.train.bits, io.train.valid)
    val stagedValid = RegNext(io.train.valid, false.B)
    val tagGroups = Reg(Vec(math.min(8, p.btbSets), UInt((32 - p.blockBits - log2Ceil(p.btbSets)).W)))
    val rowsPerGroup = p.btbSets / tagGroups.length
    val incomingIndex = io.train.bits.pc(p.blockBits + log2Ceil(p.btbSets) - 1, p.blockBits)
    for (group <- 0 until tagGroups.length) {
        when(io.train.valid && incomingIndex >= (group * rowsPerGroup).U &&
            incomingIndex < ((group + 1) * rowsPerGroup).U) {
            tagGroups(group) := io.train.bits.pc(31, p.blockBits + log2Ceil(p.btbSets))
        }
    }
    dut.io.train.valid := stagedValid
    reference.io.train.valid := stagedValid
    val pc = stagedTrain.pc
    for ((btbTrain, sets) <- Seq(dut.io.train -> p.btbSets, reference.io.train -> p.btbSets)) {
        val index = pc(p.blockBits + log2Ceil(sets) - 1, p.blockBits)
        btbTrain.bits.index := index
        btbTrain.bits.indexOH := UIntToOH(index, sets)
        btbTrain.bits.tag := pc(31, p.blockBits + log2Ceil(sets))
        btbTrain.bits.mask := stagedTrain.mask
        btbTrain.bits.cfi := VecInit((0 until p.fetchWidth).map(slot =>
            stagedTrain.mask(slot) && stagedTrain.kinds(slot) =/= 0.U
        )).asUInt
        btbTrain.bits.kinds := stagedTrain.kinds
        for (slot <- 0 until p.fetchWidth) {
            btbTrain.bits.targetWords(slot) := stagedTrain.targets(slot)(31, 2)
        }
        btbTrain.bits.backward := VecInit((0 until p.fetchWidth).map(slot =>
            FrontendCfi.conditional(stagedTrain.kinds(slot)) &&
                FrontendMath.backwardBranch(
                    FrontendMath.slotPc(pc, slot, p),
                    stagedTrain.targets(slot),
                )
        )).asUInt
    }
    dut.io.trainTagGroups := tagGroups
    reference.io.trainTagGroups.foreach(_ := reference.io.train.bits.tag)
    val expected = Reg(new FrontendBtbRaw(p, p.btbSets, p.btbWays))
    val expectedTag = RegEnable(io.query.bits(31, p.blockBits + log2Ceil(p.btbSets)), io.query.valid)
    when(io.query.valid) {
        expected := reference.io.raw
        when(dut.io.readSkipped.get) { expected.lines.foreach(_.valid := 0.U) }
    }
    io.readSkipped := dut.io.readSkipped.get
    val valid = RegInit(false.B)
    when(io.query.valid) { valid := true.B }
    io.equal := !valid || (0 until p.btbWays).map { w =>
        val result = dut.io.raw.lines(w)
        val old = expected.lines(w)
        val expectedHit = old.valid.orR && expected.tags(w) === expectedTag
        dut.io.hits(w) === expectedHit &&
        result.valid === old.valid && (!old.valid.orR || dut.io.raw.tags(w) === expected.tags(w)) &&
        (0 until p.fetchWidth).map(i =>
            !old.valid(i) || (
                result.kinds(i) === old.kinds(i) && result.targets(i) === old.targets(i)
            )
        ).reduce(_ && _)
    }.reduce(_ && _)
}

class MainBTBSpec extends AnyFreeSpec with ChiselSim {
    for (backend <- Seq(DualPortRamBackend.Vivado, DualPortRamBackend.BSG);
         (sets, ways, width) <- Seq((2, 1, 1), (8, 2, 4), (64, 2, 8))) {
        s"synchronous BTB matches the register reference through collisions and held responses ($backend/$sets/$ways/$width)" in {
            val p = FrontendParams(fetchWidth = width, btbSets = sets, btbWays = ways, observe = true)
            simulate(new MainBTBComparison(p, backend)) { d =>
                val random = new scala.util.Random(0x2026 + sets)
                val base = BigInt("80000000", 16)
                var collisions = 0
                var accepted = 0
                var query = base
                def pc(set: Int, tag: Int): BigInt = base + (tag * sets + set) * width * 4
                for (cycle <- 0 until 700) {
                    // Repeated same-row writes exercise partial updates, allocation and replacement.
                    val set = if (cycle < 80) 0 else random.nextInt(sets)
                    val tag = if (cycle < 40) 0 else random.nextInt(4)
                    val training = cycle < 120 || random.nextInt(4) != 0
                    d.io.train.valid.poke(training)
                    d.io.train.bits.pc.poke(pc(set, tag))
                    d.io.train.bits.mask.poke(1 + random.nextInt((1 << width) - 1))
                    for (i <- 0 until width) {
                        val kind = if (cycle < 40) 1 else random.nextInt(8)
                        d.io.train.bits.kinds(i).poke(kind)
                        d.io.train.bits.targets(i).poke((pc(set, tag) + i * 4 + (random.nextInt(65) - 32) * 4) &
                            BigInt("ffffffff", 16))
                    }
                    // Long stalls keep the old response live while training overwrites its RAM bank.
                    val requesting = cycle % 47 < 35
                    d.io.query.valid.poke(requesting)
                    d.io.query.bits.poke(query)
                    d.io.equal.expect(true)
                    d.io.parallelHitEqual.expect(true)
                    if (requesting) {
                        accepted += 1
                        query = pc(if (cycle < 80) 0 else random.nextInt(sets), random.nextInt(4))
                    }
                    if (d.io.readSkipped.peek().litToBoolean) collisions += 1
                    d.clock.step()
                    d.io.equal.expect(true)
                    d.io.parallelHitEqual.expect(true)
                }
                assert(collisions > 0 && accepted > 0)
            }
        }
    }
}
