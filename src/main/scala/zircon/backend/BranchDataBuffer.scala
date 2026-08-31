package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

object BranchProvider extends ChiselEnum {
  val Base, Tagged0, Tagged1, Tagged2 = Value
}

class BranchPredictionMetadata extends Bundle {
  val pc = UInt(32.W)
  val historyBefore = UInt(64.W)
  val predictedTaken = Bool()
  val predictedTarget = UInt(32.W)
  val conditional = Bool()
  val call = Bool()
  val ret = Bool()
  val provider = BranchProvider()
  val alternateProvider = BranchProvider()
  val providerPrediction = Bool()
  val alternatePrediction = Bool()
  val btbWay = UInt(1.W)
  val rasPointerBefore = UInt(3.W)
  val rasCountBefore = UInt(4.W)
}

class BranchDataAllocation(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val metadata = new BranchPredictionMetadata
}

class BranchDataReference(config: ZirconCoreConfig) extends Bundle {
  val index = UInt(log2Ceil(config.branchDataEntries).W)
  val robTag = UInt(config.robTagWidth.W)
}

class BranchResolutionRequest(config: ZirconCoreConfig) extends Bundle {
  val reference = new BranchDataReference(config)
  val actualTaken = Bool()
  val actualTarget = UInt(32.W)
}

class BranchResolutionResult(config: ZirconCoreConfig) extends Bundle {
  val reference = new BranchDataReference(config)
  val mispredict = Bool()
  val recoveryHistory = UInt(64.W)
  val recoveryRasPointer = UInt(3.W)
  val recoveryRasCount = UInt(4.W)
  val rasPointerBefore = UInt(3.W)
  val rasCountBefore = UInt(4.W)
  val rasPush = Bool()
  val rasPop = Bool()
  val rasReturnAddress = UInt(32.W)
  val actualTaken = Bool()
  val actualTarget = UInt(32.W)
  val redirectTarget = UInt(32.W)
}

class BranchTrainingRecord(config: ZirconCoreConfig) extends Bundle {
  val reference = new BranchDataReference(config)
  val metadata = new BranchPredictionMetadata
  val actualTaken = Bool()
  val actualTarget = UInt(32.W)
}

class BranchDataEntry(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val metadata = new BranchPredictionMetadata
  val resolved = Bool()
  val actualTaken = Bool()
  val actualTarget = UInt(32.W)
}

