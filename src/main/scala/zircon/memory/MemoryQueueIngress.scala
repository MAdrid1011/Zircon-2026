package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.{FaultCandidate, ROBTagOrder}

/** Moves classified M0/M1 work into the LQ/SQ without making it executable.
  *
  * A request first occupies the intake batch, then reserves its LQ/SQ owner,
  * and only in a later cycle publishes address/data updates. This separation
  * prevents an address update from targeting an entry allocated on the same
  * edge, and lets the queue enforce older-store forwarding rules. Faulting
  * requests never allocate local memory state; their exact tag/cause/tval is
  * emitted to the existing FirstFaultTracker boundary instead.
  */
class MemoryQueueIngress(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val batchWidth = config.decodeWidth
  require(batchWidth == 2, "the frozen M3 ingress accepts two classified requests")

  val io = IO(new Bundle {
    val input = Flipped(Vec(batchWidth, Decoupled(new MemoryLSURequest(config))))
    val fault = Output(Vec(batchWidth, new FaultCandidate(config)))
    val faultReady = Input(Vec(batchWidth, Bool()))

    val loadForward = Output(Valid(new LoadStoreForward(config)))
    val loadForwardReady = Input(Bool())
    val loadComplete = Flipped(Decoupled(new LoadCompletion(config)))
    val loadResult = Decoupled(new MemoryLoadResult(config))
    val loadFault = Decoupled(new LoadAccessFault(config))
    val loadContextRead = Input(Valid(UInt(config.robTagWidth.W)))
    val loadContext = Output(Valid(new LoadQueueContext(config)))

    val commitAuthorize = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val storeEffect = Decoupled(new StoreEffect(config))
    val storeEffectComplete = Input(Valid(new StoreEffectComplete(config)))
    val storeCommitInFlight = Output(Bool())
    val atomicEffect = Decoupled(new AtomicMemoryEffect(config))
    val atomicComplete = Flipped(Decoupled(new AtomicMemoryResult(config)))
    val atomicResult = Decoupled(new AtomicMemoryResult(config))
    val atomicInFlight = Output(Bool())
    val atomicAcquireBarrier = Output(Valid(UInt(config.robTagWidth.W)))
    val deviceLoadEffect = Decoupled(new OrderedLoadEffect(config))
    val deviceLoadInFlight = Output(Bool())
    val burstableDeviceGroup = Decoupled(new OrderedIOGroup(config = config))
    val burstableDeviceGroupAccepted = Input(Valid(new OrderedIOGroup(config = config)))
    val retire = Input(Vec(config.commitWidth,
      Valid(UInt(config.robTagWidth.W))))
    val retireMetadata = Output(Vec(config.commitWidth,
      Valid(new MemoryRetireMetadata(config))))

    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())

    val intakeCount = Output(UInt(2.W))
    val updateCount = Output(UInt(2.W))
    val loadCount = Output(UInt(log2Ceil(config.loadQueueEntries + 1).W))
    val storeCount = Output(UInt(log2Ceil(config.storeQueueEntries + 1).W))
  })

  val queues = Module(new LoadStoreQueues(config))
  queues.io.robHeadTag := io.robHeadTag
  queues.io.squash := io.squash
  queues.io.flush := io.flush
  queues.io.loadComplete.valid := io.loadComplete.valid
  queues.io.loadComplete.bits := io.loadComplete.bits
  io.loadComplete.ready := queues.io.loadComplete.ready
  io.loadResult.valid := queues.io.loadResult.valid
  io.loadResult.bits := queues.io.loadResult.bits
  queues.io.loadResult.ready := io.loadResult.ready
  io.loadFault.valid := queues.io.loadFault.valid
  io.loadFault.bits := queues.io.loadFault.bits
  queues.io.loadFault.ready := io.loadFault.ready
  queues.io.loadContextRead := io.loadContextRead
  io.loadForward := queues.io.loadForward
  queues.io.loadForwardReady := io.loadForwardReady
  io.loadContext := queues.io.loadContext
  for (lane <- 0 until config.commitWidth) {
    queues.io.retire(lane) := io.retire(lane)
    io.retireMetadata(lane) := queues.io.retireMetadata(lane)
  }
  queues.io.commitAuthorize.valid := io.commitAuthorize.valid
  queues.io.commitAuthorize.bits := io.commitAuthorize.bits
  io.commitAuthorize.ready := queues.io.commitAuthorize.ready
  io.storeEffect.valid := queues.io.storeEffect.valid
  io.storeEffect.bits := queues.io.storeEffect.bits
  queues.io.storeEffect.ready := io.storeEffect.ready
  queues.io.storeEffectComplete := io.storeEffectComplete
  io.storeCommitInFlight := queues.io.storeCommitInFlight
  io.atomicEffect <> queues.io.atomicEffect
  queues.io.atomicComplete <> io.atomicComplete
  io.atomicResult <> queues.io.atomicResult
  io.atomicInFlight := queues.io.atomicInFlight
  io.atomicAcquireBarrier := queues.io.atomicAcquireBarrier
  io.deviceLoadEffect <> queues.io.deviceLoadEffect
  io.deviceLoadInFlight := queues.io.deviceLoadInFlight
  io.burstableDeviceGroup <> queues.io.burstableDeviceGroup
  queues.io.burstableDeviceGroupAccepted := io.burstableDeviceGroupAccepted
  io.loadCount := queues.io.loadCount
  io.storeCount := queues.io.storeCount

  val intakeValid = RegInit(VecInit.fill(batchWidth)(false.B))
  val intakeRequest = Reg(Vec(batchWidth, new MemoryLSURequest(config)))
  val updateValid = RegInit(VecInit.fill(batchWidth)(false.B))
  val updateRequest = Reg(Vec(batchWidth, new MemoryLSURequest(config)))
  val loadAddressPending = RegInit(VecInit.fill(batchWidth)(false.B))
  val storeAddressPending = RegInit(VecInit.fill(batchWidth)(false.B))
  val storeDataPending = RegInit(VecInit.fill(batchWidth)(false.B))

  private def selectOldest(candidates: Seq[Bool]): (Bool, UInt) = {
    var selectedValid: Bool = false.B
    var selectedIndex: UInt = 0.U(1.W)
    var selectedAge: UInt = 0.U((config.robIndexWidth + 1).W)
    for (lane <- 0 until batchWidth) {
      val age = ROBTagOrder.ageFromHead(
        updateRequest(lane).address.robTag, io.robHeadTag, config)
      val take = candidates(lane) && (!selectedValid || age < selectedAge)
      selectedIndex = Mux(take, lane.U, selectedIndex)
      selectedAge = Mux(take, age, selectedAge)
      selectedValid = selectedValid || candidates(lane)
    }
    (selectedValid, selectedIndex)
  }

  val recoveryBlocked = io.flush || io.squash.valid
  val incomingNormal = VecInit(io.input.map(port =>
    port.valid && !port.bits.address.faultValid))
  val normalCount = PopCount(incomingNormal)
  val intakeEmpty = !intakeValid.asUInt.orR
  val normalIntakeOpen = !recoveryBlocked &&
    (normalCount === 0.U || intakeEmpty)

  for (lane <- 0 until batchWidth) {
    val faulting = io.input(lane).valid && io.input(lane).bits.address.faultValid
    io.fault(lane).valid := faulting && normalIntakeOpen
    io.fault(lane).record.robTag := io.input(lane).bits.address.robTag
    io.fault(lane).record.cause := io.input(lane).bits.address.faultCause
    io.fault(lane).record.trapValue := io.input(lane).bits.address.faultTval
    io.input(lane).ready := normalIntakeOpen && Mux(faulting,
      io.faultReady(lane), true.B)
  }

  val updateEmpty = !updateValid.asUInt.orR
  val allocationActive = intakeValid(0) && updateEmpty && !recoveryBlocked
  for (lane <- 0 until batchWidth) {
    queues.io.allocate(lane).valid := allocationActive && intakeValid(lane)
    queues.io.allocate(lane).bits.robTag := intakeRequest(lane).address.robTag
    queues.io.allocate(lane).bits.allocateLoad := intakeRequest(lane).address.isLoad
    queues.io.allocate(lane).bits.allocateStore := intakeRequest(lane).address.isStore
    queues.io.allocate(lane).bits.accessSize := intakeRequest(lane).address.accessSize
    queues.io.allocate(lane).bits.unsignedLoad := intakeRequest(lane).address.unsignedLoad
    queues.io.allocate(lane).bits.destinationPhysical :=
      intakeRequest(lane).request.uop.destinationPhysical
    queues.io.allocate(lane).bits.writesInteger :=
      intakeRequest(lane).request.uop.writesInteger
    queues.io.allocate(lane).bits.m1Owner := intakeRequest(lane).m1Owner
    queues.io.allocate(lane).bits.isAtomic := intakeRequest(lane).address.isAtomic
    queues.io.allocate(lane).bits.atomicOperation := intakeRequest(lane).request.uop.operation
    queues.io.allocate(lane).bits.pmaKind := intakeRequest(lane).address.pmaKind
    queues.io.allocate(lane).bits.aq := intakeRequest(lane).address.aq
    queues.io.allocate(lane).bits.rl := intakeRequest(lane).address.rl
  }
  val allocationFire = queues.io.allocate(0).fire
  when(intakeValid(1)) {
    assert(queues.io.allocate(1).fire === allocationFire,
      "two-wide LSQ allocation must accept the whole ingress batch")
  }

  val (loadSelectedValid, loadSelectedIndex) = selectOldest((0 until batchWidth).map(
    lane => updateValid(lane) && loadAddressPending(lane)))
  queues.io.loadAddress.valid := loadSelectedValid && !recoveryBlocked
  queues.io.loadAddress.bits.robTag := updateRequest(loadSelectedIndex).address.robTag
  queues.io.loadAddress.bits.address := updateRequest(loadSelectedIndex).address.address
  queues.io.loadAddress.bits.readMask := updateRequest(loadSelectedIndex).address.readMask

  val (storeAddressSelectedValid, storeAddressSelectedIndex) = selectOldest(
    (0 until batchWidth).map(lane => updateValid(lane) && storeAddressPending(lane)))
  queues.io.storeAddress.valid := storeAddressSelectedValid && !recoveryBlocked
  queues.io.storeAddress.bits.robTag := updateRequest(storeAddressSelectedIndex).address.robTag
  queues.io.storeAddress.bits.address := updateRequest(storeAddressSelectedIndex).address.address
  queues.io.storeAddress.bits.writeMask := updateRequest(storeAddressSelectedIndex).address.writeMask

  val (storeDataSelectedValid, storeDataSelectedIndex) = selectOldest(
    (0 until batchWidth).map(lane => updateValid(lane) && storeDataPending(lane)))
  queues.io.storeData.valid := storeDataSelectedValid && !recoveryBlocked
  queues.io.storeData.bits.robTag := updateRequest(storeDataSelectedIndex).address.robTag
  queues.io.storeData.bits.writeData := updateRequest(storeDataSelectedIndex).address.writeData

  val loadAddressPendingAfter = Wire(Vec(batchWidth, Bool()))
  val storeAddressPendingAfter = Wire(Vec(batchWidth, Bool()))
  val storeDataPendingAfter = Wire(Vec(batchWidth, Bool()))
  for (lane <- 0 until batchWidth) {
    loadAddressPendingAfter(lane) := loadAddressPending(lane) &&
      !(queues.io.loadAddress.fire && loadSelectedIndex === lane.U)
    storeAddressPendingAfter(lane) := storeAddressPending(lane) &&
      !(queues.io.storeAddress.fire && storeAddressSelectedIndex === lane.U)
    storeDataPendingAfter(lane) := storeDataPending(lane) &&
      !(queues.io.storeData.fire && storeDataSelectedIndex === lane.U)
  }
  val updateRemains = (0 until batchWidth).map(lane => updateValid(lane) &&
    (loadAddressPendingAfter(lane) || storeAddressPendingAfter(lane) ||
      storeDataPendingAfter(lane))).reduce(_ || _)

  val intakeSurvivesSquash = VecInit((0 until batchWidth).map(lane =>
    intakeValid(lane) && !ROBTagOrder.isYounger(
      intakeRequest(lane).address.robTag, io.squash.bits, io.robHeadTag, config)))
  val updateSurvivesSquash = VecInit((0 until batchWidth).map(lane =>
    updateValid(lane) && !ROBTagOrder.isYounger(
      updateRequest(lane).address.robTag, io.squash.bits, io.robHeadTag, config)))

  when(io.flush) {
    intakeValid.foreach(_ := false.B)
    updateValid.foreach(_ := false.B)
    loadAddressPending.foreach(_ := false.B)
    storeAddressPending.foreach(_ := false.B)
    storeDataPending.foreach(_ := false.B)
  }.elsewhen(io.squash.valid) {
    val survivingIntakeCount = PopCount(intakeSurvivesSquash)
    intakeValid(0) := survivingIntakeCount =/= 0.U
    intakeValid(1) := survivingIntakeCount === 2.U
    when(survivingIntakeCount =/= 0.U) {
      intakeRequest(0) := Mux(intakeSurvivesSquash(0),
        intakeRequest(0), intakeRequest(1))
    }
    for (lane <- 0 until batchWidth) {
      updateValid(lane) := updateSurvivesSquash(lane)
      when(!updateSurvivesSquash(lane)) {
        loadAddressPending(lane) := false.B
        storeAddressPending(lane) := false.B
        storeDataPending(lane) := false.B
      }
    }
  }.otherwise {
    when(io.input(0).fire || io.input(1).fire) {
      when(normalCount =/= 0.U) {
        val firstNormal = Mux(incomingNormal(0), io.input(0).bits, io.input(1).bits)
        intakeValid(0) := true.B
        intakeRequest(0) := firstNormal
        intakeValid(1) := normalCount === 2.U
        when(normalCount === 2.U) {
          intakeRequest(1) := io.input(1).bits
        }
      }
    }
    when(allocationFire) {
      for (lane <- 0 until batchWidth) {
        intakeValid(lane) := false.B
        updateValid(lane) := intakeValid(lane)
        updateRequest(lane) := intakeRequest(lane)
        loadAddressPending(lane) := intakeValid(lane) && intakeRequest(lane).address.isLoad
        storeAddressPending(lane) := intakeValid(lane) && intakeRequest(lane).address.isStore
        storeDataPending(lane) := intakeValid(lane) && intakeRequest(lane).address.isStore
      }
    }.otherwise {
      for (lane <- 0 until batchWidth) {
        when(updateValid(lane)) {
          loadAddressPending(lane) := loadAddressPendingAfter(lane)
          storeAddressPending(lane) := storeAddressPendingAfter(lane)
          storeDataPending(lane) := storeDataPendingAfter(lane)
        }
      }
      when(updateValid.asUInt.orR && !updateRemains) {
        updateValid.foreach(_ := false.B)
      }
    }
  }

  when(io.input(0).fire && io.input(1).fire &&
    !io.input(0).bits.address.faultValid && !io.input(1).bits.address.faultValid) {
    assert(io.input(0).bits.address.robTag =/= io.input(1).bits.address.robTag,
      "one ingress batch cannot contain duplicate ROB tags")
  }
  for (lane <- 0 until batchWidth) {
    when(io.input(lane).fire && !io.input(lane).bits.address.faultValid) {
      assert(io.input(lane).bits.address.legalMemoryOperation,
        "only a legal classified memory request may allocate an LSQ owner")
    }
  }
  when(io.squash.valid) {
    assert(!queues.io.allocate.exists(_.fire) && !queues.io.loadAddress.fire &&
      !queues.io.storeAddress.fire && !queues.io.storeData.fire,
      "memory ingress transferred work during selective squash")
  }
  assert(!intakeValid(1) || intakeValid(0),
    "ingress intake batch must remain a compact prefix")

  io.intakeCount := PopCount(intakeValid)
  io.updateCount := PopCount(updateValid)
}
