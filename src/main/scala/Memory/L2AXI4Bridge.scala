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

class AXI4WriteData(dataBits: Int = 64) extends Bundle {
    val data = UInt(dataBits.W)
    val strb = UInt((dataBits / 8).W)
    val last = Bool()
}

class AXI4WriteResponse extends Bundle {
    val id = UInt(4.W)
    val resp = UInt(2.W)
}

class AXI4ReadData(dataBits: Int = 64) extends Bundle {
    val id = UInt(4.W)
    val data = UInt(dataBits.W)
    val resp = UInt(2.W)
    val last = Bool()
}

class AXI4MasterIO(dataBits: Int = 64) extends Bundle {
    require(dataBits == 64 || dataBits == 256)
    val ar = Decoupled(new AXI4Address)
    val r = Flipped(Decoupled(new AXI4ReadData(dataBits)))
    val aw = Decoupled(new AXI4Address)
    val w = Decoupled(new AXI4WriteData(dataBits))
    val b = Flipped(Decoupled(new AXI4WriteResponse))
}

class L2AXI4BridgeIO(p: L2CacheParams, dataBits: Int = 64) extends Bundle {
    val memory = Flipped(new L2MemoryIO(p))
    val axi = new AXI4MasterIO(dataBits)
}

class L2BridgeRequest(p: L2CacheParams) extends Bundle {
    val paddr = UInt(34.W)
    val write = Bool()
    val uncache = Bool()
    val writeCombine = Bool()
    val burstBeats = UInt(4.W)
    val burstMask = UInt(p.lineBytes.W)
    val size = UInt(2.W)
    val data = UInt(p.lineBits.W)
    val mask = UInt(8.W)
}

/** Converts one complete L2 lower-memory transaction into AXI4 transfers.
  *
  * The L2 currently permits one lower-memory transaction at a time, so the bridge
  * uses AXI ID zero and deliberately serializes all reads and writes. A cached
  * cache line uses the selected AXI width. Device transfers stay narrow;
  * write-combine transfers remain 8-byte INCR beats on either bus width.
  */
class L2AXI4Bridge(val p: L2CacheParams = L2CacheParams(), val dataBits: Int = 64) extends Module {
    private val axiBytes = dataBits / 8
    private val lineBeats = p.lineBytes / axiBytes
    private val lineBeatBits = math.max(1, log2Ceil(lineBeats))
    private val narrowBeats = p.lineBytes / 8
    private val beatBits = math.max(1, log2Ceil(narrowBeats))
    private val byteBits = log2Ceil(axiBytes)

    require(p.lineBytes % axiBytes == 0)
    require(isPow2(lineBeats))

    val io = IO(new L2AXI4BridgeIO(p, dataBits))

    val idle :: readAddress :: readData :: writeAddress :: writeData :: writeResponse :: respond :: Nil = Enum(7)
    val state = RegInit(idle)
    // A fresh request fills every field before the bridge leaves idle.
    val request = Reg(new L2BridgeRequest(p))
    val beat = RegInit(0.U(beatBits.W))
    val readBeats = Reg(Vec(lineBeats, UInt(dataBits.W)))
    val responseError = RegInit(false.B)

    val lastBeat = Mux(
        request.writeCombine,
        (request.burstBeats - 1.U)(beatBits - 1, 0),
        Mux(request.uncache, 0.U(beatBits.W), (lineBeats - 1).U(beatBits.W)),
    )
    val requestBeats = request.data.asTypeOf(Vec(lineBeats, UInt(dataBits.W)))
    val narrowData = request.data.asTypeOf(Vec(narrowBeats, UInt(64.W)))
    val addressSize = Mux(request.writeCombine, 3.U(3.W), Mux(request.uncache, request.size.pad(3), byteBits.U(3.W)))
    val addressLength = Mux(
        request.writeCombine,
        request.burstBeats - 1.U,
        Mux(request.uncache, 0.U(8.W), (lineBeats - 1).U(8.W)),
    )
    val addressCache = Mux(request.uncache, 0.U(4.W), "b1111".U(4.W))

