import chisel3._
import chisel3.util._
import ZirconConfig._

/** Static instruction state retained until architectural retirement. */
class ROBEntry(fp: FrontendParams, bp: BackendParams) extends Bundle {
    val ftqIdx = UInt(fp.ftqBits.W)
    val slot = UInt(fp.slotBits.W)
    val packetEnd = Bool()
    val robIdx = UInt(bp.robWidth.W)
    val destination = new MiddleendCommitDestination(bp)
    val sqIdx = UInt(bp.sqWidth.W)
    val isStore = Bool()
    val isAtomic = Bool()
    val isSystem = Bool()
    val systemOp = UInt(5.W)
    val instruction = UInt(32.W)
    val writesExecutionState = Bool()
    val writesSatp = Bool()
    val fpDirty = Bool()

    val complete = Bool()
    val mispredicted = Bool()
    val exception = new BackendException
    val fflags = UInt(5.W)
    val fpFlagsValid = Bool()

    def enqueue(data: Data): Unit = {
        val incoming = data.asInstanceOf[ROBEntry]
        ftqIdx := incoming.ftqIdx
        slot := incoming.slot
        packetEnd := incoming.packetEnd
        robIdx := incoming.robIdx
        destination := incoming.destination
        sqIdx := incoming.sqIdx
        isStore := incoming.isStore
        isAtomic := incoming.isAtomic
        isSystem := incoming.isSystem
        systemOp := incoming.systemOp
        instruction := incoming.instruction
        writesExecutionState := incoming.writesExecutionState
        writesSatp := incoming.writesSatp
        fpDirty := incoming.fpDirty
        complete := incoming.complete
        mispredicted := false.B
        exception := incoming.exception
        fflags := 0.U
        fpFlagsValid := false.B
    }

    def write(data: Data): Unit = {
        val incoming = data.asInstanceOf[ROBEntry]
        when(incoming.complete) { complete := true.B }
        mispredicted := incoming.mispredicted
        exception := incoming.exception
        fflags := incoming.fflags
        fpFlagsValid := incoming.fpFlagsValid
    }
}

/** Completion payload after Commit has reduced the ROB identity to a physical slot. */
class ROBWrite(addressWidth: Int) extends Bundle {
    val address = UInt(addressWidth.W)
    val complete = Bool()
    val data = UInt(32.W)
    val mispredicted = Bool()
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
    private val addressWidth = CommitIndex.addressWidth(cp.robEntries)

    val availablePrefix = Output(UInt(dispatchWidth.W))
    val allocation = Output(Vec(dispatchWidth, UInt(bp.robWidth.W)))
    val enqueue = Input(new MiddleendCommitEnqueue(fp, bp, dispatchWidth))

    val completion = Input(Vec(cp.completionPorts, Valid(new ROBWrite(addressWidth))))
    val head = Output(Vec(cp.width, Valid(new ROBEntry(fp, bp))))
    val pop = Input(UInt(cp.width.W))
    val clear = Input(Bool())
    val fullCycles = Output(UInt(64.W))
    val headData = if (simulationDebug) Some(Output(Vec(cp.width, UInt(32.W)))) else None
}

