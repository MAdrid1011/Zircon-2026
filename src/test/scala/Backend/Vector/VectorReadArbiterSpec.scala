import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class VectorReadArbiterHarness extends Module {
    val arbiter = Module(new VectorReadArbiter)
    val io = IO(chiselTypeOf(arbiter.io))
    io <> arbiter.io
}

class VectorReadArbiterSpec extends AnyFreeSpec with ChiselSim {
    "a full Store borrows only a declared unused arithmetic read port" in {
        simulate(new VectorReadArbiterHarness) { dut =>
            for (owner <- 0 until 3; port <- 0 until 3)
                dut.io.addresses(owner)(port).poke(8 * owner + port + 1)
            for (memory <- Seq(false, true); single <- Seq(false, true);
                 serial <- Seq(false, true); spare <- 0 until 4) {
                dut.io.memory.poke(memory); dut.io.memorySingle.poke(single)
                dut.io.serial.poke(serial); dut.io.spare.poke(spare)
                val share = memory && single && !serial && spare != 0
                val borrowed = if ((spare & 1) != 0) 1 else 2
                val owner = if (memory) 0 else if (serial) 1 else 2
                for (port <- 0 until 3) {
                    val address = if (share && port == borrowed) 1
                        else if (share) 17 + port else 8 * owner + port + 1
                    dut.io.selected(port).expect(address)
                }
                dut.io.memoryPort.expect(if (share) borrowed else 0)
                dut.io.computeAvailable.expect(!serial && (!memory || share))
                dut.clock.step()
            }
        }
    }
}
