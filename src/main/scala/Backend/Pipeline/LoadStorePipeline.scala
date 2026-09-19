import chisel3._
import chisel3.util.log2Ceil
import ZirconConfig.{CommitParams, LoadPipelineParams}
import _root_.circt.stage.ChiselStage

class StoreRegfileIO(p: LoadPipelineParams) extends Bundle {
    val intAddr = Output(UInt(p.intWidth.W))
    val intData = Input(UInt(32.W))
    val fpAddr = Output(UInt(log2Ceil(p.numFpPhys).W))
    val fpData = Input(UInt(32.W))
    val hold = Output(Bool())
}

class StoreAddressResult(p: LoadPipelineParams) extends Bundle {
    val sqIdx = UInt(p.sqWidth.W)
    val robIdx = UInt(CommitIndex.addressWidth(CommitParams().robEntries).W)
    val vaddr = UInt(32.W)
    val paddr = UInt(34.W)
    val size = UInt(2.W)
    val mask = UInt(4.W)
    val exception = UInt(4.W)
    val uncache = Bool()
}

class StoreDataResult(p: LoadPipelineParams) extends Bundle {
    val sqIdx = UInt(p.sqWidth.W)
    val robIdx = UInt(CommitIndex.addressWidth(CommitParams().robEntries).W)
    val size = UInt(2.W)
    // The SQ aligns raw register data after joining it with the STA result.
    val data = UInt(32.W)
}

/** LS1 binds to DCache lane 1 / port B, shares LD/STA execution and collects STD independently. */
class LoadStorePipeline(p: LoadPipelineParams = LoadPipelineParams(), tlbEnabled: Boolean = false)
    extends LoadPipeline(p, withStore = true, tlbEnabled)

object ElaborateLoadStorePipeline {
    def main(args: Array[String]): Unit = {
        require(args.length == 1, "Usage: runMain ElaborateLoadStorePipeline <output-directory>")
        ChiselStage.emitSystemVerilogFile(
            new LoadStorePipeline(),
            Array("--target-dir", args(0)),
            Array("--lowering-options=disallowPackedArrays,disallowLocalVariables")
        )
    }
}
