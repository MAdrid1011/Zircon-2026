import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._
import scala.util.Random

class VectorSerialHarness extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorInstruction))
        val initialize = new VectorRegisterWrite(VectorParams())
        val address = Input(UInt(6.W))
        val data = Output(Vec(8, UInt(32.W)))
        val scalarData = Input(UInt(32.W))
        val roundingMode = Input(UInt(3.W))
        val readAvailable = Input(Bool())
        val completion = Decoupled(new VectorCompletion)
        val writes = Output(Bool())
        val legal = Output(Bool())
        val flush = Input(Bool())
    })
    val serial = Module(new VectorSerial)
    val file = Module(new VectorRegisterFile(observe = true))
    val decode = Module(new VectorDecoder)
    val groups = Module(new VectorRegisterGroups)
    decode.io.instruction := io.in.bits.scalar.inst
    groups.io.instruction := io.in.bits.scalar.inst
    groups.io.control := decode.io.control
    groups.io.vtype := io.in.bits.vector.state.vtype
    groups.io.vl := io.in.bits.vector.state.vl
    groups.io.vstart := io.in.bits.vector.state.vstart
    io.legal := decode.io.control.legal && groups.io.groups.legal
    serial.io.in.valid := io.in.valid
    serial.io.in.bits := io.in.bits
    serial.io.in.bits.vector.control := decode.io.control
    serial.io.in.bits.vector.groups := groups.io.groups
    serial.io.in.bits.vector.destination.count := groups.io.groups.destinationCount
    io.in.ready := serial.io.in.ready
    serial.io.read <> file.io.read
    serial.io.scalarData := io.scalarData
    serial.io.roundingMode := io.roundingMode
    serial.io.readAvailable := io.readAvailable
    io.completion <> serial.io.completion
    serial.io.flush := io.flush
    file.io.write(0).valid := serial.io.writeback.valid
    file.io.write(0).address := serial.io.writeback.bits.address
    file.io.write(0).data := serial.io.writeback.bits.data
    file.io.write(0).bytes := serial.io.writeback.bits.bytes
    file.io.write(1).valid := io.initialize.valid
    file.io.write(1).address := io.initialize.address
    file.io.write(1).data := io.initialize.data
    file.io.write(1).bytes := io.initialize.bytes
    io.data := file.io.observation.get(io.address).asTypeOf(Vec(8, UInt(32.W)))
    io.writes := serial.io.writeback.valid
}

