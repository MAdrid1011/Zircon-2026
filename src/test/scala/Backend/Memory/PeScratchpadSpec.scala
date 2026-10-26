// SPDX-License-Identifier: MPL-2.0
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable

class PeScratchpadSpec extends AnyFreeSpec with ChiselSim {
    private class Driver(dut: PeScratchpadBridge) extends chisel3.simulator.PeekPokeAPI {
        case class Reply(due: Int,data: BigInt,error: Int)
        val pending=mutable.Queue.empty[Reply]
        val bytes=mutable.Map.empty[Long,Int]
        val fault=mutable.Set.empty[Long]
        val requests=mutable.ArrayBuffer.empty[(Long,Boolean,BigInt,BigInt)]
        val loads=mutable.ArrayBuffer.empty[(Int,BigInt,Int)]
        val stores=mutable.ArrayBuffer.empty[Int]
        var cycles=0
        var ready: Int=>Boolean = _=>true
        var held: Option[(Long,Boolean,BigInt,BigInt)]=None
        VectorTestUtils.clear(dut.io.load.request.bits);dut.io.load.request.valid.poke(false)
        VectorTestUtils.clear(dut.io.store.req.bits);dut.io.store.req.valid.poke(false)
        dut.io.store.rsp.ready.poke(true)
        dut.io.memory.request.ready.poke(false);dut.io.memory.response.valid.poke(false)
        dut.io.memory.response.bits.data.poke(0);dut.io.memory.response.bits.error.poke(0)
        dut.reset.poke(true);dut.clock.step(2);dut.reset.poke(false)
        def value(address: Long): Int = bytes.getOrElse(address,((address*11+7)&255).toInt)
        def read(address: Long,count: Int): BigInt =
            (0 until count).foldLeft(BigInt(0))((v,i)=>v|(BigInt(value(address+i))<<(8*i)))
        def tick(): (Boolean,Boolean) = {
            dut.io.memory.request.ready.poke(ready(cycles))
            val reply=pending.headOption.filter(_.due<=cycles)
            dut.io.memory.response.valid.poke(reply.nonEmpty)
            dut.io.memory.response.bits.data.poke(reply.map(_.data).getOrElse(BigInt(0)))
            dut.io.memory.response.bits.error.poke(reply.map(_.error).getOrElse(0))
            if(dut.io.load.response.valid.peek().litToBoolean)
                loads+=((dut.io.load.response.bits.slot.peek().litValue.toInt,
                    dut.io.load.response.bits.line.peek().litValue,dut.io.load.response.bits.exception.peek().litValue.toInt))
            if(dut.io.store.rsp.valid.peek().litToBoolean && dut.io.store.rsp.ready.peek().litToBoolean)
                stores+=dut.io.store.rsp.bits.exception.peek().litValue.toInt
            val valid=dut.io.memory.request.valid.peek().litToBoolean
            val req=dut.io.memory.request.bits
            val signature=(req.address.peek().litValue.toLong,req.write.peek().litToBoolean,
                req.data.peek().litValue,req.strobe.peek().litValue)
            held.foreach(old=>assert(valid && signature==old,"stalled SRAM request changed"))
            held=if(valid && !ready(cycles)) Some(signature) else None
            if(reply.nonEmpty && dut.io.memory.response.ready.peek().litToBoolean) pending.dequeue()
            if(valid && ready(cycles)) {
                requests+=signature
                val (address,write,data,mask)=signature
                val error=if(fault(address)) 2 else 0
                if(write && error==0) for(i<-0 until 32 if mask.testBit(i))
                    bytes(address+i)=((data>>(i*8))&255).toInt
                pending.enqueue(Reply(cycles+1,if(write) BigInt(0) else read(address,32),error))
            }
            val loadFire=dut.io.load.request.valid.peek().litToBoolean && dut.io.load.request.ready.peek().litToBoolean
            val storeFire=dut.io.store.req.valid.peek().litToBoolean && dut.io.store.req.ready.peek().litToBoolean
            dut.clock.step();cycles+=1
            (loadFire,storeFire)
        }
        def drain(): Unit = {
            var guard=0
            while((!dut.io.idle.peek().litToBoolean || pending.nonEmpty) && guard<1000) {tick();guard+=1}
            assert(guard<1000)
        }
        def load(address: Long,slot: Int): Unit = {
            VectorTestUtils.clear(dut.io.load.request.bits)
            dut.io.load.request.bits.paddr.poke(address);dut.io.load.request.bits.vaddr.poke(address)
            dut.io.load.request.bits.wide.poke(true);dut.io.load.request.bits.slot.poke(slot)
            dut.io.load.request.bits.mtype.poke(2);dut.io.load.request.valid.poke(true)
            var guard=0
            var accepted=false
            while(!accepted && guard<100) {accepted=tick()._1;guard+=1}
            assert(guard<100);dut.io.load.request.valid.poke(false)
            // An accepted read keeps its own address after upstream recovery.
            dut.io.load.request.bits.paddr.poke(0);dut.io.load.request.bits.slot.poke(0)
        }
        def store(address: Long,data: BigInt,mask: BigInt): Unit = {
            VectorTestUtils.clear(dut.io.store.req.bits)
            dut.io.store.req.bits.paddr.poke(address);dut.io.store.req.bits.wide.poke(true)
            dut.io.store.req.bits.line.poke(data);dut.io.store.req.bits.lineMask.poke(mask)
            dut.io.store.req.valid.poke(true)
            var guard=0
            var accepted=false
            while(!accepted && guard<100) {accepted=tick()._2;guard+=1}
            assert(guard<100);dut.io.store.req.valid.poke(false)
            dut.io.store.req.bits.paddr.poke(0);dut.io.store.req.bits.line.poke(0)
        }
    }

