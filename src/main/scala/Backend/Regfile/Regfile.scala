import chisel3._
import chisel3.util._
import ZirconConfig.RegfileParams

class RegfileReadIO(p: RegfileParams) extends Bundle {
    val addr = Input(UInt(p.addrWidth.W))
    val data = Output(UInt(p.dataWidth.W))
    val oneHot = if (p.oneHotRead) Some(Input(UInt(p.numEntries.W))) else None
}

class RegfileWriteIO(p: RegfileParams) extends Bundle {
    val addr = Input(UInt(p.addrWidth.W))
    val we = Input(Bool())
    val data = Input(UInt(p.dataWidth.W))
    val bytes = if (p.byteWrite) Some(Input(UInt((p.dataWidth / 8).W))) else None
}

class RegfileDirectWriteIO(p: RegfileParams) extends Bundle {
    val oneHot = Input(UInt(p.numEntries.W))
    val data = Input(UInt(p.dataWidth.W))
}

class RegfileIO(
    p: RegfileParams,
    directWritePort: Option[Int],
    readBypassOverridePorts: Seq[Int],
    selectiveReadBypassPort: Option[Int],
) extends Bundle {
    val read = Vec(p.numReadPorts, new RegfileReadIO(p))
    val write = Vec(p.numWritePorts, new RegfileWriteIO(p))
    val directWrite = directWritePort.map(_ => new RegfileDirectWriteIO(p))
    val readBypassOverride = if (readBypassOverridePorts.nonEmpty)
        Some(Input(Vec(readBypassOverridePorts.size, Bool()))) else None
    val readBypassOverrideAddr = if (readBypassOverridePorts.nonEmpty)
        Some(Input(Vec(readBypassOverridePorts.size, UInt(p.addrWidth.W)))) else None
    val selectiveReadBypass = selectiveReadBypassPort.map(_ => Input(new RegfileWriteIO(p)))
    val readHold = if (p.holdReads) Some(Input(Vec(p.numReadPorts, Bool()))) else None
    val observation = if (p.observe) Some(Output(Vec(p.numEntries, UInt(p.dataWidth.W)))) else None
}

/** Asynchronous reads, synchronous writes, and same-cycle WB bypass.
  * Enabled writes must have distinct destinations; address zero requires we=false when hasZeroReg.
  * Flush/replay cancellation belongs to the producer; this block has no backpressure.
  */
