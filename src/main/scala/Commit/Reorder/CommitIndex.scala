import chisel3._
import chisel3.util._

/** Compact external identity for a banked ordered queue.
  *
  * ClusterIndexFIFO uses one-hot bank and row selectors internally. Backend
  * packages instead carry generation, binary row and binary bank. Conversion is
  * only wiring plus encoders; it never introduces division by a non-power-of-two
  * bank count.
  */
object CommitIndex {
    def addressWidth(entries: Int): Int = log2Ceil(entries)

    def compactWidth(entries: Int, banks: Int): Int = {
        require(entries % banks == 0)
        addressWidth(entries)
    }

    def encode(
        index: ClusterEntry,
        entries: Int,
        banks: Int,
        width: Int,
        simulationLint: Boolean = false,
    ): UInt = {
        val rows = entries / banks
        require(width >= compactWidth(entries, banks))
        def oneHotIndex(value: UInt, count: Int): UInt = {
            assert(PopCount(value) === 1.U, "Commit queue index must remain one-hot")
            val encoded = Mux1H(value.asBools.zipWithIndex.map { case (selected, position) =>
                selected -> position.U(log2Ceil(count).W)
            })
            if (simulationLint) Mux(value(0), 0.U, encoded) else encoded
        }
        val bank = oneHotIndex(index.qidx, banks)
        val row = oneHotIndex(index.offset, rows)
        Cat(row, bank).pad(width)
    }

    def decode(value: UInt, entries: Int, banks: Int): ClusterEntry = {
        val rows = entries / banks
        val bankWidth = log2Ceil(banks)
        val rowWidth = log2Ceil(rows)
        val result = Wire(new ClusterEntry(rows, banks))
        result.qidx := VecInit.tabulate(banks)(bank => value(bankWidth - 1, 0) === bank.U).asUInt
        result.offset := VecInit.tabulate(rows)(row =>
            value(bankWidth + rowWidth - 1, bankWidth) === row.U
        ).asUInt
        result.high := value(bankWidth + rowWidth)
        result
    }

    /** Decode a physical ROB slot. The generation bit is irrelevant to SRAM access. */
    def decodeAddress(value: UInt, entries: Int, banks: Int): ClusterEntry = {
        val rows = entries / banks
        val bankWidth = log2Ceil(banks)
        val rowWidth = log2Ceil(rows)
        require(value.getWidth == addressWidth(entries))
        val result = Wire(new ClusterEntry(rows, banks))
        result.qidx := VecInit.tabulate(banks)(bank => value(bankWidth - 1, 0) === bank.U).asUInt
        result.offset := VecInit.tabulate(rows)(row =>
            value(bankWidth + rowWidth - 1, bankWidth) === row.U
        ).asUInt
        result.high := false.B
        result
    }
}
