import chisel3._
import chisel3.util._
import ZirconConfig.RenameParams

class DomainRegisterInfo(p: RenameParams) extends Bundle {
    val src = Vec(p.numSources, UInt(5.W))
    val srcValid = Vec(p.numSources, Bool())
    val rd = UInt(5.W)
    val request = Bool()
}

class DomainPhysicalInfo(p: RenameParams) extends Bundle {
    val prs = Vec(p.numSources, UInt(p.indexWidth.W))
    val prd = UInt(p.indexWidth.W)
    val pprd = UInt(p.indexWidth.W)
}

class DomainCommitEntry(p: RenameParams) extends Bundle {
    val rd = UInt(5.W)
    val prd = UInt(p.indexWidth.W)
    val pprd = UInt(p.indexWidth.W)
}

class RenameIO(p: RenameParams) extends Bundle {
    val rinfo = Input(Vec(p.renameWidth, new DomainRegisterInfo(p)))
    val prepare = Input(UInt(p.renameWidth.W))
    val allocate = Input(UInt(p.renameWidth.W))
    val available = Output(Bool())
    val freeAvailable = Output(Bool())
    val freePrefix = Output(UInt(p.renameWidth.W))
    val pinfo = Output(Vec(p.renameWidth, new DomainPhysicalInfo(p)))
    val commit = Input(Vec(p.commitWidth, Valid(new DomainCommitEntry(p))))
    val restore = Input(Bool())
    val pra = Output(UInt(p.indexWidth.W))
}

/** One independently instantiable integer OR floating-point rename domain.
  * All tags are local. Sparse destination requests retain original lane order.
  * The caller combines domain availability and grants only dispatched lanes.
  * restore is the actual recovery cycle and may carry the final retirement update.
  */
class Rename(val p: RenameParams = RenameParams()) extends Module {
    val io = IO(new RenameIO(p))
    val fList = Module(new PRegFreeList(p.numPhys, p.renameWidth, p.commitWidth))
    val srat = Module(new SRat(
        p.indexWidth,
        p.renameWidth * (p.numSources + 1),
        p.renameWidth,
        p.commitWidth,
        p.hasZeroReg
    ))
    val request = io.rinfo.map(r => r.request && (if (p.hasZeroReg) r.rd =/= 0.U else true.B))
    io.available := !request.reduce(_ || _) || fList.io.available
    io.freeAvailable := fList.io.available
    io.freePrefix := fList.io.availablePrefix
    val prepared = request.zipWithIndex.map { case (candidate, lane) =>
        candidate && io.prepare(lane) && !io.restore
    }
    val granted = request.zipWithIndex.map { case (candidate, lane) =>
        candidate && io.allocate(lane) && !io.restore
    }
    fList.io.request := request
    fList.io.allocate := VecInit(granted)
    fList.io.restore := io.restore
    srat.io.restore := io.restore
    when(granted.reduce(_ || _)) {
        assert(
            !VecInit(granted.zip(fList.io.availablePrefix.asBools).map { case (lane, available) =>
                lane && !available
            }).asUInt.orR,
            "Rename allocation requires available resources",
        )
    }
    assert((io.allocate & ~io.prepare) === 0.U, "Rename allocation must be a prepared lane prefix")

    for (i <- 0 until p.renameWidth) {
        io.pinfo(i).prd := Mux(prepared(i), fList.io.prd(i), 0.U)
        val r = io.rinfo(i)
        val logical = r.src.toSeq :+ r.rd
        for (s <- 0 to p.numSources) {
            val read = i * (p.numSources + 1) + s
            srat.io.readAddr(read) := logical(s)
            val valid = if (s == p.numSources) prepared(i)
            else r.srcValid(s) && (if (p.hasZeroReg) logical(s) =/= 0.U else true.B)
            val hits = (0 until i).map(j => prepared(j) && io.rinfo(j).rd === logical(s))
            val winners = hits.indices.map(j => hits(j) && !hits.drop(j + 1).foldLeft(false.B)(_ || _))
            val bypass = hits.foldLeft(false.B)(_ || _)
            val value = if (i == 0) srat.io.readData(read)
            else Mux(bypass, Mux1H(winners, io.pinfo.take(i).map(_.prd)), srat.io.readData(read))
            if (s == p.numSources) io.pinfo(i).pprd := Mux(valid, value, 0.U)
            else {
                io.pinfo(i).prs(s) := Mux(valid, value, 0.U)
            }
        }
        srat.io.rename(i).valid := granted(i)
        srat.io.rename(i).bits.addr := r.rd
        srat.io.rename(i).bits.data := io.pinfo(i).prd
    }
    for (i <- 0 until p.commitWidth) {
        val c = io.commit(i)
        srat.io.commit(i).valid := c.valid
        srat.io.commit(i).bits.addr := c.bits.rd
        srat.io.commit(i).bits.data := c.bits.prd
        fList.io.release(i).valid := c.valid
        fList.io.release(i).bits := c.bits.pprd
        when(c.valid) {
            assert(c.bits.prd < p.numPhys.U && c.bits.pprd < p.numPhys.U, "Physical index out of range")
            if (p.hasZeroReg) {
                assert(
                    c.bits.rd =/= 0.U && c.bits.prd =/= 0.U && c.bits.pprd =/= 0.U,
                    "Integer zero register must not be renamed or recycled"
                )
            }
        }
    }
    io.pra := srat.io.pra
}
