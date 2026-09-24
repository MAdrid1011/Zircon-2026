import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable

class L2CacheDriver(dut: L2Cache) extends chisel3.simulator.PeekPokeAPI {
    private case class Pending(data: BigInt, error: Boolean, due: Int)

    val memory = mutable.Map.empty[Long, Int]
    val failing = mutable.Set.empty[Long]
    var cycle = 0
    var reads = 0
    var writes = 0
    var lastDcacheDirty = false
    private var pending: Option[Pending] = None
    var heldMemoryRequest: Option[Seq[BigInt]] = None

    def lowerResponseDue: Boolean = pending.exists(_.due <= cycle)

    private val lineBytes = dut.p.lineBytes
    private val lineMask = (BigInt(1) << dut.p.lineBits) - 1

    def initial(address: Long): Int = ((address * 53 + (address >> 8) * 29 + 0x6d) & 255).toInt
    def bytes(address: Long, count: Int): BigInt = (0 until count).foldLeft(BigInt(0)) { (value, byte) =>
        value | (BigInt(memory.getOrElse(address + byte, initial(address + byte))) << (8 * byte))
    }
    def line(address: Long): BigInt = bytes(address & ~(lineBytes - 1).toLong, lineBytes)
    def putLine(address: Long, data: BigInt): Unit = {
        val base = address & ~(lineBytes - 1).toLong
        for (byte <- 0 until lineBytes) memory(base + byte) = ((data >> (8 * byte)) & 255).toInt
    }
    def putMasked(address: Long, data: BigInt, mask: BigInt): Unit = {
        val base = if ((mask >> 4) != 0) address & ~(lineBytes - 1).toLong else address & ~3L
        val count = if ((mask >> 4) != 0) lineBytes else 4
        for (byte <- 0 until count if mask.testBit(byte)) {
            memory(base + byte) = ((data >> (8 * byte)) & 255).toInt
        }
    }
    def replaceWord(original: BigInt, byteOffset: Int, data: BigInt): BigInt = {
        val mask = ((BigInt(1) << 32) - 1) << (8 * byteOffset)
        ((original & ~mask) | ((data & 0xffffffffL) << (8 * byteOffset))) & lineMask
    }

    def pteValue(pte: Sv32Pte): BigInt =
        (pte.ppn.peek().litValue << 10) |
            (pte.dirty.peek().litValue << 7) |
            (pte.accessed.peek().litValue << 6) |
            (pte.global.peek().litValue << 5) |
            (pte.user.peek().litValue << 4) |
            (pte.execute.peek().litValue << 3) |
            (pte.write.peek().litValue << 2) |
            (pte.read.peek().litValue << 1) |
            pte.valid.peek().litValue

    def expectPte(pte: Sv32Pte, expected: BigInt): Unit =
        assert(pteValue(pte) == (expected & ~BigInt(0x300)))

    private def clearClients(): Unit = {
        dut.io.dcache.req.valid.poke(false)
        dut.io.dcache.req.bits.paddr.poke(0)
        dut.io.dcache.req.bits.write.poke(false)
        dut.io.dcache.req.bits.uncache.poke(false)
        dut.io.dcache.req.bits.size.poke(0)
        dut.io.dcache.req.bits.data.poke(0)
        dut.io.dcache.req.bits.mask.poke(0)
        dut.io.dcache.req.bits.victimValid.poke(false)
        dut.io.dcache.req.bits.victimLine.poke(0)
        dut.io.dcache.req.bits.victimData.poke(0)
        dut.io.dcache.req.bits.victimDirty.poke(false)
        dut.io.icache.request.valid.poke(false)
        dut.io.icache.request.bits.paddr.poke(0)
        dut.io.icache.request.bits.uncache.poke(false)
        dut.io.icache.request.bits.victimValid.poke(false)
        dut.io.icache.request.bits.victimLine.poke(0)
        dut.io.icache.request.bits.victimData.poke(0)
        dut.io.iptw.req.valid.poke(false)
        dut.io.iptw.req.bits.paddr.poke(0)
        dut.io.dptw.req.valid.poke(false)
        dut.io.dptw.req.bits.paddr.poke(0)
    }

