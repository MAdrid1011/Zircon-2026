import chisel3._
import chisel3.util._

class SharedStorePortIO extends Bundle {
    val reserveAtomic = Input(Bool())
    val atomicRequest = Flipped(Decoupled(new DStoreRequest))
    val atomicResponse = Decoupled(new DStoreResponse)
    val committedRequest = Flipped(Decoupled(new DStoreRequest))
    val committedResponse = Decoupled(new DStoreResponse)
    val cacheRequest = Decoupled(new DStoreRequest)
    val cacheResponse = Flipped(Decoupled(new DStoreResponse))
}

/** Serializes committed stores with an atomic read-modify-write sequence. */
class SharedStorePort extends Module {
    val io = IO(new SharedStorePortIO)

    val ownerValid = RegInit(false.B)
    val ownerAtomic = RegInit(false.B)

    io.cacheRequest.valid := Mux(io.reserveAtomic, io.atomicRequest.valid, io.committedRequest.valid)
    io.cacheRequest.bits := Mux(io.reserveAtomic, io.atomicRequest.bits, io.committedRequest.bits)
    io.atomicRequest.ready := io.reserveAtomic && io.cacheRequest.ready
    io.committedRequest.ready := !io.reserveAtomic && io.cacheRequest.ready

    io.atomicResponse.valid := ownerValid && ownerAtomic && io.cacheResponse.valid
    io.atomicResponse.bits := io.cacheResponse.bits
    io.committedResponse.valid := ownerValid && !ownerAtomic && io.cacheResponse.valid
    io.committedResponse.bits := io.cacheResponse.bits
    io.cacheResponse.ready := ownerValid && Mux(
        ownerAtomic,
        io.atomicResponse.ready,
        io.committedResponse.ready,
    )

    when(io.cacheRequest.fire) {
        ownerAtomic := io.reserveAtomic
    }
    when(io.cacheRequest.fire =/= io.cacheResponse.fire) {
        ownerValid := io.cacheRequest.fire
    }

    when(io.cacheRequest.fire) {
        assert(!ownerValid || io.cacheResponse.fire, "Shared store port accepted a second outstanding request")
    }
    when(io.cacheResponse.valid) {
        assert(ownerValid, "Shared store port received a response without an owner")
    }
}
