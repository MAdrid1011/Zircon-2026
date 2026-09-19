import chisel3._
import chisel3.util._
import ZirconConfig.TLBParams

class InstructionPTWIO(p: TLBParams) extends Bundle {
    val miss = Flipped(Valid(new FrontendFetchRequest(ZirconConfig.FrontendParams())))
    val refill = Valid(new TLBRefill(p))
}

class DataPTWIO(p: TLBParams) extends Bundle {
    val miss = Flipped(Vec(2, Valid(new DTLBMissRequest)))
    val refill = Valid(new TLBRefill(p))
}

class PageTableWalkerIO(p: TLBParams) extends Bundle {
    val control = Input(new AddressTranslationControl(p))
    val rootPpn = Input(UInt(p.ppnBits.W))
    val flush = Input(Bool())
    val instruction = new InstructionPTWIO(p)
    val data = new DataPTWIO(p)
    val iptw = new L2PTWIO
    val dptw = new L2PTWIO
    val busy = Output(Bool())
}

/** Shared Sv32 walker. A/D bits are checked and faulted rather than updated in memory. */
class PageTableWalker(val p: TLBParams = TLBParams()) extends Module {
    val io = IO(new PageTableWalkerIO(p))

    val states = Enum(7)
    val idle = states(0)
    val levelOneRequest = states(1)
    val levelOneResponse = states(2)
    val levelZeroRequest = states(3)
    val levelZeroResponse = states(4)
    val emitRefill = states(5)
    val waitDrop = states(6)
    val state = RegInit(idle)
    val sourceInstruction = RegInit(false.B)
    val sourceLane = RegInit(0.U(1.W))
    val vaddr = RegInit(0.U(32.W))
    val asid = RegInit(0.U(p.asidBits.W))
    val pteAddress = RegInit(0.U(34.W))
    val inheritedGlobal = RegInit(false.B)
    val result = RegInit(0.U.asTypeOf(new TLBRefill(p)))
    val cancelled = RegInit(false.B)
    val preferInstruction = RegInit(true.B)

    val instructionMiss = io.instruction.miss.valid
    val dataMiss = io.data.miss.map(_.valid).reduce(_ || _)
    val chooseInstruction = instructionMiss && (!dataMiss || preferInstruction)
    val selectedDataLane = PriorityEncoder(io.data.miss.map(_.valid))
    val selectedVaddr = Mux(
        chooseInstruction,
        io.instruction.miss.bits.pc,
        Mux1H(io.data.miss.map(_.valid), io.data.miss.map(_.bits.vaddr)),
    )

    io.iptw.req.valid := state === levelOneRequest || state === levelZeroRequest
    io.iptw.req.bits.paddr := pteAddress
    io.iptw.rsp.ready := state === levelOneResponse || state === levelZeroResponse
    io.dptw.req.valid := io.iptw.req.valid
    io.dptw.req.bits.paddr := pteAddress
    io.dptw.rsp.ready := io.iptw.rsp.ready
    when(!sourceInstruction) {
        io.iptw.req.valid := false.B
        io.iptw.rsp.ready := false.B
    }.otherwise {
        io.dptw.req.valid := false.B
        io.dptw.rsp.ready := false.B
    }

    val responseValid = Mux(sourceInstruction, io.iptw.rsp.valid, io.dptw.rsp.valid)
    val responseError = Mux(sourceInstruction, io.iptw.rsp.bits.error, io.dptw.rsp.bits.error)
    val responsePte = Wire(new Sv32Pte)
    responsePte := Mux(sourceInstruction, io.iptw.rsp.bits.pte, io.dptw.rsp.bits.pte)
    val requestFire = Mux(sourceInstruction, io.iptw.req.fire, io.dptw.req.fire)
    val responseFire = responseValid && Mux(sourceInstruction, io.iptw.rsp.ready, io.dptw.rsp.ready)

    io.instruction.refill.valid := state === emitRefill && sourceInstruction && !cancelled
    io.instruction.refill.bits := result
    io.data.refill.valid := state === emitRefill && !sourceInstruction && !cancelled
    io.data.refill.bits := result
    io.busy := state =/= idle

