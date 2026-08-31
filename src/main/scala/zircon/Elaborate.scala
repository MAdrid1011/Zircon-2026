package zircon

import circt.stage.ChiselStage
import zircon.core.ZirconCore

object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new ZirconCore(ZirconCoreConfig.default),
    args = args,
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-lowering-options=disallowLocalVariables,emittedLineLength=160"
    )
  )
}
