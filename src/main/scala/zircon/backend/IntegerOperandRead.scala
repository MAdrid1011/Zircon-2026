package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class IntegerPipeRequest(config: ZirconCoreConfig) extends Bundle {
  val uop = new UopRef(config)
  val context = new ROBExecutionContext(config)
  val lhs = UInt(32.W)
  val rhs = UInt(32.W)
}

/** Stateless E0/E1 context lookup and integer operand selection. */
class IntegerOperandRead(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val issue = Flipped(Vec(2, Decoupled(new UopRef(config))))
    val robRead = Output(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val robContext = Input(Vec(2,
      Valid(new ROBExecutionContext(config))))
    val prfReadPhysical = Output(Vec(4, UInt(physicalWidth.W)))
    val prfReadData = Input(Vec(4, UInt(32.W)))
    val execute = Vec(2, Decoupled(new IntegerPipeRequest(config)))
    val flush = Input(Bool())
  })

  private def selectOperand(
      kind: SourceKind.Type,
      integerData: UInt,
      uop: UopRef,
      context: ROBExecutionContext
  ): UInt = MuxCase(0.U(32.W), Seq(
    (kind === SourceKind.IntegerRegister) -> integerData,
    (kind === SourceKind.Immediate) -> uop.immediate,
    (kind === SourceKind.ProgramCounter) -> context.pc
  ))

  for (lane <- 0 until 2) {
    val issue = io.issue(lane)
    val context = io.robContext(lane)
    val execute = io.execute(lane)
    val firstRead = lane * 2
    val secondRead = firstRead + 1

    io.robRead(lane).valid := issue.valid && !io.flush
    io.robRead(lane).bits := issue.bits.robTag
    io.prfReadPhysical(firstRead) := issue.bits.sourcePhysical(0)
    io.prfReadPhysical(secondRead) := issue.bits.sourcePhysical(1)

    execute.valid := issue.valid && context.valid && !io.flush
    execute.bits.uop := issue.bits
    execute.bits.context := context.bits
    execute.bits.lhs := selectOperand(issue.bits.sourceKind(0),
      io.prfReadData(firstRead), issue.bits, context.bits)
    execute.bits.rhs := selectOperand(issue.bits.sourceKind(1),
      io.prfReadData(secondRead), issue.bits, context.bits)
    issue.ready := execute.ready && context.valid && !io.flush

    when(issue.valid && !io.flush) {
      assert(context.valid,
        "issued integer uop did not receive a live ROB execution context")
      when(context.valid) {
        assert(context.bits.robTag === issue.bits.robTag,
          "operand-read ROB context tag mismatch")
      }
      assert(issue.bits.sourceReady.asUInt.andR,
        "IntIQ issued an integer uop with a non-ready source")
      assert(issue.bits.sourceKind(0) =/= SourceKind.FloatingRegister &&
        issue.bits.sourceKind(1) =/= SourceKind.FloatingRegister,
        "integer operand read received a floating-register source")
      assert(issue.bits.sourcePhysical(0) < config.intPhysicalRegisters.U &&
        issue.bits.sourcePhysical(1) < config.intPhysicalRegisters.U,
        "integer operand-read physical source out of range")
      if (lane == 0) {
        assert(issue.bits.allowedEndpoints(0),
          "E0 operand read received an ineligible uop")
      } else {
        assert(issue.bits.allowedEndpoints(1),
          "E1 operand read received an ineligible uop")
      }
    }
  }
}
