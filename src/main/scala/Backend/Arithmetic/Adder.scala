import chisel3._
import chisel3.util._

// select a kind of Adder
class AdderIO(n: Int, carryOut: Boolean = true) extends Bundle {
    val src1 = Input(UInt(n.W))
    val src2 = Input(UInt(n.W))
    val cin = Input(UInt(1.W))
    val res = Output(UInt(n.W))
    val cout = if (carryOut) Some(Output(UInt(1.W))) else None
}

object BLevelCarry4 {
    def apply(p: UInt, g: UInt, c: UInt): (UInt, UInt, UInt) = {
        assert(p.getWidth == 4, "p must be 4 bits wide")
        assert(g.getWidth == 4, "g must be 4 bits wide")
        assert(c.getWidth == 1, "c must be 1 bits wide")
        val pn = p.andR
        val gn = g(3) | (p(3) & g(2)) | (p(3) & p(2) & g(1)) | (p(3) & p(2) & p(1) & g(0))
        val cn = Wire(Vec(4, UInt(1.W)))
        cn(0) := g(0) | (p(0) & c)
        cn(1) := g(1) | (p(1) & g(0)) | (p(1) & p(0) & c)
        cn(2) := g(2) | (p(2) & g(1)) | (p(2) & p(1) & g(0)) | (p(2) & p(1) & p(0) & c)
        cn(3) := gn | (pn & c)
        (pn, gn, cn.asUInt)
    }
}

class BLevelAdder4 extends RawModule {
    val io = IO(new AdderIO(4))
    // The carry network consumes propagate/generate bits, not the raw operands.
    val (_, _, c) = BLevelCarry4(io.src1 | io.src2, io.src1 & io.src2, io.cin)
    io.res := io.src1 ^ io.src2 ^ (c(2, 0) ## io.cin)
    io.cout.get := c.asUInt(3)
}

class BLevelAdder5 extends RawModule {
    val io = IO(new AdderIO(5))
    val adder4 = BLevelAdder4(io.src1(3, 0), io.src2(3, 0), io.cin)
    io.res := (adder4.io.cout.get ^ io.src1(4) ^ io.src2(4)) ## adder4.io.res
    io.cout.get := (adder4.io.cout.get & io.src1(4)) | (adder4.io.cout.get & io.src2(4)) |
        (io.src1(4) & io.src2(4))
}

class BLevelPAdder32(carryOut: Boolean = true) extends RawModule {
    val io = IO(new AdderIO(32, carryOut))
    val pi = io.src1 | io.src2;
    val gi = io.src1 & io.src2;

    val p = Wire(MixedVec(Vec(8, UInt(1.W)), Vec(2, UInt(1.W))))
    val g = Wire(MixedVec(Vec(8, UInt(1.W)), Vec(2, UInt(1.W))))
    val c = Wire(MixedVec(Vec(8, UInt(4.W)), Vec(2, UInt(4.W)), Vec(1, UInt(4.W))))

    for (i <- 0 until 8) {
        val cin = if (i == 0) io.cin else if (i == 4) c(2).asUInt(0) else c(1).asUInt(i - 1)
        val (p0n, g0n, c0n) = BLevelCarry4(pi(i * 4 + 3, i * 4), gi(i * 4 + 3, i * 4), cin)
        p(0)(i) := p0n
        g(0)(i) := g0n
        c(0)(i) := c0n
    }
    for (i <- 0 until 2) {
        val cin = if (i == 0) io.cin else c(2).asUInt(i - 1)
        val (p1n, g1n, c1n) = BLevelCarry4(p(0).asUInt(i * 4 + 3, i * 4), g(0).asUInt(i * 4 + 3, i * 4), cin)
        p(1)(i) := p1n
        g(1)(i) := g1n
        c(1)(i) := c1n
    }

