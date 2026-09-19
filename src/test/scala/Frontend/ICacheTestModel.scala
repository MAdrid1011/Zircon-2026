import chisel3._
import scala.collection.mutable

/** Shared protocol driver; expected instruction words depend only on physical memory. */
object ICacheTestModel {
    case class Fetch(
        token: BigInt,
        pc: BigInt,
        pa: BigInt,
        uncached: Boolean,
        fault: Boolean,
        delay: Int,
        errors: BigInt
    )
    case class Lower(token: BigInt, pa: BigInt, uncached: Boolean, due: Int, error: Boolean)

    class Environment(d: ICache, width: Int, lineBytes: Int) extends chisel3.simulator.PeekPokeAPI {
        val queued = mutable.Queue.empty[Fetch]
        val active = mutable.LinkedHashMap.empty[BigInt, Fetch]
        val translations = mutable.ArrayBuffer.empty[(Fetch, Int)]
        val history = mutable.Map.empty[BigInt, Fetch]
        val canceledTokens = mutable.Set.empty[BigInt]
        val nextWord = mutable.Map.empty[BigInt, Int]
        val returnedAt = mutable.Map.empty[BigInt, Int]
        val coverage = mutable.Map.empty[String, Long].withDefaultValue(0L)
        val requests = mutable.ArrayBuffer.empty[Lower]
        val accepted = mutable.Map.empty[BigInt, Int]
        val completed = mutable.Map.empty[BigInt, Int]
        val faults = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
        val failing = mutable.Set.empty[BigInt]
        var pending: Option[Lower] = None
        var cycle = 0
        var serial = BigInt(1)
        var lowerDelay = 9
        var lowerReady = true
        var outputReady = true
        var lowerReturns = 0
        var canceled = 0
        var heldOutput: Option[Seq[BigInt]] = None
        var heldLower: Option[Seq[BigInt]] = None
        var heldTranslation: Option[(Fetch, Int)] = None
        var outOfOrderTranslations = false
        var context = "baseline"
        var memorySalt = BigInt(0)
        val fullMask = (BigInt(1) << width) - 1
        private val u32 = (BigInt(1) << 32) - 1

        def word(address: BigInt): BigInt =
            ((address * BigInt("9e3779b1", 16)) ^ ((address >> 32) * BigInt("7f4a7c15", 16)) ^ BigInt("a53c6907", 16) ^
                memorySalt) &
                u32
        def cover(name: String): Unit = coverage(name) += 1
        def requireCoverage(names: String*): Unit =
            names.foreach(name => assert(coverage(name) > 0, s"$context: missing coverage $name"))
        def mask(f: Fetch): BigInt = fullMask & (fullMask << ((f.pc % (width * 4)).toInt / 4))
        def add(
            pc: BigInt,
            high: Int = 0,
            uncached: Boolean = false,
            fault: Boolean = false,
            delay: Int = 0,
            physical: Option[BigInt] = None,
            errors: BigInt = 0
        ): BigInt = {
            val pa = physical.getOrElse(pc | (BigInt(high) << 32))
            require(pc >= 0 && pc <= u32 && pa >= 0 && pa < (BigInt(1) << 34))
            require((pc & 4095) == (pa & 4095), "translation must preserve the page offset")
            require(!history.contains(serial), "token reused while its history is retained")
            val f = Fetch(serial, pc, pa, uncached, fault, delay, errors)
            serial = (serial + 1) & u32
            queued.enqueue(f)
            f.token
        }
        private def output: Seq[BigInt] = Seq(
            d.io.pp.response.bits.token.peek().litValue,
            d.io.pp.response.bits.mask.peek().litValue,
            d.io.pp.response.bits.fault.peek().litValue
        ) ++ (0 until width).map(i => d.io.pp.response.bits.inst(i).peek().litValue)
        private def lower: Seq[BigInt] = Seq(
            d.io.l2.request.bits.token.peek().litValue,
            d.io.l2.request.bits.paddr.peek().litValue,
            d.io.l2.request.bits.uncache.peek().litValue,
            d.io.l2.request.bits.victimValid.peek().litValue,
            d.io.l2.request.bits.victimLine.peek().litValue << Integer.numberOfTrailingZeros(lineBytes),
            d.io.l2.request.bits.victimData.peek().litValue
        )

