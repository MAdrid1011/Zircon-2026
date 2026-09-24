import chisel3._
import chisel3.util._

object Shifter {
    class ShifterIO(n: Int) extends Bundle {
        val src = Input(UInt(n.W))
        val shf = Input(UInt(log2Ceil(n).W))
        val sgn = Input(Bool())
        val res = Output(UInt(n.W))
    }

    class Shifter extends Module {
        val io = IO(new ShifterIO(32))
        val fill = io.sgn && io.src(31)
        val stages = (0 until 5).foldLeft(io.src) { case (value, bit) =>
            val amount = 1 << bit
            val shifted = Cat(Fill(amount, fill), value(31, amount))
            Mux(io.shf(bit), shifted, value)
        }
        io.res := stages
    }

    object Shifter {
        def apply(src: UInt, shf: UInt, sgn: Bool): Shifter = {
            val shifter = Module(new Shifter)
            shifter.io.src := src
            shifter.io.shf := shf
            shifter.io.sgn := sgn
            shifter
        }
    }

}
