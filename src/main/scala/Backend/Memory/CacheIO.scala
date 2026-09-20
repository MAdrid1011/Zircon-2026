import chisel3._
import chisel3.util.{log2Ceil, Valid}
import ZirconConfig.Cache._
import ZirconConfig.DCacheParams

// Physical addresses and access checks are supplied with the S1 request.
class DLoadRequest(val p: DCacheParams = DCacheParams()) extends Bundle {
    val vaddr = UInt(32.W)
    val paddr = UInt(34.W)
    val slot = UInt(p.slotWidth.W)
    val mtype = UInt(3.W)
    val uncache = Bool()
    val ioAuthorized = Bool()
    val exception = UInt(4.W)
    val translationMiss = Bool()
}

class DStoreTranslationRequest extends Bundle {
    val vaddr = UInt(32.W)
    val uncache = Bool()
    val exception = UInt(4.W)
    val atomic = Bool()
    val lr = Bool()
}

class DStoreTranslationResponse extends Bundle {
    val paddr = UInt(34.W)
    val uncache = Bool()
    val exception = UInt(4.W)
    val miss = Bool()
}

class DTLBMissRequest extends Bundle {
    val vaddr = UInt(32.W)
    val store = Bool()
}

class DLoadResponse(val p: DCacheParams = DCacheParams()) extends Bundle {
    val slot = UInt(p.slotWidth.W)
    val data = UInt(32.W)
    val exception = UInt(4.W)
    val retry = Bool()
    val uncache = Bool()
}

class DLoadWBSelect(val p: DCacheParams = DCacheParams()) extends Bundle {
    val slot = UInt(p.slotWidth.W)
    val exception = UInt(4.W)
    val retry = Bool()
    val uncache = Bool()
}

class DStoreRequest extends Bundle {
    val paddr = UInt(34.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
    val size = UInt(2.W)
    val uncache = Bool()
}

class DStoreResponse extends Bundle {
    val exception = UInt(4.W)
}

class DForwardQuery(val p: DCacheParams = DCacheParams()) extends Bundle {
    val wordAddress = UInt(32.W)
    val slot = UInt(p.slotWidth.W)
    val mask = UInt(4.W)
}

class DForwardResult(val p: DCacheParams = DCacheParams()) extends Bundle {
    val slot = UInt(p.slotWidth.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
    val blocked = Bool()
}

class DMemoryRequest extends Bundle {
    val paddr = UInt(34.W)
    val write = Bool()
    val uncache = Bool()
    val size = UInt(2.W)
    val data = UInt(l1LineBits.W)
    val mask = UInt(4.W)
    val victimValid = Bool()
    val victimLine = UInt((34 - log2Ceil(l1Line)).W)
    val victimData = UInt(l1LineBits.W)
    val victimDirty = Bool()
    val victimOnly = Bool()
}

class DMemoryResponse extends Bundle {
    val data = UInt(l1LineBits.W)
    val dirty = Bool()
    val error = Bool()
}

class DCacheL2IO extends Bundle {
    val req = chisel3.util.Decoupled(new DMemoryRequest)
    val rsp = Flipped(chisel3.util.Decoupled(new DMemoryResponse))
}

class DCacheLoadIO(p: DCacheParams) extends Bundle {
    val req = Flipped(chisel3.util.Decoupled(new DLoadRequest(p)))
    val fixedLatency = Output(Bool())
    val wbSelect = chisel3.util.Valid(new DLoadWBSelect(p))
    val rsp = chisel3.util.Valid(new DLoadResponse(p))
}

class DCacheStoreIO extends Bundle {
    val req = Flipped(chisel3.util.Decoupled(new DStoreRequest))
    val rsp = chisel3.util.Decoupled(new DStoreResponse)
}

class DCacheForwardIO(p: DCacheParams) extends Bundle {
    val query = chisel3.util.Valid(new DForwardQuery(p))
    val result = Flipped(chisel3.util.Valid(new DForwardResult(p)))
}

class DCacheStoreTranslationIO extends Bundle {
    val request = Flipped(Valid(new DStoreTranslationRequest))
    val response = Output(new DStoreTranslationResponse)
}

class DCacheIO(
    val p: DCacheParams = DCacheParams(),
    val tlbEnabled: Boolean = false,
    val observe: Boolean = false,
) extends Bundle {
    // Lane 0 is LS0 / RAM A; lane 1 is LS1 / RAM B. Committed writes use the separate store interface.
    val load = Vec(2, new DCacheLoadIO(p))
    val store = new DCacheStoreIO
    val forward = Vec(2, new DCacheForwardIO(p))
    val flush = Input(Bool())
    val tlb = if (tlbEnabled) Some(new TLBManagementIO) else None
    val tlbMiss = if (tlbEnabled) Some(Output(Vec(2, Valid(new DTLBMissRequest)))) else None
    val storeTranslation = if (tlbEnabled) Some(new DCacheStoreTranslationIO) else None
    val l2 = new DCacheL2IO
    val idle = Output(Bool())
    val maintenance = Flipped(new CacheMaintenanceIO)
    val performance = if (observe) Some(Output(new DCachePerformanceCounters)) else None
    val debug = if (observe) Some(Output(new DCacheDebugIO)) else None
}

class DCacheDebugIO extends Bundle {
    val requestBufferValid = UInt(2.W)
    val lookupValid = UInt(2.W)
    val lookupFresh = UInt(2.W)
    val executeValid = UInt(2.W)
    val responseValid = UInt(2.W)
    val forwardQueryValid = Bool()
    val forwardResultValid = Bool()
    val lookupResponseMatch = Bool()
    val lookupMove = Bool()
    val executeRelease = Bool()
    val missBusy = Bool()
    val storeState = UInt(3.W)
    val flush = Bool()
}

class DCachePerformanceCounters extends Bundle {
    val loadVisits = Vec(2, UInt(64.W))
    val loadHits = Vec(2, UInt(64.W))
    val loadMisses = Vec(2, UInt(64.W))
    val loadRetries = Vec(2, UInt(64.W))
    val loadRetryTranslation = Vec(2, UInt(64.W))
    val loadRetryForwardBlocked = Vec(2, UInt(64.W))
    val loadRetryUncachedOrder = Vec(2, UInt(64.W))
    val loadRetryStaleLookup = Vec(2, UInt(64.W))
    val loadRetryMissBusy = Vec(2, UInt(64.W))
    val loadRetryStoreConflict = Vec(2, UInt(64.W))
    val loadRetryLaneConflict = Vec(2, UInt(64.W))
    val storeVisits = UInt(64.W)
    val storeHits = UInt(64.W)
    val storeMisses = UInt(64.W)
    val missBusyCycles = UInt(64.W)
}
