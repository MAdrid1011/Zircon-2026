import scala.io.Source

/** Independent encoding data and literal backend contracts, never production table outputs. */
object DecoderReference {
    case class Control(
        fu: Int,
        op: Int,
        src: Seq[Boolean] = Seq.empty,
        dest: Option[Boolean] = None,
        immediate: String = "none",
        src1: Int = 0,
        src2Imm: Boolean = false,
        rounding: Boolean = false,
        atomic: Boolean = false,
    ) {
        def floating: Boolean = src.contains(true) || dest.contains(true)
    }
    case class Encoding(name: String, mask: BigInt, value: BigInt) {
        def matches(inst: BigInt): Boolean = (inst & mask) == value
    }
    val wordMask: BigInt = (BigInt(1) << 32) - 1
    private val fixed = "([0-9]+)(?:\\.\\.([0-9]+))?=(0x[0-9a-fA-F]+|[0-9]+)".r
    val encodings: Seq[Encoding] = Seq(
        "rv_i",
        "rv32_i",
        "rv_m",
        "rv_a",
        "rv_f",
        "rv_zicsr",
        "rv_zifencei",
        "rv_system"
    ).flatMap { file =>
        val source = Source.fromResource(s"decode/riscv-opcodes/$file")
        try source.getLines().flatMap { line =>
                val tokens = line.takeWhile(_ != '#').trim.split("\\s+").toSeq
                val ordinary = tokens.headOption.exists(s => s.nonEmpty && !s.startsWith("$"))
                val shift = file == "rv32_i" && tokens.headOption.contains("$pseudo_op") &&
                    tokens.size > 2 && Set("slli", "srli", "srai")(tokens(2))
                if (!ordinary && !shift) None
                else {
                    val name = tokens(if (shift) 2 else 0).toUpperCase.replace('.', '_')
                    val assignments = tokens.collect { case fixed(hi, lo, number) =>
                        val low = Option(lo).map(_.toInt).getOrElse(hi.toInt)
                        val value = if (number.startsWith("0x")) BigInt(number.drop(2), 16) else BigInt(number)
                        (((BigInt(1) << (hi.toInt - low + 1)) - 1) << low, value << low)
                    }
                    Some(Encoding(
                        name,
                        assignments.map(_._1).foldLeft(BigInt(0))(_ | _),
                        assignments.map(_._2).foldLeft(BigInt(0))(_ | _)
                    ))
                }
            }.toVector
        finally source.close()
    }

