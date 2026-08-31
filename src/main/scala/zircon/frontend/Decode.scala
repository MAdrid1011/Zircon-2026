package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.backend.{EndpointMask, ExecutionEndpoint, UopClass}

object IntOperation extends ChiselEnum {
  val Invalid, Lui, Auipc = Value
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value
  val Mul, Mulh, Mulhsu, Mulhu, Div, Divu, Rem, Remu = Value
  val Beq, Bne, Blt, Bge, Bltu, Bgeu, Jal, Jalr = Value
  val Lb, Lh, Lw, Lbu, Lhu, Sb, Sh, Sw = Value
  val LrW, ScW, AmoSwapW, AmoAddW, AmoXorW, AmoAndW, AmoOrW, AmoMinW,
    AmoMaxW, AmoMinuW, AmoMaxuW = Value
  val Fence, FenceI, Ecall, Ebreak, Mret, Wfi = Value
  val Csrrw, Csrrs, Csrrc, Csrrwi, Csrrsi, Csrrci = Value
}

class DecodedInstruction extends Bundle {
  val legal = Bool()
  val operation = IntOperation()
  val uopClass = UopClass()
  val allowedEndpoints = UInt(EndpointMask.Width.W)

  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val readsRs1 = Bool()
  val readsRs2 = Bool()
  val writesRd = Bool()
  val operandBImmediate = Bool()
  val immediate = UInt(32.W)

  val csrAddress = UInt(12.W)
  val csrImmediate = UInt(5.W)
  val csrRead = Bool()
  val csrWrite = Bool()

  val isControl = Bool()
  val isMemory = Bool()
  val isFenceI = Bool()
  val atomicAq = Bool()
  val atomicRl = Bool()
}

