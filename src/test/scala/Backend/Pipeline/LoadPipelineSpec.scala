import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.util.Random
import ZirconConfig.Cache.{l1Line, l1Offset}

class LoadPipelineDriver(dut: LoadPipelineSystem) extends chisel3.simulator.PeekPokeAPI {
    case class Forward(data: BigInt = 0, mask: Int = 0, blocked: Boolean = false, available: Int = 0)
    case class Load(
        rob: Int,
        base: Long,
        imm: Long = 0,
        prj: Int = 1,
        prd: Int = 16,
        mtype: Int = 2,
        rdVld: Boolean = true,
        sq: Int = 0,
        exception: Int = 0,
        uncache: Boolean = false,
        authorized: Boolean = false,
        sqForward: Forward = Forward(),
        sbForward: Forward = Forward()
    ) {
        val address: Long = (base + imm) & 0xffffffffL
        val retry: Boolean = exception == 0 && aligned &&
            (if (uncache) !authorized else sqForward.blocked || sbForward.blocked)
        def aligned: Boolean = (address & ((1 << (mtype & 3)) - 1)) == 0
        def fault: Int = if (exception != 0) exception
        else if (!aligned) 4
        else if (errors(if (uncache) address else address & ~(l1Line.toLong - 1))) 5 else 0
    }
    case class Transaction(id: Int, address: Long, data: BigInt, mask: Int, write: Boolean, due: Int, error: Boolean)
    case class Active(load: Load, cycle: Int, var requestCycle: Int = -1, var written: Boolean = false)
    val active = mutable.Map.empty[Int, Active]
    val accepted = mutable.Queue.empty[Load]
    val requests = mutable.Map.empty[Int, Load]
    val memory = mutable.Map.empty[Long, Int]
    val errors = mutable.Set.empty[Long]
    val intValues = mutable.Map.empty[Int, BigInt].withDefaultValue(BigInt(0))
    val fpValues = mutable.Map.empty[Int, BigInt].withDefaultValue(BigInt(0))
    val coverage = mutable.Map.empty[String, Int].withDefaultValue(0)
    val history = mutable.Queue.empty[String]
    var transaction: Option[Transaction] = None
    var cycles = 0
    var loads = 0
    var writes = 0
    var cancelled = 0
    var replays = 0
    var reads = 0
    var otherLoads = 0
    var storeWrites = 0
    var latency = 4
    var lowerReady = true
    var producer: Option[(Int, BigInt)] = None
    var other: Option[(Int, Long, Int)] = None
    val otherPending = mutable.Map.empty[(Int, Int), Long]
    val otherReplay = mutable.Queue.empty[(Int, Long, Int)]
    private var heldRequest: Option[Seq[BigInt]] = None
    private var heldLower: Option[Seq[BigInt]] = None
    private var pendingForward: Option[(Int, Load)] = None
    private var serial = 0
    val hitLatencies = mutable.ArrayBuffer.empty[Int]
    val requestCycles = mutable.ArrayBuffer.empty[Int]
    def count(key: String): Unit = coverage(key) += 1
    def fp(index: Int): Int = (1 << dut.p.physWidth) | index
    def load(
        base: Long,
        imm: Long = 0,
        prj: Int = 1,
        prd: Int = 16,
        mtype: Int = 2,
        rdVld: Boolean = true,
        sqForward: Forward = Forward(),
        sbForward: Forward = Forward(),
        exception: Int = 0,
        uncache: Boolean = false,
        authorized: Boolean = false
    ): Load = {
        val x = Load(
            serial & ((1 << dut.p.robWidth) - 1),
            base,
            imm,
            prj,
            prd,
            mtype,
            rdVld,
            (serial * 37) & ((1 << dut.p.sqWidth) - 1),
            exception,
            uncache,
            authorized,
            sqForward,
            sbForward
        )
        serial += 1
        x
    }
    def initial(a: Long): Int = ((a * 37 + (a >> 7) + 0x81) & 255).toInt
    def bytes(a: Long, n: Int): BigInt = (0 until n).foldLeft(BigInt(0))((v, i) =>
        v | (BigInt(memory.getOrElse(a + i, initial(a + i))) << (i * 8))
    )
    def putLine(address: Long, data: BigInt): Unit =
        for (b <- 0 until l1Line)
            memory((address & ~(l1Line.toLong - 1)) + b) = ((data >> (8 * b)) & 255).toInt
    def value(x: Load): BigInt = {
        var word = bytes(x.address & ~3L, 4)
        if (!x.uncache) for (b <- 0 until 4) {
            val f = if ((x.sqForward.mask & (1 << b)) != 0) x.sqForward else x.sbForward
            if ((f.mask & (1 << b)) != 0) word = (word & ~(BigInt(255) << (8 * b))) |
                (((f.data >> (8 * b)) & 255) << (8 * b))
        }
        val bits = (1 << (x.mtype & 3)) * 8
        var v = (word >> ((x.address & 3).toInt * 8)) & ((BigInt(1) << bits) - 1)
        if ((x.mtype & 4) == 0 && v.testBit(bits - 1))
            v |= ((BigInt(1) << 32) - 1) ^ ((BigInt(1) << bits) - 1)
        v
    }
    def fields(b: chisel3.Record): Seq[BigInt] =
        b.elements.values.toSeq.map(_.peek().litValue)
    def pokeLoad(x: Load): Unit = {
        val b = dut.io.iq.instPkg.bits
        BackendPackageTestUtils.clear(b)
        b.fu.poke(ZirconConfig.DecodeUnit.Load)
        b.store.poke(false)
        b.sqIdx.poke(0)
        b.prj.poke(x.prj)
        b.prd.poke(x.prd)
        b.rdVld.poke(x.rdVld)
        b.imm.poke(BigInt(x.imm & 0xffffffffL))
        b.mtype.poke(x.mtype)
        b.robIdx.poke(x.rob)
        b.sqTail.poke(x.sq)
        b.exception.valid.poke(x.exception != 0)
        b.exception.cause.poke(x.exception)
        b.exception.tval.poke(0)
        b.uncache.poke(x.uncache)
        b.ioAuthorized.poke(x.authorized)
    }
    def stable(previous: Option[Seq[BigInt]], valid: Boolean, current: Seq[BigInt], name: String): Unit =
        previous.foreach(x => assert(valid && x == current, s"Unstable $name at $cycles"))

