package zircon.platform

import chisel3._
import chisel3.util.Decoupled
import zircon.ZirconCoreConfig
import zircon.bus.AXI4MasterPort
import zircon.core.{InterruptInputs, ZirconCore}
import zircon.memory.ExternalCoherenceRequest

/** Synthesizable integration boundary for a single Zircon core and one
  * external cacheable modifier source.
  *
  * It deliberately contains no board clocking, pin assignments, or external
  * AXI master payload. A concrete wrapper supplies those after its platform
  * pinout and master contract are verified.
  */
class ZirconPlatformCore(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  require(!config.enableTrace && !config.enableM2Observation && !config.enableHostFlush,
    "ZirconPlatformCore is the production no-observation integration boundary")

  override val desiredName: String = "ZirconPlatformCore"

  val io = IO(new Bundle {
    val axi = new AXI4MasterPort(addressWidth = 32, dataWidth = 32, idWidth = 4)
    val interrupts = Input(new InterruptInputs)
    val modifier = Flipped(Decoupled(new ExternalCoherenceRequest))
    val authorized = Decoupled(new ExternalCoherenceRequest)
  })

  val core = Module(new ZirconCore(config))
  val coherence = Module(new ExternalCoherenceAdapter)

  io.axi <> core.io.axi
  core.io.interrupts := io.interrupts
  coherence.io.core <> core.io.externalCoherence
  coherence.io.modifier <> io.modifier
  io.authorized <> coherence.io.authorized
}
