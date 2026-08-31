package zircon

import chisel3.util.log2Ceil

sealed trait PMARegionKind { def code: Int }
object PMARegionKind {
  case object Inaccessible extends PMARegionKind { val code = 0 }
  case object Memory extends PMARegionKind { val code = 1 }
  case object DeviceStrong extends PMARegionKind { val code = 2 }
  case object DeviceBurstable extends PMARegionKind { val code = 3 }
}

final case class PMAEntry(
    base: BigInt,
    mask: BigInt,
    kind: PMARegionKind,
    readable: Boolean = true,
    writable: Boolean = true,
    executable: Boolean = false,
    atomic: Boolean = false
) {
  require(base >= 0 && base < (BigInt(1) << 32), "PMA base must be a 32-bit address")
  require(mask >= 0 && mask < (BigInt(1) << 32), "PMA mask must be 32 bits")

  def contains(address: BigInt): Boolean =
    (address & mask) == (base & mask)
}

final case class CacheConfig(
    bytes: Int,
    ways: Int,
    lineBytes: Int,
    mshrs: Int
) {
  require(bytes > 0 && (bytes & (bytes - 1)) == 0, "cache size must be a power of two")
  require(ways > 0 && (ways & (ways - 1)) == 0, "ways must be a power of two")
  require(lineBytes == 32, "Zircon-2026 uses a 32-byte cache line")
  require(bytes % (ways * lineBytes) == 0, "cache geometry must have an integer set count")
  require(mshrs > 0)

  val sets: Int = bytes / (ways * lineBytes)
}

final case class ZirconCoreConfig(
    resetVector: BigInt = BigInt("80000000", 16),
    hartId: Int = 0,
    enableTrace: Boolean = false,
    fetchWidth: Int = 4,
    decodeWidth: Int = 2,
    commitWidth: Int = 2,
    maxIssue: Int = 3,
    completionWidth: Int = 2,
    robEntries: Int = 24,
    intPhysicalRegisters: Int = 56,
    intIssueEntries: Int = 12,
    longIssueEntries: Int = 4,
    memIssueEntries: Int = 8,
    loadQueueEntries: Int = 8,
    storeQueueEntries: Int = 8,
    branchDataEntries: Int = 8,
    l1i: CacheConfig = CacheConfig(1024, 2, 32, 1),
    l1d: CacheConfig = CacheConfig(1024, 2, 32, 4),
    l2: CacheConfig = CacheConfig(4096, 4, 32, 4),
    pma: Seq[PMAEntry] = ZirconCoreConfig.defaultPMA
) {
  require(fetchWidth == 4)
  require(decodeWidth == 2 && commitWidth == 2)
  require(maxIssue == 3 && completionWidth == 2)
  require(robEntries == 24)
  require(intPhysicalRegisters == 56)
  require(l1i.bytes == 1024 && l1d.bytes == 1024)
  require(l2.bytes == 4096 || l2.bytes == 8192, "L2 is restricted to the measured 4/8 KiB points")
  require(resetVector >= 0 && resetVector < (BigInt(1) << 32))
  require(hartId >= 0 && BigInt(hartId) < (BigInt(1) << 32),
    "hart ID must be representable by RV32 mhartid")

  val robIndexWidth: Int = log2Ceil(robEntries)
  val robTagWidth: Int = robIndexWidth + 1
}

object ZirconCoreConfig {
  val defaultPMA: Seq[PMAEntry] = Seq(
    PMAEntry(
      base = BigInt("80000000", 16),
      mask = BigInt("f0000000", 16),
      kind = PMARegionKind.Memory,
      executable = true,
      atomic = true
    ),
    PMAEntry(
      base = BigInt("a0000000", 16),
      mask = BigInt("ffff0000", 16),
      kind = PMARegionKind.DeviceStrong
    ),
    PMAEntry(
      base = BigInt("b0000000", 16),
      mask = BigInt("f0000000", 16),
      kind = PMARegionKind.DeviceBurstable
    )
  )

  val default: ZirconCoreConfig = ZirconCoreConfig()
  val l2EightKiB: ZirconCoreConfig = ZirconCoreConfig(l2 = CacheConfig(8192, 4, 32, 4))
}
