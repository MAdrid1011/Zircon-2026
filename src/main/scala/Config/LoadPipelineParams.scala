package ZirconConfig

import chisel3.util.log2Ceil

case class LoadPipelineParams(
    numIntPhys: Int = 72,
    numFpPhys: Int = 48,
    robWidth: Int = 6,
    sqWidth: Int = 5,
    entries: Int = 4,
) {
    require(numIntPhys >= 2 && numFpPhys >= 2)
    require(robWidth > 0 && sqWidth > 0)
    require(entries >= 4 && entries <= 256 && (entries & (entries - 1)) == 0)
    val intWidth: Int = log2Ceil(numIntPhys)
    val physWidth: Int = log2Ceil(math.max(numIntPhys, numFpPhys))
    val tagWidth: Int = physWidth + 1
    val slotWidth: Int = log2Ceil(entries)
    val backend: BackendParams = BackendParams(
        numIntPhys = numIntPhys,
        numFpPhys = numFpPhys,
        robWidth = robWidth,
        sqWidth = sqWidth,
    )
}
