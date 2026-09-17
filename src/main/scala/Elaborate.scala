import circt.stage.ChiselStage

/** Emit the synthesizable or simulation top level. */
object Elaborate {
    def main(args: Array[String]): Unit = {
        val simulation = args.headOption.contains("--simulation")
        val remaining = if (simulation) args.drop(1) else args
        require(remaining.length <= 1, "Usage: runMain Elaborate [--simulation] [output-directory]")
        ChiselStage.emitSystemVerilogFile(
            new ZirconCore(simulationDebug = simulation),
            Array("--target-dir", remaining.headOption.getOrElse("generated")),
            Array("--lowering-options=disallowPackedArrays,disallowLocalVariables"),
        )
    }
}
