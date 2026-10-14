import chisel3._
import chisel3.util._
import ZirconConfig.RenameParams

class VectorDestination extends Bundle {
    val base = UInt(5.W)
    val count = UInt(4.W)
    val physical = Vec(8, UInt(6.W))
    val previous = Vec(8, UInt(6.W))
}

/** Group adapter around eight instances of the shared Rename/SRat/FreeList.
  * Architectural index mod 8 selects a bank with four architectural and eight
  * physical registers at most. A group needs at most one destination in each bank.
  */
class VectorRename(val sourceCandidates: Int = 1, val physicalRegisters: Int = 64) extends Module {
    require(sourceCandidates > 0)
    require(physicalRegisters >= 56 && physicalRegisters <= 64 && physicalRegisters % 8 == 0)
    val io = IO(new Bundle {
        val sourceBase = Input(Vec(3, UInt(5.W)))
        val sourceCandidates = if (VectorRename.this.sourceCandidates > 1)
            Some(Input(Vec(VectorRename.this.sourceCandidates, Vec(3, UInt(5.W))))) else None
        val sourceSelection = if (VectorRename.this.sourceCandidates > 1)
            Some(Input(UInt(VectorRename.this.sourceCandidates.W))) else None
        val sourceCount = Input(Vec(3, UInt(4.W)))
        val destinationBase = Input(UInt(5.W))
        val destinationCount = Input(UInt(4.W))
        val source = Output(Vec(3, Vec(8, UInt(6.W))))
        val mask = Output(UInt(6.W))
        val destination = Output(new VectorDestination)
        val available = Output(Bool())
        val bankAvailable = Output(UInt(8.W))
        val preview = Input(Valid(new VectorDestination))
        // Same held group as preview, decoded before the Rename/Dispatch edge.
        val previewBanks = Input(UInt(8.W))
        val accept = Input(Bool())
        val retire = Input(Vec(3, Valid(new VectorDestination)))
        val restore = Input(Bool())
    })
    val params = RenameParams(numPhys = physicalRegisters / 8, renameWidth = 1, commitWidth = 3,
        numSources = 4, hasZeroReg = false, numArch = 4)
    val banks = Seq.fill(8)(Module(new Rename(params, maskUnusedOutputs = false)))

    def distance(base: UInt, bank: Int): UInt = (bank.U(3.W) - base(2, 0))(2, 0)
    def localAddress(base: UInt, bank: Int): UInt =
        (base(4, 3) + (bank.U < base(2, 0)).asUInt)(1, 0)
    // A legal group visits each bank at most once. Its local tag position
    // depends on the base alone; count only qualifies the bank's write.
    def groupSelection(group: VectorDestination, bank: Int): Seq[Bool] =
        (0 until 8).map(i => distance(group.base, bank) === i.U)
    def groupContains(group: VectorDestination, bank: Int): Bool =
        // Thresholds depend only on count and are shared by all banks. Decode
        // the late base separately instead of subtracting it before comparison.
        Mux1H(UIntToOH(group.base(2, 0), 8), (0 until 8).map { first =>
            group.count > ((bank - first + 8) & 7).U
        })
    val bases = io.sourceCandidates.map(_.toSeq).getOrElse(Seq(io.sourceBase))
    def selectSource(values: Seq[UInt]): UInt =
        io.sourceSelection.map(Mux1H(_, values)).getOrElse(values.head)
    // Decode each FQ candidate beside the first-vector selector. The shared
    // RAT still has exactly three source reads and one v0 read per bank.
    val sourceBankOH = (0 until 3).map(source => selectSource(bases.map(candidate =>
        UIntToOH(candidate(source)(2, 0), 8))))

    for ((bank, index) <- banks.zipWithIndex) {
        val rinfo = bank.io.rinfo(0)
        rinfo.request := distance(io.destinationBase, index) < io.destinationCount
        rinfo.rd := localAddress(io.destinationBase, index)
        for (source <- 0 until 3) {
            rinfo.src(source) := selectSource(bases.map(candidate => localAddress(candidate(source), index)))
            rinfo.srcValid(source) := distance(io.sourceBase(source), index) < io.sourceCount(source)
        }
        // The fourth shared RAT read captures v0 before a masked destination write.
        rinfo.src(3) := 0.U
        rinfo.srcValid(3) := (index == 0).B
        bank.io.prepare := 1.U
        bank.io.restore := io.restore

        val preview = bank.io.preview(0)
        val selection = groupSelection(io.preview.bits, index)
        preview.valid := io.preview.valid && io.previewBanks(index)
        preview.bits.rd := localAddress(io.preview.bits.base, index)
        preview.bits.prd := Mux1H(selection, io.preview.bits.physical.map(_(5, 3)))
        bank.io.writeback(0).valid := io.accept && preview.valid
        bank.io.writeback(0).bits := preview.bits

        for (port <- 0 until 3) {
            val retirement = io.retire(port)
            val hits = groupSelection(retirement.bits, index)
            val commit = bank.io.commit(port)
            commit.valid := retirement.valid && groupContains(retirement.bits, index)
            commit.bits.rd := localAddress(retirement.bits.base, index)
            commit.bits.prd := Mux1H(hits, retirement.bits.physical.map(_(5, 3)))
            commit.bits.pprd := Mux1H(hits, retirement.bits.previous.map(_(5, 3)))
        }
    }
    for (source <- 0 until 3; element <- 0 until 8) {
        val tags = VecInit(banks.zipWithIndex.map { case (bank, i) =>
            Cat(bank.io.pinfo(0).prs(source), i.U(3.W))
        })
        io.source(source)(element) := Mux1H(FIFOUtil.rotate(sourceBankOH(source), element), tags)
    }
    io.mask := Cat(banks(0).io.pinfo(0).prs(3), 0.U(3.W))
    io.destination.base := io.destinationBase
    io.destination.count := io.destinationCount
    val physical = VecInit(banks.zipWithIndex.map { case (bank, i) => Cat(bank.io.pinfo(0).prd, i.U(3.W)) })
    val previous = VecInit(banks.zipWithIndex.map { case (bank, i) => Cat(bank.io.pinfo(0).pprd, i.U(3.W)) })
    for (element <- 0 until 8) {
        val index = (io.destinationBase + element.U)(2, 0)
        io.destination.physical(element) := physical(index)
        io.destination.previous(element) := previous(index)
    }
    io.available := banks.map(_.io.available).reduce(_ && _)
    io.bankAvailable := VecInit(banks.map(_.io.freeAvailable)).asUInt
    when(io.preview.valid) {
        for (bank <- 0 until 8) {
            assert(io.previewBanks(bank) === groupContains(io.preview.bits, bank),
                "Held vector bank selection must match its destination group")
        }
    }
    when(io.accept) {
        assert(io.preview.valid && !io.restore)
        assert(io.preview.bits.base +& io.preview.bits.count <= 32.U)
        for (i <- 0 until 8) when(i.U < io.preview.bits.count) {
            assert(io.preview.bits.physical(i)(2, 0) === (io.preview.bits.base + i.U)(2, 0))
            assert(io.preview.bits.physical(i) < physicalRegisters.U)
        }
    }
    assert(io.destinationCount <= 8.U)
    assert(io.destinationBase +& io.destinationCount <= 32.U)
    for (count <- io.sourceCount) assert(count <= 8.U)
    io.sourceSelection.foreach(selection => assert(PopCount(selection) <= 1.U))
}
