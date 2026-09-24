import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.Cache.{l1IndexNum, l1Line, l1Offset}

import scala.collection.mutable
import scala.util.Random

class DCacheDriver(dut: DCache, seed: Long = 20260911L) extends chisel3.simulator.PeekPokeAPI {
    case class Load(
        address: Long,
        id: Int,
        mtype: Int = 2,
        uncache: Boolean = false,
        authorized: Boolean = false,
        exception: Int = 0
    )
    case class Store(
        address: Long,
        data: BigInt,
        mask: Int = 15,
        size: Int = 2,
        id: Int = 0,
        uncache: Boolean = false,
        expectedException: Int = -1
    )
    case class Forward(data: BigInt = 0, mask: Int = 0, blocked: Boolean = false, available: Int = 0)
    case class Expected(request: Load, value: BigInt, exception: Int, retry: Boolean, cycle: Int)
    case class Transaction(
        id: Int,
        address: Long,
        write: Boolean,
        data: BigInt,
        mask: BigInt,
        victimValid: Boolean,
        victimAddress: Long,
        victimData: BigInt,
        victimDirty: Boolean,
        responseDirty: Boolean,
        due: Int,
        error: Boolean
    )
    val random = new Random(seed)
    val lineBytes = l1Line
    val setStride = l1IndexNum * l1Line
    val memory = mutable.Map.empty[Long, Int]
    val reference = mutable.Map.empty[Long, Int]
    val expected = mutable.Map.empty[(Int, Int), Expected]
    val replay = Seq.fill(2)(mutable.Queue.empty[Load])
    val forward = mutable.Map.empty[Int, Forward]
    val errors = mutable.Set.empty[Long]
    val dirtyResponses = mutable.Set.empty[Long]
    val dirtyVictimAddresses = mutable.ArrayBuffer.empty[Long]
    var store: Option[Store] = None
    var transaction: Option[Transaction] = None
    var cycles = 0
    var reads = 0
    var writes = 0
    var cleanVictims = 0
    var dirtyVictims = 0
    var loads = 0
    var stores = 0
    var cancelled = 0
    var pairs = 0
    var latency = 6
    var lowerReady = true
    var randomStalls = false
    var storeReady = true
    var maintenanceRequest = false
    var maintenanceInvalidate = false
    val latencies = mutable.ArrayBuffer.empty[Int]
    private var heldLower: Option[Seq[BigInt]] = None
    private var heldStoreResponse: Option[Seq[BigInt]] = None
    private val pendingForward = Array.fill[Option[(Int, Forward)]](2)(None)
    private var nextId = 0
    private val reservedIds = mutable.Set.empty[Int]
    def observeCycle(): Unit = ()
    def load(
        address: Long,
        mtype: Int = 2,
        uncache: Boolean = false,
        authorized: Boolean = false,
        exception: Int = 0
    ): Load = {
        val idCount = 1 << dut.p.slotWidth
        val unavailable = reservedIds.toSet ++ expected.keys.map(_._2) ++ replay.flatMap(_.map(_.id))
        val id = (0 until idCount).map(offset => (nextId + offset) & (idCount - 1)).find(!unavailable(_))
            .getOrElse(throw new AssertionError(s"no free load transaction ID at cycle $cycles"))
        val x = Load(address, id, mtype, uncache, authorized, exception)
        reservedIds += id
        nextId = (id + 1) & (idCount - 1)
        x
    }
    def initial(address: Long): Int = ((address * 37 + (address >> 7) + (address >> 32) * 59 + 0x81) & 255).toInt
    def bytes(map: mutable.Map[Long, Int], address: Long, count: Int): BigInt = (0 until count).foldLeft(BigInt(0))(
        (x, b) => x | (BigInt(map.getOrElse(address + b, initial(address + b))) << (b * 8))
    )
    def put(map: mutable.Map[Long, Int], address: Long, data: BigInt, mask: Int): Unit =
        for (b <- 0 until 4 if (mask & (1 << b)) != 0) map((address & ~3L) + b) = ((data >> (8 * b)) & 255).toInt
    def putLine(map: mutable.Map[Long, Int], address: Long, data: BigInt): Unit =
        for (b <- 0 until lineBytes) {
            map((address & ~(lineBytes - 1).toLong) + b) = ((data >> (8 * b)) & 255).toInt
        }
    def aligned(address: Long, size: Int): Boolean = size < 3 && (address & ((1 << size) - 1)) == 0
    def storeException(x: Store): Int =
        if (x.expectedException >= 0) x.expectedException
        else if (
            !aligned(x.address, x.size) || x.mask == 0 ||
            (x.uncache && x.mask != (((1 << (1 << x.size)) - 1) << (x.address & 3).toInt))
        ) 6
        else if (x.uncache && errors(x.address)) 7 else 0
    def value(x: Load, f: Forward): BigInt = {
        var result = bytes(reference, x.address, 1 << (x.mtype & 3))
        if (!x.uncache) for (b <- 0 until (1 << (x.mtype & 3))) {
            val lane = (x.address & 3).toInt + b
            if ((f.mask & (1 << lane)) != 0) result = (result & ~(BigInt(255) << (8 * b))) |
                (((f.data >> (8 * lane)) & 255) << (8 * b))
        }
        val bits = (1 << (x.mtype & 3)) * 8
        if ((x.mtype & 4) == 0 && result.testBit(bits - 1)) result |=
            ((BigInt(1) << 32) - 1) ^ ((BigInt(1) << bits) - 1)
        result & ((BigInt(1) << 32) - 1)
    }
    def tick(
        inputs: Seq[Option[Load]] = Seq(None, None),
        write: Option[Store] = None,
        cancel: Option[Int] = None
    ): Seq[Boolean] = {
        val presentingReplay = replay.map(_.nonEmpty)
        val drivenInputs = inputs.indices.map(i => replay(i).headOption.orElse(inputs(i)))
        dut.io.flush.poke(cancel.nonEmpty)
        if (cancel.nonEmpty) {
            val retainedIds = inputs.flatten.map(_.id).toSet
            reservedIds.filterInPlace(retainedIds)
        }
        for (i <- 0 until 2) {
            val x = drivenInputs(i).getOrElse(Load(0, 0))
            dut.io.load(i).req.valid.poke(drivenInputs(i).nonEmpty)
            dut.io.load(i).req.bits.vaddr.poke(x.address)
            dut.io.load(i).req.bits.paddr.poke(x.address)
            dut.io.load(i).req.bits.slot.poke(x.id)
            dut.io.load(i).req.bits.mtype.poke(x.mtype)
            dut.io.load(i).req.bits.uncache.poke(x.uncache)
            dut.io.load(i).req.bits.ioAuthorized.poke(x.authorized)
            dut.io.load(i).req.bits.exception.poke(x.exception)
            dut.io.load(i).req.bits.translationMiss.poke(false)
        }
        val s = write.getOrElse(Store(0, 0))
        dut.io.store.req.valid.poke(write.nonEmpty)
        dut.io.store.req.bits.paddr.poke(s.address)
        dut.io.store.req.bits.data.poke(s.data)
        dut.io.store.req.bits.mask.poke(s.mask)
        dut.io.store.req.bits.size.poke(s.size)
        dut.io.store.req.bits.uncache.poke(s.uncache)
        dut.io.store.rsp.ready.poke(storeReady)
        dut.io.maintenance.request.poke(maintenanceRequest)
        dut.io.maintenance.invalidate.poke(maintenanceInvalidate)
        dut.io.l2.req.ready.poke(lowerReady && (!randomStalls || random.nextInt(3) != 0))
        val response = transaction.filter(_.due <= cycles)
        dut.io.l2.rsp.valid.poke(response.nonEmpty)
        dut.io.l2.rsp.bits.data.poke(response.map(_.data).getOrElse(BigInt(0)))
        dut.io.l2.rsp.bits.dirty.poke(response.exists(_.responseDirty))
        dut.io.l2.rsp.bits.error.poke(response.exists(_.error))
        cancel.foreach { _ =>
            expected.filterInPlace { case (_, e) =>
                val keep = e.request.uncache && e.request.authorized
                if (!keep) cancelled += 1
                keep
            }
            replay.foreach(_.filterInPlace(x => x.uncache && x.authorized))
            pendingForward.indices.foreach(i => pendingForward(i) = None)
        }
        for (i <- 0 until 2) {
            val q = dut.io.forward(i).query
            val id = q.bits.slot.peek().litValue.toInt
            val address = q.bits.wordAddress.peek().litValue.toLong << 2
            var f = forward.getOrElse(id, Forward())
            if (!forward.contains(id)) store.filter(x => (x.address & ~3L) == (address & ~3L) && storeException(x) == 0)
                .foreach(x => f = Forward(x.data, x.mask))
            val prior = pendingForward(i)
            val priorReady = prior.exists { case (_, response) => cycles >= response.available }
            dut.io.forward(i).result.valid.poke(priorReady)
            dut.io.forward(i).result.bits.slot.poke(prior.map(_._1).getOrElse(0))
            dut.io.forward(i).result.bits.data.poke(prior.map(_._2.data).getOrElse(BigInt(0)))
            dut.io.forward(i).result.bits.mask.poke(prior.map(_._2.mask).getOrElse(0))
            dut.io.forward(i).result.bits.blocked.poke(prior.exists(_._2.blocked))
            pendingForward(i) =
                if (q.valid.peek().litToBoolean) Some(id -> f)
                else if (priorReady) None
                else prior
            val responseValid = dut.io.load(i).rsp.valid.peek().litToBoolean
            val responseId = dut.io.load(i).rsp.bits.slot.peek().litValue.toInt
            val responseExpected = expected.contains((i, responseId))
            if (responseValid && (cancel.isEmpty || responseExpected)) {
                val e = expected.remove((
                    i,
                    responseId
                )).getOrElse(throw new AssertionError(s"unexpected lane=$i id=$responseId cycle=$cycles"))
                dut.io.load(i).rsp.bits.exception.expect(e.exception, s"cycle=$cycles request=${e.request}")
                val actualRetry = dut.io.load(i).rsp.bits.retry.peek().litToBoolean
                if (e.retry) {
                    assert(actualRetry, s"cycle=$cycles request=${e.request}")
                } else if (actualRetry) {
                    assert(
                        e.exception == 0 && (!e.request.uncache || e.request.authorized),
                        s"unexpected retry: $e"
                    )
                    replay(i).enqueue(e.request)
                }
                if (e.exception == 0 && !e.retry && !actualRetry)
                    dut.io.load(i).rsp.bits.data.expect(e.value, s"cycle=$cycles request=${e.request}")
                loads += 1
                latencies += cycles - e.cycle
            }
        }
        val storeSignature = Seq(dut.io.store.rsp.bits.exception).map(_.peek().litValue)
        heldStoreResponse.foreach { previous =>
            assert(
                dut.io.store.rsp.valid.peek().litToBoolean && storeSignature == previous,
                s"unstable store response cycle=$cycles"
            )
        }
        heldStoreResponse =
            if (dut.io.store.rsp.valid.peek().litToBoolean && !storeReady) Some(storeSignature) else None
        observeCycle()
        if (dut.io.store.rsp.valid.peek().litToBoolean && storeReady) {
            val x = store.getOrElse(throw new AssertionError(s"unexpected store response $cycles"))
            dut.io.store.rsp.bits.exception.expect(storeException(x))
            store = None
            stores += 1
        }
        if (response.nonEmpty && dut.io.l2.rsp.ready.peek().litToBoolean) {
            val x = response.get
            if (x.write && !x.error) put(memory, x.address, x.data, x.mask.toInt)
            if (x.victimValid && x.victimDirty) putLine(memory, x.victimAddress, x.victimData)
            transaction = None
        }
        val signature = Seq(
            dut.io.l2.req.bits.paddr,
            dut.io.l2.req.bits.data,
            dut.io.l2.req.bits.mask,
            dut.io.l2.req.bits.size,
            dut.io.l2.req.bits.victimLine,
            dut.io.l2.req.bits.victimData
        ).map(_.peek().litValue) ++
            Seq(
                dut.io.l2.req.bits.write.peek().litValue,
                dut.io.l2.req.bits.uncache.peek().litValue,
                dut.io.l2.req.bits.victimValid.peek().litValue,
                dut.io.l2.req.bits.victimDirty.peek().litValue
            )
        heldLower.filter(_ => cancel.isEmpty).foreach(x =>
            assert(dut.io.l2.req.valid.peek().litToBoolean && x == signature, s"unstable lower request $cycles")
        )
        heldLower = None
        if (dut.io.l2.req.valid.peek().litToBoolean) {
            if (dut.io.l2.req.ready.peek().litToBoolean) {
                assert(transaction.isEmpty, s"multiple lower transactions $cycles")
                val address = dut.io.l2.req.bits.paddr.peek().litValue.toLong
                val write = dut.io.l2.req.bits.write.peek().litToBoolean
                val size = dut.io.l2.req.bits.size.peek().litValue.toInt
                val io = dut.io.l2.req.bits.uncache.peek().litToBoolean
                val victimValid = dut.io.l2.req.bits.victimValid.peek().litToBoolean
                val victimDirty = dut.io.l2.req.bits.victimDirty.peek().litToBoolean
                if (!write && !io) assert((address & (lineBytes - 1)) == 0)
                val data = if (write) dut.io.l2.req.bits.data.peek().litValue
                else bytes(memory, address, if (io) 1 << size else lineBytes)
                transaction = Some(Transaction(
                    0,
                    address,
                    write,
                    data,
                    dut.io.l2.req.bits.mask.peek().litValue,
                    victimValid,
                    dut.io.l2.req.bits.victimLine.peek().litValue.toLong << l1Offset,
                    dut.io.l2.req.bits.victimData.peek().litValue,
                    victimDirty,
                    dirtyResponses.remove(address & ~(lineBytes - 1).toLong),
                    cycles + latency + (if (randomStalls) random.nextInt(5) else 0),
                    errors(address)
                ))
                if (write || (victimValid && victimDirty)) writes += 1
                if (victimValid && victimDirty) dirtyVictims += 1
                if (victimValid && victimDirty) {
                    dirtyVictimAddresses += dut.io.l2.req.bits.victimLine.peek().litValue.toLong << l1Offset
                }
                if (victimValid && !victimDirty) cleanVictims += 1
                if (!write) reads += 1
            } else if (cancel.isEmpty) heldLower = Some(signature)
        }
        val acceptedStore = write.nonEmpty && dut.io.store.req.ready.peek().litToBoolean
        if (acceptedStore) {
            assert(store.isEmpty)
            store = write
            if (storeException(s) == 0) put(reference, s.address, s.data, s.mask)
        }
        val acceptedDriven = (0 until 2).map(i =>
            cancel.isEmpty && drivenInputs(i).nonEmpty && dut.io.load(i).req.ready.peek().litToBoolean
        )
        if (acceptedDriven.forall(identity)) pairs += 1
        for (i <- 0 until 2 if acceptedDriven(i)) {
            val x = drivenInputs(i).get
            val f = forward.getOrElse(x.id, Forward())
            val fault = if (x.exception != 0) x.exception
            else if (!aligned(x.address, x.mtype & 3)) 4
            else if (errors(if (x.uncache) x.address else x.address & ~(lineBytes - 1).toLong)) 5 else 0
            val retry = fault == 0 && (if (x.uncache) !x.authorized else f.blocked)
            assert(!expected.contains((i, x.id)))
            expected((i, x.id)) = Expected(x, value(x, f), fault, retry, cycles)
            if (presentingReplay(i)) replay(i).dequeue()
            else reservedIds -= x.id
        }
        val accepted = inputs.indices.map(i => acceptedDriven(i) && !presentingReplay(i))
        dut.clock.step()
        cycles += 1
        accepted :+ acceptedStore
    }
    def issue(a: Option[Load] = None, b: Option[Load] = None, write: Option[Store] = None): Unit = {
        var pending = Seq(a, b)
        var w = write
        var n = 0
        while ((pending.exists(_.nonEmpty) || w.nonEmpty) && n < 600) {
            val accepted = tick(pending, w)
            pending = pending.zip(accepted).map { case (x, fire) => if (fire) None else x }
            if (accepted(2)) w = None
            n += 1
        }
        assert(n < 600, s"request stalled $cycles")
    }
    def drain(): Unit = {
        var n = 0
        while (
            (expected.nonEmpty || replay.exists(_.nonEmpty) || transaction.nonEmpty || store.nonEmpty ||
                !dut.io.idle.peek().litToBoolean) && n < 1000
        ) {
            tick()
            n += 1
        }
        assert(n < 1000, s"drain stalled $cycles pending=$expected")
        for (_ <- 0 until 6) tick()
    }
    def maintain(invalidate: Boolean): Unit = {
        maintenanceRequest = true
        maintenanceInvalidate = invalidate
        var n = 0
        while (!dut.io.maintenance.done.peek().litToBoolean && n < 4000) {
            tick()
            n += 1
        }
        assert(n < 4000, s"cache maintenance stalled $cycles")
        maintenanceRequest = false
        tick()
        drain()
    }
}

