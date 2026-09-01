package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.bus.{AXI4Address, AXI4Burst, AXI4ReadData, AXI4Resp, AXI4WriteData,
  AXI4WriteResponse}

class OrderedIORequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val order = UInt(64.W)
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val write = Bool()
  val size = UInt(2.W)
  val writeData = UInt(32.W)
  val writeMask = UInt(4.W)
  val burstable = Bool()
  val regionTag = UInt(8.W)
}

class OrderedIOGroup(
    val maxBeats: Int = 4,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Bundle {
  val count = UInt(log2Ceil(maxBeats + 1).W)
  val requests = Vec(maxBeats, new OrderedIORequest(config))
}

/** One exact response from an ordered-device group. Read data and response
  * status remain associated with the original ROB owner, rather than with a
  * later visible AXI beat or ROB head.
  */
class OrderedIOResponse(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val write = Bool()
  val readData = UInt(32.W)
  val accessFault = Bool()
}

/** Owns one already-authorized ordered-device group through AXI completion.
  *
  * The owner accepts a one through four beat homogeneous group and reserves
  * ID 6, disjoint from fetch (0), L1D fills (1--4), and the cacheable-store
  * owner (5). Read beats become individually backpressured responses. A write
  * group's single B response is fanned out only after all AW/W traffic drains,
  * so every member stays completion-gated until its irreversible bus result.
  */
class AXIOrderedIOEngine(
    val maxBeats: Int = 4,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  require(maxBeats == 4, "the frozen ordered-device group limit is four beats")

  private val deviceId = 6
  private val countWidth = log2Ceil(maxBeats + 1)
  private val indexWidth = log2Ceil(maxBeats)

  val io = IO(new Bundle {
    val group = Flipped(Decoupled(new OrderedIOGroup(maxBeats, config)))
    val response = Decoupled(new OrderedIOResponse(config))
    val ar = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val r = Flipped(Decoupled(new AXI4ReadData(dataWidth = 32, idWidth = 4)))
    val aw = Decoupled(new AXI4Address(addressWidth = 32, idWidth = 4))
    val w = Decoupled(new AXI4WriteData(dataWidth = 32))
    val b = Flipped(Decoupled(new AXI4WriteResponse(idWidth = 4)))
    val busy = Output(Bool())
  })

  val active = RegInit(false.B)
  val activeGroup = Reg(new OrderedIOGroup(maxBeats, config))
  val addressSent = RegInit(false.B)
  /** Counts emitted W beats, accepted R beats, or delivered B-derived results. */
  val beat = RegInit(0.U(countWidth.W))
  val writeResponseMode = RegInit(false.B)
  val writeFault = RegInit(false.B)
  val responseValid = RegInit(false.B)
  val responseBits = Reg(new OrderedIOResponse(config))

  val groupCount = activeGroup.count
  val groupWrite = activeGroup.requests(0).write
  val beatIndex = beat(indexWidth - 1, 0)
  val selectedRequest = activeGroup.requests(beatIndex)
  val firstRequest = activeGroup.requests(0)
  val groupReadDone = beat === groupCount
  val responseFault = io.b.bits.resp =/= AXI4Resp.Okay &&
    io.b.bits.resp =/= AXI4Resp.ExOkay

  io.group.ready := !active && !responseValid
  when(io.group.fire) {
    active := true.B
    activeGroup := io.group.bits
    addressSent := false.B
    beat := 0.U
    writeResponseMode := false.B
    writeFault := false.B
    responseValid := false.B
  }

  io.ar.valid := active && !groupWrite && !addressSent
  io.ar.bits.id := deviceId.U
  io.ar.bits.addr := firstRequest.address
  io.ar.bits.len := groupCount - 1.U
  io.ar.bits.size := firstRequest.size
  io.ar.bits.burst := AXI4Burst.Incrementing
  io.ar.bits.lock := false.B
  io.ar.bits.cache := 0.U
  io.ar.bits.prot := "b001".U
  io.ar.bits.qos := 0.U

  io.aw.valid := active && groupWrite && !addressSent
  io.aw.bits.id := deviceId.U
  io.aw.bits.addr := firstRequest.address
  io.aw.bits.len := groupCount - 1.U
  io.aw.bits.size := firstRequest.size
  io.aw.bits.burst := AXI4Burst.Incrementing
  io.aw.bits.lock := false.B
  io.aw.bits.cache := 0.U
  io.aw.bits.prot := "b001".U
  io.aw.bits.qos := 0.U

  // AXI permits W before AW. B remains blocked until both channels have drained.
  val writeDataPending = active && groupWrite && !writeResponseMode &&
    beat < groupCount
  io.w.valid := writeDataPending
  io.w.bits.data := selectedRequest.writeData
  io.w.bits.strb := selectedRequest.writeMask
  io.w.bits.last := beat === groupCount - 1.U

  val readOwnerLive = active && !groupWrite && addressSent && !responseValid &&
    beat < groupCount
  io.r.ready := readOwnerLive
  val writeOwnerLive = active && groupWrite && addressSent &&
    !writeResponseMode && beat === groupCount && !responseValid
  io.b.ready := writeOwnerLive

  io.response.valid := responseValid
  io.response.bits := responseBits

  when(io.ar.fire || io.aw.fire) {
    addressSent := true.B
  }
  when(io.w.fire) {
    beat := beat + 1.U
  }

  when(io.r.valid) {
    assert(active && !groupWrite && addressSent,
      "AXI device R arrived without a live ordered read owner")
    assert(io.r.bits.id === deviceId.U,
      "AXI device R did not identify the ordered read owner")
    when(active && !groupWrite && addressSent) {
      assert(io.r.bits.last === (beat === groupCount - 1.U),
        "AXI device RLAST did not match the ordered-group beat count")
    }
  }
  when(io.r.fire) {
    responseValid := true.B
    responseBits.robTag := selectedRequest.robTag
    responseBits.address := selectedRequest.address
    responseBits.write := false.B
    responseBits.readData := io.r.bits.data
    responseBits.accessFault := io.r.bits.resp =/= AXI4Resp.Okay &&
      io.r.bits.resp =/= AXI4Resp.ExOkay
    beat := beat + 1.U
  }

  when(io.b.valid) {
    assert(active && groupWrite && addressSent && !writeResponseMode &&
      beat === groupCount,
      "AXI device B arrived without a fully issued ordered write owner")
    assert(io.b.bits.id === deviceId.U,
      "AXI device B did not identify the ordered write owner")
  }
  when(io.b.fire) {
    writeResponseMode := true.B
    writeFault := responseFault
    beat := 0.U
    responseValid := true.B
    responseBits.robTag := firstRequest.robTag
    responseBits.address := firstRequest.address
    responseBits.write := true.B
    responseBits.readData := 0.U
    responseBits.accessFault := responseFault
  }

  when(io.response.fire) {
    when(groupWrite && writeResponseMode) {
      when(beat === groupCount - 1.U) {
        responseValid := false.B
        active := false.B
        writeResponseMode := false.B
      }.otherwise {
        val nextIndex = (beat + 1.U)(indexWidth - 1, 0)
        val nextRequest = activeGroup.requests(nextIndex)
        beat := beat + 1.U
        responseValid := true.B
        responseBits.robTag := nextRequest.robTag
        responseBits.address := nextRequest.address
        responseBits.write := true.B
        responseBits.readData := 0.U
        responseBits.accessFault := writeFault
      }
    }.otherwise {
      responseValid := false.B
      when(groupReadDone) {
        active := false.B
      }
    }
  }

  when(io.group.fire) {
    assert(io.group.bits.count =/= 0.U && io.group.bits.count <= maxBeats.U,
      "ordered AXI group count must be one through four")
    assert(!io.group.bits.requests(0).write || io.group.bits.requests(0).writeMask.orR,
      "ordered write group head has no enabled byte lane")
    when(!io.group.bits.requests(0).burstable) {
      assert(io.group.bits.count === 1.U,
        "DeviceStrong request cannot form a multi-beat AXI group")
    }
    val stride = (1.U(33.W) << io.group.bits.requests(0).size)(31, 0)
    for (index <- 1 until maxBeats) {
      when(index.U < io.group.bits.count) {
        val expectedAddress = index match {
          case 1 => io.group.bits.requests(0).address + stride
          case 2 => io.group.bits.requests(0).address + stride + stride
          case 3 => io.group.bits.requests(0).address + stride + stride + stride
        }
        assert(io.group.bits.requests(index).write === io.group.bits.requests(0).write,
          "ordered AXI group mixed read and write members")
        assert(io.group.bits.requests(index).size === io.group.bits.requests(0).size,
          "ordered AXI group mixed transfer widths")
        assert(io.group.bits.requests(index).burstable &&
          io.group.bits.requests(index).regionTag === io.group.bits.requests(0).regionTag,
          "ordered AXI group crossed a non-burstable or PMA region boundary")
        assert(io.group.bits.requests(index).address === expectedAddress,
          "ordered AXI group addresses were not contiguous")
        assert(io.group.bits.requests(index).address(31, 12) ===
          io.group.bits.requests(0).address(31, 12),
          "ordered AXI group crossed an AXI 4 KiB boundary")
        when(io.group.bits.requests(index).write) {
          assert(io.group.bits.requests(index).writeMask.orR,
            "ordered write group member has no enabled byte lane")
        }
      }
    }
  }

  assert(beat <= maxBeats.U, "ordered AXI beat accounting exceeded group capacity")
  io.busy := active || responseValid
}

/** Collects adjacent, program-order device requests. The commit controller
  * asserts forceFlush when no additional adjacent ROB entry is available.
  * An incompatible request remains backpressured until the current group has
  * been accepted, so device order cannot be inverted.
  */
class OrderedIOCombiner(
    val maxBeats: Int = 4,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  require(maxBeats == 4, "the architectural MMIO burst limit is four beats")

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OrderedIORequest(config)))
    val forceFlush = Input(Bool())
    /** Drops a locally collected but not yet externally accepted group. */
    val cancel = Input(Bool())
    val out = Decoupled(new OrderedIOGroup(maxBeats, config))
  })

  val entries = Reg(Vec(maxBeats, new OrderedIORequest(config)))
  val count = RegInit(0.U(log2Ceil(maxBeats + 1).W))
  val nonEmpty = count =/= 0.U
  val full = count === maxBeats.U
  val lastIndex = Mux(nonEmpty, count - 1.U, 0.U)
  val head = entries(0)
  val last = entries(lastIndex(log2Ceil(maxBeats) - 1, 0))
  val stride = (1.U(33.W) << io.in.bits.size)(31, 0)

  val compatible = nonEmpty &&
    head.burstable && io.in.bits.burstable &&
    (io.in.bits.order === last.order + 1.U) &&
    (io.in.bits.address === last.address + stride) &&
    (io.in.bits.write === head.write) &&
    (io.in.bits.size === head.size) &&
    (io.in.bits.regionTag === head.regionTag) &&
    (io.in.bits.address(31, 12) === head.address(31, 12))

  val closeForIncompatible = io.in.valid && !compatible
  io.out.valid := nonEmpty && !io.cancel &&
    (io.forceFlush || full || !head.burstable || closeForIncompatible)
  io.out.bits.count := count
  io.out.bits.requests := entries

  io.in.ready := !nonEmpty || (!io.out.valid && compatible && !full)

  when(io.cancel) {
    count := 0.U
  }.elsewhen(io.out.fire) {
    count := 0.U
  }.elsewhen(io.in.fire) {
    entries(count(log2Ceil(maxBeats) - 1, 0)) := io.in.bits
    count := count + 1.U
  }

  assert(count <= maxBeats.U)
  when(nonEmpty) {
    assert(head.writeMask.orR || !head.write)
  }
}

