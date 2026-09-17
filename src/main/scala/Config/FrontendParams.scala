package ZirconConfig

import chisel3.util.{isPow2, log2Ceil}

case class FrontendParams(
    fetchWidth: Int = 4,
    fastBtbSets: Int = 32,
    btbSets: Int = 64,
    btbWays: Int = 2,
    phtSets: Int = 256,
    tageSets: Int = 256,
    historyLengths: Seq[Int] = Seq(4, 8, 16, 32, 64, 128),
    tageTagBits: Int = 9,
    tcSets: Int = 64,
    indirectTargetSets: Int = 16,
    rasDepth: Int = 16,
    ftqDepth: Int = 16,
    fqDepth: Int = 8,
    resetPc: BigInt = BigInt("80000000", 16),
    // Elaborate statistics and observation logic only for simulation.
    observe: Boolean = false,
) {
    require(fetchWidth >= 1 && fetchWidth <= 8 && isPow2(fetchWidth))
    require(
        Seq(fastBtbSets, btbSets, phtSets, tageSets, tcSets, indirectTargetSets, rasDepth, ftqDepth, fqDepth)
            .forall(n => n >= 2 && isPow2(n))
    )
    require(btbWays == 1 || btbWays == 2)
    require(historyLengths.nonEmpty && historyLengths == historyLengths.sorted.distinct)
    require(historyLengths.forall(_ > 0) && tageTagBits >= 4 && tageTagBits <= 16)
    require(resetPc >= 0 && resetPc < (BigInt(1) << 32) && (resetPc & 3) == 0)
    val slotBits = math.max(1, log2Ceil(fetchWidth))
    val blockBits = log2Ceil(fetchWidth * 4)
    val ftqBits = log2Ceil(ftqDepth)
    val historyStep = math.max(4, fetchWidth)
    val historyBits = historyLengths.last * historyStep
    val tageCount = historyLengths.size
    val providerBits = math.max(1, log2Ceil(tageCount + 1))
    val tageIndexBits = log2Ceil(tageSets)
    val phtIndexBits = log2Ceil(phtSets)
    val tcIndexBits = log2Ceil(tcSets)
    val rasBits = log2Ceil(rasDepth)
    val hashBits = math.max(8, math.max(tageIndexBits, tageTagBits))
}
