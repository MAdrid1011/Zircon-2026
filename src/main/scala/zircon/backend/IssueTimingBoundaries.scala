package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** A one-entry elastic boundary used between an issue queue and a long path.
  *
  * The queue still owns age selection and recovery.  Capturing its selected
  * compact uop here prevents the ROB-head/age cone from reaching the operand
  * read and execution state in the same cycle.
  */
class UopIssueBoundary(
  config: ZirconCoreConfig,
  registered: Boolean = true
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new UopRef(config)))
    val output = Decoupled(new UopRef(config))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val flush = Input(Bool())
  })

  if (!registered) {
    io.output <> io.input
  } else {
    val validReg = RegInit(false.B)
    val uopReg = Reg(new UopRef(config))
    val recoveryBlocked = io.flush || io.squash.valid

    io.output.valid := validReg && !recoveryBlocked
    io.output.bits := uopReg
    io.input.ready := !recoveryBlocked && (!validReg || io.output.ready)

    val heldYounger = validReg && ROBTagOrder.isYounger(
      uopReg.robTag, io.squash.bits, io.robHeadTag, config)

    when(io.flush) {
      validReg := false.B
    }.elsewhen(io.squash.valid) {
      when(heldYounger) { validReg := false.B }
    }.otherwise {
      when(io.input.fire) {
        uopReg := io.input.bits
        validReg := true.B
      }.elsewhen(io.output.fire) {
        validReg := false.B
      }
    }
    when(io.input.fire) {
      assert(io.input.bits.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
        "issue boundary received an out-of-range ROB tag")
    }
    assert(!(io.input.fire && io.output.fire && !io.output.ready),
      "issue boundary cannot replace a blocked output")
  }
}

/** Elastic boundary after auxiliary PRF reads and before LongPipe input. */
class LongOperandBoundary(
  config: ZirconCoreConfig,
  registered: Boolean = true
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new LongPipeRequest(config)))
    val output = Decoupled(new LongPipeRequest(config))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val flush = Input(Bool())
  })

  if (!registered) {
    io.output <> io.input
  } else {
    val validReg = RegInit(false.B)
    val requestReg = Reg(new LongPipeRequest(config))
    val recoveryBlocked = io.flush || io.squash.valid

    io.output.valid := validReg && !recoveryBlocked
    io.output.bits := requestReg
    io.input.ready := !recoveryBlocked && (!validReg || io.output.ready)

    val heldYounger = validReg && ROBTagOrder.isYounger(
      requestReg.uop.robTag, io.squash.bits, io.robHeadTag, config)

    when(io.flush) {
      validReg := false.B
    }.elsewhen(io.squash.valid) {
      when(heldYounger) { validReg := false.B }
    }.otherwise {
      when(io.input.fire) {
        requestReg := io.input.bits
        validReg := true.B
      }.elsewhen(io.output.fire) {
        validReg := false.B
      }
    }
    when(io.input.fire) {
      assert(io.input.bits.uop.robTag(config.robIndexWidth - 1, 0) < config.robEntries.U,
        "operand boundary received an out-of-range ROB tag")
    }
  }
}
