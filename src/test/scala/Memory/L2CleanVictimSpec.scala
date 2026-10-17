// SPDX-License-Identifier: MPL-2.0
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.L2CacheParams

class L2CleanVictimSpec extends AnyFreeSpec with ChiselSim {
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
