import chisel3._
import chisel3.util._

class RatWrite(width: Int) extends Bundle {
    val addr = UInt(5.W)
    val data = UInt(width.W)
}

class SRatIO(width: Int, readers: Int, renameWidth: Int, commitWidth: Int) extends Bundle {
    val readAddr = Input(Vec(readers, UInt(5.W)))
    val readData = Output(Vec(readers, UInt(width.W)))
    val rename = Input(Vec(renameWidth, Valid(new RatWrite(width))))
    val commit = Input(Vec(commitWidth, Valid(new RatWrite(width))))
    val restore = Input(Bool())
    val pra = Output(UInt(width.W))
}

/** Zircon-2024 SRat: identity initialization, speculative/committed maps and
  * parallel committed-map restore. A restore includes commits accepted on the same
  * edge, matching the free list's post-commit tail. Domain type is implicit.
  */
class SRat(
    width: Int,
    readers: Int,
    renameWidth: Int,
    commitWidth: Int,
    hasZero: Boolean
) extends Module {
    val io = IO(new SRatIO(width, readers, renameWidth, commitWidth))
    val ratRnm = RegInit(VecInit.tabulate(32)(i => i.U(width.W)))
    val ratCmt = RegInit(VecInit.tabulate(32)(i => i.U(width.W)))

    // x0 is constant in either implementation. f0 remains an ordinary mapping.
    if (hasZero) { ratRnm(0) := 0.U; ratCmt(0) := 0.U }
    val first = if (hasZero) 1 else 0
    def writeRows(table: Vec[UInt], ports: Vec[ValidIO[RatWrite]]): Unit = {
        for (row <- first until 32) {
            val hits = ports.map(p => p.valid && p.bits.addr === row.U)
            val winners = hits.indices.map(i => hits(i) && !hits.drop(i + 1).foldLeft(false.B)(_ || _))
            when(hits.reduce(_ || _)) { table(row) := Mux1H(winners, ports.map(_.bits.data)) }
        }
    }
    writeRows(ratRnm, io.rename)
    writeRows(ratCmt, io.commit)

    when(io.restore) {
        for (row <- first until 32) {
            val hits = io.commit.map(port => port.valid && port.bits.addr === row.U)
            val winners = hits.indices.map(index => hits(index) && !hits.drop(index + 1).foldLeft(false.B)(_ || _))
            ratRnm(row) := Mux(hits.reduce(_ || _), Mux1H(winners, io.commit.map(_.bits.data)), ratCmt(row))
        }
    }
    io.readAddr.zip(io.readData).foreach { case (addr, data) =>
        data := Mux1H((0 until 32).map(i => (addr === i.U) -> ratRnm(i)))
    }
    io.pra := RegNext(ratCmt(1), 0.U)
}
