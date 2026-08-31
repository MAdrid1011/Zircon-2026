package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.bus.{AXI4Address, AXI4Burst, AXI4ReadData, AXI4Resp}

class InstructionFetchWord extends Bundle {
  val instruction = UInt(32.W)
  val fault = new FetchFault
}

class InstructionFetchPacket(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Bundle {
  val base = UInt(32.W)
  val count = UInt(log2Ceil(config.fetchWidth + 1).W)
  val words = Vec(config.fetchWidth, new InstructionFetchWord)
}

object InstructionFetchState extends ChiselEnum {
  val Idle, Request, Receive, Present, Drain = Value
}

/** One-outstanding-burst instruction fetch transport for the executable M1 core.
  *
  * The final I-Cache will replace this transport. It already obeys the public
  * AXI4 contract: AR remains stable under backpressure, accepted requests are
  * drained after redirect, bursts never cross a 4 KiB boundary, and response
  * errors become per-word instruction access faults.
  */
class AXIInstructionFetch(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val countWidth = log2Ceil(config.fetchWidth + 1)
  private val indexWidth = log2Ceil(config.fetchWidth)

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val redirect = Input(Valid(UInt(32.W)))
    val ar = Decoupled(new AXI4Address(32, 4))
    val r = Flipped(Decoupled(new AXI4ReadData(32, 4)))
    val response = Decoupled(new InstructionFetchPacket(config))
    val responseNextPc = Input(UInt(32.W))
    val currentPc = Output(UInt(32.W))
    val busy = Output(Bool())
    val draining = Output(Bool())
  })

  require(config.fetchWidth == 4,
    "the M1 AXI fetch transport is frozen for four-word packets")

  val state = RegInit(InstructionFetchState.Idle)
  val pc = RegInit(config.resetVector.U(32.W))
  val requestBase = RegInit(config.resetVector.U(32.W))
  val requestBeats = RegInit(config.fetchWidth.U(countWidth.W))
  val requestDiscard = RegInit(false.B)
  val beatIndex = RegInit(0.U(indexWidth.W))
  val words = RegInit(VecInit.fill(config.fetchWidth)(
    0.U.asTypeOf(new InstructionFetchWord)))

  private def beatsBeforeBoundary(address: UInt): UInt = {
    val bytesRemaining = 4096.U(13.W) - address(11, 0)
    val wordsRemaining = bytesRemaining >> 2
    Mux(wordsRemaining < config.fetchWidth.U,
      wordsRemaining(countWidth - 1, 0), config.fetchWidth.U(countWidth.W))
  }

  val nextRequestBeats = beatsBeforeBoundary(pc)

  io.ar.valid := state === InstructionFetchState.Request
  io.ar.bits.id := 0.U
  io.ar.bits.addr := requestBase
  io.ar.bits.len := (requestBeats - 1.U).pad(8)
  io.ar.bits.size := 2.U
  io.ar.bits.burst := AXI4Burst.Incrementing
  io.ar.bits.lock := false.B
  io.ar.bits.cache := "b0011".U
  io.ar.bits.prot := "b101".U
  io.ar.bits.qos := 0.U

  val acceptingReadData = state === InstructionFetchState.Receive ||
    state === InstructionFetchState.Drain
  io.r.ready := acceptingReadData
  val expectedLast = beatIndex === requestBeats - 1.U
  val responseAddress = requestBase + (beatIndex << 2)
  val responseOkay = io.r.bits.resp === AXI4Resp.Okay ||
    io.r.bits.resp === AXI4Resp.ExOkay

  io.response.valid := state === InstructionFetchState.Present &&
    !io.redirect.valid
  io.response.bits.base := requestBase
  io.response.bits.count := requestBeats
  io.response.bits.words := words

  io.currentPc := pc
  io.busy := state =/= InstructionFetchState.Idle
  io.draining := state === InstructionFetchState.Drain || requestDiscard

  when(io.r.fire) {
    assert(io.r.bits.id === 0.U,
      "instruction fetch received an unknown AXI read ID")
    assert(io.r.bits.last === expectedLast,
      "instruction fetch received an AXI RLAST at the wrong beat")
  }
  when(io.redirect.valid) {
    assert(!io.redirect.bits(1, 0).orR,
      "instruction fetch redirect target must satisfy IALIGN=32")
  }
  when(state === InstructionFetchState.Request) {
    assert(requestBeats >= 1.U && requestBeats <= config.fetchWidth.U,
      "instruction fetch requested an illegal burst length")
    assert((requestBase(11, 0) + (requestBeats << 2)) <= 4096.U,
      "instruction fetch burst crossed a 4 KiB AXI boundary")
  }

  when(io.redirect.valid) {
    pc := io.redirect.bits
    switch(state) {
      is(InstructionFetchState.Idle) {
        requestDiscard := false.B
      }
      is(InstructionFetchState.Request) {
        requestDiscard := true.B
        when(io.ar.fire) {
          beatIndex := 0.U
          state := InstructionFetchState.Drain
        }
      }
      is(InstructionFetchState.Receive) {
        when(io.r.fire) {
          when(io.r.bits.last) {
            state := InstructionFetchState.Idle
          }.otherwise {
            beatIndex := beatIndex + 1.U
            state := InstructionFetchState.Drain
          }
        }.otherwise {
          state := InstructionFetchState.Drain
        }
      }
      is(InstructionFetchState.Present) {
        state := InstructionFetchState.Idle
      }
      is(InstructionFetchState.Drain) {
        when(io.r.fire) {
          when(io.r.bits.last) {
            state := InstructionFetchState.Idle
          }.otherwise {
            beatIndex := beatIndex + 1.U
          }
        }
      }
    }
  }.otherwise {
    switch(state) {
      is(InstructionFetchState.Idle) {
        when(io.enable) {
          requestBase := pc
          requestBeats := nextRequestBeats
          requestDiscard := false.B
          state := InstructionFetchState.Request
        }
      }
      is(InstructionFetchState.Request) {
        when(io.ar.fire) {
          beatIndex := 0.U
          state := Mux(requestDiscard,
            InstructionFetchState.Drain, InstructionFetchState.Receive)
        }
      }
      is(InstructionFetchState.Receive) {
        when(io.r.fire) {
          words(beatIndex).instruction := Mux(responseOkay,
            io.r.bits.data, 0.U)
          words(beatIndex).fault.valid := !responseOkay
          words(beatIndex).fault.cause := 1.U
          words(beatIndex).fault.tval := responseAddress
          when(io.r.bits.last) {
            state := InstructionFetchState.Present
          }.otherwise {
            beatIndex := beatIndex + 1.U
          }
        }
      }
      is(InstructionFetchState.Present) {
        when(io.response.fire) {
          pc := io.responseNextPc
          state := InstructionFetchState.Idle
        }
      }
      is(InstructionFetchState.Drain) {
        when(io.r.fire) {
          when(io.r.bits.last) {
            requestDiscard := false.B
            state := InstructionFetchState.Idle
          }.otherwise {
            beatIndex := beatIndex + 1.U
          }
        }
      }
    }
  }
}
