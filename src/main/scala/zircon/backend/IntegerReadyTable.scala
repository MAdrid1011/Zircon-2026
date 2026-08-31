package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Readiness scoreboard for renamed integer destinations.
  *
  * Allocation makes a new physical destination busy. Architectural
  * completion makes it ready and is also exposed combinationally so dispatch
  * in the same cycle does not miss a producer wakeup.
  */
class IntegerReadyTable(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalRegisters = config.intPhysicalRegisters
  private val physicalWidth = log2Ceil(physicalRegisters)

  val io = IO(new Bundle {
    val allocate = Input(Vec(config.decodeWidth,
      Valid(UInt(physicalWidth.W))))
    val complete = Input(Vec(config.completionWidth,
      Valid(UInt(physicalWidth.W))))
    val ready = Output(UInt(physicalRegisters.W))
  })

  val readyReg = RegInit(Fill(physicalRegisters, 1.U(1.W)))
  val allocationMask = io.allocate.map { allocation =>
    Mux(allocation.valid,
      UIntToOH(allocation.bits, physicalRegisters),
      0.U(physicalRegisters.W))
  }.reduce(_ | _)
  val completionMask = io.complete.map { completion =>
    Mux(completion.valid,
      UIntToOH(completion.bits, physicalRegisters),
      0.U(physicalRegisters.W))
  }.reduce(_ | _)

  readyReg := ((readyReg | completionMask) & ~allocationMask) | 1.U

  // Completion forwarding is visible before the clock edge. Allocation wins
  // in the impossible conflict case so a new producer is never marked ready.
  io.ready := ((readyReg | completionMask) & ~allocationMask) | 1.U

  io.allocate.foreach { allocation =>
    when(allocation.valid) {
      assert(allocation.bits =/= 0.U,
        "integer ready table cannot allocate p0")
      assert(allocation.bits < physicalRegisters.U,
        "integer ready-table allocation out of range")
    }
  }
  io.complete.foreach { completion =>
    when(completion.valid) {
      assert(completion.bits =/= 0.U,
        "integer ready table cannot complete p0")
      assert(completion.bits < physicalRegisters.U,
        "integer ready-table completion out of range")
    }
  }
  assert(!(io.allocate(0).valid && io.allocate(1).valid &&
    io.allocate(0).bits === io.allocate(1).bits),
    "dual dispatch allocated the same physical destination")
  assert(!(io.complete(0).valid && io.complete(1).valid &&
    io.complete(0).bits === io.complete(1).bits),
    "dual completion marked the same physical destination ready")
  for (allocation <- io.allocate; completion <- io.complete) {
    assert(!(allocation.valid && completion.valid &&
      allocation.bits === completion.bits),
      "a physical destination was allocated and completed in the same cycle")
  }
  assert(io.ready(0), "p0 must remain ready")
}