/** Banked ROB with parallel completion and a three-entry head window. */
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
        0,
        cp.completionPorts,
        writePayloadOnFlush = true,
        registeredDeq = false,
        exposeDeqIndex = true,
        separateEnqWrite = true,
        selectDeqAfterRegister = false,
    ))
    queue.io.enqWrite.get := io.enqueue.writeValid.asBools

    val ready = queue.io.enq(0).ready
    io.availablePrefix := Fill(dispatchWidth, ready)
    for (lane <- 0 until dispatchWidth) {
        val incoming = io.enqueue.entries(lane)
        val entry = WireDefault(0.U.asTypeOf(new ROBEntry(fp, bp)))
        entry.ftqIdx := incoming.context.ftqIdx
        entry.slot := incoming.context.slot
        entry.packetEnd := incoming.context.packetEnd
        // The physical ROB identity is reconstructed from its bank head/read index.
        entry.robIdx := 0.U
        entry.destination := incoming.destination
        entry.sqIdx := incoming.allocation.sqIdx
        entry.isStore := incoming.context.instruction.fu === DecodeUnit.Store.U
        entry.isAtomic := incoming.context.instruction.fu === DecodeUnit.Atomic.U
        entry.isSystem := incoming.context.instruction.fu === DecodeUnit.System.U
        entry.systemOp := incoming.context.instruction.op
        entry.instruction := incoming.context.instruction.inst
        val unconditionalCsrWrite = entry.systemOp === SystemOp.CSRRW.U ||
            entry.systemOp === SystemOp.CSRRWI.U
        val conditionalCsrWrite = entry.systemOp === SystemOp.CSRRS.U ||
            entry.systemOp === SystemOp.CSRRC.U || entry.systemOp === SystemOp.CSRRSI.U ||
            entry.systemOp === SystemOp.CSRRCI.U
        val csrAddress = entry.instruction(31, 20)
        val executionCsr = csrAddress === CSRAddress.fflags.U || csrAddress === CSRAddress.frm.U ||
            csrAddress === CSRAddress.fcsr.U || csrAddress === CSRAddress.sstatus.U ||
            csrAddress === CSRAddress.satp.U || csrAddress === CSRAddress.mstatus.U
        entry.writesExecutionState := entry.isSystem && executionCsr &&
            (unconditionalCsrWrite || (conditionalCsrWrite && entry.instruction(19, 15).orR))
        entry.writesSatp := entry.writesExecutionState && csrAddress === CSRAddress.satp.U
        val fpMultiplyFlags = incoming.context.instruction.fu === DecodeUnit.Multiply.U &&
            incoming.context.instruction.op >= MultiplyOp.FADD
        val fpDivideFlags = incoming.context.instruction.fu === DecodeUnit.Divide.U &&
            incoming.context.instruction.op >= DivideOp.FDIV.U
        val fpMiscFlags = incoming.context.instruction.fu === DecodeUnit.FpMisc.U &&
            ((incoming.context.instruction.op >= FpMiscOp.FMIN.U &&
                incoming.context.instruction.op <= FpMiscOp.FLE.U) ||
                (incoming.context.instruction.op >= FpMiscOp.FCVT_W_S.U &&
                    incoming.context.instruction.op <= FpMiscOp.FCVT_S_WU.U))
        entry.fpDirty := incoming.context.instruction.rinfo.dest.valid &&
            incoming.context.instruction.rinfo.dest.isFp || fpMultiplyFlags || fpDivideFlags || fpMiscFlags
        val csrSystem = entry.isSystem && entry.systemOp >= SystemOp.CSRRW.U &&
            entry.systemOp <= SystemOp.CSRRCI.U
        entry.complete := incoming.context.instruction.exception.valid || (entry.isSystem && !csrSystem)
        entry.exception.valid := incoming.context.instruction.exception.valid
        entry.exception.cause := Mux(
            incoming.context.instruction.exception.cause(4),
            15.U,
            incoming.context.instruction.exception.cause(3, 0),
        )
        entry.exception.tval := incoming.context.instruction.exception.tval
        queue.io.enq(lane).valid := io.enqueue.valid(lane)
        queue.io.enq(lane).bits := entry
        io.allocation(lane) := CommitIndex.encode(
            queue.io.enqIdx(lane),
            cp.robEntries,
            banks,
            bp.robWidth,
            simulationDebug,
        )
    }
    when(!io.clear) {
        assert((io.enqueue.valid & ~io.availablePrefix) === 0.U, "ROB enqueue exceeded available capacity")
        assert((io.enqueue.valid & ~io.enqueue.writeValid) === 0.U)
    }

    for (port <- 0 until cp.completionPorts) {
        val completion = io.completion(port)
        val update = WireDefault(0.U.asTypeOf(new ROBEntry(fp, bp)))
        update.complete := completion.bits.complete
        update.mispredicted := completion.bits.mispredicted
        update.exception := completion.bits.exception
        update.fflags := completion.bits.fflags
        update.fpFlagsValid := completion.bits.fpFlagsValid
        queue.io.wen(port) := completion.valid
        queue.io.widx(port) := CommitIndex.decodeAddress(completion.bits.address, cp.robEntries, banks)
        queue.io.wdata(port) := update
    }

    for (lane <- 0 until cp.width) {
        io.head(lane).valid := queue.io.deq(lane).valid
        io.head(lane).bits := queue.io.deq(lane).bits
        io.head(lane).bits.robIdx := CommitIndex.encode(
            queue.io.deqIdx.get(lane), cp.robEntries, banks, bp.robWidth, simulationDebug,
        )
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

    /* Simulation-only result values are isolated from the architectural ROB state. */
    if (simulationDebug) {
        val bankWidth = log2Ceil(banks)
        val rowWidth = log2Ceil(cp.robEntries / banks)
        val resultMemory = Module(new AsyncRegRam(
            UInt(32.W),
            1 << (bankWidth + rowWidth),
            cp.completionPorts,
            cp.width,
        ))

        for (port <- 0 until cp.completionPorts) {
            resultMemory.io.wen(port) := io.completion(port).valid && io.completion(port).bits.complete && !io.clear
            resultMemory.io.waddr(port) := io.completion(port).bits.address
            resultMemory.io.wdata(port) := io.completion(port).bits.data
        }
        for (lane <- 0 until cp.width) {
            resultMemory.io.raddr(lane) := io.head(lane).bits.robIdx(bankWidth + rowWidth - 1, 0)
            io.headData.get(lane) := resultMemory.io.rdata(lane)
        }
    }
}