    io.memory.req.ready := state === idle
    io.memory.rsp.valid := state === respond
    val cachedReadData = VecInit((0 until lineBeats).map { index =>
        Mux(index.U <= beat, readBeats(index), 0.U(dataBits.W))
    }).asUInt
    io.memory.rsp.bits.data := Mux(request.write, 0.U,
        Mux(request.uncache, readBeats(0).pad(p.lineBits), cachedReadData))
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
    // Narrow payloads enter L2 positioned within an 8-byte word. Widening only
    // changes their containing byte lanes, never AWSIZE, strobes or ordering.
    val uncachedOffset = if (axiBytes == 8) 0.U(3.W) else
        Cat(request.paddr(byteBits - 1, 3), 0.U(3.W))
    val burstOffset = (request.paddr(byteBits - 1, 0) + (beat << 3))(byteBits - 1, 0)
    val uncachedData = (request.data(63, 0).pad(dataBits) << (uncachedOffset << 3))(dataBits - 1, 0)
    val uncachedStrobe = (request.mask.pad(axiBytes) << uncachedOffset)(axiBytes - 1, 0)
    val burstMasks = request.burstMask.asTypeOf(Vec(narrowBeats, UInt(8.W)))
    val combinedData = (narrowData(beat).pad(dataBits) << (burstOffset << 3))(dataBits - 1, 0)
    val combinedStrobe = (burstMasks(beat).pad(axiBytes) << burstOffset)(axiBytes - 1, 0)
    io.axi.w.bits.data := Mux(request.writeCombine, combinedData,
        Mux(request.uncache, uncachedData, requestBeats(beat(lineBeatBits - 1, 0))))
    io.axi.w.bits.strb := Mux(request.writeCombine, combinedStrobe,
        Mux(request.uncache, uncachedStrobe, ((BigInt(1) << axiBytes) - 1).U(axiBytes.W)))
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
            "b1111".U(8.W),
            Mux(io.memory.req.bits.size === 1.U, "b0011".U(8.W), "b0001".U(8.W))
        ) << io.memory.req.bits.paddr(2, 0)

        assert(
            io.memory.req.bits.uncache || io.memory.req.bits.paddr(p.offsetBits - 1, 0) === 0.U,
            "L2AXI4Bridge: cached requests must be line aligned"
        )
        when(io.memory.req.bits.uncache && !io.memory.req.bits.writeCombine) {
            assert(io.memory.req.bits.size <= 2.U, "L2AXI4Bridge: uncached transfer is wider than AXI data")
            assert(
                (io.memory.req.bits.paddr(2, 0) & alignmentMask) === 0.U,
                "L2AXI4Bridge: uncached transfer is misaligned"
            )
            when(io.memory.req.bits.write) {
                assert(
                    io.memory.req.bits.mask === uncachedMask,
                    "L2AXI4Bridge: uncached write strobe does not match its address and size"
                )
            }
        }
        when(io.memory.req.bits.writeCombine) {
            assert(io.memory.req.bits.burstBeats > 0.U && io.memory.req.bits.burstBeats <= narrowBeats.U)
            assert(io.memory.req.bits.paddr(2, 0) === 0.U)
            assert(io.memory.req.bits.paddr(11, 0) + (io.memory.req.bits.burstBeats << 3) <= 4096.U)
        }

        request.paddr := io.memory.req.bits.paddr
        request.write := io.memory.req.bits.write
        request.uncache := io.memory.req.bits.uncache
        request.writeCombine := io.memory.req.bits.writeCombine
        request.burstBeats := io.memory.req.bits.burstBeats
        request.burstMask := io.memory.req.bits.burstMask
        request.size := io.memory.req.bits.size
        request.data := io.memory.req.bits.data
        request.mask := io.memory.req.bits.mask
        beat := 0.U
        responseError := false.B
        state := Mux(io.memory.req.bits.write, writeAddress, readAddress)
    }

    when(io.axi.ar.fire) {
        state := readData
    }

    when(io.axi.r.fire) {
        val expectedLast = beat === lastBeat
        val words = io.axi.r.bits.data.asTypeOf(Vec(axiBytes / 4, UInt(32.W)))
        val uncachedWord = words(request.paddr(byteBits - 1, 2))
        val shiftedData = uncachedWord >> (request.paddr(1, 0) << 3)
        val selectedData = Mux(
            request.size === 0.U,
            shiftedData & "h000000ff".U,
            Mux(request.size === 1.U, shiftedData & "h0000ffff".U, shiftedData)
        )

        readBeats(beat(lineBeatBits - 1, 0)) := Mux(request.uncache, selectedData, io.axi.r.bits.data)
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
