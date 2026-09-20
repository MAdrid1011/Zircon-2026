import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig._

class DecoderTestTop(support: DecodeSupport) extends Module {
    val p = FrontendParams()
    val io = IO(new Bundle {
        val inst = Input(UInt(32.W))
        val fault = Input(Bool())
        val exception = Input(new FrontendException)
        val out = Output(new FrontendInstruction(p))
    })
    val fields = Module(new PredecodeFields)
    val registers = Module(new RegisterInfoDecoder)
    val decoder = Module(new Decoder(p, support))
    fields.io.inst := io.inst
    registers.io.inst := io.inst
    registers.io.fields := fields.io.fields
    val packet = WireDefault(0.U.asTypeOf(new FrontendInstruction(p)))
    packet.inst := io.inst
    packet.rinfo := registers.io.rinfo
    packet.pc := "hfffffffc".U
    packet.predictedValue := "h80001234".U
    packet.kind := 7.U
    packet.predictedTaken := true.B
    packet.fault := io.fault
    packet.exception := io.exception
    // Stale controls must be replaced, including every unused field.
    packet.fu := 15.U
    packet.op := 31.U
    packet.src1Sel := 3.U
    packet.src2Imm := true.B
    packet.imm := "hdeadbeef".U
    packet.rm := 6.U
    packet.aq := true.B
    packet.rl := true.B
    decoder.io.in := packet
    io.out := decoder.io.out
}

class DecoderPassthroughTestTop extends Module {
    private val p = FrontendParams()
    val io = IO(new DecoderIO(p))
    val decoder = Module(new Decoder(p))
    decoder.io.in := io.in
    io.out := decoder.io.out
}

class DecoderSpec extends AnyFreeSpec with ChiselSim {
    import DecoderReference._

    "all 96 rows match pinned RISC-V encodings and reject ambiguous tables during elaboration" in {
        val rows = DecodeTable.entries
        assert(encodings.size == 96 && controls.size == 96 && rows.size == 96)
        assert(rows.map(_.name).toSet == encodings.map(_.name).toSet)
        assert(controls.keySet == encodings.map(_.name).toSet)
        val byName = encodings.map(e => e.name -> e).toMap
        for (row <- rows) {
            val reference = byName(row.name)
            assert(row.pattern.mask == reference.mask && row.pattern.value == reference.value, row.name)
            val c = controls(row.name)
            assert(row.fu == c.fu && row.op == c.op, row.name)
            assert(row.src == c.src.map(fp => if (fp) 2 else 1), row.name)
            assert(row.dest == c.dest.map(fp => if (fp) 2 else 1).getOrElse(0), row.name)
        }
        DecodeTable.validate(rows.reverse)
        val first = rows.head
        intercept[IllegalArgumentException] { DecodeTable.validate(Seq(first, first.copy(name = "ALIAS"))) }
        intercept[IllegalArgumentException] { DecodeTable.validate(Seq(first, rows(1).copy(name = first.name))) }
        intercept[IllegalArgumentException] { DecodeTable.validate(Seq(first.copy(pattern = BitPat("b?")))) }
        intercept[IllegalArgumentException] { DecodeTable.validate(Seq(first.copy(op = 31))) }
        intercept[IllegalArgumentException] { DecodeSupport(Set(15)) }
        intercept[IllegalArgumentException] { DecodeSupport(Set(DecodeUnit.ALU), systemOps = Set(SystemOp.CSRRW)) }
        intercept[IllegalArgumentException] { DecodeSupport(Set(DecodeUnit.System), systemOps = Set(31)) }
    }

