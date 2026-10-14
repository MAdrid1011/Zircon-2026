import chisel3._
import chisel3.util._
import ZirconConfig.BackendParams

/** The shared ReadyBoard's nonzero floating domain also admits vector tag zero.
  * Vector writers publish only after PRF visibility; speculative scalar Load
  * wakeups remain in the scalar domain.
  */
class VectorReadyBoard(val physicalRegisters: Int = 64) extends Module {
    require(physicalRegisters >= 56 && physicalRegisters <= 64 && physicalRegisters % 8 == 0)
    val io = IO(new Bundle {
        val allocate = Input(Valid(new VectorDestination))
        val wakeup = Input(Vec(2, Valid(UInt(6.W))))
        val ready = Output(UInt(64.W))
        val flush = Input(Bool())
    })
    val board = Module(new ReadyBoard(BackendParams(numFpPhys = physicalRegisters),
        width = 8, wakeupPorts = 2, numSources = 8))
    board.io.flush := io.flush
    board.io.speculation := 0.U.asTypeOf(new SpeculationResolution(board.p))
    for (index <- 0 until 8) {
        board.io.allocate(index).valid := io.allocate.valid && index.U < io.allocate.bits.count
        board.io.allocate(index).bits := Cat(1.U(1.W), io.allocate.bits.physical(index))
        for (source <- 0 until 8) {
            board.io.query(index).prs(source) := (64 + index * 8 + source).U
            board.io.query(index).valid(source) := (index * 8 + source < physicalRegisters).B
        }
    }
    for (port <- 0 until 2) {
        board.io.wakeup(port).prd := Mux(io.wakeup(port).valid,
            Cat(1.U(1.W), io.wakeup(port).bits), 0.U)
        board.io.wakeup(port).specMask := 0.U
    }
    io.ready := VecInit(board.io.state.toSeq.flatMap(_.ready.toSeq).take(physicalRegisters)).asUInt.pad(64)
}
