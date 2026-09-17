import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.util.Random

/** Commit-owned SQ/SB model; the DUT contains neither queue. */
class LoadStorePipelineDriver(dut: LoadPipelineSystem) extends LoadPipelineDriver(dut) {
    case class Store(
        seq: Int,
        base: Long,
        imm: Long,
        prj: Int,
        prs: Int,
        value: BigInt,
        size: Int,
        exception: Int,
        uncache: Boolean,
        var addrDone: Boolean = false,
        var dataDone: Boolean = false,
        var sent: Boolean = false
    ) {
        val sq: Int = seq & 255
        val rob: Int = (seq * 31) & 255
        val address: Long = (base + imm) & 0xffffffffL
        val mask: Int = (((1 << (1 << size)) - 1) << (address & 3).toInt) & 15
        val alignedData: BigInt = (value << (8 * (address & 3).toInt)) & BigInt("ffffffff", 16)
        val fault: Int = if (exception != 0) exception else if ((address & ((1 << size) - 1)) != 0) 6 else 0
    }
    val sq = mutable.ArrayBuffer.empty[Store]
    val sb = mutable.Queue.empty[Store]
    val pendingSTA = mutable.ArrayBuffer.empty[Store]
    val pendingSTD = mutable.ArrayBuffer.empty[Store]
    var sta: Option[Store] = None
    var std: Option[Store] = None
    var staReady = true
    var stdReady = true
    var fpProducer: Option[(Int, BigInt)] = None
    var staFired = false
    var stdFired = false
    var staCount = 0
    var stdCount = 0
    var committed = 0
    var storeResponses = 0
    var serialStore = 0
    private var heldSTA: Option[Seq[BigInt]] = None
    private var heldSTD: Option[Seq[BigInt]] = None

