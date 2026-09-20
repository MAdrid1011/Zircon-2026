import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{FrontendParams, ICacheParams}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.util.Random

/** Coverage-driven stress of the standalone ICache protocol and physical-address contents. */
class ICacheStressSpec extends AnyFreeSpec with ChiselSim {
    import ICacheTestModel._

    private val currentFrontend = FrontendParams()
    private val currentICache = ICacheParams()
    private val configurations = Seq((currentFrontend.fetchWidth, currentICache.sets, currentICache.lineBytes))
    private val seeds =
        sys.env.get("ICACHE_STRESS_SEEDS").map(_.split(",").toSeq.map(java.lang.Long.decode(_).longValue))
            .getOrElse(Seq(20260912L, 0x13579bdfL, 0xdeadbeefL))
    private val randomCycles = sys.env.get("ICACHE_STRESS_CYCLES").map(_.toInt).getOrElse(65536)
    require(randomCycles >= 4096)

    // This untimed reference uses physical line identities and recency lists, not RTL way bits.
    private class CacheModel(sets: Int, lineBytes: Int) {
        val lines = Array.fill(sets)(mutable.ArrayBuffer.empty[BigInt])
        def access(pa: BigInt): Boolean = {
            val line = pa / lineBytes
            val recency = lines((line % sets).toInt)
            val hit = recency.contains(line)
            recency -= line
            recency += line
            if (recency.size > 2) recency.remove(0)
            hit
        }
    }

