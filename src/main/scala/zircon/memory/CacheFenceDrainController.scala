package zircon.memory

import chisel3._
import chisel3.util._

/** Sequences a cache-global FENCE drain after the LSQ age barrier is clear.
  *
  * L1D first transfers every dirty exclusive line into L2. L2 then gives each
  * dirty resident line to the retained ID-5 writeback owner. Completion is not
  * reported until that owner has observed the final AXI B response.
  */
class CacheFenceDrainController extends Module {
  private object State extends ChiselEnum {
    val Idle, DrainL1D, DrainL2, Complete = Value
  }

  val io = IO(new Bundle {
    /** True only for the live head FENCE/FENCE.I after older LSQ owners drain. */
    val request = Input(Bool())
    val l1dDrain = Output(Bool())
    val l1dDrained = Input(Bool())
    val l2Drain = Output(Bool())
    val l2Drained = Input(Bool())
    val writebackBusy = Input(Bool())
    /** Held until the head FENCE retires. */
    val complete = Output(Bool())
  })

  private val state = RegInit(State.Idle)
  io.l1dDrain := state === State.DrainL1D
  io.l2Drain := state === State.DrainL2
  io.complete := state === State.Complete

  when(!io.request) {
    state := State.Idle
  }.otherwise {
    switch(state) {
      is(State.Idle) {
        state := State.DrainL1D
      }
      is(State.DrainL1D) {
        when(io.l1dDrained) {
          state := State.DrainL2
        }
      }
      is(State.DrainL2) {
        when(io.l2Drained && !io.writebackBusy) {
          state := State.Complete
        }
      }
      is(State.Complete) {
        state := State.Complete
      }
    }
  }
}
