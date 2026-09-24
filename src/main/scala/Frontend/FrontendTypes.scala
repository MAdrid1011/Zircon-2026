import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

object FrontendCfi {
    val None = 0
    val Conditional = 1
    val Jump = 2
    val Call = 3
    val Indirect = 4
    val IndirectCall = 5
    val Return = 6
    val Coroutine = 7
    def conditional(kind: UInt): Bool = kind === Conditional.U
    def direct(kind: UInt): Bool = kind === Jump.U || kind === Call.U
    def indirect(kind: UInt): Bool = kind(2)
    def push(kind: UInt): Bool = kind === Call.U || kind === IndirectCall.U || kind === Coroutine.U
    def pop(kind: UInt): Bool = kind === Return.U || kind === Coroutine.U
}

class FrontendOperand extends Bundle {
    val index = UInt(5.W)
    val isFp = Bool()
    val valid = Bool()
}

class FrontendRegisterInfo extends Bundle {
    val src = Vec(3, new FrontendOperand)
    val dest = new FrontendOperand
}

/** Synchronous exception metadata; legacy fetch fault remains separate until its cause is available. */
class FrontendException extends Bundle {
    val valid = Bool()
    val cause = UInt(5.W)
    val tval = UInt(32.W)
}

class FrontendInstruction(p: FrontendParams) extends Bundle {
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val rinfo = new FrontendRegisterInfo
    val kind = UInt(3.W)
    val predictedTaken = Bool()
    val predictedValue = UInt(32.W)
    val fault = Bool()
    // Decode fills these fields after FQ; preceding stages keep them zero.
    val fu = UInt(4.W)
    val op = UInt(5.W)
    val src1Sel = UInt(2.W)
    val src2Imm = Bool()
    val imm = UInt(32.W)
    val rm = UInt(3.W)
    val aq = Bool()
    val rl = Bool()
    val exception = new FrontendException

    def mtype: UInt = op(2, 0)
    def csrAddr: UInt = imm(11, 0)
    def csrImmediate: UInt = imm(16, 12)
    def fenceMode: UInt = imm(11, 8)
    def fencePred: UInt = imm(7, 4)
    def fenceSucc: UInt = imm(3, 0)
}

/** Internal prediction context travels in the common package until PD completes it. */
class FrontendPredictInfo(p: FrontendParams) extends Bundle {
    val range = UInt(p.fetchWidth.W)
    val returned = UInt(p.fetchWidth.W)
    val early = new FrontendPrediction(p)
    val main = new FrontendTargetPrediction(p)
    val earlyDirections = UInt(p.fetchWidth.W)
    val directions = UInt(p.fetchWidth.W)
    val meta = new FrontendDirectionMeta(p)
    val scRead = new FrontendCorrectorRead(p)
    val before = new FrontendStateSnapshot(p)
    val fields = Vec(p.fetchWidth, new FrontendPredecodeFields)
}

class FrontendPackage(p: FrontendParams) extends Bundle {
    val ftqIdx = UInt(p.ftqBits.W)
    val startPc = UInt(32.W)
    val mask = UInt(p.fetchWidth.W)
    val nextPc = UInt(32.W)
    val instructions = Vec(p.fetchWidth, new FrontendInstruction(p))
    val predict = new FrontendPredictInfo(p)
    val record = new FrontendFtqRecord(p)
}

class FetchQueueEntry(p: FrontendParams) extends Bundle {
    val slot = UInt(p.slotBits.W)
    val packetStart = Bool()
    val packetEnd = Bool()
    val instruction = new FrontendInstruction(p)
    val record = new FrontendFtqRecord(p)
}

class FrontendFetchRequest(p: FrontendParams) extends Bundle {
    val pc = UInt(32.W)
}

class FrontendFetchResponse(p: FrontendParams) extends Bundle {
    val mask = UInt(p.fetchWidth.W)
    val inst = Vec(p.fetchWidth, UInt(32.W))
    val fault = UInt(p.fetchWidth.W)
}

class FrontendFetchIO(p: FrontendParams) extends Bundle {
    val request = Decoupled(new FrontendFetchRequest(p))
    val response = Flipped(Decoupled(new FrontendFetchResponse(p)))
}

class FrontendMiddleIO(p: FrontendParams, width: Int) extends Bundle {
    val out = Vec(width, Decoupled(new FetchQueueEntry(p)))
}

/** One in-order retired fetch packet; masks describe only actually executed slots. */
class FrontendRetirement(p: FrontendParams) extends Bundle {
    val ftqIdx = UInt(p.ftqBits.W)
    val mask = UInt(p.fetchWidth.W)
    val taken = UInt(p.fetchWidth.W)
    val targets = Vec(p.fetchWidth, UInt(32.W))
    val nextPc = UInt(32.W)
}

