package zircon.memory

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig
import zircon.backend.ROBTagOrder

/** Eight-entry load and store queues with byte-precise forwarding.
  *
  * Allocation creates only local speculative state. In particular, an SQ
  * entry cannot produce `storeEffect` until its exact ROB tag has received
  * `commitAuthorize`; the effect must then complete before retirement can
  * release the record. This keeps cache/MMIO actions outside recovery.
  */
class LoadStoreQueues(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val lqEntries = config.loadQueueEntries
  private val sqEntries = config.storeQueueEntries
  private val lqIndexWidth = log2Ceil(lqEntries)
  private val sqIndexWidth = log2Ceil(sqEntries)
  private val lqCountWidth = log2Ceil(lqEntries + 1)
  private val sqCountWidth = log2Ceil(sqEntries + 1)

  require(lqEntries == 8 && sqEntries == 8,
    "the frozen M3 contract requires eight LQ and SQ entries")

  val io = IO(new Bundle {
    val allocate = Flipped(Vec(config.decodeWidth,
      Decoupled(new MemoryQueueAllocate(config))))
    val storeAddress = Flipped(Decoupled(new StoreAddressUpdate(config)))
    val storeData = Flipped(Decoupled(new StoreDataUpdate(config)))
    val loadAddress = Flipped(Decoupled(new LoadAddressQuery(config)))
    val loadForward = Output(Valid(new LoadStoreForward(config)))
    val loadComplete = Flipped(Decoupled(new LoadCompletion(config)))
    val loadResult = Decoupled(new MemoryLoadResult(config))
    val loadContextRead = Input(Valid(UInt(config.robTagWidth.W)))
    val loadContext = Output(Valid(new LoadQueueContext(config)))

    val commitAuthorize = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val storeEffect = Decoupled(new StoreEffect(config))
    val storeEffectComplete = Input(Valid(new StoreEffectComplete(config)))

    val retire = Input(Vec(config.commitWidth,
      Valid(UInt(config.robTagWidth.W))))
    val retireMetadata = Output(Vec(config.commitWidth,
      Valid(new MemoryRetireMetadata(config))))

    val robHeadTag = Input(UInt(config.robTagWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())

    val loadCount = Output(UInt(lqCountWidth.W))
    val storeCount = Output(UInt(sqCountWidth.W))
    val loadCapacity = Output(UInt(2.W))
    val storeCapacity = Output(UInt(2.W))
  })

  val lqValid = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqTag = Reg(Vec(lqEntries, UInt(config.robTagWidth.W)))
  val lqAccessSize = Reg(Vec(lqEntries, UInt(2.W)))
  val lqUnsignedLoad = Reg(Vec(lqEntries, Bool()))
  val lqDestinationPhysical = Reg(Vec(lqEntries,
    UInt(log2Ceil(config.intPhysicalRegisters).W)))
  val lqWritesInteger = Reg(Vec(lqEntries, Bool()))
  val lqM1Owner = Reg(Vec(lqEntries, Bool()))
  val lqIsAtomic = Reg(Vec(lqEntries, Bool()))
  val lqAq = Reg(Vec(lqEntries, Bool()))
  val lqRl = Reg(Vec(lqEntries, Bool()))
  val lqAddressValid = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqAddress = Reg(Vec(lqEntries, UInt(32.W)))
  val lqReadMask = Reg(Vec(lqEntries, UInt(4.W)))
  val lqForwardMask = Reg(Vec(lqEntries, UInt(4.W)))
  val lqForwardData = Reg(Vec(lqEntries, UInt(32.W)))
  val lqCompleted = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqMetadataValid = RegInit(VecInit.fill(lqEntries)(false.B))
  val lqMetadata = Reg(Vec(lqEntries, new MemoryRetireMetadata(config)))

  val sqValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqTag = Reg(Vec(sqEntries, UInt(config.robTagWidth.W)))
  val sqAddressValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqAddress = Reg(Vec(sqEntries, UInt(32.W)))
  val sqAccessSize = Reg(Vec(sqEntries, UInt(2.W)))
  val sqWriteMask = Reg(Vec(sqEntries, UInt(4.W)))
  val sqDataValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqWriteData = Reg(Vec(sqEntries, UInt(32.W)))
  val sqIsAtomic = Reg(Vec(sqEntries, Bool()))
  val sqAq = Reg(Vec(sqEntries, Bool()))
  val sqRl = Reg(Vec(sqEntries, Bool()))
  val sqCommitAuthorized = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqEffectIssued = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqEffectComplete = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqMetadataValid = RegInit(VecInit.fill(sqEntries)(false.B))
  val sqMetadata = Reg(Vec(sqEntries, new MemoryRetireMetadata(config)))

  private def findMatch(
      valid: Vec[Bool],
      tags: Vec[UInt],
      tag: UInt,
      entries: Int,
      indexWidth: Int
  ): (Bool, UInt) = {
    val matches = VecInit((0 until entries).map(index =>
      valid(index) && tags(index) === tag))
    (matches.asUInt.orR, PriorityEncoder(matches.asUInt)(indexWidth - 1, 0))
  }

  private def capacity(free: UInt, entries: Int): UInt =
    Mux(PopCount(free) >= config.decodeWidth.U, config.decodeWidth.U,
      PopCount(free)(1, 0))

  val recoveryBlocked = io.flush || io.squash.valid
  val lqFree0 = (~lqValid.asUInt)(lqEntries - 1, 0)
  val sqFree0 = (~sqValid.asUInt)(sqEntries - 1, 0)
  io.loadCapacity := Mux(recoveryBlocked, 0.U, capacity(lqFree0, lqEntries))
  io.storeCapacity := Mux(recoveryBlocked, 0.U, capacity(sqFree0, sqEntries))

  val allocateLoad = VecInit(io.allocate.map(port =>
    port.valid && port.bits.allocateLoad))
  val allocateStore = VecInit(io.allocate.map(port =>
    port.valid && port.bits.allocateStore))
  val allocationFits = PopCount(lqFree0) >= PopCount(allocateLoad) &&
    PopCount(sqFree0) >= PopCount(allocateStore)
  io.allocate.foreach(_.ready := !recoveryBlocked && allocationFits)

  val lqAllocation0OH = PriorityEncoderOH(lqFree0)
  val lqFree1 = lqFree0 & ~Mux(io.allocate(0).fire && allocateLoad(0),
    lqAllocation0OH, 0.U(lqEntries.W))
  val lqAllocation1OH = PriorityEncoderOH(lqFree1)
  val lqAllocation = VecInit(OHToUInt(lqAllocation0OH), OHToUInt(lqAllocation1OH))

  val sqAllocation0OH = PriorityEncoderOH(sqFree0)
  val sqFree1 = sqFree0 & ~Mux(io.allocate(0).fire && allocateStore(0),
    sqAllocation0OH, 0.U(sqEntries.W))
  val sqAllocation1OH = PriorityEncoderOH(sqFree1)
  val sqAllocation = VecInit(OHToUInt(sqAllocation0OH), OHToUInt(sqAllocation1OH))

  val (storeAddressMatch, storeAddressIndex) = findMatch(
    sqValid, sqTag, io.storeAddress.bits.robTag, sqEntries, sqIndexWidth)
  io.storeAddress.ready := !recoveryBlocked && storeAddressMatch

  val (storeDataMatch, storeDataIndex) = findMatch(
    sqValid, sqTag, io.storeData.bits.robTag, sqEntries, sqIndexWidth)
  io.storeData.ready := !recoveryBlocked && storeDataMatch

  val (loadAddressMatch, loadAddressIndex) = findMatch(
    lqValid, lqTag, io.loadAddress.bits.robTag, lqEntries, lqIndexWidth)
  val queryWordAddress = MemoryByteLanes.wordAddress(io.loadAddress.bits.address)
  val olderUnknownAddress = (0 until sqEntries).map(index =>
    sqValid(index) && ROBTagOrder.isYounger(
      io.loadAddress.bits.robTag, sqTag(index), io.robHeadTag, config) &&
      !sqAddressValid(index)).reduce(_ || _)
  val olderUnknownData = (0 until sqEntries).map(index =>
    sqValid(index) && sqAddressValid(index) &&
      ROBTagOrder.isYounger(
        io.loadAddress.bits.robTag, sqTag(index), io.robHeadTag, config) &&
      MemoryByteLanes.wordAddress(sqAddress(index)) === queryWordAddress &&
      (sqWriteMask(index) & io.loadAddress.bits.readMask).orR &&
      !sqDataValid(index)).reduce(_ || _)
  val loadMayProceed = loadAddressMatch && !olderUnknownAddress && !olderUnknownData
  io.loadAddress.ready := !recoveryBlocked && loadMayProceed

  val forwardMaskBits = Wire(Vec(4, Bool()))
  val forwardBytes = Wire(Vec(4, UInt(8.W)))
  for (lane <- 0 until 4) {
    var chosenValid: Bool = false.B
    var chosenTag: UInt = 0.U(config.robTagWidth.W)
    var chosenData: UInt = 0.U(8.W)
    for (index <- 0 until sqEntries) {
      val candidate = sqValid(index) && sqAddressValid(index) && sqDataValid(index) &&
        ROBTagOrder.isYounger(
          io.loadAddress.bits.robTag, sqTag(index), io.robHeadTag, config) &&
        MemoryByteLanes.wordAddress(sqAddress(index)) === queryWordAddress &&
        sqWriteMask(index)(lane)
      val take = candidate && (!chosenValid || ROBTagOrder.isYounger(
        sqTag(index), chosenTag, io.robHeadTag, config))
      chosenTag = Mux(take, sqTag(index), chosenTag)
      chosenData = Mux(take, sqWriteData(index)(8 * lane + 7, 8 * lane), chosenData)
      chosenValid = chosenValid || candidate
    }
    forwardMaskBits(lane) := chosenValid
    forwardBytes(lane) := chosenData
  }
  val forwardMask = forwardMaskBits.asUInt
  val requestedForwardMask = forwardMask & io.loadAddress.bits.readMask
  val forwardData = forwardBytes.asUInt

  io.loadForward.valid := io.loadAddress.valid && io.loadAddress.ready
  io.loadForward.bits.robTag := io.loadAddress.bits.robTag
  io.loadForward.bits.address := io.loadAddress.bits.address
  io.loadForward.bits.readMask := io.loadAddress.bits.readMask
  io.loadForward.bits.forwardMask := requestedForwardMask
  io.loadForward.bits.forwardData := forwardData
  io.loadForward.bits.requiresCache := requestedForwardMask =/= io.loadAddress.bits.readMask

  val (loadCompleteMatch, loadCompleteIndex) = findMatch(
    lqValid, lqTag, io.loadComplete.bits.robTag, lqEntries, lqIndexWidth)
  val loadCompleteEligible = loadCompleteMatch && lqAddressValid(loadCompleteIndex) &&
    !lqCompleted(loadCompleteIndex) && !recoveryBlocked
  val completedLoadData = MemoryByteLanes.merge(
    io.loadComplete.bits.cacheData,
    lqForwardData(loadCompleteIndex),
    lqForwardMask(loadCompleteIndex))
  io.loadResult.valid := io.loadComplete.valid && loadCompleteEligible
  io.loadResult.bits.robTag := lqTag(loadCompleteIndex)
  io.loadResult.bits.destinationPhysical := lqDestinationPhysical(loadCompleteIndex)
  io.loadResult.bits.writesInteger := lqWritesInteger(loadCompleteIndex)
  io.loadResult.bits.m1Owner := lqM1Owner(loadCompleteIndex)
  io.loadResult.bits.accessSize := lqAccessSize(loadCompleteIndex)
  io.loadResult.bits.unsignedLoad := lqUnsignedLoad(loadCompleteIndex)
  io.loadResult.bits.data := completedLoadData
  io.loadComplete.ready := loadCompleteEligible && io.loadResult.ready
  val (loadContextMatch, loadContextIndex) = findMatch(
    lqValid, lqTag, io.loadContextRead.bits, lqEntries, lqIndexWidth)
  io.loadContext.valid := io.loadContextRead.valid && loadContextMatch && !recoveryBlocked
  io.loadContext.bits.robTag := lqTag(loadContextIndex)
  io.loadContext.bits.accessSize := lqAccessSize(loadContextIndex)
  io.loadContext.bits.unsignedLoad := lqUnsignedLoad(loadContextIndex)
  io.loadContext.bits.destinationPhysical := lqDestinationPhysical(loadContextIndex)
  io.loadContext.bits.writesInteger := lqWritesInteger(loadContextIndex)
  io.loadContext.bits.m1Owner := lqM1Owner(loadContextIndex)
  io.loadContext.bits.isAtomic := lqIsAtomic(loadContextIndex)
  io.loadContext.bits.aq := lqAq(loadContextIndex)
  io.loadContext.bits.rl := lqRl(loadContextIndex)

  val (commitMatch, commitIndex) = findMatch(
    sqValid, sqTag, io.commitAuthorize.bits, sqEntries, sqIndexWidth)
  val commitEligible = commitMatch && sqAddressValid(commitIndex) &&
    sqDataValid(commitIndex) && !sqCommitAuthorized(commitIndex)
  io.commitAuthorize.ready := !recoveryBlocked && commitEligible

  var selectedStoreValid: Bool = false.B
  var selectedStoreIndex: UInt = 0.U(sqIndexWidth.W)
  var selectedStoreAge: UInt = 0.U((config.robIndexWidth + 1).W)
  for (index <- 0 until sqEntries) {
    val candidate = sqValid(index) && sqCommitAuthorized(index) &&
      !sqEffectIssued(index)
    val age = ROBTagOrder.ageFromHead(sqTag(index), io.robHeadTag, config)
    val take = candidate && (!selectedStoreValid || age < selectedStoreAge)
    selectedStoreIndex = Mux(take, index.U, selectedStoreIndex)
    selectedStoreAge = Mux(take, age, selectedStoreAge)
    selectedStoreValid = selectedStoreValid || candidate
  }
  io.storeEffect.valid := selectedStoreValid && !recoveryBlocked
  io.storeEffect.bits.robTag := sqTag(selectedStoreIndex)
  io.storeEffect.bits.address := sqAddress(selectedStoreIndex)
  io.storeEffect.bits.accessSize := sqAccessSize(selectedStoreIndex)
  io.storeEffect.bits.writeMask := sqWriteMask(selectedStoreIndex)
  io.storeEffect.bits.writeData := sqWriteData(selectedStoreIndex)
  io.storeEffect.bits.isAtomic := sqIsAtomic(selectedStoreIndex)
  io.storeEffect.bits.aq := sqAq(selectedStoreIndex)
  io.storeEffect.bits.rl := sqRl(selectedStoreIndex)

  val (effectCompleteMatch, effectCompleteIndex) = findMatch(
    sqValid, sqTag, io.storeEffectComplete.bits.robTag, sqEntries, sqIndexWidth)

  val lqRetire = VecInit((0 until lqEntries).map(index =>
    lqValid(index) && io.retire.map(port => port.valid &&
      port.bits === lqTag(index)).reduce(_ || _)))
  val sqRetire = VecInit((0 until sqEntries).map(index =>
    sqValid(index) && io.retire.map(port => port.valid &&
      port.bits === sqTag(index)).reduce(_ || _)))
  for (lane <- 0 until config.commitWidth) {
    val (retireLqMatch, retireLqIndex) = findMatch(
      lqValid, lqTag, io.retire(lane).bits, lqEntries, lqIndexWidth)
    val (retireSqMatch, retireSqIndex) = findMatch(
      sqValid, sqTag, io.retire(lane).bits, sqEntries, sqIndexWidth)
    val preferLoad = retireLqMatch && lqMetadataValid(retireLqIndex)
    val atomicPair = retireLqMatch && retireSqMatch && sqIsAtomic(retireSqIndex) &&
      lqIsAtomic(retireLqIndex)
    val atomicMetadata = Wire(new MemoryRetireMetadata(config))
    atomicMetadata.robTag := lqMetadata(retireLqIndex).robTag
    atomicMetadata.address := lqMetadata(retireLqIndex).address
    atomicMetadata.readMask := lqMetadata(retireLqIndex).readMask
    atomicMetadata.writeMask := sqMetadata(retireSqIndex).writeMask
    atomicMetadata.readData := lqMetadata(retireLqIndex).readData
    atomicMetadata.writeData := sqMetadata(retireSqIndex).writeData
    io.retireMetadata(lane).valid := io.retire(lane).valid && Mux(atomicPair,
      lqMetadataValid(retireLqIndex) && sqMetadataValid(retireSqIndex),
      preferLoad || (retireSqMatch && sqMetadataValid(retireSqIndex)))
    io.retireMetadata(lane).bits := Mux(atomicPair, atomicMetadata,
      Mux(preferLoad, lqMetadata(retireLqIndex), sqMetadata(retireSqIndex)))
    when(io.retire(lane).valid && retireLqMatch) {
      assert(lqCompleted(retireLqIndex),
        "a load cannot retire before its memory result is complete")
    }
    when(io.retire(lane).valid && retireSqMatch) {
      assert(sqEffectComplete(retireSqIndex),
        "a store cannot retire before its committed effect completes")
    }
    when(io.retire(lane).valid && atomicPair &&
      lqMetadataValid(retireLqIndex) && sqMetadataValid(retireSqIndex)) {
      assert(lqMetadata(retireLqIndex).address === sqMetadata(retireSqIndex).address,
        "atomic LQ/SQ retire metadata must describe one address")
    }
  }

  val lqSquashSurvivor = VecInit((0 until lqEntries).map(index =>
    lqValid(index) && !ROBTagOrder.isYounger(
      lqTag(index), io.squash.bits, io.robHeadTag, config)))
  val sqSquashSurvivor = VecInit((0 until sqEntries).map(index =>
    sqValid(index) && !ROBTagOrder.isYounger(
      sqTag(index), io.squash.bits, io.robHeadTag, config)))

  when(io.flush) {
    for (index <- 0 until sqEntries) {
      assert(!sqValid(index) || !sqCommitAuthorized(index),
        "a flush cannot discard a commit-authorized store effect")
    }
    lqValid.foreach(_ := false.B)
    sqValid.foreach(_ := false.B)
  }.elsewhen(io.squash.valid) {
    for (index <- 0 until sqEntries) {
      when(sqValid(index) && !sqSquashSurvivor(index)) {
        assert(!sqCommitAuthorized(index),
          "selective recovery cannot discard a commit-authorized store")
      }
    }
    for (index <- 0 until lqEntries) {
      lqValid(index) := lqSquashSurvivor(index)
    }
    for (index <- 0 until sqEntries) {
      sqValid(index) := sqSquashSurvivor(index)
    }
  }.otherwise {
    for (index <- 0 until lqEntries) {
      when(lqRetire(index)) {
        lqValid(index) := false.B
      }
    }
    for (index <- 0 until sqEntries) {
      when(sqRetire(index)) {
        sqValid(index) := false.B
      }
    }

    for (lane <- 0 until config.decodeWidth) {
      when(io.allocate(lane).fire) {
        assert(io.allocate(lane).bits.allocateLoad || io.allocate(lane).bits.allocateStore,
          "memory allocation must reserve at least one queue")
        when(io.allocate(lane).bits.allocateLoad) {
          val index = lqAllocation(lane)
          lqValid(index) := true.B
          lqTag(index) := io.allocate(lane).bits.robTag
          lqAccessSize(index) := io.allocate(lane).bits.accessSize
          lqUnsignedLoad(index) := io.allocate(lane).bits.unsignedLoad
          lqDestinationPhysical(index) := io.allocate(lane).bits.destinationPhysical
          lqWritesInteger(index) := io.allocate(lane).bits.writesInteger
          lqM1Owner(index) := io.allocate(lane).bits.m1Owner
          lqIsAtomic(index) := io.allocate(lane).bits.isAtomic
          lqAq(index) := io.allocate(lane).bits.aq
          lqRl(index) := io.allocate(lane).bits.rl
          lqAddressValid(index) := false.B
          lqCompleted(index) := false.B
          lqMetadataValid(index) := false.B
        }
        when(io.allocate(lane).bits.allocateStore) {
          val index = sqAllocation(lane)
          sqValid(index) := true.B
          sqTag(index) := io.allocate(lane).bits.robTag
          sqAddressValid(index) := false.B
          sqAccessSize(index) := io.allocate(lane).bits.accessSize
          sqDataValid(index) := false.B
          sqIsAtomic(index) := io.allocate(lane).bits.isAtomic
          sqAq(index) := io.allocate(lane).bits.aq
          sqRl(index) := io.allocate(lane).bits.rl
          sqCommitAuthorized(index) := false.B
          sqEffectIssued(index) := false.B
          sqEffectComplete(index) := false.B
          sqMetadataValid(index) := false.B
        }
      }
    }

    when(io.storeAddress.fire) {
      sqAddressValid(storeAddressIndex) := true.B
      sqAddress(storeAddressIndex) := io.storeAddress.bits.address
      sqWriteMask(storeAddressIndex) := io.storeAddress.bits.writeMask
    }
    when(io.storeData.fire) {
      sqDataValid(storeDataIndex) := true.B
      sqWriteData(storeDataIndex) := io.storeData.bits.writeData
    }
    when(io.loadAddress.fire) {
      lqAddressValid(loadAddressIndex) := true.B
      lqAddress(loadAddressIndex) := io.loadAddress.bits.address
      lqReadMask(loadAddressIndex) := io.loadAddress.bits.readMask
      lqForwardMask(loadAddressIndex) := requestedForwardMask
      lqForwardData(loadAddressIndex) := forwardData
    }
    when(io.loadComplete.fire) {
      lqCompleted(loadCompleteIndex) := true.B
      lqMetadataValid(loadCompleteIndex) := true.B
      lqMetadata(loadCompleteIndex).robTag := lqTag(loadCompleteIndex)
      lqMetadata(loadCompleteIndex).address := lqAddress(loadCompleteIndex)
      lqMetadata(loadCompleteIndex).readMask := lqReadMask(loadCompleteIndex)
      lqMetadata(loadCompleteIndex).writeMask := 0.U
      lqMetadata(loadCompleteIndex).readData := completedLoadData
      lqMetadata(loadCompleteIndex).writeData := 0.U
    }
    when(io.commitAuthorize.fire) {
      sqCommitAuthorized(commitIndex) := true.B
    }
    when(io.storeEffect.fire) {
      sqEffectIssued(selectedStoreIndex) := true.B
    }
    when(io.storeEffectComplete.valid && effectCompleteMatch) {
      assert(sqCommitAuthorized(effectCompleteIndex) && sqEffectIssued(effectCompleteIndex),
        "store effect completion requires an issued commit-authorized action")
      assert(!sqEffectComplete(effectCompleteIndex), "store effect completed twice")
      sqEffectComplete(effectCompleteIndex) := true.B
      sqMetadataValid(effectCompleteIndex) := true.B
      sqMetadata(effectCompleteIndex).robTag := sqTag(effectCompleteIndex)
      sqMetadata(effectCompleteIndex).address := sqAddress(effectCompleteIndex)
      sqMetadata(effectCompleteIndex).readMask := 0.U
      sqMetadata(effectCompleteIndex).writeMask := sqWriteMask(effectCompleteIndex)
      sqMetadata(effectCompleteIndex).readData := 0.U
      sqMetadata(effectCompleteIndex).writeData := sqWriteData(effectCompleteIndex)
    }
  }

  assert(!io.allocate(1).valid || io.allocate(0).valid,
    "LSQ allocation lane 1 cannot be valid when lane 0 is a bubble")
  when(io.allocate(0).fire && io.allocate(1).fire) {
    assert(io.allocate(0).bits.robTag =/= io.allocate(1).bits.robTag,
      "two LSQ allocations cannot share a ROB tag")
  }
  for (first <- 0 until lqEntries; second <- first + 1 until lqEntries) {
    assert(!(lqValid(first) && lqValid(second) && lqTag(first) === lqTag(second)),
      "two live LQ entries cannot share a ROB tag")
  }
  for (first <- 0 until sqEntries; second <- first + 1 until sqEntries) {
    assert(!(sqValid(first) && sqValid(second) && sqTag(first) === sqTag(second)),
      "two live SQ entries cannot share a ROB tag")
  }
  when(io.squash.valid) {
    assert(io.squash.bits(config.robIndexWidth - 1, 0) < config.robEntries.U,
      "LSQ squash boundary ROB index out of range")
    assert(!io.allocate.exists(_.fire) && !io.storeAddress.fire && !io.storeData.fire &&
      !io.loadAddress.fire && !io.commitAuthorize.fire && !io.storeEffect.fire,
      "LSQ transferred local work during selective squash")
  }
  assert(PopCount(lqValid) <= lqEntries.U, "LQ occupancy exceeded its depth")
  assert(PopCount(sqValid) <= sqEntries.U, "SQ occupancy exceeded its depth")
  io.loadCount := PopCount(lqValid)
  io.storeCount := PopCount(sqValid)
}
