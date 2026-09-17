import chisel3._
import chisel3.util._
import ZirconConfig.RegfileParams

class RegfileReadIO(p: RegfileParams) extends Bundle {
    val addr = Input(UInt(p.addrWidth.W))
    val data = Output(UInt(p.dataWidth.W))
}

class RegfileWriteIO(p: RegfileParams) extends Bundle {
    val addr = Input(UInt(p.addrWidth.W))
    val we = Input(Bool())
    val data = Input(UInt(p.dataWidth.W))
}

class RegfileIO(p: RegfileParams) extends Bundle {
    val read = Vec(p.numReadPorts, new RegfileReadIO(p))
    val write = Vec(p.numWritePorts, new RegfileWriteIO(p))
    val readHold = if (p.holdReads) Some(Input(Vec(p.numReadPorts, Bool()))) else None
}

/** Asynchronous reads, synchronous writes, and same-cycle WB bypass.
  * Enabled writes must have distinct destinations; address zero requires we=false when hasZeroReg.
  * Flush/replay cancellation belongs to the producer; this block has no backpressure.
  */
class Regfile(val p: RegfileParams = RegfileParams()) extends Module {
    val io = IO(new RegfileIO(p))
    // Integer configurations omit storage for p0; FP configurations store every entry.
    private val firstWritable = if (p.hasZeroReg) 1 else 0
    private val data = RegInit(VecInit.fill(p.numEntries - firstWritable)(0.U(p.dataWidth.W)))
    private val values = if (p.hasZeroReg) VecInit(Seq(0.U(p.dataWidth.W)) ++ data.toSeq) else data

    io.read.foreach(r => assert(r.addr < p.numEntries.U, "PRF read address out of range"))
    io.write.foreach { w =>
        when(w.we) {
            assert(w.addr < p.numEntries.U, "PRF write address out of range")
            if (p.hasZeroReg) {
                assert(w.addr =/= 0.U, "PRF write to p0 requires we=false")
            }
        }
    }
    for (i <- 0 until p.numWritePorts; j <- 0 until i) {
        assert(
            !(io.write(i).we && io.write(j).we && io.write(i).addr === io.write(j).addr),
            "PRF simultaneous writes must have distinct destinations"
        )
    }

    for (entry <- firstWritable until p.numEntries) {
        val hit = io.write.map(w => w.we && w.addr === entry.U)
        when(hit.reduce(_ || _)) {
            data(entry - firstWritable) := Mux1H(hit, io.write.map(_.data))
        }
    }

    io.read.zipWithIndex.foreach { case (r, index) =>
        val hit = io.write.map(w => w.we && w.addr === r.addr)
        val bypass = hit.reduce(_ || _)
        if (p.holdReads) {
            // The first stalled read is captured at its edge; hold then reuses that operand.
            // Snapshot, stored value and WB values share one final mutually exclusive selection.
            val hold = io.readHold.get(index)
            val previous = RegEnable(r.data, !hold)
            r.data := Mux1H(
                Seq(hold -> previous, (!hold && !bypass) -> values(r.addr)) ++
                    hit.zip(io.write).map { case (h, w) => (!hold && h) -> w.data }
            )
        } else {
            r.data := Mux(bypass, Mux1H(hit, io.write.map(_.data)), values(r.addr))
        }
    }
}
