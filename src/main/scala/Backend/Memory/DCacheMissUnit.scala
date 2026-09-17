import chisel3._
import chisel3.util._
import ZirconConfig.Cache._
import ZirconConfig.DCacheParams

class DCacheMissEntry(p: DCacheParams) extends DLoadRequest(p) {
    val store = Bool()
    val lane = Bool()
    val way = UInt(l1Way.W)
    val forwardData = UInt(32.W)
    val forwardMask = UInt(4.W)
    val storeData = UInt(32.W)
    val storeMask = UInt(4.W)
    val storeSize = UInt(2.W)
    val victimValid = Bool()
    val victimPaddr = UInt(34.W)
    val victimDirty = Bool()
}

class DCacheMissAllocate(p: DCacheParams) extends DCacheMissEntry(p) {
    val victimData = UInt(l1LineBits.W)
}

class DCacheInstall extends Bundle {
    val paddr = UInt(34.W)
    val way = UInt(l1Way.W)
    val data = UInt(l1LineBits.W)
    val dirty = Bool()
}

class DCacheMissCompletion(p: DCacheParams) extends Bundle {
    val store = Bool()
    val lane = Bool()
    val response = new DLoadResponse(p)
    val storeResponse = new DStoreResponse
    val preserve = Bool()
}

class DCacheMissUnitIO(p: DCacheParams) extends Bundle {
    val allocate = Flipped(Decoupled(new DCacheMissAllocate(p)))
    val flush = Input(Bool())
    val memory = new Bundle {
        val req = Decoupled(new DMemoryRequest)
        val rsp = Flipped(Decoupled(new DMemoryResponse))
    }
    val install = Decoupled(new DCacheInstall)
    val complete = Decoupled(new DCacheMissCompletion(p))
    val busy = Output(Bool())
}

/** One outstanding cached refill or ordered uncached access.
  *
  * Every outward control output is decoded from `state`. Lower response data only
  * updates local registers and cannot reach another module combinationally.
  */
class DCacheMissUnit(val p: DCacheParams = DCacheParams()) extends Module {
    val io = IO(new DCacheMissUnitIO(p))

    val idle :: send :: waitResponse :: install :: respond :: Nil = Enum(5)
    val state = RegInit(idle)
    val entry = Reg(new DCacheMissEntry(p))
    val line = Reg(UInt(l1LineBits.W))
    val lineDirty = RegInit(false.B)
    val error = RegInit(false.B)
    val discard = RegInit(false.B)

    def offset(address: UInt): UInt = address(l1Offset - 1, 0)
    def requestedMask(request: DLoadRequest): UInt =
        MuxLookup(request.mtype(1, 0), 0.U(4.W))(Seq(0.U -> 1.U, 1.U -> 3.U, 2.U -> 15.U))
    def extend(data: UInt, mtype: UInt): UInt = MuxLookup(mtype(1, 0), data)(Seq(
        0.U -> Cat(Fill(24, data(7) && !mtype(2)), data(7, 0)),
        1.U -> Cat(Fill(16, data(15) && !mtype(2)), data(15, 0))
    ))

    val preserve = entry.store || (entry.uncache && entry.ioAuthorized)
    val flushed = io.flush && !preserve
    val cachedWord = (line >> (offset(entry.paddr) << 3))(31, 0)
    val memoryWord = Mux(entry.uncache, line(31, 0), cachedWord)
    val byteOffset = entry.paddr(1, 0)
    val shiftedForwardData = entry.forwardData >> (byteOffset << 3)
    val shiftedForwardMask = entry.forwardMask >> byteOffset
    val mergedWord = VecInit((0 until 4).map { byte =>
        Mux(
            shiftedForwardMask(byte),
            shiftedForwardData(8 * byte + 7, 8 * byte),
            memoryWord(8 * byte + 7, 8 * byte)
        )
    }).asUInt
    val storeLineMask = (entry.storeMask << Cat(entry.paddr(l1Offset - 1, 2), 0.U(2.W)))(l1Line - 1, 0)
    val storeLineData = Fill(l1Line / 4, entry.storeData)
    val installedLine = VecInit((0 until l1Line).map { byte =>
        Mux(storeLineMask(byte), storeLineData(8 * byte + 7, 8 * byte), line(8 * byte + 7, 8 * byte))
    }).asUInt

