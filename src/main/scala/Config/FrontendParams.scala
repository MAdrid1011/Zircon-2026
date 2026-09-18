package ZirconConfig

import chisel3.util.{isPow2, log2Ceil}

case class FrontendParams(
    fetchWidth: Int = 4,
    fastBtbSets: Int = 32,
    btbSets: Int = 64,
    btbWays: Int = 2,
    phtSets: Int = 256,
    tageSets: Int = 512,
    historyLengths: Seq[Int] = Seq(4, 8, 16, 32, 64, 128),
    tageTagBits: Int = 9,
    ittageSets: Int = 128,
    ittageHistoryLengths: Seq[Int] = Seq(4, 8, 16, 32),
    ittageTagBits: Int = 9,
    scBiasSets: Int = 64,
    scSets: Int = 64,
    scHistoryLengths: Seq[Int] = Seq(4, 8, 16),
    scCounterBits: Int = 6,
    scThresholdSets: Int = 32,
    scInitialThreshold: Int = 20,
    loopSets: Int = 64,
    loopTagBits: Int = 10,
    loopCountBits: Int = 8,
    rasDepth: Int = 16,
    ftqDepth: Int = 16,
    fqDepth: Int = 8,
    resetPc: BigInt = BigInt("80000000", 16),
    // Elaborate statistics and observation logic only for simulation.
    observe: Boolean = false,
) {
    require(fetchWidth >= 1 && fetchWidth <= 8 && isPow2(fetchWidth))
    require(Seq(fastBtbSets, btbSets, phtSets, tageSets, ittageSets, scBiasSets, scSets, scThresholdSets,
        loopSets, rasDepth, ftqDepth, fqDepth)
        .forall(n => n >= 2 && isPow2(n)))
    require(btbWays == 1 || btbWays == 2)
    require(historyLengths.nonEmpty && historyLengths == historyLengths.sorted.distinct)
    require(historyLengths.forall(_ > 0) && tageTagBits >= 4 && tageTagBits <= 16)
    require(ittageHistoryLengths.nonEmpty && ittageHistoryLengths == ittageHistoryLengths.sorted.distinct)
    require(ittageHistoryLengths.forall(historyLengths.contains) && ittageTagBits >= 4 && ittageTagBits <= 16)
    require(scHistoryLengths.nonEmpty && scHistoryLengths == scHistoryLengths.sorted.distinct)
    require(scHistoryLengths.forall(historyLengths.contains))
    require(scCounterBits >= 4 && scCounterBits <= 8)
    require(scInitialThreshold >= 4 && scInitialThreshold < 64)
    require(loopTagBits >= 4 && loopTagBits <= 16)
    require(loopCountBits >= 4 && loopCountBits <= 16)
    require(resetPc >= 0 && resetPc < (BigInt(1) << 32) && (resetPc & 3) == 0)
    val slotBits = math.max(1, log2Ceil(fetchWidth))
    val blockBits = log2Ceil(fetchWidth * 4)
    val ftqBits = log2Ceil(ftqDepth)
    val historyStep = math.max(4, fetchWidth)
    val historyBits = historyLengths.last * historyStep
    val tageCount = historyLengths.size
    val providerBits = math.max(1, log2Ceil(tageCount + 1))
    val ittageCount = ittageHistoryLengths.size
    val ittageHistoryIndices = ittageHistoryLengths.map(historyLengths.indexOf)
    val ittageIndexBits = log2Ceil(ittageSets)
    val ittageProviderBits = math.max(1, log2Ceil(ittageCount + 1))
    val scHistoryIndices = scHistoryLengths.map(historyLengths.indexOf)
    val scCount = scHistoryLengths.size
    val scIndexBits = log2Ceil(scSets)
    val scThresholdIndexBits = log2Ceil(scThresholdSets)
    val loopIndexBits = log2Ceil(loopSets)
    val tageIndexBits = log2Ceil(tageSets)
    val phtIndexBits = log2Ceil(phtSets)
    val scBiasIndexBits = log2Ceil(scBiasSets)
    val rasBits = log2Ceil(rasDepth)
    val hashBits = math.max(8, math.max(tageIndexBits, tageTagBits))
}
