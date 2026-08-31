package zircon.backend

import chisel3._
import chisel3.util.Valid
import zircon.ZirconCoreConfig

class FirstFaultRecord(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val cause = UInt(32.W)
  val trapValue = UInt(32.W)
}

class FaultCandidate(config: ZirconCoreConfig) extends Bundle {
  val valid = Bool()
  val record = new FirstFaultRecord(config)
}

/** Holds the oldest detected fault by modulo-ROB distance from the current head.
  * The record is cleared only when commit consumes it or a global rollback
  * invalidates the corresponding instruction stream.
  */
class FirstFaultTracker(
    candidateWidth: Int = 2,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val candidates = Input(Vec(candidateWidth, new FaultCandidate(config)))
    val clear = Input(Bool())
    val flush = Input(Bool())
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val valid = Output(Bool())
    val record = Output(new FirstFaultRecord(config))
  })

  val validReg = RegInit(false.B)
  val recordReg = Reg(new FirstFaultRecord(config))

  private def ageFromHead(tag: UInt): UInt =
    ROBTagOrder.ageFromHead(tag, io.robHeadTag, config)

  private def survivesSquash(tag: UInt): Bool =
    !io.squash.valid || !ROBTagOrder.isYounger(
      tag, io.squash.bits, io.robHeadTag, config)

  var selectedValid: Bool = validReg && survivesSquash(recordReg.robTag)
  var selectedRecord: FirstFaultRecord = recordReg
  for (candidate <- io.candidates) {
    val candidateValid = candidate.valid && survivesSquash(candidate.record.robTag)
    val take = candidateValid &&
      (!selectedValid || ageFromHead(candidate.record.robTag) < ageFromHead(selectedRecord.robTag))
    selectedRecord = Mux(take, candidate.record, selectedRecord)
    selectedValid = selectedValid || candidateValid
  }

  when(io.clear || io.flush) {
    validReg := false.B
  }.otherwise {
    validReg := selectedValid
    when(selectedValid) {
      recordReg := selectedRecord
    }
  }

  // Keep clear/flush sequential, as commit may consume the visible record on
  // the same edge. A younger squashed record, however, must disappear before
  // any recovery-cycle observer can act on it.
  io.valid := validReg && survivesSquash(recordReg.robTag)
  io.record := recordReg

  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "FirstFault squash boundary ROB index out of range")
  }
}
