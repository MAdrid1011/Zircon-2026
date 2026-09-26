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

/** A renamed destination carried by the fixed Rename-to-Dispatch stage. */
class DomainRenameWrite(p: RenameParams) extends Bundle {
    val rd = UInt(5.W)
    val prd = UInt(p.indexWidth.W)
}

class RenameIO(p: RenameParams) extends Bundle {
    val rinfo = Input(Vec(p.renameWidth, new DomainRegisterInfo(p)))
    val prepare = Input(UInt(p.renameWidth.W))
    // The held Q-side group is the next-state view if Dispatch accepts it.
    val preview = Input(Vec(p.renameWidth, Valid(new DomainRenameWrite(p))))
    // Dispatch accepts this Q-side group atomically. These ports update the
    // speculative map and consume the physical tags captured in that group.
    val writeback = Input(Vec(p.renameWidth, Valid(new DomainRenameWrite(p))))
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
  * The caller prepares a new D-side group while the accepted Q-side group
  * writes back. Read ports are write-first with respect to that Q-side group,
  * so the fixed pipeline boundary does not create a Rename bubble.
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
    fList.io.request := request
    fList.io.previewAllocate := VecInit(io.preview.map(_.valid))
    fList.io.allocate := VecInit(io.writeback.map(write => write.valid && !io.restore))
    fList.io.restore := io.restore
    srat.io.restore := io.restore
    when(io.restore) {
        assert(!io.writeback.map(_.valid).reduce(_ || _), "Recovery must discard pending Rename writes")
    }
    for (lane <- 0 until p.renameWidth) {
        when(io.writeback(lane).valid) {
            assert(io.preview(lane).valid && io.preview(lane).bits.asUInt === io.writeback(lane).bits.asUInt,
                "Accepted Rename writes must match the Q-side preview")
        }
    }

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
        srat.io.preview(i).valid := io.preview(i).valid
        srat.io.preview(i).bits.addr := io.preview(i).bits.rd
        srat.io.preview(i).bits.data := io.preview(i).bits.prd
        srat.io.rename(i).valid := io.writeback(i).valid && !io.restore
        srat.io.rename(i).bits.addr := io.writeback(i).bits.rd
        srat.io.rename(i).bits.data := io.writeback(i).bits.prd
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
