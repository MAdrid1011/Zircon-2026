import chisel3._
import chisel3.util._
import ZirconConfig.{DCacheParams, LoadPipelineParams, RegfileParams}

class LoadIssueSystemIO(p: LoadPipelineParams, withStore: Boolean) extends Bundle {
    val iq = new LoadIQIO(p, withStore)
    val cmt = new LoadCommitIO(p, withStore)
    val wb = Valid(new LoadWriteback(p))
    val wk = new LoadWakeupIO(p)
    val bypass = new BypassProducerPort(p.backend)
}

/** Both production pipes share the full PRFs and a single DCache; Commit remains external. */
class DualLoadPipelineSystem(backend: DualPortRamBackend) extends Module {
    val p = LoadPipelineParams()
    val intParams = RegfileParams(holdReads = true)
    val fpParams = RegfileParams(numReadPorts = 3, numWritePorts = 3, hasZeroReg = false, holdReads = true)
    val io = IO(new Bundle {
        val ls0 = new LoadIssueSystemIO(p, false)
        val ls1 = new LoadIssueSystemIO(p, true)
        val intWrite = Vec(3, new RegfileWriteIO(intParams))
        val fpWrite = Vec(1, new RegfileWriteIO(fpParams))
        val intRead = Vec(7, new RegfileReadIO(intParams))
        val fpRead = Vec(2, new RegfileReadIO(fpParams))
        val flush = Input(Bool())
        val store = new Bundle {
            val req = Flipped(Decoupled(new DStoreRequest))
            val rsp = Decoupled(new DStoreResponse)
        }
        val l2 = new Bundle {
            val req = Decoupled(new DMemoryRequest)
            val rsp = Flipped(Decoupled(new DMemoryResponse))
        }
    })
    val ls0 = Module(new LoadPipeline(p))
    val ls1 = Module(new LoadStorePipeline(p))
    val intRf = Module(new Regfile(intParams))
    val fpRf = Module(new Regfile(fpParams))
    val cache = Module(new DCache(backend, DCacheParams(p.entries)))
    for ((pipe, port) <- Seq(ls0 -> io.ls0, ls1 -> io.ls1)) {
        pipe.io.blockIssue := false.B
        pipe.io.iq <> port.iq
        pipe.io.cmt <> port.cmt
        port.wb := pipe.io.rf.wr
        port.wk <> pipe.io.wk
        port.bypass <> pipe.io.bypass
        pipe.connectCache(cache.io)
    }
    for ((pipe, i) <- Seq(ls0, ls1).zipWithIndex) {
        intRf.io.read(i).addr := pipe.io.rf.rd.prj
        pipe.io.rf.rd.prjData := intRf.io.read(i).data
        for ((rf, fp) <- Seq(intRf -> false, fpRf -> true)) {
            rf.io.write(i).addr := pipe.io.rf.wr.bits.prd(p.physWidth - 1, 0)
            rf.io.write(i).data := pipe.io.rf.wr.bits.data
            rf.io.write(i).we := pipe.io.rf.wr.valid && pipe.io.rf.wr.bits.prd(p.physWidth) === fp.B
        }
    }
    intRf.io.read(2).addr := ls1.io.rf.std.get.intAddr
    ls1.io.rf.std.get.intData := intRf.io.read(2).data
    fpRf.io.read(0).addr := ls1.io.rf.std.get.fpAddr
    ls1.io.rf.std.get.fpData := fpRf.io.read(0).data
    intRf.io.readHold.get :=
        VecInit(Seq(ls0.io.rf.rd.hold, ls1.io.rf.rd.hold, ls1.io.rf.std.get.hold) ++ Seq.fill(7)(false.B))
    fpRf.io.readHold.get := VecInit(Seq(ls1.io.rf.std.get.hold, false.B, false.B))
    intRf.io.read.drop(3).zip(io.intRead).foreach { case (a, b) => a <> b }
    fpRf.io.read.drop(1).zip(io.fpRead).foreach { case (a, b) => a <> b }
    intRf.io.write.drop(2).zip(io.intWrite).foreach { case (a, b) => a <> b }
    fpRf.io.write.drop(2).zip(io.fpWrite).foreach { case (a, b) => a <> b }
    cache.io.flush := io.flush
    cache.io.maintenance.request := false.B
    cache.io.maintenance.invalidate := false.B
    cache.io.store <> io.store
    io.l2 <> cache.io.l2
}
