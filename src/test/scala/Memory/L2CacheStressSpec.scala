import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class L2CacheStressSpec extends AnyFreeSpec with ChiselSim {
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case settings: svsim.verilator.Backend.CompilationSettings =>
            settings.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case settings => settings
    }

    for (
        (backend, name) <- Seq(
            DualPortRamBackend.Registers -> "registers",
            DualPortRamBackend.Vivado -> "vivado",
            DualPortRamBackend.BSG -> "bsg"
        )
    ) {
        s"$name: randomized 34-bit target, clean-victim and PTW traffic" in {
            val params = if (backend == DualPortRamBackend.BSG) {
                ZirconConfig.L2CacheParams(sets = 16)
            } else {
                ZirconConfig.L2CacheParams()
            }
            simulate(new L2Cache(backend, p = params)) { dut =>
                val d = new L2CacheDriver(dut)
                val random = new Random(20260916L)
                import d._
                tick()

                def cachedAddress(): Long = {
                    val high = random.nextInt(3).toLong << 32
                    val set = random.nextInt(params.sets).toLong << params.offsetBits
                    val tag = random.nextInt(32).toLong << (params.offsetBits + params.indexBits)
                    high | 0x08000000L | tag | set
                }

                for (iteration <- 0 until 400) {
                    val target = cachedAddress()
                    val victimAddress = Iterator.continually(cachedAddress()).find(_ != target).get
                    val victim = if (random.nextInt(4) != 0) Some((victimAddress, line(victimAddress))) else None
                    random.nextInt(5) match {
                        case 0 | 1 =>
                            val (data, error) = dcache(target, victim.map { case (a, bits) => (a, bits, false) })
                            assert(!error && data == line(target), s"DCache data mismatch at iteration $iteration")
                            assert(
                                !lastDcacheDirty,
                                s"clean-only test received dirty ownership at iteration $iteration"
                            )
                        case 2 | 3 =>
                            val (data, error) = icache(target, victim)
                            assert(!error && data == line(target), s"ICache data mismatch at iteration $iteration")
                        case _ =>
                            val wordAddress = target + random.nextInt(params.lineBytes / 4) * 4
                            val (data, error) = if (random.nextBoolean()) iptw(wordAddress) else dptw(wordAddress)
                            assert(
                                !error && data == (bytes(wordAddress, 4) & ~BigInt(0x300)),
                                s"PTW data mismatch at iteration $iteration"
                            )
                    }
                }

                for (word <- 0 until params.lineBytes / 4) {
                    val address = 0x380000000L + word * 4
                    val data = BigInt(32, random)
                    val (_, writeError) = dcache(address, uncache = true, write = true, data = data, mask = 15)
                    assert(!writeError)
                    val (readData, readError) = dcache(address, uncache = true)
                    assert(!readError && readData == data)
                }

                drain()
                info(s"$name: cycles=$cycle randomizedTransactions=464 lowerReads=$reads lowerWrites=$writes")
            }
        }
    }
}
