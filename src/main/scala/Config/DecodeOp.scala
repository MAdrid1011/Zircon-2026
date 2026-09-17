package ZirconConfig

/** Functional destinations; dispatch maps these to physical issue queues. */
object DecodeUnit {
    val None = 0
    val ALU = 1
    val Branch = 2
    val Multiply = 3
    val Divide = 4
    val FpMisc = 5
    val Load = 6
    val Store = 7
    val Atomic = 8
    val System = 9
    val all = Set(ALU, Branch, Multiply, Divide, FpMisc, Load, Store, Atomic, System)
}

object DecodeSource {
    val Register = 0
    val PC = 1
    val Zero = 2
}

/** Elaboration-only immediate formats and operand domains. */
object DecodeImmediate {
    val None = 0
    val I = 1
    val S = 2
    val B = 3
    val U = 4
    val J = 5
    val Shift = 6
    val CSR = 7
    val Fence = 8
}

object DecodeRegister {
    val None = 0
    val Integer = 1
    val Floating = 2
}

/** Reserved contracts for the future FP auxiliary and conversion path. */
object FpMiscOp {
    val FSGNJ = 0
    val FSGNJN = 1
    val FSGNJX = 2
    val FMIN = 3
    val FMAX = 4
    val FEQ = 5
    val FLT = 6
    val FLE = 7
    val FCLASS = 8
    val FMV_X_W = 9
    val FMV_W_X = 10
    val FCVT_W_S = 11
    val FCVT_WU_S = 12
    val FCVT_S_W = 13
    val FCVT_S_WU = 14
}

/** System requests still require ordered execution and runtime permission checks. */
object SystemOp {
    val FENCE = 0
    val FENCE_I = 1
    val CSRRW = 2
    val CSRRS = 3
    val CSRRC = 4
    val CSRRWI = 5
    val CSRRSI = 6
    val CSRRCI = 7
    val ECALL = 8
    val EBREAK = 9
    val MRET = 10
    val WFI = 11
    val SRET = 12
    val SFENCE_VMA = 13
}

/** Static integration capabilities, never runtime enables or decode strategies. */
case class DecodeSupport(
    units: Set[Int],
    floatingState: Boolean = false,
    systemOps: Set[Int] = Set.empty
) {
    require(units.subsetOf(DecodeUnit.all), "Unknown decoder functional destination")
    require(systemOps.subsetOf((SystemOp.FENCE to SystemOp.SFENCE_VMA).toSet), "Unknown system operation")
    require(systemOps.isEmpty || units(DecodeUnit.System), "System operations require the System destination")
}

object DecodeSupport {
    private val csr = (SystemOp.CSRRW to SystemOp.CSRRCI).toSet
    // Floating execution also needs ordered frm/fflags/FS handling outside this decoder.
    val current = DecodeSupport(
        Set(
            DecodeUnit.ALU,
            DecodeUnit.Branch,
            DecodeUnit.Multiply,
            DecodeUnit.Divide,
            DecodeUnit.FpMisc,
            DecodeUnit.Load,
            DecodeUnit.Store,
            DecodeUnit.Atomic,
            DecodeUnit.System
        ),
        floatingState = true,
        systemOps = (SystemOp.FENCE to SystemOp.SFENCE_VMA).toSet
    )
    // For full-table validation and future integration after all consumers are connected.
    val target = DecodeSupport(
        DecodeUnit.all,
        floatingState = true,
        systemOps = (SystemOp.FENCE to SystemOp.SFENCE_VMA).toSet
    )
}

object DecodeException {
    val IllegalInstruction = 2
}
