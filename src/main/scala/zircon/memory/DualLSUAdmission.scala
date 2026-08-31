package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Admission split ahead of the two LSU pipelines.
  *
  * M1 only owns an address-classified cacheable integer load. Any other M1
  * candidate is retained in a replay slot and handed to M0 later; consuming a
  * rejected M1 candidate never creates a completion. M0's direct path and the
  * replay path remain separate because the final global issue arbiter must
  * choose their ROB-age order with the direct M0 source.
  */
class DualLSUAdmission(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val m0Input = Flipped(Decoupled(new MemoryAddressRequest(config)))
    val m1Input = Flipped(Decoupled(new MemoryAddressRequest(config)))
    val m0Issue = Decoupled(new MemoryLSURequest(config))
    val m1Issue = Decoupled(new MemoryLSURequest(config))
    val m1Replay = Decoupled(new MemoryLSURequest(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val replayOccupied = Output(Bool())
  })

  val m0Address = Module(new MemoryAddressUnit(config))
  val m1Address = Module(new MemoryAddressUnit(config))
  val replayAddress = Module(new MemoryAddressUnit(config))
  m0Address.io.valid := io.m0Input.valid
  m1Address.io.valid := io.m1Input.valid
  m0Address.io.request := io.m0Input.bits
  m1Address.io.request := io.m1Input.bits

  val replayValid = RegInit(false.B)
  val replayRequest = Reg(new MemoryAddressRequest(config))
  replayAddress.io.valid := replayValid
  replayAddress.io.request := replayRequest
  val recoveryBlocked = io.flush || io.squash.valid

  io.m0Issue.valid := io.m0Input.valid && !recoveryBlocked
  io.m0Issue.bits.request := io.m0Input.bits
  io.m0Issue.bits.address := m0Address.io.result
  io.m0Issue.bits.m1Owner := false.B
  io.m0Input.ready := io.m0Issue.ready && !recoveryBlocked

  val m1Accepted = m1Address.io.result.m1Eligible
  io.m1Issue.valid := io.m1Input.valid && m1Accepted && !recoveryBlocked
  io.m1Issue.bits.request := io.m1Input.bits
  io.m1Issue.bits.address := m1Address.io.result
  io.m1Issue.bits.m1Owner := true.B

  io.m1Replay.valid := replayValid && !recoveryBlocked
  io.m1Replay.bits.request := replayRequest
  io.m1Replay.bits.address := replayAddress.io.result
  io.m1Replay.bits.m1Owner := false.B
  val replayFree = !replayValid || io.m1Replay.fire
  io.m1Input.ready := !recoveryBlocked && Mux(m1Accepted,
    io.m1Issue.ready, replayFree)

  val replayYounger = ROBTagOrder.isYounger(
    replayRequest.uop.robTag, io.squash.bits, io.robHeadTag, config)
  when(io.flush) {
    replayValid := false.B
  }.elsewhen(io.squash.valid) {
    when(replayValid && replayYounger) {
      replayValid := false.B
    }
  }.otherwise {
    when(io.m1Input.fire && !m1Accepted) {
      replayValid := true.B
      replayRequest := io.m1Input.bits
    }.elsewhen(io.m1Replay.fire) {
      replayValid := false.B
    }
  }

  when(io.m1Issue.valid) {
    assert(io.m1Issue.bits.address.m1Eligible,
      "M1 admitted an ineligible memory request")
  }
  when(io.m1Replay.valid) {
    assert(!io.m1Replay.bits.address.m1Eligible,
      "M1 replay retained a request that M1 could have accepted")
  }
  when(io.squash.valid) {
    assert(!io.m0Issue.fire && !io.m1Issue.fire && !io.m1Replay.fire &&
      !io.m0Input.fire && !io.m1Input.fire,
      "DualLSU admission transferred work during selective squash")
  }
  io.replayOccupied := replayValid
}
