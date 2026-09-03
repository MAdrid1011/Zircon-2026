package zircon.frontend

import chisel3._
import chisel3.util._

/** RV32F single-precision operations. This metadata remains separate from the
  * integer decoder until an E2/FPR/commit path can consume it precisely. */
object FloatingOperation extends ChiselEnum {
  val Invalid, Flw, Fsw = Value
  val FmaddS, FmsubS, FnmsubS, FnmaddS = Value
  val FaddS, FsubS, FmulS, FdivS, FsqrtS = Value
  val FsgnjS, FsgnjnS, FsgnjxS, FminS, FmaxS = Value
  val FcvtWS, FcvtWuS, FcvtSW, FcvtSWu = Value
  val FmvXW, FclassS, FleS, FltS, FeqS, FmvWX = Value
}

/** Namespace and rounding metadata for one RV32F encoding.
  *
  * This is intentionally not a `DecodedInstruction`: the existing M1/M3
  * dispatch path only represents integer-source operations. Keeping F
  * metadata isolated prevents an unimplemented execution endpoint from ever
  * receiving an F instruction or fabricating a completion.
  */
class FloatingDecodedInstruction extends Bundle {
  val legal = Bool()
  val operation = FloatingOperation()
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rs3 = UInt(5.W)
  val rd = UInt(5.W)
  val immediate = UInt(32.W)
  val readsIntegerRs1 = Bool()
  val readsIntegerRs2 = Bool()
  val readsFloatRs1 = Bool()
  val readsFloatRs2 = Bool()
  val readsFloatRs3 = Bool()
  val writesIntegerRd = Bool()
  val writesFloatRd = Bool()
  val isMemory = Bool()
  val memoryWrite = Bool()
  val roundingMode = UInt(3.W)
  val usesRoundingMode = Bool()
  val dynamicRounding = Bool()
}

