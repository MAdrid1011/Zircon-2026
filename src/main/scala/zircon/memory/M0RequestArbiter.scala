package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Fall-through buffers and chooses the oldest M0 request between direct work
  * and M1 replay.
  *
  * There is intentionally no completion or side effect at this boundary. Each
  * input has a one-entry elastic slot. This is essential at the global
  * auxiliary-read boundary: a source's combinational grant can change while
  * the downstream LSU is backpressured, so selection alone cannot safely stand
  * in for input ownership. An empty slot remains fall-through when the output
  * is ready, preserving the fixed six-cycle device-group collection latency.
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

  val directBuffered = RegInit(false.B)
  val directBuffer = Reg(new MemoryLSURequest(config))
  val replayBuffered = RegInit(false.B)
  val replayBuffer = Reg(new MemoryLSURequest(config))
  val outputLockValid = RegInit(false.B)
  val outputLockDirect = RegInit(false.B)
  val recoveryBlocked = io.flush || io.squash.valid

  val directValid = directBuffered || io.direct.valid
  val replayValid = replayBuffered || io.replay.valid
  val directRequest = Mux(directBuffered, directBuffer, io.direct.bits)
  val replayRequest = Mux(replayBuffered, replayBuffer, io.replay.bits)
  val directAge = ROBTagOrder.ageFromHead(
    directRequest.address.robTag, io.robHeadTag, config)
  val replayAge = ROBTagOrder.ageFromHead(
    replayRequest.address.robTag, io.robHeadTag, config)
  val chooseDirect = directValid && (!replayValid || directAge < replayAge)
  val selectedDirect = Mux(outputLockValid, outputLockDirect, chooseDirect)
  val selectedValid = Mux(outputLockValid,
    Mux(outputLockDirect, directValid, replayValid), directValid || replayValid)

  io.output.valid := selectedValid && !recoveryBlocked
  io.output.bits := Mux(selectedDirect, directRequest, replayRequest)
  io.direct.ready := !directBuffered && !recoveryBlocked
  io.replay.ready := !replayBuffered && !recoveryBlocked

  val selectedBuffered = Mux(selectedDirect, directBuffered, replayBuffered)
  val directFlows = io.output.fire && selectedDirect && !directBuffered
  val replayFlows = io.output.fire && !selectedDirect && !replayBuffered

  val directSurvivesSquash = directBuffered && !ROBTagOrder.isYounger(
    directBuffer.address.robTag, io.squash.bits, io.robHeadTag, config)
  val replaySurvivesSquash = replayBuffered && !ROBTagOrder.isYounger(
    replayBuffer.address.robTag, io.squash.bits, io.robHeadTag, config)

  when(io.flush || io.squash.valid) {
    when(io.flush) {
      directBuffered := false.B
      replayBuffered := false.B
    }.otherwise {
      directBuffered := directSurvivesSquash
      replayBuffered := replaySurvivesSquash
    }
    outputLockValid := false.B
  }.otherwise {
    when(io.output.fire && selectedBuffered) {
      outputLockValid := false.B
      when(selectedDirect) {
        directBuffered := false.B
      }.otherwise {
        replayBuffered := false.B
      }
    }.elsewhen(io.output.fire) {
      outputLockValid := false.B
    }.elsewhen(io.output.valid && !io.output.ready) {
      outputLockValid := true.B
      outputLockDirect := selectedDirect
    }
    when(io.direct.fire && !directFlows) {
      directBuffered := true.B
      directBuffer := io.direct.bits
    }
    when(io.replay.fire && !replayFlows) {
      replayBuffered := true.B
      replayBuffer := io.replay.bits
    }
  }
  when(io.direct.valid) {
    assert(io.direct.bits.address.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "M0 direct path supplied an out-of-range ROB tag")
  }
  when(io.replay.valid) {
    assert(io.replay.bits.address.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "M0 replay path supplied an out-of-range ROB tag")
  }
  when(directBuffered && replayBuffered) {
    assert(directBuffer.address.robTag =/= replayBuffer.address.robTag,
      "M0 direct and replay slots cannot own the same ROB tag")
  }
  when(outputLockValid && !recoveryBlocked) {
    assert(selectedValid,
      "M0 arbiter lost a locally retained output before handshake")
  }
  when(io.direct.valid && io.replay.valid) {
    assert(io.direct.bits.address.robTag =/= io.replay.bits.address.robTag,
      "M0 direct and replay inputs cannot offer the same ROB tag")
  }
  when(io.squash.valid) {
    assert(!io.direct.ready && !io.replay.ready && !io.output.valid,
      "M0 arbiter transferred a request during selective recovery")
  }
}
