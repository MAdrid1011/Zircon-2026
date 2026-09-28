import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FetchQueueIO(p: FrontendParams, dequeueWidth: Int) extends Bundle {
    val enq = Vec(p.fetchWidth, Flipped(Decoupled(new FetchQueueEntry(p))))
    val enqPayloadWrite = Input(Vec(p.fetchWidth, Bool()))
    val out = Vec(dequeueWidth, Decoupled(new FetchQueueEntry(p)))
    val flush = Input(Bool())
}

/** Per-instruction storage; FTQ training state is stored once per fetch packet. */
class FetchQueuePayload(p: FrontendParams) extends Bundle {
    val slot = UInt(p.slotBits.W)
    val packetStart = Bool()
    val packetEnd = Bool()
    val instruction = new FrontendInstruction(p)
}

/** Four-wide instruction input and ordered multi-instruction output. */
class FetchQueue(p: FrontendParams, dequeueWidth: Int) extends Module {
    require(dequeueWidth > 0 && dequeueWidth <= p.fetchWidth)

    val io = IO(new FetchQueueIO(p, dequeueWidth))

    val queue = Module(new ClusterIndexFIFO(
        new FetchQueuePayload(p),
        p.fqDepth,
        p.fetchWidth,
        dequeueWidth,
        0,
        0,
        compactEnq = true,
        writePayloadOnFlush = true,
        registeredDeq = false,
        separateEnqWrite = true,
        selectDeqAfterRegister = false,
        resetPayload = false,
    ))
    // A record is consumed only for an entry which starts a fetch packet.  Do
    // not replicate this wide predictor-training payload in every compacted
    // instruction entry.
    val recordDepth = ((p.fqDepth + dequeueWidth - 1) / dequeueWidth) * dequeueWidth
    val records = Module(new ClusterIndexFIFO(
        new FrontendFtqRecord(p),
        recordDepth,
        1,
        dequeueWidth,
        0,
        0,
        writePayloadOnFlush = true,
        resetPayload = false,
    ))
    val recordInputValid = io.enq.map(_.valid).reduce(_ || _)
    val previousPacketPcWord = Reg(UInt(30.W))
    val previousPacketNextPc = Reg(UInt(32.W))
    for (lane <- 0 until p.fetchWidth) {
        queue.io.enq(lane).valid := io.enq(lane).valid && records.io.enq(0).ready
        queue.io.enq(lane).bits.slot := io.enq(lane).bits.slot
        queue.io.enq(lane).bits.packetStart := io.enq(lane).bits.packetStart
        queue.io.enq(lane).bits.packetEnd := io.enq(lane).bits.packetEnd
        queue.io.enq(lane).bits.instruction := io.enq(lane).bits.instruction
        // The packet record and slot already determine every instruction PC.
        queue.io.enq(lane).bits.instruction.pc := 0.U
        queue.io.enq(lane).bits.instruction.predictedValue := 0.U
        // Register indices are fixed instruction slices; retain only operand flags.
        for (source <- 0 until 3) {
            queue.io.enq(lane).bits.instruction.rinfo.src(source).index := 0.U
        }
        queue.io.enq(lane).bits.instruction.rinfo.dest.index := 0.U
        // A free instruction slot may be prewritten even while the record
        // queue stalls occupancy; the write remains invisible until accepted.
        queue.io.enqWrite.get(lane) := io.enqPayloadWrite(lane)
        io.enq(lane).ready := queue.io.enq(lane).ready && records.io.enq(0).ready
    }
    records.io.enq(0).valid := recordInputValid && queue.io.enq(0).ready
    records.io.enq(0).bits := io.enq(0).bits.record

