import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class DCacheNonBlockingSpec extends AnyFreeSpec with ChiselSim {
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
        s"$name: hits continue while one miss is outstanding and a second miss replays" in {
            simulate(new DCache(backend)) { dut =>
                val driver = new DCacheDriver(dut)
                import driver._

                tick()
                val warmAddress = 0x2020L
                issue(None, Some(load(warmAddress)))
                drain()

                latency = 80
                val firstMiss = load(0x4000L)
                issue(Some(firstMiss))
                var guard = 0
                while (transaction.isEmpty && guard < 20) {
                    tick()
                    guard += 1
                }
                assert(transaction.exists(!_.write), "first miss did not reach lower memory")

                var hitsUnderMiss = 0
                for (word <- 0 until 6) {
                    val hit = load(warmAddress + (word % 4) * 4)
                    issue(None, Some(hit))
                    guard = 0
                    while (expected.contains((1, hit.id)) && guard < 12) {
                        assert(transaction.nonEmpty, "lower miss completed before the warm hit")
                        tick()
                        guard += 1
                    }
                    assert(!expected.contains((1, hit.id)), "warm hit stalled behind the miss")
                    assert(transaction.nonEmpty, "warm hit did not complete under the miss")
                    hitsUnderMiss += 1
                }

                val secondMiss = load(0x6000L)
                issue(None, Some(secondMiss))
                guard = 0
                while (replay(1).isEmpty && guard < 12) {
                    assert(transaction.nonEmpty, "first miss completed before second-miss arbitration")
                    tick()
                    guard += 1
                }
                assert(replay(1).nonEmpty, "second miss did not produce a structural replay")
                assert(transaction.nonEmpty, "second miss waited for the MSHR instead of replaying")

                val writesBefore = writes
                val readsBefore = reads
                issue(write = Some(Store(0x9000L, BigInt("aabbccdd", 16))))
                assert(store.nonEmpty, "committed store was not buffered during the miss")
                assert(writes == writesBefore, "buffered store bypassed the outstanding miss")

                drain()
                assert(writes == writesBefore)
                assert(reads == readsBefore + 2, "replayed load miss and store miss must each acquire a line")
                val afterAllocate = reads
                issue(Some(load(0x9000L)))
                drain()
                assert(reads == afterAllocate, "load after store miss must hit the allocated line")
                assert(hitsUnderMiss == 6)
                info(s"$name: $hitsUnderMiss hits completed during an 80-cycle miss; second miss replayed")
            }
        }

        s"$name: store resolution retains both loads and issues both ports after the write" in {
            simulate(new DCacheStressTop(backend)) { dut =>
                var checkedResolve = false
                var checkedDeferredRead = false
                val driver = new DCacheDriver(dut) {
                    override def observeCycle(): Unit = {
                        val resolving = dut.observation.storeState.peek().litValue == 3
                        val presentingBothLoads = dut.io.load.map(_.req.valid.peek().litToBoolean).forall(identity)
                        if (resolving && presentingBothLoads) {
                            dut.observation.normalRead.expect(0)
                            checkedResolve = true
                        }
                        if (checkedResolve && dut.observation.normalRead.peek().litValue == 3) {
                            checkedDeferredRead = true
                        }
                    }
                }
                import driver._

                tick()
                issue(Some(load(0x2020L)), Some(load(0x2024L)))
                drain()
                val readsBefore = reads

                issue(write = Some(Store(0x2028L, BigInt("11223344", 16))))
                var guard = 0
                while (dut.observation.storeState.peek().litValue != 3 && guard < 8) {
                    tick()
                    guard += 1
                }
                assert(guard < 8, "store did not reach the registered resolve stage")

                val accepted = tick(Seq(Some(load(0x2020L)), Some(load(0x2024L))))
                assert(accepted.take(2).forall(identity), "store resolve backpressured a load request")
                assert(checkedResolve, "store resolve did not exercise the registered input buffers")
                drain()
                assert(checkedDeferredRead, "dual-load requests were not retained across the RAM B write")
                assert(reads == readsBefore, "loads overlapping a store hit missed in the cache")
            }
        }
    }
}