    /* Literal Micro-operation Expectations */
    private val intReg = Seq(
        "ADD" -> 0,
        "SUB" -> 8,
        "SLL" -> 1,
        "SLT" -> 2,
        "SLTU" -> 3,
        "XOR" -> 4,
        "SRL" -> 5,
        "SRA" -> 13,
        "OR" -> 6,
        "AND" -> 7
    )
        .map { case (name, op) => name -> Control(1, op, Seq(false, false), Some(false)) }
    private val intImm = Seq(
        "ADDI" -> 0,
        "SLTI" -> 2,
        "SLTIU" -> 3,
        "XORI" -> 4,
        "ORI" -> 6,
        "ANDI" -> 7,
        "SLLI" -> 1,
        "SRLI" -> 5,
        "SRAI" -> 13
    )
        .map { case (name, op) =>
            name -> Control(
                1,
                op,
                Seq(false),
                Some(false),
                immediate = if (Set("SLLI", "SRLI", "SRAI")(name)) "shift" else "i",
                src2Imm = true
            )
        }
    private val branch = Seq("BEQ" -> 24, "BNE" -> 25, "BLT" -> 28, "BGE" -> 29, "BLTU" -> 30, "BGEU" -> 31)
        .map { case (name, op) => name -> Control(2, op, Seq(false, false), immediate = "b") }
    private val load = Seq("LB" -> 0, "LH" -> 1, "LW" -> 2, "LBU" -> 4, "LHU" -> 5, "FLW" -> 2)
        .map { case (name, op) => name -> Control(6, op, Seq(false), Some(name == "FLW"), "i", src2Imm = true) }
    private val store = Seq("SB" -> 0, "SH" -> 1, "SW" -> 2, "FSW" -> 2)
        .map { case (name, op) => name -> Control(7, op, Seq(false, name == "FSW"), immediate = "s", src2Imm = true) }
    private val multiply = Seq("MUL", "MULH", "MULHSU", "MULHU").zipWithIndex
        .map { case (name, op) => name -> Control(3, op, Seq(false, false), Some(false)) }
    private val divide = Seq("DIV", "DIVU", "REM", "REMU").zipWithIndex
        .map { case (name, op) => name -> Control(4, op, Seq(false, false), Some(false)) }
    private val fpMultiply = Seq(
        "FADD_S" -> 4,
        "FSUB_S" -> 5,
        "FMUL_S" -> 6,
        "FMADD_S" -> 7,
        "FMSUB_S" -> 8,
        "FNMSUB_S" -> 9,
        "FNMADD_S" -> 10
    )
        .map { case (name, op) =>
            name -> Control(
                3,
                op,
                Seq.fill(if (op >= 7) 3 else 2)(true),
                Some(true),
                rounding = true
            )
        }
    private val fpBinary = Seq(
        "FSGNJ_S" -> 0,
        "FSGNJN_S" -> 1,
        "FSGNJX_S" -> 2,
        "FMIN_S" -> 3,
        "FMAX_S" -> 4,
        "FEQ_S" -> 5,
        "FLT_S" -> 6,
        "FLE_S" -> 7
    )
        .map { case (name, op) => name -> Control(5, op, Seq(true, true), Some(op <= 4)) }
    private val atomic = Seq(
        "LR_W" -> 2,
        "SC_W" -> 3,
        "AMOSWAP_W" -> 1,
        "AMOADD_W" -> 0,
        "AMOXOR_W" -> 4,
        "AMOAND_W" -> 12,
        "AMOOR_W" -> 8,
        "AMOMIN_W" -> 16,
        "AMOMAX_W" -> 20,
        "AMOMINU_W" -> 24,
        "AMOMAXU_W" -> 28
    )
        .map { case (name, op) =>
            name -> Control(
                8,
                op,
                Seq.fill(if (name == "LR_W") 1 else 2)(false),
                Some(false),
                atomic = true
            )
        }
    private val csr = Seq("CSRRW" -> 2, "CSRRS" -> 3, "CSRRC" -> 4, "CSRRWI" -> 5, "CSRRSI" -> 6, "CSRRCI" -> 7)
        .map { case (name, op) =>
            name -> Control(
                9,
                op,
                if (op >= 5) Seq.empty else Seq(false),
                Some(false),
                "csr",
                src2Imm = op >= 5
            )
        }
    val controls: Map[String, Control] =
        (intReg ++ intImm ++ branch ++ load ++ store ++ multiply ++ divide ++
            fpMultiply ++ fpBinary ++ atomic ++ csr ++
            Seq(
                "LUI" -> Control(1, 0, dest = Some(false), immediate = "u", src1 = 2, src2Imm = true),
                "AUIPC" -> Control(1, 0, dest = Some(false), immediate = "u", src1 = 1, src2Imm = true),
                "JAL" -> Control(2, 27, dest = Some(false), immediate = "j", src1 = 1),
                "JALR" -> Control(2, 26, Seq(false), Some(false), "i", src1 = 1),
                "FDIV_S" -> Control(4, 4, Seq(true, true), Some(true), rounding = true),
                "FSQRT_S" -> Control(4, 5, Seq(true), Some(true), rounding = true),
                "FCLASS_S" -> Control(5, 8, Seq(true), Some(false)),
                "FMV_X_W" -> Control(5, 9, Seq(true), Some(false)),
                "FMV_W_X" -> Control(5, 10, Seq(false), Some(true)),
                "FCVT_W_S" -> Control(5, 11, Seq(true), Some(false), rounding = true),
                "FCVT_WU_S" -> Control(5, 12, Seq(true), Some(false), rounding = true),
                "FCVT_S_W" -> Control(5, 13, Seq(false), Some(true), rounding = true),
                "FCVT_S_WU" -> Control(5, 14, Seq(false), Some(true), rounding = true),
                "FENCE" -> Control(9, 0, immediate = "fence"),
                "FENCE_I" -> Control(9, 1),
                "ECALL" -> Control(9, 8),
                "EBREAK" -> Control(9, 9),
                "MRET" -> Control(9, 10),
                "WFI" -> Control(9, 11),
                "SRET" -> Control(9, 12),
                "SFENCE_VMA" -> Control(9, 13, Seq(false, false)),
            )).toMap

    def decode(inst: BigInt): Option[(Encoding, Control)] = {
        val matches = encodings.filter(_.matches(inst))
        require(matches.size <= 1, s"Overlapping reference encodings for ${inst.toString(16)}")
        matches.headOption.map(e => e -> controls(e.name)).filter { case (_, c) =>
            val rm = ((inst >> 12) & 7).toInt
            !c.rounding || rm <= 4 || rm == 7
        }
    }
    def immediate(kind: String, inst: BigInt): BigInt = {
        def bits(hi: Int, lo: Int): BigInt = (inst >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)
        def signed(value: BigInt, width: Int): BigInt =
            if (value.testBit(width - 1)) value - (BigInt(1) << width) else value
        val value = kind match {
            case "i" => signed(inst >> 20, 12)
            case "s" => signed((bits(31, 25) << 5) | bits(11, 7), 12)
            case "b" => signed(
                    (bits(31, 31) << 12) | (bits(7, 7) << 11) |
                        (bits(30, 25) << 5) | (bits(11, 8) << 1),
                    13
                )
            case "u" => inst & BigInt("fffff000", 16)
            case "j" => signed(
                    (bits(31, 31) << 20) | (bits(19, 12) << 12) |
                        (bits(20, 20) << 11) | (bits(30, 21) << 1),
                    21
                )
            case "shift" => bits(24, 20)
            case "csr" => (bits(19, 15) << 12) | bits(31, 20)
            case "fence" => bits(31, 20)
            case "none" => BigInt(0)
        }
        value & wordMask
    }
    def registerWord(base: BigInt, rd: Int, rs1: Int, rs2: Int): BigInt =
        (base & ~BigInt("01ff8f80", 16)) | (BigInt(rd) << 7) | (BigInt(rs1) << 15) | (BigInt(rs2) << 20)
}
