import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class DCacheReplacementSpec extends AnyFreeSpec with ChiselSim {
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case s: svsim.verilator.Backend.CompilationSettings =>
            s.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case s => s
    }

    for (
        (backend, name) <- Seq(
            DualPortRamBackend.Registers -> "registers",
            DualPortRamBackend.Vivado -> "vivado",
            DualPortRamBackend.OpenRAM -> "openram"
        )
    ) {
        s"$name: write back dirty victims and write allocate store misses" in {
            simulate(new DCache(backend)) { dut =>
                val d = new DCacheDriver(dut)
                import d._
                // All five addresses map to set 2 and have distinct physical tags.
                val a = 0x20040L
                val b = a + 0x200
                val c = a + 0x400
                val e = a + 0x600
                val f = a + 0x800
                def readLine(address: Long): Unit = {
                    for (word <- 0 until 8 by 2) {
                        issue(Some(load(address + word * 4)), Some(load(address + (word + 1) * 4)))
                    }
                    drain()
                }
                tick()
                issue(Some(load(a)), Some(load(b)))
                drain()
                assert(reads == 2, "The two empty ways must each fetch one line")
                readLine(a)
                readLine(b)
                assert(reads == 2, "All eight words of each installed line must hit")

                // Touch A alone so that B is the next victim, then install C.
                issue(Some(load(a)))
                drain()
                issue(Some(load(c)))
                drain()
                assert(reads == 3)
                readLine(a)
                readLine(c)
                assert(reads == 3, "A and C must survive; B must be the evicted line")
                issue(Some(load(b)))
                drain()
                assert(reads == 4, "Accessing the evicted B must fetch it again")
                assert(writes == 0, "Clean eviction must not emit a lower write")

                // C remains resident. A cached store hit updates only L1 and marks the line dirty.
                issue(write = Some(Store(c + 12, BigInt("a1b2c3d4", 16), mask = 5, id = 42)))
                drain()
                assert(reads == 4 && writes == 0)
                readLine(c)
                assert(reads == 4, "The masked committed write must preserve the cached line")
                assert(bytes(memory, c + 12, 4) != bytes(reference, c + 12, 4))

                // Touch B to make C the victim. Acquiring E carries C's complete dirty line.
                issue(Some(load(b)))
                drain()
                issue(Some(load(e)))
                drain()
                assert(reads == 5 && writes == 1 && dirtyVictims == 1)
                assert(bytes(memory, c + 12, 4) == bytes(reference, c + 12, 4))
                issue(Some(load(b)))
                drain()
                assert(reads == 5, "The other way must remain resident")
                issue(Some(load(c + 12)))
                drain()
                assert(reads == 6, "The modified, evicted line must be fetched again")
                readLine(c)
                assert(reads == 6, "Refill must preserve the modified and all unmodified bytes")
                assert(writes == 1)

                // A store miss fetches, merges the committed bytes and installs a dirty line.
                issue(write = Some(Store(f, BigInt("88776655", 16), mask = 10, id = 43)))
                drain()
                assert(reads == 7 && writes == 1)
                issue(Some(load(f)))
                drain()
                assert(reads == 7, "Write allocation must make the following load hit")
                readLine(f)
                assert(reads == 7 && writes == 1)
                assert(cleanVictims > 0)
                info(
                    s"$name: seven acquisitions, one dirty writeback, clean victims=$cleanVictims; every returned byte checked"
                )
            }
        }
    }
}
