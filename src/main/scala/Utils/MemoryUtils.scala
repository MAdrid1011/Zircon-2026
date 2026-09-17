package ZirconUtil

import chisel3._
import chisel3.util._

// Memory helpers copied from Zircon-2024; existing arithmetic helpers stay shared.
object ShiftAdd1 {
    def apply(x: UInt): UInt = {
        val n = x.getWidth
        x(n - 2, 0) ## x(n - 1)
    }
}

object ShiftSub1 {
    def apply(x: UInt): UInt = {
        val n = x.getWidth
        x(0) ## x(n - 1, 1)
    }
}

object ShiftAddN {
    def apply(x: UInt, k: Int): UInt = {
        val n = x.getWidth
        if (k == 0) x
        else x(n - k - 1, 0) ## x(n - 1, n - k)
    }
}

object ShiftSubN {
    def apply(x: UInt, k: Int): UInt = {
        val n = x.getWidth
        if (k == 0) x
        else x(k - 1, 0) ## x(n - 1, k)
    }
}

object MTypeDecode {
    // memtype decode
    def apply(mtype: UInt, n: Int = 4): UInt = {
        val res = Wire(UInt(n.W))
        res := MuxLookup(mtype, 1.U(n.W))(Seq(
            0.U -> 0x1.U(n.W),
            1.U -> 0x3.U(n.W),
            2.U -> 0xf.U(n.W),
        ))
        res
    }
}

object MTypeEncode {
    // memtype encode
    def apply(mtype: UInt, n: Int = 2): UInt = {
        val res = Wire(UInt(n.W))
        res := MuxLookup(mtype, 0.U(n.W))(Seq(
            0x1.U -> 0.U(n.W),
            0x3.U -> 1.U(n.W),
            0xf.U -> 2.U(n.W),
        ))
        res
    }
}

object InheritFields {
    // inherit fields
    def apply[T <: Bundle, P <: Bundle](child: T, parent: P): Unit = {
        parent.elements.foreach { case (name, data) =>
            if (child.elements.contains(name)) {
                child.elements(name) := data
            }
        }
    }
}

object RotateRightOH {
    def apply(x: UInt, nOH: UInt): UInt = {
        val width = x.getWidth
        assert(width == nOH.getWidth, "two operators must have the same width")
        val xShifts = VecInit.tabulate(width)(i => ShiftSubN(x, i))
        Mux1H(nOH, xShifts)
    }
}

object RotateLeftOH {
    def apply(x: UInt, nOH: UInt): UInt = {
        val width = x.getWidth
        assert(width == nOH.getWidth, "two operators must have the same width")
        val xShifts = VecInit.tabulate(width)(i => ShiftAddN(x, i))
        Mux1H(nOH, xShifts)
    }
}

object Log2OH {
    def apply(x: Bits, width: Int): UInt = {
        if (width < 2) {
            x(0)
        } else if (width == 2) {
            Cat(x(1), (!x(1) && x(0)))
        } else if (width <= divideAndConquerThreshold) {
            Mux(x(width - 1), Cat(1.U(1.W), 0.U((width - 1).W)), Cat(0.U(1.W), apply(x(width - 2, 0), width - 1)))
        } else {
            val mid = 1 << (log2Ceil(width) - 1)
            val hi = x(width - 1, mid)
            val lo = x(mid - 1, 0)
            val usehi = hi.orR
            // Cat(usehi, Mux(usehi, apply(hi, width - mid), apply(lo, mid)))
            Mux(usehi, Cat(apply(hi, width - mid), 0.U(mid.W)), Cat(0.U((width - mid).W), apply(lo, mid)))
        }
    }
    def apply(x: Bits): UInt = apply(x, x.getWidth)
    def apply(x: Seq[Bool]): UInt = apply(VecInit(x).asUInt, x.size)
    private def divideAndConquerThreshold = 4
}

object Log2OHRev {
    def apply(x: Bits): UInt = {
        Reverse(Log2OH(Reverse(x.asUInt)))
    }
    def apply(x: Seq[Bool]): UInt = {
        apply(VecInit(x).asUInt)
    }
}
