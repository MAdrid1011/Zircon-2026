package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.bus.{AXI4Address, AXI4Burst, AXI4ReadData, AXI4Resp}

/** Owns cache-line AXI reads from accepted AR through the final R beat.
  *
  * IDs 1 through four are reserved for the four L1D MSHRs; ID zero remains
  * exclusively owned by instruction fetch. The engine accepts a new internal
  * request only when its previous AR payload is no longer pending, then retains
  * the owner through all interleaved response beats.
  */
class AXIDataReadEngine(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val mshrCount = config.l1d.mshrs
  private val mshrWidth = log2Ceil(mshrCount)
  private val wordsPerLine = config.l1d.lineBytes / 4
  private val beatWidth = log2Ceil(wordsPerLine + 1)

  require(mshrCount == 4, "the frozen M3 data engine owns four read IDs")
  require(wordsPerLine == 8, "the frozen M3 line refill has eight words")

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new DataReadRequest(config)))
    val response = Decoupled(new DataReadResponse(config))
    val ar = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val r = Flipped(Decoupled(new AXI4ReadData(dataWidth = 32, idWidth = 4)))
  })

  val arPending = RegInit(false.B)
  val arRequest = Reg(new DataReadRequest(config))
  val ownerValid = RegInit(VecInit.fill(mshrCount)(false.B))
  val ownerBeat = RegInit(VecInit.fill(mshrCount)(0.U(beatWidth.W)))
  val ownerFault = RegInit(VecInit.fill(mshrCount)(false.B))
  val ownerWords = Reg(Vec(mshrCount, Vec(wordsPerLine, UInt(32.W))))

  val responseValid = RegInit(false.B)
  val responseBits = Reg(new DataReadResponse(config))
  io.response.valid := responseValid
  io.response.bits := responseBits

  io.request.ready := !arPending
  when(io.request.fire) {
    assert(io.request.bits.mshr < mshrCount.U,
      "data read request named an out-of-range MSHR")
    assert(!ownerValid(io.request.bits.mshr),
      "data read request reused a live AXI owner")
    assert(io.request.bits.lineAddress(4, 0) === 0.U,
      "data read request was not cache-line aligned")
    assert(io.request.bits.lineAddress(11, 0) <= (4096 - config.l1d.lineBytes).U,
      "data read request crossed an AXI 4 KiB boundary")
    arPending := true.B
    arRequest := io.request.bits
  }

  io.ar.valid := arPending
  io.ar.bits.id := Cat(0.U(1.W), arRequest.mshr) + 1.U
  io.ar.bits.addr := arRequest.lineAddress
  io.ar.bits.len := (wordsPerLine - 1).U
  io.ar.bits.size := 2.U
  io.ar.bits.burst := AXI4Burst.Incrementing
  io.ar.bits.lock := false.B
  io.ar.bits.cache := "b0011".U
  io.ar.bits.prot := "b001".U
  io.ar.bits.qos := 0.U

  when(io.ar.fire) {
    assert(!ownerValid(arRequest.mshr),
      "data AXI AR accepted with a duplicated MSHR owner")
    arPending := false.B
    ownerValid(arRequest.mshr) := true.B
    ownerBeat(arRequest.mshr) := 0.U
    ownerFault(arRequest.mshr) := false.B
  }

  val rIdInRange = io.r.bits.id >= 1.U && io.r.bits.id <= mshrCount.U
  val rIndex = (io.r.bits.id - 1.U)(mshrWidth - 1, 0)
  val rOwnerValid = rIdInRange && ownerValid(rIndex)
  val rExpectedLast = ownerBeat(rIndex) === (wordsPerLine - 1).U
  val rWordIndex = ownerBeat(rIndex)(log2Ceil(wordsPerLine) - 1, 0)
  val responseCanAccept = !responseValid || io.response.ready
  io.r.ready := rOwnerValid && (!io.r.bits.last || responseCanAccept)

  when(io.r.valid) {
    assert(rIdInRange, "data AXI R carried an ID outside the four data owners")
    assert(rOwnerValid, "data AXI R carried an unknown or already-drained ID")
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
      responseBits.mshr := rIndex
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
    "data AXI read-owner occupancy exceeded four")
}
