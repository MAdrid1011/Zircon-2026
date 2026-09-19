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
        for (way <- 0 until p.ways) {
            normalLookupHit(port)(way) := request.valid && normal(index)(way).inScope &&
                normal(index)(way).tag === normalTag(vaddr)
        }
        for (entry <- 0 until p.superEntries) {
            superLookupHit(port)(entry) := request.valid && superpage(entry).inScope &&
                superpage(entry).vpn1 === vaddr(31, 22)
        }

        val normalHit = normalLookupHit(port).asUInt.orR
        val superHit = superLookupHit(port).asUInt.orR
        val normalPayload = Mux1H(normalLookupHit(port), normal(index))
        val superPayload = Mux1H(superLookupHit(port), superpage)
        val response = io.response(port)
        response.hit := normalHit || superHit
        response.superpage := superHit
        val physicalAddress = Mux(
            superHit,
            Cat(superPayload.ppn1, vaddr(21, 0)),
            Cat(normalPayload.ppn, vaddr(11, 0))
        )
        response.paddr := physicalAddress(p.paddrBits - 1, paddrLowBits)
        response.pma := Mux(superHit, superPayload.pma, normalPayload.pma)
        response.permissions := Mux(superHit, superPayload.permissions, normalPayload.permissions)
        when(!response.hit) {
            response.paddr := 0.U
            response.pma := 0.U
            response.permissions := 0.U.asTypeOf(new TLBPermissions)
            response.superpage := false.B
        }

        when(request.valid) {
            assert(PopCount(normalLookupHit(port)) <= 1.U, "TLB: multiple 4 KiB entries matched")
            assert(PopCount(superLookupHit(port)) <= 1.U, "TLB: multiple 4 MiB entries matched")
            assert(!(normalHit && superHit), "TLB: both page sizes matched one request")
        }
    }

    val refill = io.refill.bits
    val refillSet = refill.vpn(p.setBits - 1, 0)
    val refillTag = refill.vpn(19, p.setBits)
    val activeAsid = Mux(io.scopeUpdate.valid, io.scopeUpdate.bits.asid, currentAsid)
    val normalRefillMatches = VecInit((0 until p.ways).map(way =>
        normal(refillSet)(way).valid && normal(refillSet)(way).tag === refillTag &&
            sameAddressSpace(normal(refillSet)(way).global, normal(refillSet)(way).asid, refill.global, refill.asid)
    ))
    val normalInvalid = VecInit((0 until p.ways).map(way => !normal(refillSet)(way).valid))
    val normalRefillWay = Mux(
        normalRefillMatches.asUInt.orR,
        PriorityEncoder(normalRefillMatches),
        Mux(normalInvalid.asUInt.orR, PriorityEncoder(normalInvalid), plruVictim(normalPlru(refillSet)))
    )
    val superRefillMatches = VecInit((0 until p.superEntries).map(entry =>
        superpage(entry).valid && superpage(entry).vpn1 === refill.vpn(19, 10) &&
            sameAddressSpace(superpage(entry).global, superpage(entry).asid, refill.global, refill.asid)
    ))
    val superInvalid = VecInit((0 until p.superEntries).map(entry => !superpage(entry).valid))
    val superRefillEntry = Mux(
        superRefillMatches.asUInt.orR,
        PriorityEncoder(superRefillMatches),
        Mux(superInvalid.asUInt.orR, PriorityEncoder(superInvalid), superReplace)
    )

    when(io.scopeUpdate.valid) {
        currentAsid := io.scopeUpdate.bits.asid
    }

    when(reset.asBool || io.flush) {
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
            when(normalLookupHit(port).asUInt.orR) {
                normalPlru(normalLookupSet(port)) :=
                    plruAfter(lookupPlru, PriorityEncoder(normalLookupHit(port)))
            }
        }

        when(io.refill.valid) {
            when(refill.superpage) {
                assert(refill.ppn(9, 0) === 0.U, "TLB: a 4 MiB Sv32 leaf requires PPN[0] = 0")
                // A new superpage supersedes every overlapping small-page translation in the same address space.
                for (set <- 0 until p.sets; way <- 0 until p.ways) {
                    when(normal(set)(way).valid && normal(set)(way).tag(p.normalTagBits - 1, 10 - p.setBits) ===
                        refill.vpn(19, 10) &&
                        sameAddressSpace(normal(set)(way).global, normal(set)(way).asid, refill.global, refill.asid)) {
                        normal(set)(way).valid := false.B
                        normal(set)(way).inScope := false.B
                    }
                }
                for (entry <- 0 until p.superEntries) {
                    when(superRefillMatches(entry) && superRefillEntry =/= entry.U) {
                        superpage(entry).valid := false.B
                        superpage(entry).inScope := false.B
                    }
                }
                superpage(superRefillEntry).vpn1 := refill.vpn(19, 10)
                superpage(superRefillEntry).asid := refill.asid
                superpage(superRefillEntry).global := refill.global
                superpage(superRefillEntry).ppn1 := refill.ppn(p.ppnBits - 1, 10)
                superpage(superRefillEntry).pma := refill.pma
                superpage(superRefillEntry).permissions := refill.permissions
                superpage(superRefillEntry).valid := true.B
                superpage(superRefillEntry).inScope := refill.global || refill.asid === activeAsid
                superReplace := superRefillEntry + 1.U
            }.otherwise {
                // A small-page refill removes an overlapping superpage for the affected address space.
                for (entry <- 0 until p.superEntries) {
                    when(superpage(entry).valid && superpage(entry).vpn1 === refill.vpn(19, 10) &&
                        sameAddressSpace(superpage(entry).global, superpage(entry).asid, refill.global, refill.asid)) {
                        superpage(entry).valid := false.B
                        superpage(entry).inScope := false.B
                    }
                }
                for (way <- 0 until p.ways) {
                    when(normalRefillMatches(way) && normalRefillWay =/= way.U) {
                        normal(refillSet)(way).valid := false.B
                        normal(refillSet)(way).inScope := false.B
                    }
                }
                normal(refillSet)(normalRefillWay).tag := refillTag
                normal(refillSet)(normalRefillWay).asid := refill.asid
                normal(refillSet)(normalRefillWay).global := refill.global
                normal(refillSet)(normalRefillWay).ppn := refill.ppn
                normal(refillSet)(normalRefillWay).pma := refill.pma
                normal(refillSet)(normalRefillWay).permissions := refill.permissions
                normal(refillSet)(normalRefillWay).valid := true.B
                normal(refillSet)(normalRefillWay).inScope := refill.global || refill.asid === activeAsid
                normalPlru(refillSet) := plruAfter(normalPlru(refillSet), normalRefillWay)
            }
        }
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

class DataTLB(p: TLBParams = TLBParams()) extends TLB(p.copy(queryPorts = 2))
