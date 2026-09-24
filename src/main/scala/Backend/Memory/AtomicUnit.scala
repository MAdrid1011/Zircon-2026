import chisel3._
import chisel3.util._
import ZirconConfig.{BackendParams, CommitParams, DCacheParams}

class AtomicRequest(p: BackendParams) extends Bundle {
    val robIdx = UInt(CommitIndex.addressWidth(CommitParams().robEntries).W)
    val prd = UInt(p.tagWidth.W)
    val vaddr = UInt(32.W)
    val paddr = UInt(34.W)
    val data = UInt(32.W)
    val op = UInt(5.W)
    val uncache = Bool()
    val exception = UInt(4.W)
}

class AtomicResponse(p: BackendParams) extends Bundle {
    val robIdx = UInt(CommitIndex.addressWidth(CommitParams().robEntries).W)
    val prd = UInt(p.tagWidth.W)
    val vaddr = UInt(32.W)
    val data = UInt(32.W)
    val exception = UInt(4.W)
}

class AtomicBackendIO(p: BackendParams) extends Bundle {
    val request = Flipped(Decoupled(new AtomicRequest(p)))
    val response = Decoupled(new AtomicResponse(p))
    val blockMemoryIssue = Input(Bool())
}

class AtomicLoadIO(cache: DCacheParams) extends Bundle {
    val request = Decoupled(new DLoadRequest(cache))
    val response = Flipped(Valid(new AtomicLoadResponse))
    val forwardQuery = Flipped(Valid(new DForwardQuery(cache)))
    val forwardResult = Valid(new DForwardResult(cache))
}

class AtomicLoadResponse extends Bundle {
    val data = UInt(32.W)
    val exception = UInt(4.W)
    val retry = Bool()
}

class AtomicStoreIO extends Bundle {
    val request = Decoupled(new DStoreRequest)
    val response = Flipped(Decoupled(new DStoreResponse))
}

class AtomicUnitIO(p: BackendParams, cache: DCacheParams, observe: Boolean) extends Bundle {
    val request = Flipped(Decoupled(new AtomicRequest(p)))
    val response = Decoupled(new AtomicResponse(p))
    val clearReservation = Input(Bool())
    val busy = Output(Bool())

    val load = new AtomicLoadIO(cache)
    val store = new AtomicStoreIO
    val debug = if (observe) Some(Output(new AtomicUnitDebugIO)) else None
}

/** Commit-authorized RV32 word atomics sharing one DCache load lane and its store port. */
class AtomicUnit(
    val p: BackendParams = BackendParams(),
    val cache: DCacheParams = DCacheParams(),
    val observe: Boolean = false,
) extends Module {
    val io = IO(new AtomicUnitIO(p, cache, observe))
    val idle :: loadRequest :: loadWait :: storeRequest :: storeWait :: respond :: Nil = Enum(6)
    val state = RegInit(idle)
    val request = RegInit(0.U.asTypeOf(new AtomicRequest(p)))
    val result = RegInit(0.U(32.W))
    val exception = RegInit(0.U(4.W))
    val reservationValid = RegInit(false.B)
    val reservationAddress = RegInit(0.U(32.W))

    val isLr = request.op === 2.U
    val isSc = request.op === 3.U
    val reservationHit = reservationValid && reservationAddress === request.paddr(33, 2)
    val signedLess = request.data.asSInt < result.asSInt
    val unsignedLess = request.data < result
    val storeData = MuxLookup(request.op, request.data)(Seq(
        0.U -> (result + request.data),
        1.U -> request.data,
        3.U -> request.data,
        4.U -> (result ^ request.data),
        8.U -> (result | request.data),
        12.U -> (result & request.data),
        16.U -> Mux(signedLess, request.data, result),
        20.U -> Mux(signedLess, result, request.data),
        24.U -> Mux(unsignedLess, request.data, result),
        28.U -> Mux(unsignedLess, result, request.data),
    ))

    io.request.ready := state === idle
    io.response.valid := state === respond
    io.response.bits.robIdx := request.robIdx
    io.response.bits.prd := request.prd
    io.response.bits.vaddr := request.vaddr
    io.response.bits.data := result
    io.response.bits.exception := exception
    io.busy := state =/= idle

    io.load.request.valid := state === loadRequest
    io.load.request.bits := 0.U.asTypeOf(new DLoadRequest(cache))
    io.load.request.bits.vaddr := request.vaddr
    io.load.request.bits.paddr := request.paddr
    io.load.request.bits.mtype := 2.U
    io.load.request.bits.uncache := request.uncache
    io.load.request.bits.ioAuthorized := false.B
    io.load.request.bits.exception := request.exception
    io.load.request.bits.atomic := true.B
    val forwardValid = RegNext(io.load.forwardQuery.valid, false.B)
    val forwardSlot = RegEnable(io.load.forwardQuery.bits.slot, io.load.forwardQuery.valid)
    io.load.forwardResult.valid := forwardValid
    io.load.forwardResult.bits.slot := forwardSlot
    io.load.forwardResult.bits.data := 0.U
    io.load.forwardResult.bits.mask := 0.U
    io.load.forwardResult.bits.blocked := false.B

    io.store.request.valid := state === storeRequest
    io.store.request.bits.paddr := request.paddr
    io.store.request.bits.data := storeData
    io.store.request.bits.mask := 15.U
    io.store.request.bits.size := 2.U
    io.store.request.bits.uncache := request.uncache
    io.store.response.ready := state === storeWait

    when(io.clearReservation) {
        reservationValid := false.B
    }
    when(io.request.fire) {
        request := io.request.bits
        result := 0.U
        exception := io.request.bits.exception
        when(io.request.bits.exception.orR || io.request.bits.uncache) {
            exception := Mux(
                io.request.bits.exception.orR,
                io.request.bits.exception,
                Mux(io.request.bits.op === 2.U, 5.U, 7.U),
            )
            state := respond
        }.elsewhen(io.request.bits.op === 3.U) {
            reservationValid := false.B
            when(reservationValid && reservationAddress === io.request.bits.paddr(33, 2)) {
                state := storeRequest
            }.otherwise {
                result := 1.U
                state := respond
            }
        }.otherwise {
            when(io.request.bits.op =/= 2.U) {
                reservationValid := false.B
            }
            state := loadRequest
        }
    }
    when(io.load.request.fire) {
        state := loadWait
    }
    when(state === loadWait && io.load.response.valid) {
        result := io.load.response.bits.data
        exception := io.load.response.bits.exception
        when(io.load.response.bits.retry) {
            state := loadRequest
        }.elsewhen(io.load.response.bits.exception.orR) {
            state := respond
        }.elsewhen(isLr) {
            reservationValid := true.B
            reservationAddress := request.paddr(33, 2)
            state := respond
        }.otherwise {
            state := storeRequest
        }
    }
    when(io.store.request.fire) {
        state := storeWait
    }
    when(io.store.response.fire) {
        exception := io.store.response.bits.exception
        when(isSc && !io.store.response.bits.exception.orR) {
            result := 0.U
        }
        state := respond
    }
    when(io.response.fire) {
        state := idle
    }

    when(state === storeRequest || state === storeWait) {
        assert(!isLr)
        assert(!isSc || reservationHit || !reservationValid)
    }

    if (observe) {
        io.debug.get.state := state
    }
}

class AtomicUnitDebugIO extends Bundle {
    val state = UInt(3.W)
}
