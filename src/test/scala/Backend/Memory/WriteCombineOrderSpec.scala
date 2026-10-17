// SPDX-License-Identifier: MPL-2.0
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.DCacheParams
import scala.collection.mutable

class WriteCombineOrderSpec extends AnyFreeSpec with ChiselSim {
    "a strong doorbell waits for preceding posted descriptor responses without a Fence" in {
        simulate(new DCache(DualPortRamBackend.Registers, DCacheParams(sets=4))) { dut =>
            for (port <- dut.io.load) {
                port.req.valid.poke(false)
                port.req.bits.poke(0.U.asTypeOf(port.req.bits))
            }
            for (port <- dut.io.forward) {
                port.result.valid.poke(false)
                port.result.bits.poke(0.U.asTypeOf(port.result.bits))
            }
            dut.io.flush.poke(false)
            dut.io.maintenance.request.poke(false)
            dut.io.maintenance.invalidate.poke(false)
            dut.io.store.req.valid.poke(false)
            dut.io.store.req.bits.poke(0.U.asTypeOf(new DStoreRequest))
            dut.io.store.rsp.ready.poke(false)
            dut.io.l2.req.ready.poke(false)
            dut.io.l2.rsp.valid.poke(false)
            dut.io.l2.rsp.bits.poke(0.U.asTypeOf(new DMemoryResponse))
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)

            val base = 0xa2ffe000L
            val doorbell = 0xafffe020L
            // Initialize every field, then publish sparse updates that retain
            // the rest of the template. Physical responses are delayed.
            val stores = (0 until 8).map(i => (base+4*i, BigInt(0x10203040L+i), true)) ++
                Seq((doorbell,BigInt(0),false)) ++
                (0 until 4).flatMap(i => Seq((base,BigInt(0x2000+i),true),
                    (base+8,BigInt(0x4000+i),true),(doorbell,BigInt(0),false)))
            val expected = mutable.Map.empty[Long,Int]
            val visible = mutable.Map.empty[Long,Int]
            case class Lower(address: Long, data: BigInt, mask: BigInt, bytes: Int, posted: Boolean, due: Int)
            var lower: Option[Lower] = None
            var index = 0; var waiting = false; var cycle = 0
            var publications = 0; var postedResponses = 0
            var held: Option[Seq[BigInt]] = None
            while ((index<stores.size || lower.nonEmpty || !dut.io.idle.peek().litToBoolean) && cycle<4096) {
                val current = stores.lift(index)
                dut.io.store.req.valid.poke(current.nonEmpty && !waiting)
                current.foreach { case (address,data,posted) =>
                    dut.io.store.req.bits.paddr.poke(address)
                    dut.io.store.req.bits.data.poke(data)
                    dut.io.store.req.bits.mask.poke(15)
                    dut.io.store.req.bits.size.poke(2)
                    dut.io.store.req.bits.uncache.poke(true)
                    dut.io.store.req.bits.writeCombine.poke(posted)
                }
                dut.io.store.rsp.ready.poke(cycle%5!=0)
                dut.io.l2.req.ready.poke(cycle%7>=3)
                dut.io.l2.rsp.valid.poke(lower.exists(_.due<=cycle))
                val payload = Seq(dut.io.l2.req.bits.paddr,dut.io.l2.req.bits.data,
                    dut.io.l2.req.bits.mask,dut.io.l2.req.bits.burstMask,
                    dut.io.l2.req.bits.burstBeats,dut.io.l2.req.bits.writeCombine).map(_.peek().litValue)
                val valid = dut.io.l2.req.valid.peek().litToBoolean
                val ready = dut.io.l2.req.ready.peek().litToBoolean
                held.foreach(p => assert(valid && payload==p,"lower request changed under backpressure"))
                held = if(valid && !ready) Some(payload) else None
                if(dut.io.l2.rsp.valid.peek().litToBoolean && dut.io.l2.rsp.ready.peek().litToBoolean) {
                    val response=lower.get
                    if(response.posted) {
                        for(b <- 0 until response.bytes if response.mask.testBit(b))
                            visible(response.address+b)=((response.data>>(8*b))&255).toInt
                        postedResponses+=1
                    }
                    lower=None
                }
                if(dut.io.store.req.valid.peek().litToBoolean && dut.io.store.req.ready.peek().litToBoolean) {
                    assert(!waiting)
                    val (address,data,posted)=current.get
                    if(posted) for(b <- 0 until 4) expected(address+b)=((data>>(8*b))&255).toInt
                    else {
                        assert(lower.isEmpty,"doorbell accepted before older physical write response")
                        assert(visible==expected,"doorbell observed an incomplete descriptor")
                        publications+=1
                    }
                    waiting=true
                }
                if(valid && ready) {
                    assert(lower.isEmpty,"untagged lower transaction overlapped")
                    dut.io.l2.req.bits.write.expect(true)
                    val posted=payload(5)==1
                    if(!posted) { assert(payload(0)==doorbell); assert(visible==expected) }
                    lower=Some(Lower(payload(0).toLong,payload(1),if(posted) payload(3) else payload(2),
                        if(posted) payload(4).toInt*8 else 4,posted,cycle+11+index%5))
                }
                if(dut.io.store.rsp.valid.peek().litToBoolean && dut.io.store.rsp.ready.peek().litToBoolean) {
                    assert(waiting); dut.io.store.rsp.bits.exception.expect(0)
                    waiting=false; index+=1
                }
                dut.clock.step(); cycle+=1
            }
            assert(cycle<4096 && index==stores.size && publications==5 && postedResponses>=5)
        }
    }
}
