// SPDX-License-Identifier: MPL-2.0
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class ReadPrefetchSpec extends AnyFreeSpec with ChiselSim {
    private def encoding(offset: Int): Long =
        (((offset >> 5) & 127).toLong << 25) | (1L << 20) | (5L << 15) | 0x6013L

    "PREFETCH.R decodes a signed cache-block offset without an architectural destination" in {
        simulate(new DecoderTestTop(DecodeSupport.current)) { dut =>
            dut.io.fault.poke(false)
            dut.io.exception.valid.poke(false)
            dut.io.exception.cause.poke(0)
            dut.io.exception.tval.poke(0)
            for (offset <- Seq(-2048,-32,0,64,2016)) {
                dut.io.inst.poke(encoding(offset))
                dut.io.out.exception.valid.expect(false)
                dut.io.out.op.expect(EXEOp.ADD)
                dut.io.out.imm.expect(BigInt(offset)&0xffffffffL)
                dut.io.out.rinfo.dest.valid.expect(false)
                dut.io.out.rinfo.src(0).valid.expect(true)
                dut.io.out.rinfo.src(0).index.expect(5)
            }
            dut.io.inst.poke(encoding(64)|(1L<<7))
            dut.io.out.op.expect(EXEOp.OR)
            dut.io.out.imm.expect(65)
        }
    }

    "the arithmetic pipe publishes a hint and retires without a memory completion" in {
        simulate(new ArithBranch) { dut =>
            dut.io.iq.valid.poke(false)
            BackendPackageTestUtils.clear(dut.io.iq.bits)
            dut.io.cmt.flush.poke(false)
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.rf.read.foreach(_.data.poke(0x8000101fL))
            for (source <- 0 until 2) {
                dut.io.bypass.consumer.value(source).valid.poke(false)
                dut.io.bypass.consumer.value(source).bits.poke(0)
                dut.io.bypass.consumer.valueFpZero(source).poke(true)
                dut.io.bypass.consumer.capture(source).valid.poke(false)
                dut.io.bypass.consumer.capture(source).bits.poke(0)
                dut.io.bypass.consumer.captureFpZero(source).poke(true)
            }
            dut.clock.step()
            for (flush <- Seq(false,true)) {
                BackendPackageTestUtils.clear(dut.io.iq.bits)
                dut.io.iq.bits.inst.poke(encoding(64))
                dut.io.iq.bits.fu.poke(DecodeUnit.ALU)
                dut.io.iq.bits.op.poke(EXEOp.ADD)
                dut.io.iq.bits.imm.poke(64)
                dut.io.iq.bits.src1Sel.poke(DecodeSource.Register)
                dut.io.iq.bits.src2Imm.poke(true)
                dut.io.iq.valid.poke(true)
                dut.clock.step()
                dut.io.iq.valid.poke(false)
                dut.clock.step()
                dut.io.cmt.flush.poke(flush)
                dut.io.prefetch.valid.expect(!flush)
                if (!flush) dut.io.prefetch.bits.expect(0x8000105fL)
                dut.clock.step()
                dut.io.cmt.rob.complete.valid.expect(!flush)
                dut.io.cmt.flush.poke(false)
                dut.clock.step(2)
            }
        }
    }

    "cache hints warm data without load responses and ignore device addresses and refill faults" in {
        simulate(new DCache(DualPortRamBackend.Registers,DCacheParams(directPhysical=true))) { dut =>
            val d = new DCacheDriver(dut)
            import d._
            dut.io.prefetch.get.foreach { hint => hint.valid.poke(false);hint.bits.poke(0) }
            tick()
            def hint(address: Long): Unit = {
                dut.io.prefetch.get(0).bits.poke(address)
                dut.io.prefetch.get(0).valid.poke(true)
                tick()
                dut.io.prefetch.get(0).valid.poke(false)
                drain()
            }
            val address = 0x8000401cL
            hint(address)
            assert(reads==1 && loads==0 && expected.isEmpty)
            issue(Some(load(address)));drain()
            assert(reads==1 && loads==1)
            hint(0xa0000000L)
            assert(reads==1 && writes==0)
            val fault = 0x80018000L
            errors += fault
            hint(fault)
            assert(reads==2 && loads==1 && expected.isEmpty)
            errors -= fault
            issue(Some(load(fault)));drain()
            assert(reads==3 && loads==2)
            maintenanceRequest = true
            dut.io.prefetch.get(0).bits.poke(address)
            dut.io.prefetch.get(0).valid.poke(true)
            tick()
            dut.io.prefetch.get(0).valid.poke(false)
            maintenanceRequest = false
            drain()
        }
    }

    "recovery drains an accepted prefetch and preserves dirty ownership returned by L2" in {
        simulate(new DCache(DualPortRamBackend.Registers,DCacheParams(directPhysical=true))) { dut =>
            val d = new DCacheDriver(dut)
            import d._
            dut.io.prefetch.get.foreach { hint => hint.valid.poke(false);hint.bits.poke(0) }
            tick()
            val address = 0x80024000L
            dirtyResponses += address
            latency = 40
            dut.io.prefetch.get(0).bits.poke(address)
            dut.io.prefetch.get(0).valid.poke(true)
            tick()
            dut.io.prefetch.get(0).valid.poke(false)
            var guard=0
            while (transaction.isEmpty && guard<30) { tick();guard+=1 }
            assert(transaction.nonEmpty)
            tick(cancel=Some(0))
            drain()
            val before=reads
            issue(Some(load(address)));drain()
            assert(reads==before)
            maintain(invalidate=true)
            assert(dirtyVictimAddresses.contains(address))
        }
    }

    "a demand load claims an in-flight prefetch without retries or a duplicate memory transaction" in {
        simulate(new DCache(DualPortRamBackend.Registers,DCacheParams(directPhysical=true))) { dut =>
            var retries=0
            val d = new DCacheDriver(dut) {
                override def observeCycle(): Unit = {
                    if (dut.io.load(1).rsp.valid.peek().litToBoolean && dut.io.load(1).rsp.bits.retry.peek().litToBoolean)
                        retries+=1
                }
            }
            import d._
            dut.io.prefetch.get.foreach { hint => hint.valid.poke(false);hint.bits.poke(0) }
            tick();latency=40
            val address=0x8004400cL
            dut.io.prefetch.get(0).bits.poke(address)
            dut.io.prefetch.get(0).valid.poke(true)
            tick()
            dut.io.prefetch.get(0).valid.poke(false)
            var guard=0
            while(transaction.isEmpty && guard<30) { tick();guard+=1 }
            assert(transaction.nonEmpty)
            issue(None,Some(load(address)))
            drain()
            assert(reads==1 && loads==1 && retries==0)
        }
    }

    "two queued addresses survive a busy miss unit and recovery clears pending hints" in {
        simulate(new DCache(DualPortRamBackend.Registers,DCacheParams(directPhysical=true))) { dut =>
            val d = new DCacheDriver(dut)
            import d._
            dut.io.prefetch.get.foreach { hint => hint.valid.poke(false);hint.bits.poke(0) }
            tick();latency=40
            def hint(address: Long): Unit = {
                dut.io.prefetch.get(0).bits.poke(address)
                dut.io.prefetch.get(0).valid.poke(true)
                tick()
                dut.io.prefetch.get(0).valid.poke(false)
            }
            def awaitRequest(): Unit = {
                var guard=0
                while(transaction.isEmpty && guard<30) { tick();guard+=1 }
                assert(transaction.nonEmpty)
            }
            val address=0x8004c000L
            hint(address);awaitRequest()
            hint(address+64);hint(address+128);hint(address+192)
            drain()
            assert(reads==3 && loads==0)
            for (offset <- Seq(0,64,128)) { issue(Some(load(address+offset)));drain() }
            assert(reads==3 && loads==3)
            issue(Some(load(address+192)));drain()
            assert(reads==4 && loads==4)

            hint(address+4096);awaitRequest()
            hint(address+4160);hint(address+4224)
            tick(cancel=Some(0));drain()
            assert(reads==5 && loads==4)
        }
    }

    "both arithmetic ports retain simultaneous hints and filter device addresses independently" in {
        simulate(new DCache(DualPortRamBackend.Registers,DCacheParams(directPhysical=true))) { dut =>
            val issued=scala.collection.mutable.ArrayBuffer.empty[BigInt]
            val d = new DCacheDriver(dut) {
                override def observeCycle(): Unit = {
                    if(dut.io.l2.req.valid.peek().litToBoolean && dut.io.l2.req.ready.peek().litToBoolean)
                        issued+=dut.io.l2.req.bits.paddr.peek().litValue
                }
            }
            import d._
            dut.io.prefetch.get.foreach { hint => hint.valid.poke(false);hint.bits.poke(0) }
            tick();latency=40
            def hints(first: Option[Long],second: Option[Long]): Unit = {
                for ((address,index) <- Seq(first,second).zipWithIndex) {
                    dut.io.prefetch.get(index).valid.poke(address.nonEmpty)
                    dut.io.prefetch.get(index).bits.poke(address.getOrElse(0L))
                }
                tick();dut.io.prefetch.get.foreach(_.valid.poke(false))
            }
            val address=0x8005c000L
            hints(Some(address),None)
            var guard=0
            while(transaction.isEmpty && guard<30) { tick();guard+=1 }
            assert(transaction.nonEmpty)
            hints(Some(address+64),Some(address+128))
            hints(Some(address+192),Some(address+256))
            drain()
            assert(issued.toVector==Vector(address,address+64,address+128).map(BigInt(_)))
            assert(reads==3 && loads==0)
            for (offset <- Seq(64,128)) { issue(Some(load(address+offset)));drain() }
            assert(reads==3 && loads==2)
            hints(Some(0xa0000000L),Some(address+320));drain()
            assert(reads==4 && loads==2 && writes==0)
            hints(Some(0xa0000000L),Some(0xa2ffe000L));drain()
            assert(reads==4 && loads==2 && writes==0)
        }
    }

    "a wide demand can claim on the refill edge while retaining the original victim and dirty ownership" in {
        simulate(new DCacheMissUnit()) { dut =>
            dut.io.allocate.valid.poke(false)
            dut.io.claim.valid.poke(false)
            dut.io.flush.poke(false)
            dut.io.memory.req.ready.poke(true)
            dut.io.memory.rsp.valid.poke(false)
            dut.io.memory.rsp.bits.data.poke(0x12345678)
            dut.io.memory.rsp.bits.dirty.poke(true)
            dut.io.memory.rsp.bits.error.poke(false)
            dut.io.install.ready.poke(false)
            dut.io.complete.ready.poke(false)
            for (f <- dut.io.allocate.bits.elements.values) f match {
                case b: Bool => b.poke(false)
                case u: UInt => u.poke(0)
            }
            for (f <- dut.io.claim.bits.elements.values) f match {
                case b: Bool => b.poke(false)
                case u: UInt => u.poke(0)
            }
            dut.clock.step()
            dut.io.allocate.bits.prefetch.poke(true)
            dut.io.allocate.bits.paddr.poke(0x80010000L)
            dut.io.allocate.bits.way.poke(1)
            dut.io.allocate.bits.mtype.poke(2)
            dut.io.allocate.valid.poke(true)
            dut.clock.step()
            dut.io.allocate.valid.poke(false)
            dut.io.memory.req.valid.expect(true)
            dut.clock.step()
            dut.io.claim.ready.expect(true)
            dut.io.claim.bits.paddr.poke(0x80010000L)
            dut.io.claim.bits.wide.poke(true)
            dut.io.claim.bits.slot.poke(3)
            dut.io.claim.bits.lane.poke(true)
            dut.io.claim.bits.mtype.poke(2)
            dut.io.claim.valid.poke(true)
            dut.io.memory.rsp.valid.poke(true)
            dut.clock.step()
            dut.io.claim.valid.poke(false)
            dut.io.memory.rsp.valid.poke(false)
            dut.io.claim.ready.expect(false)
            dut.io.install.valid.expect(true)
            dut.io.install.bits.way.expect(1)
            dut.io.install.bits.data.expect(0x12345678)
            dut.io.install.bits.dirty.expect(true)
            dut.io.install.ready.poke(true)
            dut.clock.step()
            dut.io.complete.valid.expect(true)
            dut.io.complete.bits.prefetch.expect(false)
            dut.io.complete.bits.lane.expect(true)
            dut.io.complete.bits.response.wide.expect(true)
            dut.io.complete.bits.response.slot.expect(3)
            dut.io.complete.bits.response.line.expect(0x12345678)
            dut.io.complete.bits.response.exception.expect(0)
            dut.io.complete.ready.poke(true)
            dut.clock.step()
            dut.io.busy.expect(false)
        }
    }
}
