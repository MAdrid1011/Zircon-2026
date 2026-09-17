package ZirconUtil

import chisel3._
import chisel3.util._

/** Width extensions used by the migrated arithmetic blocks. */
object SE {
    def apply(value: UInt, width: Int = 32): UInt = {
        require(value.getWidth <= width)
        Cat(Fill(width - value.getWidth, value(value.getWidth - 1)), value)
    }
}

object ZE {
    def apply(value: UInt, width: Int = 32): UInt = {
        require(value.getWidth <= width)
        Cat(0.U((width - value.getWidth).W), value)
    }
}

/** Zircon-2024 b9f7b2b Utils.Log2Rev, used by SRT2's leading-zero detector.
  * Index of the least-significant set bit; the all-zero result is unspecified.
  * Reverse the input for leading-zero count. Caller handles zero separately.
  */
object Log2Rev {
    def apply(x: Bits, width: Int): UInt = {
        require(width > 0 && width <= x.getWidth)
        if (width < 2) 0.U
        else if (width == 2) (x(1) && !x(0)).asUInt
        else if (width <= 4) PriorityEncoder(x(width - 1, 0))
        else {
            val mid = 1 << (log2Ceil(width) - 1)
            val hi = x(width - 1, mid)
            val lo = x(mid - 1, 0)
            val useLo = lo.orR
            Cat(!useLo, Mux(useLo, Log2Rev(lo, mid), Log2Rev(hi, width - mid)))
        }
    }
    def apply(x: Bits): UInt = apply(x, x.getWidth)
}
