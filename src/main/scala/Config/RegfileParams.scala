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
    byteWrite: Boolean = false,
    readBypass: Boolean = true,
    initialize: Boolean = true,
    observe: Boolean = false,
    oneHotRead: Boolean = false,
    readBankEntries: Int = 0,
) {
    require(numEntries >= 2, "PRF needs at least two entries")
    require(dataWidth > 0, "PRF dataWidth must be positive")
    require(numReadPorts > 0, "PRF numReadPorts must be positive")
    require(numWritePorts > 0, "PRF numWritePorts must be positive")
    require(!byteWrite || dataWidth % 8 == 0)
    require(!byteWrite || !readBypass, "Byte writes publish readiness after the storage edge")
    require(readBankEntries == 0 || (readBankEntries >= 2 &&
        (readBankEntries & (readBankEntries - 1)) == 0 && numEntries % readBankEntries == 0))
    require(readBankEntries == 0 || !oneHotRead)

    val addrWidth: Int = log2Ceil(numEntries)
}
