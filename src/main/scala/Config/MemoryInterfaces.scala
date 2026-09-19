import chisel3._

/**
  * Core-wide cache maintenance handshake.
  *
  * Commit drives a request for FENCE.I and each cache returns completion after
  * its local drain and invalidate sequence. Cache-facing interfaces use Flipped.
  */
class CacheMaintenanceIO extends Bundle {
    val request = Output(Bool())
    val done = Input(Bool())
}
