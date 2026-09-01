package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.bus.{AXI4Address, AXI4Burst, AXI4ReadData, AXI4Resp}

/** Owns L2 demand AXI reads from accepted AR through the final R beat.
  *
  * IDs 1 through four identify physical L2 demand owners; ID zero remains
  * exclusively owned by instruction fetch. A client-local MSHR token is
  * retained independently and returns only with the fully drained response.
  * The engine accepts a new internal request only when its previous AR payload
  * is no longer pending, then retains the physical owner through all
  * interleaved response beats.
  */
class AXIDataReadEngine(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val mshrCount = config.l2.mshrs
  private val mshrWidth = log2Ceil(mshrCount)
  private val wordsPerLine = config.l2.lineBytes / 4
  private val beatWidth = log2Ceil(wordsPerLine + 1)

  require(mshrCount == 4, "the frozen M3 L2 demand engine owns four read IDs")
  require(wordsPerLine == 8, "the frozen M3 line refill has eight words")

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new L2DemandRequest(config)))
    val response = Decoupled(new L2DemandResponse(config))
    val ar = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val r = Flipped(Decoupled(new AXI4ReadData(dataWidth = 32, idWidth = 4)))
  })

  val arPending = RegInit(false.B)
  val arRequest = Reg(new L2DemandRequest(config))
  val arMshr = Reg(UInt(mshrWidth.W))
  val ownerValid = RegInit(VecInit.fill(mshrCount)(false.B))
  val ownerClient = Reg(Vec(mshrCount, UInt(1.W)))
  val ownerClientMshr = Reg(Vec(mshrCount, UInt(mshrWidth.W)))
  val ownerBeat = RegInit(VecInit.fill(mshrCount)(0.U(beatWidth.W)))
  val ownerFault = RegInit(VecInit.fill(mshrCount)(false.B))
  val ownerWords = Reg(Vec(mshrCount, Vec(wordsPerLine, UInt(32.W))))

  val responseValid = RegInit(false.B)
  val responseBits = Reg(new L2DemandResponse(config))
  io.response.valid := responseValid
  io.response.bits := responseBits

  val freeOwner = VecInit((0 until mshrCount).map(index => !ownerValid(index)))
  val anyFreeOwner = freeOwner.asUInt.orR
  val freeOwnerIndex = PriorityEncoder(freeOwner.asUInt)
  io.request.ready := !arPending && anyFreeOwner
  when(io.request.fire) {
    assert(io.request.bits.client <= L2DemandClient.Data.U,
      "L2 demand request named an unsupported client")
    assert(io.request.bits.clientMshr < mshrCount.U,
      "L2 demand request named an out-of-range client MSHR")
    assert(io.request.bits.lineAddress(4, 0) === 0.U,
      "L2 demand request was not cache-line aligned")
    assert(io.request.bits.lineAddress(11, 0) <= (4096 - config.l2.lineBytes).U,
      "L2 demand request crossed an AXI 4 KiB boundary")
    arPending := true.B
    arRequest := io.request.bits
    arMshr := freeOwnerIndex
  }

  io.ar.valid := arPending
  io.ar.bits.id := Cat(0.U(1.W), arMshr) + 1.U
  io.ar.bits.addr := arRequest.lineAddress
  io.ar.bits.len := (wordsPerLine - 1).U
  io.ar.bits.size := 2.U
  io.ar.bits.burst := AXI4Burst.Incrementing
  io.ar.bits.lock := false.B
  io.ar.bits.cache := "b0011".U
  io.ar.bits.prot := "b001".U
  io.ar.bits.qos := 0.U

  when(io.ar.fire) {
    assert(!ownerValid(arMshr),
      "L2 demand AXI AR accepted with a duplicated physical owner")
    arPending := false.B
    ownerValid(arMshr) := true.B
    ownerClient(arMshr) := arRequest.client
    ownerClientMshr(arMshr) := arRequest.clientMshr
    ownerBeat(arMshr) := 0.U
    ownerFault(arMshr) := false.B
  }

  val rIdInRange = io.r.bits.id >= 1.U && io.r.bits.id <= mshrCount.U
  val rIndex = (io.r.bits.id - 1.U)(mshrWidth - 1, 0)
  val rOwnerValid = rIdInRange && ownerValid(rIndex)
  val rExpectedLast = ownerBeat(rIndex) === (wordsPerLine - 1).U
  val rWordIndex = ownerBeat(rIndex)(log2Ceil(wordsPerLine) - 1, 0)
  val responseCanAccept = !responseValid || io.response.ready
  io.r.ready := rOwnerValid && (!io.r.bits.last || responseCanAccept)

  when(io.r.valid) {
    assert(rIdInRange, "L2 demand AXI R carried an ID outside the four owners")
    assert(rOwnerValid, "L2 demand AXI R carried an unknown or already-drained ID")
    when(rOwnerValid) {
      assert(io.r.bits.last === rExpectedLast,
        "data AXI RLAST did not match the owner's expected beat")
    }
  }

  when(io.response.fire) {
    responseValid := false.B
  }
  when(io.r.fire) {
    ownerWords(rIndex)(rWordIndex) := io.r.bits.data
    ownerFault(rIndex) := ownerFault(rIndex) ||
      (io.r.bits.resp =/= AXI4Resp.Okay && io.r.bits.resp =/= AXI4Resp.ExOkay)
    when(rExpectedLast) {
      responseValid := true.B
      responseBits.client := ownerClient(rIndex)
      responseBits.clientMshr := ownerClientMshr(rIndex)
      responseBits.accessFault := ownerFault(rIndex) ||
        (io.r.bits.resp =/= AXI4Resp.Okay && io.r.bits.resp =/= AXI4Resp.ExOkay)
      for (word <- 0 until wordsPerLine) {
        responseBits.lineData(word) := Mux(ownerBeat(rIndex) === word.U,
          io.r.bits.data, ownerWords(rIndex)(word))
      }
      ownerValid(rIndex) := false.B
      ownerBeat(rIndex) := 0.U
    }.otherwise {
      ownerBeat(rIndex) := ownerBeat(rIndex) + 1.U
    }
  }

  assert(PopCount(ownerValid) <= mshrCount.U,
    "L2 demand AXI read-owner occupancy exceeded four")
}
