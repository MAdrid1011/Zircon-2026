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

class RegfileDirectWriteIO(p: RegfileParams) extends Bundle {
    val oneHot = Input(UInt(p.numEntries.W))
    val data = Input(UInt(p.dataWidth.W))
}

class RegfileIO(
    p: RegfileParams,
    directWritePort: Option[Int],
    readBypassOverridePort: Option[Int],
    selectiveReadBypassPort: Option[Int],
) extends Bundle {
    val read = Vec(p.numReadPorts, new RegfileReadIO(p))
    val write = Vec(p.numWritePorts, new RegfileWriteIO(p))
    val directWrite = directWritePort.map(_ => new RegfileDirectWriteIO(p))
    val readBypassOverride = readBypassOverridePort.map(_ => Input(Bool()))
    val readBypassOverrideAddr = readBypassOverridePort.map(_ => Input(UInt(p.addrWidth.W)))
    val selectiveReadBypass = selectiveReadBypassPort.map(_ => Input(new RegfileWriteIO(p)))
    val readHold = if (p.holdReads) Some(Input(Vec(p.numReadPorts, Bool()))) else None
}

/** Asynchronous reads, synchronous writes, and same-cycle WB bypass.
  * Enabled writes must have distinct destinations; address zero requires we=false when hasZeroReg.
  * Flush/replay cancellation belongs to the producer; this block has no backpressure.
  */
class Regfile(
    val p: RegfileParams = RegfileParams(),
    directWritePort: Option[Int] = None,
    readBypassOverridePort: Option[Int] = None,
    overrideReadPorts: Set[Int] = Set.empty,
    selectiveReadBypassPort: Option[Int] = None,
    selectiveReadPorts: Set[Int] = Set.empty,
) extends Module {
    directWritePort.foreach(port => require(port >= 0 && port < p.numWritePorts))
    readBypassOverridePort.foreach(port => require(port >= 0 && port < p.numWritePorts))
    selectiveReadBypassPort.foreach(port => require(port >= 0 && port < p.numWritePorts))
    require(overrideReadPorts.forall(port => port >= 0 && port < p.numReadPorts))
    require(selectiveReadPorts.forall(port => port >= 0 && port < p.numReadPorts))
    require(overrideReadPorts.isEmpty || readBypassOverridePort.nonEmpty)
    require(selectiveReadPorts.isEmpty || selectiveReadBypassPort.nonEmpty)
    val io = IO(new RegfileIO(p, directWritePort, readBypassOverridePort, selectiveReadBypassPort))
    // Keep the architectural p0 read constant; its unobservable storage row is removed by synthesis.
    private val firstWritable = if (p.hasZeroReg) 1 else 0
    private val data = RegInit(VecInit.fill(p.numEntries)(0.U(p.dataWidth.W)))
    private val values = if (p.hasZeroReg) VecInit(Seq(0.U(p.dataWidth.W)) ++ data.toSeq.drop(1)) else data

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
    directWritePort.foreach { port =>
        val direct = io.directWrite.get
        assert(PopCount(direct.oneHot) <= 1.U, "PRF direct write must be zero-hot or one-hot")
        when(direct.oneHot.orR && io.write(port).we) {
            assert(
                direct.oneHot === UIntToOH(io.write(port).addr, p.numEntries),
                "PRF direct write index must match its port address"
            )
        }
    }

    for ((write, port) <- io.write.zipWithIndex) {
        if (directWritePort.contains(port)) {
            val direct = io.directWrite.get
            when(write.we && direct.oneHot.orR) {
                for (entry <- firstWritable until p.numEntries) {
                    when(direct.oneHot(entry)) { data(entry) := direct.data }
                }
            }
            when(write.we && !direct.oneHot.orR) { data(write.addr) := write.data }
        } else {
            when(write.we) { data(write.addr) := write.data }
        }
    }

    io.read.zipWithIndex.foreach { case (r, index) =>
        val stored = if (overrideReadPorts.contains(index)) {
            Mux1H(UIntToOH(r.addr, p.numEntries).asBools, values.toSeq)
        } else values(r.addr)
        val hit = io.write.zipWithIndex.map { case (w, port) =>
            if (selectiveReadBypassPort.contains(port) && selectiveReadPorts.contains(index)) {
                val selected = io.selectiveReadBypass.get
                selected.we && selected.addr === r.addr
            } else if (readBypassOverridePort.contains(port) && overrideReadPorts.contains(index)) {
                io.readBypassOverride.get && io.readBypassOverrideAddr.get === r.addr
            } else {
                w.we && w.addr === r.addr
            }
        }
        val bypassData = io.write.zipWithIndex.map { case (w, port) =>
            if (selectiveReadBypassPort.contains(port) && selectiveReadPorts.contains(index))
                io.selectiveReadBypass.get.data
            else w.data
        }
        val bypass = hit.reduce(_ || _)
        if (p.holdReads) {
            // The first stalled read is captured at its edge; hold then reuses that operand.
            // Snapshot, stored value and WB values share one final mutually exclusive selection.
            val hold = io.readHold.get(index)
            val previous = RegEnable(r.data, !hold)
            r.data := Mux1H(
                Seq(hold -> previous, (!hold && !bypass) -> stored) ++
                    hit.zip(bypassData).map { case (h, data) => (!hold && h) -> data }
            )
        } else {
            r.data := Mux(bypass, Mux1H(hit, bypassData), stored)
        }
    }
}