class RV32IDecoder extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val decoded = Output(new DecodedInstruction)
  })

  private val instruction = io.instruction
  private val opcode = instruction(6, 0)
  private val funct3 = instruction(14, 12)
  private val funct7 = instruction(31, 25)

  private val iImmediate = Cat(Fill(20, instruction(31)), instruction(31, 20))
  private val sImmediate = Cat(
    Fill(20, instruction(31)),
    instruction(31, 25),
    instruction(11, 7)
  )
  private val bImmediate = Cat(
    Fill(19, instruction(31)),
    instruction(31),
    instruction(7),
    instruction(30, 25),
    instruction(11, 8),
    0.U(1.W)
  )
  private val uImmediate = Cat(instruction(31, 12), 0.U(12.W))
  private val jImmediate = Cat(
    Fill(11, instruction(31)),
    instruction(31),
    instruction(19, 12),
    instruction(20),
    instruction(30, 21),
    0.U(1.W)
  )
  private val shiftImmediate = Cat(0.U(27.W), instruction(24, 20))

  val decoded = WireDefault(0.U.asTypeOf(new DecodedInstruction))
  decoded.operation := IntOperation.Invalid
  decoded.uopClass := UopClass.Integer
  decoded.rs1 := instruction(19, 15)
  decoded.rs2 := instruction(24, 20)
  decoded.rd := instruction(11, 7)
  decoded.csrAddress := instruction(31, 20)
  decoded.csrImmediate := instruction(19, 15)

  private def mark(
      operation: IntOperation.Type,
      uopClass: UopClass.Type,
      endpoints: Int,
      readsRs1: Boolean = false,
      readsRs2: Boolean = false,
      writesRd: Boolean = false,
      operandBImmediate: Boolean = false,
      immediate: UInt = 0.U(32.W),
      isControl: Boolean = false,
      isMemory: Boolean = false
  ): Unit = {
    decoded.legal := true.B
    decoded.operation := operation
    decoded.uopClass := uopClass
    decoded.allowedEndpoints := endpoints.U(EndpointMask.Width.W)
    decoded.readsRs1 := readsRs1.B
    decoded.readsRs2 := readsRs2.B
    decoded.writesRd := writesRd.B
    decoded.operandBImmediate := operandBImmediate.B
    decoded.immediate := immediate
    decoded.isControl := isControl.B
    decoded.isMemory := isMemory.B
  }

  switch(opcode) {
    is("b0110111".U) { // LUI
      mark(IntOperation.Lui, UopClass.Integer, EndpointMask.IntegerSimple,
        writesRd = true, operandBImmediate = true, immediate = uImmediate)
    }
    is("b0010111".U) { // AUIPC
      mark(IntOperation.Auipc, UopClass.Integer, EndpointMask.IntegerSimple,
        writesRd = true, operandBImmediate = true, immediate = uImmediate)
    }
    is("b1101111".U) { // JAL
      mark(IntOperation.Jal, UopClass.Branch, EndpointMask.E0,
        writesRd = true, immediate = jImmediate, isControl = true)
    }
    is("b1100111".U) { // JALR
      when(funct3 === 0.U) {
        mark(IntOperation.Jalr, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, writesRd = true, operandBImmediate = true,
          immediate = iImmediate, isControl = true)
      }
    }
    is("b1100011".U) { // conditional branches
      switch(funct3) {
        is("b000".U) { mark(IntOperation.Beq, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, readsRs2 = true, immediate = bImmediate, isControl = true) }
        is("b001".U) { mark(IntOperation.Bne, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, readsRs2 = true, immediate = bImmediate, isControl = true) }
        is("b100".U) { mark(IntOperation.Blt, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, readsRs2 = true, immediate = bImmediate, isControl = true) }
        is("b101".U) { mark(IntOperation.Bge, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, readsRs2 = true, immediate = bImmediate, isControl = true) }
        is("b110".U) { mark(IntOperation.Bltu, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, readsRs2 = true, immediate = bImmediate, isControl = true) }
        is("b111".U) { mark(IntOperation.Bgeu, UopClass.Branch, EndpointMask.E0,
          readsRs1 = true, readsRs2 = true, immediate = bImmediate, isControl = true) }
      }
    }
    is("b0000011".U) { // loads
      switch(funct3) {
        is("b000".U) { mark(IntOperation.Lb, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          readsRs1 = true, writesRd = true, operandBImmediate = true,
          immediate = iImmediate, isMemory = true) }
        is("b001".U) { mark(IntOperation.Lh, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          readsRs1 = true, writesRd = true, operandBImmediate = true,
          immediate = iImmediate, isMemory = true) }
        is("b010".U) { mark(IntOperation.Lw, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          readsRs1 = true, writesRd = true, operandBImmediate = true,
          immediate = iImmediate, isMemory = true) }
        is("b100".U) { mark(IntOperation.Lbu, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          readsRs1 = true, writesRd = true, operandBImmediate = true,
          immediate = iImmediate, isMemory = true) }
        is("b101".U) { mark(IntOperation.Lhu, UopClass.Load, EndpointMask.CacheableLoadCandidate,
          readsRs1 = true, writesRd = true, operandBImmediate = true,
          immediate = iImmediate, isMemory = true) }
      }
    }
    is("b0100011".U) { // stores
      switch(funct3) {
        is("b000".U) { mark(IntOperation.Sb, UopClass.Store, EndpointMask.M0,
          readsRs1 = true, readsRs2 = true, operandBImmediate = true,
          immediate = sImmediate, isMemory = true) }
        is("b001".U) { mark(IntOperation.Sh, UopClass.Store, EndpointMask.M0,
          readsRs1 = true, readsRs2 = true, operandBImmediate = true,
          immediate = sImmediate, isMemory = true) }
        is("b010".U) { mark(IntOperation.Sw, UopClass.Store, EndpointMask.M0,
          readsRs1 = true, readsRs2 = true, operandBImmediate = true,
          immediate = sImmediate, isMemory = true) }
      }
    }
    is("b0101111".U) { // RV32A atomics
      when(funct3 === "b010".U) {
        decoded.atomicAq := instruction(26)
        decoded.atomicRl := instruction(25)
        switch(instruction(31, 27)) {
          is("b00010".U) { // LR.W reserves a word and requires rs2 == x0.
            when(decoded.rs2 === 0.U) {
              mark(IntOperation.LrW, UopClass.Atomic, EndpointMask.M0,
                readsRs1 = true, writesRd = true, isMemory = true)
            }
          }
          is("b00011".U) { mark(IntOperation.ScW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b00001".U) { mark(IntOperation.AmoSwapW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b00000".U) { mark(IntOperation.AmoAddW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b00100".U) { mark(IntOperation.AmoXorW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b01100".U) { mark(IntOperation.AmoAndW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b01000".U) { mark(IntOperation.AmoOrW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b10000".U) { mark(IntOperation.AmoMinW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b10100".U) { mark(IntOperation.AmoMaxW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b11000".U) { mark(IntOperation.AmoMinuW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
          is("b11100".U) { mark(IntOperation.AmoMaxuW, UopClass.Atomic, EndpointMask.M0,
            readsRs1 = true, readsRs2 = true, writesRd = true, isMemory = true) }
        }
      }
    }
    is("b0010011".U) { // OP-IMM
      switch(funct3) {
        is("b000".U) { mark(IntOperation.Add, UopClass.Integer, EndpointMask.IntegerSimple,
          readsRs1 = true, writesRd = true, operandBImmediate = true, immediate = iImmediate) }
        is("b010".U) { mark(IntOperation.Slt, UopClass.Integer, EndpointMask.IntegerSimple,
          readsRs1 = true, writesRd = true, operandBImmediate = true, immediate = iImmediate) }
        is("b011".U) { mark(IntOperation.Sltu, UopClass.Integer, EndpointMask.IntegerSimple,
          readsRs1 = true, writesRd = true, operandBImmediate = true, immediate = iImmediate) }
        is("b100".U) { mark(IntOperation.Xor, UopClass.Integer, EndpointMask.IntegerSimple,
          readsRs1 = true, writesRd = true, operandBImmediate = true, immediate = iImmediate) }
        is("b110".U) { mark(IntOperation.Or, UopClass.Integer, EndpointMask.IntegerSimple,
          readsRs1 = true, writesRd = true, operandBImmediate = true, immediate = iImmediate) }
        is("b111".U) { mark(IntOperation.And, UopClass.Integer, EndpointMask.IntegerSimple,
          readsRs1 = true, writesRd = true, operandBImmediate = true, immediate = iImmediate) }
        is("b001".U) {
          when(funct7 === 0.U) {
            mark(IntOperation.Sll, UopClass.Integer, EndpointMask.IntegerSimple,
              readsRs1 = true, writesRd = true, operandBImmediate = true,
              immediate = shiftImmediate)
          }
        }
        is("b101".U) {
          when(funct7 === 0.U) {
            mark(IntOperation.Srl, UopClass.Integer, EndpointMask.IntegerSimple,
              readsRs1 = true, writesRd = true, operandBImmediate = true,
              immediate = shiftImmediate)
          }.elsewhen(funct7 === "b0100000".U) {
            mark(IntOperation.Sra, UopClass.Integer, EndpointMask.IntegerSimple,
              readsRs1 = true, writesRd = true, operandBImmediate = true,
              immediate = shiftImmediate)
          }
        }
      }
    }
    is("b0110011".U) { // OP / RV32M
      when(funct7 === 0.U) {
        switch(funct3) {
          is("b000".U) { mark(IntOperation.Add, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b001".U) { mark(IntOperation.Sll, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b010".U) { mark(IntOperation.Slt, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b011".U) { mark(IntOperation.Sltu, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b100".U) { mark(IntOperation.Xor, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b101".U) { mark(IntOperation.Srl, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b110".U) { mark(IntOperation.Or, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b111".U) { mark(IntOperation.And, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
        }
      }.elsewhen(funct7 === "b0100000".U) {
        when(funct3 === 0.U) {
          mark(IntOperation.Sub, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true)
        }.elsewhen(funct3 === "b101".U) {
          mark(IntOperation.Sra, UopClass.Integer, EndpointMask.IntegerSimple,
            readsRs1 = true, readsRs2 = true, writesRd = true)
        }
      }.elsewhen(funct7 === "b0000001".U) {
        switch(funct3) {
          is("b000".U) { mark(IntOperation.Mul, UopClass.Multiply, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b001".U) { mark(IntOperation.Mulh, UopClass.Multiply, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b010".U) { mark(IntOperation.Mulhsu, UopClass.Multiply, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b011".U) { mark(IntOperation.Mulhu, UopClass.Multiply, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b100".U) { mark(IntOperation.Div, UopClass.Divide, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b101".U) { mark(IntOperation.Divu, UopClass.Divide, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b110".U) { mark(IntOperation.Rem, UopClass.Divide, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
          is("b111".U) { mark(IntOperation.Remu, UopClass.Divide, EndpointMask.E2,
            readsRs1 = true, readsRs2 = true, writesRd = true) }
        }
      }
    }
    is("b0001111".U) { // FENCE / FENCE.I
      when(funct3 === 0.U) {
        // Base implementations conservatively treat reserved fm/pred/succ
        // combinations as FENCE and ignore the reserved rs1/rd fields.
        mark(IntOperation.Fence, UopClass.System, EndpointMask.E0)
      }.elsewhen(funct3 === 1.U) {
        // Zifencei requires implementations to ignore the reserved immediate,
        // rs1, and rd fields for forward compatibility.
        mark(IntOperation.FenceI, UopClass.System, EndpointMask.E0)
        decoded.isFenceI := true.B
      }
    }
    is("b1110011".U) { // SYSTEM / Zicsr
      when(funct3 === 0.U && instruction(19, 7) === 0.U) {
        switch(instruction(31, 20)) {
          is("h000".U) { mark(IntOperation.Ecall, UopClass.System, EndpointMask.E0) }
          is("h001".U) { mark(IntOperation.Ebreak, UopClass.System, EndpointMask.E0) }
          is("h302".U) { mark(IntOperation.Mret, UopClass.System, EndpointMask.E0,
            isControl = true) }
          is("h105".U) { mark(IntOperation.Wfi, UopClass.System, EndpointMask.E0) }
        }
      }.otherwise {
        switch(funct3) {
          is("b001".U) {
            mark(IntOperation.Csrrw, UopClass.Csr, EndpointMask.E0,
              readsRs1 = true, writesRd = true)
            decoded.csrRead := decoded.rd =/= 0.U
            decoded.csrWrite := true.B
          }
          is("b010".U) {
            mark(IntOperation.Csrrs, UopClass.Csr, EndpointMask.E0,
              readsRs1 = true, writesRd = true)
            decoded.csrRead := true.B
            decoded.csrWrite := decoded.rs1 =/= 0.U
          }
          is("b011".U) {
            mark(IntOperation.Csrrc, UopClass.Csr, EndpointMask.E0,
              readsRs1 = true, writesRd = true)
            decoded.csrRead := true.B
            decoded.csrWrite := decoded.rs1 =/= 0.U
          }
          is("b101".U) {
            mark(IntOperation.Csrrwi, UopClass.Csr, EndpointMask.E0, writesRd = true)
            decoded.csrRead := decoded.rd =/= 0.U
            decoded.csrWrite := true.B
          }
          is("b110".U) {
            mark(IntOperation.Csrrsi, UopClass.Csr, EndpointMask.E0, writesRd = true)
            decoded.csrRead := true.B
            decoded.csrWrite := decoded.csrImmediate =/= 0.U
          }
          is("b111".U) {
            mark(IntOperation.Csrrci, UopClass.Csr, EndpointMask.E0, writesRd = true)
            decoded.csrRead := true.B
            decoded.csrWrite := decoded.csrImmediate =/= 0.U
          }
        }
      }
    }
  }

  io.decoded := decoded
}

class EndpointAdmission extends Module {
  val io = IO(new Bundle {
    val decoded = Input(new DecodedInstruction)
    val endpoint = Input(ExecutionEndpoint())
    val allowed = Output(Bool())
  })

  io.allowed := io.decoded.legal && io.decoded.allowedEndpoints(io.endpoint.asUInt)
}
