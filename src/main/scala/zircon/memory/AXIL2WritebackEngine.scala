package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.bus.{AXI4Address, AXI4Burst, AXI4Resp, AXI4WriteData, AXI4WriteResponse}

/** Drains one dirty exclusive-L2 victim through AXI ID 5.
  *
  * A victim leaves the L2 FIFO only after this owner has retained the complete
  * line. It is not released on a failing B response: the same AW/W burst is
  * retried until AXI acknowledges it, preserving the sole dirty copy. The
  * top-level write scheduler decides when this owner may drive W; AW and W
  * remain independently backpressured at this boundary.
  */
class AXIL2WritebackEngine(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val writebackId = 5
  private val wordsPerLine = config.l2.lineBytes / 4
  private val beatWidth = log2Ceil(wordsPerLine)
  private val countWidth = log2Ceil(wordsPerLine + 1)

  require(config.l2.lineBytes == 32,
    "the frozen M3 L2 writeback owns one 32-byte burst")
  require(wordsPerLine == 8,
    "the frozen M3 writeback burst has exactly eight 32-bit beats")

  val io = IO(new Bundle {
    val victim = Flipped(Decoupled(new CacheLineTransfer(config)))
    val aw = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val w = Decoupled(new AXI4WriteData(dataWidth = 32))
    val b = Flipped(Decoupled(new AXI4WriteResponse(idWidth = 4)))
    val busy = Output(Bool())
    /** Sticks once an AXI B error has caused a retained-line retry. */
    val retryObserved = Output(Bool())
  })

  private def success(resp: UInt): Bool =
    resp === AXI4Resp.Okay || resp === AXI4Resp.ExOkay

  val active = RegInit(false.B)
  val line = Reg(new CacheLineTransfer(config))
  val awSent = RegInit(false.B)
  val wordsSent = RegInit(0.U(countWidth.W))
  val retryObserved = RegInit(false.B)

  io.victim.ready := !active
  io.aw.valid := active && !awSent
  io.aw.bits.id := writebackId.U
  io.aw.bits.addr := line.lineAddress
  io.aw.bits.len := (wordsPerLine - 1).U
  io.aw.bits.size := 2.U
  io.aw.bits.burst := AXI4Burst.Incrementing
  io.aw.bits.lock := false.B
  io.aw.bits.cache := "b0011".U
  io.aw.bits.prot := "b001".U
  io.aw.bits.qos := 0.U

  val writeComplete = wordsSent === wordsPerLine.U
  io.w.valid := active && !writeComplete
  io.w.bits.data := line.lineData(wordsSent(beatWidth - 1, 0))
  io.w.bits.strb := "b1111".U
  io.w.bits.last := wordsSent === (wordsPerLine - 1).U

  io.b.ready := active && awSent && writeComplete
  io.busy := active
  io.retryObserved := retryObserved

  when(io.victim.fire) {
    assert(io.victim.bits.dirty,
      "L2 writeback owner accepted a clean line")
    assert(io.victim.bits.lineAddress(4, 0) === 0.U,
      "L2 writeback line was not 32-byte aligned")
    assert(io.victim.bits.lineAddress(11, 0) <= "hfe0".U,
      "L2 writeback burst crossed an AXI 4 KiB boundary")
    line := io.victim.bits
    active := true.B
    awSent := false.B
    wordsSent := 0.U
  }
  when(io.aw.fire) {
    awSent := true.B
  }
  when(io.w.fire) {
    wordsSent := wordsSent + 1.U
  }

  when(io.b.valid) {
    assert(active, "AXI ID-5 B arrived without a retained dirty victim")
    assert(awSent && writeComplete,
      "AXI ID-5 B arrived before all AW/W handshakes completed")
    assert(io.b.bits.id === writebackId.U,
      "L2 writeback received a B response with the wrong AXI ID")
  }
  when(io.b.fire) {
    when(success(io.b.bits.resp)) {
      active := false.B
      awSent := false.B
      wordsSent := 0.U
    }.otherwise {
      // The retained line is still the sole dirty owner. Restart both write
      // channels only after consuming the errored response.
      awSent := false.B
      wordsSent := 0.U
      retryObserved := true.B
    }
  }

  when(active) {
    assert(line.dirty, "L2 writeback lost dirty ownership while active")
    assert(wordsSent <= wordsPerLine.U,
      "L2 writeback emitted more beats than its AXI burst length")
    assert(line.lineAddress(4, 0) === 0.U,
      "active L2 writeback line lost 32-byte alignment")
  }
}
