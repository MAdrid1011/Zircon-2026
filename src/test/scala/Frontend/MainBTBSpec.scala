import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class MainBTBComparison(p: FrontendParams) extends Module {
    val io = IO(new Bundle {
        val query = Flipped(Valid(UInt(32.W)))
        val train = Flipped(Valid(new Bundle {
            val pc = UInt(32.W)
            val mask = UInt(p.fetchWidth.W)
            val kinds = Vec(p.fetchWidth, UInt(3.W))
            val targets = Vec(p.fetchWidth, UInt(32.W))
        }))
        val equal = Output(Bool())
        val readSkipped = Output(Bool())
    })
    val dut = Module(new MainBTB(p))
    val reference = Module(new BlockBTB(p, p.btbSets, p.btbWays))
    dut.io.query <> io.query
    reference.io.pc := io.query.bits
    val train = WireDefault(0.U.asTypeOf(new FrontendTraining(p)))
    train.pc := io.train.bits.pc
    train.mask := io.train.bits.mask
    train.kinds := io.train.bits.kinds
    train.targets := io.train.bits.targets
    dut.io.train.valid := io.train.valid
    reference.io.train.valid := io.train.valid
    dut.io.train.bits := train
    reference.io.train.bits := train
    val expected = Reg(new FrontendBtbRaw(p, p.btbSets, p.btbWays))
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
        result.valid === old.valid && (!old.valid.orR || dut.io.raw.tags(w) === expected.tags(w)) &&
        (0 until p.fetchWidth).map(i =>
            !old.valid(i) || (
                result.kinds(i) === old.kinds(i) && result.targets(i) === old.targets(i) &&
                    result.backward(i) === old.backward(i)
            )
        ).reduce(_ && _)
    }.reduce(_ && _)
}

class MainBTBSpec extends AnyFreeSpec with ChiselSim {
    for ((sets, ways, width) <- Seq((2, 1, 1), (8, 2, 4), (64, 2, 8))) {
        s"synchronous BTB matches the register reference through collisions and held responses ($sets/$ways/$width)" in {
            val p = FrontendParams(fetchWidth = width, btbSets = sets, btbWays = ways, observe = true)
            simulate(new MainBTBComparison(p)) { d =>
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
                    if (requesting) {
                        accepted += 1
                        query = pc(if (cycle < 80) 0 else random.nextInt(sets), random.nextInt(4))
                    }
                    if (d.io.readSkipped.peek().litToBoolean) collisions += 1
                    d.clock.step()
                    d.io.equal.expect(true)
                }
                assert(collisions > 0 && accepted > 0)
            }
        }
    }
}