class FrontendRedirect extends Bundle {
    val pc = UInt(32.W)
}

class FrontendFtqIO(p: FrontendParams) extends Bundle {
    val retire = Flipped(Vec(3, Valid(new FrontendStateEvent(p))))
    val train = Flipped(Decoupled(new FrontendTraining(p)))
    val used = if (p.observe) Some(Input(UInt(log2Ceil(p.ftqDepth + 1).W))) else None
}

class FrontendRobIO extends Bundle {
    val redirect = Flipped(Valid(new FrontendRedirect))
}

class FrontendCommitIO(p: FrontendParams) extends Bundle {
    val ftq = new FrontendFtqIO(p)
    val rob = new FrontendRobIO
}

class FrontendPrediction(p: FrontendParams) extends Bundle {
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val targets = Vec(p.fetchWidth, UInt(32.W))
    val taken = UInt(p.fetchWidth.W)
    val mask = UInt(p.fetchWidth.W)
    val nextPc = UInt(32.W)
    val backward = UInt(p.fetchWidth.W)
}

/** Main-BTB fields consumed by predecode after the IF2 register. */
class FrontendTargetPrediction(p: FrontendParams) extends Bundle {
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val targets = Vec(p.fetchWidth, UInt(32.W))
}

object FrontendMath {
    // Infer each carry from the expected sum, then check all local carry equations in parallel.
    def sumMatches(a: UInt, b: UInt, expected: UInt): Bool = {
        val width = a.getWidth
        require(width > 0 && b.getWidth == width && expected.getWidth == width)
        val propagate = a ^ b
        val carry = expected ^ propagate
        val nextCarry = (a & b) | (propagate & carry)
        if (width == 1) !carry(0)
        else !carry(0) && carry(width - 1, 1) === nextCarry(width - 2, 0)
    }
    // Legal RV32 B-immediates fit in 13 bits, including wraparound at address boundaries.
    def backwardBranch(pc: UInt, target: UInt): Bool = {
        val displacement = target(12, 0) - pc(12, 0)
        displacement(12) || !displacement.orR
    }
    def rasTop(snapshot: FrontendStateSnapshot): UInt =
        Cat(snapshot.top, 0.U(2.W))
    def read[T <: Data](items: Seq[T], index: UInt): T = Mux1H(UIntToOH(index, items.size), items)
    def sat(value: UInt, up: Bool): UInt = {
        val maximum = ((BigInt(1) << value.getWidth) - 1).U(value.getWidth.W)
        Mux(up, Mux(value === maximum, value, value + 1.U), Mux(value === 0.U, value, value - 1.U))
    }
    def fold(value: UInt, width: Int): UInt = {
        val parts = (0 until value.getWidth by width).map { start =>
            value(math.min(start + width, value.getWidth) - 1, start).pad(width)
        }
        parts.reduce(_ ^ _)
    }
    def blockBase(pc: UInt, p: FrontendParams): UInt = Cat(pc(31, p.blockBits), 0.U(p.blockBits.W))
    def sequential(pc: UInt, p: FrontendParams): UInt = blockBase(pc, p) + (p.fetchWidth * 4).U
    def slotPc(pc: UInt, slot: Int, p: FrontendParams): UInt = blockBase(pc, p) | (slot * 4).U
    def range(pc: UInt, p: FrontendParams): UInt = {
        if (p.fetchWidth == 1) 1.U(1.W)
        else VecInit((0 until p.fetchWidth).map(i => i.U >= pc(p.blockBits - 1, 2))).asUInt
    }
    def signature(pc: UInt, prediction: FrontendPrediction, p: FrontendParams): UInt = {
        val control = Wire(UInt(p.fetchWidth.W))
        control := VecInit(prediction.kinds.zipWithIndex.map { case (k, i) =>
            k =/= 0.U && prediction.mask(i)
        }).asUInt
        fold(pc(31, 2), p.historyStep) ^ control.pad(p.historyStep) ^ prediction.taken.pad(p.historyStep)
    }
    def append(history: UInt, signature: UInt, p: FrontendParams): UInt = {
        if (p.historyBits == p.historyStep) signature
        else Cat(history(p.historyBits - p.historyStep - 1, 0), signature)
    }
}

class FrontendObserveIO extends Bundle {
    // Only counters consumed by the simulation shell cross the Frontend boundary.
    val icache = Output(new ICacheDBG)
    val loopTraining = Output(UInt(64.W))
    val loopProvider = Output(UInt(64.W))
    val loopCorrect = Output(UInt(64.W))
    val fqBlockedCycles = Output(UInt(64.W))
    val fqEmptyCycles = Output(UInt(64.W))
}