    io.allocate.ready := state === idle
    io.memory.req.valid := state === send
    io.memory.req.bits := 0.U.asTypeOf(new DMemoryRequest)
    io.memory.req.bits.paddr := Mux(
        entry.uncache,
        entry.paddr,
        Cat(entry.paddr(33, l1Offset), 0.U(l1Offset.W))
    )
    io.memory.req.bits.write := entry.store && entry.uncache
    io.memory.req.bits.uncache := entry.uncache
    io.memory.req.bits.size := Mux(entry.store, entry.storeSize, entry.mtype(1, 0))
    io.memory.req.bits.data := entry.storeData
    io.memory.req.bits.mask := entry.storeMask
    io.memory.req.bits.victimValid := entry.victimValid && !entry.uncache
    io.memory.req.bits.victimPaddr := entry.victimPaddr
    io.memory.req.bits.victimData := line
    io.memory.req.bits.victimDirty := entry.victimDirty
    io.memory.req.bits.victimOnly := false.B
    io.memory.rsp.ready := state === waitResponse

    io.install.valid := state === install
    io.install.bits.paddr := entry.paddr
    io.install.bits.way := entry.way
    io.install.bits.data := Mux(entry.store, installedLine, line)
    io.install.bits.dirty := entry.store || lineDirty

    io.complete.valid := state === respond && !discard
    io.complete.bits.store := entry.store
    io.complete.bits.lane := entry.lane
    io.complete.bits.preserve := preserve
    io.complete.bits.response.slot := entry.slot
    io.complete.bits.response.data := extend(mergedWord, entry.mtype)
    io.complete.bits.response.exception := Mux(error, 5.U, 0.U)
    io.complete.bits.response.retry := false.B
    io.complete.bits.storeResponse.exception := Mux(error, 7.U, 0.U)
    io.busy := state =/= idle

    switch(state) {
        is(idle) {
            when(io.allocate.fire) {
                entry := 0.U.asTypeOf(new DCacheMissEntry(p))
                for ((name, field) <- io.allocate.bits.elements if entry.elements.contains(name)) {
                    entry.elements(name) := field
                }
                line := io.allocate.bits.victimData
                lineDirty := false.B
                error := false.B
                discard := false.B
                state := send
            }
        }
        is(send) {
            when(io.memory.req.fire) {
                state := waitResponse
            }
        }
        is(waitResponse) {
            when(io.memory.rsp.fire) {
                line := io.memory.rsp.bits.data
                lineDirty := io.memory.rsp.bits.dirty
                error := io.memory.rsp.bits.error
                when(io.memory.rsp.bits.error || entry.uncache) {
                    state := Mux(discard, idle, respond)
                }.elsewhen(discard && !io.memory.rsp.bits.dirty) {
                    state := idle
                }.otherwise {
                    state := install
                }
            }
        }
        is(install) {
            when(io.install.fire) {
                state := Mux(discard, idle, respond)
            }
        }
        is(respond) {
            when(io.complete.fire || discard) {
                state := idle
            }
        }
    }

    // Recovery owns the edge. A request accepted by lower memory on the same
    // edge must still be drained, while an unsent request can be removed.
    when(flushed) {
        switch(state) {
            is(send) {
                when(io.memory.req.fire) {
                    discard := true.B
                    state := waitResponse
                }.otherwise {
                    state := idle
                }
            }
            is(waitResponse) {
                when(io.memory.rsp.fire) {
                    discard := true.B
                    state := Mux(
                        io.memory.rsp.bits.error || entry.uncache || !io.memory.rsp.bits.dirty,
                        idle,
                        install
                    )
                }.otherwise {
                    discard := true.B
                }
            }
            is(install) {
                discard := true.B
                when(!lineDirty) {
                    state := idle
                }
            }
            is(respond) {
                state := idle
            }
        }
    }

    when(state === install) {
        assert(!entry.uncache && !error, "DCacheMissUnit: only successful cached accesses install")
    }
    when(io.allocate.fire) {
        val mask = (requestedMask(io.allocate.bits) << io.allocate.bits.paddr(1, 0))(3, 0)
        assert(io.allocate.bits.way.orR || io.allocate.bits.uncache, "DCacheMissUnit: cached miss needs a victim")
        assert(PopCount(io.allocate.bits.way) <= 1.U, "DCacheMissUnit: victim must be one-hot")
        when(!io.allocate.bits.store) {
            assert(!(io.allocate.bits.forwardMask & ~mask).orR, "DCacheMissUnit: forwarding exceeds access mask")
        }
        when(io.allocate.bits.victimValid) {
            assert(!io.allocate.bits.uncache, "DCacheMissUnit: uncached access cannot carry a victim")
            assert(io.allocate.bits.victimPaddr(l1Offset - 1, 0) === 0.U)
        }
    }
}
