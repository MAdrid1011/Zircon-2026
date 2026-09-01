package zircon

import circt.stage.ChiselStage
import zircon.core.ZirconCore

object Elaborate extends App {
  val traceEnabled = args.contains("--trace")
  val stageArgs = args.filterNot(_ == "--trace")
  ChiselStage.emitSystemVerilogFile(
    new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = traceEnabled,
      enableHostFlush = traceEnabled)),
    args = stageArgs,
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-lowering-options=disallowLocalVariables,emittedLineLength=160"
    )
  )
}
