package ZirconConfig

import chisel3.util.{isPow2, log2Ceil}

case class ICacheParams(sets: Int = 16, lineBytes: Int = 32, tlbEnabled: Boolean = false) {
    require(sets >= 2 && isPow2(sets))
    require(lineBytes >= 4 && isPow2(lineBytes))
    require(sets * lineBytes <= 4096, "ICache index must fit within the page offset")
    val ways = 2
    val offsetBits = log2Ceil(lineBytes)
    val indexBits = log2Ceil(sets)
    val tagBits = 34 - offsetBits - indexBits
    val lineBits = lineBytes * 8
}