    def pageFaultRefill(address: UInt, currentAsid: UInt): TLBRefill = {
        val value = WireDefault(0.U.asTypeOf(new TLBRefill(p)))
        value.vpn := address(31, 12)
        value.asid := currentAsid
        value.pma := PMAAttribute.cached
        value
    }

    def accessFaultRefill(address: UInt, currentAsid: UInt): TLBRefill = {
        val value = WireDefault(pageFaultRefill(address, currentAsid))
        value.pma := PMAAttribute.invalid
        value.permissions.read := true.B
        value.permissions.write := true.B
        value.permissions.execute := true.B
        value.permissions.user := true.B
        value.permissions.accessed := true.B
        value.permissions.dirty := true.B
        value
    }

    def consumePte(pte: Sv32Pte, levelOne: Boolean): Unit = {
        val valid = pte.valid && !(pte.write && !pte.read)
        val leaf = pte.read || pte.execute
        val alignedSuperpage = !levelOne.B || pte.ppn(9, 0) === 0.U
        when(responseError) {
            result := accessFaultRefill(vaddr, asid)
            state := emitRefill
        }.elsewhen(!valid || (leaf && !alignedSuperpage) || (!leaf && !levelOne.B)) {
            result := pageFaultRefill(vaddr, asid)
            state := emitRefill
        }.elsewhen(leaf) {
            val physicalPpn = Mux(levelOne.B, Cat(pte.ppn(21, 10), 0.U(10.W)), pte.ppn)
            val physicalAddress = Mux(
                levelOne.B,
                Cat(pte.ppn(21, 10), vaddr(21, 0)),
                Cat(pte.ppn, vaddr(11, 0)),
            )
            result.vpn := vaddr(31, 12)
            result.ppn := physicalPpn
            result.asid := asid
            result.global := inheritedGlobal || pte.global
            result.pma := PMA.attribute(physicalAddress)
            result.permissions.read := pte.read
            result.permissions.write := pte.write
            result.permissions.execute := pte.execute
            result.permissions.user := pte.user
            result.permissions.accessed := pte.accessed
            result.permissions.dirty := pte.dirty
            result.superpage := levelOne.B
            state := emitRefill
        }.otherwise {
            inheritedGlobal := inheritedGlobal || pte.global
            pteAddress := Cat(pte.ppn, 0.U(12.W)) + Cat(0.U(22.W), vaddr(21, 12), 0.U(2.W))
            state := levelZeroRequest
        }
    }

    when(io.flush && state =/= idle) {
        cancelled := true.B
    }

    switch(state) {
        is(idle) {
            cancelled := false.B
            when(instructionMiss || dataMiss) {
                sourceInstruction := chooseInstruction
                sourceLane := selectedDataLane
                vaddr := selectedVaddr
                asid := io.control.asid
                pteAddress := Cat(io.rootPpn, 0.U(12.W)) +
                    Cat(0.U(22.W), selectedVaddr(31, 22), 0.U(2.W))
                inheritedGlobal := false.B
                result := pageFaultRefill(selectedVaddr, io.control.asid)
                preferInstruction := !chooseInstruction
                state := levelOneRequest
            }
        }
        is(levelOneRequest) {
            when(cancelled) {
                state := waitDrop
            }.elsewhen(requestFire) {
                state := levelOneResponse
            }
        }
        is(levelOneResponse) {
            when(responseFire) {
                when(cancelled) { state := waitDrop }.otherwise { consumePte(responsePte, levelOne = true) }
            }
        }
        is(levelZeroRequest) {
            when(cancelled) {
                state := waitDrop
            }.elsewhen(requestFire) {
                state := levelZeroResponse
            }
        }
        is(levelZeroResponse) {
            when(responseFire) {
                when(cancelled) { state := waitDrop }.otherwise { consumePte(responsePte, levelOne = false) }
            }
        }
        is(emitRefill) {
            state := waitDrop
        }
        is(waitDrop) {
            val sourceStillAsserted = Mux(
                sourceInstruction,
                io.instruction.miss.valid && io.instruction.miss.bits.pc === vaddr,
                io.data.miss(sourceLane).valid && io.data.miss(sourceLane).bits.vaddr === vaddr,
            )
            when(!sourceStillAsserted || cancelled) {
                state := idle
            }
        }
    }
}
