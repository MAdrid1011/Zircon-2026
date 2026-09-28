import chisel3._
import chisel3.util._
import ZirconConfig.TLBParams

object PMAAttribute {
    val cached = 0.U(2.W)
    val uncached = 1.U(2.W)
    val device = 2.U(2.W)
    val invalid = 3.U(2.W)
}

class TLBPermissions extends Bundle {
    val read = Bool()
    val write = Bool()
    val execute = Bool()
    val user = Bool()
    val accessed = Bool()
    val dirty = Bool()
}

class TLBLookupRequest(p: TLBParams, vaddrLowBits: Int) extends Bundle {
    require(vaddrLowBits >= 0 && vaddrLowBits < p.vaddrBits)
    val vaddr = UInt((p.vaddrBits - vaddrLowBits).W)
}

class TLBLookupResponse(p: TLBParams, paddrLowBits: Int = 0) extends Bundle {
    require(paddrLowBits >= 0 && paddrLowBits < p.paddrBits)
    val hit = Bool()
    val paddr = UInt((p.paddrBits - paddrLowBits).W)
    val pma = UInt(p.pmaBits.W)
    val permissions = new TLBPermissions
    val superpage = Bool()
}

class TLBRefill(p: TLBParams) extends Bundle {
    val vpn = UInt((p.vaddrBits - 12).W)
    val ppn = UInt(p.ppnBits.W)
    val asid = UInt(p.asidBits.W)
    val global = Bool()
    val pma = UInt(p.pmaBits.W)
    val permissions = new TLBPermissions
    val superpage = Bool()
}

class TLBScopeUpdate(p: TLBParams) extends Bundle {
    val asid = UInt(p.asidBits.W)
}

private class TLB4KEntry(p: TLBParams) extends Bundle {
    val valid = Bool()
    val inScope = Bool()
    val tag = UInt(p.normalTagBits.W)
    val asid = UInt(p.asidBits.W)
    val global = Bool()
    val ppn = UInt(p.ppnBits.W)
    val pma = UInt(p.pmaBits.W)
    val permissions = new TLBPermissions
}

private class TLB4MEntry(p: TLBParams) extends Bundle {
    val valid = Bool()
    val inScope = Bool()
    val vpn1 = UInt(10.W)
    val asid = UInt(p.asidBits.W)
    val global = Bool()
    val ppn1 = UInt((p.ppnBits - 10).W)
    val pma = UInt(p.pmaBits.W)
    val permissions = new TLBPermissions
}

class TLBIO(p: TLBParams, paddrLowBits: Int) extends Bundle {
    val lookup = Flipped(Vec(p.queryPorts, Valid(new TLBLookupRequest(p, paddrLowBits))))
    val response = Output(Vec(p.queryPorts, new TLBLookupResponse(p, paddrLowBits)))
    val refill = Flipped(Valid(new TLBRefill(p)))
    val scopeUpdate = Flipped(Valid(new TLBScopeUpdate(p)))
    val flush = Input(Bool())
}

/** Small combinational Sv32 TLB with a set-associative 4 KiB bank and a fully associative 4 MiB bank.
  * `inScope` removes ASID/global comparisons from the lookup path. The owner must pulse `scopeUpdate`
  * with the accepted satp ASID whenever the active address space changes.
  */
class TLB(val p: TLBParams = TLBParams(), val paddrLowBits: Int = 0) extends Module {
    val io = IO(new TLBIO(p, paddrLowBits))

    val currentAsid = RegInit(0.U(p.asidBits.W))

    private val normal = Reg(Vec(p.sets, Vec(p.ways, new TLB4KEntry(p))))
    val normalPlru = RegInit(VecInit.fill(p.sets)(0.U(3.W)))

    private val superpage = Reg(Vec(p.superEntries, new TLB4MEntry(p)))
    val superReplace = RegInit(0.U(p.superIndexBits.W))

    def setIndex(vaddr: UInt): UInt = vaddr(12 + p.setBits - 1, 12)
    def normalTag(vaddr: UInt): UInt = vaddr(31, 12 + p.setBits)
    def sameAddressSpace(globalA: Bool, asidA: UInt, globalB: Bool, asidB: UInt): Bool =
        globalA || globalB || asidA === asidB
    def plruVictim(state: UInt): UInt = Mux(
        state(0),
        Mux(state(2), 3.U, 2.U),
        Mux(state(1), 1.U, 0.U)
    )
    def plruAfter(state: UInt, way: UInt): UInt = MuxLookup(way, state)(Seq(
        0.U -> Cat(state(2), 1.U(1.W), 1.U(1.W)),
        1.U -> Cat(state(2), 0.U(1.W), 1.U(1.W)),
        2.U -> Cat(1.U(1.W), state(1), 0.U(1.W)),
        3.U -> Cat(0.U(1.W), state(1), 0.U(1.W))
    ))

