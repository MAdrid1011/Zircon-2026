package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, SourceKind, UopClass}
import zircon.memory.M0RequestArbiter

class M0RequestArbiterSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: M0RequestArbiter): Unit = {
    for (input <- Seq(dut.io.direct, dut.io.replay)) {
      input.valid.poke(false)
      input.bits.request.uop.robTag.poke(0)
      input.bits.request.uop.allowedEndpoints.poke(EndpointMask.M0)
      input.bits.request.uop.uopClass.poke(UopClass.Load)
      input.bits.request.uop.operation.poke(0)
      input.bits.request.uop.sourceKind.foreach(_.poke(SourceKind.None))
      input.bits.request.uop.sourcePhysical.foreach(_.poke(0))
      input.bits.request.uop.sourceReady.foreach(_.poke(true))
      input.bits.request.uop.destinationPhysical.poke(32)
      input.bits.request.uop.writesInteger.poke(true)
      input.bits.request.uop.writesFloat.poke(false)
      input.bits.request.uop.immediate.poke(0)
      input.bits.request.base.poke(0)
      input.bits.request.storeData.poke(0)
      input.bits.request.atomicAq.poke(false)
      input.bits.request.atomicRl.poke(false)
      input.bits.address.robTag.poke(0)
      input.bits.address.legalMemoryOperation.poke(true)
      input.bits.address.isLoad.poke(true)
      input.bits.address.isStore.poke(false)
      input.bits.address.isAtomic.poke(false)
      input.bits.address.unsignedLoad.poke(false)
      input.bits.address.accessSize.poke(2)
      input.bits.address.address.poke(0)
      input.bits.address.readMask.poke(15)
      input.bits.address.writeMask.poke(0)
      input.bits.address.writeData.poke(0)
      input.bits.address.pmaKind.poke(PMARegionKind.Memory.code)
      input.bits.address.naturallyAligned.poke(true)
      input.bits.address.m1Eligible.poke(false)
      input.bits.address.faultValid.poke(false)
      input.bits.address.faultCause.poke(0)
      input.bits.address.faultTval.poke(0)
      input.bits.address.aq.poke(false)
      input.bits.address.rl.poke(false)
      input.bits.m1Owner.poke(false)
    }
    dut.io.output.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(
      dut: M0RequestArbiter,
      replay: Boolean,
      tag: Int,
      address: BigInt
  ): Unit = {
    val input = if (replay) dut.io.replay else dut.io.direct
    input.valid.poke(true)
    input.bits.request.uop.robTag.poke(tag)
    input.bits.address.robTag.poke(tag)
    input.bits.address.address.poke(address)
    input.bits.m1Owner.poke(replay)
  }

  describe("M0RequestArbiter") {
    it("chooses the older replay across the ROB index wrap") {
      simulate(new M0RequestArbiter) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, replay = false, tag = 0, BigInt("80001000", 16))
        drive(dut, replay = true, tag = 21, BigInt("a0000000", 16))
        dut.io.output.ready.poke(true)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.address.robTag.expect(21)
        dut.io.replay.ready.expect(true)
        dut.io.direct.ready.expect(false)
      }
    }

    it("passes the direct M0 path when no replay is pending") {
      simulate(new M0RequestArbiter) { dut =>
        clear(dut)
        drive(dut, replay = false, tag = 4, BigInt("80002000", 16))
        dut.io.output.ready.poke(true)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.address.robTag.expect(4)
        dut.io.direct.ready.expect(true)
        dut.io.replay.ready.expect(false)
      }
    }

    it("locks a selected source while backpressured despite a later older replay") {
      simulate(new M0RequestArbiter) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(1)
        drive(dut, replay = false, tag = 4, BigInt("80003000", 16))
        dut.io.output.valid.expect(true)
        dut.io.output.bits.address.robTag.expect(4)
        dut.clock.step()

        drive(dut, replay = true, tag = 3, BigInt("a0000000", 16))
        dut.io.output.bits.address.robTag.expect(4)
        dut.io.direct.ready.expect(false)
        dut.io.replay.ready.expect(false)
        dut.io.output.ready.poke(true)
        dut.io.direct.ready.expect(true)
        dut.clock.step()

        dut.io.direct.valid.poke(false)
        dut.io.output.valid.expect(true)
        dut.io.output.bits.address.robTag.expect(3)
      }
    }

    it("suppresses all transfers during selective recovery and global flush") {
      simulate(new M0RequestArbiter) { dut =>
        clear(dut)
        drive(dut, replay = false, tag = 4, BigInt("80004000", 16))
        drive(dut, replay = true, tag = 5, BigInt("a0000000", 16))
        dut.io.output.ready.poke(true)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(3)
        dut.io.output.valid.expect(false)
        dut.io.direct.ready.expect(false)
        dut.io.replay.ready.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.flush.poke(true)
        dut.io.output.valid.expect(false)
        dut.io.direct.ready.expect(false)
        dut.io.replay.ready.expect(false)
      }
    }
  }
}
