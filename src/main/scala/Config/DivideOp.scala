package ZirconConfig

/** Operation encoding local to the shared RV32/FP32 divide/square-root unit. */
object DivideOp {
    val DIV = 0
    val DIVU = 1
    val REM = 2
    val REMU = 3
    val FDIV = 4
    val FSQRT = 5
}
