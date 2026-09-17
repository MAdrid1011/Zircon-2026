package ZirconConfig

import chisel3.util.{isPow2, log2Ceil}

case class TLBParams(
    queryPorts: Int = 1,
    sets: Int = 4,
    ways: Int = 4,
    superEntries: Int = 4,
    vaddrBits: Int = 32,
    paddrBits: Int = 34,
    asidBits: Int = 9,
    pmaBits: Int = 2,
) {
    require(queryPorts > 0, "TLB needs at least one query port")
    require(sets >= 2 && isPow2(sets), "TLB set count must be a power of two")
    require(sets <= 1024, "Sv32 VPN[0] provides at most ten set-index bits")
    require(ways == 4, "The current tree-PLRU implementation requires four ways")
    require(superEntries >= 2 && isPow2(superEntries), "Superpage entry count must be a power of two")
    require(vaddrBits == 32, "The current TLB implements Sv32")
    require(paddrBits >= 32 && paddrBits <= 34, "Sv32 physical addresses are at most 34 bits")
    require(asidBits == 9, "RV32 satp defines a nine-bit ASID")
    require(pmaBits == 2, "The current PMA encoding has four values")

    val setBits: Int = log2Ceil(sets)
    val wayBits: Int = log2Ceil(ways)
    val superIndexBits: Int = log2Ceil(superEntries)
    val ppnBits: Int = paddrBits - 12
    val normalTagBits: Int = 20 - setBits
}