    private def exercise(support: DecodeSupport, full: Boolean): Unit = {
        simulate(new DecoderTestTop(support)) { d =>
            d.io.fault.poke(false)
            d.io.exception.valid.poke(false)
            d.io.exception.cause.poke(0)
            d.io.exception.tval.poke(0)
            d.reset.poke(true); d.clock.step(); d.reset.poke(false)
            var checked = 0
            val seen = scala.collection.mutable.Set.empty[String]
            def check(word: BigInt): Unit = {
                val inst = word & wordMask
                val semantic = decode(inst)
                val allowed = semantic.filter { case (_, c) =>
                    full || (support.units(c.fu) && (!c.floating || support.floatingState) &&
                        (c.fu != DecodeUnit.System || support.systemOps(c.op)))
                }
                val c = allowed.map(_._2)
                d.io.inst.poke(inst)
                val clue = s"inst=0x${inst.toString(16)} ${semantic.map(_._1.name).getOrElse("ILLEGAL")}"
                d.io.out.fu.expect(c.map(_.fu).getOrElse(0), clue)
                d.io.out.op.expect(c.map(_.op).getOrElse(0), clue)
                d.io.out.src1Sel.expect(c.map(_.src1).getOrElse(0), clue)
                d.io.out.src2Imm.expect(c.exists(_.src2Imm).B, clue)
                d.io.out.imm.expect(c.map(x => immediate(x.immediate, inst)).getOrElse(BigInt(0)), clue)
                d.io.out.rm.expect(if (c.exists(_.rounding)) (inst >> 12) & 7 else BigInt(0), clue)
                d.io.out.aq.expect((c.exists(_.atomic) && inst.testBit(26)).B, clue)
                d.io.out.rl.expect((c.exists(_.atomic) && inst.testBit(25)).B, clue)
                d.io.out.exception.valid.expect(allowed.isEmpty.B, clue)
                d.io.out.exception.cause.expect(if (allowed.isEmpty) 2 else 0, clue)
                d.io.out.exception.tval.expect(if (allowed.isEmpty) inst else BigInt(0), clue)
                d.io.out.inst.expect(inst)
                d.io.out.pc.expect(BigInt("fffffffc", 16))
                d.io.out.predictedValue.expect(BigInt("80001234", 16))
                d.io.out.kind.expect(7)
                d.io.out.predictedTaken.expect(true)
                d.io.out.fault.expect(false)
                // Compare PD's shallow operand rules even for a statically disabled legal instruction.
                semantic.foreach { case (encoding, control) =>
                    seen += encoding.name
                    val indices = Seq(((inst >> 15) & 31).toInt, ((inst >> 20) & 31).toInt, ((inst >> 27) & 31).toInt)
                    for (i <- 0 until 3) {
                        val used = i < control.src.size
                        val fp = used && control.src(i)
                        d.io.out.rinfo.src(i).valid.expect((used && (fp || indices(i) != 0)).B, clue)
                        if (used) {
                            d.io.out.rinfo.src(i).index.expect(indices(i), clue)
                            d.io.out.rinfo.src(i).isFp.expect(fp.B, clue)
                        }
                    }
                    val rd = ((inst >> 7) & 31).toInt
                    d.io.out.rinfo.dest.valid.expect(control.dest.exists(fp => fp || rd != 0).B, clue)
                    control.dest.foreach { fp =>
                        d.io.out.rinfo.dest.index.expect(rd, clue)
                        d.io.out.rinfo.dest.isFp.expect(fp.B, clue)
                    }
                }
                checked += 1
            }
            val random = new scala.util.Random(20260913)
            for (e <- encodings) {
                check(e.value)
                check(e.value | (wordMask ^ e.mask))
                for (_ <- 0 until 10) check(e.value | (BigInt(32, random) & ~e.mask))
                if (full) for (bit <- 0 until 32) check(e.value ^ (BigInt(1) << bit))
            }
            assert(seen == encodings.map(_.name).toSet)
            if (full) {
                // Exhaustive OP and OP-IMM upper functions, including reserved shift encodings.
                for (opcode <- Seq(0x13, 0x33); f7 <- 0 until 128; f3 <- 0 until 8)
                    check((BigInt(f7) << 25) | (31 << 20) | (1 << 15) | (f3 << 12) | (31 << 7) | opcode)
                // All FP functions/rm values, with all rs2 encodings for constrained unary families.
                for (f7 <- 0 until 128; f3 <- 0 until 8; rs2 <- Seq(0, 1, 2, 31))
                    check((BigInt(f7) << 25) | (rs2 << 20) | (f3 << 12) | 0x53)
                for (f7 <- Seq(44, 96, 104, 112, 120); f3 <- 0 until 8; rs2 <- 0 until 32)
                    check((BigInt(f7) << 25) | (rs2 << 20) | (f3 << 12) | 0x53)
                for (opcode <- Seq(0x43, 0x47, 0x4b, 0x4f); fmt <- 0 until 4; rm <- 0 until 8; rs3 <- Seq(0, 31))
                    check((BigInt(rs3) << 27) | (fmt << 25) | (rm << 12) | opcode)
                // LR must have rs2=0; aq/rl never become part of the address offset.
                for (f5 <- 0 until 32; order <- 0 until 4; f3 <- 0 until 8; rs2 <- Seq(0, 1))
                    check((BigInt(f5) << 27) | (order << 25) | (rs2 << 20) | (f3 << 12) | 0x2f)
                for (
                    opcode <- Seq(0x03, 0x23, 0x07, 0x27, 0x63, 0x67, 0x73, 0x0f); f3 <- 0 until 8;
                    imm <- Seq(0, 1, 0x7ff, 0x800, 0xfff); reg <- Seq(0, 31)
                )
                    check((BigInt(imm) << 20) | (reg << 15) | (f3 << 12) | (reg << 7) | opcode)
                for (_ <- 0 until 3000) check(BigInt(32, random))
            }
            info(s"Validated $checked decoder vectors; ${seen.size} instruction semantics; full=$full")
        }
    }