    val normalLookupHit = Wire(Vec(p.queryPorts, Vec(p.ways, Bool())))
    val superLookupHit = Wire(Vec(p.queryPorts, Vec(p.superEntries, Bool())))
    val normalLookupSet = Wire(Vec(p.queryPorts, UInt(p.setBits.W)))
    for (port <- 0 until p.queryPorts) {
        val request = io.lookup(port)
        val vaddr = if (paddrLowBits == 0) request.bits.vaddr
        else Cat(request.bits.vaddr, 0.U(paddrLowBits.W))
        val index = setIndex(vaddr)
        normalLookupSet(port) := index
        val setSelect = UIntToOH(index, p.sets)
        val normalMatches = VecInit((0 until p.sets).map { set =>
            VecInit((0 until p.ways).map { way =>
                setSelect(set) && normal(set)(way).inScope &&
                    normal(set)(way).tag === normalTag(vaddr)
            })
        })
        for (way <- 0 until p.ways) {
            normalLookupHit(port)(way) := VecInit((0 until p.sets).map(set =>
                normalMatches(set)(way))).asUInt.orR
        }
        for (entry <- 0 until p.superEntries) {
            superLookupHit(port)(entry) := superpage(entry).inScope &&
                superpage(entry).vpn1 === vaddr(31, 22)
        }

        val normalHit = normalLookupHit(port).asUInt.orR
        val superHit = superLookupHit(port).asUInt.orR
        val normalPayload = Mux1H(for {
            set <- 0 until p.sets
            way <- 0 until p.ways
        } yield normalMatches(set)(way) -> normal(set)(way))
        val superPayload = Mux1H(superLookupHit(port), superpage)
        val response = io.response(port)
        val matched = normalHit || superHit
        // The response is a side-effect-free combinational probe. Callers use
        // request.valid to qualify consumption and replacement-state updates.
        response.hit := matched
        response.superpage := superHit
        val physicalAddress = Mux(
            superHit,
            Cat(superPayload.ppn1, vaddr(21, 0)),
            Cat(normalPayload.ppn, vaddr(11, 0))
        )
        response.paddr := physicalAddress(p.paddrBits - 1, paddrLowBits)
        response.pma := Mux(superHit, superPayload.pma, normalPayload.pma)
        response.permissions := Mux(superHit, superPayload.permissions, normalPayload.permissions)
        // Payload is consumed only on a hit. Keep the match reduction off all
        // payload bits so a miss need not clear their output paths.

        when(request.valid) {
            assert(PopCount(normalLookupHit(port)) <= 1.U, "TLB: multiple 4 KiB entries matched")
            assert(PopCount(superLookupHit(port)) <= 1.U, "TLB: multiple 4 MiB entries matched")
            assert(!(normalHit && superHit), "TLB: both page sizes matched one request")
        }
    }

    val incomingRefill = io.refill.bits
    val incomingRefillSet = incomingRefill.vpn(p.setBits - 1, 0)
    val incomingRefillTag = incomingRefill.vpn(19, p.setBits)
    val incomingSetSelect = UIntToOH(incomingRefillSet, p.sets)
    val activeAsid = Mux(io.scopeUpdate.valid, io.scopeUpdate.bits.asid, currentAsid)
    val incomingNormalMatches = VecInit((0 until p.sets).map { set =>
        VecInit((0 until p.ways).map { way =>
            val entry = normal(set)(way)
            entry.valid && entry.tag === incomingRefillTag &&
                sameAddressSpace(entry.global, entry.asid, incomingRefill.global, incomingRefill.asid)
        })
    })
    val incomingNormalWay = VecInit((0 until p.sets).map { set =>
        val matches = incomingNormalMatches(set)
        val invalid = VecInit(normal(set).map(entry => !entry.valid))
        Mux(matches.asUInt.orR, PriorityEncoder(matches),
            Mux(invalid.asUInt.orR, PriorityEncoder(invalid), plruVictim(normalPlru(set))))
    })
    val incomingSuperMatches = VecInit((0 until p.superEntries).map(entry =>
        superpage(entry).valid && superpage(entry).vpn1 === incomingRefill.vpn(19, 10) &&
            sameAddressSpace(
                superpage(entry).global,
                superpage(entry).asid,
                incomingRefill.global,
                incomingRefill.asid,
            )
    ))
    val incomingSuperInvalid = VecInit((0 until p.superEntries).map(entry => !superpage(entry).valid))
    val incomingSuperEntry = Mux(
        incomingSuperMatches.asUInt.orR,
        PriorityEncoder(incomingSuperMatches),
        Mux(incomingSuperInvalid.asUInt.orR, PriorityEncoder(incomingSuperInvalid), superReplace)
    )

