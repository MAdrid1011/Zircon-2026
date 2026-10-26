import chisel3._
import chisel3.util._

/** Reuse an arithmetic instruction's unused RF port for a full Store word.
  * Mask/index collection and the serial engine retain exclusive ownership.
  * Only address selection and return routing change; the RF still has three
  * read ports and the collectors retain their original instruction ownership.
  */
class VectorReadArbiter extends RawModule {
    val io = IO(new Bundle {
        val memory = Input(Bool())
        val memorySingle = Input(Bool())
        val serial = Input(Bool())
        val spare = Input(UInt(2.W))
        val addresses = Input(Vec(3, Vec(3, UInt(6.W))))
        val selected = Output(Vec(3, UInt(6.W)))
        val computeAvailable = Output(Bool())
        val memoryPort = Output(UInt(2.W))
    })
    val share = io.memory && io.memorySingle && !io.serial && io.spare.orR
    val upper = !io.spare(0)
    val owners = VecInit(Seq(io.memory, !io.memory && io.serial, !io.memory && !io.serial))
    for (port <- 0 until 3) {
        val exclusive = Mux1H(owners, io.addresses.map(_(port)))
        val borrowed = if (port == 0) io.addresses(2)(port)
            else Mux(upper === (port == 2).B, io.addresses(0)(0), io.addresses(2)(port))
        io.selected(port) := Mux(share, borrowed, exclusive)
    }
    io.computeAvailable := !io.serial && (!io.memory || share)
    io.memoryPort := Mux(share, Mux(upper, 2.U, 1.U), 0.U)
}
