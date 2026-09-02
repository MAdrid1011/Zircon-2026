package zircon.memory

import chisel3._
import chisel3.util._

/** Sideband request kinds frozen by ADR-0023. */
object ExternalCoherenceKind {
  val WriteInvalidate = 0.U(1.W)
  val AtomicInvalidate = 1.U(1.W)
}

/** A platform must obtain this acknowledgement before issuing its modifier. */
class ExternalCoherenceRequest extends Bundle {
  val kind = UInt(1.W)
  val lineAddress = UInt(32.W)
}

class ExternalCoherenceResponse extends Bundle {
  val kind = UInt(1.W)
  val lineAddress = UInt(32.W)
}

/** Serializes one external cacheable modifier with the local cache hierarchy.
  *
  * Cache endpoints acknowledge their exact-line cleanup only after any
  * matching accepted owner has drained. A dirty L2 cleanup creates the normal
  * ID-5 victim; acknowledgement waits for that exact writeback completion.
  */
class ExternalCoherenceController extends Module {
  object State extends ChiselEnum {
    val Idle, CleanL1D, CleanL2, AwaitWriteback, InvalidateI, Respond = Value
  }

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExternalCoherenceRequest))
    val response = Decoupled(new ExternalCoherenceResponse)
    val cacheableIngressBlocked = Output(Bool())

    val l1dCleanup = Decoupled(UInt(32.W))
    /** Existing I-side demand/lookahead owners must drain before a target line
      * can be removed from shared L2 and the private L1I. */
    val instructionDrained = Input(Bool())
    val l2Cleanup = Decoupled(UInt(32.W))
    val l2CleanupDirty = Input(Bool())
    val writebackComplete = Input(Valid(UInt(32.W)))
    val l1iInvalidate = Output(Bool())
    val reservationInvalidateLine = Output(Valid(UInt(32.W)))
  })

  val state = RegInit(State.Idle)
  val heldKind = Reg(UInt(1.W))
  val heldLineAddress = Reg(UInt(32.W))

  io.request.ready := state === State.Idle
  io.cacheableIngressBlocked := state =/= State.Idle || io.request.fire

  io.l1dCleanup.valid := state === State.CleanL1D && io.instructionDrained
  io.l1dCleanup.bits := heldLineAddress
  io.l2Cleanup.valid := state === State.CleanL2
  io.l2Cleanup.bits := heldLineAddress
  io.l1iInvalidate := state === State.InvalidateI
  io.reservationInvalidateLine.valid := state === State.InvalidateI
  io.reservationInvalidateLine.bits := heldLineAddress

  io.response.valid := state === State.Respond
  io.response.bits.kind := heldKind
  io.response.bits.lineAddress := heldLineAddress

  when(io.request.valid) {
    assert(io.request.bits.lineAddress(4, 0) === 0.U,
      "external coherence requests must be cache-line aligned")
    assert(io.request.bits.kind === ExternalCoherenceKind.WriteInvalidate ||
      io.request.bits.kind === ExternalCoherenceKind.AtomicInvalidate,
      "external coherence request used an unsupported kind")
  }
  when(io.l1dCleanup.valid || io.l2Cleanup.valid || io.response.valid) {
    assert(heldLineAddress(4, 0) === 0.U,
      "external coherence retained an unaligned line address")
  }
  when(io.response.valid) {
    assert(io.cacheableIngressBlocked,
      "external coherence acknowledged while cacheable ingress was open")
  }
  when(state === State.CleanL1D && !io.instructionDrained) {
    assert(!io.l1dCleanup.fire,
      "external coherence cleaned data state before I-side owners drained")
  }

  switch(state) {
    is(State.Idle) {
      when(io.request.fire) {
        heldKind := io.request.bits.kind
        heldLineAddress := io.request.bits.lineAddress
        state := State.CleanL1D
      }
    }
    is(State.CleanL1D) {
      when(io.l1dCleanup.fire) {
        state := State.CleanL2
      }
    }
    is(State.CleanL2) {
      when(io.l2Cleanup.fire) {
        state := Mux(io.l2CleanupDirty, State.AwaitWriteback,
          State.InvalidateI)
      }
    }
    is(State.AwaitWriteback) {
      when(io.writebackComplete.valid &&
          io.writebackComplete.bits === heldLineAddress) {
        state := State.InvalidateI
      }
    }
    is(State.InvalidateI) {
      state := State.Respond
    }
    is(State.Respond) {
      when(io.response.fire) {
        state := State.Idle
      }
    }
  }
}
