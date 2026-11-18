import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class DualPortStorageHarness(
    depth: Int,
    lanes: Int,
    bits: Int,
    writePort: Int,
    fullWriteOnly: Boolean = false,
    writeCycleReadUnused: Boolean = false
) extends Module {
    val io = IO(new Bundle {
        val address = Input(Vec(2, UInt(log2Ceil(depth).W)))
        val writeAddress = Input(UInt(log2Ceil(depth).W))
        val enable = Input(Vec(2, Bool()))
        val dataIn = Input(Vec(2, UInt((lanes * bits).W)))
        val mask = Input(Vec(2, UInt(lanes.W)))
        val writeAll = Input(Vec(2, Bool()))
        val dataOut = Output(Vec(2, UInt((lanes * bits).W)))
    })
    val ram = Module(new DualPortMaskedRam(depth, lanes, bits,
        DualPortRamBackend.Registers, writePort, fullWriteOnly, writeCycleReadUnused))
    ram.io.clka := clock
    ram.io.addra := io.address(0)
    ram.io.addrb := io.address(1)
    ram.io.writeAddress := io.writeAddress
    ram.io.ena := io.enable(0)
    ram.io.enb := io.enable(1)
    ram.io.dina := io.dataIn(0)
    ram.io.dinb := io.dataIn(1)
    ram.io.wea := io.mask(0)
    ram.io.web := io.mask(1)
    ram.io.writeAllA := io.writeAll(0)
    ram.io.writeAllB := io.writeAll(1)
    io.dataOut(0) := ram.io.douta
    io.dataOut(1) := ram.io.doutb
}

class MaskedStorageSpec extends AnyFreeSpec with ChiselSim {
    for (initial <- Seq(false, true)) {
        s"Boolean metadata preserves reset and later-port write priority ($initial)" in {
            simulate(new AsyncRegRam(Bool(), 7, 2, 3, Some(initial.B))) { d =>
                val random = new scala.util.Random(20261026)
                val stored = Array.fill(7)(initial)
                for (port <- 0 until 2) {
                    d.io.wen(port).asInstanceOf[Bool].poke(false)
                    d.io.waddr(port).poke(0)
                    d.io.wdata(port).poke(false)
                }
                d.io.raddr.foreach(_.poke(0))
                d.reset.poke(true); d.clock.step(2); d.reset.poke(false)
                for (cycle <- 0 until 300) {
                    val address = Seq(random.nextInt(7), random.nextInt(7))
                    val enabled = Seq.fill(2)(random.nextBoolean())
                    val data = Seq.fill(2)(random.nextBoolean())
                    val reads = Seq(address(0), address(1), random.nextInt(7))
                    for (port <- 0 until 2) {
                        d.io.wen(port).asInstanceOf[Bool].poke(enabled(port))
                        d.io.waddr(port).poke(address(port))
                        d.io.wdata(port).poke(data(port))
                    }
                    for (port <- 0 until 3) {
                        d.io.raddr(port).poke(reads(port))
                        d.io.rdata(port).expect(stored(reads(port)))
                    }
                    // The reference applies writes in source-port order;
                    // the later enabled port owns a shared destination.
                    for (port <- 0 until 2 if enabled(port)) stored(address(port)) = data(port)
                    d.clock.step()
                    for (port <- 0 until 3) d.io.rdata(port).expect(stored(reads(port)))
                }
            }
        }
    }

    for ((depth, lanes, bits) <- Seq((8, 4, 8), (16, 8, 9)); writePort <- 0 until 2) {
        s"1RW1R storage preserves lane masks and live held outputs ($depth/$lanes/$bits, port $writePort)" in {
            simulate(new DualPortStorageHarness(depth, lanes, bits, writePort)) { d =>
                val random = new scala.util.Random(20261026 + lanes)
                val stored = Array.fill(depth)(BigInt(0))
                val laneMask = (BigInt(1) << bits) - 1
                val allLanes = (1 << lanes) - 1
                val held = Array(0, 0)
                for (port <- 0 until 2) {
                    d.io.enable(port).poke(true)
                    d.io.address(port).poke(0)
                    d.io.dataIn(port).poke(0)
                    d.io.mask(port).poke(0)
                    d.io.writeAll(port).poke(false)
                }
                d.io.writeAddress.poke(0)
                for (address <- 0 until depth) {
                    d.io.address(writePort).poke(address)
                    d.io.writeAddress.poke(address)
                    d.io.mask(writePort).poke(allLanes)
                    d.clock.step()
                    held(writePort) = address
                }
                for (cycle <- 0 until 400) {
                    val address = Seq(random.nextInt(depth), random.nextInt(depth))
                    val enabled = Seq.fill(2)(random.nextInt(4) != 0)
                    val selectedMask = random.nextInt(1 << lanes)
                    val masks = Seq.tabulate(2)(port => if (port == writePort) selectedMask else 0)
                    val data = Seq.fill(2)(BigInt(lanes * bits, random))
                    for (port <- 0 until 2) {
                        d.io.enable(port).poke(enabled(port))
                        d.io.address(port).poke(address(port))
                        d.io.dataIn(port).poke(data(port))
                        d.io.mask(port).poke(masks(port))
                        d.io.dataOut(port).expect(stored(held(port)))
                    }
                    d.io.writeAddress.poke(address(writePort))
                    for (port <- 0 until 2 if enabled(port)) {
                        for (lane <- 0 until lanes if (masks(port) & (1 << lane)) != 0) {
                            val selected = laneMask << (lane * bits)
                            stored(address(port)) = (stored(address(port)) & ~selected) | (data(port) & selected)
                        }
                        held(port) = address(port)
                    }
                    d.clock.step()
                    // A disabled port holds its selected address; another
                    // port's write to that word is still visible after the edge.
                    for (port <- 0 until 2) d.io.dataOut(port).expect(stored(held(port)))
                }
            }
        }
    }

