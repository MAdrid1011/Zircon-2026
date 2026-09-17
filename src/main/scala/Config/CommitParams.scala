package ZirconConfig

import chisel3.util.log2Ceil

/** Width shared by architectural retirement and all retirement-owned queues. */
final case class CommitParams(width: Int = 3) {
    require(width == 3, "Zircon-2026 uses three-wide retirement")

    val robEntries = 48
    val sqEntries = 12
    val storeBufferEntries = 4
    val completionPorts = 7
    val robReadPorts = 5
}

/** Capacity of the committed store drain buffer. */
object StoreBuffer {
    val nsb = 4
    val wsb = log2Ceil(nsb)
}
