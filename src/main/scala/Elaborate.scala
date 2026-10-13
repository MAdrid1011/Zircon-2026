import circt.stage.ChiselStage
import ZirconConfig.{CoreProfile, L2CacheParams}

/** Emit the synthesizable or simulation top level. */
object Elaborate {
    def main(args: Array[String]): Unit = {
        val options = args.filter(_.startsWith("--"))
        val remaining = args.filterNot(_.startsWith("--"))
        require(
            options.forall(option => Set("--simulation", "--bsg")(option) || option.startsWith("--profile=")),
            "Usage: runMain Elaborate [--simulation] [--bsg] [--profile=system|performance] [output-directory]",
        )
        require(remaining.length <= 1, "Only one output directory may be specified")
        val simulation = options.contains("--simulation")
        val bsg = options.contains("--bsg")
        val profiles = options.filter(_.startsWith("--profile="))
        require(profiles.length <= 1)
        val profile = CoreProfile.named(profiles.headOption.map(_.stripPrefix("--profile=")).getOrElse("system"))
        val ramBackend = if (bsg) DualPortRamBackend.BSG else DualPortRamBackend.Vivado
        val l2Params = if (bsg) L2CacheParams(sets = 16) else L2CacheParams()
        val loweringOptions =
            if (simulation) "disallowPackedArrays"
            else "disallowPackedArrays,disallowLocalVariables"
        ChiselStage.emitSystemVerilogFile(
            new ZirconCore(simulationDebug = simulation, ramBackend = ramBackend, l2Params = l2Params, profile = profile),
            Array("--target-dir", remaining.headOption.getOrElse(s"generated/${profile.name}")),
            Array(
                "--disable-all-randomization",
                s"--lowering-options=$loweringOptions",
            ),
        )
    }
}