    private val selected = sys.env.get("ICACHE_STRESS_CONFIGS").map(_.split(",").toSet)
    for (
        (width, sets, lineBytes) <- configurations
        if selected.forall(_.contains(s"w${width}-s${sets}-l${lineBytes}"))
    ) {
        s"ICache stress covers geometry, aliases, cancellation and random traffic ($width/$sets/$lineBytes)" in {
            // The maximum legal line initializes 16384 bits, above Verilator's replication warning limit.
            implicit val backendSettingsModifications: svsim.BackendSettingsModifications = {
                case s: svsim.verilator.Backend.CompilationSettings if lineBytes > 1024 =>
                    s.withDisabledWarnings(s.disabledWarnings :+ "WIDTHCONCAT")
                case s => s
            }
            simulate(new ICache(FrontendParams(fetchWidth = width, observe = true), ICacheParams(sets, lineBytes))) {
                d =>
                    val e = new Environment(d, width, lineBytes)
                    val model = new CacheModel(sets, lineBytes)
                    val span = sets * lineBytes
                    val name = s"w${width}-s${sets}-l${lineBytes}"
                    e.context = s"$name directed"
                    e.lowerDelay = 1

                    def check(pa: BigInt, virtualPage: BigInt = BigInt("80000000", 16)): Unit = {
                        val pc = virtualPage | (pa & 4095)
                        val hit = model.access(pa)
                        val before = e.requests.size
                        val hits = d.io.dbg.get.hit.peek().litValue
                        val visits = d.io.dbg.get.visit.peek().litValue
                        e.fetch(pc, physical = Some(pa))
                        assert(e.requests.size == before + (if (hit) 0 else 1), s"$name: reference hit mismatch PA=$pa")
                        d.io.dbg.get.hit.expect(hits + (if (hit) 1 else 0))
                        d.io.dbg.get.visit.expect(visits + 1)
                        e.cover(if (hit) "reference_hit" else "reference_miss")
                    }

                    // Exercise both ways and LRU victims in every set, then every word in both resident lines.
                    for (set <- 0 until sets) {
                        val a = BigInt(set * lineBytes)
                        for (tag <- Seq(0, 1, 0, 2, 0, 1, 2, 1)) check(a + tag * span)
                        for (tag <- Seq(1, 2); offset <- 0 until lineBytes by 4) check(a + tag * span + offset)
                        e.cover("set_conflict_checked")
                        e.cover("set_all_words_checked")
                    }
                    assert(e.coverage("set_conflict_checked") == sets)
                    assert(e.coverage("set_all_words_checked") == sets)

                    // Toggle every implemented physical tag bit, including PA[33:32].
                    for (bit <- Integer.numberOfTrailingZeros(span) until 34) {
                        check(0)
                        check(BigInt(1) << bit)
                        check(0)
                        e.cover("tag_bit_checked")
                    }
                    for (pa <- Seq(BigInt(0), (BigInt(1) << 34) - 4, BigInt("7ffffffc", 16), BigInt("80000000", 16))) {
                        for (
                            va <- Seq(BigInt(0), BigInt("7ffff000", 16), BigInt("80000000", 16), BigInt("fffff000", 16))
                        ) {
                            check(pa, va)
                            e.cover("virtual_alias_checked")
                        }
                    }
                    // Identical VA and page offset with different physical pages must use the physical tag.
                    for (page <- Seq(0, 1, 2, 3, 0, 2, 1, 3)) check((BigInt(page) << 32) | 4092)

                    // Faults and uncached accesses must bypass even resident cache lines.
                    val base = BigInt("20000000", 16)
                    for (offset <- 0 until lineBytes by 4) {
                        val pc = base + offset
                        e.fetch(pc)
                        val beforeFault = e.requests.size
                        e.fetch(pc, fault = true)
                        for (misalignment <- 1 to 3) e.fetch(pc + misalignment)
                        assert(e.requests.size == beforeFault, "fault used lower memory on a resident line")
                        val words = width - (offset % (width * 4)) / 4
                        for (badSlot <- 0 until width) {
                            val beforeUncached = e.requests.size
                            e.fetch(pc, uncached = true, errors = BigInt(1) << badSlot)
                            assert(
                                e.requests.size == beforeUncached + words,
                                "uncached access skipped or repeated a word"
                            )
                            e.cover("uncached_start_error_slot_checked")
                        }
                        val beforeHit = e.requests.size
                        e.fetch(pc)
                        assert(e.requests.size == beforeHit, "uncached/faulting access disturbed a resident line")
                    }
                    // Lower errors must not install data or alter the selected victim.
                    val conflict = base + 8 * span
                    e.fetch(conflict)
                    e.fetch(conflict + span)
                    for (offset <- 0 until lineBytes by 4) {
                        val before = e.requests.size
                        e.fetch(conflict + 2 * span + offset, errors = 1)
                        assert(e.requests.size == before + 1)
                        e.fetch(conflict)
                        e.fetch(conflict + span)
                        assert(e.requests.size == before + 1, "failed fill evicted a valid line")
                    }
                    e.fetch(conflict + 2 * span)
                    val successfulRetry = e.requests.size
                    e.fetch(conflict + 2 * span)
                    assert(e.requests.size == successfulRetry)

                    // Sweep cancellation across each cycle of a cold cached/uncached transaction.
                    // Include consecutive flushes and immediate replay of the same physical line.
                    for (
                        uncached <- Seq(false, true); delay <- Seq(1, 9); offset <- 0 until (18 + width * (delay + 1))
                    ) {
                        val pc = BigInt("40000000", 16) + (e.serial * lineBytes)
                        e.lowerDelay = delay
                        val token = e.add(pc, uncached = uncached)
                        e.until(e.accepted.contains(token))
                        for (_ <- 0 until offset) e.step()
                        val beforeFlush = e.requests.size
                        val hadCompleted = e.completed.contains(token)
                        val installed = !uncached && e.returnedAt.get(token).exists(_ + 1 < e.cycle)
                        e.flush()
                        e.flush()
                        e.drain()
                        assert(e.completed.contains(token) == hadCompleted)
                        if (!uncached)
                            assert(e.requests.size == beforeFlush, "cancellation created an unpresented read")
                        val beforeReplay = e.requests.size
                        e.fetch(pc)
                        assert(
                            e.requests.size == beforeReplay + (if (installed) 0 else 1),
                            s"$name: wrong allocation after flush, uncached=$uncached delay=$delay offset=$offset"
                        )
                        e.cover(if (installed) "flush_kept_installed_line" else "flush_no_install")
                        e.cover(if (uncached) "uncached_flush_cycle_checked" else "cached_flush_cycle_checked")
                    }

                    // A lower valid blocked for a long time remains stable across repeated redirects.
                    e.lowerReady = false
                    val blocked = e.add(BigInt("60000000", 16), uncached = true)
                    e.until(d.io.l2.request.valid.peek().litToBoolean)
                    e.step()
                    for (i <- 0 until 257) e.step(flush = i % 7 == 0)
                    e.lowerReady = true
                    e.lowerDelay = 257
                    e.drain()
                    assert(!e.completed.contains(blocked))

                    // Keep a refill result blocked while a younger IF1 translation is already available.
                    e.outputReady = false
                    e.add(BigInt("61000000", 16))
                    e.add(BigInt("61001000", 16))
                    e.until(d.io.pp.response.valid.peek().litToBoolean)
                    val hits = d.io.dbg.get.hit.peek().litValue
                    val visits = d.io.dbg.get.visit.peek().litValue
                    for (_ <- 0 until 513) e.step()
                    d.io.dbg.get.hit.expect(hits)
                    d.io.dbg.get.visit.expect(visits)
                    e.outputReady = true
                    e.drain()

                    // Redirect on the same edge as a held hit, replacing both older pipeline tokens.
                    e.outputReady = false
                    val heldHit = e.add(BigInt("61001000", 16))
                    val younger = e.add(BigInt("61002000", 16), delay = 127)
                    e.until(d.io.pp.response.valid.peek().litToBoolean)
                    e.step()
                    assert(e.queued.isEmpty)
                    val redirect = e.add(BigInt("61001000", 16))
                    val redirectCycle = e.cycle
                    e.step(flush = true)
                    e.outputReady = true
                    e.drain()
                    assert(e.accepted(redirect) == redirectCycle && e.completed.contains(redirect))
                    assert(!e.completed.contains(heldHit) && !e.completed.contains(younger))

                    // Drain an old translation after a new redirect has already completed.
                    e.outOfOrderTranslations = true
                    val stale = e.add(BigInt("62000000", 16), delay = 511)
                    e.until(e.accepted.contains(stale))
                    e.flush()
                    e.lowerDelay = 1
                    val fresh = e.add(BigInt("62001000", 16))
                    e.until(e.completed.contains(fresh))
                    assert(
                        e.translations.exists(_._1.token == stale),
                        "stale translation did not overlap the new stream"
                    )
                    e.drain()

                    // Wrap the 32-bit transaction counter without reusing any live identity.
                    e.serial = (BigInt(1) << 32) - 2
                    for (_ <- 0 until 3) e.add(BigInt("62001000", 16))
                    e.drain()
                    assert(e.completed.contains(0) && e.completed.contains((BigInt(1) << 32) - 1))
                    e.serial = e.history.keys.filter(_ < (BigInt(1) << 31)).max + 1
                    e.cover("token_wrap_checked")

                    // Reset at a quiescent boundary invalidates all prior tags/data.
                    for (set <- 0 until sets; way <- 0 until 2)
                        e.fetch(BigInt("70000000", 16) + set * lineBytes + way * span)
                    d.reset.poke(true.B)
                    d.clock.step(3)
                    e.cycle += 3
                    d.reset.poke(false.B)
                    e.memorySalt = BigInt("f0a55a0f", 16)
                    val resetReads = e.requests.size
                    for (set <- 0 until sets; way <- 0 until 2)
                        e.fetch(BigInt("70000000", 16) + set * lineBytes + way * span)
                    assert(e.requests.size == resetReads + sets * 2, "reset preserved a valid line")
                    e.cover("warm_reset_checked")

                    val directedCycles = e.cycle
                    val runs = mutable.ArrayBuffer.empty[String]
                    for (seed <- seeds) {
                        e.context = s"$name seed=$seed"
                        val random = new Random(seed)
                        val before = e.coverage.toMap.withDefaultValue(0L)
                        val start = e.cycle
                        // Begin each seed with empty arrays, independently of the preceding random stream.
                        d.reset.poke(true.B)
                        d.clock.step(3)
                        e.cycle += 3
                        d.reset.poke(false.B)
                        val completed = e.completed.size
                        val canceled = e.canceled
                        val reads = e.requests.size
                        for (tick <- 0 until randomCycles) {
                            // Burst stalls cross miss/translation/output boundaries; occasional long returns model L2 misses.
                            e.lowerReady = tick % 1021 >= 73 && random.nextInt(4) != 0
                            e.outputReady = tick % 509 >= 47 && random.nextInt(3) != 0
                            e.lowerDelay = if (random.nextInt(80) == 0) 128 + random.nextInt(129)
                            else 1 + random.nextInt(20)
                            if (e.queued.size < 3 && random.nextInt(4) != 0) {
                                val phase = (tick / 1024) % 5
                                val offset = phase match {
                                    case 0 => random.nextInt(2 * width) * 4 // Hot working set.
                                    case 1 => random.nextInt(7) * span +
                                            random.nextInt(lineBytes / 4) * 4 // Same-set thrashing.
                                    case 2 => random.nextInt(8 * span / 4) * 4 // Capacity pressure.
                                    case 3 => (random.nextInt(3) + 1) * 4096 - 4 // Page/block/line ends.
                                    case _ => random.nextInt(65536) * 4 // Larger sparse address space.
                                }
                                val region = Seq(0L, 0x7ff00000L, 0x80000000L, 0xfff00000L)(random.nextInt(4))
                                val pc = BigInt(region + offset) +
                                    (if (random.nextInt(97) == 0) 1 + random.nextInt(3) else 0)
                                val pa = if (phase == 0) pc & 4095
                                else (BigInt(random.nextInt(4)) << 32) | (pc & BigInt("ffffffff", 16))
                                val token = e.add(
                                    pc,
                                    physical = Some(pa),
                                    uncached = random.nextInt(11) == 0,
                                    fault = random.nextInt(41) == 0,
                                    delay =
                                        if (random.nextInt(64) == 0) 128 + random.nextInt(129) else random.nextInt(13),
                                    errors =
                                        if (random.nextInt(17) == 0) BigInt(random.nextInt((1 << width) - 1) + 1) else 0
                                )
                                assert(token < (BigInt(1) << 32))
                            }
                            if (random.nextInt(173) == 0 || tick % 2048 == 100 || tick % 2048 == 101) e.flush()
                            else e.step()
                        }
                        e.lowerReady = true
                        e.outputReady = true
                        e.drain()
                        val required = Seq(
                            "cached_read",
                            "uncached_read",
                            "cached_error",
                            "uncached_error",
                            "output_backpressure",
                            "lower_backpressure",
                            "translation_backpressure",
                            "flush",
                            "canceled_read_drained",
                            "stale_translation_drained",
                            "translation_fault",
                            "misaligned_pc"
                        )
                        if (randomCycles >= 65536) required.foreach { event =>
                            assert(e.coverage(event) > before(event), s"${e.context}: random phase missed $event")
                        }
                        assert(e.completed.size > completed + 100, s"${e.context}: insufficient progress")
                        val metrics = Map(
                            "cycles" -> (e.cycle - start).toLong,
                            "completed" -> (e.completed.size - completed).toLong,
                            "canceled" -> (e.canceled - canceled).toLong,
                            "lower_reads" -> (e.requests.size - reads).toLong
                        )
                        val events = e.coverage.toSeq.sortBy(_._1).map { case (key, value) =>
                            s"\"$key\":${value - before(key)}"
                        }.mkString(",")
                        runs += s"{\"seed\":$seed,${metrics.toSeq.sortBy(_._1).map { case (key, value) =>
                                s"\"$key\":$value"
                            }.mkString(",")},\"events\":{$events}}"
                        info(s"$name seed=$seed cycles=${e.cycle - start} completed=${e.completed.size -
                                completed} canceled=${e.canceled - canceled} lowerReads=${e.requests.size - reads}")
                    }
                    e.requireCoverage(
                        "reference_hit",
                        "reference_miss",
                        "pa_high_0",
                        "pa_high_1",
                        "pa_high_2",
                        "pa_high_3",
                        "flush_lower_response",
                        "flush_held_lower",
                        "flush_held_output",
                        "redirect_accepted_with_flush",
                        "long_lower_delay",
                        "one_cycle_lower",
                        "token_wrap_checked"
                    )
                    val out = Paths.get(sys.env.getOrElse("ICACHE_STRESS_OUTPUT", "build/icache-stress/results"))
                    Files.createDirectories(out)
                    val events =
                        e.coverage.toSeq.sortBy(_._1).map { case (key, value) => s"\"$key\":$value" }.mkString(",")
                    val report =
                        s"{\"width\":$width,\"sets\":$sets,\"line_bytes\":$lineBytes,\"directed_cycles\":$directedCycles,\"cycles\":${e.cycle},\"completed\":${e.completed.size},\"canceled\":${e.canceled},\"lower_reads\":${e.requests.size},\"events\":{$events},\"random_runs\":[${runs.mkString(",")}]}\n"
                    Files.write(out.resolve(s"$name.json"), report.getBytes(StandardCharsets.UTF_8))
            }
        }
    }
}