    "native reads retain their address through stalls and drain two SRAM replies" in {
        simulate(new PeScratchpadBridge) { dut =>
            val d=new Driver(dut);import d._
            ready=c=>c>=4 && c%5!=2
            for((address,slot)<-Seq((0xb0001040L,3),(0xb0002080L,5),(0xb00010c0L,1))) {
                val expected=read(address,64)
                load(address,slot);drain()
                assert(loads.last==((slot,expected,0)))
            }
            assert(requests.map(_._1).toVector==Vector(0xb0001040L,0xb0001060L,0xb0002080L,0xb00020a0L,0xb00010c0L,0xb00010e0L))
            assert(stores.isEmpty && loads.size==3)
        }
    }

    "native Stores preserve both halves, masks and a stalled completion" in {
        simulate(new PeScratchpadBridge) { dut =>
            val d=new Driver(dut);import d._
            ready=c=>c%4!=1
            val address=0xb0002040L
            val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt((i*29+11)&255)<<(8*i)))
            val mask=BigInt("d7ff0fff3fffbffd",16)
            val expected=(0 until 64).foldLeft(read(address,64)) { (v,i)=>
                if(mask.testBit(i)) (v & ~(BigInt(255)<<(8*i))) | (data & (BigInt(255)<<(8*i))) else v
            }
            dut.io.store.rsp.ready.poke(false)
            store(address,data,mask)
            for(_<-0 until 12) tick()
            dut.io.store.rsp.valid.expect(true);dut.io.store.rsp.bits.exception.expect(0)
            assert(stores.isEmpty && read(address,64)==expected)
            dut.io.store.rsp.ready.poke(true);drain()
            assert(stores.toVector==Vector(0))
            load(address,2);drain();assert(loads.last==((2,expected,0)))
        }
    }

    "errors from either SRAM beat reach their single logical completion" in {
        simulate(new PeScratchpadBridge) { dut =>
            val d=new Driver(dut);import d._
            for(offset<-Seq(0L,32L)) {
                val address=0xb0003000L+offset*4
                fault+=address+offset
                load(address,1);drain();assert(loads.last._3==5)
                store(address,BigInt(0),BigInt("f"*16,16));drain();assert(stores.last==7)
                fault-=address+offset
            }
            assert(loads.size==2 && stores.size==2 && requests.size==8)
        }
    }

    "masked Stores issue only live halves and keep one backpressured completion" in {
        simulate(new PeScratchpadBridge) { dut =>
            val d=new Driver(dut);import d._
            ready=c=>c%5!=1 && c%5!=2
            val masks=Seq(BigInt("ffffffff",16),BigInt("ffffffff00000000",16),
                BigInt(0),BigInt(1),BigInt(1)<<63,BigInt("ff000000000000ff",16))
            for((mask,index)<-masks.zipWithIndex) {
                val address=0xb0005000L+index*64L
                val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt((i*17+index)&255)<<(8*i)))
                val expected=(0 until 64).foldLeft(read(address,64)) { (v,i)=>
                    if(mask.testBit(i)) (v & ~(BigInt(255)<<(8*i))) | (data & (BigInt(255)<<(8*i))) else v
                }
                val before=requests.size
                dut.io.store.rsp.ready.poke(false)
                store(address,data,mask)
                for(_<-0 until 12)tick()
                dut.io.store.rsp.valid.expect(true)
                dut.io.store.rsp.bits.exception.expect(0)
                assert(stores.size==index && read(address,64)==expected)
                val offsets=Seq(0,32).filter(offset=>((mask>>offset)&BigInt("ffffffff",16))!=0)
                assert(requests.drop(before).map(_._1).toVector==offsets.map(address+_).toVector)
                dut.io.store.rsp.ready.poke(true);drain()
                assert(stores.size==index+1 && stores.last==0)
                load(address,index%8);drain()
                assert(loads.last==((index%8,expected,0)))
            }
        }
    }

    "inactive Store halves do not raise SRAM errors and single replies hand off to Loads" in {
        simulate(new PeScratchpadBridge) { dut =>
            val d=new Driver(dut);import d._
            for(upper<-Seq(false,true)) {
                val address=if(upper)0xb0006080L else 0xb0006000L
                val active=address+(if(upper)32 else 0)
                val inactive=address+(if(upper)0 else 32)
                val mask=BigInt("ffffffff",16)<<(if(upper)32 else 0)
                fault+=inactive
                val before=requests.size
                store(address,BigInt("12345678"*16,16),mask)
                load(address,4);drain()
                assert(stores.last==0)
                assert(requests.drop(before).map(_._1).toVector==Vector(active,address,address+32))
                assert(loads.last._3==5)
                fault-=inactive;fault+=active
                store(address,BigInt(0),mask);drain();assert(stores.last==7)
                fault-=active
            }
        }
    }

    "a completing Store can hand the SRAM port to a waiting Load" in {
        simulate(new PeScratchpadBridge) { dut =>
            val d=new Driver(dut);import d._
            val address=0xb0004000L
            val data=BigInt("12345678"*16,16)
            store(address,data,BigInt("f"*16,16))
            load(address,4);drain()
            assert(stores.toVector==Vector(0) && loads.toVector==Vector((4,data,0)))
            assert(requests.map(_._2).toVector==Vector(true,true,false,false))
        }
    }
}
