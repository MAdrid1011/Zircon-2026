import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.Cache._
import ZirconConfig.DCacheParams

import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.util.Random

/** Observation only: these outputs never participate in stimulus or the data oracle. */
class DCacheStressTop(backend: DualPortRamBackend) extends DCache(backend, DCacheParams(64)) {
    val observation = IO(Output(new Bundle {
        val events = UInt(20.W)
        val lookup = UInt(4.W)
        val lookupValid = Bool()
        val install = UInt((l1Index + l1Way).W)
        val occupancy = UInt(4.W)
        val normalRead = UInt(2.W)
        val storeState = UInt(3.W)
    }))
    observation.events := VecInit(Seq(
        io.load(0).req.fire,
        io.load(1).req.fire,
        io.store.req.fire,
        storeArrayWrite || (missUnit.io.allocate.fire && missUnit.io.allocate.bits.store),
        storeLookupResult.hit.orR,
        tagTab.map(_.wea.orR).reduce(_ || _),
        missUnit.io.memory.rsp.fire,
        io.store.rsp.valid,
        io.load(0).rsp.valid,
        io.load(1).rsp.valid,
        io.store.rsp.valid && !io.store.rsp.ready,
        io.l2.req.valid && !io.l2.req.ready,
        lookupMove.asUInt.orR,
        VecInit((0 until 2).map(lane => lookupValid(lane) && !lookupFresh(lane))).asUInt.orR,
        io.flush,
        missUnit.io.busy,
        io.l2.req.fire && !io.l2.req.bits.write,
        io.l2.req.fire && io.l2.req.bits.write,
        io.forward(0).query.valid && io.forward(0).result.valid,
        io.forward(1).query.valid && io.forward(1).result.valid
    )).asUInt
    // SRAM read data becomes valid after the falling edge; classify at the actual capture edge.
    observation.lookup := RegEnable(
        Cat(
            hitNow(1).orR,
            hitNow(0).orR,
            lookupValid.asUInt
        ),
        lookupMove.asUInt.orR
    )
    observation.lookupValid := RegNext(lookupMove.asUInt.orR, false.B)
    observation.install := Cat(missUnit.io.install.bits.way, index(missUnit.io.install.bits.paddr))
    observation.occupancy := PopCount(requestBufferValid) +& PopCount(lookupValid) +& PopCount(executeValid) +&
        PopCount(responseValid)
    observation.normalRead := loadIssue.asUInt
    observation.storeState := storeState
}

class DCacheStressDriver(dut: DCacheStressTop, seed: Long) extends DCacheDriver(dut, seed) {
    val coverage = mutable.Map.empty[String, Long].withDefaultValue(0L)
    val history = mutable.Queue.empty[String]
    private var writeIssued = false
    private var cleanout = 0
    def count(name: String): Unit = coverage(name) += 1
    override def observeCycle(): Unit = {
        val events = dut.observation.events.peek().litValue.toInt
        def event(bit: Int): Boolean = (events & (1 << bit)) != 0
        val occupancy = dut.observation.occupancy.peek().litValue.toInt
        for (lane <- 0 until 2) {
            if (dut.observation.normalRead.peek().litValue.testBit(lane)) {
                count(s"fixed_port_read_$lane")
                if (!event(lane)) count(s"buffered_read_$lane")
            }
        }
        count(s"occupancy_$occupancy")
        if (event(0) && event(1)) count("dual_accept")
        if (event(2)) {
            count("store_accept")
            if (occupancy > 0) count("store_accept_with_loads")
            if (event(0) || event(1)) count("load_store_accept")
            writeIssued = false
            count(s"store_mask_${dut.io.store.req.bits.mask.peek().litValue}")
        }
        if (event(3)) count(if (event(4)) "store_hit" else "store_miss")
        if (event(5)) {
            val install = dut.observation.install.peek().litValue.toInt
            count(s"refill_set_${install & (l1IndexNum - 1)}")
            count(s"refill_way_${install >> l1Index}")
            count("refill")
        }
        if (dut.observation.lookupValid.peek().litToBoolean) {
            val lookup = dut.observation.lookup.peek().litValue.toInt
            if ((lookup & 3) == 3) count(s"dual_lookup_${(lookup >> 2) & 3}")
        }
        if (event(13)) count("lookup_hold")
        if (event(14)) {
            count("cancel")
            if (event(6)) count("cancel_read_response")
            if (event(7)) count("cancel_store_response")
            if (event(5)) count("cancel_install")
            if (event(8) || event(9)) count("cancel_wb")
        }
        for ((bit, name) <- Seq(8 -> "load0_wb", 9 -> "load1_wb", 10 -> "store_stall", 11 -> "lower_stall"))
            if (event(bit)) count(name)
        for (lane <- 0 until 2 if event(18 + lane)) {
            count(s"forward_mask_${dut.io.forward(lane).result.bits.mask.peek().litValue}")
        }
        if (event(17)) {
            val x = store.getOrElse(throw new AssertionError("Write without an accepted committed store"))
            assert(x.uncache, "Only uncached stores use the lower write operation")
            assert(!writeIssued, "Duplicate lower write for one committed store")
            dut.io.l2.req.bits.paddr.expect(x.address)
            dut.io.l2.req.bits.data.expect(x.data)
            dut.io.l2.req.bits.mask.expect(x.mask)
            dut.io.l2.req.bits.size.expect(x.size)
            dut.io.l2.req.bits.uncache.expect(x.uncache)
            writeIssued = true
        }
        if (dut.io.store.rsp.valid.peek().litToBoolean && storeReady) {
            assert(
                store.exists(x => !x.uncache || writeIssued || storeException(x) == 6),
                "Uncached store completed without a lower write"
            )
        }
        history.enqueue(
            s"cycle=$cycles events=$events occupancy=$occupancy storeState=${dut.observation.storeState.peek().litValue} " +
                s"idle=${dut.io.idle.peek().litToBoolean} pending=${expected.keys.toSeq.sorted} " +
                s"replay=${replay.map(_.size)} lower=$transaction"
        )
        if (history.size > 256) history.dequeue()
    }
    override def tick(inputs: Seq[Option[Load]], write: Option[Store], cancel: Option[Int]): Seq[Boolean] = {
        history.enqueue(
            s"drive cycle=$cycles loads=$inputs store=$write cancel=$cancel ready=$storeReady/$lowerReady"
        )
        if (history.size > 256) history.dequeue()
        val accepted = super.tick(inputs, write, cancel)
        for (lane <- 0 until 2 if accepted(lane)) {
            val x = inputs(lane).get
            count(s"load_set_${(x.address >> l1Offset) & (l1IndexNum - 1)}")
            count(s"load_type_${x.mtype}")
            count(s"pa_high_${x.address >> 32}")
        }
        accepted
    }
    def evictDirtyLines(base: Long): Unit = {
        for (set <- 0 until l1IndexNum; tag <- 0 until 3) {
            issue(Some(load(base + set * l1Line + tag * l1IndexNum * l1Line)))
        }
        drain()
    }
    def checkMemoryAfterWriteback(): Unit = {
        evictDirtyLines(0x300000000L + cleanout * 0x10000L)
        cleanout += 1
        reference.foreach { case (address, byte) =>
            assert(memory.getOrElse(address, initial(address)) == byte, f"Final memory mismatch at 0x$address%x")
        }
    }
}

