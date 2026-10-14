/*
 * Lookup tables adapted from Berkeley SoftFloat, Release 3d.
 * Copyright 2011-2016 The Regents of the University of California.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the University nor the names of its contributors may
 *    be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
import chisel3._
import chisel3.util._

class FpEstimateRequest extends Bundle {
    val source = UInt(32.W)
    val reciprocalSqrt = Bool()
    val rounding = UInt(3.W)
}

class FpEstimateResponse extends Bundle {
    val result = UInt(32.W)
    val flags = UInt(5.W)
}

/** Standard RVV seven-bit estimates, with normalization and lookup separated
  * by a register. Exceptional operands reuse the scalar IEEE classification.
  */
class FpEstimate extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new FpEstimateRequest))
        val out = Decoupled(new FpEstimateResponse)
        val flush = Input(Bool())
    })
    private val reciprocal = Seq(
        127,125,123,121,119,117,116,114,112,110,109,107,105,104,102,100,
        99,97,96,94,93,91,90,88,87,85,84,83,81,80,79,77,
        76,75,74,72,71,70,69,68,66,65,64,63,62,61,60,59,
        58,57,56,55,54,53,52,51,50,49,48,47,46,45,44,43,
        42,41,40,40,39,38,37,36,35,35,34,33,32,31,31,30,
        29,28,28,27,26,25,25,24,23,23,22,21,21,20,19,19,
        18,17,17,16,15,15,14,14,13,12,12,11,11,10,9,9,
        8,8,7,7,6,5,5,4,4,3,3,2,2,1,1,0)
    private val reciprocalSqrt = Seq(
        52,51,50,48,47,46,44,43,42,41,40,39,38,36,35,34,
        33,32,31,30,30,29,28,27,26,25,24,23,23,22,21,20,
        19,19,18,17,16,16,15,14,14,13,12,12,11,10,10,9,
        9,8,7,7,6,6,5,4,4,3,3,2,2,1,1,0,
        127,125,123,121,119,118,116,114,113,111,109,108,106,105,103,102,
        100,99,97,96,95,93,92,91,90,88,87,86,85,84,83,82,
        80,79,78,77,76,75,74,73,72,71,70,70,69,68,67,66,
        65,64,63,63,62,61,60,59,59,58,57,56,56,55,54,53)
    val valid = RegInit(VecInit.fill(2)(false.B))
    val advance = !valid.last || io.out.ready
    io.in.ready := advance && !io.flush
    io.out.valid := valid.last && !io.flush
    when(io.flush) { valid.foreach(_ := false.B) }
    .elsewhen(advance) { valid(0) := io.in.valid; valid(1) := valid(0) }

    val request = Reg(new FpEstimateRequest)
    val exponent = Reg(SInt(10.W))
    val fraction = Reg(UInt(23.W))
    val source = io.in.bits.source
    val leading = PriorityEncoder(Reverse(source(22, 0)))
    when(advance) {
        request := io.in.bits
        exponent := Mux(source(30, 23).orR, source(30, 23).zext,
            -leading.zext.pad(10))
        fraction := Mux(source(30, 23).orR, source(22, 0),
            (source(22, 0) << (leading +& 1.U))(22, 0))
    }
    val classification = SharedMultiplyLogic.decode(request.source)
    val sqrt = request.reciprocalSqrt
    val index = Mux(sqrt, Cat(exponent.asUInt(0), fraction(22, 17)), fraction(22, 16))
    val tableValue = Mux(sqrt, VecInit(reciprocalSqrt.map(_.U(7.W)))(index),
        VecInit(reciprocal.map(_.U(7.W)))(index))
    val resultExponent = Mux(sqrt, (380.S(11.W) - exponent) >> 1, 253.S(11.W) - exponent)
    val normal = Cat(classification.sign, resultExponent.asUInt(7, 0), tableValue, 0.U(16.W))
    val subnormalSig = Cat(1.U(1.W), tableValue, 0.U(16.W))
    val subnormal = Cat(classification.sign, 0.U(8.W),
        Mux(resultExponent === 0.S, subnormalSig >> 1, subnormalSig >> 2)(22, 0))
    val overflow = !sqrt && exponent < (-1).S
    val invalid = classification.snan || sqrt && classification.sign && !classification.zero && !classification.nan
    val maxFinite = request.rounding === 1.U || request.rounding === 2.U && !classification.sign ||
        request.rounding === 3.U && classification.sign
    val overflowBits = Cat(classification.sign, Mux(maxFinite, "h7f7fffff".U(31.W), "h7f800000".U(31.W)))
    val response = Wire(new FpEstimateResponse)
    response.result := Mux(classification.nan || invalid, "h7fc00000".U,
        Mux(classification.zero, Cat(classification.sign, "h7f800000".U(31.W)),
            Mux(classification.inf, Cat(classification.sign, 0.U(31.W)),
                Mux(overflow, overflowBits, Mux(!sqrt && resultExponent <= 0.S, subnormal, normal)))))
    response.flags := Mux(invalid, 16.U, Mux(classification.zero, 8.U,
        Mux(!classification.nan && !classification.inf && overflow, 5.U, 0.U)))
    val result = Reg(new FpEstimateResponse)
    when(advance) { result := response }
    io.out.bits := result
}
