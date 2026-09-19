import chisel3._
import chisel3.util._
import ZirconConfig.{DCacheParams, LoadPipelineParams, RegfileParams}
import _root_.circt.stage.ChiselStage

/** Integration fixture: production Pipe, full-sized PRFs and one shared production DCache. */
class LoadPipelineSystem(
    backend: DualPortRamBackend,
    val p: LoadPipelineParams = LoadPipelineParams(),
    val withStore: Boolean = false
) extends Module {
    val intParams = RegfileParams(numEntries = p.numIntPhys, holdReads = true)
    val fpParams = RegfileParams(
        numEntries = p.numFpPhys,
        numReadPorts = 3,
        numWritePorts = 3,
        hasZeroReg = false,
        holdReads = withStore
    )
    val io = IO(new Bundle {
        val iq = new LoadIQIO(p, withStore)
        val cmt = new LoadCommitIO(p, withStore)
        val wk = new LoadWakeupIO(p)
        val bypass = new BypassProducerPort(p.backend)
        val wb = Valid(new LoadWriteback(p))
        val intWrite = Vec(intParams.numWritePorts - 1, new RegfileWriteIO(intParams))
        val fpWrite = Vec(fpParams.numWritePorts - 1, new RegfileWriteIO(fpParams))
        val intRead = Vec(intParams.numReadPorts - (if (withStore) 2 else 1), new RegfileReadIO(intParams))
        val fpRead = Vec(fpParams.numReadPorts - (if (withStore) 1 else 0), new RegfileReadIO(fpParams))
        val other = new Bundle {
            val req = Flipped(Decoupled(new DLoadRequest(DCacheParams(p.entries))))
            val fixedLatency = Output(Bool())
            val wbSelect = Valid(new DLoadWBSelect(DCacheParams(p.entries)))
            val rsp = Valid(new DLoadResponse(DCacheParams(p.entries)))
        }
        val store = new Bundle {
            val req = Flipped(Decoupled(new DStoreRequest))
            val rsp = Decoupled(new DStoreResponse)
        }
        val l2 = new Bundle {
            val req = Decoupled(new DMemoryRequest)
            val rsp = Flipped(Decoupled(new DMemoryResponse))
        }
        val request = Output(Valid(new DLoadRequest(DCacheParams(p.entries))))
        val requestReady = Output(Bool())
        val idle = Output(Bool())
    })
    val pipe = Module(if (withStore) new LoadStorePipeline(p) else new LoadPipeline(p))
    val intRf = Module(new Regfile(intParams))
    val fpRf = Module(new Regfile(fpParams))
    val cache = Module(new DCache(backend, DCacheParams(p.entries)))
    pipe.io.blockIssue := false.B
    cache.io.maintenance.request := false.B
    pipe.io.iq <> io.iq
    pipe.io.cmt <> io.cmt
    io.wk <> pipe.io.wk
    io.bypass <> pipe.io.bypass
    io.wb := pipe.io.rf.wr
    intRf.io.read(0).addr := pipe.io.rf.rd.prj
    intRf.io.readHold.get := VecInit.fill(intParams.numReadPorts)(false.B)
    intRf.io.readHold.get(0) := pipe.io.rf.rd.hold
    pipe.io.rf.rd.prjData := intRf.io.read(0).data
    if (withStore) {
        intRf.io.read(1).addr := pipe.io.rf.std.get.intAddr
        pipe.io.rf.std.get.intData := intRf.io.read(1).data
        intRf.io.readHold.get(1) := pipe.io.rf.std.get.hold
        fpRf.io.read(0).addr := pipe.io.rf.std.get.fpAddr
        pipe.io.rf.std.get.fpData := fpRf.io.read(0).data
        fpRf.io.readHold.get := VecInit(Seq(pipe.io.rf.std.get.hold) ++ Seq.fill(fpParams.numReadPorts - 1)(false.B))
    }
    intRf.io.read.drop(if (withStore) 2 else 1).zip(io.intRead).foreach { case (a, b) => a <> b }
    fpRf.io.read.drop(if (withStore) 1 else 0).zip(io.fpRead).foreach { case (a, b) => a <> b }
    intRf.io.write.drop(1).zip(io.intWrite).foreach { case (a, b) => a <> b }
    fpRf.io.write.drop(1).zip(io.fpWrite).foreach { case (a, b) => a <> b }
    for ((rf, fp) <- Seq(intRf -> false, fpRf -> true)) {
        rf.io.write(0).addr := pipe.io.rf.wr.bits.prd(p.physWidth - 1, 0)
        rf.io.write(0).data := pipe.io.rf.wr.bits.data
        rf.io.write(0).we := pipe.io.rf.wr.valid && pipe.io.rf.wr.bits.prd(p.physWidth) === fp.B
    }
    pipe.connectCache(cache.io)
    cache.io.load(1 - pipe.cacheLane) <> io.other
    val otherForward = cache.io.forward(1 - pipe.cacheLane)
    otherForward.result.valid := RegNext(otherForward.query.valid, false.B)
    otherForward.result.bits := 0.U.asTypeOf(new DForwardResult)
    otherForward.result.bits.slot := RegEnable(otherForward.query.bits.slot, otherForward.query.valid)
    cache.io.store <> io.store
    io.l2 <> cache.io.l2
    cache.io.flush := io.cmt.flush
    io.request.valid := pipe.io.cache.req.valid
    io.request.bits := pipe.io.cache.req.bits
    io.requestReady := pipe.io.cache.req.ready
    io.idle := cache.io.idle
}

class LoadStorePipelineSystem(backend: DualPortRamBackend, p: LoadPipelineParams = LoadPipelineParams())
    extends LoadPipelineSystem(backend, p, withStore = true)

object ElaborateLoadStorePipelineSystem {
    def main(args: Array[String]): Unit = {
        require(args.length == 2, "Usage: Test / runMain ElaborateLoadStorePipelineSystem <backend> <output-directory>")
        ChiselStage.emitSystemVerilogFile(
            new LoadStorePipelineSystem(DualPortRamBackend.parse(args(0))),
            Array("--target-dir", args(1)),
            Array("--lowering-options=disallowPackedArrays,disallowLocalVariables")
        )
    }
}

object ElaborateLoadPipelineSystem {
    def main(args: Array[String]): Unit = {
        require(args.length == 2, "Usage: Test / runMain ElaborateLoadPipelineSystem <backend> <output-directory>")
        ChiselStage.emitSystemVerilogFile(
            new LoadPipelineSystem(DualPortRamBackend.parse(args(0))),
            Array("--target-dir", args(1)),
            Array("--lowering-options=disallowPackedArrays,disallowLocalVariables")
        )
    }
}
