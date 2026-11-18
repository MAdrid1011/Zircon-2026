import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class CommitSideTableSpec extends AnyFreeSpec with ChiselSim {
    "replicas preserve synchronous reads and forward colliding writes" in {
        simulate(new CommitSideTable(depth = 32, width = 37, readPorts = 3)) { dut =>
            val random = new scala.util.Random(20261118)
            val memory = Array.fill(32)(BigInt(0))
            val mask = (BigInt(1) << 37) - 1

            dut.io.write.valid.poke(false)
            dut.io.write.bits.address.poke(0)
            dut.io.write.bits.data.poke(0)
            dut.io.readAddress.foreach(_.poke(0))
            dut.reset.poke(true)
            dut.clock.step()
            dut.reset.poke(false)

            for (address <- memory.indices) {
                val data = BigInt(37, random)
                dut.io.write.valid.poke(true)
                dut.io.write.bits.address.poke(address)
                dut.io.write.bits.data.poke(data)
                dut.io.readAddress(0).poke(address)
                dut.io.readAddress(1).poke(address)
                dut.io.readAddress(2).poke(address)
                dut.clock.step()
                memory(address) = data
                dut.io.readData.foreach(_.expect(data))
            }

            for (_ <- 0 until 500) {
                val addresses = Seq.fill(3)(random.nextInt(memory.length))
                val write = random.nextBoolean()
                val writeAddress = random.nextInt(memory.length)
                val writeData = BigInt(37, random) & mask
                dut.io.write.valid.poke(write)
                dut.io.write.bits.address.poke(writeAddress)
                dut.io.write.bits.data.poke(writeData)
                dut.io.readAddress.zip(addresses).foreach { case (port, address) => port.poke(address) }
                dut.clock.step()
                if (write) memory(writeAddress) = writeData
                dut.io.readData.zip(addresses).foreach { case (port, address) =>
                    port.expect(memory(address))
                }
            }
        }
    }
}