    clearClients()
    dut.io.dcache.rsp.ready.poke(true)
    dut.io.icache.response.ready.poke(true)
    dut.io.iptw.rsp.ready.poke(true)
    dut.io.dptw.rsp.ready.poke(true)

    def tick(): Unit = {
        assert(cycle < 100000, "L2Cache test timed out")
        val memoryReady = cycle % 4 != 1
        dut.io.memory.req.ready.poke(memoryReady)
        val returning = pending.filter(_.due <= cycle)
        dut.io.memory.rsp.valid.poke(returning.nonEmpty)
        dut.io.memory.rsp.bits.data.poke(returning.map(_.data).getOrElse(BigInt(0)))
        dut.io.memory.rsp.bits.error.poke(returning.exists(_.error))

        val signature = Seq(
            dut.io.memory.req.bits.paddr.peek().litValue,
            dut.io.memory.req.bits.data.peek().litValue,
            dut.io.memory.req.bits.mask.peek().litValue,
            dut.io.memory.req.bits.size.peek().litValue,
            dut.io.memory.req.bits.write.peek().litValue,
            dut.io.memory.req.bits.uncache.peek().litValue
        )
        if (dut.io.memory.req.valid.peek().litToBoolean) {
            heldMemoryRequest.foreach(old => assert(old == signature, s"memory request changed at cycle $cycle"))
            if (memoryReady) {
                assert(pending.isEmpty, "L2Cache issued more than one lower transaction")
                val address = signature(0).toLong
                val data = signature(1)
                val mask = signature(2)
                val write = signature(4) != 0
                val uncache = signature(5) != 0
                val error = failing(address)
                if (write) {
                    writes += 1
                    if (!error && uncache) putMasked(address, data, mask)
                    else if (!error) putLine(address, data)
                } else {
                    reads += 1
                }
                val returned = if (write) BigInt(0) else if (uncache) bytes(address, 4) else line(address)
                pending = Some(Pending(returned, error, cycle + 3))
                heldMemoryRequest = None
            } else {
                heldMemoryRequest = Some(signature)
            }
        } else {
            assert(heldMemoryRequest.isEmpty, s"memory request valid was withdrawn at cycle $cycle")
        }
        val responseAccepted = returning.nonEmpty && dut.io.memory.rsp.ready.peek().litToBoolean
        dut.clock.step()
        if (responseAccepted) pending = None
        cycle += 1
    }

    private def waitFor(condition: => Boolean): Unit = {
        while (!condition) tick()
    }

    def dcache(
        address: Long,
        victim: Option[(Long, BigInt, Boolean)] = None,
        uncache: Boolean = false,
        write: Boolean = false,
        data: BigInt = 0,
        mask: BigInt = 0,
        size: Int = 2,
    ): (BigInt, Boolean) = {
        dut.io.dcache.req.valid.poke(true)
        dut.io.dcache.req.bits.paddr.poke(address)
        dut.io.dcache.req.bits.write.poke(write)
        dut.io.dcache.req.bits.uncache.poke(uncache)
        dut.io.dcache.req.bits.size.poke(size)
        dut.io.dcache.req.bits.data.poke(data)
        dut.io.dcache.req.bits.mask.poke(mask)
        dut.io.dcache.req.bits.victimValid.poke(victim.nonEmpty)
        dut.io.dcache.req.bits.victimLine.poke(victim.map(_._1 >> dut.p.offsetBits).getOrElse(0L))
        dut.io.dcache.req.bits.victimData.poke(victim.map(_._2).getOrElse(BigInt(0)))
        dut.io.dcache.req.bits.victimDirty.poke(victim.exists(_._3))
        waitFor(dut.io.dcache.req.ready.peek().litToBoolean)
        tick()
        dut.io.dcache.req.valid.poke(false)
        waitFor(dut.io.dcache.rsp.valid.peek().litToBoolean)
        val result = dut.io.dcache.rsp.bits.data.peek().litValue
        lastDcacheDirty = dut.io.dcache.rsp.bits.dirty.peek().litToBoolean
        val error = dut.io.dcache.rsp.bits.error.peek().litToBoolean
        tick()
        result -> error
    }