    def allocate(
        base: Long = 0x1000,
        imm: Long = 0,
        prj: Int = 1,
        prs: Int = 2,
        value: BigInt = BigInt("89abcdef", 16),
        size: Int = 2,
        exception: Int = 0,
        uncache: Boolean = false
    ): Store = {
        val s = Store(serialStore, base, imm, prj, prs, value, size, exception, uncache)
        serialStore += 1
        assert(!sq.exists(_.sq == s.sq))
        sq += s
        s
    }
    def writeSource(s: Store): Unit = {
        if ((s.prs & (1 << dut.p.physWidth)) != 0) {
            fpProducer = Some((s.prs & ((1 << dut.p.physWidth) - 1)) -> s.value)
            tick(); fpProducer = None
        } else if (s.prs != 0) writeRegister(s.prs, s.value.toLong)
    }
    def issueSTA(s: Store): Unit = {
        sta = Some(s)
        var guard = 0
        do { tick(); guard += 1 } while (!staFired && guard < 1000)
        assert(staFired); sta = None
    }
    def issueSTD(s: Store): Unit = {
        std = Some(s)
        var guard = 0
        do { tick(); guard += 1 } while (!stdFired && guard < 1000)
        assert(stdFired); std = None
    }
    def collect(): Unit = {
        var guard = 0
        while ((pendingSTA.nonEmpty || pendingSTD.nonEmpty) && guard < 1000) { tick(); guard += 1 }
        assert(guard < 1000)
    }
    def commit(s: Store): Unit = {
        assert(sq.head == s && s.addrDone && s.dataDone && s.fault == 0)
        sq.remove(0); sb.enqueue(s); committed += 1
    }
    def drainStores(): Unit = {
        var guard = 0
        while (sb.nonEmpty && guard < 3000) { tick(); guard += 1 }
        assert(sb.isEmpty)
        drain()
    }
    def forwarding(address: Long, boundary: Int, committedOnly: Boolean = false): Forward = {
        val entries = if (committedOnly) sb.toSeq else sq.filter(_.seq < boundary).toSeq
        var data = BigInt(0)
        var mask = 0
        var blocked = false
        // Entries are visited oldest to youngest, so the youngest older store wins each byte.
        for (s <- entries) {
            if (!s.addrDone) blocked = true
            else if (s.fault == 0 && (s.address & ~3L) == (address & ~3L)) {
                if (!s.dataDone) blocked = true
                else for (b <- 0 until 4 if (s.mask & (1 << b)) != 0) {
                    mask |= 1 << b
                    data = (data & ~(BigInt(255) << (8 * b))) | (s.alignedData & (BigInt(255) << (8 * b)))
                }
            }
        }
        Forward(data, mask, blocked)
    }
    override def beforeCycle(input: Option[Load], cancel: Option[Int]): Unit = {
        cancel.foreach { _ =>
            pendingSTA.clear(); pendingSTD.clear(); sq.clear()
            heldSTA = None
            heldSTD = None
        }
        assert(sta.isEmpty || input.isEmpty, "LD and STA share one address issue port")
        sta.foreach { s =>
            val b = dut.io.iq.instPkg.bits
            dut.io.iq.instPkg.valid.poke(true)
            b.store.poke(true); b.sqIdx.poke(s.sq)
            b.prj.poke(s.prj); b.imm.poke(BigInt(s.imm & 0xffffffffL)); b.mtype.poke(s.size)
            b.robIdx.poke(s.rob); b.rdVld.poke(false)
            b.exception.valid.poke(s.exception != 0)
            b.exception.cause.poke(s.exception)
            b.exception.tval.poke(0)
            b.uncache.poke(s.uncache)
            // Stores remain speculative even if this load-only authorization bit is accidentally set.
            b.ioAuthorized.poke(true)
        }
        val s = dut.io.iq.std.get
        s.valid.poke(std.nonEmpty)
        std.foreach { x =>
            s.bits.prs(0).poke(x.prs); s.bits.sqIdx.poke(x.sq); s.bits.robIdx.poke(x.rob); s.bits.size.poke(x.size)
        }
        dut.io.cmt.sq.addr.get.ready.poke(staReady)
        dut.io.cmt.sq.data.get.ready.poke(stdReady)
        fpProducer.foreach { case (r, v) =>
            dut.io.fpWrite(0).we.poke(true); dut.io.fpWrite(0).addr.poke(r); dut.io.fpWrite(0).data.poke(v)
        }
        dut.io.store.req.valid.poke(sb.headOption.exists(!_.sent))
        sb.headOption.foreach { x =>
            val b = dut.io.store.req.bits
            b.paddr.poke(if (x.uncache) x.address else x.address & ~3L)
            b.data.poke(x.alignedData); b.mask.poke(x.mask)
            b.size.poke(if (x.uncache) x.size else 2); b.uncache.poke(x.uncache)
        }
    }
    override def afterCycle(input: Option[Load], cancel: Option[Int]): Unit = {
        val a = dut.io.cmt.sq.addr.get
        val d = dut.io.cmt.sq.data.get
        stable(heldSTA, a.valid.peek().litToBoolean, fields(a.bits), "STA")
        stable(heldSTD, d.valid.peek().litToBoolean, fields(d.bits), "STD")
        heldSTA = if (a.valid.peek().litToBoolean && !staReady) Some(fields(a.bits)) else None
        heldSTD = if (d.valid.peek().litToBoolean && !stdReady) Some(fields(d.bits)) else None
        if (a.valid.peek().litToBoolean && staReady) {
            val s = pendingSTA.remove(0)
            a.bits.sqIdx.expect(s.sq); a.bits.robIdx.expect(s.rob);
            a.bits.vaddr.expect(s.address); a.bits.paddr.expect(s.address)
            a.bits.mask.expect(s.mask); a.bits.size.expect(s.size)
            a.bits.exception.expect(s.fault); a.bits.uncache.expect(s.uncache)
            assert(!s.addrDone); s.addrDone = true; staCount += 1
            count(s"sta_size_${s.size}")
        }
        if (d.valid.peek().litToBoolean && stdReady) {
            val s = pendingSTD.remove(0)
            d.bits.sqIdx.expect(s.sq); d.bits.robIdx.expect(s.rob);
            d.bits.data.expect(s.value); d.bits.size.expect(s.size)
            assert(!s.dataDone); s.dataDone = true; stdCount += 1
            count(if ((s.prs & (1 << dut.p.physWidth)) != 0) "std_fp" else "std_int")
        }
        if (a.valid.peek().litToBoolean && staReady && d.valid.peek().litToBoolean && stdReady)
            count("sta_std_parallel")
        if (
            dut.io.request.valid.peek().litToBoolean && dut.io.requestReady.peek().litToBoolean &&
            d.valid.peek().litToBoolean && stdReady
        ) count("ld_std_parallel")
        staFired = sta.nonEmpty && dut.io.iq.instPkg.ready.peek().litToBoolean
        stdFired = std.nonEmpty && dut.io.iq.std.get.ready.peek().litToBoolean
        if (staFired) { assert(sq.contains(sta.get)); pendingSTA += sta.get }
        if (stdFired) { assert(sq.contains(std.get)); pendingSTD += std.get }
        if (dut.io.store.req.valid.peek().litToBoolean && dut.io.store.req.ready.peek().litToBoolean) {
            assert(!sb.head.sent); sb.head.sent = true
        }
        if (dut.io.store.rsp.valid.peek().litToBoolean) {
            assert(sb.head.sent)
            val completed = sb.head
            for (b <- 0 until 4 if (completed.mask & (1 << b)) != 0) {
                memory((completed.address & ~3L) + b) =
                    ((completed.alignedData >> (8 * b)) & 255).toInt
            }
            sb.dequeue()
            storeResponses += 1
        }
        fpProducer.foreach { case (r, v) => fpValues(r) = v }
    }
}

