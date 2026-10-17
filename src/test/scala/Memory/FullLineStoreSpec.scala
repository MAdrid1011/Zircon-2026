// SPDX-License-Identifier: MPL-2.0
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.L2CacheParams

class FullLineStoreSpec extends AnyFreeSpec with ChiselSim {
    "full and masked Stores preserve payload and ordering through miss-unit backpressure and recovery" in {
        simulate(new DCacheMissUnit()) { dut =>
            val bytes = 64
            val all = (BigInt(1) << bytes) - 1
            val replacement = (0 until bytes).foldLeft(BigInt(0))((v,b) => v | BigInt(b+1) << (8*b))
            val original = (BigInt(1) << (bytes*8)) - 1
            dut.io.allocate.valid.poke(false)
            dut.io.claim.valid.poke(false)
            dut.io.flush.poke(false)
            dut.io.memory.req.ready.poke(false)
            dut.io.memory.rsp.valid.poke(false)
            dut.io.memory.rsp.bits.data.poke(original)
            dut.io.memory.rsp.bits.dirty.poke(false)
            dut.io.memory.rsp.bits.error.poke(false)
            dut.io.install.ready.poke(false)
            dut.io.complete.ready.poke(false)
            dut.clock.step()
            for ((mask, failed) <- Seq((all,false), (BigInt("aa"*8,16),false), (all,true))) {
                dut.io.memory.rsp.bits.error.poke(failed)
                for (field <- dut.io.allocate.bits.elements.values) field match {
                    case b: Bool => b.poke(false)
                    case u: UInt => u.poke(0)
                }
                dut.io.allocate.bits.store.poke(true)
                dut.io.allocate.bits.wide.poke(true)
                dut.io.allocate.bits.paddr.poke(0x80004000L)
                dut.io.allocate.bits.way.poke(1)
                dut.io.allocate.bits.storeLine.poke(replacement)
                dut.io.allocate.bits.storeLineMask.poke(mask)
                dut.io.allocate.bits.victimValid.poke(true)
                dut.io.allocate.bits.victimLine.poke(0x80008000L >> 6)
                dut.io.allocate.bits.victimData.poke(original)
                dut.io.allocate.bits.victimDirty.poke(true)
                dut.io.allocate.valid.poke(true)
                dut.io.allocate.ready.expect(true)
                dut.clock.step()
                dut.io.allocate.valid.poke(false)
                for (_ <- 0 until 3) {
                    dut.io.memory.req.valid.expect(true)
                    dut.io.memory.req.bits.write.expect(mask == all)
                    dut.io.memory.req.bits.uncache.expect(false)
                    dut.io.memory.req.bits.victimData.expect(original)
                    dut.io.memory.req.bits.victimDirty.expect(true)
                    dut.io.flush.poke(true)
                    dut.clock.step()
                }
                dut.io.flush.poke(false)
                dut.io.memory.req.ready.poke(true)
                dut.clock.step()
                dut.io.memory.req.ready.poke(false)
                dut.io.memory.rsp.valid.poke(true)
                dut.io.memory.rsp.ready.expect(true)
                dut.clock.step()
                dut.io.memory.rsp.valid.poke(false)
                val expected = (0 until bytes).foldLeft(BigInt(0)) { (v,b) =>
                    val byte = if (mask.testBit(b)) b+1 else 255
                    v | BigInt(byte) << (8*b)
                }
                for (_ <- 0 until 3) {
                    dut.io.install.valid.expect(!failed)
                    if (!failed) {
                        dut.io.install.bits.data.expect(expected)
                        dut.io.install.bits.dirty.expect(true)
                    }
                    dut.clock.step()
                }
                if (!failed) {
                    dut.io.install.ready.poke(true)
                    dut.clock.step()
                    dut.io.install.ready.poke(false)
                }
                dut.io.complete.valid.expect(true)
                dut.io.complete.bits.store.expect(true)
                dut.io.complete.bits.preserve.expect(true)
                dut.io.complete.bits.storeResponse.exception.expect(if (failed) 7 else 0)
                dut.clock.step(2)
                dut.io.complete.ready.poke(true)
                dut.clock.step()
                dut.io.complete.ready.poke(false)
            }
        }
    }

    "L2 allocates full-line Stores without target reads and preserves clean and dirty victims" in {
        simulate(new L2Cache(DualPortRamBackend.Registers, p = L2CacheParams(sets=16))) { dut =>
            val d = new L2CacheDriver(dut)
            import d._
            tick()
            val first = cycle
            assert(!dcache(0x80010000L,write=true)._2)
            assert(cycle-first<=5 && reads==0 && writes==0)
            assert(!lastDcacheDirty)
            val clean = 0x80018040L
            assert(!dcache(0x80010080L,Some((clean,line(clean),false)),write=true)._2)
            assert(reads==0 && writes==0)
            assert(dcache(clean)._1==line(clean) && reads==0)
            val target = 0x80020000L
            val old = replaceWord(line(target),0,0x11223344L)
            dut.io.dcache.req.bits.victimOnly.poke(true)
            assert(!dcache(0,Some((target,old,true)))._2)
            dut.io.dcache.req.bits.victimOnly.poke(false)
            val victim = 0x80020100L
            val victimData = replaceWord(line(victim),4,0x55667788L)
            val before = reads
            assert(!dcache(target,Some((victim,victimData,true)),write=true)._2)
            assert(!lastDcacheDirty && reads==before)
            assert(dcache(victim)._1==victimData && reads==before)
            // The discarded target no longer carries its previous dirty bytes.
            assert(dcache(target)._1==line(target) && reads==before+1)
            // A later clean copy cannot overwrite a new committed dirty version.
            val newest = replaceWord(line(target),0,0xaabbccddL)
            dut.io.dcache.req.bits.victimOnly.poke(true)
            dcache(0,Some((target,newest,true)))
            dcache(0,Some((target,old,false)))
            dut.io.dcache.req.bits.victimOnly.poke(false)
            val stable = reads
            assert(dcache(target)._1==newest && lastDcacheDirty && reads==stable)
        }
    }

    "full-line allocation propagates dirty victim writeback errors before replacing it" in {
        val params = L2CacheParams(sets=16)
        simulate(new L2Cache(DualPortRamBackend.Registers, p=params)) { dut =>
            val d = new L2CacheDriver(dut)
            import d._
            tick()
            val resident = (0 until 4).map(i => 0x80030000L+i*params.sets*params.lineBytes)
            dut.io.dcache.req.bits.victimOnly.poke(true)
            for (address <- resident) dcache(0,Some((address,line(address),true)))
            dut.io.dcache.req.bits.victimOnly.poke(false)
            failing ++= resident
            val target = 0x80040000L
            val victim = 0x80031000L
            val value = replaceWord(line(victim),0,0x12345678L)
            assert(dcache(target,Some((victim,value,true)),write=true)._2)
            assert(reads==0 && writes==1)
            failing --= resident
            assert(!dcache(target,Some((victim,value,true)),write=true)._2)
            assert(reads==0 && writes==2)
            assert(dcache(victim)._1==value && lastDcacheDirty && reads==0)
        }
    }
}