    val (p2n, g2n, c2n) = BLevelCarry4(0.U(2.W) ## p(1).asUInt, 0.U(2.W) ## g(1).asUInt, io.cin)
    c(2)(0) := c2n
    io.res := io.src1 ^ io.src2 ^ (c(0).asUInt(30, 0) ## io.cin)
    io.cout.foreach(_ := c(0).asUInt(31))
}

class BLevelPAdder64(carryOut: Boolean = true) extends RawModule {
    val io = IO(new AdderIO(64, carryOut))
    val pi = io.src1 | io.src2;
    val gi = io.src1 & io.src2;

    val p = Wire(MixedVec(Vec(16, UInt(1.W)), Vec(4, UInt(1.W))))
    val g = Wire(MixedVec(Vec(16, UInt(1.W)), Vec(4, UInt(1.W))))
    val c = Wire(MixedVec(Vec(16, UInt(4.W)), Vec(4, UInt(4.W)), Vec(1, UInt(4.W))))

    for (i <- 0 until 16) {
        val cin = if (i == 0) io.cin else if (i % 4 == 0) c(2).asUInt(i / 4 - 1) else c(1).asUInt(i - 1)
        val (p0n, g0n, c0n) = BLevelCarry4(pi(i * 4 + 3, i * 4), gi(i * 4 + 3, i * 4), cin)
        p(0)(i) := p0n
        g(0)(i) := g0n
        c(0)(i) := c0n
    }
    for (i <- 0 until 4) {
        val cin = if (i == 0) io.cin else c(2).asUInt(i - 1)
        val (p1n, g1n, c1n) = BLevelCarry4(p(0).asUInt(i * 4 + 3, i * 4), g(0).asUInt(i * 4 + 3, i * 4), cin)
        p(1)(i) := p1n
        g(1)(i) := g1n
        c(1)(i) := c1n
    }

    val (p2n, g2n, c2n) = BLevelCarry4(p(1).asUInt, g(1).asUInt, io.cin)
    c(2)(0) := c2n
    io.res := io.src1 ^ io.src2 ^ (c(0).asUInt(62, 0) ## io.cin)
    io.cout.foreach(_ := c(0).asUInt(63))
}

class BLevelPAdder33 extends RawModule {
    val io = IO(new AdderIO(33))
    val adder32 = BLevelPAdder32(io.src1(31, 0), io.src2(31, 0), io.cin)
    io.res := (adder32.io.cout.get ^ io.src1(32) ^ io.src2(32)) ## adder32.io.res
    io.cout.get := (adder32.io.cout.get & io.src1(32)) | (adder32.io.cout.get & io.src2(32)) |
        (io.src1(32) & io.src2(32))
}

class BLevelPAdder36(carryOut: Boolean = true) extends RawModule {
    val io = IO(new AdderIO(36, carryOut))
    val pi = io.src1 | io.src2
    val gi = io.src1 & io.src2
    val p0 = Wire(Vec(9, Bool()))
    val g0 = Wire(Vec(9, Bool()))
    val c0 = Wire(Vec(9, UInt(4.W)))
    val p1 = Wire(Vec(3, Bool()))
    val g1 = Wire(Vec(3, Bool()))
    val c1 = Wire(Vec(3, UInt(4.W)))

    for (i <- 0 until 9) {
        val cin = if (i == 0) io.cin else if (i % 4 == 0) c1(i / 4 - 1)(3) else c1(i / 4)(i % 4 - 1)
        val (pn, gn, cn) = BLevelCarry4(pi(i * 4 + 3, i * 4), gi(i * 4 + 3, i * 4), cin)
        p0(i) := pn
        g0(i) := gn
        c0(i) := cn
    }
    val groupP = Seq(p0.asUInt(3, 0), p0.asUInt(7, 4), Cat(0.U(3.W), p0(8)))
    val groupG = Seq(g0.asUInt(3, 0), g0.asUInt(7, 4), Cat(0.U(3.W), g0(8)))
    for (i <- 0 until 3) {
        val cin = if (i == 0) io.cin else c1(i - 1)(3)
        val (pn, gn, cn) = BLevelCarry4(groupP(i), groupG(i), cin)
        p1(i) := pn
        g1(i) := gn
        c1(i) := cn
    }

    io.res := io.src1 ^ io.src2 ^ Cat(c0.asUInt(34, 0), io.cin)
    io.cout.foreach(_ := c0(8)(3))
}

object BLevelAdder4 {
    def apply(src1: UInt, src2: UInt, cin: UInt): BLevelAdder4 = {
        val adder = Module(new BLevelAdder4)
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder
    }
}

object BLevelAdder5 {
    def apply(src1: UInt, src2: UInt, cin: UInt): BLevelAdder5 = {
        val adder = Module(new BLevelAdder5)
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder
    }
}

object BLevelPAdder32 {
    def apply(src1: UInt, src2: UInt, cin: UInt): BLevelPAdder32 = {
        val adder = Module(new BLevelPAdder32)
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder
    }

    def sum(src1: UInt, src2: UInt, cin: UInt): UInt = {
        val adder = Module(new BLevelPAdder32(carryOut = false))
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder.io.res
    }
}

object BLevelPAdder33 {
    def apply(src1: UInt, src2: UInt, cin: UInt): BLevelPAdder33 = {
        val adder = Module(new BLevelPAdder33)
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder
    }
}

object BLevelPAdder64 {
    def apply(src1: UInt, src2: UInt, cin: UInt): BLevelPAdder64 = {
        val adder = Module(new BLevelPAdder64)
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder
    }

    def sum(src1: UInt, src2: UInt, cin: UInt): UInt = {
        val adder = Module(new BLevelPAdder64(carryOut = false))
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder.io.res
    }
}

object BLevelPAdder36 {
    def sum(src1: UInt, src2: UInt, cin: UInt): UInt = {
        val adder = Module(new BLevelPAdder36(carryOut = false))
        adder.io.src1 := src1
        adder.io.src2 := src2
        adder.io.cin := cin
        adder.io.res
    }
}
