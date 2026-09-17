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
    def compactWidth(entries: Int, banks: Int): Int = {
        require(entries % banks == 0)
        1 + log2Ceil(entries / banks) + log2Ceil(banks)
    }

    def encode(index: ClusterEntry, entries: Int, banks: Int, width: Int): UInt = {
        val rows = entries / banks
        require(width >= compactWidth(entries, banks))
        Cat(index.high, OHToUInt(index.offset), OHToUInt(index.qidx)).pad(width)
    }

    def decode(value: UInt, entries: Int, banks: Int): ClusterEntry = {
        val rows = entries / banks
        val bankWidth = log2Ceil(banks)
        val rowWidth = log2Ceil(rows)
        val result = Wire(new ClusterEntry(rows, banks))
        result.qidx := UIntToOH(value(bankWidth - 1, 0), banks)
        result.offset := UIntToOH(value(bankWidth + rowWidth - 1, bankWidth), rows)
        result.high := value(bankWidth + rowWidth)
        result
    }
}
