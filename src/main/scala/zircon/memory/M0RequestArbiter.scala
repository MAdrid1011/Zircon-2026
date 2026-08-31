package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Chooses the oldest M0 request between the direct path and an M1 replay.
  *
  * There is intentionally no completion or side effect at this boundary. A
  * selected source is locked while the downstream LSU is backpressured so a
  * newly visible older candidate cannot alter a held Decoupled payload.
  */
class M0RequestArbiter(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val direct = Flipped(Decoupled(new MemoryLSURequest(config)))
    val replay = Flipped(Decoupled(new MemoryLSURequest(config)))
    val output = Decoupled(new MemoryLSURequest(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val lockValid = RegInit(false.B)
  val lockDirect = RegInit(false.B)
  val recoveryBlocked = io.flush || io.squash.valid

  val directAge = ROBTagOrder.ageFromHead(
    io.direct.bits.address.robTag, io.robHeadTag, config)
  val replayAge = ROBTagOrder.ageFromHead(
    io.replay.bits.address.robTag, io.robHeadTag, config)
  val chooseDirect = io.direct.valid &&
    (!io.replay.valid || directAge < replayAge)
  val chosenValid = io.direct.valid || io.replay.valid
  val selectedDirect = Mux(lockValid, lockDirect, chooseDirect)
  val selectedInput = Mux(selectedDirect, io.direct.bits, io.replay.bits)
  val selectedValid = Mux(lockValid,
    Mux(lockDirect, io.direct.valid, io.replay.valid), chosenValid)

  io.output.valid := selectedValid && !recoveryBlocked
  io.output.bits := selectedInput
  io.direct.ready := false.B
  io.replay.ready := false.B
  when(io.output.valid) {
    when(selectedDirect) {
      io.direct.ready := io.output.ready
    }.otherwise {
      io.replay.ready := io.output.ready
    }
  }

  when(io.flush || io.squash.valid) {
    lockValid := false.B
  }.elsewhen(io.output.fire) {
    lockValid := false.B
  }.elsewhen(io.output.valid && !io.output.ready) {
    lockValid := true.B
    lockDirect := selectedDirect
  }

  when(lockValid) {
    assert(selectedValid,
      "M0 arbiter locked a source that withdrew before its handshake")
  }
  when(io.direct.valid) {
    assert(io.direct.bits.address.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "M0 direct path supplied an out-of-range ROB tag")
  }
  when(io.replay.valid) {
    assert(io.replay.bits.address.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "M0 replay path supplied an out-of-range ROB tag")
  }
  when(io.direct.valid && io.replay.valid) {
    assert(io.direct.bits.address.robTag =/= io.replay.bits.address.robTag,
      "M0 direct and replay paths cannot own the same ROB tag")
  }
  when(io.squash.valid) {
    assert(!io.direct.ready && !io.replay.ready && !io.output.valid,
      "M0 arbiter transferred a request during selective recovery")
  }
}
