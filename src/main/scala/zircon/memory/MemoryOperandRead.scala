package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{ROBExecutionContext, SourceKind, UopRef}

/** Shared LSU operand boundary. It keeps memory uops compact in MemIQ, obtains
  * their ROB-owned atomic metadata by tag, and reads only base/store operands
  * from the integer PRF. The top-level global issue arbiter owns any physical
  * PRF port sharing; this module is the ready/valid/context contract it uses.
  */
class MemoryOperandRead(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val issue = Flipped(Vec(2, Decoupled(new UopRef(config))))
    val robRead = Output(Vec(2, Valid(UInt(config.robTagWidth.W))))
    val robContext = Input(Vec(2, Valid(new ROBExecutionContext(config))))
    val prfReadPhysical = Output(Vec(4, UInt(physicalWidth.W)))
    val prfReadData = Input(Vec(4, UInt(32.W)))
    val request = Vec(2, Decoupled(new MemoryAddressRequest(config)))
    val flush = Input(Bool())
  })

  for (lane <- 0 until 2) {
    val issue = io.issue(lane)
    val context = io.robContext(lane)
    val request = io.request(lane)
    val basePort = lane * 2
    val storePort = basePort + 1

    io.robRead(lane).valid := issue.valid && !io.flush
    io.robRead(lane).bits := issue.bits.robTag
    io.prfReadPhysical(basePort) := Mux(issue.valid,
      issue.bits.sourcePhysical(0), 0.U)
    io.prfReadPhysical(storePort) := Mux(issue.valid,
      issue.bits.sourcePhysical(1), 0.U)

    request.valid := issue.valid && context.valid && !io.flush
    request.bits.uop := issue.bits
    request.bits.base := Mux(issue.bits.sourceKind(0) === SourceKind.IntegerRegister,
      io.prfReadData(basePort), 0.U)
    request.bits.storeData := Mux(issue.bits.sourceKind(1) === SourceKind.IntegerRegister,
      io.prfReadData(storePort), 0.U)
    request.bits.atomicAq := context.bits.atomicAq
    request.bits.atomicRl := context.bits.atomicRl
    issue.ready := request.ready && context.valid && !io.flush

    when(issue.valid && !io.flush) {
      assert(context.valid,
        "memory issue did not receive a live ROB execution context")
      when(context.valid) {
        assert(context.bits.robTag === issue.bits.robTag,
          "memory operand-read ROB context tag mismatch")
      }
      assert(issue.bits.sourceReady.asUInt.andR,
        "MemIQ issued a memory uop with a non-ready source")
      assert(issue.bits.sourceKind(0) === SourceKind.IntegerRegister,
        "memory operand read requires an integer base register")
      assert(issue.bits.sourcePhysical(0) < config.intPhysicalRegisters.U &&
        issue.bits.sourcePhysical(1) < config.intPhysicalRegisters.U,
        "memory operand-read physical source out of range")
    }
  }
}