    "single-lane target decode covers integer, floating, atomic, system and illegal boundaries" in {
        exercise(DecodeSupport.target, full = true)
    }
    "default decode accepts all connected backend operations and rejects absent consumers" in {
        exercise(DecodeSupport.current, full = false)
    }

    "incoming faults dominate decode and all non-decode instruction fields pass through" in {
        simulate(new DecoderPassthroughTestTop) { d =>
            d.io.in.pc.poke(0x80001004L)
            d.io.in.kind.poke(6)
            d.io.in.predictedTaken.poke(true)
            d.io.in.predictedValue.poke(0x81234567L)
            d.io.in.fu.poke(15); d.io.in.op.poke(31); d.io.in.src1Sel.poke(3)
            d.io.in.src2Imm.poke(true); d.io.in.imm.poke(0xffffffffL)
            d.io.in.rm.poke(6); d.io.in.aq.poke(true); d.io.in.rl.poke(true)
            for ((operand, i) <- (d.io.in.rinfo.src.toSeq :+ d.io.in.rinfo.dest).zipWithIndex) {
                operand.index.poke(i * 7)
                operand.isFp.poke(i % 2 == 0)
                operand.valid.poke(i != 1)
            }
            for (
                fault <- Seq(false, true); prior <- Seq(false, true); cause <- Seq(0, 1, 12, 31);
                word <- Seq(0x00100093L, 0L, 0xffffffffL, 0x00000053L)
            ) {
                d.io.in.inst.poke(word)
                d.io.in.fault.poke(fault)
                d.io.in.exception.valid.poke(prior)
                d.io.in.exception.cause.poke(cause)
                d.io.in.exception.tval.poke(0x87654321L)
                val expectedFu = if (word == 0x00100093L) DecodeUnit.ALU
                else if (word == 0x00000053L) DecodeUnit.Multiply
                else DecodeUnit.None
                val illegal = !fault && !prior && expectedFu == DecodeUnit.None
                d.io.out.fu.expect(if (!fault && !prior) expectedFu else DecodeUnit.None)
                d.io.out.exception.valid.expect(fault || prior || illegal)
                d.io.out.exception.cause.expect(if (fault) 12 else if (illegal) 2 else cause)
                d.io.out.exception.tval.expect(if (fault) 0x80001004L else if (illegal) word else 0x87654321L)
                d.io.out.fault.expect(fault)
                d.io.out.pc.expect(0x80001004L)
                d.io.out.inst.expect(word)
                d.io.out.kind.expect(6)
                d.io.out.predictedTaken.expect(true)
                d.io.out.predictedValue.expect(0x81234567L)
                for ((operand, i) <- (d.io.out.rinfo.src.toSeq :+ d.io.out.rinfo.dest).zipWithIndex) {
                    operand.index.expect(i * 7)
                    operand.isFp.expect(i % 2 == 0)
                    operand.valid.expect(i != 1)
                }
                if (fault || prior || illegal) {
                    d.io.out.op.expect(0); d.io.out.imm.expect(0); d.io.out.rm.expect(0)
                    d.io.out.src1Sel.expect(0); d.io.out.src2Imm.expect(false)
                    d.io.out.aq.expect(false); d.io.out.rl.expect(false)
                }
            }
        }
    }
}