    def icache(
        address: Long,
        victim: Option[(Long, BigInt)] = None,
        uncache: Boolean = false,
    ): (BigInt, Boolean) = {
        dut.io.icache.request.valid.poke(true)
        dut.io.icache.request.bits.paddr.poke(address)
        dut.io.icache.request.bits.uncache.poke(uncache)
        dut.io.icache.request.bits.victimValid.poke(victim.nonEmpty)
        dut.io.icache.request.bits.victimLine.poke(victim.map(_._1 >> dut.p.offsetBits).getOrElse(0L))
        dut.io.icache.request.bits.victimData.poke(victim.map(_._2).getOrElse(BigInt(0)))
        waitFor(dut.io.icache.request.ready.peek().litToBoolean)
        tick()
        dut.io.icache.request.valid.poke(false)
        waitFor(dut.io.icache.response.valid.peek().litToBoolean)
        val result = dut.io.icache.response.bits.data.peek().litValue
        val error = dut.io.icache.response.bits.error.peek().litToBoolean
        tick()
        result -> error
    }

    def iptw(address: Long): (BigInt, Boolean) = {
        dut.io.iptw.req.valid.poke(true)
        dut.io.iptw.req.bits.paddr.poke(address)
        waitFor(dut.io.iptw.req.ready.peek().litToBoolean)
        tick()
        dut.io.iptw.req.valid.poke(false)
        waitFor(dut.io.iptw.rsp.valid.peek().litToBoolean)
        val result = pteValue(dut.io.iptw.rsp.bits.pte)
        val error = dut.io.iptw.rsp.bits.error.peek().litToBoolean
        tick()
        result -> error
    }

    def dptw(address: Long): (BigInt, Boolean) = {
        dut.io.dptw.req.valid.poke(true)
        dut.io.dptw.req.bits.paddr.poke(address)
        waitFor(dut.io.dptw.req.ready.peek().litToBoolean)
        tick()
        dut.io.dptw.req.valid.poke(false)
        waitFor(dut.io.dptw.rsp.valid.peek().litToBoolean)
        val result = pteValue(dut.io.dptw.rsp.bits.pte)
        val error = dut.io.dptw.rsp.bits.error.peek().litToBoolean
        tick()
        result -> error
    }

    def drain(): Unit = {
        waitFor(dut.io.idle.peek().litToBoolean && pending.isEmpty)
        for (_ <- 0 until 2) tick()
    }
}

