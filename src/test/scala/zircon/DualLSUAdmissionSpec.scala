package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, SourceKind, UopClass}
import zircon.frontend.IntOperation
import zircon.memory.DualLSUAdmission

class DualLSUAdmissionSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: DualLSUAdmission): Unit = {
    for (input <- Seq(dut.io.m0Input, dut.io.m1Input)) {
      input.valid.poke(false)
      input.bits.uop.robTag.poke(0)
      input.bits.uop.allowedEndpoints.poke(0)
      input.bits.uop.uopClass.poke(UopClass.Load)
      input.bits.uop.operation.poke(0)
      input.bits.uop.sourceKind.foreach(_.poke(SourceKind.None))
      input.bits.uop.sourcePhysical.foreach(_.poke(0))
      input.bits.uop.sourceReady.foreach(_.poke(true))
      input.bits.uop.destinationPhysical.poke(32)
      input.bits.uop.writesInteger.poke(true)
      input.bits.uop.writesFloat.poke(false)
      input.bits.uop.immediate.poke(0)
      input.bits.base.poke(0)
      input.bits.storeData.poke(0)
      input.bits.atomicAq.poke(false)
      input.bits.atomicRl.poke(false)
    }
    dut.io.m0Issue.ready.poke(false)
    dut.io.m1Issue.ready.poke(false)
    dut.io.m1Replay.ready.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
  }

  private def drive(
      dut: DualLSUAdmission,
      m1: Boolean,
      tag: Int,
      operation: IntOperation.Type,
      uopClass: UopClass.Type,
      endpoints: Int,
      address: BigInt
  ): Unit = {
    val input = if (m1) dut.io.m1Input else dut.io.m0Input
    input.valid.poke(true)
    input.bits.uop.robTag.poke(tag)
    input.bits.uop.allowedEndpoints.poke(endpoints)
    input.bits.uop.uopClass.poke(uopClass)
    input.bits.uop.operation.poke(operation.asUInt.litValue)
    input.bits.uop.sourceKind.foreach(_.poke(SourceKind.None))
    input.bits.uop.sourcePhysical.foreach(_.poke(0))
    input.bits.uop.sourceReady.foreach(_.poke(true))
    input.bits.uop.destinationPhysical.poke(32)
    input.bits.uop.writesInteger.poke(uopClass != UopClass.Store)
    input.bits.uop.writesFloat.poke(false)
    input.bits.uop.immediate.poke(0)
    input.bits.base.poke(address)
    input.bits.storeData.poke(BigInt("cafebabe", 16))
    input.bits.atomicAq.poke(false)
    input.bits.atomicRl.poke(false)
  }

  describe("DualLSUAdmission") {
    it("admits only an eligible M1 load to the load pipeline") {
      simulate(new DualLSUAdmission) { dut =>
        clear(dut)
        drive(dut, m1 = true, 4, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, BigInt("80001000", 16))
        dut.io.m1Issue.ready.poke(true)
        dut.io.m1Issue.valid.expect(true)
        dut.io.m1Issue.bits.address.m1Eligible.expect(true)
        dut.io.m1Issue.bits.address.robTag.expect(4)
        dut.io.m1Input.ready.expect(true)
        dut.clock.step()
        dut.io.m1Input.valid.poke(false)
        dut.io.replayOccupied.expect(false)
      }
    }

    it("holds an ineligible M1 request as a stable M0 replay") {
      simulate(new DualLSUAdmission) { dut =>
        clear(dut)
        drive(dut, m1 = true, 5, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, BigInt("a0000000", 16))
        dut.io.m1Input.ready.expect(true)
        dut.io.m1Issue.valid.expect(false)
        dut.clock.step()
        dut.io.m1Input.valid.poke(false)
        dut.io.replayOccupied.expect(true)
        dut.io.m1Replay.valid.expect(true)
        dut.io.m1Replay.bits.request.uop.robTag.expect(5)
        dut.io.m1Replay.bits.address.m1Eligible.expect(false)
        dut.io.m1Replay.ready.poke(false)
        dut.clock.step()
        dut.io.m1Replay.bits.request.uop.robTag.expect(5)
        dut.io.m1Replay.ready.poke(true)
        dut.clock.step()
        dut.io.replayOccupied.expect(false)
      }
    }

    it("routes M1 misalignment and atomics to replay while preserving direct M0 faults") {
      simulate(new DualLSUAdmission) { dut =>
        clear(dut)
        drive(dut, m1 = true, 6, IntOperation.Lh, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, BigInt("80001001", 16))
        dut.clock.step()
        dut.io.m1Input.valid.poke(false)
        dut.io.m1Replay.valid.expect(true)
        dut.io.m1Replay.bits.address.faultValid.expect(true)
        dut.io.m1Replay.bits.address.faultCause.expect(4)
        dut.io.m1Replay.ready.poke(true)
        dut.clock.step()

        drive(dut, m1 = false, 7, IntOperation.AmoAddW, UopClass.Atomic,
          EndpointMask.M0, BigInt("80001000", 16))
        dut.io.m0Issue.ready.poke(true)
        dut.io.m0Issue.valid.expect(true)
        dut.io.m0Issue.bits.address.isAtomic.expect(true)
        dut.io.m0Issue.bits.address.m1Eligible.expect(false)
      }
    }

    it("drops only a younger replay on selective recovery and clears all replay on flush") {
      simulate(new DualLSUAdmission) { dut =>
        clear(dut)
        dut.io.robHeadTag.poke(20)
        drive(dut, m1 = true, 0, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, BigInt("a0000000", 16))
        dut.clock.step()
        dut.io.m1Input.valid.poke(false)
        dut.io.replayOccupied.expect(true)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(23)
        dut.io.m1Replay.valid.expect(false)
        dut.clock.step()
        dut.io.squash.valid.poke(false)
        dut.io.replayOccupied.expect(false)

        drive(dut, m1 = true, 21, IntOperation.Lw, UopClass.Load,
          EndpointMask.CacheableLoadCandidate, BigInt("a0000000", 16))
        dut.clock.step()
        dut.io.m1Input.valid.poke(false)
        dut.io.replayOccupied.expect(true)
        dut.io.flush.poke(true)
        dut.clock.step()
        dut.io.flush.poke(false)
        dut.io.replayOccupied.expect(false)
      }
    }
  }
}
