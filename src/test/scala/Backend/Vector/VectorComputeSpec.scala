import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._
import scala.util.Random

class VectorComputeHarness extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new VectorInstruction))
        val initialize = new VectorRegisterWrite(VectorParams())
        val address = Input(UInt(6.W))
        val data = Output(Vec(8, UInt(32.W)))
        val scalarAddress = Output(UInt(7.W))
        val scalarData = Input(UInt(32.W))
        val roundingMode = Input(UInt(3.W))
        val writeback = Output(Valid(new VectorTaskWriteback))
        val completion = Decoupled(new VectorCompletion)
        val configuration = Output(Valid(new VectorCSRState))
        val flush = Input(Bool())
    })
    val compute = Module(new VectorCompute(shareSerialMultiply = true))
    compute.io.serialActive.get := false.B
    compute.io.serialMultiply.get.in.valid := false.B
    compute.io.serialMultiply.get.in.bits := 0.U.asTypeOf(compute.io.serialMultiply.get.in.bits)
    compute.io.serialMultiply.get.out.ready := true.B
    compute.io.serialMultiply.get.flush := io.flush
    val file = Module(new VectorRegisterFile)
    compute.io.in <> io.in
    compute.io.read <> file.io.read
    compute.io.scalarData := io.scalarData
    io.scalarAddress := compute.io.scalarAddress
    compute.io.roundingMode := io.roundingMode
    compute.io.readAvailable := true.B
    io.completion <> compute.io.completion
    io.configuration := compute.io.configuration
    compute.io.flush := io.flush
    io.writeback := compute.io.writeback
    file.io.write(0).valid := compute.io.writeback.valid
    file.io.write(0).address := compute.io.writeback.bits.address
    file.io.write(0).data := compute.io.writeback.bits.data
    file.io.write(0).bytes := compute.io.writeback.bits.bytes
    file.io.write(1).valid := io.initialize.valid
    file.io.write(1).address := io.initialize.address
    file.io.write(1).data := io.initialize.data
    file.io.write(1).bytes := io.initialize.bytes
    val observation = Module(new VectorRegisterFile)
    observation.io.write := file.io.write
    for (read <- observation.io.read) {
        read.address := io.address
        read.oneHot := UIntToOH(io.address, 64)
    }
    io.data := observation.io.read(0).data
}

