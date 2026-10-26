import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

/** Architectural drain, ordering and recovery checks for queued LMUL2 Stores. */
class VectorStoreGroupsSpec extends AnyFreeSpec with ChiselSim {
    "two-group Stores reuse idle payload rows and preserve retired data across recovery" in {
        simulate(new VectorStoreBuffer) { dut =>
            VectorTestUtils.clear(dut.io.collect.bits)
            dut.io.collect.valid.poke(false)
            dut.io.commit.foreach { x => x.valid.poke(false); x.bits.poke(0) }
            dut.io.drain.start.valid.poke(false); dut.io.drain.start.bits.poke(true)
            dut.io.cache.request.ready.poke(false)
            dut.io.cache.response.valid.poke(false); dut.io.cache.response.bits.exception.poke(0)
            dut.io.loadLine.poke(0); dut.io.loadTail.poke(12)
            dut.io.scalarLoadLine.foreach(_.poke(0)); dut.io.scalarLoadTail.foreach(_.poke(12))
            dut.io.flush.poke(false); dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            def address(id: Int): Long = 0xb0000000L+id*128
            def data(id: Int,group: Int): BigInt = BigInt(f"${id*2+group}%02x"*32,16)
            def present(id: Int,group: Int): Unit = {
                val t=dut.io.collect.bits
                t.sqIdx.poke(id); t.robIdx.poke(id); t.index.poke(group); t.last.poke(group==1)
                t.address.poke(address(id)+group*32); t.data.poke(data(id,group))
                t.mask.poke(BigInt("ffffffff",16)); t.scattered.poke(false); t.addressOnly.poke(false)
                t.fieldRegisterShift.poke(1); t.lastField.poke(0); t.eew.poke(2)
                dut.io.collect.valid.poke(true)
            }
            def send(id: Int,group: Int): Unit = {
                present(id,group); dut.io.collect.ready.expect(true); dut.clock.step()
                dut.io.collect.valid.poke(false)
            }
            def retire(ids: Int*): Unit = {
                for((id,port)<-ids.zipWithIndex) { dut.io.commit(port).valid.poke(true); dut.io.commit(port).bits.poke(id) }
                dut.clock.step(); dut.io.commit.foreach(_.valid.poke(false))
            }
            def drain(ids: Seq[Int]): Unit = {
                var cycle=0; var replyAt= -1; var writes=Vector.empty[(BigInt,BigInt,BigInt)]
                var held: Option[(BigInt,BigInt,BigInt)]=None
                dut.io.drain.start.valid.poke(true)
                while(!dut.io.empty.peek().litToBoolean && cycle<100) {
                    val ready=cycle%4!=1
                    dut.io.cache.request.ready.poke(ready)
                    dut.io.cache.response.valid.poke(replyAt==cycle)
                    val r=dut.io.cache.request.bits
                    val value=(r.paddr.peek().litValue,r.line.peek().litValue,r.lineMask.peek().litValue)
                    val valid=dut.io.cache.request.valid.peek().litToBoolean
                    held.foreach(old=>assert(valid && value==old,"A stalled payload changed"))
                    held=if(valid && !ready)Some(value)else None
                    if(replyAt==cycle) { dut.io.cache.response.ready.expect(true); replyAt= -1 }
                    if(valid && ready) { assert(replyAt<0); writes :+= value; replyAt=cycle+3 }
                    dut.clock.step(); cycle+=1
                }
                assert(cycle<100 && replyAt<0)
                assert(writes.size==ids.size)
                for((write,id)<-writes.zip(ids)) {
                    assert(write._1==address(id))
                    assert(write._2==(data(id,1)<<256|data(id,0)))
                    assert(write._3==(BigInt(1)<<64)-1)
                }
                dut.io.drain.start.valid.poke(false)
                dut.io.cache.request.ready.poke(false); dut.io.cache.response.valid.poke(false)
            }
            for(id<-1 to 3;group<-0 to 1) send(id,group)
            present(4,0); dut.io.collect.ready.expect(false); dut.clock.step(); dut.io.collect.valid.poke(false)
            for(id<-1 to 3) {
                dut.io.loadLine.poke((address(id)+32)>>6); dut.io.loadClear.expect(false)
            }
            dut.io.scalarLoadLine(0).poke(address(2)>>6)
            dut.io.scalarLoadTail(0).poke(2); dut.io.scalarLoadClear(0).expect(true)
            retire(1,2)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
            dut.io.loadLine.poke(address(3)>>6); dut.io.loadClear.expect(true)
            drain(Seq(1,2))
            for(id<-4 to 6;group<-0 to 1) send(id,group)
            retire(4,5,6); drain(Seq(4,5,6))
            // The primary can drain between a successor's two collection
            // beats. Keep that incomplete owner without exposing its data.
            send(7,0); send(7,1); send(8,0)
            retire(7)
            dut.io.drain.start.valid.poke(true); dut.io.drain.start.ready.expect(true)
            dut.clock.step(); dut.io.drain.start.valid.poke(false)
            dut.io.cache.request.ready.poke(true)
            dut.io.cache.request.valid.expect(true); dut.clock.step()
            dut.io.cache.response.valid.poke(true); dut.io.cache.response.ready.expect(true)
            dut.clock.step(); dut.io.cache.response.valid.poke(false)
            dut.io.empty.expect(false)
            for(_<-0 until 3) { dut.io.cache.request.valid.expect(false); dut.clock.step() }
            send(8,1); dut.clock.step(); retire(8); drain(Seq(8))
            // A partial speculative successor must not survive recovery.
            send(9,0); send(9,1); send(10,0)
            retire(9)
            dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
            drain(Seq(9)); dut.io.empty.expect(true)
        }
    }
}