    // Refill selection is isolated from the wide TLB state update. The PTW
    // produces one refill pulse, this stage records its destinations and the
    // following edge installs the payload without re-running address compares.
    private val normalEntryCount = p.sets * p.ways
    val refillStageValid = RegInit(false.B)
    val refillStage = Reg(new TLBRefill(p))
    val refillNormalWrite = Reg(UInt(normalEntryCount.W))
    val refillNormalInvalidate = Reg(UInt(normalEntryCount.W))
    val refillNormalPlruWrite = Reg(UInt(p.sets.W))
    val refillNormalPlruValue = Reg(UInt(3.W))
    val refillSuperWrite = Reg(UInt(p.superEntries.W))
    val refillSuperInvalidate = Reg(UInt(p.superEntries.W))
    val refillSuperReplaceValue = Reg(UInt(p.superIndexBits.W))

    val incomingNormalWrite = VecInit.tabulate(p.sets, p.ways) { (set, way) =>
        incomingSetSelect(set) && incomingNormalWay(set) === way.U
    }.asUInt
    val incomingNormalInvalidate = VecInit.tabulate(p.sets, p.ways) { (set, way) =>
        val entry = normal(set)(way)
        Mux(
            incomingRefill.superpage,
            entry.valid && entry.tag(p.normalTagBits - 1, 10 - p.setBits) === incomingRefill.vpn(19, 10) &&
                sameAddressSpace(entry.global, entry.asid, incomingRefill.global, incomingRefill.asid),
            incomingSetSelect(set) && incomingNormalMatches(set)(way) && incomingNormalWay(set) =/= way.U,
        )
    }.asUInt
    val incomingSuperWrite = UIntToOH(incomingSuperEntry, p.superEntries)
    val incomingSuperInvalidate = VecInit.tabulate(p.superEntries) { entryIndex =>
        val entry = superpage(entryIndex)
        Mux(
            incomingRefill.superpage,
            incomingSuperMatches(entryIndex) && incomingSuperEntry =/= entryIndex.U,
            entry.valid && entry.vpn1 === incomingRefill.vpn(19, 10) &&
                sameAddressSpace(entry.global, entry.asid, incomingRefill.global, incomingRefill.asid),
        )
    }.asUInt

    when(io.scopeUpdate.valid) {
        currentAsid := io.scopeUpdate.bits.asid
    }