/** Eight-entry, one-read/one-write branch metadata and history checkpoint file. */
class BranchDataBuffer(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Module {
  private val entries = config.branchDataEntries
  private val indexWidth = log2Ceil(entries)

  val io = IO(new Bundle {
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val allocate = Flipped(Decoupled(new BranchDataAllocation(config)))
    val allocatedIndex = Output(Valid(UInt(indexWidth.W)))
    val resolve = Flipped(Decoupled(new BranchResolutionRequest(config)))
    val resolution = Decoupled(new BranchResolutionResult(config))
    val commit = Flipped(Decoupled(new BranchDataReference(config)))
    val training = Output(Valid(new BranchTrainingRecord(config)))
    val flushAll = Input(Bool())
    val count = Output(UInt(log2Ceil(entries + 1).W))
  })

  require(entries == 8, "the frozen Branch Data Buffer has eight entries")

  val entryData = Reg(Vec(entries, new BranchDataEntry(config)))
  val entryValid = RegInit(VecInit.fill(entries)(false.B))

  private def ageFromHead(tag: UInt): UInt =
    ROBTagOrder.ageFromHead(tag, io.robHeadTag, config)

  val commitIndex = io.commit.bits.index
  val resolveIndex = io.resolve.bits.reference.index
  val commitEntry = entryData(commitIndex)
  val resolveEntry = entryData(resolveIndex)
  val commitMatch = entryValid(commitIndex) &&
    commitEntry.robTag === io.commit.bits.robTag
  val resolveMatch = entryValid(resolveIndex) &&
    resolveEntry.robTag === io.resolve.bits.reference.robTag

  // Commit owns the single data read port. Resolve uses both read and write.
  // A lane-1 exception may retire an older lane-0 branch and flush younger
  // state on the same edge. The single commit read remains available in that
  // cycle so predictor training is not lost; the sequential flush still wins
  // when clearing BDB entries.
  io.commit.ready := true.B
  val resolveCanProceed = !io.flushAll && !io.commit.valid
  io.resolve.ready := resolveCanProceed && io.resolution.ready
  val commitFire = io.commit.fire
  val resolveFire = io.resolve.fire

  val freedByCommit = Mux(commitFire && commitMatch,
    UIntToOH(commitIndex, entries), 0.U(entries.W))
  val availableMask = (~entryValid.asUInt) | freedByCommit
  val allocationIndex = PriorityEncoder(availableMask)
  io.allocate.ready := !io.flushAll && !resolveFire && availableMask.orR
  val allocateFire = io.allocate.fire
  io.allocatedIndex.valid := allocateFire
  io.allocatedIndex.bits := allocationIndex

  val directionMispredict = resolveEntry.metadata.predictedTaken =/=
    io.resolve.bits.actualTaken
  val targetMispredict = io.resolve.bits.actualTaken &&
    resolveEntry.metadata.predictedTarget =/= io.resolve.bits.actualTarget
  val resolveMispredict = directionMispredict || targetMispredict
  val recoveryHistory = Mux(resolveEntry.metadata.conditional,
    Cat(resolveEntry.metadata.historyBefore(62, 0), io.resolve.bits.actualTaken),
    resolveEntry.metadata.historyBefore)
  val rasCall = io.resolve.bits.actualTaken && resolveEntry.metadata.call
  val rasReturn = io.resolve.bits.actualTaken && resolveEntry.metadata.ret
  val rasPointerAfterPop = Mux(rasReturn &&
    resolveEntry.metadata.rasCountBefore =/= 0.U,
    resolveEntry.metadata.rasPointerBefore -% 1.U,
    resolveEntry.metadata.rasPointerBefore)
  val rasCountAfterPop = Mux(rasReturn &&
    resolveEntry.metadata.rasCountBefore =/= 0.U,
    resolveEntry.metadata.rasCountBefore - 1.U,
    resolveEntry.metadata.rasCountBefore)
  val recoveryRasPointer = Mux(rasCall,
    rasPointerAfterPop +% 1.U, rasPointerAfterPop)
  val recoveryRasCount = Mux(rasCall,
    Mux(rasCountAfterPop < 8.U, rasCountAfterPop + 1.U, 8.U),
    rasCountAfterPop)

  io.resolution.valid := resolveCanProceed && io.resolve.valid && resolveMatch
  io.resolution.bits.reference := io.resolve.bits.reference
  io.resolution.bits.mispredict := resolveMispredict
  io.resolution.bits.recoveryHistory := recoveryHistory
  io.resolution.bits.recoveryRasPointer := recoveryRasPointer
  io.resolution.bits.recoveryRasCount := recoveryRasCount
  io.resolution.bits.rasPointerBefore := resolveEntry.metadata.rasPointerBefore
  io.resolution.bits.rasCountBefore := resolveEntry.metadata.rasCountBefore
  io.resolution.bits.rasPush := rasCall
  io.resolution.bits.rasPop := rasReturn
  io.resolution.bits.rasReturnAddress := resolveEntry.metadata.pc + 4.U
  io.resolution.bits.actualTaken := io.resolve.bits.actualTaken
  io.resolution.bits.actualTarget := io.resolve.bits.actualTarget
  io.resolution.bits.redirectTarget := Mux(io.resolve.bits.actualTaken,
    io.resolve.bits.actualTarget, resolveEntry.metadata.pc + 4.U)

  io.training.valid := commitFire && commitMatch
  io.training.bits.reference := io.commit.bits
  io.training.bits.metadata := commitEntry.metadata
  io.training.bits.actualTaken := commitEntry.actualTaken
  io.training.bits.actualTarget := commitEntry.actualTarget

  when(io.flushAll) {
    entryValid.foreach(_ := false.B)
  }.otherwise {
    when(commitFire && commitMatch) {
      entryValid(commitIndex) := false.B
    }

    when(resolveFire && resolveMatch) {
      entryData(resolveIndex).resolved := true.B
      entryData(resolveIndex).actualTaken := io.resolve.bits.actualTaken
      entryData(resolveIndex).actualTarget := io.resolve.bits.actualTarget

      when(resolveMispredict) {
        for (entry <- 0 until entries) {
          when(entryValid(entry) && ROBTagOrder.isYounger(
            entryData(entry).robTag,
            io.resolve.bits.reference.robTag,
            io.robHeadTag,
            config)) {
            entryValid(entry) := false.B
          }
        }
      }
    }

    when(allocateFire) {
      entryData(allocationIndex).robTag := io.allocate.bits.robTag
      entryData(allocationIndex).metadata := io.allocate.bits.metadata
      entryData(allocationIndex).resolved := false.B
      entryData(allocationIndex).actualTaken := false.B
      entryData(allocationIndex).actualTarget := 0.U
      entryValid(allocationIndex) := true.B
    }
  }

  val verifyRecovery = RegNext(resolveFire && resolveMatch && resolveMispredict,
    false.B)
  val recoveredTag = RegEnable(io.resolve.bits.reference.robTag,
    resolveFire && resolveMatch && resolveMispredict)
  when(verifyRecovery) {
    val recoveredAge = ageFromHead(recoveredTag)
    for (entry <- 0 until entries) {
      assert(!entryValid(entry) || ageFromHead(entryData(entry).robTag) <= recoveredAge,
        "a younger BDB entry survived misprediction recovery")
    }
  }

  when(commitFire) {
    assert(commitMatch, "BDB commit reference did not match a live entry")
    when(commitMatch) {
      assert(commitEntry.resolved, "a branch reached commit before resolution")
    }
  }
  when(resolveFire) {
    assert(resolveMatch, "BDB resolve reference did not match a live entry")
    when(resolveMatch) {
      assert(!resolveEntry.resolved, "a branch was resolved more than once")
    }
  }
  when(allocateFire) {
    assert(availableMask(allocationIndex),
      "BDB allocation selected a non-free entry")
  }
  assert(PopCount(Seq(commitFire, resolveFire)) <= 1.U,
    "the BDB single read port was oversubscribed")
  assert(PopCount(Seq(resolveFire, allocateFire)) <= 1.U,
    "the BDB single write port was oversubscribed")
  assert(PopCount(entryValid) <= entries.U,
    "BDB occupancy exceeded its configured depth")

  io.count := PopCount(entryValid)
}