class Regfile(
    val p: RegfileParams = RegfileParams(),
    directWritePort: Option[Int] = None,
    readBypassOverridePorts: Seq[Int] = Seq.empty,
    overrideReadPorts: Set[Int] = Set.empty,
    selectiveReadBypassPort: Option[Int] = None,
    selectiveReadPorts: Set[Int] = Set.empty,
) extends Module {
    directWritePort.foreach(port => require(port >= 0 && port < p.numWritePorts))
    readBypassOverridePorts.foreach(port => require(port >= 0 && port < p.numWritePorts))
    require(readBypassOverridePorts.distinct.size == readBypassOverridePorts.size)
    selectiveReadBypassPort.foreach(port => require(port >= 0 && port < p.numWritePorts))
    require(overrideReadPorts.forall(port => port >= 0 && port < p.numReadPorts))
    require(selectiveReadPorts.forall(port => port >= 0 && port < p.numReadPorts))
    require(overrideReadPorts.isEmpty || readBypassOverridePorts.nonEmpty)
    require(selectiveReadPorts.isEmpty || selectiveReadBypassPort.nonEmpty)
    val io = IO(new RegfileIO(p, directWritePort, readBypassOverridePorts, selectiveReadBypassPort))
    // Keep the architectural p0 read constant; its unobservable storage row is removed by synthesis.
    private val firstWritable = if (p.hasZeroReg) 1 else 0
    // Uninitialized byte-masked RF storage has asynchronous reads and two
    // independent write ports. Describe the storage directly instead of
    // expanding a write-enable mux for every row and byte. Hardware banked
    // reads use explicit row/bank masks; observed simulation keeps the array.
    private val byteMemory = if (p.byteWrite && !p.initialize && !p.hasZeroReg && !p.oneHotRead &&
        (p.readBankEntries == 0 || p.observe))
        Some(Mem(p.numEntries, Vec(p.dataWidth / 8, UInt(8.W)))) else None
    private val byteData = if (p.byteWrite && byteMemory.isEmpty)
        Some(Reg(Vec(p.numEntries, Vec(p.dataWidth / 8, UInt(8.W))))) else None
    private val data = if (byteMemory.nonEmpty)
        VecInit((0 until p.numEntries).map(row => byteMemory.get(row.U).asUInt))
        else if (p.byteWrite) VecInit(byteData.get.map(_.asUInt))
        else if (p.initialize) RegInit(VecInit.fill(p.numEntries)(0.U(p.dataWidth.W)))
        else Reg(Vec(p.numEntries, UInt(p.dataWidth.W)))
    private val values = if (p.hasZeroReg) VecInit(Seq(0.U(p.dataWidth.W)) ++ data.toSeq.drop(1)) else data
    io.observation.foreach(_ := values)

    io.read.foreach(r => assert(r.addr < p.numEntries.U, "PRF read address out of range"))
    if (p.oneHotRead) io.read.foreach { read =>
        assert(PopCount(read.oneHot.get) <= 1.U)
    }
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
        if (p.byteWrite) {
            require(directWritePort.isEmpty)
            if (byteMemory.nonEmpty) {
                when(write.we) {
                    byteMemory.get.write(write.addr,
                        write.data.asTypeOf(Vec(p.dataWidth / 8, UInt(8.W))), write.bytes.get.asBools)
                }
            } else {
                for (entry <- firstWritable until p.numEntries; byte <- 0 until p.dataWidth / 8) {
                    when(write.we && write.addr === entry.U && write.bytes.get(byte)) {
                        byteData.get(entry)(byte) := write.data(8 * byte + 7, 8 * byte)
                    }
                }
            }
        } else if (directWritePort.contains(port)) {
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
    if (p.byteWrite && p.initialize) {
        when(reset.asBool) { byteData.get := 0.U.asTypeOf(byteData.get) }
    }

    io.read.zipWithIndex.foreach { case (r, index) =>
        val stored = if (byteMemory.nonEmpty) {
            // The existing banked decoder selects exactly addr. An indexed
            // asynchronous memory read preserves that value without a row-wide
            // OR network in the behavioral simulation model.
            byteMemory.get(r.addr).asUInt
        } else if (p.readBankEntries > 0) {
            // Decode row and bank independently. The bank select drives only
            // the final word mux, rather than every bit of every storage row.
            val rowBits = log2Ceil(p.readBankEntries)
            val rowOH = UIntToOH(r.addr(rowBits - 1, 0), p.readBankEntries)
            val words = values.toSeq.grouped(p.readBankEntries).map(rows =>
                Mux1H(rowOH.asBools, rows)).toSeq
            if (words.size == 1) words.head else
                Mux1H(UIntToOH(r.addr(p.addrWidth - 1, rowBits), words.size).asBools, words)
        } else if (p.oneHotRead) {
            Mux1H(r.oneHot.get, values)
        } else if (overrideReadPorts.contains(index)) {
            Mux1H(UIntToOH(r.addr, p.numEntries).asBools, values.toSeq)
        } else values(r.addr)
        val hit = io.write.zipWithIndex.map { case (w, port) =>
            if (selectiveReadBypassPort.contains(port) && selectiveReadPorts.contains(index)) {
                val selected = io.selectiveReadBypass.get
                selected.we && selected.addr === r.addr
            } else if (readBypassOverridePorts.contains(port) && overrideReadPorts.contains(index)) {
                val overrideIndex = readBypassOverridePorts.indexOf(port)
                io.readBypassOverride.get(overrideIndex) && io.readBypassOverrideAddr.get(overrideIndex) === r.addr
            } else if (p.readBypass) {
                w.we && w.addr === r.addr
            } else {
                false.B
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