class VectorSerialSpec extends AnyFreeSpec with ChiselSim {
    "shared divide/conversion and packed masks preserve element semantics, hold and flush" in {
        simulate(new VectorSerialHarness) { dut =>
            VectorTestUtils.clear(dut.io.in.bits); VectorTestUtils.clear(dut.io.initialize)
            dut.io.in.valid.poke(false); dut.io.address.poke(0); dut.io.scalarData.poke(0)
            dut.io.roundingMode.poke(0); dut.io.readAvailable.poke(true)
            dut.io.completion.ready.poke(true); dut.io.flush.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val rng = new Random(20261013)
            val wordMask = (BigInt(1) << 32) - 1
            var cycles = 0
            var writes = 0
            def tick(): Unit = {
                if (dut.io.writes.peek().litToBoolean) writes += 1
                dut.clock.step(); cycles += 1
            }
            def initialize(address: Int, values: Seq[BigInt]): Unit = {
                dut.io.initialize.address.poke(address); dut.io.initialize.valid.poke(true)
                for (lane <- 0 until 8) {
                    dut.io.initialize.data(lane).poke(values(lane) & wordMask)
                    dut.io.initialize.bytes(lane).poke(15)
                }
                tick(); dut.io.initialize.valid.poke(false)
            }
            def pack(values: Seq[BigInt], bits: Int): Seq[BigInt] = (0 until 8).map { lane =>
                (0 until 32 / bits).map { sub =>
                    (values(lane * (32 / bits) + sub) & ((BigInt(1) << bits) - 1)) << (sub * bits)
                }.reduce(_ | _)
            }
            def instruction(funct: Int, form: Int, unary: Int = 8, unmasked: Boolean = true): BigInt =
                (BigInt(funct) << 26) | (if (unmasked) BigInt(1) << 25 else BigInt(0)) |
                    (4 << 20) | (unary << 15) | (form << 12) | (16 << 7) | 0x57
            def run(inst: BigInt, sew: Int, count: Int = 1, vl: Int = -1, start: Int = 0,
                    scalar: BigInt = 0, rm: Int = 0, policies: Int = 0, vxrm: Int = 0,
                    expectedLegal: Boolean = true): Unit = {
                val input = dut.io.in.bits
                VectorTestUtils.clear(input)
                input.scalar.inst.poke(inst); input.scalar.robIdx.poke(11); input.scalar.prd.poke(13)
                input.vector.state.vtype.poke((sew << 3) | (if (count == 8) 3 else if (count == 4) 2 else if (count == 2) 1 else 0) | (policies << 6))
                input.vector.state.vl.poke(if (vl < 0) count * (32 >> sew) else vl)
                input.vector.state.vstart.poke(start); input.vector.mask.poke(0)
                input.vector.state.vxrm.poke(vxrm)
                for (group <- 0 until 8) {
                    input.vector.source(0)(group).poke(4 + group)
                    input.vector.source(1)(group).poke(12 + group)
                    input.vector.source(2)(group).poke(24 + group)
                    input.vector.destination.physical(group).poke(40 + group)
                }
                dut.io.scalarData.poke(scalar & wordMask); dut.io.roundingMode.poke(rm)
                dut.io.in.valid.poke(true); dut.io.legal.expect(expectedLegal.B,
                    s"instruction=${inst.toString(16)} SEW=$sew count=$count")
                assert(dut.io.in.ready.peek().litToBoolean)
                tick(); dut.io.in.valid.poke(false)
                var elapsed = 0
                while (!dut.io.completion.valid.peek().litToBoolean && elapsed < 12000) {
                    dut.io.readAvailable.poke(elapsed % 9 != 2)
                    tick(); elapsed += 1
                }
                dut.io.readAvailable.poke(true)
                assert(elapsed < 12000, s"timeout inst=${inst.toString(16)}")
                dut.io.completion.bits.robIdx.expect(11)
            }
            def finish(): Unit = {
                dut.io.completion.ready.poke(false)
                val before = writes
                for (_ <- 0 until 3) { dut.io.completion.valid.expect(true); tick() }
                assert(writes == before, "Held completion must not duplicate PRF writes")
                dut.io.completion.ready.poke(true); tick()
            }
            for (sew <- 0 to 2; funct <- 0x20 to 0x23; count <- Seq(1, 2)) {
                val bits = 8 << sew
                val m = (BigInt(1) << bits) - 1
                val values = count * (256 / bits)
                val a = Array.fill(values)(BigInt(bits, rng))
                val b = Array.fill(values)(BigInt(bits, rng))
                b(0) = 0; a(1) = BigInt(1) << (bits - 1); b(1) = m
                val signed = (funct & 1) != 0
                def integer(x: BigInt): BigInt = if (signed && x.testBit(bits - 1)) x - (BigInt(1) << bits) else x
                val expected = a.zip(b).map { case (x, y) =>
                    val ax = integer(x); val by = integer(y)
                    (if ((funct & 2) == 0) { if (by == 0) m else ax / by }
                        else { if (by == 0) x else ax % by }) & m
                }
                for (group <- 0 until count) {
                    initialize(4 + group, pack(a.slice(group * (256 / bits), (group + 1) * (256 / bits)), bits))
                    initialize(12 + group, pack(b.slice(group * (256 / bits), (group + 1) * (256 / bits)), bits))
                }
                run(instruction(funct, 2), sew, count)
                dut.io.completion.bits.retirement.fflags.expect(0)
                for (group <- 0 until count) {
                    dut.io.address.poke(40 + group)
                    val packed = pack(expected.slice(group * (256 / bits), (group + 1) * (256 / bits)), bits)
                    for (lane <- 0 until 8) dut.io.data(lane).expect(packed(lane))
                }
                finish()
            }
            // Pinned scalar IEEE oracle vectors are exercised through RVV decode,
            // collection, masked flag aggregation and byte-accurate PRF writes.
            val source = scala.io.Source.fromResource("fp-units-softfloat.txt")
            val conversions = try source.getLines().filter(s => s.nonEmpty && !s.startsWith("#"))
                .map(_.split(" ").map(BigInt(_, 16))).filter(_(0) >= 11).toVector finally source.close()
            for ((sample, index) <- conversions.zipWithIndex if index % 19 == 0) {
                val op = sample(0).toInt
                val unary = Map(11 -> 1, 12 -> 0, 13 -> 3, 14 -> 2)(op)
                initialize(4, Seq.fill(8)(sample(2)))
                run(instruction(0x12, 1, unary), 2, vl = 5, rm = sample(1).toInt, policies = 3)
                dut.io.completion.bits.retirement.fflags.expect(sample(5))
                dut.io.address.poke(40)
                for (lane <- 0 until 8) dut.io.data(lane).expect(if (lane < 5) sample(4) else wordMask)
                finish()
            }
            val widthSource = scala.io.Source.fromResource("fp-narrow-softfloat.txt")
            val widthConversions = try widthSource.getLines().map(_.split(" ").map(BigInt(_, 16)))
                .toVector finally widthSource.close()
            for ((sample, index) <- widthConversions.zipWithIndex if index % 5 == 0) {
                val unary = sample(0).toInt
                val wide = unary == 10 || unary == 11
                for (group <- 0 until 2)
                    initialize(4 + group, Seq.fill(8)(if (wide) (sample(2) & 65535) * 65537 else sample(2)))
                run(instruction(0x12, 1, unary), 1, vl = 13, start = index % 3,
                    rm = sample(1).toInt, policies = 3)
                dut.io.completion.bits.retirement.fflags.expect(sample(4))
                val bits = if (wide) 32 else 16
                for (group <- 0 until (if (wide) 2 else 1)) {
                    dut.io.address.poke(40 + group)
                    for (lane <- 0 until 8; sub <- 0 until 32 / bits) {
                        val element = group * (256 / bits) + lane * (32 / bits) + sub
                        if (element >= index % 3) {
                            val actual = (dut.io.data(lane).peek().litValue >> (bits * sub)) & ((BigInt(1) << bits) - 1)
                            val expected = if (element < 13) sample(3) else (BigInt(1) << bits) - 1
                            assert(actual == expected, s"width conversion unary=$unary rm=${sample(1)} element=$element")
                        }
                    }
                }
                finish()
            }
            for (sew <- 0 to 2; funct <- 0x18 to 0x1d; masked <- Seq(false, true)) {
                val bits = 8 << sew; val count = 2; val perReg = 256 / bits; val vl = 2 * perReg - 3
                val a = Array.fill(2 * perReg)(BigInt(bits, rng))
                val b = Array.fill(2 * perReg)(BigInt(bits, rng))
                val mask = BigInt(256, rng); val old = BigInt(256, rng)
                initialize(0, (0 until 8).map(i => (mask >> (32 * i)) & wordMask))
                initialize(24, (0 until 8).map(i => (old >> (32 * i)) & wordMask))
                for (group <- 0 until count) {
                    initialize(4 + group, pack(a.slice(group * perReg, (group + 1) * perReg), bits))
                    initialize(12 + group, pack(b.slice(group * perReg, (group + 1) * perReg), bits))
                }
                def signed(x: BigInt): BigInt = if (x.testBit(bits - 1)) x - (BigInt(1) << bits) else x
                val expected = (0 until 256).foldLeft(old) { (out, i) =>
                    val value = if (i < 2 || i < vl && masked && !mask.testBit(i)) out.testBit(i)
                    else if (i >= vl) true
                    else funct match {
                        case 0x18 => a(i) == b(i)
                        case 0x19 => a(i) != b(i)
                        case 0x1a => a(i) < b(i)
                        case 0x1b => signed(a(i)) < signed(b(i))
                        case 0x1c => a(i) <= b(i)
                        case 0x1d => signed(a(i)) <= signed(b(i))
                    }
                    if (value) out.setBit(i) else out.clearBit(i)
                }
                run(instruction(funct, 0, unmasked = !masked), sew, count, vl, start = 2)
                dut.io.address.poke(40)
                for (lane <- 0 until 8) dut.io.data(lane).expect((expected >> (32 * lane)) & wordMask)
                finish()
            }
            for (sew <- 0 to 2; kind <- Seq("sat", "average", "shift"); signed <- Seq(false, true);
                 subtract <- (if (kind == "shift") Seq(false) else Seq(false, true)); vxrm <- 0 until 4) {
                val bits = 8 << sew; val perReg = 256 / bits; val m = (BigInt(1) << bits) - 1
                val a = Array.fill(perReg)(BigInt(bits, rng)); val b = Array.fill(perReg)(BigInt(bits, rng))
                a(0) = m; b(0) = 1; a(1) = BigInt(1) << (bits - 1); b(1) = m
                a(2) = (BigInt(1) << (bits - 1)) - 1; b(2) = 1
                initialize(4, pack(a.toIndexedSeq, bits)); initialize(12, pack(b.toIndexedSeq, bits))
                def value(x: BigInt): BigInt = if (signed && x.testBit(bits - 1)) x - (BigInt(1) << bits) else x
                var expectedSat = false
                val expected = a.zip(b).map { case (ax, bx) =>
                    val x = value(ax); val y = value(bx)
                    if (kind == "sat") {
                        val full = if (subtract) x - y else x + y
                        val minimum = if (signed) -(BigInt(1) << (bits - 1)) else BigInt(0)
                        val maximum = if (signed) (BigInt(1) << (bits - 1)) - 1 else m
                        expectedSat ||= full < minimum || full > maximum
                        full.max(minimum).min(maximum) & m
                    } else {
                        val shift = if (kind == "shift") bx.toInt & (bits - 1) else 1
                        val raw = if (kind == "shift") x else if (subtract) x - y else x + y
                        val full = if (kind == "average" && !signed) raw & ((BigInt(1) << (bits + 1)) - 1) else raw
                        val base = full >> shift
                        val guard = shift > 0 && full.testBit(shift - 1)
                        val sticky = shift > 1 && (full & ((BigInt(1) << (shift - 1)) - 1)) != 0
                        val round = vxrm match {
                            case 0 => guard
                            case 1 => guard && (sticky || base.testBit(0))
                            case 2 => false
                            case 3 => !base.testBit(0) && (guard || sticky)
                        }
                        (base + (if (round) 1 else 0)) & m
                    }
                }
                val funct = (if (kind == "sat") 0x20 else if (kind == "average") 8 else 0x2a) |
                    (if (signed) 1 else 0) | (if (subtract) 2 else 0)
                run(instruction(funct, if (kind == "average") 2 else 0), sew, vxrm = vxrm)
                dut.io.completion.bits.retirement.saturated.expect(expectedSat)
                dut.io.address.poke(40)
                for (lane <- 0 until 8) dut.io.data(lane).expect(pack(expected.toIndexedSeq, bits)(lane),
                    s"$kind SEW=$bits signed=$signed subtract=$subtract vxrm=$vxrm lane=$lane")
                finish()
            }
            for (sew <- 0 to 2; funct <- 0 to 7; maskMode <- 0 to 2) {
                val masked = maskMode != 0
                val bits = 8 << sew; val perReg = 256 / bits; val m = (BigInt(1) << bits) - 1
                val values = Array.fill(2 * perReg)(BigInt(bits, rng))
                val mask = if (maskMode == 2) BigInt(0) else BigInt(256, rng)
                val old = BigInt(256, rng); val initial = BigInt(bits, rng)
                initialize(0, (0 until 8).map(i => (mask >> (32 * i)) & wordMask))
                initialize(24, (0 until 8).map(i => (old >> (32 * i)) & wordMask))
                initialize(12, Seq(initial) ++ Seq.fill(7)(BigInt(0)))
                for (group <- 0 until 2) initialize(4 + group,
                    pack(values.slice(group * perReg, (group + 1) * perReg).toIndexedSeq, bits))
                def signed(x: BigInt): BigInt = if (x.testBit(bits - 1)) x - (BigInt(1) << bits) else x
                val vl = values.length - 3
                val expected = (0 until vl).filter(i => !masked || mask.testBit(i)).foldLeft(initial) { (acc, i) =>
                    val a = values(i)
                    (funct match {
                        case 0 => acc + a
                        case 1 => acc & a
                        case 2 => acc | a
                        case 3 => acc ^ a
                        case 4 => acc.min(a)
                        case 5 => if (signed(acc) < signed(a)) acc else a
                        case 6 => acc.max(a)
                        case 7 => if (signed(acc) > signed(a)) acc else a
                    }) & m
                }
                run(instruction(funct, 2, unmasked = !masked), sew, 2, vl)
                dut.io.address.poke(40)
                val packed = (old & ~m) | expected
                for (lane <- 0 until 8) dut.io.data(lane).expect((packed >> (32 * lane)) & wordMask,
                    s"reduction SEW=$bits funct=$funct masked=$masked lane=$lane")
                finish()
            }
            for (funct <- Seq(1, 3, 5, 7); vl <- Seq(0, 7)) {
                val old = BigInt("deadbeef" * 8, 16)
                val seed = BigInt("192f37a0", 16)
                initialize(0, Seq.fill(8)(BigInt(0)))
                initialize(12, Seq(seed) ++ Seq.fill(7)(BigInt(0)))
                initialize(24, Seq.fill(8)(BigInt("deadbeef", 16)))
                run(instruction(funct, 1, unmasked = false), 2, vl = vl)
                dut.io.address.poke(40)
                val expected = if (vl == 0) old else (old & ~wordMask) | seed
                for (lane <- 0 until 8) dut.io.data(lane).expect((expected >> (32 * lane)) & wordMask)
                dut.io.completion.bits.retirement.fflags.expect(0)
                finish()
            }
            for (sew <- 0 to 2; count <- Seq(1, 2); vxrm <- 0 until 4) {
                val bits = 8 << sew; val perReg = 256 / bits; val m = (BigInt(1) << bits) - 1
                def signed(value: BigInt): BigInt = if (value.testBit(bits - 1)) value - (BigInt(1) << bits) else value
                val minimum = BigInt(1) << (bits - 1)
                val a = Array.fill(perReg * count)(BigInt(bits, rng)); val b = Array.fill(perReg * count)(BigInt(bits, rng))
                a(0) = minimum; b(0) = minimum
                for (group <- 0 until count) {
                    initialize(4 + group, pack(a.slice(group * perReg, (group + 1) * perReg).toSeq, bits))
                    initialize(12 + group, pack(b.slice(group * perReg, (group + 1) * perReg).toSeq, bits))
                }
                val expected = a.indices.map { index =>
                    if (a(index) == minimum && b(index) == minimum) minimum - 1
                    else {
                        val product = signed(a(index)) * signed(b(index)); val base = product >> (bits - 1)
                        val discarded = product & ((BigInt(1) << (bits - 1)) - 1)
                        val guard = product.testBit(bits - 2); val sticky = (discarded & ((BigInt(1) << (bits - 2)) - 1)) != 0
                        val increment = vxrm match {
                            case 0 => guard
                            case 1 => guard && (sticky || base.testBit(0))
                            case 2 => false
                            case 3 => !base.testBit(0) && discarded != 0
                        }
                        (base + (if (increment) 1 else 0)) & m
                    }
                }
                run(instruction(0x27, 0), sew, count, vxrm = vxrm)
                dut.io.completion.bits.retirement.saturated.expect(true)
                for (group <- 0 until count) {
                    dut.io.address.poke(40 + group)
                    val words = pack(expected.slice(group * perReg, (group + 1) * perReg), bits)
                    for (lane <- 0 until 8) dut.io.data(lane).expect(words(lane), s"vsmul SEW=$bits vxrm=$vxrm")
                }
                finish()
            }
            for (sew <- 0 to 1; signed <- Seq(false, true); masked <- Seq(false, true); count <- Seq(1, 2, 4, 8)) {
                val bits = 8 << sew; val wideBits = bits * 2; val m = (BigInt(1) << wideBits) - 1
                val values = Seq.fill(count * 256 / bits)(BigInt(bits, rng)); val seed = BigInt(wideBits, rng)
                val old = Seq.fill(256 / wideBits)(BigInt(wideBits, rng))
                val mask = BigInt(256, rng); initialize(0, pack((0 until 8).map(i => (mask >> (i * 32)) & wordMask), 32))
                for (group <- 0 until count) initialize(4 + group, pack(values.slice(group * (256 / bits), (group + 1) * (256 / bits)), bits))
                initialize(12, pack(Seq(seed) ++ Seq.fill(256 / wideBits - 1)(BigInt(0)), wideBits))
                initialize(24, pack(old, wideBits))
                val vl = values.size - 3
                val result = values.take(vl).zipWithIndex.filter { case (_, i) => !masked || mask.testBit(i) }
                    .foldLeft(seed) { case (sum, (value, _)) => sum +
                        (if (signed && value.testBit(bits - 1)) value - (BigInt(1) << bits) else value) } & m
                val inst = instruction(if (signed) 0x31 else 0x30, 0, unmasked = !masked)
                val aligned = if (count == 8) (inst & ~(BigInt(31) << 20)) | (8 << 20) else inst
                run(aligned, sew, count, vl)
                dut.io.address.poke(40)
                val expected = pack(Seq(result) ++ old.tail, wideBits)
                for (lane <- 0 until 8) dut.io.data(lane).expect(expected(lane), s"wide reduction SEW=$bits signed=$signed")
                finish()
            }
            for (sew <- 0 to 2) {
                val bits = 8 << sew; val perReg = 256 / bits
                val values = Seq.fill(perReg)(BigInt(bits, rng))
                val indices = Seq.tabulate(perReg)(i => BigInt(if (i % 7 == 0) perReg + 3 else (i * 13) % perReg))
                initialize(4, pack(values, bits))
                for (group <- indices.indices by 16) initialize(12 + group / 16,
                    pack(indices.slice(group, group + 16).padTo(16, BigInt(0)), 16))
                run(instruction(0x0e, 0), sew)
                dut.io.address.poke(40)
                val expected = pack(indices.map(index => if (index >= perReg) BigInt(0) else values(index.toInt)), bits)
                for (lane <- 0 until 8) dut.io.data(lane).expect(expected(lane), s"gatherei16 SEW=$bits")
                finish()
            }
            for (sew <- 0 to 2; count <- Seq(1, 2, 8); policy <- Seq(0, 3)) {
                val bits = 8 << sew; val perReg = 256 / bits
                val values = Seq.fill(count * perReg)(BigInt(bits, rng))
                val old = Seq.fill(count * perReg)(BigInt(bits, rng))
                val selected = BigInt(256, rng)
                for (group <- 0 until count) {
                    initialize(4 + group, pack(values.slice(group * perReg, (group + 1) * perReg), bits))
                    initialize(24 + group, pack(old.slice(group * perReg, (group + 1) * perReg), bits))
                }
                initialize(12, (0 until 8).map(i => (selected >> (32 * i)) & wordMask))
                val vl = values.size - 3
                val kept = values.take(vl).zipWithIndex.filter { case (_, i) => selected.testBit(i) }.map(_._1)
                val expected = kept ++ (if (policy == 0) old.drop(kept.size) else
                    Seq.fill(values.size - kept.size)((BigInt(1) << bits) - 1))
                val inst = if (count == 8) (instruction(0x17, 2) & ~(BigInt(31) << 20)) | (8 << 20)
                    else instruction(0x17, 2)
                run(inst, sew, count, vl, policies = policy)
                for (group <- 0 until count) {
                    dut.io.address.poke(40 + group)
                    val words = pack(expected.slice(group * perReg, (group + 1) * perReg), bits)
                    for (lane <- 0 until 8) dut.io.data(lane).expect(words(lane), s"compress SEW=$bits count=$count policy=$policy")
                }
                finish()
            }
            for (sew <- 0 to 2; funct <- Seq(0x0c, 0x0e, 0x0f); form <- Seq(0, 3, 4, 6)
                 if (funct != 0x0c || form != 6) && (form != 0 || funct == 0x0c)) {
                val bits = 8 << sew; val perReg = 256 / bits; val m = (BigInt(1) << bits) - 1
                val values = Seq.fill(2 * perReg)(BigInt(bits, rng))
                val old = Seq.fill(2 * perReg)(BigInt(bits, rng)); val offset = BigInt(7)
                val indices = values.indices.map(i => BigInt((i * 7 + 3) % (values.length + 5)))
                for (group <- 0 until 2) {
                    initialize(4 + group, pack(values.slice(group * perReg, (group + 1) * perReg), bits))
                    initialize(12 + group, pack(indices.slice(group * perReg, (group + 1) * perReg), bits))
                    initialize(24 + group, pack(old.slice(group * perReg, (group + 1) * perReg), bits))
                }
                val vl = values.length - 2
                val expected = values.indices.map { i =>
                    if (i >= vl) old(i)
                    else if (funct == 0x0c) {
                        val index = if (form == 0) indices(i).toInt else offset.toInt
                        if (index < values.length) values(index) else BigInt(0)
                    }
                    else if (form == 6) {
                        if (funct == 0x0e) { if (i == 0) offset & m else values(i - 1) }
                        else if (i + 1 == vl) offset & m else values(i + 1)
                    } else if (funct == 0x0e) { if (i < offset.toInt) old(i) else values(i - offset.toInt) }
                    else if (i + offset.toInt < values.length) values(i + offset.toInt) else BigInt(0)
                }
                run(instruction(funct, form, unary = if (form == 0) 8 else offset.toInt), sew, 2, vl, scalar = offset)
                for (group <- 0 until 2) {
                    dut.io.address.poke(40 + group)
                    val packed = pack(expected.slice(group * perReg, (group + 1) * perReg), bits)
                    for (lane <- 0 until 8) dut.io.data(lane).expect(packed(lane),
                        s"permute SEW=$bits funct=$funct form=$form group=$group lane=$lane")
                }
                finish()
            }
            for (sew <- 0 to 1; funct <- Seq(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
                 0x38, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f); form <- Seq(2, 6)
                 if funct != 0x3e || form == 6) {
                val bits = 8 << sew; val elements = 256 / bits; val wideBits = bits * 2
                val wideInput = funct >= 0x34 && funct <= 0x37
                val aBits = if (wideInput) wideBits else bits
                val a = Seq.fill(elements)(BigInt(aBits, rng)); val b = Seq.fill(elements)(BigInt(bits, rng))
                val old = Seq.fill(elements)(BigInt(wideBits, rng))
                for (group <- 0 until (if (wideInput) 2 else 1)) initialize(4 + group,
                    pack(a.slice(group * (256 / aBits), (group + 1) * (256 / aBits)), aBits))
                initialize(12, pack(b, bits))
                for (group <- 0 until 2) initialize(24 + group,
                    pack(old.slice(group * (256 / wideBits), (group + 1) * (256 / wideBits)), wideBits))
                def signed(x: BigInt, width: Int): BigInt = if (x.testBit(width - 1)) x - (BigInt(1) << width) else x
                // vs2 is unsigned for vwmaccsu, while vs1/rs1 is signed.
                // vwmaccus.vx reverses those operand types; vwmulsu uses signed vs2.
                val aSigned = Seq(0x31, 0x33, 0x35, 0x37, 0x3a, 0x3b, 0x3d, 0x3e).contains(funct)
                val bSigned = Seq(0x31, 0x33, 0x35, 0x37, 0x3b, 0x3d, 0x3f).contains(funct)
                val expected = a.indices.map { i =>
                    val x = if (aSigned) signed(a(i), aBits) else a(i)
                    val operand = b(if (form == 6) 0 else i)
                    val y = if (bSigned) signed(operand, bits) else operand
                    val value = if (funct >= 0x38) x * y + (if (funct >= 0x3c) old(i) else BigInt(0))
                        else if ((funct & 2) == 0) x + y else x - y
                    value & ((BigInt(1) << wideBits) - 1)
                }
                run(instruction(funct, form), sew, scalar = if (form == 6) b.head else BigInt(0))
                for (group <- 0 until 2) {
                    dut.io.address.poke(40 + group)
                    val packed = pack(expected.slice(group * (256 / wideBits), (group + 1) * (256 / wideBits)), wideBits)
                    for (lane <- 0 until 8) dut.io.data(lane).expect(packed(lane),
                        s"widen SEW=$bits funct=$funct form=$form group=$group lane=$lane")
                }
                finish()
            }
            for (sew <- 0 to 1; funct <- 0x2c to 0x2f; vxrm <- 0 until 4) {
                val bits = 8 << sew; val wideBits = bits * 2; val elements = 256 / bits
                val a = Seq.fill(elements)(BigInt(wideBits, rng)); val b = Seq.fill(elements)(BigInt(bits, rng))
                for (group <- 0 until 2) initialize(4 + group,
                    pack(a.slice(group * (256 / wideBits), (group + 1) * (256 / wideBits)), wideBits))
                initialize(12, pack(b, bits))
                var clipped = false
                val signed = (funct & 1) != 0; val clip = (funct & 2) != 0
                val m = (BigInt(1) << bits) - 1
                val expected = a.indices.map { i =>
                    val value = if (signed && a(i).testBit(wideBits - 1)) a(i) - (BigInt(1) << wideBits) else a(i)
                    val shift = b(i).toInt & (wideBits - 1); val base = value >> shift
                    val guard = shift > 0 && value.testBit(shift - 1)
                    val sticky = shift > 1 && (value & ((BigInt(1) << (shift - 1)) - 1)) != 0
                    val round = clip && (vxrm match {
                        case 0 => guard
                        case 1 => guard && (sticky || base.testBit(0))
                        case 2 => false
                        case 3 => !base.testBit(0) && (guard || sticky)
                    })
                    val rounded = base + (if (round) 1 else 0)
                    val minimum = if (signed) -(BigInt(1) << (bits - 1)) else BigInt(0)
                    val maximum = if (signed) (BigInt(1) << (bits - 1)) - 1 else m
                    clipped ||= clip && (rounded < minimum || rounded > maximum)
                    (if (clip) rounded.max(minimum).min(maximum) else rounded) & m
                }
                run(instruction(funct, 0), sew, vxrm = vxrm)
                dut.io.completion.bits.retirement.saturated.expect(clipped)
                dut.io.address.poke(40)
                for (lane <- 0 until 8) dut.io.data(lane).expect(pack(expected, bits)(lane),
                    s"narrow SEW=$bits funct=$funct vxrm=$vxrm lane=$lane")
                finish()
            }
            for (sew <- 1 to 2; distance <- 1 to sew; signed <- Seq(false, true)) {
                val bits = 8 << sew; val sourceBits = bits >> distance; val elements = 256 / bits
                val a = Seq.fill(256 / sourceBits)(BigInt(sourceBits, rng))
                initialize(4, pack(a, sourceBits))
                val expected = a.take(elements).map { x =>
                    val value = if (signed && x.testBit(sourceBits - 1)) x - (BigInt(1) << sourceBits) else x
                    value & ((BigInt(1) << bits) - 1)
                }
                val unary = (4 - distance) * 2 + (if (signed) 1 else 0)
                run(instruction(0x12, 2, unary), sew)
                dut.io.address.poke(40)
                for (lane <- 0 until 8) dut.io.data(lane).expect(pack(expected, bits)(lane))
                finish()
            }
            run(instruction(0x20, 2), 2)
            // Cancel an accepted instruction while the iterative unit owns a lane.
            finish()
            dut.io.in.valid.poke(true); tick(); dut.io.in.valid.poke(false)
            dut.clock.step(9); dut.io.flush.poke(true); tick(); dut.io.flush.poke(false)
            for (_ <- 0 until 60) {
                dut.io.completion.valid.expect(false); dut.io.writes.expect(false); tick()
            }
            info(s"$cycles cycles, $writes writes; RVV divide/conversion/mask and cancellation checks")
        }
    }
}
