import chisel3._

object PMA {
    private val mainMemoryBase = "h80000000".U(34.W)
    private val mainMemoryMask = "h3e0000000".U(34.W)
    private val deviceBase = "ha0000000".U(34.W)
    private val deviceMask = "h3f0000000".U(34.W)
    // Dedicated posted-write window, outside the UART/CLINT subranges.
    private val writeCombineBase = "ha2000000".U(34.W)
    private val writeCombineLimit = "ha3000000".U(34.W)

    def attribute(paddr: UInt, scratchpadEnabled: Boolean = false): UInt = {
        val mainMemory = (paddr & mainMemoryMask) === mainMemoryBase
        val device = (paddr & deviceMask) === deviceBase
        val writeCombine = paddr >= writeCombineBase && paddr < writeCombineLimit
        val scratchpad = scratchpadEnabled.B && paddr(33, 20) === "hb00".U
        Mux(mainMemory, PMAAttribute.cached,
            Mux(scratchpad, PMAAttribute.device, Mux(writeCombine, PMAAttribute.uncached,
                Mux(device, PMAAttribute.device, PMAAttribute.invalid))))
    }

    def readable(paddr: UInt, scratchpadEnabled: Boolean = false): Bool =
        attribute(paddr, scratchpadEnabled) =/= PMAAttribute.invalid
    def writable(paddr: UInt, scratchpadEnabled: Boolean = false): Bool =
        attribute(paddr, scratchpadEnabled) =/= PMAAttribute.invalid
    def executable(paddr: UInt, scratchpadEnabled: Boolean = false): Bool =
        attribute(paddr, scratchpadEnabled) === PMAAttribute.cached
    def atomic(paddr: UInt, scratchpadEnabled: Boolean = false): Bool =
        attribute(paddr, scratchpadEnabled) === PMAAttribute.cached
}
