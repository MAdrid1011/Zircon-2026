import chisel3._
import chisel3.util.BitPat
import ZirconConfig._

/** Named instruction semantics. Only selected control fields become hardware signals. */
case class DecodeEntry(
    name: String,
    pattern: BitPat,
    fu: Int,
    op: Int,
    src: Seq[Int] = Seq.empty,
    dest: Int = DecodeRegister.None,
    immType: Int = DecodeImmediate.None,
    src1Sel: Int = DecodeSource.Register,
    src2Imm: Boolean = false,
    rounding: Boolean = false,
    aqrl: Boolean = false,
) {
    def usesFp: Boolean = src.contains(DecodeRegister.Floating) || dest == DecodeRegister.Floating
    def supportedBy(support: DecodeSupport): Boolean =
        support.units(fu) && (!usesFp || support.floatingState) &&
            (fu != DecodeUnit.System || support.systemOps(op))
}

object DecodeTable {
    import DecodeRegister.{Integer, Floating}
    import DecodeUnit._

    /* Integer Arithmetic */
    private val integerRegister = Seq(
        ("ADD", Instructions.ADD, EXEOp.ADD),
        ("SUB", Instructions.SUB, EXEOp.SUB),
        ("SLL", Instructions.SLL, EXEOp.SLL),
        ("SLT", Instructions.SLT, EXEOp.SLT),
        ("SLTU", Instructions.SLTU, EXEOp.SLTU),
        ("XOR", Instructions.XOR, EXEOp.XOR),
        ("SRL", Instructions.SRL, EXEOp.SRL),
        ("SRA", Instructions.SRA, EXEOp.SRA),
        ("OR", Instructions.OR, EXEOp.OR),
        ("AND", Instructions.AND, EXEOp.AND),
    ).map { case (name, pattern, op) =>
        DecodeEntry(name, pattern, ALU, op.litValue.toInt, src = Seq(Integer, Integer), dest = Integer)
    }
    private val integerImmediate = Seq(
        ("ADDI", Instructions.ADDI, EXEOp.ADD, DecodeImmediate.I),
        ("SLTI", Instructions.SLTI, EXEOp.SLT, DecodeImmediate.I),
        ("SLTIU", Instructions.SLTIU, EXEOp.SLTU, DecodeImmediate.I),
        ("XORI", Instructions.XORI, EXEOp.XOR, DecodeImmediate.I),
        ("ORI", Instructions.ORI, EXEOp.OR, DecodeImmediate.I),
        ("ANDI", Instructions.ANDI, EXEOp.AND, DecodeImmediate.I),
        ("SLLI", Instructions.SLLI, EXEOp.SLL, DecodeImmediate.Shift),
        ("SRLI", Instructions.SRLI, EXEOp.SRL, DecodeImmediate.Shift),
        ("SRAI", Instructions.SRAI, EXEOp.SRA, DecodeImmediate.Shift),
    ).map { case (name, pattern, op, immediate) =>
        DecodeEntry(
            name,
            pattern,
            ALU,
            op.litValue.toInt,
            src = Seq(Integer),
            dest = Integer,
            immType = immediate,
            src2Imm = true
        )
    }
    private val upperImmediate = Seq(
        DecodeEntry(
            "LUI",
            Instructions.LUI,
            ALU,
            EXEOp.ADD.litValue.toInt,
            dest = Integer,
            immType = DecodeImmediate.U,
            src1Sel = DecodeSource.Zero,
            src2Imm = true
        ),
        DecodeEntry(
            "AUIPC",
            Instructions.AUIPC,
            ALU,
            EXEOp.ADD.litValue.toInt,
            dest = Integer,
            immType = DecodeImmediate.U,
            src1Sel = DecodeSource.PC,
            src2Imm = true
        ),
    )

