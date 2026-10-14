import chisel3._
import chisel3.util._
import ZirconConfig.CSRAddress

/** Ordered, nonprivileged state of the computation core. */
class PerformanceCSR extends CSRBase(vectorEnabled = true) {
    val cycleLow = RegInit(0.U(32.W))
    val cycleHigh = RegInit(0.U(32.W))
    val instretLow = RegInit(0.U(32.W))
    val instretHigh = RegInit(0.U(32.W))
    val cycle = Cat(cycleHigh, cycleLow)
    val instret = Cat(instretHigh, instretLow)
    val flags = RegInit(0.U(5.W))
    val rounding = RegInit(0.U(3.W))
    val initialVector = WireDefault(0.U.asTypeOf(new VectorCSRState))
    initialVector.vtype := "h80000000".U
    val vector = RegInit(initialVector)
    val cycleIncrement = BLevelPAdder32(cycleLow, 1.U(32.W), 0.U)
    val instretIncrement = BLevelPAdder32(instretLow, io.retired.pad(32), 0.U)
    val cycleHighIncrement = BLevelPAdder32.sum(cycleHigh, 1.U(32.W), 0.U)
    val instretHighIncrement = BLevelPAdder32.sum(instretHigh, 1.U(32.W), 0.U)
    cycleLow := cycleIncrement.io.res
    instretLow := instretIncrement.io.res
    when(cycleIncrement.io.cout.get.asBool) { cycleHigh := cycleHighIncrement }
    when(instretIncrement.io.cout.get.asBool) { instretHigh := instretHighIncrement }

    val request = io.req.bits
    val writes = request.rw || request.source.orR
    val reads = !request.rw || !request.rdZero
    val operand = Mux(request.immediate, request.source.pad(32),
        Mux(request.source.orR, request.data, 0.U))
    val entries = Seq(
        CSRAddress.fflags -> flags.pad(32),
        CSRAddress.frm -> rounding.pad(32),
        CSRAddress.fcsr -> Cat(rounding, flags).pad(32),
        0x008 -> vector.vstart,
        0x009 -> Cat(0.U(31.W), vector.vxsat),
        0x00a -> vector.vxrm.pad(32),
        0x00f -> Cat(vector.vxrm, vector.vxsat).pad(32),
        0xc20 -> vector.vl,
        0xc21 -> vector.vtype,
        0xc22 -> 32.U(32.W),
        CSRAddress.cycle -> cycle(31, 0),
        CSRAddress.cycleh -> cycle(63, 32),
        CSRAddress.time -> cycle(31, 0),
        CSRAddress.timeh -> cycle(63, 32),
        CSRAddress.instret -> instret(31, 0),
        CSRAddress.instreth -> instret(63, 32),
    )
    val hit = VecInit(entries.map { case (address, _) => request.addr === address.U })
    val old = Mux1H(hit, entries.map(_._2))
    val legal = hit.asUInt.orR && !(writes && request.addr(11, 10).andR)
    val next = Mux(request.rw, operand,
        (old & Mux(request.rc, ~operand, "hffffffff".U)) | Mux(request.rs, operand, 0.U))
    val write = io.req.valid && io.commit && writes && legal
    io.rsp.valid := io.req.valid
    io.rsp.bits.illegal := !legal
    io.rsp.bits.read := reads && legal && io.req.valid
    io.rsp.bits.write := writes && legal && io.req.valid
    io.rsp.bits.data := Mux(io.rsp.bits.read, old, 0.U)

    when(write) {
        switch(request.addr) {
            is(CSRAddress.fflags.U) { flags := next(4, 0) }
            is(CSRAddress.frm.U) { rounding := next(2, 0) }
            is(CSRAddress.fcsr.U) { flags := next(4, 0); rounding := next(7, 5) }
            is("h008".U) { vector.vstart := next(7, 0) }
            is("h009".U) { vector.vxsat := next(0) }
            is("h00a".U) { vector.vxrm := next(1, 0) }
            is("h00f".U) { vector.vxsat := next(0); vector.vxrm := next(2, 1) }
        }
    }
    val vectorRetire = io.vector.get.retire
    val retiredFlags = Mux(io.fp.valid, io.fp.bits.flags, 0.U) |
        Mux(vectorRetire.valid, vectorRetire.bits.fflags, 0.U)
    when(io.fp.valid || vectorRetire.valid) { flags := flags | retiredFlags }
    when(vectorRetire.valid) {
        vector.vstart := 0.U
        vector.vxsat := vector.vxsat || vectorRetire.bits.saturated
        when(vectorRetire.bits.configuration) {
            vector.vl := vectorRetire.bits.vl
            vector.vtype := vectorRetire.bits.vtype
        }
    }
    io.vector.get.state := vector
    io.state := 0.U.asTypeOf(new CSRState)
    io.state.frm := rounding
    io.state.fflags := flags
    assert(!io.commit || io.req.valid)
    assert(!(write && (io.fp.valid || vectorRetire.valid)),
        "CSR updates and arithmetic retirement are ordered")
}
