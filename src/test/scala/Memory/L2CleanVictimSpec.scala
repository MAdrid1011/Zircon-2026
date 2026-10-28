// SPDX-License-Identifier: MPL-2.0
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.L2CacheParams

class L2ConcurrentVictimHarness extends L2Cache(DualPortRamBackend.Registers, p = L2CacheParams(sets = 16)) {
    val incomingHitDuringVictimLookup = IO(Output(Bool()))
    incomingHitDuringVictimLookup := engineState === engineVictimLookup && !engineSourceI &&
        iS2Advance && !iFast && iHit.orR &&
        iS2.paddr(p.offsetBits + p.indexBits - 1, p.offsetBits) ===
            engineVictimAddress(p.offsetBits + p.indexBits - 1, p.offsetBits)
}

class L2CleanVictimSpec extends AnyFreeSpec with ChiselSim {
    "a same-edge opposite-channel slow hit protects a newly installed dirty victim" in {
        simulate(new L2ConcurrentVictimHarness) { dut =>
            val d = new L2CacheDriver(dut)
            import d._
            tick()
            val target = 0x50000L
            val stride = dut.p.sets * dut.p.lineBytes
            // All four ways are data-owned; the first inserted line is PLRU.
            for (address <- (0 until 4).map(i => target + i * stride) :+ (target + 64)) {
                dut.io.dcache.req.bits.victimOnly.poke(true)
                dcache(0, Some((address, line(address), false)))
                dut.io.dcache.req.bits.victimOnly.poke(false)
            }
            val dirtyVictim = target + 4 * stride
            val dirtyData = replaceWord(line(dirtyVictim), 0, BigInt("c18ab590", 16))
            val demand = 0x91080L
            lowerLatency = 24
            dut.io.dcache.rsp.ready.poke(false)
            dut.io.dcache.req.valid.poke(true)
            dut.io.dcache.req.bits.paddr.poke(demand)
            dut.io.dcache.req.bits.victimValid.poke(true)
            dut.io.dcache.req.bits.victimLine.poke(dirtyVictim >> dut.p.offsetBits)
            dut.io.dcache.req.bits.victimData.poke(dirtyData)
            dut.io.dcache.req.bits.victimDirty.poke(true)
            assert(dut.io.dcache.req.ready.peek().litToBoolean)
            tick()
            dut.io.dcache.req.valid.poke(false)
            // Occupy the clean-victim buffer while the D engine waits below L2.
            assert(icache(target + 64, Some((0x720c0L, line(0x720c0L))))._1 == line(target + 64))
            while (!lowerResponseDue) tick()
            var covered = false
            beforeStep = () => {
                covered ||= dut.incomingHitDuringVictimLookup.peek().litToBoolean
            }
            // S1 captures with the lower response, so S2 enters S3 precisely
            // when D chooses a replacement for the dirty victim.
            val (instructionData, instructionError) = icache(target, Some((0x73100L, line(0x73100L))))
            assert(covered, "The regression must exercise the same-edge ownership transfer")
            assert(!instructionError && instructionData == line(target))
            dut.io.dcache.rsp.valid.expect(true)
            dut.io.dcache.rsp.bits.data.expect(line(demand))
            dut.io.dcache.rsp.ready.poke(true)
            tick()
            drain()
            val (data, error) = dcache(dirtyVictim)
            assert(!error && data == dirtyData, "The opposite-channel consume discarded the dirty victim")
        }
    }

    "clean data victims return demand responses before insertion and retain data-way allocation" in {
        val params = L2CacheParams(sets = 16)
        simulate(new L2Cache(DualPortRamBackend.Registers, p = params)) { dut =>
            val d = new L2CacheDriver(dut)
            import d._
            tick()
            val victims = (0 until 4).map(i => 0x50000L + i * params.sets * params.lineBytes)
            for ((victim, i) <- victims.zipWithIndex) {
                val target = 0x81040L + i * params.lineBytes
                val before = reads
                val (data, error) = dcache(target, Some((victim, line(victim), false)), drainVictim = false)
                assert(!error && data == line(target) && reads == before + 1)
                assert(!dut.io.idle.peek().litToBoolean,
                    "Demand completion must precede the clean victim's background insertion")
                drain()
            }
            val before = reads
            for (victim <- victims) {
                val (data, error) = dcache(victim)
                assert(!error && data == line(victim))
                drain()
            }
            assert(reads == before, "Data victims must use all four data ways, rather than the instruction quota")
            // Place a clean target in L2, then exchange it with another clean D victim.
            val target = 0x91800L
            dut.io.dcache.req.bits.victimOnly.poke(true)
            dcache(0, Some((target, line(target), false)))
            dut.io.dcache.req.bits.victimOnly.poke(false)
            drain()
            val first = cycle
            val (data, error) = dcache(target, Some((0x91c40L, line(0x91c40L), false)), drainVictim = false)
            assert(!error && data == line(target))
            assert(cycle - first <= 5, s"A warm target took ${cycle - first} cycles")
            assert(!dut.io.idle.peek().litToBoolean)
            drain()
            val warmReads = reads
            assert(dcache(0x91c40L)._1 == line(0x91c40L))
            assert(reads == warmReads)
            // A failed demand must preserve the original no-insertion ordering.
            val failed = 0xa0000L
            val retained = 0xa0440L
            failing += failed
            assert(dcache(failed, Some((retained, line(retained), false)))._2)
            drain(); failing -= failed
            val failedReads = reads
            assert(dcache(retained)._1 == line(retained))
            assert(reads == failedReads + 1)
        }
    }
}
