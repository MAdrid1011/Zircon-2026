import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

object FrontendCfiClass {
    val None = 0
    val Branch = 1
    val Jal = 2
    val Jalr = 3
}

class FrontendPredecodeFields extends Bundle {
    val cfiClass = UInt(2.W)
    val immediate = UInt(32.W)
}

class PredecodeFieldsIO extends Bundle {
    val inst = Input(UInt(32.W))
    val fields = Output(new FrontendPredecodeFields)
}

/** IF2 performs shallow classification and immediate wiring before the PD register. */
class PredecodeFields extends RawModule {
    val io = IO(new PredecodeFieldsIO)
    val inst = io.inst

    /* Control-Flow Classification */
    val branch = inst(6, 0) === "h63".U && inst(14, 12) =/= 2.U && inst(14, 12) =/= 3.U
    val jal = inst(6, 0) === "h6f".U
    val jalr = inst(6, 0) === "h67".U && inst(14, 12) === 0.U

    /* Immediate Extraction */
    // Wiring only: full target addition belongs to PD.
    val bImm = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
    val jImm = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
    val iImm = Cat(Fill(20, inst(31)), inst(31, 20))
    io.fields.cfiClass := Mux(
        branch,
        FrontendCfiClass.Branch.U,
        Mux(jal, FrontendCfiClass.Jal.U, Mux(jalr, FrontendCfiClass.Jalr.U, FrontendCfiClass.None.U))
    )
    io.fields.immediate := Mux1H(Seq(branch -> bImm, jal -> jImm, jalr -> iImm))
}
