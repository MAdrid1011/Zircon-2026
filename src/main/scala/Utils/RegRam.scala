// Adapted from Zircon-2024 b9f7b2b for the current cache RAM interface.
import chisel3._
import chisel3.util._

class AsyncRegRamIO[T <: Data](gen: T, depth: Int, wport: Int, rport: Int) extends Bundle {
    val wen = Input(Vec(wport, if (gen.isInstanceOf[Vec[_]]) UInt(gen.asInstanceOf[Vec[_]].length.W) else Bool()))
    val waddr = Input(Vec(wport, UInt(log2Ceil(depth).W)))
    val wdata = Input(Vec(wport, gen))
    val raddr = Input(Vec(rport, UInt(log2Ceil(depth).W)))
    val rdata = Output(Vec(rport, gen))
}

class AsyncRegRam[T <: Data](gen: T, depth: Int, wport: Int, rport: Int, resetVal: Option[T] = None) extends Module {
    // Vec storage supports a separate write enable for each element.
    val io = IO(new AsyncRegRamIO(gen, depth, wport, rport))
    val ram = resetVal match {
        case Some(value) => RegInit(VecInit.fill(depth)(value))
        case None => Reg(Vec(depth, gen))
    }
    for (i <- 0 until wport) {
        if (gen.isInstanceOf[Vec[_]]) {
            for (j <- 0 until gen.asInstanceOf[Vec[_]].length) {
                when(io.wen.asInstanceOf[Vec[UInt]](i)(j)) {
                    ram(io.waddr.asInstanceOf[Vec[UInt]](i)).asInstanceOf[Vec[T]](j) :=
                        io.wdata.asInstanceOf[Vec[Vec[T]]](i)(j)
                }
            }
        } else {
            when(io.wen.asInstanceOf[Vec[Bool]](i)) {
                ram(io.waddr(i)) := io.wdata.asInstanceOf[Vec[T]](i)
            }
        }
    }
    for (i <- 0 until rport) {
        io.rdata(i) := ram(io.raddr(i))
    }
}
