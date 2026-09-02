package zircon.platform

import chisel3._
import chisel3.util._
import zircon.core.ExternalCoherencePort
import zircon.memory.{ExternalCoherenceKind, ExternalCoherenceRequest}

/** Platform-side gate for cacheable modifiers outside ZirconCore.
  *
  * A board/SoC integration supplies the real write or atomic after `authorized`
  * fires. This adapter deliberately carries no board pins or foreign AXI payload:
  * those belong to the concrete integration, while this shared boundary makes
  * the mandatory coherence-before-modifier ordering synthesizable and testable.
  */
class ExternalCoherenceAdapter extends Module {
  object State extends ChiselEnum {
    val Idle, SendRequest, AwaitResponse, Authorize = Value
  }

  val io = IO(new Bundle {
    /** One external cacheable store or atomic awaiting authorization. */
    val modifier = Flipped(Decoupled(new ExternalCoherenceRequest))
    /** Connect directly to `ZirconCoreIO.externalCoherence`. */
    val core = Flipped(new ExternalCoherencePort)
    /** The integration may start the retained external action only on this fire. */
    val authorized = Decoupled(new ExternalCoherenceRequest)
  })

  val state = RegInit(State.Idle)
  val heldRequest = Reg(new ExternalCoherenceRequest)

  io.modifier.ready := state === State.Idle
  io.core.request.valid := state === State.SendRequest
  io.core.request.bits := heldRequest
  io.core.response.ready := state === State.AwaitResponse
  io.authorized.valid := state === State.Authorize
  io.authorized.bits := heldRequest

  when(io.modifier.valid) {
    assert(io.modifier.bits.lineAddress(4, 0) === 0.U,
      "platform coherence modifier must be cache-line aligned")
    assert(io.modifier.bits.kind === ExternalCoherenceKind.WriteInvalidate ||
      io.modifier.bits.kind === ExternalCoherenceKind.AtomicInvalidate,
      "platform coherence modifier used an unsupported kind")
  }
  when(io.core.request.valid) {
    assert(io.core.request.bits.asUInt === heldRequest.asUInt,
      "platform coherence request changed while awaiting core acceptance")
  }
  when(io.core.response.fire) {
    assert(io.core.response.bits.asUInt === heldRequest.asUInt,
      "platform coherence response did not match its retained modifier")
  }
  when(io.authorized.valid) {
    assert(state === State.Authorize,
      "external modifier became authorized before coherence acknowledgement")
  }

  switch(state) {
    is(State.Idle) {
      when(io.modifier.fire) {
        heldRequest := io.modifier.bits
        state := State.SendRequest
      }
    }
    is(State.SendRequest) {
      when(io.core.request.fire) {
        state := State.AwaitResponse
      }
    }
    is(State.AwaitResponse) {
      when(io.core.response.fire) {
        state := State.Authorize
      }
    }
    is(State.Authorize) {
      when(io.authorized.fire) {
        state := State.Idle
      }
    }
  }
}
