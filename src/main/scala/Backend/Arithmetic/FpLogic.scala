// SPDX-License-Identifier: Apache-2.0
import chisel3._
import chisel3.util._
import ZirconConfig.FpMiscOp._

class FpLogicRequest(val tagWidth: Int) extends Bundle {
    val src1 = UInt(32.W)
    val src2 = UInt(32.W)
    val op = UInt(4.W)
    val tag = UInt(tagWidth.W)
}

class FpLogicResponse(val tagWidth: Int) extends Bundle {
    val res = UInt(32.W)
    val fflags = UInt(5.W)
    val dstIsFp = Bool()
    val tag = UInt(tagWidth.W)
}

/** Raw IEEE classification; no normalization or NaN canonicalization. */
class FpLogicClass extends Bundle {
    val sign = Bool()
    val zero = Bool()
    val normal = Bool()
    val subnormal = Bool()
    val inf = Bool()
    val nan = Bool()
    val snan = Bool()
}

/** One registered stage for FP32 comparisons, min/max, classification and bit operations. */
class FpLogic(val tagWidth: Int = 32) extends Module {
    require(tagWidth > 0)
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new FpLogicRequest(tagWidth)))
        val out = Decoupled(new FpLogicResponse(tagWidth))
        val flush = Input(Bool())
    })

    private def classify(bits: UInt): FpLogicClass = {
        val result = Wire(new FpLogicClass)
        val expZero = !bits(30, 23).orR
        val expOnes = bits(30, 23).andR
        val fracZero = !bits(22, 0).orR
        result.sign := bits(31)
        result.zero := expZero && fracZero
        result.normal := !expZero && !expOnes
        result.subnormal := expZero && !fracZero
        result.inf := expOnes && fracZero
        result.nan := expOnes && !fracZero
        result.snan := result.nan && !bits(22)
        result
    }

    val req = io.in.bits
    val a = classify(req.src1)
    val b = classify(req.src2)

    /* Shared comparison: the unsigned IEEE magnitude orders all non-NaN values. */
    val magnitudeLess = req.src1(30, 0) < req.src2(30, 0)
    val magnitudeEqual = req.src1(30, 0) === req.src2(30, 0)
    val bothZero = a.zero && b.zero
    val equal = (a.sign === b.sign && magnitudeEqual) || bothZero
    val less = !bothZero && Mux(
        a.sign =/= b.sign,
        a.sign,
        Mux(a.sign, !magnitudeLess && !magnitudeEqual, magnitudeLess)
    )
    val anyNaN = a.nan || b.nan
    val signalingNaN = a.snan || b.snan
    val compareResult = !anyNaN && MuxLookup(req.op, false.B)(Seq(
        FEQ.U -> equal,
        FLT.U -> less,
        FLE.U -> (less || equal)
    ))

    /* Min/max preserve the selected operand, with explicit signed-zero and NaN rules. */
    val isMin = req.op === FMIN.U
    val zeroSign = Mux(isMin, a.sign || b.sign, a.sign && b.sign)
    val minMaxResult = Mux(
        a.nan && b.nan,
        "h7fc00000".U(32.W),
        Mux(
            a.nan,
            req.src2,
            Mux(
                b.nan,
                req.src1,
                Mux(
                    bothZero,
                    Cat(zeroSign, 0.U(31.W)),
                    Mux(less === isMin, req.src1, req.src2)
                )
            )
        )
    )

    /* Sign injection and moves retain every payload bit, including signaling NaNs. */
    val injectedSign = MuxLookup(req.op, b.sign)(Seq(
        FSGNJN.U -> !b.sign,
        FSGNJX.U -> (a.sign ^ b.sign)
    ))
    val classResult = Cat(
        a.nan && !a.snan,
        a.snan,
        !a.sign && a.inf,
        !a.sign && a.normal,
        !a.sign && a.subnormal,
        !a.sign && a.zero,
        a.sign && a.zero,
        a.sign && a.subnormal,
        a.sign && a.normal,
        a.sign && a.inf
    )
    val isSign = req.op <= FSGNJX.U
    val isMinMax = req.op === FMIN.U || req.op === FMAX.U
    val isCompare = req.op >= FEQ.U && req.op <= FLE.U
    val isMove = req.op === FMV_X_W.U || req.op === FMV_W_X.U
    val response = Wire(new FpLogicResponse(tagWidth))
    response.res := Mux1H(Seq(
        isSign -> Cat(injectedSign, req.src1(30, 0)),
        isMinMax -> minMaxResult,
        isCompare -> Cat(0.U(31.W), compareResult),
        (req.op === FCLASS.U) -> classResult.pad(32),
        isMove -> req.src1
    ))
    val invalid = (isMinMax && signalingNaN) ||
        (isCompare && Mux(req.op === FEQ.U, signalingNaN, anyNaN))
    response.fflags := Cat(invalid, 0.U(4.W))
    response.dstIsFp := isSign || isMinMax || req.op === FMV_W_X.U
    response.tag := req.tag

    /* A held result owns its register until accepted or killed. */
    val result = Reg(new FpLogicResponse(tagWidth))
    val valid = RegInit(false.B)
    val active = !reset.asBool && !io.flush
    val advance = !valid || io.out.ready
    io.in.ready := active && advance
    io.out.valid := active && valid
    io.out.bits := result
    when(io.flush) {
        valid := false.B
    }.elsewhen(advance) {
        valid := io.in.valid
    }
    when(io.in.fire) {
        result := response
    }
    when(active && io.in.valid) {
        assert(req.op <= FMV_W_X.U, "FpLogic received a non-logic operation")
    }
}
