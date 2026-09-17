import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class SinglePortMaskedRamTest(depth: Int, lanes: Int, laneBits: Int) extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val write = Input(Bool())
        val address = Input(UInt(math.max(1, log2Ceil(depth)).W))
        val dataIn = Input(UInt((lanes * laneBits).W))
        val mask = Input(UInt(lanes.W))
        val dataOut = Output(UInt((lanes * laneBits).W))
    })
    val ram = Module(new SinglePortMaskedRam(depth, lanes, laneBits))
    ram.io.clock := clock
    ram.io.enable := io.enable
    ram.io.write := io.write
    ram.io.address := io.address
    ram.io.dataIn := io.dataIn
    ram.io.mask := io.mask
    io.dataOut := ram.io.dataOut
}

class SinglePortMaskedRamSpec extends AnyFreeSpec with ChiselSim {
    for ((depth, lanes, bits) <- Seq((1, 1, 1), (8, 3, 9), (32, 8, 34))) {
        s"Xilinx RAM preserves registered addressing, masks and disabled writes ($depth/$lanes/$bits)" in {
            simulate(new SinglePortMaskedRamTest(depth, lanes, bits)) { d =>
                val random = new scala.util.Random(2024 + depth)
                val memory = Array.fill(depth)(BigInt(0))
                val laneMask = (BigInt(1) << bits) - 1
                var address = 0
                var valid = false
                for (cycle <- 0 until 300) {
                    val next = random.nextInt(depth)
                    val enable = cycle == 0 || random.nextInt(3) != 0
                    val write = cycle > 0 && random.nextBoolean()
                    val mask = random.nextInt(1 << lanes)
                    val data = BigInt(lanes * bits, random)
                    d.io.enable.poke(enable)
                    d.io.write.poke(write)
                    d.io.address.poke(next)
                    d.io.dataIn.poke(data)
                    d.io.mask.poke(mask)
                    // Changing the input address alone must not select another word.
                    if (valid) d.io.dataOut.expect(memory(address))
                    if (enable) {
                        if (write) {
                            for (i <- 0 until lanes if (mask & (1 << i)) != 0) {
                                val selected = laneMask << (i * bits)
                                memory(next) = (memory(next) & ~selected) | (data & selected)
                            }
                        }
                        address = next
                        valid = true
                    }
                    d.clock.step()
                    // The legacy registered-address template exposes newly written lanes after the edge.
                    d.io.dataOut.expect(memory(address))
                }
            }
        }
    }
}
