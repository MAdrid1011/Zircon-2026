import chisel3._
import ZirconConfig.BackendParams

/** Exception state carried by every backend pipeline. */
class BackendException extends Bundle {
    val valid = Bool()
    val cause = UInt(5.W)
    val tval = UInt(32.W)
}

/**
  * The single instruction package shared by every backend pipeline.
  *
  * Dispatch fills identity and operation fields. RF stages attach source values and
  * execution stages attach results to the same package. A pipeline only reads the
  * fields it needs; unused fields are removed when that pipeline is synthesized.
  */
class BackendPackage(val p: BackendParams = BackendParams()) extends Bundle {
    // Rename, dispatch and issue information.
    val prs = Vec(3, UInt(p.tagWidth.W))
    val sourceValid = Vec(3, Bool())
    // Ready state travels with the instruction and is updated in place by its issue queue.
    val sourceReady = Vec(3, Bool())
    val sourceSpecMask = Vec(3, UInt(p.specWidth.W))
    val prd = UInt(p.tagWidth.W)
    val rdValid = Bool()
    val fu = UInt(4.W)
    val op = UInt(5.W)
    val src1Sel = UInt(2.W)
    val src2Imm = Bool()
    val imm = UInt(32.W)
    val roundingMode = UInt(3.W)
    val inst = UInt(32.W)
    val rdZero = Bool()

    // Ordered backend identities and memory attributes.
    val robIdx = UInt(p.robWidth.W)
    val sqTail = UInt(p.sqWidth.W)
    val sqIdx = UInt(p.sqWidth.W)
    val exception = new BackendException
    val uncache = Bool()
    val ioAuthorized = Bool()
    val store = Bool()
    val mtype = UInt(3.W)
    val size = UInt(2.W)
    val fpFlagsValid = Bool()

    // Values attached by RF and execution stages.
    val source = Vec(3, UInt(32.W))
    val pc = UInt(32.W)
    val predictedTaken = Bool()
    val predictedValue = UInt(32.W)
    val result = UInt(32.W)
    val fflags = UInt(5.W)
    val resultIsFp = Bool()
    val branchTaken = Bool()
    val branchTarget = UInt(32.W)
    val predFail = Bool()

    // Conventional two-source names retained for integer and memory pipelines.
    def prj: UInt = prs(0)
    def prk: UInt = prs(1)
    def prjValid: Bool = sourceValid(0)
    def prkValid: Bool = sourceValid(1)
    def rdVld: Bool = rdValid
    def source1: UInt = source(0)
    def source2: UInt = source(1)
}

/** Physical mappings and initial readiness attached after rename and ReadyBoard lookup. */
class BackendRenameInfo(val p: BackendParams = BackendParams()) extends Bundle {
    val prs = Vec(3, UInt(p.tagWidth.W))
    val sourceIndependent = Vec(3, Bool())
    val sourceReady = Vec(3, Bool())
    val sourceSpecMask = Vec(3, UInt(p.specWidth.W))
    val prd = UInt(p.tagWidth.W)
}

/** Ordered identities allocated atomically with dispatch. */
class BackendAllocation(val p: BackendParams = BackendParams()) extends Bundle {
    val robIdx = UInt(p.robWidth.W)
    val sqTail = UInt(p.sqWidth.W)
    val sqIdx = UInt(p.sqWidth.W)
}

