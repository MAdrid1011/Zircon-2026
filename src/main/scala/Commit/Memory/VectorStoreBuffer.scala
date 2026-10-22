import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

class VectorStoreDrain extends Bundle {
    val start = Decoupled(Bool())
    val done = Input(Bool())
}

/** Wide payload side buffer. The original SQ/SB token owns program order;
  * this buffer survives retirement and is released only after its last write.
  */
class VectorStoreBuffer(val p: BackendParams = BackendParams(), observe: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val collect = Flipped(Decoupled(new VectorStoreTask(p)))
        val commit = Input(Vec(3, Valid(UInt(p.sqWidth.W))))
        val drain = Flipped(new VectorStoreDrain)
        val cache = new StoreBufferCacheIO
        val flush = Input(Bool())
        val empty = Output(Bool())
        val active = Output(Bool())
        val observation = if (observe) Some(Output(new VectorStoreTrace)) else None
    })
    val payload = Reg(Vec(8, new VectorStoreTask(p)))
    val nextLineAddress = Reg(Vec(8, UInt(26.W)))
    val adjacentPair = RegInit(VecInit.fill(8)(false.B))
    val contiguousMasks = Reg(Vec(8, UInt(128.W)))
    val elementAddresses = Mem(256, UInt(32.W))
    val scattered = Reg(Bool())
    val eew = Reg(UInt(2.W))
    val fieldRegisterShift = Reg(UInt(2.W))
    val lastField = Reg(UInt(3.W))
    val position = RegInit(0.U(5.W))
    val elementAddress = Reg(UInt(32.W))
    val nextElementLine = Reg(UInt(26.W))
    val valid = RegInit(false.B)
    val committed = RegInit(false.B)
    val filled = RegInit(false.B)
    val identity = Reg(UInt(p.sqWidth.W))
    val count = Reg(UInt(4.W))
    val nextCollect = Reg(UInt(3.W))
    val group = Reg(UInt(3.W))
    val groupOH = RegInit(1.U(8.W))
    val second = RegInit(false.B)
    val idle :: readElementAddress :: prepare :: response :: done :: Nil = Enum(5)
    val state = RegInit(idle)
    val active = RegInit(false.B)
    val commitHit = io.commit.map(event => event.valid && event.bits === identity).reduce(_ || _)
    io.collect.ready := !io.flush && (!valid || !filled && io.collect.bits.sqIdx === identity)
    io.drain.start.ready := valid && committed && filled && state === idle
    io.drain.done := state === done
    io.empty := !valid
    io.active := active
    io.observation.foreach { trace =>
        trace.count := count
        trace.addresses := VecInit(payload.map(_.address)).asUInt
        trace.data := VecInit(payload.map(_.data)).asUInt
        trace.masks := VecInit(payload.map(_.mask)).asUInt
        trace.scattered := scattered
        trace.eew := eew
        trace.fieldRegisterShift := fieldRegisterShift
        trace.lastField := lastField
        for (group <- 0 until 8) {
            trace.elementAddresses(group) := VecInit((0 until 32).map(index =>
                elementAddresses(group * 32 + index))).asUInt
        }
    }
    io.cache.response.ready := state === response
    val item = Mux1H(groupOH, payload)
    val address = Mux(scattered, elementAddress, item.address)
    // Two adjacent register groups already occupy one cache line. Reuse the
    // collected payload to issue their combined data and byte mask together.
    val nextItem = Mux1H(Cat(groupOH(6,0),groupOH(7)),payload)
    val pair = !scattered && !second && (groupOH & adjacentPair.asUInt).orR
    val step = Mux(pair,2.U(2.W),1.U(2.W))
    val elementBytes = (1.U(3.W) << eew)(2, 0)
    val dataMask = Mux(eew === 0.U, 255.U(32.W), Mux(eew === 1.U, 65535.U(32.W), "hffffffff".U))
    val narrowData = (item.data >> (position << 3))(31, 0) & dataMask
    val narrowMask = (item.mask >> position)(3, 0) & Mux(eew === 0.U, 1.U, Mux(eew === 1.U, 3.U, 15.U))
    val shiftedData = (Mux(scattered, narrowData.pad(256), item.data).pad(1024) << (address(5, 0) << 3))(1023, 0)
    val scatteredMask = (narrowMask.pad(128) << elementAddress(5, 0))(127, 0)
    val shiftedMask = Mux(scattered, scatteredMask, Mux1H(groupOH, contiguousMasks))
    val lineMask = Mux(pair,Cat(nextItem.mask,item.mask),Mux(second, shiftedMask(127, 64), shiftedMask(63, 0)))
    val hasSecond = shiftedMask(127, 64).orR && !second
    val last = group +& step === count
    val fieldStride = (1.U(4.W) << fieldRegisterShift)(3, 0)
    val withinField = group & Cat(fieldRegisterShift === 3.U,
        fieldRegisterShift >= 2.U, fieldRegisterShift >= 1.U)
    val currentField = group >> fieldRegisterShift
    def advance(): Unit = {
        when(hasSecond) {
            second := true.B
            state := prepare
        }.otherwise {
            second := false.B
            when(scattered) {
                // Segment payload is stored by field, but outward writes visit
                // all fields of one element before moving to the next element.
                // This preserves ordered indexed stores even when addresses alias.
                val lastPosition = position +& elementBytes === 32.U
                val finalField = currentField === lastField
                val nextGroup = Mux(!finalField, group + fieldStride,
                    Mux(lastPosition, withinField + 1.U, withinField))(2, 0)
                group := nextGroup
                groupOH := UIntToOH(nextGroup, 8)
                when(finalField) {
                    position := Mux(lastPosition, 0.U, position + elementBytes)
                }
                state := readElementAddress
                when(finalField && lastPosition && withinField +& 1.U === fieldStride) {
                    state := done
                    active := false.B
                }
            }.otherwise {
                position := 0.U
                group := group + step
                groupOH := Mux(pair,Cat(groupOH(5,0),groupOH(7,6)),Cat(groupOH(6,0),groupOH(7)))
                state := Mux(last, done, Mux(scattered, readElementAddress, prepare))
                when(last) { active := false.B }
            }
        }
    }
    when(io.collect.fire) {
        when(!valid) {
            assert(io.collect.bits.index === 0.U)
            identity := io.collect.bits.sqIdx
            valid := true.B
            committed := false.B
            scattered := io.collect.bits.scattered
            eew := io.collect.bits.eew
            fieldRegisterShift := io.collect.bits.fieldRegisterShift
            lastField := io.collect.bits.lastField
            adjacentPair.foreach(_ := false.B)
        }.otherwise {
            assert(io.collect.bits.index === nextCollect)
            assert(!committed)
        }
        when(io.collect.bits.addressOnly) {
            elementAddresses(Cat(io.collect.bits.index, io.collect.bits.element)) := io.collect.bits.address
            nextCollect := io.collect.bits.index
        }.otherwise {
            payload(io.collect.bits.index) := io.collect.bits
            contiguousMasks(io.collect.bits.index) :=
                (io.collect.bits.mask.pad(128) << io.collect.bits.address(5, 0))(127, 0)
            nextLineAddress(io.collect.bits.index) := BLevelPAdder32.sum(
                Cat(io.collect.bits.address(31, 6), 0.U(6.W)), 64.U(32.W), 0.U(1.W))(31, 6)
            // A successor is known while its payload arrives. No address
            // comparison or increment remains after the drain group mux.
            for (previous <- 0 until 7) {
                when(io.collect.bits.index === (previous + 1).U) {
                    val aligned = !payload(previous).address(5, 0).orR
                    val adjacent = io.collect.bits.address ===
                        Cat(payload(previous).address(31, 6), 32.U(6.W))
                    adjacentPair(previous) := aligned && adjacent
                }
            }
            nextCollect := io.collect.bits.index + 1.U
        }
        when(io.collect.bits.last) {
            count := io.collect.bits.index +& 1.U
            filled := true.B
        }
    }
    when(valid && commitHit) {
        assert(filled)
        committed := true.B
    }
    when(io.drain.start.fire) {
        active := true.B
        group := 0.U
        groupOH := 1.U
        position := 0.U
        second := false.B
        state := Mux(scattered, readElementAddress, prepare)
    }
    when(state === readElementAddress) {
        val selectedAddress = elementAddresses(Cat(group, position))
        elementAddress := selectedAddress
        // Prepare both line addresses at the existing element-read boundary.
        // The second-fragment selector must not precede a cache-address adder.
        nextElementLine := selectedAddress(31, 6) + 1.U
        state := prepare
    }
    io.cache.request.bits := 0.U.asTypeOf(new DStoreRequest)
    io.cache.request.bits.wide := true.B
    val contiguousLine = Mux(second, Mux1H(groupOH, nextLineAddress), item.address(31, 6))
    val scatteredAddress = Cat(Mux(second, nextElementLine, elementAddress(31, 6)), 0.U(6.W))
    io.cache.request.bits.paddr := Mux(scattered, scatteredAddress,
        Cat(contiguousLine, 0.U(6.W))).pad(34)
    io.cache.request.bits.line := Mux(pair,Cat(nextItem.data,item.data),Mux(second, shiftedData(1023, 512), shiftedData(511, 0)))
    io.cache.request.bits.lineMask := lineMask
    io.cache.request.bits.size := 2.U
    io.cache.request.valid := state === prepare && lineMask.orR
    when(state === prepare && !lineMask.orR) { advance() }
    when(io.cache.request.fire) { state := response }
    when(io.cache.response.fire) {
        assert(io.cache.response.bits.exception === 0.U)
        advance()
    }
    when(state === done) {
        valid := false.B
        committed := false.B
        filled := false.B
        state := idle
    }
    // Retired stores retain ownership across recovery, including a retirement
    // on the recovery edge. No cache request exists before SB accepts the token.
    when(io.flush && valid && !committed && !commitHit) {
        assert(state === idle)
        valid := false.B
        filled := false.B
    }
    when(state =/= idle) {
        assert(valid && committed && filled)
        assert(groupOH === UIntToOH(group, 8))
    }
    // The done cycle returns the token to SB, which may immediately offer its
    // next scalar request. Payload ownership has already ended at that edge.
    assert(active === (state =/= idle && state =/= done))
}
