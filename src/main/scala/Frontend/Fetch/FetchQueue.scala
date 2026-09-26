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
        // The bank selector otherwise reaches every Decode/Rename payload bit.
        // Keep the existing prefix dequeue contract, but cross the FQ-to-Rename
        // boundary through the FIFO's elastic registered output.
        registeredDeq = true,
        separateEnqWrite = true,
        selectDeqAfterRegister = true,
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
    ))
    val recordInputValid = io.enq.map(_.valid).reduce(_ || _)
    for (lane <- 0 until p.fetchWidth) {
        queue.io.enq(lane).valid := io.enq(lane).valid && records.io.enq(0).ready
        queue.io.enq(lane).bits.slot := io.enq(lane).bits.slot
        queue.io.enq(lane).bits.packetStart := io.enq(lane).bits.packetStart
        queue.io.enq(lane).bits.packetEnd := io.enq(lane).bits.packetEnd
        queue.io.enq(lane).bits.instruction := io.enq(lane).bits.instruction
        // A free instruction slot may be prewritten even while the record
        // queue stalls occupancy; the write remains invisible until accepted.
        queue.io.enqWrite.get(lane) := io.enqPayloadWrite(lane)
        io.enq(lane).ready := queue.io.enq(lane).ready && records.io.enq(0).ready
    }
    records.io.enq(0).valid := recordInputValid && queue.io.enq(0).ready
    records.io.enq(0).bits := io.enq(0).bits.record

    for (lane <- 0 until dequeueWidth) {
        val priorPacketStarts = PopCount((0 until lane).map { prior =>
            queue.io.deq(prior).valid && queue.io.deq(prior).bits.packetStart
        })
        val recordSelect = VecInit((0 until dequeueWidth).map(index => priorPacketStarts === index.U))
        io.out(lane).valid := queue.io.deq(lane).valid
        io.out(lane).bits.slot := queue.io.deq(lane).bits.slot
        io.out(lane).bits.packetStart := queue.io.deq(lane).bits.packetStart
        io.out(lane).bits.packetEnd := queue.io.deq(lane).bits.packetEnd
        io.out(lane).bits.instruction := queue.io.deq(lane).bits.instruction
        io.out(lane).bits.record := Mux1H(recordSelect, records.io.deq.map(_.bits))
        queue.io.deq(lane).ready := io.out(lane).ready
        when(queue.io.deq(lane).valid && queue.io.deq(lane).bits.packetStart) {
            assert(Mux1H(recordSelect, records.io.deq.map(_.valid)),
                "FetchQueue packet start must have an FTQ record")
        }
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
