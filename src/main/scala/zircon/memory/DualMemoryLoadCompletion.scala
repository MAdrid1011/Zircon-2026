package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{CompletionResult, FaultCandidate, FirstFaultRecord, ROBTagOrder}

/** Sends each retained load result to its frozen M0 or M1 completion buffer. */
class DualMemoryLoadCompletion(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val loadResult = Flipped(Decoupled(new MemoryLoadResult(config)))
    val fault = Flipped(Vec(2, Decoupled(new FirstFaultRecord(config))))
    val loadFault = Flipped(Decoupled(new LoadAccessFault(config)))
    val storeResult = Flipped(Decoupled(new StoreWriteResult(config)))
    val atomicResult = Flipped(Decoupled(new AtomicMemoryResult(config)))
    val faultAccepted = Output(Vec(2, new FaultCandidate(config)))
    val m0Completion = Decoupled(new CompletionResult(config))
    val m1Completion = Decoupled(new CompletionResult(config))
    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
    val m0Count = Output(UInt(2.W))
    val m1Count = Output(UInt(2.W))
  })

  val m0Buffer = Module(new MemoryLoadCompletion(config))
  val m1Buffer = Module(new MemoryLoadCompletion(config))
  for (buffer <- Seq(m0Buffer, m1Buffer)) {
    buffer.io.robHeadTag := io.robHeadTag
    buffer.io.squash := io.squash
    buffer.io.flush := io.flush
  }

  val m0RegularLoad = io.loadResult.valid && !io.loadResult.bits.m1Owner
  val atomicSuccess = io.atomicResult.valid && !io.atomicResult.bits.accessFault
  val m0RegularLoadAge = ROBTagOrder.ageFromHead(io.loadResult.bits.robTag,
    io.robHeadTag, config)
  val atomicResultAge = ROBTagOrder.ageFromHead(io.atomicResult.bits.robTag,
    io.robHeadTag, config)
  val selectAtomicLoad = atomicSuccess &&
    (!m0RegularLoad || atomicResultAge < m0RegularLoadAge)
  val selectRegularM0Load = m0RegularLoad && !selectAtomicLoad
  val atomicAsLoadResult = Wire(new MemoryLoadResult(config))
  atomicAsLoadResult.robTag := io.atomicResult.bits.robTag
  atomicAsLoadResult.destinationPhysical := io.atomicResult.bits.destinationPhysical
  atomicAsLoadResult.writesInteger := io.atomicResult.bits.writesInteger
  atomicAsLoadResult.m1Owner := false.B
  atomicAsLoadResult.accessSize := 2.U
  atomicAsLoadResult.unsignedLoad := false.B
  atomicAsLoadResult.data := io.atomicResult.bits.data
  m0Buffer.io.loadResult.valid := selectRegularM0Load || selectAtomicLoad
  m0Buffer.io.loadResult.bits := Mux(selectAtomicLoad, atomicAsLoadResult,
    io.loadResult.bits)
  m1Buffer.io.loadResult.valid := io.loadResult.valid &&
    io.loadResult.bits.m1Owner
  m1Buffer.io.loadResult.bits := io.loadResult.bits
  io.loadResult.ready := Mux(io.loadResult.valid && io.loadResult.bits.m1Owner,
    m1Buffer.io.loadResult.ready,
    selectRegularM0Load && m0Buffer.io.loadResult.ready)

  m0Buffer.io.effectCompletion.valid := io.storeResult.valid &&
    !io.storeResult.bits.accessFault
  m0Buffer.io.effectCompletion.bits.robTag := io.storeResult.bits.robTag
  m0Buffer.io.effectCompletion.bits.writesInteger := false.B
  m0Buffer.io.effectCompletion.bits.destinationPhysical := 0.U
  m0Buffer.io.effectCompletion.bits.data := 0.U
  m1Buffer.io.effectCompletion.valid := false.B
  m1Buffer.io.effectCompletion.bits := 0.U.asTypeOf(m1Buffer.io.effectCompletion.bits)

  val m0LoadFault = io.loadFault.valid && !io.loadFault.bits.m1Owner
  val m1LoadFault = io.loadFault.valid && io.loadFault.bits.m1Owner
  val m0LoadFaultRecord = Wire(new FirstFaultRecord(config))
  m0LoadFaultRecord.robTag := io.loadFault.bits.robTag
  m0LoadFaultRecord.cause := 5.U // load access fault
  m0LoadFaultRecord.trapValue := io.loadFault.bits.trapValue
  val m1LoadFaultRecord = Wire(new FirstFaultRecord(config))
  m1LoadFaultRecord.robTag := io.loadFault.bits.robTag
  m1LoadFaultRecord.cause := 5.U // load access fault
  m1LoadFaultRecord.trapValue := io.loadFault.bits.trapValue
  val m0StoreFault = io.storeResult.valid && io.storeResult.bits.accessFault
  val m0StoreFaultRecord = Wire(new FirstFaultRecord(config))
  m0StoreFaultRecord.robTag := io.storeResult.bits.robTag
  m0StoreFaultRecord.cause := 7.U // store/AMO access fault
  m0StoreFaultRecord.trapValue := io.storeResult.bits.address
  val m0AtomicFault = io.atomicResult.valid && io.atomicResult.bits.accessFault
  val m0AtomicFaultRecord = Wire(new FirstFaultRecord(config))
  m0AtomicFaultRecord.robTag := io.atomicResult.bits.robTag
  m0AtomicFaultRecord.cause := 7.U // store/AMO access fault
  m0AtomicFaultRecord.trapValue := io.atomicResult.bits.faultAddress

  val m0IngressAge = ROBTagOrder.ageFromHead(io.fault(0).bits.robTag,
    io.robHeadTag, config)
  val m0LoadAge = ROBTagOrder.ageFromHead(io.loadFault.bits.robTag,
    io.robHeadTag, config)
  val m0StoreAge = ROBTagOrder.ageFromHead(io.storeResult.bits.robTag,
    io.robHeadTag, config)
  val m0AtomicAge = ROBTagOrder.ageFromHead(io.atomicResult.bits.robTag,
    io.robHeadTag, config)
  val m0SelectIngress = io.fault(0).valid &&
    (!m0LoadFault || m0IngressAge < m0LoadAge) &&
    (!m0StoreFault || m0IngressAge < m0StoreAge) &&
    (!m0AtomicFault || m0IngressAge < m0AtomicAge)
  val m0SelectLoad = m0LoadFault && !m0SelectIngress &&
    (!m0StoreFault || m0LoadAge < m0StoreAge) &&
    (!m0AtomicFault || m0LoadAge < m0AtomicAge)
  val m0SelectStore = m0StoreFault && !m0SelectIngress && !m0SelectLoad &&
    (!m0AtomicFault || m0StoreAge < m0AtomicAge)
  val m0SelectAtomic = m0AtomicFault && !m0SelectIngress && !m0SelectLoad &&
    !m0SelectStore
  m0Buffer.io.fault.valid := io.fault(0).valid || m0LoadFault || m0StoreFault ||
    m0AtomicFault
  m0Buffer.io.fault.bits := Mux(m0SelectIngress, io.fault(0).bits,
    Mux(m0SelectLoad, m0LoadFaultRecord,
      Mux(m0SelectStore, m0StoreFaultRecord, m0AtomicFaultRecord)))
  io.fault(0).ready := m0SelectIngress && m0Buffer.io.fault.ready

  val m1IngressAge = ROBTagOrder.ageFromHead(io.fault(1).bits.robTag,
    io.robHeadTag, config)
  val m1LoadAge = ROBTagOrder.ageFromHead(io.loadFault.bits.robTag,
    io.robHeadTag, config)
  val m1SelectIngress = io.fault(1).valid &&
    (!m1LoadFault || m1IngressAge <= m1LoadAge)
  m1Buffer.io.fault.valid := io.fault(1).valid || m1LoadFault
  m1Buffer.io.fault.bits := Mux(m1SelectIngress, io.fault(1).bits,
    m1LoadFaultRecord)
  io.fault(1).ready := m1SelectIngress && m1Buffer.io.fault.ready
  io.loadFault.ready := Mux(io.loadFault.bits.m1Owner,
    m1LoadFault && !m1SelectIngress && m1Buffer.io.fault.ready,
    m0SelectLoad && m0Buffer.io.fault.ready)
  io.storeResult.ready := Mux(io.storeResult.bits.accessFault,
    m0SelectStore && m0Buffer.io.fault.ready,
    m0Buffer.io.effectCompletion.ready)
  io.atomicResult.ready := Mux(io.atomicResult.bits.accessFault,
    m0SelectAtomic && m0Buffer.io.fault.ready,
    selectAtomicLoad && m0Buffer.io.loadResult.ready)

  for ((buffer, lane) <- Seq((m0Buffer, 0), (m1Buffer, 1))) {
    io.faultAccepted(lane).valid := buffer.io.fault.fire
    io.faultAccepted(lane).record := buffer.io.fault.bits
  }

  io.m0Completion <> m0Buffer.io.completion
  io.m1Completion <> m1Buffer.io.completion
  io.m0Count := m0Buffer.io.count
  io.m1Count := m1Buffer.io.count

  when(io.squash.valid || io.flush) {
    assert(!io.loadResult.ready,
      "dual load completion accepted a response during recovery")
  }
}
