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
  val aq = Bool()
  val rl = Bool()
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
  val aq = Bool()
  val rl = Bool()
}

class StoreEffectComplete(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
}

/** A line-fill request from L1D to the sole data-side AXI read engine. */
class DataReadRequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val mshr = UInt(log2Ceil(config.l1d.mshrs).W)
  val lineAddress = UInt(32.W)
}

/** One fully drained eight-word cache-line response for its exact MSHR owner. */
class DataReadResponse(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val mshr = UInt(log2Ceil(config.l1d.mshrs).W)
  val lineData = Vec(config.l1d.lineBytes / 4, UInt(32.W))
  val accessFault = Bool()
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
