import chisel3._
import chisel3.util._

class RatWrite(width: Int) extends Bundle {
    val addr = UInt(5.W)
    val data = UInt(width.W)
}

class SRatIO(width: Int, readers: Int, renameWidth: Int, commitWidth: Int) extends Bundle {
    val readAddr = Input(Vec(readers, UInt(5.W)))
    val readData = Output(Vec(readers, UInt(width.W)))
    val preview = Input(Vec(renameWidth, Valid(new RatWrite(width))))
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
    def winners(ports: Seq[ValidIO[RatWrite]], row: Int): Seq[Bool] = {
        val hits = ports.map(port => port.valid && port.bits.addr === row.U)
        hits.indices.map(index => hits(index) && !hits.drop(index + 1).foldLeft(false.B)(_ || _))
    }
    def writeRows(table: Vec[UInt], ports: Seq[ValidIO[RatWrite]]): Unit = {
        for (row <- first until 32) {
            val selected = winners(ports, row)
            when(selected.reduce(_ || _)) { table(row) := Mux1H(selected, ports.map(_.bits.data)) }
        }
    }
    writeRows(ratRnm, io.rename.toSeq)
    writeRows(ratCmt, io.commit.toSeq)

    when(io.restore) {
        for (row <- first until 32) {
            val selected = winners(io.commit.toSeq, row)
            ratRnm(row) := Mux(
                selected.reduce(_ || _),
                Mux1H(selected, io.commit.map(_.bits.data)),
                ratCmt(row),
            )
        }
    }
    io.readAddr.zip(io.readData).foreach { case (addr, data) =>
        val pending = io.preview.map(port => port.valid && port.bits.addr === addr)
        val selected = pending.indices.map(index => pending(index) && !pending.drop(index + 1).foldLeft(false.B)(_ || _))
        val registered = Mux1H((0 until 32).map(i => (addr === i.U) -> ratRnm(i)))
        // A stalled Q-side group may be previewed without changing SRAT state.
        // Last lane wins when that group is actually accepted.
        data := Mux(pending.reduce(_ || _), Mux1H(selected, io.preview.map(_.bits.data)), registered)
    }
    io.pra := RegNext(ratCmt(1), 0.U)
}
