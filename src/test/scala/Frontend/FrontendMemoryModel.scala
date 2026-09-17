import chisel3._
import ZirconConfig.{FrontendParams, ICacheParams}
import scala.collection.mutable

/** A translation/L2 environment for the complete frontend; the ICache is never bypassed. */
class FrontendMemoryModel(d: FrontendTestHarness, p: FrontendParams, c: ICacheParams)
    extends chisel3.simulator.PeekPokeAPI {
    case class Fetch(token: BigInt, pc: BigInt, pa: BigInt, due: Int, fault: Boolean, uncached: Boolean)
    case class Lower(token: BigInt, pa: BigInt, due: Int, uncached: Boolean, error: Boolean)
    case class Packet(
        token: BigInt,
        index: BigInt,
        pc: BigInt,
        mask: BigInt,
        nextPc: BigInt,
        taken: BigInt,
        words: Seq[BigInt],
        faults: BigInt
    )

    val rom = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0x13))
    val faulting = mutable.Set.empty[BigInt]
    val uncached = mutable.Set.empty[BigInt]
    val lowerErrors = mutable.Set.empty[BigInt]
    val accepted = mutable.LinkedHashMap.empty[BigInt, Fetch]
    val active = mutable.LinkedHashMap.empty[BigInt, Fetch]
    val canceled = mutable.Set.empty[BigInt]
    val completed = mutable.Set.empty[BigInt]
    val lowerReads = mutable.ArrayBuffer.empty[Lower]
    val translations = mutable.ArrayBuffer.empty[Fetch]
    val packets = mutable.ArrayBuffer.empty[Packet]
    val repairs = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
    val stages = Seq.fill(3)(mutable.Set.empty[BigInt])
    val faults = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    var pending: Option[Lower] = None
    var translation: Option[Fetch] = None
    var heldLower: Option[Seq[BigInt]] = None
    var heldResponse: Option[Seq[BigInt]] = None
    private var parkTranslations = false
    private val parked = mutable.Set.empty[BigInt]
    var consumerReady = false
    var lowerReady = true
    var lowerDelay = 5
    var translationDelay = 0
    var redirect: Option[BigInt] = None
    var retirement: Option[Packet] = None
    var cycle = 0
    var returns = 0
    var staleTranslations = 0
    var expectTraining = false
    val fullMask = (BigInt(1) << p.fetchWidth) - 1
    val low32 = (BigInt(1) << 32) - 1
    def word(pa: BigInt): BigInt = rom(pa & low32)
    def range(pc: BigInt): BigInt = fullMask & (fullMask << ((pc % (p.fetchWidth * 4)).toInt / 4))

    def step(): Unit = {
        d.io.middle.out.ready.poke(consumerReady)
        d.io.commit.redirect.valid.poke(redirect.nonEmpty)
        d.io.commit.redirect.bits.pc.poke(redirect.getOrElse(p.resetPc))
        d.io.commit.retire.valid.poke(retirement.nonEmpty)
        d.io.commit.retire.bits.fetchToken.poke(retirement.map(_.token).getOrElse(BigInt(0)))
        d.io.commit.retire.bits.ftqIdx.poke(retirement.map(_.index).getOrElse(BigInt(0)))
        d.io.commit.retire.bits.mask.poke(retirement.map(_.mask).getOrElse(BigInt(0)))
        d.io.commit.retire.bits.taken.poke(retirement.map(_.taken).getOrElse(BigInt(0)))
        d.io.commit.retire.bits.nextPc.poke(retirement.map(_.nextPc).getOrElse(BigInt(0)))
        d.io.commit.retire.bits.targets.zipWithIndex.foreach { case (target, i) =>
            target.poke(retirement.filter(_.taken.testBit(i)).map(_.nextPc).getOrElse(BigInt(0)))
        }
        translation = translation.orElse(translations.find(_.due <= cycle))
        d.io.mmu.response.valid.poke(translation.nonEmpty)
        d.io.mmu.response.bits.token.poke(translation.map(_.token).getOrElse(BigInt(0)))
        d.io.mmu.response.bits.paddr.poke(translation.map(_.pa).getOrElse(BigInt(0)))
        d.io.mmu.response.bits.fault.poke(translation.exists(_.fault))
        d.io.mmu.response.bits.uncache.poke(translation.exists(_.uncached))
        d.io.mem.l2.request.ready.poke(lowerReady)
        val returning = pending.filter(_.due <= cycle)
        d.io.mem.l2.response.valid.poke(returning.nonEmpty)
        d.io.mem.l2.response.bits.token.poke(returning.map(_.token).getOrElse(BigInt(0)))
        d.io.mem.l2.response.bits.error.poke(returning.exists(_.error))
        d.io.mem.l2.response.bits.data.poke(returning.map { r =>
            if (r.uncached) word(r.pa)
            else (0 until c.lineBytes / 4).foldLeft(BigInt(0))((bits, i) => bits | (word(r.pa + 4 * i) << (32 * i)))
        }.getOrElse(BigInt(0)))

        val observe = d.io.observe.get
        observe.training.expect(expectTraining)
        expectTraining = retirement.nonEmpty
        if (retirement.nonEmpty) d.io.commit.retire.ready.expect(true)
        if (observe.cancel.valid.peek().litToBoolean) {
            canceled ++= active.keys
            active.clear()
            parked.clear()
            heldResponse = None
            observe.fetchResponse.valid.expect(false)
            if (observe.cancel.bits.global.peek().litToBoolean) d.io.middle.out.valid.expect(false)
        }
        for (stage <- 0 until 3 if observe.stages(stage).valid.peek().litToBoolean) {
            val token = observe.stages(stage).bits.token.peek().litValue
            assert(stages(stage).add(token), s"stage $stage repeated token $token at $cycle")
        }
        if (observe.repair.valid.peek().litToBoolean) {
            val token = observe.repair.bits.token.peek().litValue
            repairs(token) += 1
            assert(repairs(token) == 1, "PD repair repeated while stalled")
        }
        if (observe.fetchResponse.valid.peek().litToBoolean) {
            val r = observe.fetchResponse.bits
            val values = Seq(r.token.peek().litValue, r.mask.peek().litValue, r.fault.peek().litValue) ++
                r.inst.map(_.peek().litValue)
            val token = values.head
            assert(active.headOption.exists(_._1 == token), "unexpected ICache response")
            heldResponse.foreach(old => assert(old == values, "ICache response changed under backpressure"))
            val f = active(token)
            assert(values(1) == range(f.pc))
            assert(values(2) == (if (f.fault) range(f.pc) else faults(token) & range(f.pc)))
            for (i <- 0 until p.fetchWidth if values(1).testBit(i) && !values(2).testBit(i))
                assert(values(i + 3) == word((f.pa & ~BigInt(p.fetchWidth * 4 - 1)) + 4 * i))
            if (observe.fetchResponseReady.peek().litToBoolean) {
                observe.stages(1).valid.expect(true)
                assert(completed.add(token))
                active.remove(token)
                heldResponse = None
            } else heldResponse = Some(values)
        } else assert(heldResponse.isEmpty)
        if (d.io.mem.l2.request.valid.peek().litToBoolean) {
            val r = d.io.mem.l2.request.bits
            val values = Seq(r.token.peek().litValue, r.paddr.peek().litValue, r.uncache.peek().litValue)
            heldLower.foreach(old => assert(old == values, "lower request changed under backpressure/flush"))
            val f = accepted(values.head)
            assert(!f.fault && (active.contains(f.token) || heldLower.nonEmpty))
            assert((values(2) != 0) == f.uncached)
            if (!f.uncached) assert(values(1) == (f.pa & ~BigInt(c.lineBytes - 1)))
            if (lowerReady) {
                assert(pending.isEmpty)
                val lower = Lower(f.token, values(1), cycle + lowerDelay, f.uncached, lowerErrors(values(1) & low32))
                pending = Some(lower)
                lowerReads += lower
                heldLower = None
            } else heldLower = Some(values)
        } else assert(heldLower.isEmpty)
        if (returning.nonEmpty && d.io.mem.l2.response.ready.peek().litToBoolean) {
            val r = returning.get
            if (r.error) faults(r.token) |=
                (if (r.uncached) BigInt(1) << ((r.pa % (p.fetchWidth * 4)).toInt / 4) else fullMask)
            pending = None
            returns += 1
        }
        if (translation.nonEmpty && d.io.mmu.response.ready.peek().litToBoolean) {
            val f = translation.get
            if (canceled(f.token)) staleTranslations += 1
            translations -= f
            translation = None
        }
        if (observe.fetchRequest.valid.peek().litToBoolean && observe.fetchRequestReady.peek().litToBoolean) {
            val pc = observe.fetchRequest.bits.pc.peek().litValue
            val token = observe.fetchRequest.bits.token.peek().litValue
            val f = Fetch(token, pc, pc | (BigInt(3) << 32), cycle + 1 + translationDelay, faulting(pc), uncached(pc))
            assert(!accepted.contains(token))
            accepted(token) = f
            active(token) = f
            if (parkTranslations) parked += token else translations += f
        }
        if (d.io.middle.out.valid.peek().litToBoolean) {
            val bits = d.io.middle.out.bits
            val token = bits.fetchToken.peek().litValue
            val pc = bits.startPc.peek().litValue
            val mask = bits.mask.peek().litValue
            val words = bits.instructions.map(_.inst.peek().litValue).toSeq
            val fault = bits.instructions.zipWithIndex.foldLeft(BigInt(0)) { case (v, (inst, i)) =>
                if (inst.fault.peek().litToBoolean) v.setBit(i) else v
            }
            assert(completed(token) && accepted(token).pc == pc)
            for (i <- 0 until p.fetchWidth if mask.testBit(i)) {
                if (fault.testBit(i)) {
                    bits.instructions(i).kind.expect(0)
                    (bits.instructions(i).rinfo.src.toSeq :+ bits.instructions(i).rinfo.dest).foreach { op =>
                        op.valid.expect(false); op.index.expect(0); op.isFp.expect(false)
                    }
                } else assert(words(i) == rom((pc & ~BigInt(p.fetchWidth * 4 - 1)) + i * 4))
            }
            if (consumerReady) {
                val taken = bits.instructions.zipWithIndex.foldLeft(BigInt(0)) { case (v, (inst, i)) =>
                    if (inst.predictedTaken.peek().litToBoolean) v.setBit(i) else v
                }
                packets += Packet(
                    token,
                    bits.ftqIdx.peek().litValue,
                    pc,
                    mask,
                    bits.nextPc.peek().litValue,
                    taken,
                    words,
                    fault
                )
            }
        }
        d.clock.step()
        cycle += 1
    }

    def until(condition: => Boolean): Unit = {
        val deadline = cycle + 5000
        while (!condition && cycle < deadline) step()
        assert(condition, s"frontend stalled at $cycle")
    }
    def handoff(): Packet = {
        val count = packets.size
        consumerReady = true
        until(packets.size > count)
        consumerReady = false
        packets.last
    }
    def retire(packet: Packet, flush: Boolean = false): Unit = {
        retirement = Some(packet)
        if (flush) redirect = Some(packet.nextPc)
        step()
        retirement = None
        redirect = None
    }
    def finish(): Unit = {
        // Stall translations for new-path fetches while canceled transactions drain.
        parkTranslations = true
        consumerReady = false
        redirect = Some(p.resetPc)
        lowerReady = true
        step()
        redirect = None
        until(pending.isEmpty && heldLower.isEmpty && translations.isEmpty && translation.isEmpty &&
            !d.io.observe.get.icacheMiss.peek().litToBoolean)
        for (_ <- 0 until 4) step()
        assert(active.keySet == parked, "only untranslated new-path requests may remain")
        assert(accepted.size == completed.size + canceled.size + parked.size && returns == lowerReads.size)
    }
}