class DCacheSpec extends AnyFreeSpec with ChiselSim {
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
    ) {
        s"dual DCache $name: data, ports, miss, forwarding, fixed WB and cancellation" in {
            simulate(new DCache(backend)) { dut =>
                val d = new DCacheDriver(dut)
                import d._
                tick()
                // Coalesce cached reads only, then sustain two hits per cycle at the original latency.
                issue(Some(load(0x1000)), Some(load(0x100c)))
                drain()
                assert(reads == 1)
                val start = latencies.size
                val pairStart = pairs
                for (_ <- 0 until 24) {
                    val fires = tick(Seq(Some(load(0x1000)), Some(load(0x1004))))
                    assert(fires.take(2).forall(identity))
                }
                drain()
                assert(pairs - pairStart == 24)
                assert(latencies.drop(start).forall(_ == 3))
                // Different misses in the same set must use replacement state after the first install.
                val before = reads
                issue(Some(load(0x2000)), Some(load(0x2200)))
                drain()
                assert(reads == before + 2)
                issue(Some(load(0x2004)), Some(load(0x2204)))
                drain()
                assert(reads == before + 2)
                issue(Some(load(0x100000000L)), Some(load(0x200000000L)))
                drain()
                // All byte masks, including four independently supplied store bytes and subword offsets.
                for (mask <- 0 until 16) {
                    val x = load(0x3000 + mask * lineBytes)
                    forward(x.id) = Forward(BigInt("44332211", 16), mask)
                    val r = reads
                    issue(Some(x))
                    drain()
                    if (mask == 15) assert(reads == r)
                    forward.remove(x.id)
                }
                for (offset <- 0 until 4; unsigned <- Seq(0, 4)) {
                    val x = load(0x3000 + offset, unsigned)
                    forward(x.id) = Forward(BigInt("80ff7f01", 16), 15)
                    issue(None, Some(x))
                    drain()
                    forward.remove(x.id)
                }
                for (offset <- Seq(0, 2); unsigned <- Seq(1, 5)) {
                    val x = load(0x3000 + offset, unsigned)
                    forward(x.id) = Forward(BigInt("80ff7f01", 16), 15)
                    issue(Some(x))
                    drain()
                    forward.remove(x.id)
                }
                val blocked = load(0x6000)
                forward(blocked.id) = Forward(blocked = true)
                val blockedReads = reads
                issue(Some(blocked))
                drain()
                assert(reads == blockedReads)
                forward.remove(blocked.id)
                // Registered request buffers accept both loads while the committed store takes RAM B.
                issue(Some(load(0x4000)), Some(load(0x4020)))
                drain()
                lowerReady = false
                storeReady = false
                val w = Store(0x4000, BigInt("aabbccdd", 16), id = 91)
                val first = tick(Seq(Some(load(0x4020)), Some(load(0x4024))), Some(w))
                assert(first(2) && first.take(2).forall(identity))
                val second = tick(Seq(Some(load(0x4020)), Some(load(0x4024))))
                assert(second.take(2).exists(identity))
                val third = tick(Seq(Some(load(0x4020)), Some(load(0x4024))))
                assert(third.take(2).exists(identity))
                for (_ <- 0 until 8) tick()
                lowerReady = true
                for (_ <- 0 until 20) tick()
                storeReady = true
                drain()
                issue(Some(load(0x4000)))
                drain()
                for (mask <- 1 until 16) {
                    issue(write =
                        Some(Store(
                            0x4080,
                            BigInt(mask) * BigInt("11223344", 16) & BigInt("ffffffff", 16),
                            mask,
                            id = mask
                        ))
                    )
                    issue(Some(load(0x4080)), Some(load(0x4000)))
                    drain()
                }
                // WB captures forwarding once; later SB removal cannot change its bytes.
                issue(Some(load(0x5000)), Some(load(0x5020)))
                drain()
                val snap = load(0x5000)
                forward(snap.id) = Forward(BigInt("88776655", 16), 15)
                issue(Some(snap), Some(load(0x5020)))
                for (_ <- 0 until 2) tick()
                forward.remove(snap.id)
                issue(Some(load(0x5004)), Some(load(0x5024)))
                drain()
                // Accepted younger S2 requests survive two serial misses and reread after installation.
                issue(Some(load(0x7000)), Some(load(0x7200)))
                issue(Some(load(0x7004)), Some(load(0x7204)))
                drain()
                // Unauthorized IO and faults never initiate a lower operation, even with complete forwarding.
                val noRead = reads
                val fault = load(0x8000, exception = 13)
                forward(fault.id) = Forward(0, 15)
                issue(Some(fault), Some(load(0x9000, uncache = true)))
                issue(Some(load(0x8001)), Some(load(0x8002, 1)))
                drain()
                forward.remove(fault.id)
                assert(reads == noRead + 1) // The aligned halfword is an ordinary cache miss.
                // Repeated IO reads at one address are distinct side effects, including byte/halfword offsets.
                for (size <- Seq(0, 1, 2)) {
                    val addr = 0xa000L + (if (size == 0) 3 else if (size == 1) 2 else 0)
                    val n = reads
                    issue(
                        Some(load(addr, size | 4, uncache = true, authorized = true)),
                        Some(load(addr, size | 4, uncache = true, authorized = true))
                    )
                    drain()
                    assert(reads == n + 2)
                }
                // Keep accepted lower identity until its late response has been drained after cancellation.
                latency = 25
                issue(Some(load(0xb000)), Some(load(0xb004)))
                var guard = 0
                while (transaction.isEmpty && guard < 40) { tick(); guard += 1 }
                assert(transaction.nonEmpty)
                tick(cancel = Some(8))
                drain()
                val cancelledReads = reads
                issue(Some(load(0xb000)))
                drain()
                assert(reads == cancelledReads + 1)
                // A cancelled acquisition must still install a dirty line transferred out of exclusive L2.
                val cancelledDirty = 0xb200L
                dirtyResponses += cancelledDirty
                issue(Some(load(cancelledDirty)))
                guard = 0
                while (transaction.isEmpty && guard < 40) { tick(); guard += 1 }
                assert(transaction.nonEmpty)
                tick(cancel = Some(10))
                drain()
                val dirtyCancelReads = reads
                issue(Some(load(cancelledDirty)))
                drain()
                assert(reads == dirtyCancelReads, "cancelled dirty L2 transfer was not retained in DCache")
                latency = 6
                issue(Some(load(0xb000)), Some(load(0xb004)))
                for (_ <- 0 until 2) tick()
                tick(cancel = Some(9))
                drain()
                // Read/write errors, malformed accesses and a fresh read after a failed cached write.
                errors += 0xc000L
                issue(Some(load(0xc000)))
                drain()
                errors.clear()
                issue(Some(load(0xc000)))
                drain()
                errors += 0xc200L
                issue(write = Some(Store(0xc200, 0x76543210, id = 78, expectedException = 7)))
                drain()
                errors.clear()
                val failedStoreMissReads = reads
                issue(Some(load(0xc200)))
                drain()
                assert(reads == failedStoreMissReads + 1)
                errors += 0xc000L
                issue(write = Some(Store(0xc000, 0x12345678, id = 77)))
                drain()
                errors.clear()
                issue(Some(load(0xc000)))
                drain()
                issue(write = Some(Store(0xd003, 0x55, mask = 1, size = 0, uncache = true)))
                drain()
                issue(write = Some(Store(0xd003, BigInt("55000000", 16), mask = 8, size = 0, uncache = true)))
                drain()
                // A cache hit snapshot remains valid when its companion miss evicts that way.
                issue(Some(load(0x10000)), Some(load(0x10200)))
                drain()
                issue(Some(load(0x10004)), Some(load(0x10400)))
                issue(Some(load(0x10008)), Some(load(0x10404)))
                drain()
                // Overlay stores before, on, and after the lower read response, including store misses.
                for ((delta, j) <- Seq(-6, -2, 0, 1).zipWithIndex) {
                    latency = 15
                    val addr = 0x11008L + j * 512
                    val x = load(addr)
                    val w = Store(addr, BigInt("fedcba98", 16), 5, id = 120 + j)
                    forward(x.id) = Forward(w.data, w.mask)
                    issue(Some(x))
                    var guard = 0
                    while (transaction.isEmpty && guard < 40) { tick(); guard += 1 }
                    assert(transaction.nonEmpty)
                    val when = transaction.get.due + delta
                    while (cycles < when) tick()
                    issue(write = Some(w))
                    drain()
                    forward.remove(x.id)
                    val readsBefore = reads
                    issue(Some(load(addr)))
                    drain()
                    assert(reads == readsBefore)
                }
                latency = 6
                // Fill both lanes and modify a nearby RAM row through the store port.
                issue(Some(load(0x13000)), Some(load(0x13020)))
                drain()
                issue(Some(load(0x13000)), Some(load(0x13020)))
                val held = load(0x13004)
                forward(held.id) = Forward(BigInt("cafebabe", 16), 15)
                issue(Some(held), Some(load(0x13024)))
                for (_ <- 0 until 2) tick()
                forward.remove(held.id)
                issue(Some(load(0x13008)), Some(load(0x13028)))
                issue(write = Some(Store(0x1300c, BigInt("10203040", 16), id = 140)))
                drain()
                // Cancel all pending same-line requests before external acceptance.
                lowerReady = false
                issue(Some(load(0x14000)), Some(load(0x14004)))
                for (_ <- 0 until 8) tick()
                tick(cancel = Some(31))
                lowerReady = true
                drain()
                lowerReady = false
                issue(Some(load(0x15000)))
                for (_ <- 0 until 8) tick()
                tick(cancel = Some(33))
                lowerReady = true
                drain()
                val refetch = reads
                issue(Some(load(0x15000)))
                drain()
                assert(reads == refetch + 1)
                val io = load(0x16003, 4, uncache = true, authorized = true)
                issue(Some(io))
                tick(cancel = Some(34))
                drain()
                // Delayed external forwarding is captured once in S3.
                val delayed = load(0x17000)
                forward(delayed.id) = Forward(BigInt("76543210", 16), 15, available = cycles + 12)
                val noFetch = reads
                issue(Some(delayed), Some(load(0x13000)))
                drain()
                forward.remove(delayed.id)
                assert(reads <= noFetch + 1)
                // A cached store hit completes locally even when lower memory would reject that address.
                val badAddress = 0x18008L
                val dependent = load(badAddress)
                forward(dependent.id) = Forward(BigInt("deadbeef", 16), 5)
                latency = 15
                issue(Some(dependent))
                var errorGuard = 0
                while (transaction.isEmpty && errorGuard < 40) { tick(); errorGuard += 1 }
                assert(transaction.nonEmpty)
                errors += badAddress
                issue(write = Some(Store(badAddress, BigInt("deadbeef", 16), 5, id = 150)))
                drain()
                errors.clear()
                forward.remove(dependent.id)
                val failedOverlayReads = reads
                issue(Some(load(badAddress)))
                drain()
                assert(reads == failedOverlayReads)
                latency = 6
                // A dirty L2 hit transfers writeback ownership into the installed L1 line.
                val dirtyTransfer = 0x1a000L
                dirtyResponses += dirtyTransfer
                issue(Some(load(dirtyTransfer)))
                drain()
                issue(Some(load(dirtyTransfer + setStride)))
                drain()
                issue(Some(load(dirtyTransfer + 2 * setStride)))
                drain()
                assert(dirtyVictimAddresses.contains(dirtyTransfer))
                // Random traffic mixes replacement, 34-bit tags, widths, writes and independent backpressure.
                randomStalls = true
                for (round <- 0 until 500) {
                    if (round % 4 == 0) {
                        drain()
                        val addr = 0xe000L + random.nextInt(64) * 4
                        issue(write = Some(Store(addr, BigInt(32, random), 1 + random.nextInt(15), id = round & 255)))
                    }
                    def request(): Load = {
                        val size = random.nextInt(3)
                        val base = (if (random.nextInt(8) == 0) 0x100000000L else 0L) + 0xe000L
                        load(
                            base + (random.nextInt(4096) & ~((1 << size) - 1)),
                            size | (if (random.nextBoolean()) 4 else 0)
                        )
                    }
                    issue(Some(request()), Some(request()))
                }
                drain()
                info(
                    s"$name: cycles=$cycles loads=$loads stores=$stores lowerReads=$reads lowerWrites=$writes dualAccepts=$pairs cancelled=$cancelled; warm-hit latency=3, throughput=2/cycle"
                )
            }
        }
    }
}