/** Streams one prevalidated group through `OrderedIOCombiner` without exposing
  * it to AXI until every member has been collected. `cancel` is legal only
  * before the combiner output fires, so a flush can remove a speculative group
  * while an accepted AXI group remains owned by AXIOrderedIOEngine.
  */
class OrderedIOGroupStreamer(
    val maxBeats: Int = 4,
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  require(maxBeats == 4, "the frozen ordered-device group limit is four beats")

  private val countWidth = log2Ceil(maxBeats + 1)
  private val indexWidth = log2Ceil(maxBeats)

  val io = IO(new Bundle {
    val group = Flipped(Decoupled(new OrderedIOGroup(maxBeats, config)))
    val request = Decoupled(new OrderedIORequest(config))
    val forceFlush = Output(Bool())
    val accepted = Input(Bool())
    val cancel = Input(Bool())
    val active = Output(Bool())
  })

  val active = RegInit(false.B)
  val heldGroup = Reg(new OrderedIOGroup(maxBeats, config))
  val nextRequest = RegInit(0.U(countWidth.W))
  val complete = active && nextRequest === heldGroup.count

  io.group.ready := !active && !io.cancel
  io.request.valid := active && !complete && !io.cancel
  io.request.bits := heldGroup.requests(nextRequest(indexWidth - 1, 0))
  io.forceFlush := complete && !io.cancel
  io.active := active

  when(io.cancel) {
    active := false.B
    nextRequest := 0.U
  }.elsewhen(io.group.fire) {
    assert(io.group.bits.count =/= 0.U && io.group.bits.count <= maxBeats.U,
      "ordered group streamer requires one through four members")
    active := true.B
    heldGroup := io.group.bits
    nextRequest := 0.U
  }.elsewhen(io.accepted) {
    assert(complete, "ordered group streamer accepted an incomplete group")
    active := false.B
    nextRequest := 0.U
  }.elsewhen(io.request.fire) {
    nextRequest := nextRequest + 1.U
  }

  when(active) {
    assert(heldGroup.count =/= 0.U && heldGroup.count <= maxBeats.U,
      "active ordered group streamer held an invalid group count")
  }
}
