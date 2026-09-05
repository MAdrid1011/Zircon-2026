package object zircon {
  /** Keep the many isolated ChiselSim harnesses buildable in a bounded window. */
  implicit lazy val zirconTestSimulator: chisel3.simulator.HasSimulator = {
    val common = svsim.CommonCompilationSettings.default.copy(
      optimizationStyle = svsim.CommonCompilationSettings.OptimizationStyle.OptimizeForCompilationSpeed,
      availableParallelism = svsim.CommonCompilationSettings.AvailableParallelism.UpTo(8)
    )
    chisel3.simulator.HasSimulator.simulators.verilator(compilationSettings = common)
  }
}
