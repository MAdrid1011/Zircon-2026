package zircon.frontend

import chisel3._
import chisel3.util.{Cat, Fill}

class ControlPredecode extends Bundle {
  val control = Bool()
  val conditional = Bool()
  val direct = Bool()
  val indirect = Bool()
  val directTarget = UInt(32.W)
  val call = Bool()
  val ret = Bool()
}

/** Lightweight RV32 branch/JAL/JALR recognition for fetched instruction words. */
class RV32ControlPredecoder extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(32.W))
    val instruction = Input(UInt(32.W))
    val predecode = Output(new ControlPredecode)
  })

  val opcode = io.instruction(6, 0)
  val funct3 = io.instruction(14, 12)
  val rs1 = io.instruction(19, 15)
  val rd = io.instruction(11, 7)

  val legalBranchFunct3 = funct3 === "b000".U ||
    funct3 === "b001".U || funct3 === "b100".U ||
    funct3 === "b101".U || funct3 === "b110".U ||
    funct3 === "b111".U
  val conditional = opcode === "b1100011".U && legalBranchFunct3
  val jal = opcode === "b1101111".U
  val jalr = opcode === "b1100111".U && funct3 === 0.U

  val branchImmediate = Cat(
    Fill(19, io.instruction(31)),
    io.instruction(31),
    io.instruction(7),
    io.instruction(30, 25),
    io.instruction(11, 8),
    0.U(1.W)
  )
  val jumpImmediate = Cat(
    Fill(11, io.instruction(31)),
    io.instruction(31),
    io.instruction(19, 12),
    io.instruction(20),
    io.instruction(30, 21),
    0.U(1.W)
  )

  private def isLinkRegister(register: UInt): Bool =
    register === 1.U || register === 5.U

  val rdIsLink = isLinkRegister(rd)
  val rs1IsLink = isLinkRegister(rs1)
  val rasPush = (jal || jalr) && rdIsLink
  val rasPop = jalr && rs1IsLink && (!rdIsLink || rd =/= rs1)

  io.predecode.control := conditional || jal || jalr
  io.predecode.conditional := conditional
  io.predecode.direct := conditional || jal
  io.predecode.indirect := jalr
  io.predecode.directTarget := Mux(conditional,
    io.pc +% branchImmediate,
    Mux(jal, io.pc +% jumpImmediate, 0.U))
  io.predecode.call := rasPush
  io.predecode.ret := rasPop

  assert(!(io.predecode.direct && io.predecode.indirect),
    "a control instruction cannot be both direct and indirect")
  assert(!io.predecode.conditional || io.predecode.direct,
    "a conditional branch must have a direct target")
}