    // Store tests extend the same cache/memory/load scoreboard with independent SQ events.
    def beforeCycle(input: Option[Load], cancel: Option[Int]): Unit = {}
    def afterCycle(input: Option[Load], cancel: Option[Int]): Unit = {}

    def tick(
        input: Option[Load] = None,
        cancel: Option[Int] = None,
        store: Option[(Long, BigInt, Int)] = None
    ): Boolean = {
        if (other.isEmpty && otherReplay.nonEmpty) other = Some(otherReplay.dequeue())
        history.enqueue(s"cycle=$cycles input=$input cancel=$cancel active=${active.keys.toSeq} lower=$transaction")
        if (history.size > 80) history.dequeue()
        dut.io.iq.instPkg.valid.poke(input.nonEmpty)
        pokeLoad(input.getOrElse(Load(0, 0)))
        dut.io.cmt.flush.poke(cancel.nonEmpty)
        dut.io.wk.grant.poke(0)
        for ((w, i) <- dut.io.intWrite.zipWithIndex) {
            w.we.poke(i == 0 && producer.nonEmpty)
            w.addr.poke(if (i == 0) producer.map(_._1).getOrElse(0) else 0)
            w.data.poke(if (i == 0) producer.map(_._2).getOrElse(BigInt(0)) else BigInt(0))
        }
        dut.io.fpWrite.foreach { w => w.we.poke(false); w.addr.poke(0); w.data.poke(0) }
        dut.io.intRead.foreach(_.addr.poke(0))
        dut.io.fpRead.foreach(_.addr.poke(0))
        dut.io.other.req.valid.poke(other.nonEmpty)
        dut.io.other.req.bits.vaddr.poke(other.map(_._2).getOrElse(0L))
        dut.io.other.req.bits.paddr.poke(other.map(_._2).getOrElse(0L))
        dut.io.other.req.bits.slot.poke(other.map(_._1).getOrElse(0))
        dut.io.other.req.bits.mtype.poke(2)
        dut.io.other.req.bits.exception.poke(0)
        dut.io.other.req.bits.uncache.poke(false)
        dut.io.other.req.bits.ioAuthorized.poke(false)
        dut.io.other.req.bits.translationMiss.poke(false)
        dut.io.store.req.valid.poke(store.nonEmpty)
        dut.io.store.req.bits.paddr.poke(store.map(_._1).getOrElse(0L))
        dut.io.store.req.bits.data.poke(store.map(_._2).getOrElse(BigInt(0)))
        dut.io.store.req.bits.mask.poke(store.map(_._3).getOrElse(15))
        dut.io.store.req.bits.size.poke(2)
        dut.io.store.req.bits.uncache.poke(false)
        dut.io.store.rsp.ready.poke(true)
        dut.io.l2.req.ready.poke(lowerReady)
        val response = transaction.filter(_.due <= cycles)
        dut.io.l2.rsp.valid.poke(response.nonEmpty)
        dut.io.l2.rsp.bits.data.poke(response.map(_.data).getOrElse(BigInt(0)))
        dut.io.l2.rsp.bits.dirty.poke(false)
        dut.io.l2.rsp.bits.error.poke(response.exists(_.error))
        dut.io.iq.std.foreach { s =>
            s.valid.poke(false)
            BackendPackageTestUtils.clear(s.bits)
            s.bits.prs(0).poke(0); s.bits.sqIdx.poke(0); s.bits.robIdx.poke(0); s.bits.size.poke(0)
        }
        dut.io.cmt.storeAddress.foreach(_.ready.poke(true))
        dut.io.cmt.storeData.foreach(_.ready.poke(true))
        beforeCycle(input, cancel)

        cancel.foreach { _ =>
            val dead = active.keySet.toSet
            cancelled += dead.size
            active.clear()
            accepted.clear()
            requests.clear()
            otherPending.clear()
            otherReplay.clear()
            heldRequest = None
            pendingForward = None
            count("cancel")
        }

        val q = dut.io.cmt.sqQuery
        val priorForward = pendingForward
        val forwardReady = priorForward.exists { case (_, x) =>
            cycles >= math.max(x.sqForward.available, x.sbForward.available)
        }
        for (
            (f, port) <- Seq(
                priorForward.map(_._2.sqForward).getOrElse(Forward()) -> dut.io.cmt.sqResult,
                priorForward.map(_._2.sbForward).getOrElse(Forward()) -> dut.io.cmt.sbResult
            )
        ) {
            port.valid.poke(forwardReady)
            port.bits.slot.poke(priorForward.map(_._1).getOrElse(0))
            port.bits.data.poke(f.data)
            port.bits.mask.poke(f.mask)
            port.bits.blocked.poke(f.blocked)
        }
        val query = if (q.valid.peek().litToBoolean) {
            val id = q.bits.slot.peek().litValue.toInt
            // A request accepted directly into an empty DCache lookup can start
            // forwarding in the same cycle that the scoreboard records it.
            val x = requests.getOrElse(id, accepted.front)
            q.bits.wordAddress.expect(x.address >> 2)
            q.bits.sqTail.expect(x.sq & ((1 << q.bits.sqTail.getWidth) - 1))
            dut.io.cmt.sbQuery.valid.expect(true)
            Some(x)
        } else None
        pendingForward = query match {
            case Some(x) =>
                assert(priorForward.isEmpty || forwardReady, s"Overlapping forwarding queries at $cycles")
                Some(q.bits.slot.peek().litValue.toInt -> x)
            case None if forwardReady => None
            case None => priorForward
        }
        if (query.nonEmpty) {
            count("forward_query")
            if (query.get.sqForward.mask != 0 && query.get.sbForward.mask != 0) count("sq_sb_merge")
        }

        val reqValid = dut.io.request.valid.peek().litToBoolean
        val reqReady = dut.io.requestReady.peek().litToBoolean
        val request = fields(dut.io.request.bits)
        stable(heldRequest, reqValid, request, "cache request")
        heldRequest = if (reqValid && !reqReady) Some(request) else None
        if (reqValid && !reqReady) count("rf_stall")
        if (reqValid && reqReady) {
            val x = accepted.dequeue()
            dut.io.request.bits.paddr.expect(x.address)
            dut.io.request.bits.mtype.expect(x.mtype)
            val key = dut.io.request.bits.slot.peek().litValue.toInt
            assert(!requests.contains(key))
            requests(key) = x
            active(x.rob).requestCycle = cycles
            requestCycles += cycles
            count(s"type_${x.mtype}")
        }

        val wbValid = dut.io.wb.valid.peek().litToBoolean
        dut.io.wk.wakeWB.specMask.expect(0)
        if (wbValid && cancel.isEmpty) {
            dut.io.wk.wakeWB.prd.expect(dut.io.wb.bits.prd.peek().litValue)
            val prd = dut.io.wb.bits.prd.peek().litValue.toInt
            val a = active.values.find(_.load.prd == prd).get
            assert(a.load.rdVld && a.load.fault == 0 && !a.load.retry)
            dut.io.bypass.result.expect(value(a.load))
        }
        if (cancel.nonEmpty) {
            dut.io.wb.valid.expect(false)
            dut.io.cmt.rob.valid.expect(false)
        } else if (!wbValid) {
            dut.io.wk.wakeWB.prd.expect(0)
        }
        if (wbValid) {
            dut.io.cmt.rob.valid.expect(true)
            count("fixed_wb_completion")
            val prd = dut.io.wb.bits.prd.peek().litValue.toInt
            val a = active.values.find(a => a.load.prd == prd && !a.written).get
            assert(a.load.rdVld && a.load.fault == 0 && !a.load.retry)
            dut.io.wb.bits.data.expect(value(a.load), s"Result mismatch for ${a.load} at $cycles")
            dut.io.wk.wakeWB.prd.expect(prd)
            dut.io.bypass.result.expect(value(a.load))
            if ((prd & (1 << dut.p.physWidth)) != 0) {
                fpValues(prd & ((1 << dut.p.physWidth) - 1)) = value(a.load); count("fp_write")
            } else { intValues(prd) = value(a.load); count("int_write") }
            a.written = true
            writes += 1
            hitLatencies += cycles - a.requestCycle
        }

        val robValid = dut.io.cmt.rob.valid.peek().litToBoolean
        if (robValid) {
            val rob = dut.io.cmt.rob.bits.robIdx.peek().litValue.toInt
            val a = active.remove(rob).get
            assert(!a.load.retry || a.load.fault != 0)
            assert(!a.load.rdVld || a.load.fault != 0 || a.written, "ROB completed before PRF write")
            dut.io.cmt.rob.bits.vaddr.expect(a.load.address)
            dut.io.cmt.rob.bits.exception.expect(a.load.fault)
            requests.filterInPlace((_, x) => x.rob != rob)
            loads += 1
            if (a.load.fault != 0) count(s"exception_${a.load.fault}")
            if (!a.load.rdVld) count("x0_load")
        }

        val replayValid = dut.io.wk.replay.valid.peek().litToBoolean
        if (replayValid) {
            val rob = dut.io.wk.replay.bits.robIdx.peek().litValue.toInt
            val a = active.remove(rob).get
            assert(a.load.fault == 0 && !a.written)
            dut.io.wk.replay.bits.prj.expect(a.load.prj)
            dut.io.wk.replay.bits.prd.expect(a.load.prd)
            dut.io.wk.replay.bits.imm.expect(BigInt(a.load.imm & 0xffffffffL))
            dut.io.wk.replay.bits.sqTail.expect(a.load.sq)
            requests.filterInPlace((_, x) => x.rob != rob)
            replays += 1
        }

        if (dut.io.other.rsp.valid.peek().litToBoolean) {
            val id = dut.io.other.rsp.bits.slot.peek().litValue.toInt
            val key = otherPending.keys.find(_._1 == id).get
            val a = otherPending.remove(key).get
            dut.io.other.rsp.bits.exception.expect(0)
            if (dut.io.other.rsp.bits.retry.peek().litToBoolean) {
                otherReplay.enqueue((key._1, a, key._2))
            } else {
                dut.io.other.rsp.bits.data.expect(bytes(a, 4))
                otherLoads += 1
            }
        }
        if (other.nonEmpty && dut.io.other.req.ready.peek().litToBoolean) {
            val x = other.get
            otherPending((x._1, x._3)) = x._2
            other = None
            if (reqValid && reqReady) count("dual_cache_accept")
        }
        if (dut.io.store.rsp.valid.peek().litToBoolean) {
            dut.io.store.rsp.bits.exception.expect(0)
        }
        if (response.nonEmpty && dut.io.l2.rsp.ready.peek().litToBoolean) {
            val t = response.get
            if (t.write && !t.error) for (b <- 0 until 4 if (t.mask & (1 << b)) != 0)
                memory((t.address & ~3L) + b) = ((t.data >> (b * 8)) & 255).toInt
            transaction = None
        }
        val lowerValid = dut.io.l2.req.valid.peek().litToBoolean
        val lower = fields(dut.io.l2.req.bits)
        stable(heldLower, lowerValid, lower, "lower request")
        heldLower = if (lowerValid && !lowerReady) Some(lower) else None
        if (lowerValid && !lowerReady) count("lower_stall")
        if (lowerValid && lowerReady) {
            assert(transaction.isEmpty)
            val r = dut.io.l2.req.bits
            val a = r.paddr.peek().litValue.toLong
            val write = r.write.peek().litToBoolean
            val dirtyVictim = r.victimValid.peek().litToBoolean && r.victimDirty.peek().litToBoolean
            if (dirtyVictim) {
                putLine(
                    r.victimLine.peek().litValue.toLong << l1Offset,
                    r.victimData.peek().litValue
                )
                storeWrites += 1
            }
            val data = if (write) r.data.peek().litValue
            else bytes(a, if (r.uncache.peek().litToBoolean) 1 << r.size.peek().litValue.toInt else l1Line)
            transaction = Some(Transaction(
                0,
                a,
                data,
                r.mask.peek().litValue.toInt,
                write,
                cycles + latency,
                errors(a)
            ))
            if (write) storeWrites += 1 else reads += 1
        }
        val fire = input.nonEmpty && dut.io.iq.instPkg.ready.peek().litToBoolean
        if (fire) {
            val x = input.get
            assert(!active.contains(x.rob))
            active(x.rob) = Active(x, cycles)
            accepted.enqueue(x)
        }
        producer.foreach { case (r, v) => intValues(r) = v }
        afterCycle(input, cancel)
        dut.clock.step()
        cycles += 1
        fire
    }
    def issue(x: Load): Unit = {
        var guard = 0
        while (!tick(Some(x)) && guard < 1000) guard += 1
        assert(guard < 1000)
    }
    def drain(): Unit = {
        var guard = 0
        while (
            (active.nonEmpty || transaction.nonEmpty || other.nonEmpty || otherReplay.nonEmpty ||
                otherPending.nonEmpty || !dut.io.idle.peek().litToBoolean) &&
            guard < 1500
        ) {
            tick(); guard += 1
        }
        assert(guard < 1500, s"Drain failed: $active")
        for (_ <- 0 until 8) tick()
        assert(accepted.isEmpty && requests.isEmpty)
    }
    def writeRegister(r: Int, v: Long): Unit = {
        producer = Some(r -> BigInt(v & 0xffffffffL)); tick(); producer = None
    }
    def checkRegisters(): Unit = {
        for ((r, v) <- intValues) { dut.io.intRead(0).addr.poke(r); dut.io.intRead(0).data.expect(v) }
        for ((r, v) <- fpValues) { dut.io.fpRead(0).addr.poke(r); dut.io.fpRead(0).data.expect(v) }
        dut.io.intRead(0).addr.poke(0); dut.io.intRead(0).data.expect(0)
    }
}

