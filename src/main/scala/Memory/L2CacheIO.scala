import chisel3._
import chisel3.util._
import ZirconConfig.{ICacheParams, L2CacheParams}

class L2PTWRequest extends Bundle {
    val paddr = UInt(34.W)
}

/** Sv32 PTE fields consumed by hardware; the two RSW bits do not cross this boundary. */
class Sv32Pte extends Bundle {
    val ppn = UInt(22.W)
    val dirty = Bool()
    val accessed = Bool()
    val global = Bool()
    val user = Bool()
    val execute = Bool()
    val write = Bool()
    val read = Bool()
    val valid = Bool()
}

class L2PTWResponse extends Bundle {
    val pte = new Sv32Pte
    val error = Bool()
}

class L2PTWIO extends Bundle {
    val req = Decoupled(new L2PTWRequest)
    val rsp = Flipped(Decoupled(new L2PTWResponse))
}

/** One complete lower-memory transaction. Cached traffic uses one complete cache line.
  * Uncached writes use the low 64 data bits and eight byte strobes as AXI-aligned
  * lanes; uncached reads return the requested value in the low response bits.
  */
class L2MemoryRequest(p: L2CacheParams) extends Bundle {
    val paddr = UInt(34.W)
    val write = Bool()
    val uncache = Bool()
    val size = UInt(2.W)
    val data = UInt(p.lineBits.W)
    val mask = UInt(8.W)
}

class L2MemoryResponse(p: L2CacheParams) extends Bundle {
    val data = UInt(p.lineBits.W)
    val error = Bool()
}

class L2MemoryIO(p: L2CacheParams) extends Bundle {
    val req = Decoupled(new L2MemoryRequest(p))
    val rsp = Flipped(Decoupled(new L2MemoryResponse(p)))
}

class L2CacheIO(c: ICacheParams, p: L2CacheParams, observe: Boolean = false) extends Bundle {
    val icache = Flipped(new ICacheL2IO(c))
    val dcache = Flipped(new DCacheL2IO)
    val iptw = Flipped(new L2PTWIO)
    val dptw = Flipped(new L2PTWIO)
    val memory = new L2MemoryIO(p)
    val idle = Output(Bool())
    val performance = if (observe) Some(Output(new L2PerformanceCounters)) else None
}

class L2PerformanceCounters extends Bundle {
    val instructionVisits = UInt(64.W)
    val instructionHits = UInt(64.W)
    val instructionMisses = UInt(64.W)
    val dataVisits = UInt(64.W)
    val dataHits = UInt(64.W)
    val dataMisses = UInt(64.W)
    val instructionVictimInsertions = UInt(64.W)
    val dataVictimInsertions = UInt(64.W)
    val lowerMemoryReads = UInt(64.W)
    val lowerMemoryWrites = UInt(64.W)
    val engineBusyCycles = UInt(64.W)
}