        def step(flush: Boolean = false): Unit = {
            assert(cycle < 10000000, s"$context: ICache test timed out")
            d.io.flush.poke(flush)
            d.io.pp.response.ready.poke(outputReady)
            d.io.l2.request.ready.poke(lowerReady)
            d.io.pp.request.valid.poke(queued.nonEmpty)
            d.io.pp.request.bits.pc.poke(queued.headOption.map(_.pc).getOrElse(BigInt(0)))
            d.io.pp.request.bits.token.poke(queued.headOption.map(_.token).getOrElse(BigInt(0)))
            val eligible = if (outOfOrderTranslations) translations.find(_._2 <= cycle)
            else translations.headOption.filter(_._2 <= cycle)
            val translationItem = heldTranslation.orElse(eligible)
            val translation = translationItem.map(_._1)
            d.io.mmu.response.valid.poke(translation.nonEmpty)
            d.io.mmu.response.bits.token.poke(translation.map(_.token).getOrElse(BigInt(0)))
            d.io.mmu.response.bits.paddr.poke(translation.map(_.pa).getOrElse(BigInt(0)))
            d.io.mmu.response.bits.uncache.poke(translation.exists(_.uncached))
            d.io.mmu.response.bits.fault.poke(translation.exists(_.fault))
            val returning = pending.filter(_.due <= cycle)
            val data = returning.map { r =>
                if (r.uncached) word(r.pa)
                else (0 until lineBytes / 4).foldLeft(BigInt(0))((bits, i) => bits | (word(r.pa + 4 * i) << (32 * i)))
            }.getOrElse(BigInt(0))
            d.io.l2.response.valid.poke(returning.nonEmpty)
            d.io.l2.response.bits.token.poke(returning.map(_.token).getOrElse(BigInt(0)))
            d.io.l2.response.bits.data.poke(data)
            d.io.l2.response.bits.error.poke(returning.exists(_.error))

            if (flush) {
                cover("flush")
                if (heldOutput.nonEmpty) cover("flush_held_output")
                if (heldLower.nonEmpty) cover("flush_held_lower")
                if (pending.nonEmpty) cover("flush_outstanding_read")
                if (returning.nonEmpty) cover("flush_lower_response")
                if (translation.nonEmpty) cover("flush_translation_response")
                canceled += active.size
                canceledTokens ++= active.keys
                active.clear()
                heldOutput = None
                d.io.pp.response.valid.expect(false)
            }
            if (d.io.mmu.request.valid.peek().litToBoolean) {
                val token = d.io.mmu.request.bits.token.peek().litValue
                assert(active.contains(token), s"translation query has no accepted request: $token")
                d.io.mmu.request.bits.pc.expect(active(token).pc)
            }
            if (d.io.pp.response.valid.peek().litToBoolean) {
                val values = output
                heldOutput.foreach(old => assert(old == values, s"response changed under backpressure at $cycle"))
                val token = values.head
                assert(
                    active.headOption.exists(_._1 == token),
                    s"unexpected/out-of-order response $token at $cycle, active=${active.keys}"
                )
                val f = active(token)
                val expectedFault = if (f.fault || (f.pc & 3) != 0) mask(f) else faults(token) & mask(f)
                assert(values(1) == mask(f), s"incorrect slot mask for PC ${f.pc.toString(16)}")
                assert(values(2) == expectedFault, s"fault mismatch for token $token at $cycle")
                for (i <- 0 until width if mask(f).testBit(i) && !expectedFault.testBit(i)) {
                    val expected = word((f.pa & ~BigInt(width * 4 - 1)) + 4 * i)
                    assert(
                        values(i + 3) == expected,
                        s"data mismatch token=$token pc=${f.pc.toString(16)} slot=$i cycle=$cycle"
                    )
                }
                if (outputReady) {
                    assert(!completed.contains(token), s"duplicate response $token")
                    assert(!canceledTokens(token), s"canceled fetch $token completed")
                    completed(token) = cycle
                    active.remove(token)
                    heldOutput = None
                    cover("completed")
                    if (expectedFault != 0) cover("fault_response")
                } else {
                    cover("output_backpressure")
                    heldOutput = Some(values)
                }
            } else assert(heldOutput.isEmpty, s"response valid withdrawn at $cycle")

            if (d.io.l2.request.valid.peek().litToBoolean) {
                val values = lower
                heldLower.foreach(old => assert(old == values, s"lower request changed under backpressure at $cycle"))
                val token = values.head
                assert(history.contains(token), s"unowned lower request $token at $cycle")
                val f = history(token)
                assert(!f.fault && (f.pc & 3) == 0, "faulting fetch accessed lower memory")
                assert(active.contains(token) || heldLower.nonEmpty, "canceled fetch issued a new lower request")
                assert((values(2) != 0) == f.uncached, "lower memory attributes changed")
                val ordinal = nextWord.getOrElse(token, 0)
                val expectedAddress = if (f.uncached) f.pa + ordinal * 4 else f.pa & ~BigInt(lineBytes - 1)
                assert(values(1) == expectedAddress, s"$context: wrong lower address for $token at $cycle")
                assert(ordinal < (if (f.uncached) mask(f).bitCount else 1), "duplicate or excess lower read")
                if (lowerReady) {
                    val victimValid = values(3) != 0
                    assert(!victimValid || !f.uncached, "uncached request carried an ICache victim")
                    if (victimValid) {
                        val victimAddress = values(4)
                        val expectedVictim = (0 until lineBytes / 4).foldLeft(BigInt(0)) { (bits, i) =>
                            bits | (word(victimAddress + 4 * i) << (32 * i))
                        }
                        assert(victimAddress % lineBytes == 0, "misaligned ICache victim")
                        assert(values(5) == expectedVictim, s"incorrect ICache victim data at $cycle")
                        cover("clean_victim")
                    }
                    assert(pending.isEmpty, "more than one lower request is outstanding")
                    val error = if (f.uncached) f.errors.testBit((values(1) % (width * 4)).toInt / 4)
                    else f.errors != 0
                    val r = Lower(token, values(1), f.uncached, cycle + lowerDelay, error || failing(values(1)))
                    assert((r.pa % (if (r.uncached) 4 else lineBytes)) == 0, "misaligned lower request")
                    pending = Some(r)
                    requests += r
                    nextWord(token) = ordinal + 1
                    cover(if (f.uncached) "uncached_read" else "cached_read")
                    if (r.error) cover(if (f.uncached) "uncached_error" else "cached_error")
                    if (lowerDelay == 1) cover("one_cycle_lower")
                    if (lowerDelay >= 128) cover("long_lower_delay")
                    heldLower = None
                } else {
                    cover("lower_backpressure")
                    heldLower = Some(values)
                }
            } else assert(heldLower.isEmpty, s"lower valid withdrawn at $cycle")
            if (returning.nonEmpty && d.io.l2.response.ready.peek().litToBoolean) {
                val r = returning.get
                if (r.error) {
                    val bits = if (r.uncached) BigInt(1) << ((r.pa % (width * 4)).toInt / 4) else fullMask
                    faults(r.token) |= bits
                }
                pending = None
                returnedAt(r.token) = cycle
                lowerReturns += 1
                if (canceledTokens(r.token)) cover("canceled_read_drained")
            }
            if (translation.nonEmpty) {
                if (d.io.mmu.response.ready.peek().litToBoolean) {
                    translations.remove(translations.indexOf(translationItem.get))
                    heldTranslation = None
                    if (canceledTokens(translation.get.token)) cover("stale_translation_drained")
                } else {
                    heldTranslation = translationItem
                    cover("translation_backpressure")
                }
            }
            if (queued.nonEmpty && d.io.pp.request.ready.peek().litToBoolean) {
                val f = queued.dequeue()
                assert(!active.contains(f.token))
                active(f.token) = f
                history(f.token) = f
                accepted(f.token) = cycle
                translations += ((f, cycle + 1 + f.delay))
                cover("accepted")
                cover(s"pa_high_${f.pa >> 32}")
                cover(s"slot_${(f.pc % (width * 4)).toInt / 4}")
                if (flush) cover("redirect_accepted_with_flush")
                if (f.fault) cover("translation_fault")
                if ((f.pc & 3) != 0) cover("misaligned_pc")
            }
            if (queued.nonEmpty && !d.io.pp.request.ready.peek().litToBoolean) cover("input_backpressure")
            d.clock.step()
            cycle += 1
        }
        def until(condition: => Boolean): Unit = {
            val deadline = cycle + 3000
            while (!condition && cycle < deadline) step()
            assert(condition, s"condition not reached at $cycle")
        }
        def drain(): Unit = {
            until(queued.isEmpty && active.isEmpty && pending.isEmpty && translations.isEmpty &&
                !d.io.miss.peek().litToBoolean)
            for (_ <- 0 until 4) step()
            assert(accepted.size == completed.size + canceledTokens.size, s"$context: lost or duplicate fetch")
            assert(lowerReturns == requests.size, s"$context: lower transaction did not drain")
        }
        def fetch(
            pc: BigInt,
            high: Int = 0,
            uncached: Boolean = false,
            fault: Boolean = false,
            delay: Int = 0,
            physical: Option[BigInt] = None,
            errors: BigInt = 0
        ): BigInt = {
            val token = add(pc, high, uncached, fault, delay, physical, errors)
            drain()
            assert(completed.contains(token), s"fetch $token did not complete")
            token
        }
        def flush(): Unit = {
            queued.clear()
            step(flush = true)
        }
    }

}
