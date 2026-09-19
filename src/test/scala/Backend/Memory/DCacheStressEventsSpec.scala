import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.{Files, Paths}

class DCacheStressEventsSpec extends AnyFreeSpec with ChiselSim {
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case s: svsim.verilator.Backend.CompilationSettings =>
            s.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case s => s
    }
    val output = Paths.get(sys.env.getOrElse("ZIRCON_DCACHE_STRESS_OUTPUT", "build/dcache-stress"))
    Files.createDirectories(output)
    for (
        (backend, name) <- Seq(
            DualPortRamBackend.Registers -> "registers",
            DualPortRamBackend.Vivado -> "vivado",
            DualPortRamBackend.OpenRAM -> "openram"
        ) if sys.env.get("ZIRCON_DCACHE_STRESS_BACKEND").forall(_ == name)
    ) {
        s"$name: cancel, refill, committed write and fixed WB responses at adjacent edges" in {
            simulate(new DCacheStressTop(backend)) { dut =>
                val d = new DCacheStressDriver(dut, 20260912L)
                import d._
                try {
                    tick()
                    // The registered lane-1 input buffer accepts work while a committed store owns RAM B.
                    for (warm <- Seq(false, true); offered <- Seq(1, 2, 3)) {
                        val addr = 0x15000000L + (if (warm) 0x10000 else 0) + offered * 4096
                        if (warm) { issue(Some(load(addr)), Some(load(addr + 32))); drain() }
                        val a = load(addr)
                        val b = load(addr + 32)
                        val accepted = tick(
                            Seq(if ((offered & 1) != 0) Some(a) else None, if ((offered & 2) != 0) Some(b) else None),
                            write = Some(Store(addr + 32, BigInt("a15c817f", 16), id = offered))
                        )
                        assert(
                            accepted == Seq((offered & 1) != 0, (offered & 2) != 0, true),
                            s"Registered W1 arbitration: $accepted"
                        )
                        drain()
                        count("registered_w1_accept")
                    }
                    // Preserve either way's snapshot while its peer misses and a later store updates the array.
                    for (way <- 0 until 2; swap <- Seq(false, true)) {
                        val addr = 0x14000000L + (way * 2 + (if (swap) 1 else 0)) * 4096
                        issue(Some(load(addr)), Some(load(addr + 512)))
                        drain()
                        issue(write = Some(Store(addr + 12, BigInt("11223344", 16), id = 1)))
                        drain()
                        issue(write = Some(Store(addr + 524, BigInt("a1b2c3d4", 16), id = 2)))
                        drain()
                        val hit = load(addr + way * 512 + 12)
                        val miss = load(addr + 1024 + 20)
                        latency = 12
                        issue(Some(if (swap) hit else miss), Some(if (swap) miss else hit))
                        tick()
                        issue(write = Some(Store(hit.address, BigInt("5a817fc0", 16), mask = 5, id = 3)))
                        for (_ <- 0 until 10) tick()
                        drain()
                        issue(Some(load(hit.address)), Some(load(miss.address)))
                        drain()
                        count("late_way_snapshot")
                    }
                    // W3 acceptance is independent of the new addresses, including a same-row write.
                    for (warm <- Seq(false, true); offset <- Seq(0, 4, 32, 512); mask <- Seq(1, 5, 15)) {
                        val addr = 0x13000000L + (if (warm) 0x10000 else 0) + offset * 32 + mask * 1024
                        if (warm) { issue(Some(load(addr))); drain() }
                        issue(write = Some(Store(addr, BigInt("817fa05c", 16), mask, id = mask)))
                        // Present loads while the store hit writes RAM B. Both requests enter
                        // their input buffers and issue together after the cache-array update.
                        tick()
                        val beforeBuffered = coverage("buffered_read_1")
                        val accepted = tick(Seq(Some(load(addr + offset)), Some(load(addr))))
                        assert(accepted.take(2).forall(identity), s"W3 rejected loads: warm=$warm offset=$offset")
                        drain()
                        if (warm) {
                            assert(
                                coverage("buffered_read_1") > beforeBuffered,
                                "Deferred W3 lane-1 read was not retained"
                            )
                        }
                        count("w3_dual_deferred")
                    }
                    val cancelTimes = Seq(-1, 0, 1, 2, 3, 4, 5, 6, 12, 55, 56, 57, 58, 59, 60, 64, 68, 999)
                    for (
                        (delta, wi) <- Seq(-2, 0, 1, 4).zipWithIndex;
                        (cancelAt, ci) <- cancelTimes.zipWithIndex
                    ) {
                        val addr = 0x10000000L + (wi * cancelTimes.size + ci) * 512 + 8
                        latency = 20
                        storeReady = false
                        val x = load(addr)
                        val y = load(addr + 4)
                        val w = Store(addr, BigInt("9a81f07c", 16), mask = 5, id = ci + wi * cancelTimes.size)
                        // An older SQ store is known to forwarding before it reaches the committed write port.
                        forward(x.id) = Forward(w.data, w.mask)
                        issue(Some(x), Some(y))
                        var guard = 0
                        while (transaction.isEmpty && guard < 100) { tick(); guard += 1 }
                        assert(transaction.nonEmpty)
                        val due = transaction.get.due
                        var sent = false
                        var killed = false
                        while (cycles < due + 72) {
                            storeReady = cycles >= due + 55
                            val cancelNow = if (cancelAt == 999)
                                sent && dut.io.store.rsp.valid.peek().litToBoolean
                            else cycles == due + cancelAt
                            val fires = tick(
                                write = if (!sent && cycles >= due + delta) Some(w) else None,
                                cancel = if (cancelNow && !killed) Some(1) else None
                            )
                            if (fires(2)) sent = true
                            if (cancelNow) killed = true
                        }
                        assert(sent && killed, s"Event schedule missed delta=$delta cancel=$cancelAt")
                        storeReady = true
                        drain()
                        forward.remove(x.id)
                        issue(Some(load(addr)), Some(load(addr + 4)))
                        drain()
                        count("directed_event_case")
                    }
                    // Every word offset, both byte orders, and a later overlapping byte update.
                    for (word <- 0 until 8; reverse <- Seq(false, true)) {
                        val addr = 0x11000000L + word * 4 + (if (reverse) 512 else 0)
                        val order = if (reverse) (0 until 4).reverse else 0 until 4
                        for (byte <- order) {
                            issue(write = Some(Store(addr, BigInt(0x91 + byte) << (byte * 8), 1 << byte, id = byte)))
                        }
                        issue(write = Some(Store(addr, BigInt("5a00", 16), mask = 2, id = 9)))
                        issue(Some(load(addr)), Some(load(addr + 1, mtype = 4)))
                        drain()
                        count("four_byte_then_word")
                    }
                    // Delay every forwarding mask, then remove the producer after the WB snapshot was captured.
                    for (mask <- 0 until 16) {
                        val addr = 0x12000000L + mask * 32
                        val x = load(addr)
                        val data = BigInt("817fa05c", 16)
                        forward(x.id) = Forward(data, mask, available = cycles + 12)
                        issue(Some(x), Some(load(addr + 4)))
                        var guard = 0
                        while (!dut.io.load(0).rsp.valid.peek().litToBoolean && guard < 200) { tick(); guard += 1 }
                        assert(guard < 200)
                        forward.remove(x.id)
                        for (_ <- 0 until 8) tick()
                        drain()
                        if (mask != 0) issue(write = Some(Store(addr, data, mask, id = mask)))
                        drain()
                        count("delayed_mask_snapshot")
                    }
                    checkMemoryAfterWriteback()
                    val required = Seq(
                        "cancel_read_response",
                        "cancel_store_response",
                        "four_byte_then_word",
                        "w3_dual_deferred",
                        "late_way_snapshot",
                        "registered_w1_accept"
                    )
                    val missing = required.filter(coverage(_) == 0)
                    assert(missing.isEmpty, s"Uncovered events: ${missing.mkString(", ")}")
                    val cover =
                        coverage.toSeq.sortBy(_._1).map { case (key, n) => s"\"$key\":$n" }.mkString("{", ",", "}")
                    Files.writeString(
                        output.resolve(s"$name-events.json"),
                        s"""{"backend":"$name","case":"events","cycles":$cycles,"loads":$loads,"stores":$stores,"cancelled":$cancelled,"reads":$reads,"writes":$writes,"coverage":$cover}
"""
                    )
                    info(s"$name event matrix: cycles=$cycles loads=$loads stores=$stores cancelled=$cancelled")
                } catch {
                    case t: Throwable =>
                        Files.writeString(
                            output.resolve(s"$name-events-failure.txt"),
                            history.mkString("\n") + s"\n$t\n"
                        )
                        throw t
                }
            }
        }
    }
}
