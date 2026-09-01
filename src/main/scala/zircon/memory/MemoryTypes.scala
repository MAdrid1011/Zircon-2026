package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Static memory-uop information that moves from an LSU into the LQ and/or SQ.
  * Address and store data intentionally arrive through independent records so a
  * load never observes a known-address older store before its data is usable.
  */
class MemoryQueueAllocate(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val allocateLoad = Bool()
  val allocateStore = Bool()
  val accessSize = UInt(2.W)
  val unsignedLoad = Bool()
  val destinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val writesInteger = Bool()
  val m1Owner = Bool()
  val isAtomic = Bool()
  /** Retained for the M0 LR/SC/AMO owner. It must not be reconstructed from a
    * later decode slot because recovery may already have reused that slot. */
  val atomicOperation = UInt(7.W)
  val pmaKind = UInt(2.W)
  val aq = Bool()
  val rl = Bool()
}

class StoreAddressUpdate(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val writeMask = UInt(4.W)
}

class StoreDataUpdate(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val writeData = UInt(32.W)
}

class LoadAddressQuery(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val readMask = UInt(4.W)
}

/** Result accompanying a successful load-address query. `forwardMask` selects
  * bytes that must override a later cache word; an all-one mask needs no cache
  * request at all.
  */
class LoadStoreForward(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val readMask = UInt(4.W)
  val forwardMask = UInt(4.W)
  val forwardData = UInt(32.W)
  val requiresCache = Bool()
  /** Only an M1-owned non-atomic load may enter the executable L1D slice.
    * M0 device and atomic owners keep their LQ state pending for their later
    * ordered-MMIO/RV32A transaction engines.
    */
  val cacheable = Bool()
}

class LoadCompletion(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val cacheData = UInt(32.W)
  val accessFault = Bool()
  val faultAddress = UInt(32.W)
}

/** An AXI data-read failure that remains owned by the exact LQ entry until its
  * M0/M1 completion buffer accepts the matching no-write completion.
  */
class LoadAccessFault(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val m1Owner = Bool()
  val trapValue = UInt(32.W)
}

/** A load response after byte forwarding but before architectural completion.
  * It remains Decoupled so cache response ownership is retained whenever the
  * corresponding two-entry LSU completion buffer is full.
  */
class MemoryLoadResult(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val destinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val writesInteger = Bool()
  val m1Owner = Bool()
  val accessSize = UInt(2.W)
  val unsignedLoad = Bool()
  val data = UInt(32.W)
}

/** Static LQ data retained for LSU completion and atomic ordering decisions. */
class LoadQueueContext(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val accessSize = UInt(2.W)
  val unsignedLoad = Bool()
  val destinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val writesInteger = Bool()
  val m1Owner = Bool()
  val isAtomic = Bool()
  val atomicOperation = UInt(7.W)
  val aq = Bool()
  val rl = Bool()
}

/** Exact-head RV32A operation retained by the LQ/SQ until its AXI lifecycle
  * completes. SC has only an SQ owner; LR has only an LQ owner; an AMO owns
  * both records. All transfers are naturally aligned 32-bit words.
  */
class AtomicMemoryEffect(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val operation = UInt(7.W)
  val address = UInt(32.W)
  val writeData = UInt(32.W)
  val writeMask = UInt(4.W)
  val destinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val writesInteger = Bool()
  val aq = Bool()
  val rl = Bool()
}

/** One architectural result from the serialized RV32A AXI owner. A successful
  * SC with no live reservation is a legitimate no-write result; every other
  * nonzero `storePerformed` waits for the matching B response.
  */
class AtomicMemoryResult(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val operation = UInt(7.W)
  val destinationPhysical = UInt(log2Ceil(config.intPhysicalRegisters).W)
  val writesInteger = Bool()
  val data = UInt(32.W)
  val accessFault = Bool()
  val faultAddress = UInt(32.W)
  val readData = UInt(32.W)
  val readMask = UInt(4.W)
  val writeData = UInt(32.W)
  val writeMask = UInt(4.W)
  val storePerformed = Bool()
}

/** A commit-authorized store or atomic action. Cache/MMIO logic may only act
  * after receiving this record, and must report success before retirement.
  */
class StoreEffect(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val accessSize = UInt(2.W)
  val writeMask = UInt(4.W)
  val writeData = UInt(32.W)
  val isAtomic = Bool()
  val pmaKind = UInt(2.W)
  val aq = Bool()
  val rl = Bool()
}

class StoreEffectComplete(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val accessFault = Bool()
}

/** A legal non-atomic device load whose address is retained by the LQ and whose
  * exact ROB head now owns an ordered external read. The ordered-device engine
  * returns a normal `LoadCompletion`; no result is invented at this boundary.
  */
class OrderedLoadEffect(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val accessSize = UInt(2.W)
  val pmaKind = UInt(2.W)
}

/** One exact response for a commit-authorized cacheable store AXI write. */
class StoreWriteResult(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val accessFault = Bool()
}

/** L2 demand clients share physical read MSHRs without reserving slots by
  * cache. Both active L1I and L1D paths use this interface; `Instruction`
  * carries the L1I local token and `Data` carries an L1D local MSHR token.
  */
object L2DemandClient {
  val Instruction = 0
  val Data = 1
}

/** A line-fill request from a local cache MSHR to the L2 demand AXI owner. */
class L2DemandRequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val client = UInt(1.W)
  val clientMshr = UInt(log2Ceil(config.l2.mshrs).W)
  val lineAddress = UInt(32.W)
}

/** One fully drained line response returned to the original local cache MSHR. */
class L2DemandResponse(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val client = UInt(1.W)
  val clientMshr = UInt(log2Ceil(config.l2.mshrs).W)
  val lineData = Vec(config.l2.lineBytes / 4, UInt(32.W))
  val accessFault = Bool()
}

/** A complete D-cache line whose ownership moves between L1D, L2, a transfer
  * register, and the dirty-victim/writeback path. The address is always line
  * aligned and the dirty bit travels with the only owning copy.
  */
class CacheLineTransfer(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val lineAddress = UInt(32.W)
  val lineData = Vec(config.l2.lineBytes / 4, UInt(32.W))
  val dirty = Bool()
}

/** Request to move a D-side line out of the exclusive L2. A hit transfers the
  * line into the response buffer; a miss has no data ownership.
  */
class L2LookupRequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val lineAddress = UInt(32.W)
}

class L2LookupResponse(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val hit = Bool()
  val transfer = new CacheLineTransfer(config)
}

/** Exact architectural memory information kept until the owning ROB entry
  * retires. This is the sole LSQ source for retire tracing.
  */
class MemoryRetireMetadata(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val address = UInt(32.W)
  val readMask = UInt(4.W)
  val writeMask = UInt(4.W)
  val readData = UInt(32.W)
  val writeData = UInt(32.W)
}

private[memory] object MemoryByteLanes {
  def wordAddress(address: UInt): UInt = Cat(address(31, 2), 0.U(2.W))

  def merge(cacheData: UInt, forwardedData: UInt, forwardedMask: UInt): UInt = {
    val merged = Wire(Vec(4, UInt(8.W)))
    for (lane <- 0 until 4) {
      merged(lane) := Mux(forwardedMask(lane),
        forwardedData(8 * lane + 7, 8 * lane),
        cacheData(8 * lane + 7, 8 * lane))
    }
    merged.asUInt
  }
}
