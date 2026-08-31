package zircon.memory

import chisel3._
import chisel3.util._
import zircon.{PMARegionKind, ZirconCoreConfig}
import zircon.bus.{AXI4Address, AXI4Burst, AXI4Resp, AXI4WriteData, AXI4WriteResponse}

/** Owns one commit-authorized cacheable store from AW/W acceptance through B.
  *
  * This first write slice is deliberately single beat and single owner. Device
  * groups, AMOs, cache writeback bursts, and dirty L1D ownership remain outside
  * it. An accepted write is never withdrawn; its exact B result stays buffered
  * until the M0 completion path consumes it.
  */
class AXIDataStoreEngine(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val writeId = 5

  val io = IO(new Bundle {
    val effect = Flipped(Decoupled(new StoreEffect(config)))
    val invalidateReady = Input(Bool())
    val activeStore = Output(Valid(new StoreEffect(config)))
    val busy = Output(Bool())

    val result = Decoupled(new StoreWriteResult(config))
    val aw = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val w = Decoupled(new AXI4WriteData(dataWidth = 32))
    val b = Flipped(Decoupled(new AXI4WriteResponse(idWidth = 4)))
  })

  val active = RegInit(false.B)
  val effectBits = Reg(new StoreEffect(config))
  val awSent = RegInit(false.B)
  val wSent = RegInit(false.B)

  val resultValid = RegInit(false.B)
  val resultBits = Reg(new StoreWriteResult(config))
  io.result.valid := resultValid
  io.result.bits := resultBits

  io.effect.ready := !active && !resultValid && io.invalidateReady
  when(io.effect.fire) {
    assert(!io.effect.bits.isAtomic,
      "the single-beat store engine cannot accept an atomic effect")
    assert(io.effect.bits.pmaKind === PMARegionKind.Memory.code.U,
      "the single-beat store engine cannot accept device traffic")
    active := true.B
    effectBits := io.effect.bits
    awSent := false.B
    wSent := false.B
  }

  io.aw.valid := active && !awSent
  io.aw.bits.id := writeId.U
  io.aw.bits.addr := effectBits.address
  io.aw.bits.len := 0.U
  io.aw.bits.size := effectBits.accessSize
  io.aw.bits.burst := AXI4Burst.Incrementing
  io.aw.bits.lock := false.B
  io.aw.bits.cache := "b0011".U
  io.aw.bits.prot := "b001".U
  io.aw.bits.qos := 0.U

  io.w.valid := active && !wSent
  io.w.bits.data := effectBits.writeData
  io.w.bits.strb := effectBits.writeMask
  io.w.bits.last := true.B

  when(io.aw.fire) {
    awSent := true.B
  }
  when(io.w.fire) {
    wSent := true.B
  }

  val canAcceptB = active && awSent && wSent && (!resultValid || io.result.ready)
  io.b.ready := canAcceptB
  when(io.b.valid) {
    assert(active, "AXI B arrived without a live store owner")
    assert(awSent && wSent,
      "AXI B arrived before the single-beat AW and W handshakes")
    assert(io.b.bits.id === writeId.U,
      "AXI B did not identify the live cacheable-store owner")
  }

  when(io.result.fire) {
    resultValid := false.B
  }
  when(io.b.fire) {
    resultValid := true.B
    resultBits.robTag := effectBits.robTag
    resultBits.address := effectBits.address
    resultBits.accessFault := io.b.bits.resp =/= AXI4Resp.Okay &&
      io.b.bits.resp =/= AXI4Resp.ExOkay
    active := false.B
    awSent := false.B
    wSent := false.B
  }

  io.activeStore.valid := active
  io.activeStore.bits := effectBits
  io.busy := active || resultValid

  when(active) {
    assert(effectBits.accessSize <= 2.U,
      "store AXI issued an unsupported transfer size")
    assert(effectBits.accessSize =/= 1.U || !effectBits.address(0),
      "halfword store AXI address lost its natural alignment")
    assert(effectBits.accessSize =/= 2.U || !effectBits.address(1, 0).orR,
      "word store AXI address lost its natural alignment")
  }
}
