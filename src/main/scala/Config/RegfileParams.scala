package ZirconConfig

import chisel3.util.log2Ceil

/** PRF dimensions and physical-zero policy, fixed at elaboration without changing latency. */
final case class RegfileParams(
    numEntries: Int = 62,
    dataWidth: Int = 32,
    numReadPorts: Int = 10,
    numWritePorts: Int = 5,
    hasZeroReg: Boolean = true,
    holdReads: Boolean = false,
) {
    require(numEntries >= 2, "PRF needs at least two entries")
    require(dataWidth > 0, "PRF dataWidth must be positive")
    require(numReadPorts > 0, "PRF numReadPorts must be positive")
    require(numWritePorts > 0, "PRF numWritePorts must be positive")

    val addrWidth: Int = log2Ceil(numEntries)
}
