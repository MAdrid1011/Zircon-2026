package zircon

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CoreConfigSpec extends AnyFunSpec with Matchers {
  describe("ZirconCoreConfig") {
    it("freezes the architectural resource points") {
      val cfg = ZirconCoreConfig.default
      cfg.fetchWidth shouldBe 4
      cfg.decodeWidth shouldBe 2
      cfg.commitWidth shouldBe 2
      cfg.maxIssue shouldBe 3
      cfg.robEntries shouldBe 24
      cfg.intPhysicalRegisters shouldBe 56
      cfg.l1i.sets shouldBe 16
      cfg.l1d.sets shouldBe 16
      cfg.l2.sets shouldBe 32
    }

    it("keeps only the measured four and eight KiB L2 points") {
      ZirconCoreConfig.default.l2.bytes shouldBe 4096
      ZirconCoreConfig.l2EightKiB.l2.bytes shouldBe 8192
      an[IllegalArgumentException] should be thrownBy {
        ZirconCoreConfig(l2 = CacheConfig(16384, 4, 32, 4))
      }
    }

    it("classifies the default address map in software") {
      val entries = ZirconCoreConfig.defaultPMA
      entries.find(_.contains(BigInt("80001000", 16))).map(_.kind) shouldBe Some(PMARegionKind.Memory)
      entries.find(_.contains(BigInt("a00003f8", 16))).map(_.kind) shouldBe Some(PMARegionKind.DeviceStrong)
      entries.find(_.contains(BigInt("b1234000", 16))).map(_.kind) shouldBe Some(PMARegionKind.DeviceBurstable)
      entries.exists(_.contains(BigInt("40000000", 16))) shouldBe false
    }
  }
}
