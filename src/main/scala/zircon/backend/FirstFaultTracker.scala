package zircon.backend

import chisel3._

class FirstFaultRecord extends Bundle {
  val order = UInt(64.W)
  val robTag = UInt(5.W)
  val cause = UInt(32.W)
  val trapValue = UInt(32.W)
}

class FaultCandidate extends Bundle {
  val valid = Bool()
  val record = new FirstFaultRecord
}

/** Holds the oldest detected fault by monotonic instruction order. The record
  * is cleared only when commit consumes it or a global rollback invalidates the
  * corresponding instruction stream.
  */
class FirstFaultTracker(candidateWidth: Int = 2) extends Module {
  val io = IO(new Bundle {
    val candidates = Input(Vec(candidateWidth, new FaultCandidate))
    val clear = Input(Bool())
    val flush = Input(Bool())
    val valid = Output(Bool())
    val record = Output(new FirstFaultRecord)
  })

  val validReg = RegInit(false.B)
  val recordReg = Reg(new FirstFaultRecord)

  var selectedValid: Bool = validReg
  var selectedRecord: FirstFaultRecord = recordReg
  for (candidate <- io.candidates) {
    val take = candidate.valid && (!selectedValid || candidate.record.order < selectedRecord.order)
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
