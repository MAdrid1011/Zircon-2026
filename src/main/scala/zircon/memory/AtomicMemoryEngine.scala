package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.bus.{AXI4Address, AXI4Burst, AXI4ReadData, AXI4Resp, AXI4WriteData,
  AXI4WriteResponse}
import zircon.frontend.IntOperation

/** Serialized exact-head RV32A AXI owner.
  *
  * The engine accepts one retained LQ/SQ record at a time. LR and AMO first
  * read one word; AMO then performs its write and only produces the old value
  * after B. SC with a missing reservation produces its architecturally legal
  * `rd = 1` result without issuing a write. Accepted AR/AW/W traffic is always
  * drained, including while a later global flush discards its completion.
  */
class AtomicMemoryEngine(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val atomicId = 7

  object State extends ChiselEnum {
    val Idle, SendRead, AwaitRead, SendWrite, AwaitWrite = Value
  }

  val io = IO(new Bundle {
    val effect = Flipped(Decoupled(new AtomicMemoryEffect(config)))
    val result = Decoupled(new AtomicMemoryResult(config))
    /** A same-hart committed store invalidates only a matching reservation. */
    val invalidate = Input(Valid(UInt(32.W)))
    /** A trap/interrupt flush clears the reservation and suppresses killed
      * completion delivery, while already accepted AXI channels still drain. */
    val flush = Input(Bool())
    val ar = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val r = Flipped(Decoupled(new AXI4ReadData(dataWidth = 32, idWidth = 4)))
    val aw = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val w = Decoupled(new AXI4WriteData(dataWidth = 32))
    val b = Flipped(Decoupled(new AXI4WriteResponse(idWidth = 4)))
    val busy = Output(Bool())
    val reservationLive = Output(Bool())
    /** Combinational classification of an offered effect. A failed SC is a
      * local architectural result and must not wait behind dirty cache data. */
    val externalAccessRequired = Output(Bool())
    val externalWriteRequired = Output(Bool())
  })

  val state = RegInit(State.Idle)
  val effectBits = Reg(new AtomicMemoryEffect(config))
  val readData = Reg(UInt(32.W))
  val writeData = Reg(UInt(32.W))
  val awSent = RegInit(false.B)
  val wSent = RegInit(false.B)
  val resultValid = RegInit(false.B)
  val resultBits = Reg(new AtomicMemoryResult(config))

  val reservationValid = RegInit(false.B)
  val reservationWord = Reg(UInt(32.W))

  private def wordAddress(address: UInt): UInt = MemoryByteLanes.wordAddress(address)
  private def success(resp: UInt): Bool =
    resp === AXI4Resp.Okay || resp === AXI4Resp.ExOkay

  private def isLr(operation: UInt): Bool = operation === IntOperation.LrW.asUInt
  private def isSc(operation: UInt): Bool = operation === IntOperation.ScW.asUInt
  private def isAmo(operation: UInt): Bool = operation === IntOperation.AmoSwapW.asUInt ||
    operation === IntOperation.AmoAddW.asUInt || operation === IntOperation.AmoXorW.asUInt ||
    operation === IntOperation.AmoAndW.asUInt || operation === IntOperation.AmoOrW.asUInt ||
    operation === IntOperation.AmoMinW.asUInt || operation === IntOperation.AmoMaxW.asUInt ||
    operation === IntOperation.AmoMinuW.asUInt || operation === IntOperation.AmoMaxuW.asUInt

  private def amoWrite(operation: UInt, oldValue: UInt, operand: UInt): UInt =
    MuxLookup(operation, operand)(Seq(
      IntOperation.AmoSwapW.asUInt -> operand,
      IntOperation.AmoAddW.asUInt -> (oldValue + operand),
      IntOperation.AmoXorW.asUInt -> (oldValue ^ operand),
      IntOperation.AmoAndW.asUInt -> (oldValue & operand),
      IntOperation.AmoOrW.asUInt -> (oldValue | operand),
      IntOperation.AmoMinW.asUInt -> Mux(oldValue.asSInt < operand.asSInt, oldValue, operand),
      IntOperation.AmoMaxW.asUInt -> Mux(oldValue.asSInt > operand.asSInt, oldValue, operand),
      IntOperation.AmoMinuW.asUInt -> Mux(oldValue < operand, oldValue, operand),
      IntOperation.AmoMaxuW.asUInt -> Mux(oldValue > operand, oldValue, operand)
    ))

  private def captureResult(
      data: UInt,
      fault: Bool,
      observedRead: UInt,
      didStore: Bool
  ): Unit = {
    resultValid := true.B
    resultBits.robTag := effectBits.robTag
    resultBits.operation := effectBits.operation
    resultBits.destinationPhysical := effectBits.destinationPhysical
    resultBits.writesInteger := effectBits.writesInteger
    resultBits.data := data
    resultBits.accessFault := fault
    resultBits.faultAddress := effectBits.address
    resultBits.readData := observedRead
    resultBits.readMask := Mux(isLr(effectBits.operation) || isAmo(effectBits.operation),
      "b1111".U, 0.U)
    resultBits.writeData := Mux(didStore, writeData, 0.U)
    resultBits.writeMask := Mux(didStore, effectBits.writeMask, 0.U)
    resultBits.storePerformed := didStore
  }

  val incomingSc = isSc(io.effect.bits.operation)
  val incomingAmo = isAmo(io.effect.bits.operation)
  val incomingLr = isLr(io.effect.bits.operation)
  val incomingWord = wordAddress(io.effect.bits.address)
  val scReservationHit = reservationValid && reservationWord === incomingWord

  io.externalAccessRequired := !incomingSc || scReservationHit
  io.externalWriteRequired := incomingAmo || (incomingSc && scReservationHit)

  io.effect.ready := state === State.Idle && !resultValid && !io.flush
  io.result.valid := resultValid && !io.flush
  io.result.bits := resultBits

  io.ar.valid := state === State.SendRead
  io.ar.bits.id := atomicId.U
  io.ar.bits.addr := effectBits.address
  io.ar.bits.len := 0.U
  io.ar.bits.size := 2.U
  io.ar.bits.burst := AXI4Burst.Incrementing
  io.ar.bits.lock := false.B
  io.ar.bits.cache := "b0011".U
  io.ar.bits.prot := "b001".U
  io.ar.bits.qos := 0.U

  io.r.ready := state === State.AwaitRead

  io.aw.valid := state === State.SendWrite && !awSent
  io.aw.bits.id := atomicId.U
  io.aw.bits.addr := effectBits.address
  io.aw.bits.len := 0.U
  io.aw.bits.size := 2.U
  io.aw.bits.burst := AXI4Burst.Incrementing
  io.aw.bits.lock := false.B
  io.aw.bits.cache := "b0011".U
  io.aw.bits.prot := "b001".U
  io.aw.bits.qos := 0.U

  io.w.valid := state === State.SendWrite && !wSent
  io.w.bits.data := writeData
  io.w.bits.strb := effectBits.writeMask
  io.w.bits.last := true.B

  io.b.ready := state === State.AwaitWrite && !resultValid

  when(io.result.fire) {
    resultValid := false.B
  }

  when(io.effect.fire) {
    assert(incomingLr || incomingSc || incomingAmo,
      "atomic engine accepted an unsupported RV32A operation")
    assert(io.effect.bits.address(1, 0) === 0.U,
      "atomic engine accepted a non-word-aligned operation")
    assert(io.effect.bits.writeMask === "b1111".U || incomingLr,
      "SC/AMO must retain a full-word write mask")
    effectBits := io.effect.bits
    awSent := false.B
    wSent := false.B
    when(incomingSc && !scReservationHit) {
      // SC failure is defined by the local reservation and performs no bus write.
      resultValid := true.B
      resultBits.robTag := io.effect.bits.robTag
      resultBits.operation := io.effect.bits.operation
      resultBits.destinationPhysical := io.effect.bits.destinationPhysical
      resultBits.writesInteger := io.effect.bits.writesInteger
      resultBits.data := 1.U
      resultBits.accessFault := false.B
      resultBits.faultAddress := io.effect.bits.address
      resultBits.readData := 0.U
      resultBits.readMask := 0.U
      resultBits.writeData := 0.U
      resultBits.writeMask := 0.U
      resultBits.storePerformed := false.B
    }.elsewhen(incomingSc) {
      writeData := io.effect.bits.writeData
      state := State.SendWrite
    }.otherwise {
      state := State.SendRead
    }
  }

  when(io.ar.fire) {
    state := State.AwaitRead
  }
  when(io.r.valid) {
    assert(state === State.AwaitRead,
      "atomic AXI R arrived without a live read owner")
    assert(io.r.bits.id === atomicId.U,
      "atomic AXI R used an ID outside the reserved atomic owner")
    assert(io.r.bits.last,
      "single-word atomic AXI read arrived without RLAST")
  }
  when(io.r.fire) {
    when(io.flush) {
      state := State.Idle
    }.elsewhen(!success(io.r.bits.resp)) {
      captureResult(0.U, true.B, io.r.bits.data, false.B)
      state := State.Idle
    }.elsewhen(isLr(effectBits.operation)) {
      captureResult(io.r.bits.data, false.B, io.r.bits.data, false.B)
      state := State.Idle
    }.otherwise {
      readData := io.r.bits.data
      writeData := amoWrite(effectBits.operation, io.r.bits.data, effectBits.writeData)
      awSent := false.B
      wSent := false.B
      state := State.SendWrite
    }
  }
  when(io.aw.fire) {
    awSent := true.B
  }
  when(io.w.fire) {
    wSent := true.B
  }
  when(state === State.SendWrite && awSent && wSent) {
    state := State.AwaitWrite
  }
  when(io.b.fire) {
    assert(state === State.AwaitWrite,
      "atomic AXI B arrived without a live write owner")
    assert(io.b.bits.id === atomicId.U,
      "atomic AXI B used an ID outside the reserved atomic owner")
  }
  when(io.b.fire) {
    when(!io.flush) {
      val completionData = Mux(isSc(effectBits.operation), 0.U, readData)
      captureResult(completionData, !success(io.b.bits.resp), readData, true.B)
    }
    state := State.Idle
    awSent := false.B
    wSent := false.B
  }

  // A successful LR replaces a prior reservation. SC always consumes it; an
  // AMO or a normal same-word store only invalidates a matching reservation.
  when(io.r.fire && !io.flush && success(io.r.bits.resp) &&
      isLr(effectBits.operation)) {
    reservationValid := true.B
    reservationWord := wordAddress(effectBits.address)
  }
  when(io.invalidate.valid && reservationValid &&
      reservationWord === wordAddress(io.invalidate.bits)) {
    reservationValid := false.B
  }
  when(io.effect.fire && incomingSc) {
    reservationValid := false.B
  }.elsewhen(io.effect.fire && incomingAmo && reservationValid &&
      reservationWord === incomingWord) {
    reservationValid := false.B
  }
  when(io.flush) {
    reservationValid := false.B
    resultValid := false.B
  }

  io.busy := state =/= State.Idle || resultValid
  io.reservationLive := reservationValid
}
