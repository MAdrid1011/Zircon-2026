import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

/** Architectural lane oracle, independent of the iterative implementation. */
class VectorShuffleSpec extends AnyFreeSpec with ChiselSim {
    "single-register SEW32 permutations preserve masks, tails, restart, stalls and flush" in {
        simulate(new VectorSerialHarness) { dut =>
            VectorTestUtils.clear(dut.io.in.bits)
            VectorTestUtils.clear(dut.io.initialize)
            dut.io.in.valid.poke(false)
            dut.io.address.poke(40)
            dut.io.scalarData.poke(0)
            dut.io.roundingMode.poke(0)
            dut.io.readAvailable.poke(true)
            dut.io.completion.ready.poke(false)
            dut.io.flush.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val rng = new Random(20261026)
            val wordMask = (BigInt(1) << 32) - 1
            var writes = 0
            def tick(): Unit = {
                if (dut.io.writes.peek().litToBoolean) writes += 1
                dut.clock.step()
            }
            def initialize(address: Int, data: Seq[BigInt]): Unit = {
                dut.io.initialize.address.poke(address)
                dut.io.initialize.valid.poke(true)
                for (lane <- 0 until 8) {
                    dut.io.initialize.data(lane).poke(data(lane))
                    dut.io.initialize.bytes(lane).poke(15)
                }
                tick(); dut.io.initialize.valid.poke(false)
            }
            val forms = Seq((0x0c, 0), (0x0c, 3), (0x0c, 4), (0x0e, 0),
                (0x0e, 3), (0x0e, 4), (0x0f, 3), (0x0f, 4),
                (0x0e, 5), (0x0e, 6), (0x0f, 5), (0x0f, 6),
                (0x17, 0), (0x17, 3), (0x17, 4), (0x17, 5))
            for (((funct, form), operation) <- forms.zipWithIndex; sample <- 0 until 40) {
                val gather16 = funct == 0x0e && form == 0
                val gather = funct == 0x0c || gather16
                val merge = funct == 0x17
                val one = !merge && !gather && (form == 5 || form == 6)
                val up = !gather && funct == 0x0e
                val fractional = sample % 3
                val lmul = if (fractional == 0) 0 else if (fractional == 1) 7 else 6
                val maximum = 8 >> fractional
                val vl = if (sample < 9) sample.min(maximum) else rng.nextInt(maximum + 1)
                val start = if (sample < 9) 0 else rng.nextInt(12)
                val policies = sample % 4
                val masked = merge || sample % 2 == 0
                val selected = BigInt(8, rng)
                val values = Seq.fill(8)(BigInt(32, rng))
                val old = Seq.fill(8)(BigInt(32, rng))
                val indices = Seq.tabulate(8)(i => BigInt((i * 3 + sample) % 13))
                val vs1 = if (gather16) (0 until 8).map(i =>
                    if (i < 4) indices(2*i) | (indices(2*i+1) << 16) else BigInt(0)) else indices
                val scalar = Seq(BigInt(0), BigInt(1), BigInt(7), BigInt(8),
                    BigInt("ffffffff", 16), BigInt("fffffffe", 16), BigInt(32, rng))(sample % 7)
                val immediate = sample % 32
                val immediateSigned = BigInt(if (immediate < 16) immediate else immediate - 32) & wordMask
                val distance = if (one) BigInt(1) else if (form == 3) BigInt(immediate) else scalar
                initialize(0, Seq(selected) ++ Seq.fill(7)(BigInt(0)))
                initialize(4, values); initialize(12, vs1); initialize(24, old)
                val in = dut.io.in.bits
                VectorTestUtils.clear(in)
                val inst = (BigInt(funct) << 26) | (if (!masked) BigInt(1) << 25 else BigInt(0)) |
                    (4 << 20) | ((if (form == 3) immediate else 8) << 15) |
                    (form << 12) | (16 << 7) | 0x57
                in.scalar.inst.poke(inst); in.scalar.robIdx.poke(11)
                in.vector.state.vtype.poke(16 | lmul | (policies << 6))
                in.vector.state.vl.poke(vl); in.vector.state.vstart.poke(start)
                in.vector.mask.poke(0)
                for (group <- 0 until 8) {
                    in.vector.source(0)(group).poke(4 + group)
                    in.vector.source(1)(group).poke(12 + group)
                    in.vector.source(2)(group).poke(24 + group)
                    in.vector.destination.physical(group).poke(40 + group)
                }
                dut.io.scalarData.poke(scalar)
                dut.io.in.valid.poke(true); dut.io.legal.expect(true)
                dut.io.in.ready.expect(true)
                val before = writes
                tick(); dut.io.in.valid.poke(false)
                var elapsed = 0
                while (!dut.io.completion.valid.peek().litToBoolean && elapsed < 30) {
                    dut.io.readAvailable.poke(sample % 5 != 0 || elapsed % 3 != 0)
                    tick(); elapsed += 1
                }
                assert(elapsed < 30, s"shuffle timed out operation=$operation")
                if (sample % 5 != 0) assert(elapsed <= 6, s"single register shuffle serialized: $elapsed")
                dut.io.readAvailable.poke(true)
                dut.io.completion.bits.robIdx.expect(11)
                dut.io.completion.bits.retirement.fflags.expect(0)
                assert(writes == before + 1)
                dut.io.address.poke(40)
                for (i <- 0 until 8) {
                    val body = i >= start && i < vl
                    val enabled = !masked || selected.testBit(i)
                    val active = body && (merge || enabled) && (!up || one || i >= distance)
                    val agnostic = start < vl && i >= start &&
                        (i >= vl && (policies & 1) != 0 || body && !enabled && !merge && (policies & 2) != 0)
                    val index = if (gather) {
                        if (form == 0) indices(i) else if (form == 3) BigInt(immediate) else scalar
                    } else if (up) BigInt(i) - distance else BigInt(i) + distance
                    val value = if (merge) {
                        val other = if (form == 0) vs1(i) else if (form == 3) immediateSigned else scalar
                        if (selected.testBit(i)) other else values(i)
                    } else if (one && (if (up) i == 0 else i + 1 == vl)) scalar
                    else if (index >= 0 && index < maximum) values(index.toInt) else BigInt(0)
                    dut.io.data(i).expect(if (active) value else if (agnostic) wordMask else old(i),
                        s"operation=$operation sample=$sample lane=$i vl=$vl start=$start")
                }
                for (_ <- 0 until 3) { dut.io.completion.valid.expect(true); tick() }
                assert(writes == before + 1, "completion backpressure repeated a register write")
                dut.io.completion.ready.poke(true); tick(); dut.io.completion.ready.poke(false)
            }
            // An instruction blocked on operand availability must relinquish
            // ownership on flush without writing a destination or completing.
            dut.io.readAvailable.poke(false)
            dut.io.in.valid.poke(true); tick(); dut.io.in.valid.poke(false)
            val beforeFlush = writes
            dut.clock.step(8)
            dut.io.flush.poke(true); tick(); dut.io.flush.poke(false)
            dut.io.readAvailable.poke(true)
            for (_ <- 0 until 10) {
                dut.io.writes.expect(false); dut.io.completion.valid.expect(false); tick()
            }
            assert(writes == beforeFlush)
            dut.io.in.ready.expect(true)
        }
    }
}
