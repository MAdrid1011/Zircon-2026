package zircon

import circt.stage.ChiselStage
import zircon.core.ZirconCore
import zircon.platform.ZirconPlatformCore

object Elaborate extends App {
  val traceEnabled = args.contains("--trace")
  val platformEnabled = args.contains("--platform")
  val l2EightKiB = args.contains("--l2-8k")
  require(!platformEnabled || !traceEnabled,
    "the production platform boundary does not expose trace-only ports")
  val stageArgs = args.filterNot(argument => argument == "--trace" ||
    argument == "--platform" || argument == "--l2-8k")
  val baseConfig = if (l2EightKiB) ZirconCoreConfig.l2EightKiB
    else ZirconCoreConfig.default
  val config = baseConfig.copy(enableTrace = traceEnabled,
    enableHostFlush = traceEnabled)
  ChiselStage.emitSystemVerilogFile(
    if (platformEnabled) new ZirconPlatformCore(config) else new ZirconCore(config),
    args = stageArgs,
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-lowering-options=disallowLocalVariables,emittedLineLength=160"
    )
  )
}