class VectorComputeSpec extends AnyFreeSpec with ChiselSim {
    "eight-lane collection handles LMUL, narrow elements, mask/tail, overlap and WB stalls" in {
        simulate(new VectorComputeHarness) { dut =>
            VectorTestUtils.clear(dut.io.in.bits)
            VectorTestUtils.clear(dut.io.initialize)
            dut.io.in.valid.poke(false); dut.io.address.poke(0)
            dut.io.scalarData.poke(0); dut.io.roundingMode.poke(0)
            dut.io.completion.ready.poke(true); dut.io.flush.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val rng = new Random(20261013)
            val words = Array.fill(64, 8)(BigInt(32, rng))
            for (address <- 0 until 64) {
                dut.io.initialize.valid.poke(true); dut.io.initialize.address.poke(address)
                for (lane <- 0 until 8) {
                    dut.io.initialize.data(lane).poke(words(address)(lane))
                    dut.io.initialize.bytes(lane).poke(15)
                }
                dut.clock.step()
            }
            dut.io.initialize.valid.poke(false)
            var tasks = 0
            var cycles = 0
            def step(): Unit = {
                if (dut.io.writeback.valid.peek().litToBoolean) tasks += 1
                dut.clock.step(); cycles += 1
            }
            for (sew <- 0 to 2; count <- Seq(1, 2, 8); masked <- Seq(false, true);
                 policy <- Seq(0, 3)) {
                val bits = 8 << sew
                val perWord = 32 / bits
                val maximum = count * 8 * perWord
                val vl = maximum - 3
                val start = if (masked) 2 else 0
                val base = 32
                val oldBase = 16
                val expected = Array.tabulate(count, 8)((group, lane) => words(oldBase + group)(lane))
                val bitMask = (BigInt(1) << bits) - 1
                val mask = words(0).zipWithIndex.map { case (value, lane) => value << (lane * 32) }.reduce(_ | _)
                for (group <- 0 until count; lane <- 0 until 8; sub <- 0 until perWord) {
                    val index = (group * 8 + lane) * perWord + sub
                    val body = index >= start && index < vl
                    val enabled = !masked || mask.testBit(index)
                    val active = body && enabled
                    val agnostic = index >= start && (index >= vl && (policy & 1) != 0 ||
                        body && !enabled && (policy & 2) != 0)
                    if (active || agnostic) {
                        val a = (words(4 + group)(lane) >> (sub * bits)) & bitMask
                        val b = (words(8 + group)(lane) >> (sub * bits)) & bitMask
                        val value = if (agnostic) bitMask else (a + b) & bitMask
                        val positioned = bitMask << (sub * bits)
                        expected(group)(lane) = (expected(group)(lane) & ~positioned) | (value << (sub * bits))
                    }
                }
                val input = dut.io.in.bits
                VectorTestUtils.clear(input)
                input.scalar.robIdx.poke(9)
                input.scalar.inst.poke(if (masked) BigInt(0) else BigInt(1) << 25)
                input.vector.control.execution.poke(VectorExecution.ALU)
                input.vector.control.writesVector.poke(true)
                input.vector.control.operation.poke(EXEOp.ADD.litValue)
                input.vector.destination.count.poke(count)
                input.vector.state.vtype.poke((sew << 3) | ((if (count == 8) 3 else if (count == 2) 1 else 0)) | (policy << 6))
                input.vector.state.vl.poke(vl); input.vector.state.vstart.poke(start)
                input.vector.groups.mask.poke(masked); input.vector.mask.poke(0)
                for (source <- 0 until 3) input.vector.groups.sourceCount(source).poke(count)
                for (group <- 0 until 8) {
                    input.vector.source(0)(group).poke(4 + group)
                    input.vector.source(1)(group).poke(8 + group)
                    input.vector.source(2)(group).poke(oldBase + group)
                    input.vector.destination.physical(group).poke(base + group)
                }
                dut.io.in.valid.poke(true)
                while (!dut.io.in.ready.peek().litToBoolean) step()
                step(); dut.io.in.valid.poke(false)
                dut.io.completion.ready.poke(false)
                var waited = 0
                while (!dut.io.completion.valid.peek().litToBoolean && waited < 200) { step(); waited += 1 }
                assert(waited < 200)
                dut.io.completion.bits.robIdx.expect(9)
                step(); step() // Completion and last task must hold without duplicate writes.
                dut.io.completion.bits.robIdx.expect(9)
                dut.io.completion.ready.poke(true); step()
                for (group <- 0 until count) {
                    dut.io.address.poke(base + group)
                    for (lane <- 0 until 8) {
                        dut.io.data(lane).expect(expected(group)(lane))
                        words(base + group)(lane) = expected(group)(lane)
                    }
                }
            }
            // Destructive and accumulating forms share the same eight MUL
            // lanes, with modulo-SEW addition after the product register.
            for (sew <- 0 to 2; funct <- Seq(0x29, 0x2b, 0x2d, 0x2f); scalar <- Seq(false, true)) {
                val input = dut.io.in.bits
                VectorTestUtils.clear(input)
                val bits = 8 << sew; val m = (BigInt(1) << bits) - 1
                val scalarValue = BigInt(32, rng)
                input.scalar.inst.poke((BigInt(funct) << 26) | (BigInt(1) << 25) |
                    ((if (scalar) 6 else 2) << 12) | 0x57)
                input.scalar.robIdx.poke(11)
                input.vector.control.execution.poke(VectorExecution.Multiply)
                input.vector.control.operation.poke(16 | ((funct >> 1) & 1))
                input.vector.control.scalar.poke(scalar)
                input.vector.control.readsDestination.poke(true)
                input.vector.control.writesVector.poke(true)
                input.vector.destination.count.poke(2)
                input.vector.state.vtype.poke((sew << 3) | 1)
                input.vector.state.vl.poke(512 / bits)
                dut.io.scalarData.poke(scalarValue)
                for (source <- 0 until 3) input.vector.groups.sourceCount(source).poke(2)
                for (group <- 0 until 8) {
                    input.vector.source(0)(group).poke(4 + group)
                    input.vector.source(1)(group).poke(8 + group)
                    input.vector.source(2)(group).poke(16 + group)
                    input.vector.destination.physical(group).poke(32 + group)
                }
                input.vector.control.floating.poke(false)
                dut.io.in.valid.poke(true)
                while (!dut.io.in.ready.peek().litToBoolean) step()
                step(); dut.io.in.valid.poke(false)
                var waited = 0
                while (!dut.io.completion.valid.peek().litToBoolean && waited < 200) { step(); waited += 1 }
                assert(waited < 200)
                dut.io.completion.bits.robIdx.expect(11)
                dut.io.completion.ready.poke(false); step(); step()
                dut.io.completion.ready.poke(true); step()
                for (group <- 0 until 2; lane <- 0 until 8) {
                    var expected = BigInt(0)
                    for (sub <- 0 until 32 / bits) {
                        val a = (words(4 + group)(lane) >> (sub * bits)) & m
                        val b = if (scalar) scalarValue & m else (words(8 + group)(lane) >> (sub * bits)) & m
                        val c = (words(16 + group)(lane) >> (sub * bits)) & m
                        val product = b * (if ((funct & 4) == 0) c else a)
                        val addend = if ((funct & 4) == 0) a else c
                        expected |= ((if ((funct & 2) == 0) addend + product else addend - product) & m) << (sub * bits)
                    }
                    dut.io.address.poke(32 + group)
                    dut.io.data(lane).expect(expected, s"MAC SEW=$bits funct=$funct scalar=$scalar")
                }
            }
            info(s"Verified $tasks vector subelement tasks across $cycles cycles")
        }
    }