    for (writePort <- 0 until 2) {
        s"whole-word writes preserve the 1RW1R read-address contract (port $writePort)" in {
            simulate(new DualPortStorageHarness(8, 8, 8, writePort, fullWriteOnly = true)) { d =>
                for (port <- 0 until 2) {
                    d.io.enable(port).poke(true)
                    d.io.address(port).poke(0)
                    d.io.dataIn(port).poke(0)
                    d.io.mask(port).poke(0)
                    d.io.writeAll(port).poke(false)
                }
                d.io.writeAddress.poke(0)
                for (address <- 0 until 8) {
                    d.io.address(writePort).poke(address)
                    d.io.writeAddress.poke(address)
                    d.io.writeAll(writePort).poke(true)
                    d.io.dataIn(writePort).poke(BigInt("1122334455667788", 16) ^ address)
                    d.clock.step()
                }
                d.io.writeAll(writePort).poke(false)
                for (address <- 0 until 8) {
                    val readPort = 1 - writePort
                    d.io.address(readPort).poke(address)
                    d.clock.step()
                    d.io.dataOut(readPort).expect(BigInt("1122334455667788", 16) ^ address)
                }

                val readPort = 1 - writePort
                d.io.writeAll(writePort).poke(false)
                d.io.address(writePort).poke(3)
                d.io.address(readPort).poke(5)
                d.io.enable(writePort).poke(true)
                d.io.enable(readPort).poke(true)
                d.clock.step()

                val collisionData = BigInt("8877665544332211", 16)
                d.io.address(writePort).poke(5)
                d.io.writeAddress.poke(5)
                d.io.enable(readPort).poke(false)
                d.io.writeAll(writePort).poke(true)
                d.io.dataIn(writePort).poke(collisionData)
                d.clock.step()
                d.io.dataOut(writePort).expect(collisionData)
                d.io.dataOut(readPort).expect(collisionData)
            }
        }
    }

    for (writePort <- 0 until 2) {
        s"write-cycle-unused contract preserves ordinary reads (port $writePort)" in {
            simulate(new DualPortStorageHarness(
                8, 4, 8, writePort, writeCycleReadUnused = true)) { d =>
                val readPort = 1 - writePort
                for (port <- 0 until 2) {
                    d.io.enable(port).poke(true)
                    d.io.address(port).poke(0)
                    d.io.dataIn(port).poke(0)
                    d.io.mask(port).poke(0)
                    d.io.writeAll(port).poke(false)
                }
                d.io.writeAddress.poke(0)
                for (address <- 0 until 8) {
                    d.io.address(writePort).poke(address)
                    d.io.writeAddress.poke(address)
                    d.io.mask(writePort).poke(15)
                    d.io.dataIn(writePort).poke(BigInt("10203040", 16) + address)
                    d.clock.step()
                }
                d.io.mask(writePort).poke(0)
                for (address <- 0 until 8) {
                    d.io.address(readPort).poke(address)
                    d.clock.step()
                    d.io.dataOut(readPort).expect(BigInt("10203040", 16) + address)
                }

                d.io.address(writePort).poke(2)
                d.io.enable(writePort).poke(true)
                d.clock.step()
                d.io.dataOut(writePort).expect(BigInt("10203042", 16))
                d.io.writeAddress.poke(5)
                d.io.mask(writePort).poke(15)
                d.io.dataIn(writePort).poke(BigInt("a0b0c0d0", 16))
                d.clock.step()
                d.io.dataOut(writePort).expect(BigInt("10203042", 16))
                d.io.mask(writePort).poke(0)
                d.io.address(writePort).poke(5)
                d.clock.step()
                d.io.dataOut(writePort).expect(BigInt("a0b0c0d0", 16))
            }
        }
    }
}
