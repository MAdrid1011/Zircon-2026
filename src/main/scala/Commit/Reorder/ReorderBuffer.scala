import chisel3._
import chisel3.util._
import ZirconConfig._

/** Static instruction state retained until architectural retirement. */
class ROBEntry(fp: FrontendParams, bp: BackendParams) extends Bundle {
    val fetchToken = UInt(32.W)
    val ftqIdx = UInt(fp.ftqBits.W)
    val slot = UInt(fp.slotBits.W)
    val packetEnd = Bool()
    val pc = UInt(32.W)
    val robIdx = UInt(bp.robWidth.W)
    val destination = new MiddleendCommitDestination(bp)
    val sqIdx = UInt(bp.sqWidth.W)
    val isStore = Bool()
    val isAtomic = Bool()
    val isSystem = Bool()
    val systemOp = UInt(5.W)
    val instruction = UInt(32.W)
    val fpDirty = Bool()

    val complete = Bool()
    val exception = new BackendException
    val fflags = UInt(5.W)
    val fpFlagsValid = Bool()

    def enqueue(data: Data): Unit = {
        val incoming = data.asInstanceOf[ROBEntry]
        fetchToken := incoming.fetchToken
        ftqIdx := incoming.ftqIdx
        slot := incoming.slot
        packetEnd := incoming.packetEnd
        pc := incoming.pc
        robIdx := incoming.robIdx
        destination := incoming.destination
        sqIdx := incoming.sqIdx
        isStore := incoming.isStore
        isAtomic := incoming.isAtomic
        isSystem := incoming.isSystem
        systemOp := incoming.systemOp
        instruction := incoming.instruction
        fpDirty := incoming.fpDirty
        complete := incoming.complete
        exception := incoming.exception
        fflags := 0.U
        fpFlagsValid := false.B
    }

    def write(data: Data): Unit = {
        val incoming = data.asInstanceOf[ROBEntry]
        complete := incoming.complete
        exception := incoming.exception
        fflags := incoming.fflags
        fpFlagsValid := incoming.fpFlagsValid
    }
}

class ROBCompletion(bp: BackendParams) extends Bundle {
    val robIdx = UInt(bp.robWidth.W)
    val data = UInt(32.W)
    val exception = new BackendException
    val fflags = UInt(5.W)
    val fpFlagsValid = Bool()
}

class ReorderBufferIO(
    fp: FrontendParams,
    bp: BackendParams,
    dispatchWidth: Int,
    cp: CommitParams,
    simulationDebug: Boolean,
) extends Bundle {
    val request = Input(new MiddleendCommitRequest(fp, dispatchWidth))
    val availablePrefix = Output(UInt(dispatchWidth.W))
    val allocation = Output(Vec(dispatchWidth, UInt(bp.robWidth.W)))
    val enqueue = Input(new MiddleendCommitEnqueue(fp, bp, dispatchWidth))

    val completion = Input(Vec(cp.completionPorts, Valid(new ROBCompletion(bp))))
    val readIdx = Input(Vec(cp.robReadPorts, UInt(bp.robWidth.W)))
    val readPc = Output(Vec(cp.robReadPorts, UInt(32.W)))
    val readEntry = Output(Vec(cp.robReadPorts, new ROBEntry(fp, bp)))

    val head = Output(Vec(cp.width, Valid(new ROBEntry(fp, bp))))
    val headIdx = Output(Vec(cp.width, UInt(bp.robWidth.W)))
    val headData = if (simulationDebug) Some(Output(Vec(cp.width, UInt(32.W)))) else None
    val pop = Input(UInt(cp.width.W))
    val clear = Input(Bool())
    val fullCycles = Output(UInt(64.W))
}

