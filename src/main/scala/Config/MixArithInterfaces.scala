import chisel3._
import chisel3.util._
import ZirconConfig.{BackendParams, CommitParams}

object MixArithConstants {
    private val backend = BackendParams()
    val numIntPhys = backend.numIntPhys
    val numFpPhys = backend.numFpPhys
    val localPhysWidth = backend.physWidth
    val fpPhysWidth = log2Ceil(backend.numFpPhys)
    val physTagWidth = localPhysWidth + 1
    val robWidth = backend.robWidth
    val robAddressWidth = log2Ceil(CommitParams().robEntries)
    val numSources = 3
    val tagWidth = physTagWidth + 1 + 1 + robAddressWidth + 37
}

/** Fields consumed after a MixArith instruction leaves the common issue queue. */
class MixArithIssue extends Bundle {
    val prs = Vec(MixArithConstants.numSources, UInt(MixArithConstants.physTagWidth.W))
    val sourceValid = Vec(MixArithConstants.numSources, Bool())
    val prd = UInt(MixArithConstants.physTagWidth.W)
    val rdValid = Bool()
    val fu = UInt(4.W)
    val op = UInt(5.W)
    val imm = UInt(17.W)
    val roundingMode = UInt(3.W)
    val inst = UInt(32.W)
    val rdZero = Bool()
    val robIdx = UInt(MixArithConstants.robAddressWidth.W)
    val exception = new BackendException
    val fpFlagsValid = Bool()
}

object MixArithIssue {
    def fromBackend(source: BackendPackage): MixArithIssue = {
        val issue = Wire(new MixArithIssue)
        issue.prs := source.prs
        issue.sourceValid := source.sourceValid
        issue.prd := source.prd
        issue.rdValid := source.rdValid
        issue.fu := source.fu
        issue.op := source.op
        issue.imm := source.imm(16, 0)
        issue.roundingMode := source.roundingMode
        issue.inst := source.inst
        issue.rdZero := source.rdZero
        issue.robIdx := source.robIdx(MixArithConstants.robAddressWidth - 1, 0)
        issue.exception := source.exception
        issue.fpFlagsValid := source.fpFlagsValid
        when(source.imm(31, 17).orR && !source.exception.valid) {
            issue.exception.valid := true.B
            issue.exception.cause := ZirconConfig.DecodeException.IllegalInstruction.U
            issue.exception.tval := source.inst
        }
        issue
    }
}

/** Instruction state that must survive the private arithmetic pipelines. */
class MixArithTag extends Bundle {
    val prd = UInt(MixArithConstants.physTagWidth.W)
    val rdValid = Bool()
    val fpFlagsValid = Bool()
    val robIdx = UInt(MixArithConstants.robAddressWidth.W)
    val exception = new BackendException
}

/** Only fields needed one cycle before WB for bypass reservation. */
class MixArithWakeTag extends Bundle {
    val prd = UInt(MixArithConstants.physTagWidth.W)
    val rdValid = Bool()
    val exceptionValid = Bool()
}

object MixArithWakeTag {
    def from(tag: MixArithTag): MixArithWakeTag = {
        val wake = Wire(new MixArithWakeTag)
        wake.prd := tag.prd
        wake.rdValid := tag.rdValid
        wake.exceptionValid := tag.exception.valid
        wake
    }

    def fromUInt(tag: UInt): MixArithWakeTag = from(tag.asTypeOf(new MixArithTag))
}

/** Common result shape used to align every MixArith operation at WB. */
class MixArithResult extends Bundle {
    val tag = new MixArithTag
    val data = UInt(32.W)
    val fflags = UInt(5.W)
}

class MixArithCompletion extends Bundle {
    val prd = UInt(MixArithConstants.physTagWidth.W)
    val rdValid = Bool()
    val data = UInt(32.W)
    val fflags = UInt(5.W)
    val fpFlagsValid = Bool()
    val robIdx = UInt(MixArithConstants.robAddressWidth.W)
    val exception = new BackendException
}

class MixArithReadPort(addressWidth: Int) extends Bundle {
    val addr = Output(UInt(addressWidth.W))
    val data = Input(UInt(32.W))
}

class MixArithLocalWrite(addressWidth: Int) extends Bundle {
    val addr = UInt(addressWidth.W)
    val data = UInt(32.W)
}

class MixArithRegfileIO extends Bundle {
    val intRead = Vec(2, new MixArithReadPort(MixArithConstants.localPhysWidth))
    val fpRead = Vec(3, new MixArithReadPort(MixArithConstants.fpPhysWidth))
    val intWrite = Output(Valid(new MixArithLocalWrite(MixArithConstants.localPhysWidth)))
    val fpWrite = Output(Valid(new MixArithLocalWrite(MixArithConstants.fpPhysWidth)))
}

class MixArithRobIO extends Bundle {
    val readIdx = Output(UInt(MixArithConstants.robAddressWidth.W))
    val pc = Input(UInt(32.W))
    val complete = Output(Valid(new MixArithCompletion))
}

class MixArithCommitIO extends Bundle {
    val rob = new MixArithRobIO
    val flush = Input(Bool())
}

class MixArithPipelineIO extends Bundle {
    val iq = Flipped(Decoupled(new MixArithIssue))
    val rf = new MixArithRegfileIO
    val cmt = new MixArithCommitIO
    val csr = new CSRExecutionPort
    val bypass = new PipelineBypassPort(3)
    val wakeEX2 = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
    val wakeEX3 = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
    val wakeup = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
    val divideBusy = Output(Bool())
}
