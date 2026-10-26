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
    if (gen.isInstanceOf[Bool] && resetVal.nonEmpty && wport > 0) {
        // Cache metadata is a bit array. Keep it packed and update selected bits;
        // later write ports retain their original same-address priority.
        val bits = RegInit(Fill(depth, resetVal.get.asUInt))
        val masks = (0 until wport).map(port =>
            Mux(io.wen.asInstanceOf[Vec[Bool]](port),
                UIntToOH(io.waddr(port), depth), 0.U(depth.W)))
        val written = masks.reduce(_ | _)
        val set = masks.indices.map { port =>
            val later = masks.drop(port + 1).foldLeft(0.U(depth.W))(_ | _)
            masks(port) & ~later & Fill(depth, io.wdata(port).asUInt)
        }.reduce(_ | _)
        bits := (bits & ~written) | set
        for (port <- 0 until rport) io.rdata(port) := bits(io.raddr(port)).asTypeOf(gen)
    } else {
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
}
