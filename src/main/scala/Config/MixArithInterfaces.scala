import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

object MixArithConstants {
    private val backend = BackendParams()
    val numIntPhys = backend.numIntPhys
    val numFpPhys = backend.numFpPhys
    val localPhysWidth = backend.physWidth
    val physTagWidth = localPhysWidth + 1
    val robWidth = backend.robWidth
    val numSources = 3
    val packageWidth = (new BackendPackage(backend)).getWidth
}

class MixArithCompletion extends Bundle {
    val prd = UInt(MixArithConstants.physTagWidth.W)
    val rdValid = Bool()
    val data = UInt(32.W)
    val fflags = UInt(5.W)
    val fpFlagsValid = Bool()
    val robIdx = UInt(MixArithConstants.robWidth.W)
    val exception = new BackendException
}

class MixArithReadPort extends Bundle {
    val addr = Output(UInt(MixArithConstants.localPhysWidth.W))
    val data = Input(UInt(32.W))
}

class MixArithLocalWrite extends Bundle {
    val addr = UInt(MixArithConstants.localPhysWidth.W)
    val data = UInt(32.W)
}

class MixArithRegfileIO extends Bundle {
    val intRead = Vec(2, new MixArithReadPort)
    val fpRead = Vec(3, new MixArithReadPort)
    val intWrite = Output(Valid(new MixArithLocalWrite))
    val fpWrite = Output(Valid(new MixArithLocalWrite))
}

class MixArithRobIO extends Bundle {
    val readIdx = Output(UInt(MixArithConstants.robWidth.W))
    val pc = Input(UInt(32.W))
    val complete = Output(Valid(new MixArithCompletion))
}

class MixArithCommitIO extends Bundle {
    val rob = new MixArithRobIO
    val flush = Input(Bool())
}

class MixArithPipelineIO extends Bundle {
    val iq = Flipped(Decoupled(new BackendPackage))
    val rf = new MixArithRegfileIO
    val cmt = new MixArithCommitIO
    val csr = new CSRExecutionPort
    val bypass = new PipelineBypassPort(3)
    val wakeEX2 = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
    val wakeEX3 = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
    val wakeup = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
    val available = Output(Bool())
    val divideBusy = Output(Bool())
}
