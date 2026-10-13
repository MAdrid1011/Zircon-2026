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
  * physical registers. A group needs at most one destination in each bank.
  */
class VectorRename extends Module {
    val io = IO(new Bundle {
        val sourceBase = Input(Vec(3, UInt(5.W)))
        val sourceCount = Input(Vec(3, UInt(4.W)))
        val destinationBase = Input(UInt(5.W))
        val destinationCount = Input(UInt(4.W))
        val source = Output(Vec(3, Vec(8, UInt(6.W))))
        val mask = Output(UInt(6.W))
        val destination = Output(new VectorDestination)
        val available = Output(Bool())
        val bankAvailable = Output(UInt(8.W))
        val preview = Input(Valid(new VectorDestination))
        val accept = Input(Bool())
        val retire = Input(Vec(3, Valid(new VectorDestination)))
        val restore = Input(Bool())
    })
    val params = RenameParams(numPhys = 8, renameWidth = 1, commitWidth = 3,
        numSources = 4, hasZeroReg = false, numArch = 4)
    val banks = Seq.fill(8)(Module(new Rename(params, maskUnusedOutputs = false)))

    def distance(base: UInt, bank: Int): UInt = (bank.U(3.W) - base(2, 0))(2, 0)
    def localAddress(base: UInt, bank: Int): UInt =
        (base(4, 3) + (bank.U < base(2, 0)).asUInt)(1, 0)
    def groupSelection(group: VectorDestination, bank: Int): Seq[Bool] =
        (0 until 8).map(i => i.U < group.count && (group.base + i.U)(2, 0) === bank.U)

    for ((bank, index) <- banks.zipWithIndex) {
        val rinfo = bank.io.rinfo(0)
        rinfo.request := distance(io.destinationBase, index) < io.destinationCount
        rinfo.rd := localAddress(io.destinationBase, index)
        for (source <- 0 until 3) {
            rinfo.src(source) := localAddress(io.sourceBase(source), index)
            rinfo.srcValid(source) := distance(io.sourceBase(source), index) < io.sourceCount(source)
        }
        // The fourth shared RAT read captures v0 before a masked destination write.
        rinfo.src(3) := 0.U
        rinfo.srcValid(3) := (index == 0).B
        bank.io.prepare := 1.U
        bank.io.restore := io.restore

        val preview = bank.io.preview(0)
        val selection = groupSelection(io.preview.bits, index)
        preview.valid := io.preview.valid && selection.reduce(_ || _)
        preview.bits.rd := localAddress(io.preview.bits.base, index)
        preview.bits.prd := Mux1H(selection, io.preview.bits.physical.map(_(5, 3)))
        bank.io.writeback(0).valid := io.accept && preview.valid
        bank.io.writeback(0).bits := preview.bits

        for (port <- 0 until 3) {
            val retirement = io.retire(port)
            val hits = groupSelection(retirement.bits, index)
            val commit = bank.io.commit(port)
            commit.valid := retirement.valid && hits.reduce(_ || _)
            commit.bits.rd := localAddress(retirement.bits.base, index)
            commit.bits.prd := Mux1H(hits, retirement.bits.physical.map(_(5, 3)))
            commit.bits.pprd := Mux1H(hits, retirement.bits.previous.map(_(5, 3)))
        }
    }
    for (source <- 0 until 3; element <- 0 until 8) {
        val index = (io.sourceBase(source) + element.U)(2, 0)
        val tags = VecInit(banks.zipWithIndex.map { case (bank, i) =>
            Cat(bank.io.pinfo(0).prs(source), i.U(3.W))
        })
        io.source(source)(element) := tags(index)
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
    when(io.accept) {
        assert(io.preview.valid && !io.restore)
        assert(io.preview.bits.base +& io.preview.bits.count <= 32.U)
        for (i <- 0 until 8) when(i.U < io.preview.bits.count) {
            assert(io.preview.bits.physical(i)(2, 0) === (io.preview.bits.base + i.U)(2, 0))
        }
    }
    assert(io.destinationCount <= 8.U)
    assert(io.destinationBase +& io.destinationCount <= 32.U)
    for (count <- io.sourceCount) assert(count <= 8.U)
}