    /* Control Flow */
    private val branches = Seq(
        ("BEQ", Instructions.BEQ, EXEOp.BEQ),
        ("BNE", Instructions.BNE, EXEOp.BNE),
        ("BLT", Instructions.BLT, EXEOp.BLT),
        ("BGE", Instructions.BGE, EXEOp.BGE),
        ("BLTU", Instructions.BLTU, EXEOp.BLTU),
        ("BGEU", Instructions.BGEU, EXEOp.BGEU),
    ).map { case (name, pattern, op) =>
        DecodeEntry(
            name,
            pattern,
            Branch,
            op.litValue.toInt,
            src = Seq(Integer, Integer),
            immType = DecodeImmediate.B
        )
    } ++ Seq(
        DecodeEntry(
            "JAL",
            Instructions.JAL,
            Branch,
            EXEOp.JAL.litValue.toInt,
            dest = Integer,
            immType = DecodeImmediate.J,
            src1Sel = DecodeSource.PC
        ),
        DecodeEntry(
            "JALR",
            Instructions.JALR,
            Branch,
            EXEOp.JALR.litValue.toInt,
            src = Seq(Integer),
            dest = Integer,
            immType = DecodeImmediate.I,
            src1Sel = DecodeSource.PC
        ),
    )

    /* Loads and Stores */
    private val loads = Seq(
        ("LB", Instructions.LB, 0, Integer),
        ("LH", Instructions.LH, 1, Integer),
        ("LW", Instructions.LW, 2, Integer),
        ("LBU", Instructions.LBU, 4, Integer),
        ("LHU", Instructions.LHU, 5, Integer),
        ("FLW", Instructions.FLW, 2, Floating),
    ).map { case (name, pattern, op, dest) =>
        DecodeEntry(
            name,
            pattern,
            Load,
            op,
            src = Seq(Integer),
            dest = dest,
            immType = DecodeImmediate.I,
            src2Imm = true
        )
    }
    private val stores = Seq(
        ("SB", Instructions.SB, 0, Integer),
        ("SH", Instructions.SH, 1, Integer),
        ("SW", Instructions.SW, 2, Integer),
        ("FSW", Instructions.FSW, 2, Floating),
    ).map { case (name, pattern, op, data) =>
        DecodeEntry(
            name,
            pattern,
            Store,
            op,
            src = Seq(Integer, data),
            immType = DecodeImmediate.S,
            src2Imm = true
        )
    }

    /* Existing Shared Arithmetic Units */
    private val integerMultiply = Seq(
        ("MUL", Instructions.MUL, MultiplyOp.MUL),
        ("MULH", Instructions.MULH, MultiplyOp.MULH),
        ("MULHSU", Instructions.MULHSU, MultiplyOp.MULHSU),
        ("MULHU", Instructions.MULHU, MultiplyOp.MULHU),
    ).map { case (name, pattern, op) =>
        DecodeEntry(name, pattern, Multiply, op.litValue.toInt, src = Seq(Integer, Integer), dest = Integer)
    }
    private val integerDivide = Seq(
        ("DIV", Instructions.DIV, DivideOp.DIV),
        ("DIVU", Instructions.DIVU, DivideOp.DIVU),
        ("REM", Instructions.REM, DivideOp.REM),
        ("REMU", Instructions.REMU, DivideOp.REMU),
    ).map { case (name, pattern, op) =>
        DecodeEntry(name, pattern, Divide, op, src = Seq(Integer, Integer), dest = Integer)
    }
    private val floatingMultiply = Seq(
        ("FADD_S", Instructions.FADD_S, MultiplyOp.FADD, 2),
        ("FSUB_S", Instructions.FSUB_S, MultiplyOp.FSUB, 2),
        ("FMUL_S", Instructions.FMUL_S, MultiplyOp.FMUL, 2),
        ("FMADD_S", Instructions.FMADD_S, MultiplyOp.FMADD, 3),
        ("FMSUB_S", Instructions.FMSUB_S, MultiplyOp.FMSUB, 3),
        ("FNMSUB_S", Instructions.FNMSUB_S, MultiplyOp.FNMSUB, 3),
        ("FNMADD_S", Instructions.FNMADD_S, MultiplyOp.FNMADD, 3),
    ).map { case (name, pattern, op, sources) =>
        DecodeEntry(
            name,
            pattern,
            Multiply,
            op.litValue.toInt,
            src = Seq.fill(sources)(Floating),
            dest = Floating,
            rounding = true
        )
    }
    private val floatingDivide = Seq(
        DecodeEntry(
            "FDIV_S",
            Instructions.FDIV_S,
            Divide,
            DivideOp.FDIV,
            src = Seq(Floating, Floating),
            dest = Floating,
            rounding = true
        ),
        DecodeEntry(
            "FSQRT_S",
            Instructions.FSQRT_S,
            Divide,
            DivideOp.FSQRT,
            src = Seq(Floating),
            dest = Floating,
            rounding = true
        ),
    )

