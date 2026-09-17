package ZirconConfig

import chisel3.util._

case class DCacheParams(loadEntries: Int = 8) {
    require(loadEntries >= 2 && isPow2(loadEntries))
    val slotWidth: Int = log2Ceil(loadEntries)
}

case class L2CacheParams(
    sets: Int = 32,
    ways: Int = 4,
    lineBytes: Int = 32,
    maxInstructionWays: Int = 2,
) {
    require(sets >= 2 && isPow2(sets))
    require(ways == 4, "The current tree-PLRU implementation requires four ways")
    require(lineBytes == 32, "L2 and both L1 caches currently exchange 32-byte lines")
    require(maxInstructionWays >= 1 && maxInstructionWays < ways)
    val offsetBits: Int = log2Ceil(lineBytes)
    val indexBits: Int = log2Ceil(sets)
    val tagBits: Int = 34 - offsetBits - indexBits
    val lineBits: Int = lineBytes * 8
    val capacityBytes: Int = sets * ways * lineBytes
}

// Configuration copied from Zircon-2024 for the single-read migration baseline.
object Fetch {
    val nfch = 4
    val nfq = 8
}

object Cache {
    import Fetch._
    val l1Way = 2
    val l1Offset = 5
    val l1Index = 4
    val l1IndexNum = 1 << l1Index
    val l1Tag = 32 - l1Offset - l1Index
    val l1Line = (1 << l1Offset)
    val l1LineBits = l1Line * 8
    val icLine = l1Line
    val icLineBits = icLine * 8
    val fetchOffset = 2 + log2Ceil(nfch)
    assert(l1Offset >= fetchOffset, "l1Offset must be greater than fetchOffset")
    val l2Offset = 6
    val l2Index = 5
    val l2IndexNum = 1 << l2Index
    val l2Tag = 32 - l2Offset - l2Index
    val l2Way = 2 * l1Way
    val l2Line = (1 << l2Offset)
    val l2LineBits = l2Line * 8
}
