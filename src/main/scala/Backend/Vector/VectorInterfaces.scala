import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

/** Tags and configuration only; vector data remains in the shared lane PRFs. */
class VectorInstructionMetadata extends Bundle {
    val control = new VectorDecodeControl
    val groups = new VectorRegisterGroupInfo
    val source = Vec(3, Vec(8, UInt(6.W)))
    val mask = UInt(6.W)
    val destination = new VectorDestination
    val state = new VectorCSRState
}

class VectorInstruction(p: BackendParams = BackendParams()) extends Bundle {
    val scalar = new BackendPackage(p)
    val vector = new VectorInstructionMetadata
}

class VectorMiddleendIO(p: BackendParams = BackendParams()) extends Bundle {
    val enqueue = Output(Valid(new VectorInstruction(p)))
    val availableCompute = Input(Bool())
    val availableMemory = Input(Bool())
    val allocate = Output(Valid(new VectorDestination))
    val configuration = Input(Valid(new VectorCSRState))
    val committed = Input(new VectorCSRState)
    val retire = Input(Vec(3, Valid(new VectorDestination)))
}

class VectorScalarAccess(p: BackendParams = BackendParams()) extends Bundle {
    val address = Input(UInt(p.tagWidth.W))
    val data = Output(UInt(32.W))
    val writeback = Flipped(Decoupled(new VectorScalarWrite(p)))
}

class VectorScalarWrite(p: BackendParams = BackendParams()) extends Bundle {
    val destination = UInt(p.tagWidth.W)
    val data = UInt(32.W)
}

class VectorCompletion(p: BackendParams = BackendParams()) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
    val scalar = Valid(new VectorScalarWrite(p))
    val retirement = new VectorCSRRetirement
}

class VectorTaskWriteback extends Bundle {
    val address = UInt(6.W)
    val data = Vec(8, UInt(32.W))
    val bytes = Vec(8, UInt(4.W))
    val ready = Bool()
}

class VectorCommitIO(p: BackendParams = BackendParams()) extends Bundle {
    val enqueue = Flipped(Valid(new VectorInstruction(p)))
    val completion = Flipped(Decoupled(new VectorCompletion(p)))
    val retire = Output(Vec(3, Valid(new VectorDestination)))
    val state = Output(new VectorCSRState)
    val store = Flipped(Decoupled(new VectorStoreTask(p)))
    val ordering = Flipped(new VectorLoadOrdering(p))
}

class VectorBackendIO(p: BackendParams = BackendParams()) extends Bundle {
    val enqueue = Flipped(Valid(new VectorInstruction(p)))
    val allocate = Flipped(Valid(new VectorDestination))
    val availableCompute = Output(Bool())
    val availableMemory = Output(Bool())
    val configuration = Output(Valid(new VectorCSRState))
    val completion = Decoupled(new VectorCompletion(p))
    val roundingMode = Input(UInt(3.W))
    val store = Decoupled(new VectorStoreTask(p))
    val ordering = new VectorLoadOrdering(p)
}

class VectorStoreTask(p: BackendParams = BackendParams()) extends Bundle {
    val sqIdx = UInt(p.sqWidth.W)
    val robIdx = UInt(p.robWidth.W)
    val index = UInt(3.W)
    val last = Bool()
    val address = UInt(32.W)
    val data = UInt(256.W)
    val mask = UInt(32.W)
    val scattered = Bool()
    val addressOnly = Bool()
    val element = UInt(5.W)
    val eew = UInt(2.W)
    val fieldRegisterShift = UInt(2.W)
    val lastField = UInt(3.W)
}

class VectorLoadOrdering(p: BackendParams = BackendParams()) extends Bundle {
    val tail = Output(UInt(p.sqWidth.W))
    val native = Output(Bool())
    val line = Output(UInt(26.W))
    val clear = Input(Bool())
}

class VectorRetireTrace extends Bundle {
    val base = UInt(5.W)
    val count = UInt(4.W)
    val physical = UInt(48.W)
    val result = new VectorCSRRetirement
}

class VectorCoreDebug extends Bundle {
    val performance = Vec(8,UInt(64.W))
    val registers = Vec(64, UInt(256.W))
    val retire = Vec(3, new VectorRetireTrace)
    val state = new VectorCSRState
    val store = new VectorStoreTrace
}

class VectorStoreTrace extends Bundle {
    val count = UInt(4.W)
    val addresses = UInt(256.W)
    val data = UInt(2048.W)
    val masks = UInt(256.W)
    val scattered = Bool()
    val eew = UInt(2.W)
    val fieldRegisterShift = UInt(2.W)
    val lastField = UInt(3.W)
    val elementAddresses = Vec(8, UInt(1024.W))
}