    var packetPcWord = previousPacketPcWord
    var packetNextPc = previousPacketNextPc
    val dequeueRecords = Wire(Vec(dequeueWidth, new FrontendFtqRecord(p)))
    val consumedStarts = Wire(Vec(dequeueWidth, Bool()))
    for (lane <- 0 until dequeueWidth) {
        val priorPacketStarts = PopCount((0 until lane).map { prior =>
            queue.io.deq(prior).valid && queue.io.deq(prior).bits.packetStart
        })
        val recordSelect = VecInit((0 until dequeueWidth).map(index => priorPacketStarts === index.U))
        val record = Mux1H(recordSelect, records.io.deq.map(_.bits))
        dequeueRecords(lane) := record
        val packetStart = queue.io.deq(lane).valid && queue.io.deq(lane).bits.packetStart
        consumedStarts(lane) := queue.io.deq(lane).fire && packetStart
        packetPcWord = Mux(packetStart, record.train.pcWord, packetPcWord)
        packetNextPc = Mux(packetStart, record.nextPc, packetNextPc)
        io.out(lane).valid := queue.io.deq(lane).valid
        io.out(lane).bits.slot := queue.io.deq(lane).bits.slot
        io.out(lane).bits.packetStart := queue.io.deq(lane).bits.packetStart
        io.out(lane).bits.packetEnd := queue.io.deq(lane).bits.packetEnd
        io.out(lane).bits.instruction := queue.io.deq(lane).bits.instruction
        val inst = queue.io.deq(lane).bits.instruction.inst
        io.out(lane).bits.instruction.rinfo.src(0).index := inst(19, 15)
        io.out(lane).bits.instruction.rinfo.src(1).index := inst(24, 20)
        io.out(lane).bits.instruction.rinfo.src(2).index := inst(31, 27)
        io.out(lane).bits.instruction.rinfo.dest.index := inst(11, 7)
        val kind = queue.io.deq(lane).bits.instruction.kind
        val branchOffset = Cat(Fill(19, inst(31)), inst(31), inst(7),
            inst(30, 25), inst(11, 8), 0.U(1.W))
        val jumpOffset = Cat(Fill(11, inst(31)), inst(31), inst(19, 12),
            inst(20), inst(30, 21), 0.U(1.W))
        val directOffset = Mux(kind === FrontendCfi.Conditional.U, branchOffset, jumpOffset)
        io.out(lane).bits.instruction.predictedValue := Mux(
            FrontendCfi.indirect(kind), packetNextPc,
            Mux(queue.io.deq(lane).bits.instruction.predictedTaken, directOffset, 4.U),
        )
        io.out(lane).bits.instruction.pc :=
            FrontendMath.blockBase(Cat(packetPcWord, 0.U(2.W)), p) |
                (queue.io.deq(lane).bits.slot << 2)
        io.out(lane).bits.record := record
        queue.io.deq(lane).ready := io.out(lane).ready
        when(queue.io.deq(lane).valid && queue.io.deq(lane).bits.packetStart) {
            assert(Mux1H(recordSelect, records.io.deq.map(_.valid)),
                "FetchQueue packet start must have an FTQ record")
        }
    }
    val newestConsumedStart = VecInit((0 until dequeueWidth).map { lane =>
        consumedStarts(lane) && !consumedStarts.drop(lane + 1).reduceOption(_ || _).getOrElse(false.B)
    })
    when(consumedStarts.asUInt.orR) {
        previousPacketPcWord := Mux1H(newestConsumedStart, dequeueRecords.map(_.train.pcWord))
        previousPacketNextPc := Mux1H(newestConsumedStart, dequeueRecords.map(_.nextPc))
    }
    for (record <- 0 until dequeueWidth) {
        records.io.deq(record).ready := VecInit((0 until dequeueWidth).map { lane =>
            val priorPacketStarts = PopCount((0 until lane).map { prior =>
                queue.io.deq(prior).valid && queue.io.deq(prior).bits.packetStart
            })
            queue.io.deq(lane).fire && queue.io.deq(lane).bits.packetStart &&
                priorPacketStarts === record.U
        }).asUInt.orR
    }
    queue.io.flush := io.flush
    records.io.flush := io.flush
}