class LoadStorePipelineSpec extends AnyFreeSpec with ChiselSim {
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
        if sys.env.get("ZIRCON_LOAD_BACKEND").forall(_ == name)
    ) {
        s"$name: independent STA/STD, Commit SQ/SB model, forwarding and single committed drain" in {
            simulate(new LoadStorePipelineSystem(backend)) { dut =>
                val d = new LoadStorePipelineDriver(dut)
                import d._
                try {
                    tick(); writeRegister(1, 0x1000); writeRegister(2, 0x89abcdefL)
                    // Both halves can arrive first, or in the same cycle, with one address event per store.
                    val first = allocate()
                    issueSTA(first); collect(); assert(!first.dataDone)
                    issueSTD(first); collect(); count("sta_first")
                    val second = allocate(imm = 4)
                    issueSTD(second); collect(); assert(!second.addrDone)
                    issueSTA(second); collect(); count("std_first")
                    val third = allocate(imm = 8)
                    sta = Some(third); std = Some(third); tick(); assert(staFired && stdFired)
                    sta = None; std = None; collect()
                    assert(storeWrites == 0 && reads == 0, "Speculative stores accessed DCache/L2")
                    commit(first); commit(second); commit(third); drainStores()
                    assert(storeResponses == 3 && storeWrites == 0); count("commit_once")

                    // First stalled RF cycle sees producer bypass; later writes must not alter either half.
                    val held = allocate(base = 0x2000, value = BigInt("7f800001", 16), prs = fp(0))
                    staReady = false; stdReady = false
                    sta = Some(held); std = Some(held); tick(); sta = None; std = None
                    producer = Some(1 -> BigInt(0x2000)); fpProducer = Some(0 -> held.value); tick()
                    producer = Some(1 -> BigInt(0x3000)); fpProducer = Some(0 -> BigInt(0)); tick()
                    producer = None; fpProducer = None
                    for (_ <- 0 until 7) tick()
                    staReady = true; stdReady = true; collect()
                    commit(held); drainStores(); count("held_sta_std_bypass")
                    writeRegister(1, 0x1000)

                    // Integer p0 is zero; floating p0 and unusual FP payloads preserve all 32 bits.
                    for (
                        (src, bits) <- Seq(
                            0 -> BigInt(0),
                            fp(0) -> BigInt("80000000", 16),
                            fp(2) -> BigInt("7fc01234", 16),
                            fp(3) -> BigInt("ff800000", 16)
                        )
                    ) {
                        val s = allocate(imm = 16, prs = src, value = bits)
                        writeSource(s); issueSTD(s); issueSTA(s); collect(); commit(s); drainStores()
                    }
                    count("typed_source_zero")

                    // Four SB stores can supply four separate bytes to one LW before any DCache write.
                    val bytes4 = (0 until 4).map { b =>
                        val s = allocate(imm = 32 + b, value = BigInt(0x80 + b), size = 0)
                        writeSource(s); issueSTA(s); issueSTD(s); collect(); s
                    }
                    val merged = forwarding(0x1020, serialStore)
                    assert(merged.mask == 15 && merged.data == BigInt("83828180", 16))
                    issue(load(0x1000, imm = 32, sqForward = merged).copy(sq = serialStore & 255)); drain()
                    count("four_byte_sq_merge")
                    val boundary = serialStore
                    val younger = allocate(imm = 32, value = BigInt("12345678", 16))
                    writeSource(younger); issueSTA(younger); issueSTD(younger); collect()
                    assert(forwarding(0x1020, boundary) == merged)
                    val youngest = forwarding(0x1020, serialStore)
                    assert(youngest.data == younger.value)
                    issue(load(0x1000, imm = 32, sqForward = youngest)); drain(); count("youngest_older_byte")
                    bytes4.foreach(commit)
                    // The SQ's younger bytes override the older committed SB bytes.
                    lowerReady = false
                    issue(load(
                        0x1000,
                        imm = 32,
                        sqForward = forwarding(0x1020, serialStore),
                        sbForward = forwarding(0x1020, serialStore, committedOnly = true)
                    ))
                    for (_ <- 0 until 12) tick()
                    lowerReady = true; drainStores(); drain()
                    commit(younger); drainStores()
                    issue(load(0x1000, imm = 32)); drain(); count("post_commit_cache_read")

                    // An older store with unknown address or matching missing data forces replay.
                    val unknown = allocate(imm = 40)
                    val beforeReplay = replays
                    issue(load(0x1000, imm = 40, sqForward = forwarding(0x1028, serialStore))); drain()
                    issueSTA(unknown); collect()
                    issue(load(0x1000, imm = 40, sqForward = forwarding(0x1028, serialStore))); drain()
                    assert(replays == beforeReplay + 2)
                    writeSource(unknown); issueSTD(unknown); collect()
                    issue(load(0x1000, imm = 40, sqForward = forwarding(0x1028, serialStore))); drain()
                    commit(unknown); drainStores(); count("missing_half_replay")

                    // STD runs while STA is held, and alongside an ordinary load.
                    val independent = allocate(imm = 48)
                    writeSource(independent); staReady = false; issueSTA(independent)
                    issueSTD(independent); for (_ <- 0 until 4) tick()
                    assert(independent.dataDone && !independent.addrDone)
                    staReady = true; collect(); count("std_while_sta_blocked")
                    commit(independent); drainStores()
                    val alongside = allocate(imm = 52)
                    writeSource(alongside); std = Some(alongside)
                    issue(load(0x1000, imm = 8)); assert(stdFired); std = None
                    tick(); collect(); drain(); issueSTA(alongside); collect(); commit(alongside); drainStores()

                    // Signed offsets and RV32 overflow use the same address adder for STA and LD.
                    writeRegister(3, 0xfffffff0L)
                    for (s <- Seq(allocate(base = 0xfffffff0L, imm = 32, prj = 3), allocate(imm = -4))) {
                        writeSource(s); issueSTD(s); issueSTA(s); collect(); commit(s); drainStores()
                    }
                    count("store_address_wrap")

                    // Faulting and flushed stores never drain. New work resumes on the cycle after recovery.
                    for ((offset, size, ex) <- Seq((1, 1, 0), (2, 2, 0), (0, 2, 15))) {
                        val s = allocate(imm = offset, size = size, exception = ex)
                        issueSTA(s); issueSTD(s); collect(); assert(s.fault != 0)
                        tick(cancel = Some(20)); count("store_exception")
                    }
                    for (delay <- 0 until 4) {
                        val s = allocate(uncache = true)
                        staReady = false; stdReady = false
                        sta = Some(s); std = Some(s); tick(); sta = None; std = None
                        for (_ <- 0 until delay) tick()
                        tick(cancel = Some(0)); staReady = true; stdReady = true
                        for (_ <- 0 until 3) tick()
                        assert(!s.addrDone && !s.dataDone); count("cancel_store_rf")
                    }
                    val cancelledInput = allocate()
                    sta = Some(cancelledInput); std = Some(cancelledInput)
                    tick(cancel = Some(40)); assert(!staFired && !stdFired); sta = None; std = None
                    val killedStage = allocate()
                    staReady = false; stdReady = false
                    sta = Some(killedStage); std = Some(killedStage); tick(); sta = None; std = None; tick()
                    tick(cancel = Some(42)); assert(!staFired && !stdFired)
                    val replacement = allocate()
                    sta = Some(replacement); std = Some(replacement)
                    tick(); assert(staFired && stdFired); sta = None; std = None
                    staReady = true; stdReady = true; collect(); commit(replacement); drainStores()
                    count("flush_replace_both_halves")
                    val ioStore = allocate(imm = 64, uncache = true)
                    writeSource(ioStore); issueSTD(ioStore); issueSTA(ioStore); collect(); commit(ioStore)
                    tick(cancel = Some(41)); drainStores(); count("committed_store_survives")

                    // Random sizes, lane positions, source domains, arrival orders and independent SQ backpressure.
                    val rng = new Random(20260914L)
                    for (i <- 0 until 160) {
                        val size = rng.nextInt(3)
                        val offset = (rng.nextInt(512) >> size) << size
                        val s = allocate(
                            imm = offset,
                            size = size,
                            prs = if (size == 2 && rng.nextBoolean()) fp(rng.nextInt(8)) else 2,
                            value = BigInt(32, rng)
                        )
                        writeSource(s)
                        if (rng.nextBoolean()) { issueSTA(s); issueSTD(s) }
                        else { issueSTD(s); issueSTA(s) }
                        for (_ <- 0 until rng.nextInt(5)) {
                            staReady = rng.nextBoolean(); stdReady = rng.nextBoolean()
                            tick()
                        }
                        staReady = true; stdReady = true; collect()
                        if (i % 7 == 0) {
                            val otherSlot = i & ((1 << dut.p.slotWidth) - 1)
                            other = Some((otherSlot, 0x1800L + 4 * (i % 8), 90))
                            issue(load(
                                0x1000,
                                imm = offset,
                                mtype = if (size == 2) 2 else size + 4,
                                sqForward = forwarding(s.address, serialStore)
                            ))
                            drain()
                        }
                        commit(s); drainStores()
                        issue(load(0x1000, imm = offset, mtype = if (size == 2) 2 else size + 4)); drain()
                        count("random_store")
                    }
                    assert(sq.isEmpty && sb.isEmpty && pendingSTA.isEmpty && pendingSTD.isEmpty)
                    assert(
                        storeResponses == committed,
                        s"Duplicate/missing committed Store responses: $storeResponses / $committed"
                    )
                    checkRegisters()
                    val required = Seq(
                        "sta_first",
                        "std_first",
                        "sta_std_parallel",
                        "ld_std_parallel",
                        "commit_once",
                        "held_sta_std_bypass",
                        "typed_source_zero",
                        "four_byte_sq_merge",
                        "youngest_older_byte",
                        "post_commit_cache_read",
                        "missing_half_replay",
                        "std_while_sta_blocked",
                        "store_address_wrap",
                        "flush_replace_both_halves",
                        "store_exception",
                        "cancel_store_rf",
                        "committed_store_survives",
                        "sta_size_0",
                        "sta_size_1",
                        "sta_size_2",
                        "std_fp",
                        "std_int",
                        "random_store"
                    )
                    required.foreach(k => assert(coverage(k) > 0, s"Missing coverage: $k"))
                    val out = Paths.get("build/load-store-pipeline/verification"); Files.createDirectories(out)
                    val bins = coverage.toSeq.sortBy(_._1).map { case (k, v) => s"\"$k\": $v" }.mkString(", ")
                    Files.writeString(
                        out.resolve(s"$name.json"),
                        s"""{"backend":"$name","cycles":$cycles,"sta":$staCount,"std":$stdCount,"committed":$committed,"loads":$loads,"replays":$replays,"lower_writes":$storeWrites,"coverage":{$bins}}"""
                    )
                } catch { case t: Throwable => throw new AssertionError(history.mkString("\n"), t) }
            }
        }
    }
}
