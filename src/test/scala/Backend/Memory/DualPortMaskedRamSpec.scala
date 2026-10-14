import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class DualPortRamHarness(lanes: Int, laneBits: Int, depth: Int = 16,
    backends: Seq[DualPortRamBackend] = Seq(DualPortRamBackend.Vivado, DualPortRamBackend.Registers)) extends Module {
    val io = IO(new Bundle {
        val address = Input(Vec(2, UInt(log2Ceil(depth).W)))
        val enable = Input(Vec(2, Bool()))
        val mask = Input(Vec(2, UInt(lanes.W)))
        val data = Input(Vec(2, UInt((lanes * laneBits).W)))
        val result = Output(Vec(backends.size, Vec(2, UInt((lanes * laneBits).W))))
    })
    backends.zipWithIndex.foreach { case (backend, i) =>
        val ram = Module(new DualPortMaskedRam(depth, lanes, laneBits, backend))
        ram.io.clka := clock
        ram.io.addra := io.address(0)
        ram.io.addrb := io.address(1)
        ram.io.ena := io.enable(0)
        ram.io.enb := io.enable(1)
        ram.io.wea := io.mask(0)
        ram.io.web := io.mask(1)
        ram.io.dina := io.data(0)
        ram.io.dinb := io.data(1)
        io.result(i)(0) := ram.io.douta
        io.result(i)(1) := ram.io.doutb
    }
}

class DualPortMaskedRamSpec extends AnyFreeSpec with ChiselSim {
    for ((lanes, bits) <- Seq((1, 25), (32, 8))) {
        s"128-row BSG $lanes x $bits Cache RAM matches shared register timing and byte masks" in {
            simulate(new DualPortRamHarness(lanes, bits, 128,
                Seq(DualPortRamBackend.BSG, DualPortRamBackend.Registers))) { dut =>
                val rng = new Random(20261013L + lanes)
                val memory = Array.fill(128)(BigInt(0))
                val held = Array(0, 0)
                def step(a: Int, b: Int, ena: Boolean, enb: Boolean, mask: BigInt, data: BigInt): Unit = {
                    dut.io.address(0).poke(a); dut.io.address(1).poke(b)
                    dut.io.enable(0).poke(ena); dut.io.enable(1).poke(enb)
                    dut.io.mask(0).poke(mask); dut.io.mask(1).poke(0)
                    dut.io.data(0).poke(data); dut.io.data(1).poke(0)
                    if (ena) {
                        held(0) = a
                        for (lane <- 0 until lanes if mask.testBit(lane)) {
                            val m = ((BigInt(1) << bits) - 1) << (lane * bits)
                            memory(a) = (memory(a) & ~m) | (data & m)
                        }
                    }
                    if (enb) held(1) = b
                    dut.clock.step()
                    for (backend <- 0 until 2; port <- 0 until 2)
                        dut.io.result(backend)(port).expect(memory(held(port)))
                }
                for (a <- 0 until 128) step(a, 0, true, true, (BigInt(1) << lanes) - 1, 0)
                for (_ <- 0 until 512)
                    step(rng.nextInt(128), rng.nextInt(128), rng.nextBoolean(), rng.nextBoolean(),
                        BigInt(lanes, rng), BigInt(lanes * bits, rng))
                step(127, 127, true, true, 0, 0)
                step(127, 0, true, false, (BigInt(1) << lanes) - 1, 1)
            }
        }
    }
    for ((lanes, laneBits) <- Seq((1, 23), (1, 25), (32, 8))) {
        s"$lanes lanes x $laneBits bits: Vivado and register models preserve the original address-register timing" in {
            simulate(new DualPortRamHarness(lanes, laneBits)) { dut =>
                val random = new Random(20260911L + lanes)
                val all = (BigInt(1) << lanes) - 1
                val laneMask = (BigInt(1) << laneBits) - 1
                val memory = Array.fill(16)(BigInt(0))
                val held = Array(0, 0)
                var cycles = 0
                def step(address: Seq[Int], enable: Seq[Boolean], mask: Seq[BigInt], data: Seq[BigInt]): Unit = {
                    for (p <- 0 until 2) {
                        dut.io.address(p).poke(address(p))
                        dut.io.enable(p).poke(enable(p))
                        dut.io.mask(p).poke(mask(p))
                        dut.io.data(p).poke(data(p))
                        if (enable(p)) {
                            held(p) = address(p)
                            for (lane <- 0 until lanes if mask(p).testBit(lane)) {
                                val bits = laneMask << (lane * laneBits)
                                memory(address(p)) = (memory(address(p)) & ~bits) | (data(p) & bits)
                            }
                        }
                    }
                    dut.clock.step()
                    for (backend <- 0 until 2; p <- 0 until 2)
                        dut.io.result(backend)(p).expect(memory(held(p)), s"cycle=$cycles backend=$backend port=$p")
                    cycles += 1
                }
                // Initialize through the two legal write ports; the register backend has no array reset.
                for (i <- 0 until 16 by 2)
                    step(Seq(i, i + 1), Seq(true, true), Seq(all, all), Seq.fill(2)(BigInt(lanes * laneBits, random)))
                for (_ <- 0 until 512) {
                    val a = random.nextInt(16)
                    var b = random.nextInt(16)
                    val mask = Seq.fill(2)(if (random.nextBoolean()) BigInt(0) else BigInt(lanes, random))
                    if (a == b && mask.exists(_ != 0)) b = (b + 1) % 16
                    step(
                        Seq(a, b),
                        Seq.fill(2)(random.nextInt(4) != 0),
                        mask,
                        Seq.fill(2)(BigInt(lanes * laneBits, random))
                    )
                }
                // A disabled output still observes writes to its held address in the 2024 template.
                step(Seq(3, 4), Seq(true, true), Seq(0, 0), Seq(0, 0))
                step(Seq(7, 3), Seq(false, true), Seq(0, all), Seq(0, 1))
                info(s"Validated $cycles cycles against a lane reference model and both RAM backends")
            }
        }
    }
}
