import chisel3._
import chisel3.util._
import ZirconConfig.L2CacheParams

class AXI4Address extends Bundle {
    val id = UInt(4.W)
    val addr = UInt(34.W)
    val len = UInt(8.W)
    val size = UInt(3.W)
    val burst = UInt(2.W)
    val lock = Bool()
    val cache = UInt(4.W)
    val prot = UInt(3.W)
    val qos = UInt(4.W)
}

class AXI4WriteData extends Bundle {
    val data = UInt(32.W)
    val strb = UInt(4.W)
    val last = Bool()
}

class AXI4WriteResponse extends Bundle {
    val id = UInt(4.W)
    val resp = UInt(2.W)
}

class AXI4ReadData extends Bundle {
    val id = UInt(4.W)
    val data = UInt(32.W)
    val resp = UInt(2.W)
    val last = Bool()
}

class AXI4MasterIO extends Bundle {
    val ar = Decoupled(new AXI4Address)
    val r = Flipped(Decoupled(new AXI4ReadData))
    val aw = Decoupled(new AXI4Address)
    val w = Decoupled(new AXI4WriteData)
    val b = Flipped(Decoupled(new AXI4WriteResponse))
}

class L2AXI4BridgeIO(p: L2CacheParams) extends Bundle {
    val memory = Flipped(new L2MemoryIO(p))
    val axi = new AXI4MasterIO
}

class L2BridgeRequest(p: L2CacheParams) extends Bundle {
    val paddr = UInt(34.W)
    val write = Bool()
    val uncache = Bool()
    val size = UInt(2.W)
    val data = UInt(p.lineBits.W)
    val mask = UInt(4.W)
}

/** Converts one complete L2 lower-memory transaction into AXI4 transfers.
  *
  * The L2 currently permits one lower-memory transaction at a time, so the bridge
  * uses AXI ID zero and deliberately serializes all reads and writes. A cached
  * cache line is transferred as 32-bit INCR beats. An uncached request is
  * a single narrow beat whose data and strobe use the address-selected bus lanes.
  */
class L2AXI4Bridge(val p: L2CacheParams = L2CacheParams()) extends Module {
    private val axiBytes = 4
    private val lineBeats = p.lineBytes / axiBytes
    private val beatBits = log2Ceil(lineBeats)

    require(p.lineBytes % axiBytes == 0)
    require(isPow2(lineBeats))

    val io = IO(new L2AXI4BridgeIO(p))

    val idle :: readAddress :: readData :: writeAddress :: writeData :: writeResponse :: respond :: Nil = Enum(7)
    val state = RegInit(idle)
    val request = RegInit(0.U.asTypeOf(new L2BridgeRequest(p)))
    val beat = RegInit(0.U(beatBits.W))
    val readBeats = RegInit(VecInit.fill(lineBeats)(0.U(32.W)))
    val responseError = RegInit(false.B)

    val lastBeat = Mux(request.uncache, 0.U(beatBits.W), (lineBeats - 1).U(beatBits.W))
    val requestBeats = request.data.asTypeOf(Vec(lineBeats, UInt(32.W)))
    val addressSize = Mux(request.uncache, request.size.pad(3), 2.U(3.W))
    val addressLength = Mux(request.uncache, 0.U(8.W), (lineBeats - 1).U(8.W))
    val addressCache = Mux(request.uncache, 0.U(4.W), "b1111".U(4.W))

    io.memory.req.ready := state === idle
    io.memory.rsp.valid := state === respond
    io.memory.rsp.bits.data := Mux(request.write, 0.U, readBeats.asUInt)
    io.memory.rsp.bits.error := responseError

    io.axi.ar.valid := state === readAddress
    io.axi.ar.bits := 0.U.asTypeOf(new AXI4Address)
    io.axi.ar.bits.addr := request.paddr
    io.axi.ar.bits.len := addressLength
    io.axi.ar.bits.size := addressSize
    io.axi.ar.bits.burst := 1.U
    io.axi.ar.bits.cache := addressCache