    "configuration captures both scalar operands, and flush cancels collected arithmetic" in {
        simulate(new VectorComputeHarness) { dut =>
            VectorTestUtils.clear(dut.io.initialize); VectorTestUtils.clear(dut.io.in.bits)
            dut.io.in.valid.poke(false); dut.io.address.poke(0); dut.io.roundingMode.poke(0)
            dut.io.scalarData.poke(0); dut.io.flush.poke(false); dut.io.completion.ready.poke(true)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            val input = dut.io.in.bits
            input.scalar.inst.poke((BigInt(0x40) << 25) | (2 << 20) | (1 << 15) | (7 << 12) | (3 << 7) | 0x57)
            input.scalar.prs(0).poke(5); input.scalar.prs(1).poke(6); input.scalar.prd.poke(7)
            input.scalar.robIdx.poke(4); input.vector.control.execution.poke(VectorExecution.Configuration)
            dut.io.in.valid.poke(true); dut.clock.step(); dut.io.in.valid.poke(false)
            var finished = false
            for (_ <- 0 until 20 if !finished) {
                dut.io.scalarData.poke(if (dut.io.scalarAddress.peek().litValue == 5) 17 else 0x11)
                if (dut.io.completion.valid.peek().litToBoolean) {
                    dut.io.completion.bits.scalar.valid.expect(true)
                    dut.io.completion.bits.scalar.bits.data.expect(16)
                    dut.io.configuration.valid.expect(true)
                    dut.io.configuration.bits.vl.expect(16)
                    dut.io.configuration.bits.vtype.expect(0x11)
                    finished = true
                }
                dut.clock.step()
            }
            assert(finished)
            VectorTestUtils.clear(input)
            input.vector.control.execution.poke(VectorExecution.ALU)
            input.vector.control.writesVector.poke(true)
            input.vector.destination.count.poke(1)
            input.vector.destination.physical(0).poke(40)
            input.vector.state.vtype.poke(0xd0); input.vector.state.vl.poke(8)
            dut.io.in.valid.poke(true); dut.clock.step(); dut.io.in.valid.poke(false)
            dut.clock.step(2); dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
            for (_ <- 0 until 10) {
                dut.io.completion.valid.expect(false); dut.io.writeback.valid.expect(false)
                dut.clock.step()
            }
        }
    }
}
