import chisel3._
import chisel3.util._

/** Exact modulo-width increment with an early carry prefix and byte adders. */
object AsicEventCounter {
    def next(current: UInt, increment: UInt): UInt = {
        val width = current.getWidth
        require(width >= 8 && width % 8 == 0)
        require(increment.getWidth <= 8)
        val low = current(7, 0) +& increment
        val pieces = Seq(low(7, 0)) ++ (8 until width by 8).map { offset =>
            val prefix = if (offset == 8) true.B else current(offset - 1, 8).andR
            val carry = low(8) && prefix
            dontTouch(carry)
            (current(offset + 7, offset) + carry.asUInt)(7, 0)
        }
        Cat(pieces.reverse)
    }
}
