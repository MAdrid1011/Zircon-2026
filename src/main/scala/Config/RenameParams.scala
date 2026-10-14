package ZirconConfig

import chisel3.util.log2Ceil

case class RenameParams(
    numPhys: Int = 72,
    renameWidth: Int = 2,
    commitWidth: Int = 2,
    numSources: Int = 3,
    hasZeroReg: Boolean = true,
    numArch: Int = 32,
) {
    require(renameWidth > 0 && commitWidth > 0 && numSources >= 2 && numSources <= 4)
    require(numArch >= 2 && numArch <= 32)
    require(
        numPhys - numArch >= math.max(renameWidth, commitWidth),
        "FreeList must contain at least one physical register per rename and commit lane"
    )
    val indexWidth: Int = log2Ceil(numPhys)
}