object BackendPackage {
    /** Build the common backend package from one decoded instruction and dispatch allocations. */
    def fromFrontend(
        instruction: FrontendInstruction,
        physical: BackendRenameInfo,
        allocation: BackendAllocation,
        p: BackendParams = BackendParams(),
    ): BackendPackage = {
        val result = WireDefault(0.U.asTypeOf(new BackendPackage(p)))
        result.prs := physical.prs
        result.prd := physical.prd
        for (source <- 0 until 3) {
            result.sourceValid(source) := instruction.rinfo.src(source).valid
            result.sourceReady(source) := !instruction.rinfo.src(source).valid || physical.sourceReady(source)
            // ReadyBoard already returns zero for an unused source.
            result.sourceSpecMask(source) := physical.sourceSpecMask(source)
        }
        result.rdValid := instruction.rinfo.dest.valid
        result.fu := instruction.fu
        result.op := instruction.op
        result.src1Sel := instruction.src1Sel
        result.src2Imm := instruction.src2Imm
        result.imm := instruction.imm
        result.roundingMode := instruction.rm
        result.inst := instruction.inst
        result.rdZero := instruction.inst(11, 7) === 0.U

        result.robIdx := allocation.robIdx
        result.sqTail := allocation.sqTail
        result.sqIdx := allocation.sqIdx
        result.exception.valid := instruction.exception.valid
        result.exception.cause := instruction.exception.cause
        result.exception.tval := instruction.exception.tval
        result.store := instruction.fu === ZirconConfig.DecodeUnit.Store.U
        val memory = instruction.fu === ZirconConfig.DecodeUnit.Load.U || result.store
        result.mtype := Mux(memory, instruction.op(2, 0), 0.U)
        result.size := Mux(result.store, instruction.op(1, 0), 0.U)

        result.pc := instruction.pc
        result.predictedTaken := instruction.predictedTaken
        result.predictedValue := instruction.predictedValue
        result.resultIsFp := instruction.rinfo.dest.valid && instruction.rinfo.dest.isFp
        val fpMultiply = instruction.fu === ZirconConfig.DecodeUnit.Multiply.U &&
            instruction.op >= ZirconConfig.MultiplyOp.FADD
        val fpDivide = instruction.fu === ZirconConfig.DecodeUnit.Divide.U &&
            instruction.op >= ZirconConfig.DivideOp.FDIV.U
        val fpMiscFlags = instruction.fu === ZirconConfig.DecodeUnit.FpMisc.U &&
            ((instruction.op >= ZirconConfig.FpMiscOp.FMIN.U && instruction.op <= ZirconConfig.FpMiscOp.FLE.U) ||
                (instruction.op >= ZirconConfig.FpMiscOp.FCVT_W_S.U &&
                    instruction.op <= ZirconConfig.FpMiscOp.FCVT_S_WU.U))
        result.fpFlagsValid := fpMultiply || fpDivide || fpMiscFlags
        result
    }

    /** Select one lane from the current multi-instruction frontend packet. */
    def fromFrontend(
        frontend: FrontendPackage,
        lane: Int,
        physical: BackendRenameInfo,
        allocation: BackendAllocation,
        p: BackendParams,
    ): BackendPackage = {
        require(lane >= 0 && lane < frontend.instructions.length)
        fromFrontend(frontend.instructions(lane), physical, allocation, p)
    }

    /** Turn a Store package into the independent STD task consumed by LS1. */
    def forStoreData(base: BackendPackage): BackendPackage = {
        val result = WireDefault(base)
        result.prs(0) := base.prs(1)
        result.sourceValid(0) := base.sourceValid(1)
        result.sourceReady(0) := base.sourceReady(1)
        result.sourceSpecMask(0) := base.sourceSpecMask(1)
        result.source(0) := base.source(1)
        result.prs(1) := 0.U
        result.prs(2) := 0.U
        result.sourceValid(1) := false.B
        result.sourceValid(2) := false.B
        result.sourceReady(1) := true.B
        result.sourceReady(2) := true.B
        result.sourceSpecMask(1) := 0.U
        result.sourceSpecMask(2) := 0.U
        result.source(1) := 0.U
        result.source(2) := 0.U
        result.size := base.mtype(1, 0)
        result
    }
}

class BackendWakeup(val p: BackendParams = BackendParams()) extends Bundle {
    // Integer p0 is the empty code; FP f0 remains a valid non-zero unified tag.
    val prd = UInt(p.tagWidth.W)
    val specMask = UInt(p.specWidth.W)
}

class SpeculationResolution(val p: BackendParams = BackendParams()) extends Bundle {
    val resolvedMask = UInt(p.specWidth.W)
    val failedMask = UInt(p.specWidth.W)
}

class LoadSpeculationResult(val p: BackendParams = BackendParams()) extends Bundle {
    val mask = UInt(p.specWidth.W)
    val failed = Bool()
}
