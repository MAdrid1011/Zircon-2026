import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{FrontendParams, ICacheParams}

import scala.collection.mutable
import scala.util.Random

/** Architectural words and transaction identities are checked independently of Cache arrays/FSM. */
class ICacheSpec extends AnyFreeSpec with ChiselSim {
    import ICacheTestModel._

    for ((width, sets, lineBytes) <- Seq((1, 2, 4), (2, 4, 32), (4, 16, 32), (8, 2, 64))) {
        s"ICache preserves line data, slot alignment, replacement and hit throughput ($width/$sets/$lineBytes)" in {
            simulate(new ICache(FrontendParams(fetchWidth = width, observe = true), ICacheParams(sets, lineBytes))) {
                d =>
                    val e = new Environment(d, width, lineBytes)
                    val base = BigInt("80000000", 16)
                    val span = sets * lineBytes
                    e.fetch(base)
                    e.fetch(base + span)
                    val before = e.requests.size
                    e.fetch(base)
                    assert(e.requests.size == before)
                    e.fetch(base + 2 * span)
                    e.fetch(base)
                    assert(e.requests.size == before + 1, "recently used way was incorrectly evicted")
                    e.fetch(base + span)
                    assert(e.requests.size == before + 2, "victim did not miss")
                    for (offset <- 0 until lineBytes by 4) e.fetch(base + offset)
                    val stream = (0 until 24).map(i => e.add(base + (i % (lineBytes / 4)) * 4))
                    val lowerBefore = e.requests.size
                    val hitsBefore = d.io.dbg.get.hit.peek().litValue
                    val visitsBefore = d.io.dbg.get.visit.peek().litValue
                    e.drain()
                    assert(e.requests.size == lowerBefore, "warm hit stream accessed lower memory")
                    d.io.dbg.get.hit.expect(hitsBefore + 24)
                    d.io.dbg.get.visit.expect(visitsBefore + 24)
                    assert(stream.forall(t => e.completed(t) - e.accepted(t) == 2), "hit latency changed")
                    assert(
                        stream.sliding(2).forall(ts => e.completed(ts(1)) == e.completed(ts(0)) + 1),
                        "hit throughput below one block per cycle"
                    )
                    e.fetch(base, high = 1)
                    e.fetch(base, high = 2)
                    val highBefore = e.requests.size
                    e.fetch(base, high = 1)
                    assert(e.requests.size == highBefore, "34-bit physical tag did not hit")
                    assert(e.requests.exists(_.pa >= (BigInt(1) << 33)))
                    val uncachedBefore = e.requests.size
                    e.fetch(base + 3 * span, uncached = true)
                    e.fetch(base + 3 * span + (width - 1) * 4, uncached = true)
                    assert(e.requests.size == uncachedBefore + width + 1)
                    e.fetch(base + 3 * span)
                    assert(e.requests.size == uncachedBefore + width + 2, "uncached words allocated a line")
                    info(
                        s"width=$width sets=$sets lineBytes=$lineBytes cycles=${e.cycle} completed=${e.completed.size} lowerReads=${e.requests.size}; hit latency=2, interval=1"
                    )
            }
        }
    }