    /* Future Floating Auxiliary Path */
    private val floatingBinary = Seq(
        ("FSGNJ_S", Instructions.FSGNJ_S, FpMiscOp.FSGNJ, Floating),
        ("FSGNJN_S", Instructions.FSGNJN_S, FpMiscOp.FSGNJN, Floating),
        ("FSGNJX_S", Instructions.FSGNJX_S, FpMiscOp.FSGNJX, Floating),
        ("FMIN_S", Instructions.FMIN_S, FpMiscOp.FMIN, Floating),
        ("FMAX_S", Instructions.FMAX_S, FpMiscOp.FMAX, Floating),
        ("FEQ_S", Instructions.FEQ_S, FpMiscOp.FEQ, Integer),
        ("FLT_S", Instructions.FLT_S, FpMiscOp.FLT, Integer),
        ("FLE_S", Instructions.FLE_S, FpMiscOp.FLE, Integer),
    ).map { case (name, pattern, op, dest) =>
        DecodeEntry(name, pattern, FpMisc, op, src = Seq(Floating, Floating), dest = dest)
    }
    private val floatingUnary = Seq(
        DecodeEntry(
            "FCLASS_S",
            Instructions.FCLASS_S,
            FpMisc,
            FpMiscOp.FCLASS,
            src = Seq(Floating),
            dest = Integer
        ),
        DecodeEntry(
            "FMV_X_W",
            Instructions.FMV_X_W,
            FpMisc,
            FpMiscOp.FMV_X_W,
            src = Seq(Floating),
            dest = Integer
        ),
        DecodeEntry(
            "FMV_W_X",
            Instructions.FMV_W_X,
            FpMisc,
            FpMiscOp.FMV_W_X,
            src = Seq(Integer),
            dest = Floating
        ),
        DecodeEntry(
            "FCVT_W_S",
            Instructions.FCVT_W_S,
            FpMisc,
            FpMiscOp.FCVT_W_S,
            src = Seq(Floating),
            dest = Integer,
            rounding = true
        ),
        DecodeEntry(
            "FCVT_WU_S",
            Instructions.FCVT_WU_S,
            FpMisc,
            FpMiscOp.FCVT_WU_S,
            src = Seq(Floating),
            dest = Integer,
            rounding = true
        ),
        DecodeEntry(
            "FCVT_S_W",
            Instructions.FCVT_S_W,
            FpMisc,
            FpMiscOp.FCVT_S_W,
            src = Seq(Integer),
            dest = Floating,
            rounding = true
        ),
        DecodeEntry(
            "FCVT_S_WU",
            Instructions.FCVT_S_WU,
            FpMisc,
            FpMiscOp.FCVT_S_WU,
            src = Seq(Integer),
            dest = Floating,
            rounding = true
        ),
    )

    /* Word Atomics: funct5 Is the Local Operation, Never an Address Offset */
    private val atomics = Seq(
        ("LR_W", Instructions.LR_W, 2),
        ("SC_W", Instructions.SC_W, 3),
        ("AMOSWAP_W", Instructions.AMOSWAP_W, 1),
        ("AMOADD_W", Instructions.AMOADD_W, 0),
        ("AMOXOR_W", Instructions.AMOXOR_W, 4),
        ("AMOAND_W", Instructions.AMOAND_W, 12),
        ("AMOOR_W", Instructions.AMOOR_W, 8),
        ("AMOMIN_W", Instructions.AMOMIN_W, 16),
        ("AMOMAX_W", Instructions.AMOMAX_W, 20),
        ("AMOMINU_W", Instructions.AMOMINU_W, 24),
        ("AMOMAXU_W", Instructions.AMOMAXU_W, 28),
    ).map { case (name, pattern, op) =>
        DecodeEntry(
            name,
            pattern,
            Atomic,
            op,
            src = Seq.fill(if (op == 2) 1 else 2)(Integer),
            dest = Integer,
            aqrl = true
        )
    }

