import chisel3._

/**
  * Core-wide cache maintenance handshake.
  *
  * Commit drives a request for FENCE.I or SFENCE.VMA and each selected cache
  * returns completion after its local clean/invalidate sequence. Cache-facing
  * interfaces use Flipped.
  */
class CacheMaintenanceIO extends Bundle {
    val request = Output(Bool())
    val invalidate = Output(Bool())
    val done = Input(Bool())
}
