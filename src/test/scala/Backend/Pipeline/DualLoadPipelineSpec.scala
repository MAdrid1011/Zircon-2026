import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable

class DualLoadPipelineSpec extends AnyFreeSpec with ChiselSim {
    implicit val timedMemories: svsim.BackendSettingsModifications = {
        case s: svsim.verilator.Backend.CompilationSettings =>
            s.withTiming(Some(svsim.verilator.Backend.CompilationSettings.Timing.TimingEnabled))
        case s => s
    }
    for (
        (backend, name) <- Seq(
            DualPortRamBackend.Registers -> "registers",
            DualPortRamBackend.Vivado -> "vivado",
            DualPortRamBackend.OpenRAM -> "openram"
        )
    ) {
        s"$name: real LS0/LS1 and PRFs share fixed DCache ports under dual loads and SB traffic" in {
            simulate(new DualLoadPipelineSystem(backend)) { dut =>
                case class Load(lane: Int, id: Int, address: Long, fp: Boolean) {
                    val rd: Int = 4 + lane * 24 + id % 16
                    val tag: Int = rd | (if (fp) 1 << dut.p.physWidth else 0)
                    val rob: Int = id & ((1 << dut.p.robWidth) - 1)
                }
                case class Transaction(id: BigInt, data: BigInt, due: Int)
                val active = mutable.Map.empty[(Int, Int), Load]
                val written = mutable.Set.empty[(Int, Int)]
                val replay = Seq.fill(2)(mutable.Queue.empty[Load])
                val values = mutable.Map.empty[(Boolean, Int), BigInt]
                val ports = Seq(dut.io.ls0, dut.io.ls1)
                var lower: Option[Transaction] = None
                var cycle = 0
                var completed = 0
                var dualIssue = 0
                var dualWB = 0
                var storeWrites = 0
                var storeAcquires = 0
                var storePending = false
                var storesDone = 0
                var pendingForward = Seq.fill[Option[Int]](2)(None)
                def bytes(addr: Long, n: Int): BigInt = (0 until n).foldLeft(BigInt(0))((v, i) =>
                    v | (BigInt(((addr + i) * 37 + ((addr + i) >> 7) + 0x81) & 255) << (i * 8))
                )
                def tick(
                    inputs: Seq[Option[Load]] = Seq(None, None),
                    stall: Boolean = false,
                    cancel: Boolean = false,
                    write: Boolean = false,
                    initialize: Boolean = false
                ): Seq[Boolean] = {
                    val presented = ports.indices.map(i => replay(i).headOption.orElse(inputs(i)))
                    val presentingReplay = ports.indices.map(i => replay(i).nonEmpty)
                    dut.io.flush.poke(cancel)
                    dut.io.intWrite.zipWithIndex.foreach { case (w, i) =>
                        w.we.poke(initialize && i == 0); w.addr.poke(1); w.data.poke(0x1000)
                    }
                    dut.io.fpWrite.foreach { w => w.we.poke(false); w.addr.poke(0); w.data.poke(0) }
                    dut.io.intRead.foreach(_.addr.poke(0)); dut.io.fpRead.foreach(_.addr.poke(0))
                    val sd = dut.io.ls1.iq.std.get
                    sd.valid.poke(false); BackendPackageTestUtils.clear(sd.bits)
                    sd.bits.prs(0).poke(0); sd.bits.sqIdx.poke(0); sd.bits.robIdx.poke(0); sd.bits.size.poke(2)
                    dut.io.ls1.cmt.storeAddress.get.ready.poke(true); dut.io.ls1.cmt.storeData.get.ready.poke(true)
                    if (cancel) {
                        active.clear()
                        written.clear()
                        replay.foreach(_.clear())
                        pendingForward = pendingForward.map(_ => None)
                    }
                    for ((port, lane) <- ports.zipWithIndex) {
                        val x = presented(lane).getOrElse(Load(lane, 0, 0x1000, false))
                        val b = port.iq.instPkg.bits
                        BackendPackageTestUtils.clear(b)
                        port.iq.instPkg.valid.poke(presented(lane).nonEmpty)
                        b.fu.poke(ZirconConfig.DecodeUnit.Load)
                        b.prj.poke(1); b.imm.poke(x.address - 0x1000); b.prd.poke(x.tag); b.rdVld.poke(true)
                        b.robIdx.poke(x.rob); b.sqTail.poke(0); b.mtype.poke(2)
                        b.uncache.poke(false); b.ioAuthorized.poke(false); b.exception.valid.poke(false)
                        b.store.poke(false); b.sqIdx.poke(0)
                        port.wk.grant.poke(0)
                        port.cmt.flush.poke(cancel);
                        for (r <- Seq(port.cmt.sqResult, port.cmt.sbResult)) {
                            r.valid.poke(pendingForward(lane).nonEmpty)
                            r.bits.slot.poke(pendingForward(lane).getOrElse(0))
                            r.bits.data.poke(0)
                            r.bits.mask.poke(0)
                            r.bits.blocked.poke(false)
                        }
                    }
                    dut.io.store.req.valid.poke(write && !storePending)
                    dut.io.store.req.bits.paddr.poke(0x9000)
                    dut.io.store.req.bits.data.poke(BigInt("aabbccdd", 16)); dut.io.store.req.bits.mask.poke(15)
                    dut.io.store.req.bits.size.poke(2); dut.io.store.req.bits.uncache.poke(false)
                    dut.io.store.rsp.ready.poke(true)
                    dut.io.l2.req.ready.poke(!stall || cycle % 4 != 0)
                    val rsp = lower.filter(_.due <= cycle)
                    dut.io.l2.rsp.valid.poke(rsp.nonEmpty);
                    dut.io.l2.rsp.bits.data.poke(rsp.map(_.data).getOrElse(BigInt(0)));
                    dut.io.l2.rsp.bits.dirty.poke(false)
                    dut.io.l2.rsp.bits.error.poke(false)
                    if (rsp.nonEmpty && dut.io.l2.rsp.ready.peek().litToBoolean) lower = None
                    if (dut.io.l2.req.valid.peek().litToBoolean && dut.io.l2.req.ready.peek().litToBoolean) {
                        assert(lower.isEmpty)
                        val r = dut.io.l2.req.bits
                        val isWrite = r.write.peek().litToBoolean
                        if (isWrite) { storeWrites += 1; r.paddr.expect(0x9000); r.data.expect(BigInt("aabbccdd", 16)) }
                        if (!isWrite && r.paddr.peek().litValue == 0x9000) storeAcquires += 1
                        lower = Some(Transaction(
                            BigInt(0),
                            if (isWrite) BigInt(0) else bytes(r.paddr.peek().litValue.toLong, 32),
                            cycle + 4 + cycle % 7
                        ))
                    }
                    val wbFires = ports.map(_.wb.valid.peek().litToBoolean)
                    if (wbFires.forall(identity)) dualWB += 1
                    for ((port, lane) <- ports.zipWithIndex) {
                        if (port.wk.replay.valid.peek().litToBoolean) {
                            val rob = port.wk.replay.bits.robIdx.peek().litValue.toInt
                            replay(lane).enqueue(active((lane, rob)))
                        }
                        if (wbFires(lane)) {
                            val tag = port.wb.bits.prd.peek().litValue.toInt
                            val x =
                                active.values.find(x => x.lane == lane && x.tag == tag && !written((lane, x.id))).get
                            port.wb.bits.data.expect(bytes(x.address, 4))
                            written += ((lane, x.id)); values((x.fp, x.rd)) = bytes(x.address, 4)
                        }
                        if (port.cmt.rob.valid.peek().litToBoolean) {
                            val key = (lane, port.cmt.rob.bits.robIdx.peek().litValue.toInt)
                            val x = active.remove(key).get
                            assert(written.remove((lane, x.id))); port.cmt.rob.bits.vaddr.expect(x.address)
                            port.cmt.rob.bits.exception.expect(0); completed += 1
                        }
                    }
                    if (dut.io.store.req.valid.peek().litToBoolean && dut.io.store.req.ready.peek().litToBoolean)
                        storePending = true
                    if (dut.io.store.rsp.valid.peek().litToBoolean) {
                        assert(storePending); dut.io.store.rsp.bits.exception.expect(0); storePending = false;
                        storesDone += 1
                    }
                    val presentedFire = ports.indices.map(i =>
                        presented(i).nonEmpty && ports(i).iq.instPkg.ready.peek().litToBoolean
                    )
                    val fire = ports.indices.map(i => presentedFire(i) && !presentingReplay(i))
                    if (fire.forall(identity)) dualIssue += 1
                    for (i <- ports.indices if presentedFire(i)) {
                        val x = presented(i).get
                        if (presentingReplay(i)) {
                            replay(i).dequeue()
                            assert(active.contains((i, x.rob)))
                        } else {
                            assert(!active.contains((i, x.rob)))
                            active((i, x.rob)) = x
                        }
                    }
                    pendingForward = ports.map { port =>
                        val query = port.cmt.sqQuery
                        port.cmt.sbQuery.valid.expect(query.valid.peek().litToBoolean)
                        if (query.valid.peek().litToBoolean) Some(query.bits.slot.peek().litValue.toInt) else None
                    }
                    dut.clock.step(); cycle += 1; fire
                }
                def drain(): Unit = {
                    var n = 0
                    while (
                        (active.nonEmpty || replay.exists(_.nonEmpty) || lower.nonEmpty || storePending) && n < 3000
                    ) {
                        tick()
                        n += 1
                    }
                    assert(n < 3000); for (_ <- 0 until 8) tick()
                }
                tick(initialize = true)
                for (phase <- 0 until 3) {
                    var next = Seq(0, 0)
                    var guard = 0
                    while (next.exists(_ < 64) && guard < 5000) {
                        val in = (0 until 2).map(i =>
                            if (next(i) == 64) None
                            else Some(Load(
                                i,
                                phase * 64 + next(i),
                                0x1000 + (if (phase == 0) 0 else i * 512) + (next(i) % 8) * 4,
                                fp = (next(i) + i) % 2 != 0
                            ))
                        )
                        val fire = tick(in, stall = phase == 2, write = phase != 0 && guard % 19 == 0)
                        next = next.indices.map(i => next(i) + (if (fire(i)) 1 else 0))
                        guard += 1
                    }
                    assert(next.forall(_ == 64)); drain()
                }
                // Cancel both RF entries with one commit flush, then reuse the slots.
                tick(Seq(Some(Load(0, 220, 0x3000, false)), Some(Load(1, 220, 0x3200, true))))
                tick(cancel = true); drain()
                assert(completed == 384 && dualIssue > 20 && dualWB > 20 && storesDone > 0)
                // Conflict evictions may require the cacheable store line to be acquired again.
                assert(storeAcquires > 0 && storeWrites == 0)
                for (((fp, index), value) <- values) {
                    if (fp) { dut.io.fpRead(0).addr.poke(index); dut.io.fpRead(0).data.expect(value) }
                    else { dut.io.intRead(0).addr.poke(index); dut.io.intRead(0).data.expect(value) }
                }
                info(
                    s"$name dual pipes: cycles=$cycle loads=$completed dualIssue=$dualIssue dualWB=$dualWB writes=$storeWrites"
                )
            }
        }
    }
}
