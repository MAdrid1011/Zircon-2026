package ZirconConfig

import chisel3.util.log2Ceil

/** Width-defining parameters shared by all backend pipelines. */
case class BackendParams(
    numIntPhys: Int = 72,
    numFpPhys: Int = 48,
    robWidth: Int = 8,
    sqWidth: Int = 8,
    specWidth: Int = 8,
) {
    require(numIntPhys >= 2 && numFpPhys >= 2)
    require(robWidth > 0 && sqWidth > 0 && specWidth > 0)
    val intWidth: Int = log2Ceil(numIntPhys)
    val physWidth: Int = log2Ceil(math.max(numIntPhys, numFpPhys))
    val tagWidth: Int = physWidth + 1
}
