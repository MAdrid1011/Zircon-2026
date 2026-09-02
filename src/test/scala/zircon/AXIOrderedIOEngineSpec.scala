package zircon

import chisel3.simulator.scalatest.ChiselSim
import java.nio.file.{Files, Paths}
import org.scalatest.funspec.AnyFunSpec
import scala.util.Random
import zircon.memory.AXIOrderedIOEngine

class AXIOrderedIOEngineSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: AXIOrderedIOEngine): Unit = {
    dut.io.group.valid.poke(false)
    dut.io.group.bits.count.poke(0)
    dut.io.group.bits.requests.foreach { request =>
      request.order.poke(0)
      request.robTag.poke(0)
      request.address.poke(0)
      request.write.poke(false)
      request.size.poke(2)
      request.writeData.poke(0)
      request.writeMask.poke(0)
      request.burstable.poke(true)
      request.regionTag.poke(0)
    }
    dut.io.response.ready.poke(false)
    dut.io.ar.ready.poke(false)
    dut.io.r.valid.poke(false)
    dut.io.r.bits.id.poke(0)
    dut.io.r.bits.data.poke(0)
    dut.io.r.bits.resp.poke(0)
    dut.io.r.bits.last.poke(false)
    dut.io.aw.ready.poke(false)
    dut.io.w.ready.poke(false)
    dut.io.b.valid.poke(false)
    dut.io.b.bits.id.poke(0)
    dut.io.b.bits.resp.poke(0)
  }

  private def offerGroup(
      dut: AXIOrderedIOEngine,
      addresses: Seq[BigInt],
      write: Boolean,
      size: Int = 2,
      burstable: Boolean = true
  ): Unit = {
    dut.io.group.valid.poke(true)
    dut.io.group.bits.count.poke(addresses.size)
    for ((address, index) <- addresses.zipWithIndex) {
      val request = dut.io.group.bits.requests(index)
      request.order.poke(10 + index)
      request.robTag.poke(4 + index)
      request.address.poke(address)
      request.write.poke(write)
      request.size.poke(size)
      request.writeData.poke(BigInt("10000000", 16) + index)
      request.writeMask.poke(if (write) 15 else 0)
      request.burstable.poke(burstable)
      request.regionTag.poke(7)
    }
    dut.io.group.ready.expect(true)
    dut.clock.step()
    dut.io.group.valid.poke(false)
  }

  private def acceptResponse(
      dut: AXIOrderedIOEngine,
      tag: Int,
      address: BigInt,
      write: Boolean,
      data: BigInt = 0,
      fault: Boolean = false
  ): Unit = {
    dut.io.response.valid.expect(true)
    dut.io.response.bits.robTag.expect(tag)
    dut.io.response.bits.address.expect(address)
    dut.io.response.bits.write.expect(write)
    dut.io.response.bits.readData.expect(data)
    dut.io.response.bits.accessFault.expect(fault)
    dut.io.response.ready.poke(true)
    dut.clock.step()
    dut.io.response.ready.poke(false)
  }

  private def saveRandomFailure(
      seed: Long,
      count: Int,
      write: Boolean,
      fault: Boolean,
      baseAddress: BigInt
  ): Unit = {
    val directory = Paths.get("target", "zircon-failures")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(
      s"ordered-io-random-${java.lang.Long.toHexString(seed)}.txt"),
      s"test=AXIOrderedIOEngineSpec\nseed=0x${java.lang.Long.toHexString(seed)}\n" +
        s"count=$count\nwrite=$write\nfault=$fault\nbase=0x${baseAddress.toString(16)}\n")
  }

  describe("AXIOrderedIOEngine") {
    it("owns a four-beat device write through independent W/AW and one B result") {
      simulate(new AXIOrderedIOEngine) { dut =>
        clear(dut)
        val addresses = (0 until 4).map(index => BigInt("b0001000", 16) + index * 4)
        offerGroup(dut, addresses, write = true)
        dut.io.aw.valid.expect(true)
        dut.io.aw.bits.id.expect(6)
        dut.io.aw.bits.addr.expect(addresses.head)
        dut.io.aw.bits.len.expect(3)
        dut.io.aw.bits.size.expect(2)
        dut.io.aw.bits.cache.expect(0)
        dut.io.w.valid.expect(true)
        dut.io.w.bits.data.expect(BigInt("10000000", 16))
        dut.io.w.bits.last.expect(false)

        // W may advance before AW; both payloads retain ownership independently.
        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.io.w.bits.data.expect(BigInt("10000001", 16))
        dut.io.w.ready.poke(false)
        dut.io.aw.ready.poke(true)
        dut.clock.step()
        dut.io.aw.ready.poke(false)

        for (index <- 1 until 4) {
          dut.io.w.valid.expect(true)
          dut.io.w.bits.data.expect(BigInt("10000000", 16) + index)
          dut.io.w.bits.last.expect(index == 3)
          dut.io.w.ready.poke(true)
          dut.clock.step()
          dut.io.w.ready.poke(false)
        }
        dut.io.b.ready.expect(true)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(6)
        dut.io.b.bits.resp.poke(2)
        dut.clock.step()
        dut.io.b.valid.poke(false)

        for ((address, index) <- addresses.zipWithIndex) {
          acceptResponse(dut, tag = 4 + index, address, write = true, fault = true)
        }
        dut.io.busy.expect(false)
      }
    }

    it("holds read responses and maps every R beat to its exact group member") {
      simulate(new AXIOrderedIOEngine) { dut =>
        clear(dut)
        val addresses = (0 until 3).map(index => BigInt("b0002000", 16) + index * 4)
        offerGroup(dut, addresses, write = false)
        dut.io.ar.valid.expect(true)
        dut.io.ar.bits.id.expect(6)
        dut.io.ar.bits.addr.expect(addresses.head)
        dut.io.ar.bits.len.expect(2)
        dut.io.ar.ready.poke(false)
        dut.clock.step(2)
        dut.io.ar.valid.expect(true)
        dut.io.ar.bits.addr.expect(addresses.head)
        dut.io.ar.ready.poke(true)
        dut.clock.step()
        dut.io.ar.ready.poke(false)

        for ((address, index) <- addresses.zipWithIndex) {
          val data = BigInt("ca000000", 16) + index
          dut.io.r.valid.poke(true)
          dut.io.r.bits.id.poke(6)
          dut.io.r.bits.data.poke(data)
          dut.io.r.bits.resp.poke(if (index == 1) 2 else 0)
          dut.io.r.bits.last.poke(index == addresses.size - 1)
          dut.io.r.ready.expect(true)
          dut.clock.step()
          dut.io.r.valid.poke(false)

          dut.clock.step(2)
          acceptResponse(dut, tag = 4 + index, address, write = false, data = data,
            fault = index == 1)
        }
        dut.io.busy.expect(false)
      }
    }

    it("issues DeviceStrong as one non-cacheable beat") {
      simulate(new AXIOrderedIOEngine) { dut =>
        clear(dut)
        val address = BigInt("a00003f8", 16)
        offerGroup(dut, Seq(address), write = true, size = 0, burstable = false)
        dut.io.aw.valid.expect(true)
        dut.io.aw.bits.len.expect(0)
        dut.io.aw.bits.size.expect(0)
        dut.io.aw.bits.cache.expect(0)
        dut.io.aw.ready.poke(true)
        dut.io.w.ready.poke(true)
        dut.clock.step()
        dut.io.aw.ready.poke(false)
        dut.io.w.ready.poke(false)
        dut.io.b.valid.poke(true)
        dut.io.b.bits.id.poke(6)
        dut.io.b.bits.resp.poke(0)
        dut.io.b.ready.expect(true)
        dut.clock.step()
        dut.io.b.valid.poke(false)
        acceptResponse(dut, tag = 4, address, write = true)
      }
    }

    it("runs explicit-seed one-to-four beat groups with independent channel pressure") {
      val seeds = Seq(0x5eed0101L, 0x5eed0102L, 0x5eed0103L, 0x5eed0104L)
      for ((seed, index) <- seeds.zipWithIndex) {
        simulate(new AXIOrderedIOEngine) { dut =>
          clear(dut)
          val random = new Random(seed)
          val count = index + 1
          val write = index % 2 == 0
          val fault = if (write) index == 2 else true
          val faultBeat = if (write) -1 else random.nextInt(count)
          val baseAddress = BigInt("b0004000", 16) + random.nextInt(64) * 16
          val addresses = (0 until count).map(offset => baseAddress + offset * 4)
          try {
            offerGroup(dut, addresses, write = write)
            if (write) {
              var awAccepted = false
              var sentBeats = 0
              for (attempt <- 0 until 64 if !awAccepted || sentBeats < count) {
                dut.io.aw.ready.poke(attempt == 63 || random.nextBoolean())
                dut.io.w.ready.poke(attempt == 63 || random.nextBoolean())
                val awFire = dut.io.aw.valid.peek().litToBoolean &&
                  dut.io.aw.ready.peek().litToBoolean
                val wFire = dut.io.w.valid.peek().litToBoolean &&
                  dut.io.w.ready.peek().litToBoolean
                if (wFire) {
                  dut.io.w.bits.data.expect(BigInt("10000000", 16) + sentBeats)
                  dut.io.w.bits.last.expect(sentBeats == count - 1)
                }
                dut.clock.step()
                awAccepted ||= awFire
                if (wFire) sentBeats += 1
              }
              assert(awAccepted && sentBeats == count,
                s"seed=0x${java.lang.Long.toHexString(seed)} did not issue write group")
              dut.io.aw.ready.poke(false)
              dut.io.w.ready.poke(false)
              dut.io.b.valid.poke(true)
              dut.io.b.bits.id.poke(6)
              dut.io.b.bits.resp.poke(if (fault) 2 else 0)
              dut.io.b.ready.expect(true)
              dut.clock.step()
              dut.io.b.valid.poke(false)
              for ((address, member) <- addresses.zipWithIndex) {
                if (random.nextBoolean()) {
                  dut.io.response.valid.expect(true)
                  dut.clock.step()
                }
                acceptResponse(dut, 4 + member, address, write = true, fault = fault)
              }
            } else {
              var arAccepted = false
              for (attempt <- 0 until 64 if !arAccepted) {
                dut.io.ar.ready.poke(attempt == 63 || random.nextBoolean())
                val arFire = dut.io.ar.valid.peek().litToBoolean &&
                  dut.io.ar.ready.peek().litToBoolean
                dut.clock.step()
                arAccepted ||= arFire
              }
              assert(arAccepted,
                s"seed=0x${java.lang.Long.toHexString(seed)} did not issue read group")
              dut.io.ar.ready.poke(false)
              for ((address, member) <- addresses.zipWithIndex) {
                val data = BigInt("c0000000", 16) + member
                dut.io.r.valid.poke(true)
                dut.io.r.bits.id.poke(6)
                dut.io.r.bits.data.poke(data)
                dut.io.r.bits.resp.poke(if (member == faultBeat) 2 else 0)
                dut.io.r.bits.last.poke(member == count - 1)
                dut.io.r.ready.expect(true)
                dut.clock.step()
                dut.io.r.valid.poke(false)
                if (random.nextBoolean()) {
                  dut.io.response.valid.expect(true)
                  dut.clock.step()
                }
                acceptResponse(dut, 4 + member, address, write = false, data = data,
                  fault = member == faultBeat)
              }
            }
            dut.io.busy.expect(false)
          } catch {
            case failure: Throwable =>
              saveRandomFailure(seed, count, write, fault, baseAddress)
              throw failure
          }
        }
      }
    }
  }
}