    /* Ordered System Requests */
    private val csr = Seq(
        ("CSRRW", Instructions.CSRRW, SystemOp.CSRRW, false),
        ("CSRRS", Instructions.CSRRS, SystemOp.CSRRS, false),
        ("CSRRC", Instructions.CSRRC, SystemOp.CSRRC, false),
        ("CSRRWI", Instructions.CSRRWI, SystemOp.CSRRWI, true),
        ("CSRRSI", Instructions.CSRRSI, SystemOp.CSRRSI, true),
        ("CSRRCI", Instructions.CSRRCI, SystemOp.CSRRCI, true),
    ).map { case (name, pattern, op, immediate) =>
        DecodeEntry(
            name,
            pattern,
            System,
            op,
            src = if (immediate) Seq.empty else Seq(Integer),
            dest = Integer,
            immType = DecodeImmediate.CSR,
            src2Imm = immediate
        )
    }
    private val system = Seq(
        DecodeEntry("FENCE", Instructions.FENCE, System, SystemOp.FENCE, immType = DecodeImmediate.Fence),
        DecodeEntry("FENCE_I", Instructions.FENCE_I, System, SystemOp.FENCE_I),
        DecodeEntry("ECALL", Instructions.ECALL, System, SystemOp.ECALL),
        DecodeEntry("EBREAK", Instructions.EBREAK, System, SystemOp.EBREAK),
        DecodeEntry("MRET", Instructions.MRET, System, SystemOp.MRET),
        DecodeEntry("SRET", Instructions.SRET, System, SystemOp.SRET),
        DecodeEntry(
            "SFENCE_VMA",
            Instructions.SFENCE_VMA,
            System,
            SystemOp.SFENCE_VMA,
            src = Seq(Integer, Integer)
        ),
        DecodeEntry("WFI", Instructions.WFI, System, SystemOp.WFI),
    )

    val entries: Seq[DecodeEntry] = integerRegister ++ integerImmediate ++ upperImmediate ++ branches ++
        loads ++ stores ++ integerMultiply ++ integerDivide ++ floatingMultiply ++ floatingDivide ++
        floatingBinary ++ floatingUnary ++ atomics ++ csr ++ system

    /** Prove disjoint masks during elaboration; overlapping rows must never acquire priority. */
    def validate(rows: Seq[DecodeEntry]): Unit = {
        require(rows.nonEmpty, "Empty instruction table")
        require(rows.map(_.name).distinct.size == rows.size, "Duplicate instruction names")
        rows.foreach { row =>
            require(row.pattern.getWidth == 32, s"${row.name}: expected a 32-bit instruction pattern")
            require(DecodeUnit.all(row.fu) && row.op >= 0 && row.op < 32, s"${row.name}: invalid fu/op")
            require(
                row.src.size <= 3 && row.src.forall(d => d == Integer || d == Floating),
                s"${row.name}: invalid source domains"
            )
            require(Set(DecodeRegister.None, Integer, Floating)(row.dest), s"${row.name}: invalid destination")
            require(row.immType >= 0 && row.immType <= DecodeImmediate.Fence, s"${row.name}: invalid immediate")
            require(row.src1Sel >= 0 && row.src1Sel <= DecodeSource.Zero, s"${row.name}: invalid source selection")
            require(!row.rounding || row.usesFp, s"${row.name}: rounding requires floating operands")
            require(row.aqrl == (row.fu == Atomic), s"${row.name}: atomic ordering mismatch")
            val localOps = row.fu match {
                case ALU => integerRegister.map(_.op).toSet
                case Branch => branches.map(_.op).toSet
                case Multiply => integerMultiply.map(_.op).toSet ++ floatingMultiply.map(_.op)
                case Divide => integerDivide.map(_.op).toSet ++ floatingDivide.map(_.op)
                case Load => Set(0, 1, 2, 4, 5)
                case Store => Set(0, 1, 2)
                case FpMisc => (0 to FpMiscOp.FCVT_S_WU).toSet
                case Atomic => Set(0, 1, 2, 3, 4, 8, 12, 16, 20, 24, 28)
                case System => (0 to SystemOp.SFENCE_VMA).toSet
            }
            require(localOps(row.op), s"${row.name}: operation does not belong to its functional unit")
        }
        for (i <- rows.indices; j <- 0 until i) {
            val a = rows(i)
            val b = rows(j)
            require(
                ((a.pattern.value ^ b.pattern.value) & a.pattern.mask & b.pattern.mask) != 0,
                s"Overlapping instruction patterns: ${a.name} and ${b.name}"
            )
        }
    }
    validate(entries)
}
