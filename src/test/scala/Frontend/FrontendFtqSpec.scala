import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{FrontendParams, ICacheParams}

/** Queue/recovery tests use the real ICache and delay only translation or lower memory. */
class FrontendFtqSpec extends AnyFreeSpec with ChiselSim {
    private val base = BigInt("80000000", 16)
    private def params(ftq: Int = 4, fq: Int = 2, width: Int = 4): FrontendParams = FrontendParams(
        fetchWidth = width,
        fastBtbSets = 2,
        btbSets = 2,
        phtSets = 2,
        tageSets = 2,
        tcSets = 2,
        rasDepth = 2,
        ftqDepth = ftq,
        fqDepth = fq,
        observe = true
    )

    for ((ftq, fq) <- Seq((2, 4), (4, 2))) {
        s"real ICache preserves older packets and applies PD repair once with ${if (ftq == 2) "FTQ" else "FQ"} full" in {
            val p = params(ftq, fq)
            val c = ICacheParams()
            simulate(new FrontendTestHarness(p, c)) { d =>
                val e = new FrontendMemoryModel(d, p, c)
                e.rom(base + 32) = BigInt("0200006f", 16) // The third block jumps to PC+32.
                e.until(e.repairs.nonEmpty)
                val token = e.repairs.keys.head
                assert(e.accepted(token).pc == base + 32 && !e.stages(2)(token))
                d.io.observe.get.ftqUsed.expect(2)
                d.io.observe.get.ftqBlocked.expect(ftq == 2)
                for (_ <- 0 until 20) e.step()
                val history = d.io.observe.get.history.peek().litValue
                val visits = d.io.observe.get.icache.visit.peek().litValue
                for (_ <- 0 until 64) {
                    e.step()
                    d.io.observe.get.history.expect(history)
                    d.io.observe.get.icache.visit.expect(visits)
                    assert(e.repairs(token) == 1 && !e.stages(2)(token))
                }
                val a = e.handoff()
                assert(a.pc == base && a.index == 0)
                e.retire(a)
                val b = e.handoff()
                val third = e.handoff()
                assert(b.pc == base + 16 && b.index == 1)
                assert(third.token == token && third.index == (if (ftq == 2) 0 else 2))
                assert(third.mask == 1 && third.nextPc == base + 64)
                e.retire(b, flush = true)
                e.step()
                d.io.observe.get.ftqUsed.expect(0)
                e.finish()
            }
        }
    }

    for (phase <- Seq("translation", "held-lower", "outstanding", "install")) {
        s"global recovery drains $phase and retries the same PC under a fresh token" in {
            val p = params()
            val c = ICacheParams()
            simulate(new FrontendTestHarness(p, c)) { d =>
                val e = new FrontendMemoryModel(d, p, c)
                if (phase == "translation") e.translationDelay = 127
                if (phase == "held-lower") e.lowerReady = false
                e.lowerDelay = 31
                e.until(e.accepted.nonEmpty)
                val old = e.accepted.keys.head
                phase match {
                    case "translation" => ()
                    case "held-lower" => e.until(e.heldLower.nonEmpty)
                    case "outstanding" => e.until(e.pending.nonEmpty)
                    case "install" => e.until(e.returns > 0)
                }
                e.redirect = Some(base)
                e.step()
                e.redirect = None
                e.lowerReady = true
                e.translationDelay = 0
                e.until(e.accepted.values.exists(f => f.pc == base && f.token != old))
                val fresh = e.accepted.values.find(f => f.pc == base && f.token != old).get.token
                val packet = e.handoff()
                assert(packet.token == fresh && packet.pc == base)
                assert(!e.completed(old) && !e.stages(2)(old))
                assert(e.lowerReads.count(_.token == fresh) == 1, "canceled miss installed a cache line")
                e.retire(packet)
                e.finish()
                if (phase == "translation") assert(e.staleTranslations > 0)
            }
        }
    }

    for (width <- Seq(1, 2, 4, 8); fault <- Seq("translation", "line", "uncached-word")) {
        s"integrated predecode preserves $fault faults and operand validity at width $width" in {
            val p = params(width = width)
            val c = ICacheParams()
            simulate(new FrontendTestHarness(p, c)) { d =>
                val e = new FrontendMemoryModel(d, p, c)
                for (i <- 0 until width) e.rom(base + i * 4) = BigInt("00108093", 16) // ADDI x1,x1,1.
                if (fault == "translation") e.faulting += base
                if (fault == "line") e.lowerErrors += base
                if (fault == "uncached-word") {
                    e.uncached += base
                    e.lowerErrors += base + (width - 1) * 4
                }
                e.until(e.accepted.nonEmpty)
                val packet = e.handoff()
                val expected = if (fault == "uncached-word") BigInt(1) << (width - 1) else (BigInt(1) << width) - 1
                assert(packet.faults == expected && packet.mask == (BigInt(1) << width) - 1)
                assert(packet.taken == 0 && packet.nextPc == base + width * 4)
                val expectedReads = if (fault == "translation") 0 else if (fault == "line") 1 else width
                assert(e.lowerReads.count(_.token == packet.token) == expectedReads)
                e.finish()
            }
        }
    }
}