    "ICache drains canceled transactions, holds responses and reports translation/lower errors" in {
        simulate(new ICache(FrontendParams(observe = true))) { d =>
            val e = new Environment(d, 4, 32)
            val base = BigInt("80004000", 16)
            // Cancel after S2 detects miss, before the miss FSM starts its lower request.
            val early = e.add(base)
            e.until(d.io.miss.peek().litToBoolean)
            e.flush()
            e.drain()
            assert(!e.completed.contains(early) && e.requests.isEmpty)

            // A request already held at the lower interface survives flush unchanged.
            e.lowerReady = false
            val held = e.add(base + 32)
            e.until(d.io.l2.request.valid.peek().litToBoolean)
            e.step()
            e.flush()
            for (_ <- 0 until 5) e.step()
            e.lowerReady = true
            e.drain()
            assert(!e.completed.contains(held))
            val reads = e.requests.size
            e.fetch(base + 32)
            assert(e.requests.size == reads + 1, "canceled refill installed a line")

            // Cancel with a read outstanding, then immediately queue the same PC under a new token.
            val pending = e.add(base + 64)
            e.until(e.pending.exists(_.token == pending))
            e.flush()
            val fresh = e.add(base + 64)
            e.drain()
            assert(!e.completed.contains(pending) && e.completed.contains(fresh))

            // The cycle after the lower response is the original refill phase.
            val cancelInstall = e.add(base + 96)
            val returned = e.lowerReturns
            e.until(e.lowerReturns > returned)
            e.flush()
            e.drain()
            assert(!e.completed.contains(cancelInstall))
            val beforeInstall = e.requests.size
            e.fetch(base + 96)
            assert(e.requests.size == beforeInstall + 1)

            // Output backpressure holds the return buffer while a younger translation waits.
            e.outputReady = false
            e.add(base + 128)
            e.add(base + 160, delay = 3)
            e.until(d.io.pp.response.valid.peek().litToBoolean)
            for (_ <- 0 until 17) e.step()
            e.outputReady = true
            e.drain()

            // Flush a held hit and drain its younger delayed translation.
            e.outputReady = false
            val hit = e.add(base + 160)
            e.add(base + 192, delay = 23)
            e.until(d.io.pp.response.valid.peek().litToBoolean)
            e.step()
            e.flush()
            e.outputReady = true
            e.fetch(base + 192)
            assert(!e.completed.contains(hit))

            // A redirect may accept its new PF request on the same edge that cancels a held hit.
            e.outputReady = false
            val oldHit = e.add(base + 192)
            e.until(d.io.pp.response.valid.peek().litToBoolean)
            e.step()
            val redirect = e.add(base + 192)
            val redirectCycle = e.cycle
            e.step(flush = true)
            e.outputReady = true
            e.drain()
            assert(e.accepted(redirect) == redirectCycle && e.completed.contains(redirect))
            assert(!e.completed.contains(oldHit))

            val beforeFault = e.requests.size
            e.fetch(base + 224, fault = true)
            e.fetch(base + 225)
            assert(e.requests.size == beforeFault, "faulting fetch accessed lower memory")
            e.failing += base + 256
            e.fetch(base + 260)
            e.failing.clear()
            val afterError = e.requests.size
            e.fetch(base + 260)
            assert(e.requests.size == afterError + 1, "failed lower read installed a line")

            // Uncached words occupy their aligned slots and never allocate a cache line.
            e.failing += base + 300
            val uncached = e.requests.size
            e.fetch(base + 292, uncached = true)
            assert(e.requests.size == uncached + 3)
            e.failing.clear()
            e.fetch(base + 292, uncached = true)
            assert(e.requests.size == uncached + 6)
            e.fetch(base + 292)
            assert(e.requests.size == uncached + 7)

            val uncachedCancel = e.add(base + 320, uncached = true)
            e.until(e.pending.exists(_.token == uncachedCancel))
            val uncachedBefore = e.requests.size
            e.flush()
            e.drain()
            assert(e.requests.size == uncachedBefore, "flush issued further uncached words")
            assert(!e.completed.contains(uncachedCancel))
            info(
                s"directed cycles=${e.cycle} completed=${e.completed.size} canceled=${e.canceled} lowerReads=${e.requests.size}"
            )
        }
    }

    "ICache handles randomized translation, lower-memory and consumer delays with repeated flushes" in {
        simulate(new ICache(FrontendParams(observe = true), ICacheParams(sets = 4))) { d =>
            val e = new Environment(d, 4, 32)
            val random = new Random(20260911L)
            val base = BigInt("90000000", 16)
            for (cycle <- 0 until 5000) {
                e.lowerReady = random.nextInt(4) != 0
                e.outputReady = random.nextInt(3) != 0
                e.lowerDelay = 2 + random.nextInt(19)
                if (e.queued.size < 3 && random.nextBoolean()) {
                    e.add(
                        base + random.nextInt(128) * 4,
                        high = random.nextInt(3),
                        uncached = random.nextInt(13) == 0,
                        fault = random.nextInt(29) == 0,
                        delay = random.nextInt(9)
                    )
                }
                if (cycle % 137 == 91) e.flush() else e.step()
            }
            e.lowerReady = true
            e.outputReady = true
            e.drain()
            assert(e.completed.size > 150 && e.canceled > 30 && e.requests.size > 100)
            assert(e.lowerReturns == e.requests.size, "an accepted lower request was not drained")
            info(
                s"random cycles=${e.cycle} completed=${e.completed.size} canceled=${e.canceled} lowerReads=${e.requests.size}"
            )
        }
    }
}
