package zircon.backend

import chisel3._
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
    val valid = Output(Bool())
    val record = Output(new FirstFaultRecord(config))
  })

  val validReg = RegInit(false.B)
  val recordReg = Reg(new FirstFaultRecord(config))

  private val headIndex = io.robHeadTag(config.robIndexWidth - 1, 0)
  private def ageFromHead(tag: UInt): UInt = {
    val index = tag(config.robIndexWidth - 1, 0)
    Mux(index >= headIndex,
      index - headIndex,
      index + config.robEntries.U - headIndex)
  }

  var selectedValid: Bool = validReg
  var selectedRecord: FirstFaultRecord = recordReg
  for (candidate <- io.candidates) {
    val take = candidate.valid &&
      (!selectedValid || ageFromHead(candidate.record.robTag) < ageFromHead(selectedRecord.robTag))
    selectedRecord = Mux(take, candidate.record, selectedRecord)
    selectedValid = selectedValid || candidate.valid
  }

  when(io.clear || io.flush) {
    validReg := false.B
  }.otherwise {
    validReg := selectedValid
    when(selectedValid) {
      recordReg := selectedRecord
    }
  }

  io.valid := validReg
  io.record := recordReg
}