class LoadPipelineSpec extends AnyFreeSpec with ChiselSim {
    def withStore: Boolean = false
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case s: svsim.verilator.Backend.CompilationSettings =>
            s.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case s => s
    }
    for (
        (backend, name) <- Seq(
            DualPortRamBackend.Registers -> "registers",
            DualPortRamBackend.Vivado -> "vivado",
            DualPortRamBackend.BSG -> "bsg"
        )
        if sys.env.get("ZIRCON_LOAD_BACKEND").forall(_ == name)
    ) {
        s"$name: RF/AGU, fixed typed writeback and real shared DCache under stalls, replay and cancellation" in {
            simulate(new LoadPipelineSystem(backend, withStore = withStore)) { dut =>
                val d = new LoadPipelineDriver(dut)
                import d._
                try {
                    tick()
                    writeRegister(1, 0x1000)
                    writeRegister(2, 0xfffffff0L)
                    writeRegister(3, 0x2000)
                    // Cold line, signed/unsigned subwords, FP destination and address wraparound.
                    for (t <- Seq(0, 1, 2, 4, 5)) { issue(load(0x1000, imm = 4, mtype = t)); drain() }
                    issue(load(0x1000, prd = fp(16))); drain()
                    issue(load(0x1000, prd = fp(0))); drain()
                    issue(load(0x1000, prd = 0, rdVld = false)); drain()
                    issue(load(0x1000, imm = -4)); drain()
                    issue(load(0xfffffff0L, imm = 0x20, prj = 2)); drain()
                    checkRegisters()

                    // Actual producer write in the consumer RF cycle must win over stored PRF data.
                    val bypassed = load(0x3040, prj = 3, prd = 17)
                    issue(bypassed)
                    producer = Some(3 -> BigInt(0x3040)); tick(); producer = None
                    assert(active(bypassed.rob).requestCycle == active(bypassed.rob).cycle + 1)
                    drain(); count("same_cycle_wb_base")
                    issue(load(0x1000, prd = 18, sqForward = Forward(BigInt(0x4000), 15))); drain()
                    issue(load(0x4000, prj = 18, prd = 19)); drain(); count("dependent_load")

                    // Four occupied contexts must still sustain one hit per cycle by recycling WB.
                    val start = requestCycles.size
                    for (i <- 0 until 40) issue(load(0x1000, imm = (i % 8) * 4, prd = 20 + i % 20))
                    drain()
                    val sequence = requestCycles.drop(start)
                    assert(sequence.sliding(2).forall(x => x(1) - x(0) == 1), s"Hit throughput bubbles: $sequence")
                    assert(hitLatencies.contains(3), "No three-cycle cache-to-WB hit")
                    count("sustained_hit")

                    // SQ wins per overlapping byte; disjoint bytes are filled by SB, then Cache.
                    for (mask <- 0 until 16) {
                        val sq = Forward(BigInt("817fa05c", 16), mask, available = cycles + 12)
                        val sb = Forward(BigInt("01020304", 16), 15 ^ (mask >> 1))
                        issue(load(0x1000, imm = 8, prd = fp(24), sqForward = sq, sbForward = sb)); drain()
                        count("forward_mask")
                    }
                    // Preserve special FP bit patterns without involving arithmetic.
                    for (bits <- Seq("00000000", "80000000", "7f800001", "7fc01234", "ff800000")) {
                        issue(load(0x1000, prd = fp(25), sqForward = Forward(BigInt(bits, 16), 15))); drain()
                        count("fp_bits")
                    }
                    issue(load(0x1000, imm = 1, mtype = 2)); drain()
                    issue(load(0x1000, exception = 13)); drain()
                    errors += 0x6000L
                    issue(load(0x1000, imm = 0x5000)); drain(); errors.clear()

                    val retry = load(0x1000, sqForward = Forward(blocked = true))
                    issue(retry); drain()
                    issue(retry.copy(sqForward = Forward())); drain(); count("reissued_load")
                    issue(load(0x1000, uncache = true)); drain()
                    val ioLoad = load(0x1000, uncache = true, authorized = true)
                    issue(ioLoad); tick(cancel = Some(7)); drain(); count("authorized_io_survives")

                    // Cancel separately across the request and cache stages, then reuse the released slots.
                    for (delay <- 0 until 5) {
                        issue(load(0x1000))
                        for (_ <- 0 until delay) tick()
                        tick(cancel = Some(10 + delay))
                        issue(load(0x1000, prd = fp(26))); drain()
                        count("cancel_stage")
                    }

                    latency = 40
                    issue(load(0x1000, imm = 0x7000))
                    while (transaction.isEmpty) tick()
                    tick(cancel = Some(60))
                    issue(load(0x1000, prd = fp(29))); drain(); count("cancel_miss")

                    // Two load lanes and disjoint committed writes compete for the actual two RAM ports.
                    val rng = new Random(20260913L)
                    var pending: Option[Load] = None
                    var otherId = 0
                    var pendingStore: Option[(Long, BigInt, Int)] = None
                    for (i <- 0 until 5000) {
                        lowerReady = rng.nextInt(3) != 0
                        latency = 2 + rng.nextInt(15)
                        if (pending.isEmpty) {
                            val t = Seq(0, 1, 2, 4, 5)(rng.nextInt(5))
                            val off = rng.nextInt(4096) & ~((1 << (t & 3)) - 1)
                            val fpDestination = i % 3 == 0
                            val dest = 16 + i %
                                (if (fpDestination) dut.p.numFpPhys - 16 else dut.p.numIntPhys - 16)
                            val x = load(
                                0x1000,
                                imm = off,
                                prd = if (fpDestination) fp(dest) else dest,
                                mtype = if (fpDestination) 2 else t
                            )
                            if (!active.values.exists(_.load.prd == x.prd))
                                pending = Some(if (fpDestination) x.copy(imm = off & ~3L) else x)
                        }
                        if (other.isEmpty && otherReplay.isEmpty) {
                            val slot = otherId & 3
                            if (!otherPending.keys.exists(_._1 == slot)) {
                                other = Some((slot, 0x1000L + rng.nextInt(1024) * 4, 101))
                                otherId += 1
                            }
                        }
                        if (pendingStore.isEmpty && i % 137 == 0)
                            pendingStore = Some((0x100000L + (i % 64) * 4, BigInt(32, rng), 1 + rng.nextInt(15)))
                        // Committed writes use an address region disjoint from all in-flight loads.
                        val storeFire = pendingStore.nonEmpty && dut.io.store.req.ready.peek().litToBoolean
                        if (tick(pending, store = pendingStore)) pending = None
                        if (storeFire) pendingStore = None
                    }
                    lowerReady = true
                    pending.foreach(issue)
                    while (other.nonEmpty) tick()
                    drain(); checkRegisters()
                    for (
                        required <- Seq(
                            "same_cycle_wb_base",
                            "dependent_load",
                            "sustained_hit",
                            "fixed_wb_completion",
                            "sq_sb_merge",
                            "fp_bits",
                            "exception_4",
                            "exception_5",
                            "exception_13",
                            "reissued_load",
                            "authorized_io_survives",
                            "cancel_stage",
                            "cancel_miss",
                            "dual_cache_accept",
                            "rf_stall",
                            "lower_stall"
                        )
                    )
                        assert(coverage(required) > 0, s"Missing $required")
                    val out = Paths.get(sys.env.getOrElse(
                        "ZIRCON_LOAD_OUTPUT",
                        if (withStore) "build/load-store-pipeline/load-verification"
                        else "build/load-pipeline/verification"
                    ))
                    Files.createDirectories(out)
                    val cover = coverage.toSeq.sortBy(_._1).map { case (k, v) => s"\"$k\":$v" }.mkString("{", ",", "}")
                    Files.writeString(
                        out.resolve(s"$name.json"),
                        s"""{"backend":"$name","cycles":$cycles,"loads":$loads,"writes":$writes,"cancelled":$cancelled,"replays":$replays,"lower_reads":$reads,"other_loads":$otherLoads,"lower_writes":$storeWrites,"coverage":$cover}
"""
                    )
                    info(
                        s"$name cycles=$cycles loads=$loads writes=$writes cancelled=$cancelled replay=$replays other=$otherLoads"
                    )
                } catch {
                    case t: Throwable => throw new AssertionError(history.mkString("\n"), t)
                }
            }
        }
    }
}

class LoadStoreLoadSpec extends LoadPipelineSpec {
    override def withStore: Boolean = true
}
