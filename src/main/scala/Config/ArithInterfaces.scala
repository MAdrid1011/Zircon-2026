import chisel3._
import chisel3.util._
import ZirconConfig.{BackendParams, CommitParams}

object ArithConstants {
    private val backend = BackendParams()
    val numIntPhys = backend.numIntPhys
    val physWidth = backend.intWidth
    val tagWidth = physWidth + 1
    val robWidth = backend.robWidth
    val robAddressWidth = log2Ceil(CommitParams().robEntries)
}

class IntegerResult extends Bundle {
    val prd = UInt(ArithConstants.physWidth.W)
    val data = UInt(32.W)
}

class ArithCompletion extends Bundle {
    val robIdx = UInt(ArithConstants.robAddressWidth.W)
    val data = UInt(32.W)
    val exception = new BackendException
}

class ArithBranchUpdate extends Bundle {
    val robIdx = UInt(ArithConstants.robAddressWidth.W)
    val taken = Bool()
    val target = UInt(32.W)
    val predFail = Bool()
}

class ArithRegfileReadIO extends Bundle {
    val addr = Output(UInt(ArithConstants.physWidth.W))
    val data = Input(UInt(32.W))
}

class ArithRegfileIO extends Bundle {
    val read = Vec(2, new ArithRegfileReadIO)
    val write = Output(Valid(new IntegerResult))
}

class ArithRobIO extends Bundle {
    val readIdx = Output(UInt(ArithConstants.robAddressWidth.W))
    val pc = Input(UInt(32.W))
    val complete = Output(Valid(new ArithCompletion))
}

class ArithBranchContextIO extends Bundle {
    val update = Output(Valid(new ArithBranchUpdate))
}

class ArithWakeupIO extends Bundle {
    val wakeIssue = Output(new BackendWakeup)
    val wakeRF = Output(new BackendWakeup)
    val wakeWB = Output(new BackendWakeup)
}

class CSRExecutionPort extends Bundle {
    val req = Output(Valid(new CSRRequest))
    val rsp = Input(Valid(new CSRResponse))
    val frm = Input(UInt(3.W))
    // The IQ has already proved this instruction is the oldest authorized CSR.
    val commit = Output(Bool())
}
