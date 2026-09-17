import chisel3._

object PMA {
    private val mainMemoryBase = "h80000000".U(34.W)
    private val mainMemoryMask = "h3e0000000".U(34.W)
    private val deviceBase = "ha0000000".U(34.W)
    private val deviceMask = "h3f0000000".U(34.W)

    def attribute(paddr: UInt): UInt = {
        val mainMemory = (paddr & mainMemoryMask) === mainMemoryBase
        val device = (paddr & deviceMask) === deviceBase
        Mux(mainMemory, PMAAttribute.cached, Mux(device, PMAAttribute.device, PMAAttribute.invalid))
    }

    def readable(paddr: UInt): Bool = attribute(paddr) =/= PMAAttribute.invalid
    def writable(paddr: UInt): Bool = attribute(paddr) =/= PMAAttribute.invalid
    def executable(paddr: UInt): Bool = attribute(paddr) === PMAAttribute.cached
    def atomic(paddr: UInt): Bool = attribute(paddr) === PMAAttribute.cached
}