    io.axi.r.ready := state === readData

    io.axi.aw.valid := state === writeAddress
    io.axi.aw.bits := 0.U.asTypeOf(new AXI4Address)
    io.axi.aw.bits.addr := request.paddr
    io.axi.aw.bits.len := addressLength
    io.axi.aw.bits.size := addressSize
    io.axi.aw.bits.burst := 1.U
    io.axi.aw.bits.cache := addressCache

    io.axi.w.valid := state === writeData
    io.axi.w.bits.data := Mux(request.uncache, request.data(31, 0), requestBeats(beat))
    io.axi.w.bits.strb := Mux(request.uncache, request.mask(3, 0), "b1111".U)
    io.axi.w.bits.last := beat === lastBeat

    io.axi.b.ready := state === writeResponse

    when(io.memory.req.fire) {
        val alignmentMask = Mux(
            io.memory.req.bits.size === 2.U,
            3.U(2.W),
            Mux(io.memory.req.bits.size === 1.U, 1.U(2.W), 0.U(2.W))
        )
        val uncachedMask = Mux(
            io.memory.req.bits.size === 2.U,
            "b1111".U(4.W),
            Mux(io.memory.req.bits.size === 1.U, "b0011".U(4.W), "b0001".U(4.W))
        ) << io.memory.req.bits.paddr(1, 0)

        assert(
            io.memory.req.bits.uncache || io.memory.req.bits.paddr(p.offsetBits - 1, 0) === 0.U,
            "L2AXI4Bridge: cached requests must be line aligned"
        )
        when(io.memory.req.bits.uncache) {
            assert(io.memory.req.bits.size <= 2.U, "L2AXI4Bridge: uncached transfer is wider than AXI data")
            assert(
                (io.memory.req.bits.paddr(1, 0) & alignmentMask) === 0.U,
                "L2AXI4Bridge: uncached transfer is misaligned"
            )
            when(io.memory.req.bits.write) {
                assert(
                    io.memory.req.bits.mask === uncachedMask(3, 0),
                    "L2AXI4Bridge: uncached write strobe does not match its address and size"
                )
            }
        }

        request.paddr := io.memory.req.bits.paddr
        request.write := io.memory.req.bits.write
        request.uncache := io.memory.req.bits.uncache
        request.size := io.memory.req.bits.size
        request.data := io.memory.req.bits.data
        request.mask := io.memory.req.bits.mask
        beat := 0.U
        readBeats.foreach(_ := 0.U)
        responseError := false.B
        state := Mux(io.memory.req.bits.write, writeAddress, readAddress)
    }

    when(io.axi.ar.fire) {
        state := readData
    }

    when(io.axi.r.fire) {
        val expectedLast = beat === lastBeat
        val shiftedData = io.axi.r.bits.data >> (request.paddr(1, 0) << 3)
        val selectedData = Mux(
            request.size === 0.U,
            shiftedData & "h000000ff".U,
            Mux(request.size === 1.U, shiftedData & "h0000ffff".U, shiftedData)
        )

        readBeats(beat) := Mux(request.uncache, selectedData, io.axi.r.bits.data)
        responseError := responseError || io.axi.r.bits.id.orR || io.axi.r.bits.resp.orR ||
            (io.axi.r.bits.last =/= expectedLast)
        when(io.axi.r.bits.last || expectedLast) {
            state := respond
        }.otherwise {
            beat := beat + 1.U
        }
    }

    when(io.axi.aw.fire) {
        beat := 0.U
        state := writeData
    }

    when(io.axi.w.fire) {
        when(beat === lastBeat) {
            state := writeResponse
        }.otherwise {
            beat := beat + 1.U
        }
    }

    when(io.axi.b.fire) {
        responseError := responseError || io.axi.b.bits.id.orR || io.axi.b.bits.resp.orR
        state := respond
    }

    when(io.memory.rsp.fire) {
        state := idle
    }
}
