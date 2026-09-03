package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{EndpointMask, SourceKind, UopClass}
import zircon.frontend.{FloatingOperation, IntOperation}
import zircon.memory.MemoryAddressUnit

class MemoryAddressUnitSpec extends AnyFunSpec with ChiselSim {
  private def drive(
      dut: MemoryAddressUnit,
      operation: IntOperation.Type,
      uopClass: UopClass.Type,
      endpoints: Int,
      base: BigInt,
      immediate: BigInt = 0,
      storeData: BigInt = 0,
      aq: Boolean = false,
      rl: Boolean = false
  ): Unit = {
    dut.io.valid.poke(true)
    val request = dut.io.request
    request.uop.robTag.poke(3)
    request.uop.allowedEndpoints.poke(endpoints)
    request.uop.uopClass.poke(uopClass)
    request.uop.operation.poke(operation.asUInt.litValue)
    request.uop.sourceKind.foreach(_.poke(SourceKind.None))
    request.uop.sourcePhysical.foreach(_.poke(0))
    request.uop.sourceReady.foreach(_.poke(true))
    request.uop.destinationPhysical.poke(32)
    request.uop.writesInteger.poke(uopClass != UopClass.Store)
    request.uop.writesFloat.poke(false)
    request.uop.floatingOperation.poke(FloatingOperation.Invalid)
    request.uop.immediate.poke(immediate)
    request.base.poke(base)
    request.storeData.poke(storeData)
    request.floatingStoreData.poke(0)
    request.atomicAq.poke(aq)
    request.atomicRl.poke(rl)
  }

  describe("MemoryAddressUnit") {
    it("derives RV32I byte lanes and aligned store data") {
      simulate(new MemoryAddressUnit) { dut =>
        drive(dut, IntOperation.Lbu, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          BigInt("80001003", 16))
        dut.io.result.address.expect(BigInt("80001003", 16))
        dut.io.result.accessSize.expect(0)
        dut.io.result.readMask.expect(8)
        dut.io.result.unsignedLoad.expect(true)
        dut.io.result.naturallyAligned.expect(true)
        dut.io.result.m1Eligible.expect(true)

        drive(dut, IntOperation.Sh, UopClass.Store, EndpointMask.M0,
          BigInt("80001002", 16), storeData = BigInt("a1b2", 16))
        dut.io.result.accessSize.expect(1)
        dut.io.result.writeMask.expect(12)
        dut.io.result.writeData.expect(BigInt("a1b20000", 16))
        dut.io.result.naturallyAligned.expect(true)
      }
    }

    it("creates exact misalignment faults before PMA access") {
      simulate(new MemoryAddressUnit) { dut =>
        drive(dut, IntOperation.Lh, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          BigInt("80001001", 16))
        dut.io.result.faultValid.expect(true)
        dut.io.result.faultCause.expect(4)
        dut.io.result.faultTval.expect(BigInt("80001001", 16))
        dut.io.result.m1Eligible.expect(false)

        drive(dut, IntOperation.Sw, UopClass.Store, EndpointMask.M0,
          BigInt("80001002", 16))
        dut.io.result.faultValid.expect(true)
        dut.io.result.faultCause.expect(6)
        dut.io.result.faultTval.expect(BigInt("80001002", 16))
      }
    }

    it("classifies PMA read, write, and atomic denials with exact causes") {
      simulate(new MemoryAddressUnit) { dut =>
        drive(dut, IntOperation.Lw, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          BigInt("40000000", 16))
        dut.io.result.faultValid.expect(true)
        dut.io.result.faultCause.expect(5)

        drive(dut, IntOperation.Sw, UopClass.Store, EndpointMask.M0,
          BigInt("40000000", 16))
        dut.io.result.faultValid.expect(true)
        dut.io.result.faultCause.expect(7)

        drive(dut, IntOperation.LrW, UopClass.Atomic, EndpointMask.M0,
          BigInt("a0000000", 16))
        dut.io.result.faultValid.expect(true)
        dut.io.result.faultCause.expect(5)
        dut.io.result.m1Eligible.expect(false)
      }
    }

    it("routes only normal aligned Memory loads to M1") {
      simulate(new MemoryAddressUnit) { dut =>
        drive(dut, IntOperation.Lw, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          BigInt("80002000", 16))
        dut.io.result.m1Eligible.expect(true)
        dut.io.result.faultValid.expect(false)

        drive(dut, IntOperation.Lw, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          BigInt("a0000000", 16))
        dut.io.result.m1Eligible.expect(false)
        dut.io.result.faultValid.expect(false)

        drive(dut, IntOperation.AmoAddW, UopClass.Atomic, EndpointMask.M0,
          BigInt("80002000", 16), aq = true, rl = true)
        dut.io.result.isLoad.expect(true)
        dut.io.result.isStore.expect(true)
        dut.io.result.isAtomic.expect(true)
        dut.io.result.readMask.expect(15)
        dut.io.result.writeMask.expect(15)
        dut.io.result.m1Eligible.expect(false)
        dut.io.result.aq.expect(true)
        dut.io.result.rl.expect(true)
      }
    }

    it("classifies aligned FLW and FSW through the cacheable LSU path") {
      simulate(new MemoryAddressUnit) { dut =>
        dut.io.valid.poke(true)
        dut.io.request.uop.robTag.poke(4)
        dut.io.request.uop.allowedEndpoints.poke(EndpointMask.CacheableLoadCandidate)
        dut.io.request.uop.uopClass.poke(UopClass.Load)
        dut.io.request.uop.operation.poke(0)
        dut.io.request.uop.floatingOperation.poke(FloatingOperation.Flw)
        dut.io.request.uop.sourceKind.foreach(_.poke(SourceKind.None))
        dut.io.request.uop.sourcePhysical.foreach(_.poke(0))
        dut.io.request.uop.sourceReady.foreach(_.poke(true))
        dut.io.request.uop.immediate.poke(4)
        dut.io.request.base.poke(BigInt("80001000", 16))
        dut.io.request.storeData.poke(0)
        dut.io.request.floatingStoreData.poke(0)
        dut.io.result.isLoad.expect(true)
        dut.io.result.isStore.expect(false)
        dut.io.result.readMask.expect(15)
        dut.io.result.m1Eligible.expect(true)
        dut.io.result.faultValid.expect(false)

        dut.io.request.uop.uopClass.poke(UopClass.Store)
        dut.io.request.uop.floatingOperation.poke(FloatingOperation.Fsw)
        dut.io.request.floatingStoreData.poke(BigInt("3f800000", 16))
        dut.io.result.isLoad.expect(false)
        dut.io.result.isStore.expect(true)
        dut.io.result.writeMask.expect(15)
        dut.io.result.writeData.expect(BigInt("3f800000", 16))
        dut.io.result.m1Eligible.expect(false)
        dut.io.result.faultValid.expect(false)
      }
    }
  }
}