class L2CacheSpec extends AnyFreeSpec with ChiselSim {
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
        s"$name: biased-exclusive moves, dirty writeback, instruction borrowing, PTW and uncached traffic" in {
            val params = ZirconConfig.L2CacheParams(sets = 16)
            simulate(new L2Cache(backend, p = params)) { dut =>
                val d = new L2CacheDriver(dut)
                import d._
                tick()

                val setStride = params.sets * params.lineBytes
                val a = 0x10000L
                val b = a + setStride
                val c = a + 2 * setStride
                val e = a + 3 * setStride
                val f = a + 4 * setStride
                val g = a + 5 * setStride
                val targets = (0 until 8).map(i => 0x20040L + i * setStride)

                val (aFromMemory, aError) = dcache(a)
                assert(!aError && aFromMemory == line(a) && reads == 1)
                val (target0, target0Error) = dcache(targets(0), Some((a, aFromMemory, false)))
                assert(!target0Error && target0 == line(targets(0)) && reads == 2)
                val beforeL2Hit = reads
                val (aFromL2, aL2Error) = icache(a)
                assert(!aL2Error && aFromL2 == line(a) && reads == beforeL2Hit, "clean victim did not move through L2")

                val dirtyTransferAddress = 0x18080L
                val dirtyTransfer = replaceWord(line(dirtyTransferAddress), 8, BigInt("71c04a2e", 16))
                dcache(targets(7), Some((dirtyTransferAddress, dirtyTransfer, true)))
                val dirtyTransferReads = reads
                val (transferred, transferError) = dcache(dirtyTransferAddress)
                assert(!transferError && transferred == dirtyTransfer && reads == dirtyTransferReads)
                assert(lastDcacheDirty, "DCache did not receive dirty ownership from L2")

                val dirtyB = replaceWord(line(b), 12, BigInt("a15c817f", 16))
                dcache(targets(1), Some((b, dirtyB, true)))
                dcache(targets(2), Some((c, line(c), false)))
                dcache(targets(3), Some((e, line(e), false)))
                dcache(targets(4), Some((f, line(f), false)))
                val writesBeforeEviction = writes
                dcache(targets(5), Some((g, line(g), false)))
                assert(writes == writesBeforeEviction + 1, "dirty L2 victim was not written back")
                assert(line(b) == dirtyB, "dirty L2 writeback lost modified bytes")

                val ptwReads = reads
                val (pte, pteError) = iptw(g + 4)
                assert(
                    !pteError && pte == (bytes(g + 4, 4) & ~BigInt(0x300)) && reads == ptwReads,
                    "IPTW did not retain an L2 hit"
                )
                val (gFromL2, gError) = icache(g)
                assert(!gError && gFromL2 == line(g) && reads == ptwReads, "ICache did not consume the retained line")
                icache(g)
                assert(reads == ptwReads + 1, "clean L2 hit was not removed after transfer to L1")

                val i1 = 0x30040L
                val i2 = i1 + setStride
                val i3 = i1 + 2 * setStride
                icache(targets(0) + 0x40, Some((i1, line(i1))))
                icache(targets(1) + 0x40, Some((i2, line(i2))))
                icache(targets(2) + 0x40, Some((i3, line(i3))))
                val quotaReads = reads
                icache(i3)
                icache(i1)
                icache(i2)
                assert(reads == quotaReads, "instruction victims did not borrow invalid ways")

                val instructionVictims = (0 until 5).map(i => 0x38040L + i * setStride)
                for ((address, index) <- instructionVictims.zipWithIndex) {
                    icache(targets(index) + 0x40, Some((address, line(address))))
                }
                val fullSetReads = reads
                for (address <- instructionVictims) {
                    icache(address)
                }
                assert(reads == fullSetReads + 1, "instruction quota did not apply after the set became full")

                val uncached = 0x41004L
                val uncachedData = BigInt("88776655", 16)
                val (_, storeError) = dcache(
                    uncached,
                    uncache = true,
                    write = true,
                    data = uncachedData,
                    mask = 15,
                    size = 2
                )
                assert(!storeError && bytes(uncached, 4) == uncachedData)
                val (uncachedRead, uncachedError) = dptw(uncached)
                assert(!uncachedError && uncachedRead == (uncachedData & ~BigInt(0x300)))

                val failed = 0x50000L
                failing += failed
                val (_, firstError) = dcache(failed)
                assert(firstError)
                failing.clear()
                val (recovered, secondError) = dcache(failed)
                assert(!secondError && recovered == line(failed))

                val retainedVictim = 0x61000L
                val failedTarget = 0x62020L
                val retainedDirty = replaceWord(line(retainedVictim), 4, BigInt("3a4b5c6d", 16))
                failing += (failedTarget & ~(params.lineBytes - 1).toLong)
                val (_, failedTargetError) = dcache(
                    failedTarget,
                    Some((retainedVictim, retainedDirty, true))
                )
                assert(failedTargetError)
                failing.clear()
                val failedVictimReads = reads
                val (victimAfterFailure, victimAfterFailureError) = icache(retainedVictim)
                assert(!victimAfterFailureError && victimAfterFailure == line(retainedVictim))
                assert(reads == failedVictimReads + 1, "L2 accepted a victim before the target fill succeeded")

                drain()
                info(s"$name: cycles=$cycle lowerReads=$reads lowerWrites=$writes; all returned bytes checked")
            }
        }

