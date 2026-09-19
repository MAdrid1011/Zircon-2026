import chisel3._
import chisel3.util._
import ZirconConfig.{FrontendParams, ICacheParams}

class ICacheTranslation extends Bundle {
    val token = UInt(32.W)
    val paddr = UInt(34.W)
    val uncache = Bool()
    val fault = Bool()
}

/** IF1 translation query stays valid until its matching response is consumed or flushed. */
class IMMUIO(p: FrontendParams) extends Bundle {
    val request = Valid(new FrontendFetchRequest(p))
    val response = Flipped(Decoupled(new ICacheTranslation))
}

/** Cached reads return one aligned line; uncached reads return one word in the low 32 bits. */
class ICacheLineRequest(c: ICacheParams) extends Bundle {
    val token = UInt(32.W)
    val paddr = UInt(34.W)
    val uncache = Bool()
    val victimValid = Bool()
    val victimLine = UInt((34 - c.offsetBits).W)
    val victimData = UInt(c.lineBits.W)
}

class ICacheLineResponse(c: ICacheParams) extends Bundle {
    val token = UInt(32.W)
    val data = UInt(c.lineBits.W)
    val error = Bool()
}

class ICacheL2IO(c: ICacheParams) extends Bundle {
    val request = Decoupled(new ICacheLineRequest(c))
    val response = Flipped(Decoupled(new ICacheLineResponse(c)))
}

class ICacheIO(p: FrontendParams, c: ICacheParams) extends Bundle {
    val pp = Flipped(new FrontendFetchIO(p))
    val flush = Input(Bool())
    val mmu = new IMMUIO(p)
    val tlb = if (c.tlbEnabled) Some(new TLBManagementIO) else None
    val l2 = new ICacheL2IO(c)
    val miss = Output(Bool())
    val maintenance = Flipped(new CacheMaintenanceIO)
    val dbg = if (p.observe) Some(Output(new ICacheDBG)) else None
}

class ICacheDBG extends Bundle {
    val visit = UInt(64.W)
    val hit = UInt(64.W)
    val missCycle = UInt(64.W)
}
