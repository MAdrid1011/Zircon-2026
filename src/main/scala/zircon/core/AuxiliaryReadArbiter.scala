package zircon.core

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** One external endpoint's compact request for the two auxiliary PRF ports. */
class AuxiliaryReadRequest(config: ZirconCoreConfig) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val sourcePhysical = Vec(2, UInt(log2Ceil(config.intPhysicalRegisters).W))
  val sourceRequired = Vec(2, Bool())
}

/** Allocates the two non-integer-pipe PRF reads without changing the frozen 6R2W PRF.
  * Candidate order is E2, M0, M1. A grant is only a combinational permission;
  * the owning endpoint still controls its ready/valid handshake.
  */
class AuxiliaryReadArbiter(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val candidateCount = 3
  private val readPorts = 2
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)

  val io = IO(new Bundle {
    val candidate = Input(Vec(candidateCount,
      Valid(new AuxiliaryReadRequest(config))))
    val traceReadRequired = Input(Bool())
    val startSlots = Input(UInt(2.W))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val grant = Output(Vec(candidateCount, Bool()))
    val readPhysical = Output(Vec(readPorts, UInt(physicalWidth.W)))
    val readData = Input(Vec(readPorts, UInt(32.W)))
    val candidateData = Output(Vec(candidateCount, Vec(2, UInt(32.W))))
  })

  // The same live-tag distance is consumed by each allocation round. Compute
  // it once per candidate so the priority loop does not replicate the index
  // compare/subtract cone three times.
  private val candidateAge = VecInit((0 until candidateCount).map(index =>
    ROBTagOrder.ageFromHead(io.candidate(index).bits.robTag, io.robHeadTag, config)))

  private def selectOldest(candidates: Seq[Bool]): (Bool, UInt) = {
    var selectedValid: Bool = false.B
    var selectedIndex: UInt = 0.U(log2Ceil(candidateCount).W)
    var selectedAge: UInt = 0.U((config.robIndexWidth + 1).W)
    for (index <- 0 until candidateCount) {
      val age = candidateAge(index)
      val take = candidates(index) && (!selectedValid || age < selectedAge)
      selectedValid = selectedValid || candidates(index)
      selectedIndex = Mux(take, index.U, selectedIndex)
      selectedAge = Mux(take, age, selectedAge)
    }
    (selectedValid, selectedIndex)
  }

  val sourceCount = VecInit((0 until candidateCount).map(index =>
    PopCount(io.candidate(index).bits.sourceRequired)))
  var grantedMask: UInt = 0.U(candidateCount.W)
  var remainingReads: UInt = readPorts.U(2.W)
  var remainingStarts: UInt = io.startSlots
  for (round <- 0 until candidateCount) {
    val eligible = (0 until candidateCount).map { index =>
      io.candidate(index).valid && !grantedMask(index) &&
        sourceCount(index) <= remainingReads && remainingStarts =/= 0.U &&
        !io.traceReadRequired
    }
    // Keep one global ROB-age decision for E2 and the two memory endpoints.
    // E2 is already isolated by its registered launch boundary, so changing
    // priority here would violate the architectural oldest-first contract
    // when an older LSU can consume the available PRF ports.
    val (selectedValid, selectedIndex) = selectOldest(eligible)
    val selectedReads = sourceCount(selectedIndex)
    grantedMask = Mux(selectedValid,
      grantedMask | UIntToOH(selectedIndex, candidateCount), grantedMask)
    remainingReads = Mux(selectedValid, remainingReads - selectedReads,
      remainingReads)
    remainingStarts = Mux(selectedValid, remainingStarts - 1.U,
      remainingStarts)
  }
  io.grant := VecInit((0 until candidateCount).map(index => grantedMask(index)))

  io.readPhysical.foreach(_ := 0.U)
  var assignedReads: UInt = 0.U(2.W)
  for (candidate <- 0 until candidateCount) {
    for (source <- 0 until 2) {
      val selected = io.grant(candidate) &&
        io.candidate(candidate).bits.sourceRequired(source)
      when(selected && assignedReads === 0.U) {
        io.readPhysical(0) := io.candidate(candidate).bits.sourcePhysical(source)
      }
      when(selected && assignedReads === 1.U) {
        io.readPhysical(1) := io.candidate(candidate).bits.sourcePhysical(source)
      }
      io.candidateData(candidate)(source) := Mux(selected,
        Mux(assignedReads === 0.U, io.readData(0), io.readData(1)), 0.U)
      assignedReads = Mux(selected, assignedReads + 1.U, assignedReads)
    }
  }

  val grantCount = PopCount(io.grant)
  val grantedReadCount = PopCount((0 until candidateCount).flatMap(candidate =>
    (0 until 2).map(source => io.grant(candidate) &&
      io.candidate(candidate).bits.sourceRequired(source))))
  assert(grantCount <= io.startSlots,
    "auxiliary read arbiter exceeded the remaining global start slots")
  assert(grantedReadCount <= readPorts.U,
    "auxiliary read arbiter exceeded the two physical PRF ports")
  when(io.traceReadRequired) {
    assert(!grantCount.orR,
      "auxiliary read arbiter granted E2 or LSU work during a trace read")
  }
}
