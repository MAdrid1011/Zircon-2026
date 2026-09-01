package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.{OrderedIOCombiner, OrderedIOGroupStreamer}

class OrderedIOCombinerSpec extends AnyFunSpec with ChiselSim {
  private def driveRequest(
      dut: OrderedIOCombiner,
      order: Long,
      address: BigInt,
      write: Boolean = true,
      size: Int = 2,
      burstable: Boolean = true,
      regionTag: Int = 1
  ): Unit = {
    dut.io.in.bits.order.poke(order)
    dut.io.in.bits.robTag.poke((order % 24).toInt)
    dut.io.in.bits.address.poke(address)
    dut.io.in.bits.write.poke(write)
    dut.io.in.bits.size.poke(size)
    dut.io.in.bits.writeData.poke(address & BigInt("ffffffff", 16))
    dut.io.in.bits.writeMask.poke(if (write) 15 else 0)
    dut.io.in.bits.burstable.poke(burstable)
    dut.io.in.bits.regionTag.poke(regionTag)
  }

  private def driveGroup(
      dut: OrderedIOGroupStreamer,
      count: Int,
      baseAddress: BigInt = BigInt("b0003000", 16)
  ): Unit = {
    dut.io.group.bits.count.poke(count)
    for (index <- 0 until 4) {
      val request = dut.io.group.bits.requests(index)
      request.order.poke(50 + index)
      request.robTag.poke(8 + index)
      request.address.poke(baseAddress + index * 4)
      request.write.poke(false)
      request.size.poke(2)
      request.writeData.poke(0)
      request.writeMask.poke(0)
      request.burstable.poke(true)
      request.regionTag.poke(1)
    }
  }

  describe("OrderedIOCombiner") {
    it("combines adjacent program-order accesses and flushes deterministically") {
      simulate(new OrderedIOCombiner) { dut =>
        dut.io.out.ready.poke(true)
        dut.io.forceFlush.poke(false)
        dut.io.cancel.poke(false)
        dut.io.in.valid.poke(true)

        driveRequest(dut, 10, BigInt("b0001000", 16))
        dut.io.in.ready.expect(true)
        dut.clock.step()

        driveRequest(dut, 11, BigInt("b0001004", 16))
        dut.io.in.ready.expect(true)
        dut.clock.step()

        dut.io.in.valid.poke(false)
        dut.io.forceFlush.poke(true)
        dut.io.out.valid.expect(true)
        dut.io.out.bits.count.expect(2)
        dut.io.out.bits.requests(0).address.expect(BigInt("b0001000", 16))
        dut.io.out.bits.requests(1).address.expect(BigInt("b0001004", 16))
        dut.clock.step()
        dut.io.out.valid.expect(false)
      }
    }

    it("does not combine across a 4 KiB boundary") {
      simulate(new OrderedIOCombiner) { dut =>
        dut.io.out.ready.poke(true)
        dut.io.forceFlush.poke(false)
        dut.io.cancel.poke(false)
        dut.io.in.valid.poke(true)

        driveRequest(dut, 20, BigInt("b0000ffc", 16))
        dut.clock.step()
        driveRequest(dut, 21, BigInt("b0001000", 16))
        dut.io.out.valid.expect(true)
        dut.io.in.ready.expect(false)
        dut.io.out.bits.count.expect(1)
      }
    }

    it("emits strong-order requests as one-beat groups") {
      simulate(new OrderedIOCombiner) { dut =>
        dut.io.out.ready.poke(true)
        dut.io.forceFlush.poke(false)
        dut.io.cancel.poke(false)
        dut.io.in.valid.poke(true)
        driveRequest(dut, 30, BigInt("a00003f8", 16), burstable = false)
        dut.clock.step()
        dut.io.in.valid.poke(false)
        dut.io.out.valid.expect(true)
        dut.io.out.bits.count.expect(1)
      }
    }

    it("cancels a local group before it reaches an external owner") {
      simulate(new OrderedIOCombiner) { dut =>
        dut.io.out.ready.poke(true)
        dut.io.forceFlush.poke(false)
        dut.io.cancel.poke(false)
        dut.io.in.valid.poke(true)
        driveRequest(dut, 40, BigInt("b0002000", 16))
        dut.io.in.ready.expect(true)
        dut.clock.step()

        dut.io.in.valid.poke(false)
        dut.io.cancel.poke(true)
        dut.io.out.valid.expect(false)
        dut.clock.step()
        dut.io.cancel.poke(false)
        dut.io.out.valid.expect(false)
      }
    }
  }

  describe("OrderedIOGroupStreamer") {
    it("streams every member before allowing its complete group to be accepted") {
      simulate(new OrderedIOGroupStreamer) { dut =>
        dut.io.group.valid.poke(false)
        dut.io.request.ready.poke(false)
        dut.io.accepted.poke(false)
        dut.io.cancel.poke(false)

        driveGroup(dut, count = 4)
        dut.io.group.valid.poke(true)
        dut.io.group.ready.expect(true)
        dut.clock.step()
        dut.io.group.valid.poke(false)

        for (index <- 0 until 4) {
          dut.io.active.expect(true)
          dut.io.request.valid.expect(true)
          dut.io.request.bits.order.expect(50 + index)
          dut.io.request.bits.robTag.expect(8 + index)
          dut.io.request.bits.address.expect(BigInt("b0003000", 16) + index * 4)
          dut.io.forceFlush.expect(false)
          dut.io.request.ready.poke(true)
          dut.clock.step()
          dut.io.request.ready.poke(false)
        }

        dut.io.request.valid.expect(false)
        dut.io.forceFlush.expect(true)
        dut.io.accepted.poke(true)
        dut.clock.step()
        dut.io.accepted.poke(false)
        dut.io.active.expect(false)
      }
    }

    it("cancels a held group before its external acceptance") {
      simulate(new OrderedIOGroupStreamer) { dut =>
        dut.io.group.valid.poke(false)
        dut.io.request.ready.poke(false)
        dut.io.accepted.poke(false)
        dut.io.cancel.poke(false)

        driveGroup(dut, count = 2)
        dut.io.group.valid.poke(true)
        dut.io.group.ready.expect(true)
        dut.clock.step()
        dut.io.group.valid.poke(false)
        dut.io.request.valid.expect(true)

        dut.io.cancel.poke(true)
        dut.io.request.valid.expect(false)
        dut.io.forceFlush.expect(false)
        dut.clock.step()
        dut.io.cancel.poke(false)
        dut.io.active.expect(false)
      }
    }
  }
}