    when(reset.asBool || io.flush) {
        refillStageValid := false.B
        normal.foreach(_.foreach { entry =>
            entry.valid := false.B
            entry.inScope := false.B
        })
        superpage.foreach { entry =>
            entry.valid := false.B
            entry.inScope := false.B
        }
        normalPlru.foreach(_ := 0.U)
        superReplace := 0.U
    }.otherwise {
        refillStageValid := io.refill.valid
        when(io.refill.valid) {
            refillStage := incomingRefill
            refillNormalWrite := incomingNormalWrite
            refillNormalInvalidate := incomingNormalInvalidate
            refillNormalPlruWrite := incomingSetSelect
            refillNormalPlruValue := Mux1H(incomingSetSelect.asBools,
                (0 until p.sets).map(set => plruAfter(normalPlru(set), incomingNormalWay(set))))
            refillSuperWrite := incomingSuperWrite
            refillSuperInvalidate := incomingSuperInvalidate
            refillSuperReplaceValue := incomingSuperEntry + 1.U
        }

        when(io.scopeUpdate.valid) {
            for (set <- 0 until p.sets; way <- 0 until p.ways) {
                normal(set)(way).inScope := normal(set)(way).valid &&
                    (normal(set)(way).global || normal(set)(way).asid === io.scopeUpdate.bits.asid)
            }
            for (entry <- 0 until p.superEntries) {
                superpage(entry).inScope := superpage(entry).valid &&
                    (superpage(entry).global || superpage(entry).asid === io.scopeUpdate.bits.asid)
            }
        }

        // Lookup updates are intentionally approximate when several ports touch one set in one cycle.
        // The highest-numbered hit wins; replacement quality never affects translation correctness.
        for (port <- 0 until p.queryPorts) {
            val lookupPlru = Cat(
                Mux1H((0 until p.sets).map(set => (normalLookupSet(port) === set.U) -> normalPlru(set)(2))),
                Mux1H((0 until p.sets).map(set => (normalLookupSet(port) === set.U) -> normalPlru(set)(1))),
                0.U(1.W),
            )
            when(io.lookup(port).valid && normalLookupHit(port).asUInt.orR) {
                normalPlru(normalLookupSet(port)) :=
                    plruAfter(lookupPlru, PriorityEncoder(normalLookupHit(port)))
            }
        }

        when(refillStageValid) {
            when(refillStage.superpage) {
                // A new superpage supersedes every overlapping small-page translation in the same address space.
                for (set <- 0 until p.sets; way <- 0 until p.ways) {
                    when(refillNormalInvalidate(set * p.ways + way)) {
                        normal(set)(way).valid := false.B
                        normal(set)(way).inScope := false.B
                    }
                }
                for (entry <- 0 until p.superEntries) {
                    when(refillSuperInvalidate(entry)) {
                        superpage(entry).valid := false.B
                        superpage(entry).inScope := false.B
                    }
                    when(refillSuperWrite(entry)) {
                        superpage(entry).vpn1 := refillStage.vpn(19, 10)
                        superpage(entry).asid := refillStage.asid
                        superpage(entry).global := refillStage.global
                        superpage(entry).ppn1 := refillStage.ppn(p.ppnBits - 1, 10)
                        superpage(entry).pma := refillStage.pma
                        superpage(entry).permissions := refillStage.permissions
                        superpage(entry).valid := true.B
                        superpage(entry).inScope := refillStage.global || refillStage.asid === activeAsid
                    }
                }
                superReplace := refillSuperReplaceValue
            }.otherwise {
                // A small-page refill removes an overlapping superpage for the affected address space.
                for (entry <- 0 until p.superEntries) {
                    when(refillSuperInvalidate(entry)) {
                        superpage(entry).valid := false.B
                        superpage(entry).inScope := false.B
                    }
                }
                for (set <- 0 until p.sets; way <- 0 until p.ways) {
                    when(refillNormalInvalidate(set * p.ways + way)) {
                        normal(set)(way).valid := false.B
                        normal(set)(way).inScope := false.B
                    }
                    when(refillNormalWrite(set * p.ways + way)) {
                        normal(set)(way).tag := refillStage.vpn(19, p.setBits)
                        normal(set)(way).asid := refillStage.asid
                        normal(set)(way).global := refillStage.global
                        normal(set)(way).ppn := refillStage.ppn
                        normal(set)(way).pma := refillStage.pma
                        normal(set)(way).permissions := refillStage.permissions
                        normal(set)(way).valid := true.B
                        normal(set)(way).inScope := refillStage.global || refillStage.asid === activeAsid
                    }
                }
                for (set <- 0 until p.sets) {
                    when(refillNormalPlruWrite(set)) { normalPlru(set) := refillNormalPlruValue }
                }
            }
        }
    }

    when(io.refill.valid && io.refill.bits.superpage) {
        assert(io.refill.bits.ppn(9, 0) === 0.U, "TLB: a 4 MiB Sv32 leaf requires PPN[0] = 0")
    }

    for (set <- 0 until p.sets; way <- 0 until p.ways) {
        assert(!normal(set)(way).inScope || normal(set)(way).valid, "TLB: scoped 4 KiB entry must be valid")
    }
    for (entry <- 0 until p.superEntries) {
        assert(!superpage(entry).inScope || superpage(entry).valid, "TLB: scoped 4 MiB entry must be valid")
    }
}

class InstructionTLB(p: TLBParams = TLBParams(), paddrLowBits: Int = 2)
    extends TLB(p.copy(queryPorts = 1), paddrLowBits)

class DataTLB(p: TLBParams = TLBParams(), queryPorts: Int = 2)
    extends TLB(p.copy(queryPorts = queryPorts))