/** 48-entry banked ROB with parallel completion and a three-entry head window. */
class ReorderBuffer(
    val fp: FrontendParams = FrontendParams(),
    val bp: BackendParams = BackendParams(),
    val dispatchWidth: Int = IssueParams().dispatchWidth,
    val cp: CommitParams = CommitParams(),
    val simulationDebug: Boolean = false,
) extends Module {
    private val banks = math.max(dispatchWidth, cp.width)
    require(cp.robEntries % banks == 0)
    require(bp.robWidth >= CommitIndex.compactWidth(cp.robEntries, banks))

    val io = IO(new ReorderBufferIO(fp, bp, dispatchWidth, cp, simulationDebug))
    val queue = Module(new ClusterIndexFIFO(
        new ROBEntry(fp, bp),
        cp.robEntries,
        dispatchWidth,
        cp.width,
        cp.robReadPorts,
        cp.completionPorts,
    ))

    val ready = queue.io.enq(0).ready && !io.clear
    io.availablePrefix := Fill(dispatchWidth, ready)
    for (lane <- 0 until dispatchWidth) {
        val incoming = io.enqueue.entries(lane)
        val entry = WireDefault(0.U.asTypeOf(new ROBEntry(fp, bp)))
        entry.fetchToken := incoming.context.fetchToken
        entry.ftqIdx := incoming.context.ftqIdx
        entry.slot := incoming.context.slot
        entry.packetEnd := incoming.context.packetEnd
        entry.pc := incoming.context.instruction.pc
        entry.robIdx := incoming.allocation.robIdx
        entry.destination := incoming.destination
        entry.sqIdx := incoming.allocation.sqIdx
        entry.isStore := incoming.context.instruction.fu === DecodeUnit.Store.U
        entry.isAtomic := incoming.context.instruction.fu === DecodeUnit.Atomic.U
        entry.isSystem := incoming.context.instruction.fu === DecodeUnit.System.U
        entry.systemOp := incoming.context.instruction.op
        entry.instruction := incoming.context.instruction.inst
        entry.fpDirty := incoming.context.instruction.rinfo.dest.valid &&
            incoming.context.instruction.rinfo.dest.isFp ||
            incoming.context.instruction.rinfo.src.map(source => source.valid && source.isFp).reduce(_ || _)
        val csrSystem = entry.isSystem && entry.systemOp >= SystemOp.CSRRW.U &&
            entry.systemOp <= SystemOp.CSRRCI.U
        entry.complete := incoming.context.instruction.exception.valid || (entry.isSystem && !csrSystem)
        entry.exception.valid := incoming.context.instruction.exception.valid
        entry.exception.cause := incoming.context.instruction.exception.cause
        entry.exception.tval := incoming.context.instruction.exception.tval
        queue.io.enq(lane).valid := io.enqueue.valid(lane) && !io.clear
        queue.io.enq(lane).bits := entry
        io.allocation(lane) := CommitIndex.encode(queue.io.enqIdx(lane), cp.robEntries, banks, bp.robWidth)
    }
    when(!io.clear) {
        assert((io.enqueue.valid & ~io.request.valid) === 0.U, "ROB enqueue must have a matching resource request")
        assert((io.enqueue.valid & ~io.availablePrefix) === 0.U, "ROB enqueue exceeded available capacity")
    }

    for (port <- 0 until cp.completionPorts) {
        val completion = io.completion(port)
        val update = WireDefault(0.U.asTypeOf(new ROBEntry(fp, bp)))
        update.complete := true.B
        update.exception := completion.bits.exception
        update.fflags := completion.bits.fflags
        update.fpFlagsValid := completion.bits.fpFlagsValid
        queue.io.wen(port) := completion.valid && !io.clear
        queue.io.widx(port) := CommitIndex.decode(completion.bits.robIdx, cp.robEntries, banks)
        queue.io.wdata(port) := update
    }

    if (simulationDebug) {
        val bankWidth = log2Ceil(banks)
        val rowWidth = log2Ceil(cp.robEntries / banks)
        val resultMemory = Mem(1 << (bankWidth + rowWidth), UInt(32.W))
        def resultIndex(index: UInt): UInt = index(bankWidth + rowWidth - 1, 0)

        for (port <- 0 until cp.completionPorts) {
            when(io.completion(port).valid && !io.clear) {
                resultMemory(resultIndex(io.completion(port).bits.robIdx)) := io.completion(port).bits.data
            }
        }
        for (lane <- 0 until cp.width) {
            io.headData.get(lane) := resultMemory(resultIndex(io.headIdx(lane)))
        }
    }

    for (port <- 0 until cp.robReadPorts) {
        queue.io.ridx(port) := CommitIndex.decode(io.readIdx(port), cp.robEntries, banks)
        io.readPc(port) := queue.io.rdata(port).pc
        io.readEntry(port) := queue.io.rdata(port)
    }

    for (lane <- 0 until cp.width) {
        io.head(lane).valid := queue.io.deq(lane).valid
        io.head(lane).bits := queue.io.deq(lane).bits
        io.headIdx(lane) := CommitIndex.encode(queue.io.deqIdx(lane), cp.robEntries, banks, bp.robWidth)
        queue.io.deq(lane).ready := io.pop(lane)
        when(io.pop(lane) && !io.clear) {
            assert(queue.io.deq(lane).valid && queue.io.deq(lane).bits.complete)
        }
    }
    when(!io.clear) {
        FIFOUtil.assertPrefix(io.pop.asBools, "ROB retirement must be an ordered prefix")
    }
    queue.io.flush := io.clear

    val fullCycles = RegInit(0.U(64.W))
    when(!ready) { fullCycles := fullCycles + 1.U }
    io.fullCycles := fullCycles
}
