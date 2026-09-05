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
    // Number of instructions retired at the current edge.  The tracker uses
    // this to advance its local head snapshot without putting the live ROB
    // head into the candidate-selection combinational cone.
    val headAdvance = Input(UInt(2.W))
    val candidates = Input(Vec(candidateWidth, new FaultCandidate(config)))
    val clear = Input(Bool())
    val flush = Input(Bool())
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val valid = Output(Bool())
    val record = Output(new FirstFaultRecord(config))
  })

  val validReg = RegInit(false.B)
  val recordReg = RegInit(0.U.asTypeOf(new FirstFaultRecord(config)))
  val headTagReg = RegInit(0.U(config.robTagWidth.W))
  val headTagInitialized = RegInit(false.B)

  private def advanceHeadTag(tagValue: UInt, amount: UInt): UInt = {
    val index = tagValue(config.robIndexWidth - 1, 0)
    val generation = tagValue(config.robTagWidth - 1)
    val sum = index +& amount
    val wrapped = sum >= config.robEntries.U
    val nextIndex = Mux(wrapped, sum - config.robEntries.U, sum)
    (generation ^ wrapped) ## nextIndex(config.robIndexWidth - 1, 0)
  }

  // ROB and tracker update on the same edge.  Predicting the post-retirement
  // tag here keeps the snapshot aligned with ROB.headTag in the following
  // cycle, including the non-power-of-two 24-entry wrap.
  when(!headTagInitialized || io.flush || io.robHeadTag =/= headTagReg) {
    headTagReg := io.robHeadTag
    headTagInitialized := true.B
  }.otherwise {
    headTagReg := advanceHeadTag(headTagReg, io.headAdvance)
  }

  private def ageFromHead(tag: UInt): UInt =
    ROBTagOrder.ageFromHead(tag, headTagReg, config)

  private def survivesSquash(tag: UInt): Bool =
    !io.squash.valid || !ROBTagOrder.isYounger(
      tag, io.squash.bits, headTagReg, config)

  // Select the oldest candidate with a balanced tree.  The previous linear
  // fold put every candidate's age comparator and full 65-bit payload mux in
  // series, which made the IntIQ/FirstFault path especially sensitive to
  // placement.  Pairwise reduction preserves left-side priority on equal age.
  var selectedValids = io.candidates.map(candidate =>
    candidate.valid && survivesSquash(candidate.record.robTag)).toVector
  var selectedRecords = io.candidates.map(_.record).toVector
  while (selectedValids.length > 1) {
    val nextValids = Vector.newBuilder[Bool]
    val nextRecords = Vector.newBuilder[FirstFaultRecord]
    var pair = 0
    while (pair < selectedValids.length) {
      if (pair + 1 == selectedValids.length) {
        nextValids += selectedValids(pair)
        nextRecords += selectedRecords(pair)
      } else {
        val leftValid = selectedValids(pair)
        val rightValid = selectedValids(pair + 1)
        val rightWins = rightValid &&
          (!leftValid || ageFromHead(selectedRecords(pair + 1).robTag) <
            ageFromHead(selectedRecords(pair).robTag))
        val record = Wire(new FirstFaultRecord(config))
        record := Mux(rightWins, selectedRecords(pair + 1), selectedRecords(pair))
        nextValids += (leftValid || rightValid)
        nextRecords += record
      }
      pair += 2
    }
    selectedValids = nextValids.result()
    selectedRecords = nextRecords.result()
  }
  val candidateValid = selectedValids.head
  val candidateRecord = selectedRecords.head
  val selectedValid = Wire(Bool())
  val selectedRecord = Wire(new FirstFaultRecord(config))
  val candidateWins = candidateValid &&
    (!validReg || !survivesSquash(recordReg.robTag) ||
      ageFromHead(candidateRecord.robTag) < ageFromHead(recordReg.robTag))
  selectedValid := validReg && survivesSquash(recordReg.robTag) || candidateValid
  selectedRecord := Mux(candidateWins, candidateRecord, recordReg)

  when(io.clear || io.flush) {
    validReg := false.B
  }.otherwise {
    validReg := selectedValid
    when(selectedValid) {
      recordReg := selectedRecord
    }
  }

  // Keep every visible output register-only. The squash edge removes a younger
  // record for the following cycle; the resolving branch is older and remains
  // incomplete in the launch cycle, so that record cannot match a committable
  // ROB head. Avoiding a combinational squash filter also keeps BDB commit,
  // branch resolution, fault visibility, and commit arbitration acyclic.
  io.valid := validReg
  io.record := recordReg

  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "FirstFault squash boundary ROB index out of range")
  }

  when(!io.flush) {
    assert(io.headAdvance <= 2.U,
      "FirstFault head advance exceeded the two-wide commit bound")
  }
}