        s"$name: instruction lookup recovers after the victim engine uses its RAM port" in {
            val params = ZirconConfig.L2CacheParams(sets = 16)
            simulate(new L2Cache(backend, p = params)) { dut =>
                val d = new L2CacheDriver(dut)
                import d._
                tick()

                // The target and alias share a tag but occupy different sets. A stale alias
                // RAM output must not be accepted when the victim engine releases port A.
                val target = 0x20060L
                val alias = 0x20180L
                val source = 0x202a0L
                val backgroundVictim = 0x20980L
                dcache(0x30000L, Some((target, line(target), false)))
                for (way <- 0 until params.ways) {
                    val resident = alias + way * 0x200L
                    dcache(0x31020L + way * 0x20L, Some((resident, line(resident), true)))
                }
                dcache(0x32040L, Some((source, line(source), false)))
                val setupReads = reads
                val (targetPte, targetPteError) = iptw(target)
                assert(!targetPteError && targetPte == (bytes(target, 4) & ~BigInt(0x300)))
                assert(reads == setupReads, "the target was not resident before the victim-port overlap")

                // Occupy the shared engine through a D-side victim installation. The
                // victim uses another set so the alias remains available on port A.
                dut.io.dcache.req.valid.poke(true)
                dut.io.dcache.req.bits.paddr.poke(0x40000L)
                dut.io.dcache.req.bits.write.poke(false)
                dut.io.dcache.req.bits.uncache.poke(false)
                dut.io.dcache.req.bits.size.poke(2)
                dut.io.dcache.req.bits.data.poke(0)
                dut.io.dcache.req.bits.mask.poke(0)
                dut.io.dcache.req.bits.victimValid.poke(true)
                dut.io.dcache.req.bits.victimLine.poke(0x404e0L >> params.offsetBits)
                dut.io.dcache.req.bits.victimData.poke(line(0x404e0L))
                dut.io.dcache.req.bits.victimDirty.poke(false)
                while (!dut.io.dcache.req.ready.peek().litToBoolean) tick()
                tick()
                dut.io.dcache.req.valid.poke(false)

                dut.io.icache.request.valid.poke(true)
                dut.io.icache.request.bits.paddr.poke(source)
                dut.io.icache.request.bits.uncache.poke(false)
                dut.io.icache.request.bits.victimValid.poke(true)
                dut.io.icache.request.bits.victimLine.poke(backgroundVictim >> params.offsetBits)
                dut.io.icache.request.bits.victimData.poke(line(backgroundVictim))
                while (!dut.io.icache.request.ready.peek().litToBoolean) tick()
                tick()
                dut.io.icache.request.valid.poke(false)
                while (!dut.io.icache.response.valid.peek().litToBoolean) tick()
                dut.io.icache.response.bits.data.expect(line(source))
                tick()

                // Advance the returning D miss through victim-read into victim-lookup.
                // A target accepted there remains in S1 through the following install.
                while (!lowerResponseDue) tick()
                tick()
                tick()
                val readsBeforeTarget = reads
                dut.io.icache.request.valid.poke(true)
                dut.io.icache.request.bits.paddr.poke(target)
                dut.io.icache.request.bits.victimValid.poke(false)
                assert(dut.io.icache.request.ready.peek().litToBoolean)
                tick()
                dut.io.icache.request.valid.poke(false)
                while (!dut.io.icache.response.valid.peek().litToBoolean) tick()
                dut.io.icache.response.bits.data.expect(line(target))
                assert(!dut.io.icache.response.bits.error.peek().litToBoolean)
                tick()
                assert(reads == readsBeforeTarget, "the resident target unexpectedly missed in L2")
                drain()
            }
        }
    }

    "dual channels accept I/D and I/D PTWs together, prioritize L1 requests, and hold responses" in {
        simulate(new L2Cache(DualPortRamBackend.Registers)) { dut =>
            val d = new L2CacheDriver(dut)
            import d._
            tick()

            def driveD(address: Long): Unit = {
                dut.io.dcache.req.valid.poke(true)
                dut.io.dcache.req.bits.paddr.poke(address)
                dut.io.dcache.req.bits.write.poke(false)
                dut.io.dcache.req.bits.uncache.poke(false)
                dut.io.dcache.req.bits.size.poke(2)
                dut.io.dcache.req.bits.data.poke(0)
                dut.io.dcache.req.bits.mask.poke(0)
                dut.io.dcache.req.bits.victimValid.poke(false)
                dut.io.dcache.req.bits.victimLine.poke(0)
                dut.io.dcache.req.bits.victimData.poke(0)
                dut.io.dcache.req.bits.victimDirty.poke(false)
            }

            def driveI(address: Long): Unit = {
                dut.io.icache.request.valid.poke(true)
                dut.io.icache.request.bits.paddr.poke(address)
                dut.io.icache.request.bits.uncache.poke(false)
                dut.io.icache.request.bits.victimValid.poke(false)
                dut.io.icache.request.bits.victimLine.poke(0)
                dut.io.icache.request.bits.victimData.poke(0)
            }

            def driveIPtw(address: Long): Unit = {
                dut.io.iptw.req.valid.poke(true)
                dut.io.iptw.req.bits.paddr.poke(address)
            }

            def driveDPtw(address: Long): Unit = {
                dut.io.dptw.req.valid.poke(true)
                dut.io.dptw.req.bits.paddr.poke(address)
            }

            val dAddress = 0x71000L
            val iAddress = 0x72020L
            dcache(0x73040L, Some((dAddress, line(dAddress), false)))
            icache(0x74060L, Some((iAddress, line(iAddress))))
            val hitReads = reads

            driveD(dAddress)
            driveI(iAddress)
            assert(dut.io.dcache.req.ready.peek().litToBoolean)
            assert(dut.io.icache.request.ready.peek().litToBoolean)
            val issueCycle = cycle
            tick()
            dut.io.dcache.req.valid.poke(false)
            dut.io.icache.request.valid.poke(false)

            var dResponseCycle = -1
            var iResponseCycle = -1
            while (dResponseCycle < 0 || iResponseCycle < 0) {
                if (dut.io.dcache.rsp.valid.peek().litToBoolean) {
                    dut.io.dcache.rsp.bits.data.expect(line(dAddress))
                    dResponseCycle = cycle
                }
                if (dut.io.icache.response.valid.peek().litToBoolean) {
                    dut.io.icache.response.bits.data.expect(line(iAddress))
                    iResponseCycle = cycle
                }
                tick()
            }
            assert(dResponseCycle == iResponseCycle)
            assert(dResponseCycle - issueCycle == 3)
            assert(reads == hitReads, "simultaneous I/D L2 hits unexpectedly accessed lower memory")
            drain()

            val dBurst = (0 until 3).map(i => 0x80000L + i * dut.p.lineBytes)
            val iBurst = (0 until 3).map(i => 0x81080L + i * dut.p.lineBytes)
            for (i <- dBurst.indices) {
                dcache(0x90000L + i * dut.p.lineBytes, Some((dBurst(i), line(dBurst(i)), false)))
                icache(
                    0x91080L + i * dut.p.lineBytes,
                    Some((iBurst(i), line(iBurst(i))))
                )
            }
            val burstReads = reads
            val burstIssueCycle = cycle
            for (i <- dBurst.indices) {
                driveD(dBurst(i))
                driveI(iBurst(i))
                assert(dut.io.dcache.req.ready.peek().litToBoolean)
                assert(dut.io.icache.request.ready.peek().litToBoolean)
                tick()
            }
            dut.io.dcache.req.valid.poke(false)
            dut.io.icache.request.valid.poke(false)
            for (i <- dBurst.indices) {
                assert(dut.io.dcache.rsp.valid.peek().litToBoolean)
                assert(dut.io.icache.response.valid.peek().litToBoolean)
                dut.io.dcache.rsp.bits.data.expect(line(dBurst(i)))
                dut.io.icache.response.bits.data.expect(line(iBurst(i)))
                assert(cycle == burstIssueCycle + 3 + i)
                tick()
            }
            assert(reads == burstReads, "pipelined I/D L2 hits unexpectedly accessed lower memory")
            drain()

            val priorityD = 0x75080L
            val priorityPtw = 0x760c0L
            dcache(0x770a0L, Some((priorityD, line(priorityD), false)))
            dcache(0x780e0L, Some((priorityPtw, line(priorityPtw), false)))
            val priorityReads = reads
            driveD(priorityD)
            driveDPtw(priorityPtw + 4)
            assert(dut.io.dcache.req.ready.peek().litToBoolean)
            assert(!dut.io.dptw.req.ready.peek().litToBoolean)
            tick()
            assert(
                !dut.io.dcache.req.ready.peek().litToBoolean,
                "DPTW was starved by a sustained DCache request"
            )
            assert(dut.io.dptw.req.ready.peek().litToBoolean)
            tick()
            dut.io.dcache.req.valid.poke(false)
            dut.io.dptw.req.valid.poke(false)

            var sawD = false
            var sawPtw = false
            while (!sawD || !sawPtw) {
                if (dut.io.dcache.rsp.valid.peek().litToBoolean) {
                    dut.io.dcache.rsp.bits.data.expect(line(priorityD))
                    sawD = true
                }
                if (dut.io.dptw.rsp.valid.peek().litToBoolean) {
                    expectPte(dut.io.dptw.rsp.bits.pte, bytes(priorityPtw + 4, 4))
                    sawPtw = true
                }
                tick()
            }
            assert(reads == priorityReads, "DCache/PTW L2 hits unexpectedly accessed lower memory")
            drain()

            val priorityI = 0x7a020L
            val priorityIPtw = 0x7b060L
            dcache(0x7c0a0L, Some((priorityI, line(priorityI), false)))
            dcache(0x7d0e0L, Some((priorityIPtw, line(priorityIPtw), false)))
            val iPriorityReads = reads
            driveI(priorityI)
            driveIPtw(priorityIPtw + 8)
            assert(dut.io.icache.request.ready.peek().litToBoolean)
            assert(!dut.io.iptw.req.ready.peek().litToBoolean)
            tick()
            assert(
                !dut.io.icache.request.ready.peek().litToBoolean,
                "IPTW was starved by a sustained ICache request"
            )
            assert(dut.io.iptw.req.ready.peek().litToBoolean)
            tick()
            dut.io.icache.request.valid.poke(false)
            dut.io.iptw.req.valid.poke(false)

            var sawI = false
            var sawIPtw = false
            while (!sawI || !sawIPtw) {
                if (dut.io.icache.response.valid.peek().litToBoolean) {
                    dut.io.icache.response.bits.data.expect(line(priorityI))
                    sawI = true
                }
                if (dut.io.iptw.rsp.valid.peek().litToBoolean) {
                    expectPte(dut.io.iptw.rsp.bits.pte, bytes(priorityIPtw + 8, 4))
                    sawIPtw = true
                }
                tick()
            }
            assert(reads == iPriorityReads, "ICache/IPTW L2 hits unexpectedly accessed lower memory")
            drain()

            val simultaneousIPtw = 0x7e100L
            val simultaneousDPtw = 0x7f140L
            dcache(0x82000L, Some((simultaneousIPtw, line(simultaneousIPtw), false)))
            dcache(0x83020L, Some((simultaneousDPtw, line(simultaneousDPtw), false)))
            val simultaneousPtwReads = reads
            driveIPtw(simultaneousIPtw + 12)
            driveDPtw(simultaneousDPtw + 16)
            assert(dut.io.iptw.req.ready.peek().litToBoolean)
            assert(dut.io.dptw.req.ready.peek().litToBoolean)
            val ptwIssueCycle = cycle
            tick()
            dut.io.iptw.req.valid.poke(false)
            dut.io.dptw.req.valid.poke(false)
            while (
                !dut.io.iptw.rsp.valid.peek().litToBoolean ||
                !dut.io.dptw.rsp.valid.peek().litToBoolean
            ) tick()
            expectPte(dut.io.iptw.rsp.bits.pte, bytes(simultaneousIPtw + 12, 4))
            expectPte(dut.io.dptw.rsp.bits.pte, bytes(simultaneousDPtw + 16, 4))
            assert(cycle - ptwIssueCycle == 3)
            tick()
            assert(reads == simultaneousPtwReads, "simultaneous I/D PTW hits accessed lower memory")
            drain()

            val missIPtw = 0x184024L
            val missDPtw = 0x194068L
            val simultaneousMissReads = reads
            driveIPtw(missIPtw)
            driveDPtw(missDPtw)
            assert(dut.io.iptw.req.ready.peek().litToBoolean)
            assert(dut.io.dptw.req.ready.peek().litToBoolean)
            tick()
            dut.io.iptw.req.valid.poke(false)
            dut.io.dptw.req.valid.poke(false)
            var sawIPtwMiss = false
            var sawDPtwMiss = false
            while (!sawIPtwMiss || !sawDPtwMiss) {
                if (dut.io.iptw.rsp.valid.peek().litToBoolean) {
                    expectPte(dut.io.iptw.rsp.bits.pte, bytes(missIPtw, 4))
                    sawIPtwMiss = true
                }
                if (dut.io.dptw.rsp.valid.peek().litToBoolean) {
                    expectPte(dut.io.dptw.rsp.bits.pte, bytes(missDPtw, 4))
                    sawDPtwMiss = true
                }
                tick()
            }
            assert(
                reads == simultaneousMissReads + 2,
                "shared miss engine lost a simultaneous PTW request"
            )
            drain()

            dut.io.dcache.req.valid.poke(true)
            dut.io.dcache.req.bits.paddr.poke(0x79000L)
            dut.io.dcache.req.bits.write.poke(false)
            dut.io.dcache.req.bits.uncache.poke(false)
            dut.io.dcache.req.bits.size.poke(2)
            dut.io.dcache.req.bits.data.poke(0)
            dut.io.dcache.req.bits.mask.poke(0)
            dut.io.dcache.req.bits.victimValid.poke(false)
            dut.io.dcache.req.bits.victimLine.poke(0)
            dut.io.dcache.req.bits.victimData.poke(0)
            dut.io.dcache.req.bits.victimDirty.poke(false)
            dut.io.dcache.rsp.ready.poke(false)
            while (!dut.io.dcache.req.ready.peek().litToBoolean) tick()
            tick()
            dut.io.dcache.req.valid.poke(false)
            while (!dut.io.dcache.rsp.valid.peek().litToBoolean) tick()
            val held = Seq(
                dut.io.dcache.rsp.bits.data.peek().litValue,
                dut.io.dcache.rsp.bits.dirty.peek().litValue,
                dut.io.dcache.rsp.bits.error.peek().litValue
            )
            for (_ <- 0 until 7) {
                assert(dut.io.dcache.rsp.valid.peek().litToBoolean)
                assert(held == Seq(
                    dut.io.dcache.rsp.bits.data.peek().litValue,
                    dut.io.dcache.rsp.bits.dirty.peek().litValue,
                    dut.io.dcache.rsp.bits.error.peek().litValue
                ))
                tick()
            }
            dut.io.dcache.rsp.ready.poke(true)
            tick()
            drain()
        }
    }
}