/** Metadata-only decoder for the frozen RV32F single-precision subset. */
class RV32FMetadataDecoder extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val decoded = Output(new FloatingDecodedInstruction)
  })

  val instruction = io.instruction
  val opcode = instruction(6, 0)
  val funct3 = instruction(14, 12)
  val funct7 = instruction(31, 25)
  val rmLegal = funct3 <= 4.U || funct3 === 7.U
  val iImmediate = Cat(Fill(20, instruction(31)), instruction(31, 20))
  val sImmediate = Cat(Fill(20, instruction(31)), instruction(31, 25),
    instruction(11, 7))

  val decoded = WireDefault(0.U.asTypeOf(new FloatingDecodedInstruction))
  decoded.operation := FloatingOperation.Invalid
  decoded.rs1 := instruction(19, 15)
  decoded.rs2 := instruction(24, 20)
  decoded.rs3 := instruction(31, 27)
  decoded.rd := instruction(11, 7)
  decoded.immediate := iImmediate
  decoded.roundingMode := funct3
  decoded.dynamicRounding := funct3 === 7.U

  private def mark(
      operation: FloatingOperation.Type,
      readsIntegerRs1: Boolean = false,
      readsIntegerRs2: Boolean = false,
      readsFloatRs1: Boolean = false,
      readsFloatRs2: Boolean = false,
      readsFloatRs3: Boolean = false,
      writesIntegerRd: Boolean = false,
      writesFloatRd: Boolean = false,
      isMemory: Boolean = false,
      memoryWrite: Boolean = false,
      usesRoundingMode: Boolean = false
  ): Unit = {
    decoded.legal := (!usesRoundingMode).B || rmLegal
    decoded.operation := operation
    decoded.readsIntegerRs1 := readsIntegerRs1.B
    decoded.readsIntegerRs2 := readsIntegerRs2.B
    decoded.readsFloatRs1 := readsFloatRs1.B
    decoded.readsFloatRs2 := readsFloatRs2.B
    decoded.readsFloatRs3 := readsFloatRs3.B
    decoded.writesIntegerRd := writesIntegerRd.B
    decoded.writesFloatRd := writesFloatRd.B
    decoded.isMemory := isMemory.B
    decoded.memoryWrite := memoryWrite.B
    decoded.immediate := Mux(memoryWrite.B, sImmediate, iImmediate)
    decoded.usesRoundingMode := usesRoundingMode.B
  }

  switch(opcode) {
    is("b0000111".U) { // FLW
      when(funct3 === "b010".U) {
        mark(FloatingOperation.Flw, readsIntegerRs1 = true, writesFloatRd = true,
          isMemory = true)
      }
    }
    is("b0100111".U) { // FSW
      when(funct3 === "b010".U) {
        mark(FloatingOperation.Fsw, readsIntegerRs1 = true, readsFloatRs2 = true,
          isMemory = true, memoryWrite = true)
      }
    }
    is("b1000011".U) { // FMADD.S
      when(instruction(26, 25) === 0.U) {
        mark(FloatingOperation.FmaddS, readsFloatRs1 = true, readsFloatRs2 = true,
          readsFloatRs3 = true, writesFloatRd = true, usesRoundingMode = true)
      }
    }
    is("b1000111".U) { // FMSUB.S
      when(instruction(26, 25) === 0.U) {
        mark(FloatingOperation.FmsubS, readsFloatRs1 = true, readsFloatRs2 = true,
          readsFloatRs3 = true, writesFloatRd = true, usesRoundingMode = true)
      }
    }
    is("b1001011".U) { // FNMSUB.S
      when(instruction(26, 25) === 0.U) {
        mark(FloatingOperation.FnmsubS, readsFloatRs1 = true, readsFloatRs2 = true,
          readsFloatRs3 = true, writesFloatRd = true, usesRoundingMode = true)
      }
    }
    is("b1001111".U) { // FNMADD.S
      when(instruction(26, 25) === 0.U) {
        mark(FloatingOperation.FnmaddS, readsFloatRs1 = true, readsFloatRs2 = true,
          readsFloatRs3 = true, writesFloatRd = true, usesRoundingMode = true)
      }
    }
    is("b1010011".U) { // OP-FP
      switch(funct7) {
        is("b0000000".U) {
          mark(FloatingOperation.FaddS, readsFloatRs1 = true, readsFloatRs2 = true,
            writesFloatRd = true, usesRoundingMode = true)
        }
        is("b0000100".U) {
          mark(FloatingOperation.FsubS, readsFloatRs1 = true, readsFloatRs2 = true,
            writesFloatRd = true, usesRoundingMode = true)
        }
        is("b0001000".U) {
          mark(FloatingOperation.FmulS, readsFloatRs1 = true, readsFloatRs2 = true,
            writesFloatRd = true, usesRoundingMode = true)
        }
        is("b0001100".U) {
          mark(FloatingOperation.FdivS, readsFloatRs1 = true, readsFloatRs2 = true,
            writesFloatRd = true, usesRoundingMode = true)
        }
        is("b0101100".U) {
          when(decoded.rs2 === 0.U) {
            mark(FloatingOperation.FsqrtS, readsFloatRs1 = true, writesFloatRd = true,
              usesRoundingMode = true)
          }
        }
        is("b0010000".U) {
          switch(funct3) {
            is(0.U) { mark(FloatingOperation.FsgnjS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesFloatRd = true) }
            is(1.U) { mark(FloatingOperation.FsgnjnS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesFloatRd = true) }
            is(2.U) { mark(FloatingOperation.FsgnjxS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesFloatRd = true) }
          }
        }
        is("b0010100".U) {
          switch(funct3) {
            is(0.U) { mark(FloatingOperation.FminS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesFloatRd = true) }
            is(1.U) { mark(FloatingOperation.FmaxS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesFloatRd = true) }
          }
        }
        is("b1100000".U) {
          when(decoded.rs2 === 0.U) {
            mark(FloatingOperation.FcvtWS, readsFloatRs1 = true,
              writesIntegerRd = true, usesRoundingMode = true)
          }.elsewhen(decoded.rs2 === 1.U) {
            mark(FloatingOperation.FcvtWuS, readsFloatRs1 = true,
              writesIntegerRd = true, usesRoundingMode = true)
          }
        }
        is("b1101000".U) {
          when(decoded.rs2 === 0.U) {
            mark(FloatingOperation.FcvtSW, readsIntegerRs1 = true,
              writesFloatRd = true, usesRoundingMode = true)
          }.elsewhen(decoded.rs2 === 1.U) {
            mark(FloatingOperation.FcvtSWu, readsIntegerRs1 = true,
              writesFloatRd = true, usesRoundingMode = true)
          }
        }
        is("b1110000".U) {
          when(decoded.rs2 === 0.U) {
            switch(funct3) {
              is(0.U) { mark(FloatingOperation.FmvXW, readsFloatRs1 = true,
                writesIntegerRd = true) }
              is(1.U) { mark(FloatingOperation.FclassS, readsFloatRs1 = true,
                writesIntegerRd = true) }
            }
          }
        }
        is("b1010000".U) {
          switch(funct3) {
            is(0.U) { mark(FloatingOperation.FleS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesIntegerRd = true) }
            is(1.U) { mark(FloatingOperation.FltS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesIntegerRd = true) }
            is(2.U) { mark(FloatingOperation.FeqS, readsFloatRs1 = true,
              readsFloatRs2 = true, writesIntegerRd = true) }
          }
        }
        is("b1111000".U) {
          when(decoded.rs2 === 0.U && funct3 === 0.U) {
            mark(FloatingOperation.FmvWX, readsIntegerRs1 = true, writesFloatRd = true)
          }
        }
      }
    }
  }

  io.decoded := decoded
}
