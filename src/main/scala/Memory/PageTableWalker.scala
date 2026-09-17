import chisel3._
import chisel3.util._
import ZirconConfig.TLBParams

class PageTableWalkerIO(p: TLBParams) extends Bundle {
    val control = Input(new AddressTranslationControl(p))
    val satp = Input(UInt(32.W))
    val flush = Input(Bool())
    val instruction = new Bundle {
        val miss = Flipped(Valid(new FrontendFetchRequest(ZirconConfig.FrontendParams())))
        val refill = Valid(new TLBRefill(p))
    }
    val data = new Bundle {
        val miss = Flipped(Vec(2, Valid(new DTLBMissRequest)))
        val refill = Valid(new TLBRefill(p))
    }
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
    val vaddr = Reg(UInt(32.W))
    val asid = Reg(UInt(p.asidBits.W))
    val pteAddress = Reg(UInt(34.W))
    val inheritedGlobal = RegInit(false.B)
    val result = Reg(new TLBRefill(p))
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
    val response = Mux(sourceInstruction, io.iptw.rsp.bits, io.dptw.rsp.bits)
    val requestFire = Mux(sourceInstruction, io.iptw.req.fire, io.dptw.req.fire)
    val responseFire = responseValid && Mux(sourceInstruction, io.iptw.rsp.ready, io.dptw.rsp.ready)

    io.instruction.refill.valid := state === emitRefill && sourceInstruction && !cancelled
    io.instruction.refill.bits := result
    io.data.refill.valid := state === emitRefill && !sourceInstruction && !cancelled
    io.data.refill.bits := result
    io.busy := state =/= idle

    def pageFaultRefill(address: UInt, currentAsid: UInt): TLBRefill = {
        val value = WireDefault(0.U.asTypeOf(new TLBRefill(p)))
        value.vaddr := address
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

    def consumePte(pte: UInt, levelOne: Boolean): Unit = {
        val valid = pte(0) && !(pte(2) && !pte(1))
        val leaf = pte(1) || pte(3)
        val alignedSuperpage = !levelOne.B || pte(19, 10) === 0.U
        when(response.error) {
            result := accessFaultRefill(vaddr, asid)
            state := emitRefill
        }.elsewhen(!valid || (leaf && !alignedSuperpage) || (!leaf && !levelOne.B)) {
            result := pageFaultRefill(vaddr, asid)
            state := emitRefill
        }.elsewhen(leaf) {
            val physicalPpn = Mux(levelOne.B, Cat(pte(31, 20), 0.U(10.W)), pte(31, 10))
            val physicalAddress = Mux(
                levelOne.B,
                Cat(pte(31, 20), vaddr(21, 0)),
                Cat(pte(31, 10), vaddr(11, 0)),
            )
            result.vaddr := vaddr
            result.ppn := physicalPpn
            result.asid := asid
            result.global := inheritedGlobal || pte(5)
            result.pma := PMA.attribute(physicalAddress)
            result.permissions.read := pte(1)
            result.permissions.write := pte(2)
            result.permissions.execute := pte(3)
            result.permissions.user := pte(4)
            result.permissions.accessed := pte(6)
            result.permissions.dirty := pte(7)
            result.superpage := levelOne.B
            state := emitRefill
        }.otherwise {
            inheritedGlobal := inheritedGlobal || pte(5)
            pteAddress := Cat(pte(31, 10), 0.U(12.W)) + Cat(0.U(22.W), vaddr(21, 12), 0.U(2.W))
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
                pteAddress := Cat(io.satp(21, 0), 0.U(12.W)) +
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
                when(cancelled) { state := waitDrop }.otherwise { consumePte(response.data, levelOne = true) }
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
                when(cancelled) { state := waitDrop }.otherwise { consumePte(response.data, levelOne = false) }
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