class DCacheStressSpec extends AnyFreeSpec with ChiselSim {
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case s: svsim.verilator.Backend.CompilationSettings =>
            s.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case s => s
    }
    val line = l1Line
    val capacity = l1Way * l1IndexNum * line
    val patterns = Seq(
        "line" -> line,
        "quarter" -> (capacity / 4),
        "below" -> (capacity - line),
        "capacity" -> capacity,
        "above" -> (capacity + line),
        "twice" -> (capacity * 2),
        "large" -> (capacity * 8),
        "conflict2" -> 2,
        "conflict3" -> 3,
        "conflict8" -> 8,
        "pa34" -> capacity
    )
    val profiles = Seq("read", "mixed", "congested")
    val seeds = sys.env.getOrElse("ZIRCON_DCACHE_STRESS_SEEDS", "2024,2026").split(",").map(_.toLong).toSeq
    val duration = sys.env.getOrElse("ZIRCON_DCACHE_STRESS_CYCLES", "2048").toInt
    val output = Paths.get(sys.env.getOrElse("ZIRCON_DCACHE_STRESS_OUTPUT", "build/dcache-stress"))
    Files.createDirectories(output)
    def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    for (
        (backend, name) <- Seq(
            DualPortRamBackend.Registers -> "registers",
            DualPortRamBackend.Vivado -> "vivado",
            DualPortRamBackend.OpenRAM -> "openram"
        ) if sys.env.get("ZIRCON_DCACHE_STRESS_BACKEND").forall(_ == name)
    ) {
        s"$name: address-range and traffic matrix with independent byte oracle" in {
            simulate(new DCacheStressTop(backend)) { dut =>
                for (
                    ((pattern, span), pi) <- patterns.zipWithIndex; (profile, ti) <- profiles.zipWithIndex;
                    (seed, si) <- seeds.zipWithIndex
                ) {
                    val label = s"$pattern-$profile-$seed"
                    if (sys.env.get("ZIRCON_DCACHE_STRESS_CASE").forall(label.contains)) {
                        val base = 0x100000L + ((pi * profiles.size + ti) * seeds.size + si) * 0x10000L
                        val d = new DCacheStressDriver(dut, seed + pi * 997 + ti * 73)
                        import d._
                        // Each case uses disjoint physical lines; previous cases drain before the next starts.
                        val rng = new Random(seed + pi * 997 + ti * 73)
                        var pending = Seq.fill[Option[Load]](2)(None)
                        var pendingStore: Option[Store] = None
                        var serial = 0
                        var progress = 0
                        var previous = 0
                        def address(): Long = {
                            val low = if (pattern.startsWith("conflict"))
                                rng.nextInt(span) * l1IndexNum * line + rng.nextInt(line)
                            else rng.nextInt(span)
                            base + low + (if (pattern == "pa34") rng.nextInt(4).toLong << 32 else 0L)
                        }
                        def wordAddress(a: Long): Long = a & ~3L
                        def busyWords: Set[Long] = expected.values.map(e => wordAddress(e.request.address)).toSet ++
                            pending.flatten.map(x => wordAddress(x.address)) ++
                            replay.flatten.map(x => wordAddress(x.address))
                        def readyInputs(): Unit = {
                            val burst = cycles % 127
                            storeReady = profile == "read" || (burst >= 23 && rng.nextInt(3) != 0)
                            lowerReady = profile == "read" || (burst >= 11 && rng.nextInt(3) != 0)
                            latency = if (profile == "read") 1
                            else if (profile == "mixed") 2 + rng.nextInt(8) else 10 + rng.nextInt(30)
                        }
                        try {
                            // Reset only between drained cases so isolated replay has identical valid/LRU state.
                            dut.reset.poke(true.B)
                            dut.clock.step(2)
                            dut.reset.poke(false.B)
                            tick()
                            for (_ <- 0 until duration) {
                                readyInputs()
                                val cancel = if (profile != "read" && expected.nonEmpty && rng.nextInt(71) == 0)
                                    Some(0)
                                else None
                                if (cancel.nonEmpty) pending = Seq.fill(2)(None)
                                if (
                                    pendingStore.isEmpty && profile != "read" &&
                                    rng.nextInt(if (profile == "mixed") 4 else 2) == 0
                                ) {
                                    val addr = address() & ~3L
                                    if (!busyWords(wordAddress(addr))) {
                                        pendingStore =
                                            Some(Store(addr, BigInt(32, rng), 1 + rng.nextInt(15), id = serial & 255))
                                        serial += 1
                                    }
                                }
                                for (lane <- 0 until 2 if pending(lane).isEmpty) {
                                    val size = rng.nextInt(3)
                                    val addr = address() & ~((1L << size) - 1)
                                    if (!pendingStore.exists(w => wordAddress(w.address) == wordAddress(addr))) {
                                        val x = load(addr, size | (if (rng.nextBoolean() && size < 2) 4 else 0))
                                        val w = store.filter(w => wordAddress(w.address) == wordAddress(addr))
                                        forward(x.id) = Forward(
                                            w.map(_.data).getOrElse(BigInt(0)),
                                            w.map(_.mask).getOrElse(0),
                                            available = cycles + (if (profile == "congested") rng.nextInt(24) else 0)
                                        )
                                        pending = pending.updated(lane, Some(x))
                                    }
                                }
                                val accepted = tick(pending, pendingStore, cancel)
                                pending = pending.zip(accepted).map { case (x, fire) => if (fire) None else x }
                                if (accepted(2)) pendingStore = None
                                val now = loads + stores + cancelled + reads + writes
                                if (now != previous) { progress = cycles; previous = now }
                                assert(cycles - progress < 1000, s"No progress in $label")
                            }
                            storeReady = true
                            lowerReady = true
                            issue(pending(0), pending(1), pendingStore)
                            drain()
                            checkMemoryAfterWriteback()
                            assert(loads > 0 && reads > 0, s"No useful read traffic in $label")
                            if (profile != "read") assert(stores > 0 && coverage("store_accept_with_loads") > 0)
                            val fields = Seq(
                                "backend" -> quote(name),
                                "case" -> quote(label),
                                "seed" -> seed.toString,
                                "cycles" -> cycles.toString,
                                "loads" -> loads.toString,
                                "stores" -> stores.toString,
                                "cancelled" -> cancelled.toString,
                                "reads" -> reads.toString,
                                "writes" -> writes.toString,
                                "coverage" -> coverage.toSeq.sortBy(_._1).map { case (k, v) =>
                                    s"${quote(k)}:$v"
                                }.mkString("{", ",", "}")
                            )
                            Files.writeString(
                                output.resolve(s"$name-$label.json"),
                                fields.map { case (k, v) => s"${quote(k)}:$v" }.mkString("{", ",", "}\n")
                            )
                            info(s"$name $label: cycles=$cycles loads=$loads stores=$stores cancelled=$cancelled")
                        } catch {
                            case t: Throwable =>
                                Files.writeString(
                                    output.resolve(s"$name-$label-failure.txt"),
                                    s"$label $name duration=$duration\n${history.mkString("\n")}\n$t\n"
                                )
                                throw new AssertionError(
                                    s"DCache stress failed: $name $label; replay with ZIRCON_DCACHE_STRESS_CASE=$label",
                                    t
                                )
                        }
                    }
                }
            }
        }
    }
}
