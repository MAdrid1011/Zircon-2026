import chisel3._
import chisel3.util._

/**
  * Posted queue for aligned word stores in a PMA write-combine window.
  *
  * The queue owns one open, in-order burst.  It only appends the same 64-bit
  * beat or the immediately following beat, never crosses a 4 KiB boundary,
  * and closes after eight beats or a short age timeout.  Device stores are
  * deliberately excluded by the producer-side writeCombine bit.
  */
class WriteCombineQueue(depth: Int = 8) extends Module {
    require(depth == 8, "WriteCombineQueue currently uses an eight-beat AXI payload")
    val io = IO(new Bundle {
        val enq = Flipped(Decoupled(new DStoreRequest))
        val out = Decoupled(new DMemoryRequest)
        val rsp = Flipped(Decoupled(new DMemoryResponse))
        val seal = Input(Bool())
        val empty = Output(Bool())
        val busy = Output(Bool())
    })

    val valid = RegInit(false.B)
    val inflight = RegInit(false.B)
    val sealedBurst = RegInit(false.B)
    val base = Reg(UInt(34.W))
    val lastBeatAddress = Reg(UInt(34.W))
    val nextBeatAddress = Reg(UInt(34.W))
    val count = RegInit(0.U(4.W))
    val age = RegInit(0.U(3.W))
    val data = RegInit(VecInit.fill(depth)(0.U(64.W)))
    val mask = RegInit(VecInit.fill(depth)(0.U(8.W)))
    // The current and next beat positions stay one-hot. They drive the wide
    // payload write enables directly, keeping binary count decode off every
    // data and strobe register input.
    val lastBeatOH = RegInit(0.U(depth.W))
    val nextBeatOH = RegInit(1.U(depth.W))

    val incomingBeat = Cat(io.enq.bits.paddr(33, 3), 0.U(3.W))
    val sameBeat = valid && incomingBeat === lastBeatAddress
    val nextBeat = valid && incomingBeat === nextBeatAddress
    val samePage = !valid || incomingBeat(33, 12) === base(33, 12)
    val contiguous = samePage && (sameBeat || nextBeat)
    val canAppend = !sealedBurst && (!valid || (count < depth.U && contiguous))
    val ready = canAppend && !io.out.valid && !inflight
    io.enq.ready := ready

    val flushAge = valid && !sealedBurst && age === 7.U
    io.out.valid := valid && !inflight && (sealedBurst || count === depth.U || flushAge)
    io.out.bits := 0.U.asTypeOf(new DMemoryRequest)
    io.out.bits.paddr := base
    io.out.bits.write := true.B
    io.out.bits.uncache := true.B
    io.out.bits.writeCombine := true.B
    io.out.bits.size := 2.U
    io.out.bits.data := data.asUInt
    io.out.bits.mask := 0.U
    io.out.bits.burstBeats := count
    io.out.bits.burstMask := mask.asUInt
    io.out.bits.victimValid := false.B
    io.out.bits.victimLine := 0.U
    io.out.bits.victimData := 0.U
    io.out.bits.victimDirty := false.B
    io.out.bits.victimOnly := false.B
    io.rsp.ready := inflight
    io.empty := !valid && !inflight
    io.busy := valid || inflight

    when(io.out.fire) {
        inflight := true.B
    }.elsewhen(io.enq.fire) {
        val incomingData = Mux(io.enq.bits.paddr(2), Cat(io.enq.bits.data, 0.U(32.W)),
            Cat(0.U(32.W), io.enq.bits.data))
        val incomingMask = Mux(io.enq.bits.paddr(2), Cat(io.enq.bits.mask, 0.U(4.W)),
            Cat(0.U(4.W), io.enq.bits.mask))
        when(!valid) {
            valid := true.B
            sealedBurst := false.B
            base := incomingBeat
            lastBeatAddress := incomingBeat
            nextBeatAddress := incomingBeat + 8.U
            count := 1.U
            age := 0.U
            lastBeatOH := 1.U
            nextBeatOH := 2.U
            data(0) := incomingData
            mask(0) := incomingMask
        }.elsewhen(sameBeat) {
            for (index <- 0 until depth) {
                when(lastBeatOH(index)) {
                    data(index) := VecInit.tabulate(8) { byte =>
                        Mux(
                            incomingMask(byte),
                            incomingData(8 * byte + 7, 8 * byte),
                            data(index)(8 * byte + 7, 8 * byte),
                        )
                    }.asUInt
                    mask(index) := mask(index) | incomingMask
                }
            }
            age := 0.U
        }.otherwise {
            for (index <- 0 until depth) {
                when(nextBeatOH(index)) {
                    data(index) := incomingData
                    mask(index) := incomingMask
                }
            }
            lastBeatAddress := nextBeatAddress
            nextBeatAddress := nextBeatAddress + 8.U
            lastBeatOH := nextBeatOH
            nextBeatOH := (nextBeatOH << 1)(depth - 1, 0)
            count := count + 1.U
            age := 0.U
        }
    }.elsewhen(valid && !sealedBurst) {
        age := age + 1.U
    }

    when(io.rsp.fire) {
        valid := false.B
        inflight := false.B
        sealedBurst := false.B
        count := 0.U
        age := 0.U
        lastBeatOH := 0.U
        nextBeatOH := 1.U
        data := VecInit.fill(depth)(0.U)
        mask := VecInit.fill(depth)(0.U)
    }

    // A non-contiguous next store closes the current burst; it remains held
    // by the upstream Decoupled producer until the burst has drained.
    when(valid && !sealedBurst && io.enq.valid && !contiguous) {
        sealedBurst := true.B
    }
    when(valid && !inflight && io.seal) {
        sealedBurst := true.B
    }
}
