package zircon.frontend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

class BranchTargetTraining extends Bundle {
  val pc = UInt(32.W)
  val target = UInt(32.W)
  val conditional = Bool()
  val call = Bool()
  val ret = Bool()
}

class BranchTargetPrediction extends Bundle {
  val hit = Bool()
  val way = UInt(1.W)
  val target = UInt(32.W)
  val conditional = Bool()
  val call = Bool()
  val ret = Bool()
}

class BranchTargetEntry extends Bundle {
  val valid = Bool()
  val tag = UInt(25.W)
  val target = UInt(32.W)
  val conditional = Bool()
  val call = Bool()
  val ret = Bool()
}

/** A 64-entry, two-way BTB banked for four consecutive instruction queries. */
class BankedBranchTargetBuffer(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val banksCount = 4
  private val waysCount = 2
  private val rowsPerBank = 8

  val io = IO(new Bundle {
    val fetchBase = Input(UInt(32.W))
    val predictions = Output(Vec(config.fetchWidth, new BranchTargetPrediction))
    val train = Input(Valid(new BranchTargetTraining))
    val invalidate = Input(Bool())
    val ready = Output(Bool())
  })

  require(config.fetchWidth == 4, "the BTB bank mapping is frozen for four-wide fetch")

  private val entryWidth = 1 + 25 + 32 + 1 + 1 + 1
  private val storageWidth = 64
  // Pack the two ways into one physical RAM per bank.  Keeping both ways in
  // the same read word removes eight tiny distributed-RAM instances and their
  // replicated address/decode muxes.  A training write preserves the other
  // way using the old read word (the RAM is read-first).
  val arrays = Seq.tabulate(banksCount)((_) =>
    Module(new BranchTargetMemory(rowsPerBank, storageWidth * waysCount)))
  val replacement = RegInit(VecInit.fill(banksCount)(
    VecInit.fill(rowsPerBank)(false.B)))
  val scrubbing = RegInit(true.B)
  val scrubRow = RegInit(0.U(3.W))

  val queryPc = VecInit.tabulate(config.fetchWidth)(slot =>
    io.fetchBase + (slot * 4).U(32.W))
  val queryBank = VecInit(queryPc.map(_(3, 2)))
  val queryRow = VecInit(queryPc.map(_(6, 4)))
  val queryTag = VecInit(queryPc.map(_(31, 7)))

  val trainBank = io.train.bits.pc(3, 2)
  val trainRow = io.train.bits.pc(6, 4)
  val trainTag = io.train.bits.pc(31, 7)
  val training = io.train.valid && !scrubbing && !io.invalidate

  val bankReads = Wire(Vec(banksCount,
    Vec(waysCount, new BranchTargetEntry)))
  for (bank <- 0 until banksCount) {
    val slotMatches = (0 until config.fetchWidth).map(slot =>
      queryBank(slot) === bank.U)
    val queryRowForBank = Mux1H((0 until config.fetchWidth).map(slot =>
      slotMatches(slot) -> queryRow(slot)))
    val trainingThisBank = training && trainBank === bank.U
    val readRow = Mux(trainingThisBank, trainRow, queryRowForBank)

    arrays(bank).io.clk := clock
    arrays(bank).io.readAddress := readRow
    val packedRead = arrays(bank).io.readData
    for (way <- 0 until waysCount) {
      bankReads(bank)(way) := packedRead(
        way * storageWidth + entryWidth - 1,
        way * storageWidth).asTypeOf(new BranchTargetEntry)
    }

    assert(PopCount(slotMatches) === 1.U,
      "a four-instruction fetch group must access every BTB bank once")
  }

  val trainHits = Wire(Vec(waysCount, Bool()))
  for (way <- 0 until waysCount) {
    val entry = bankReads(trainBank)(way)
    trainHits(way) := entry.valid && entry.tag === trainTag
  }
  val trainWay = Mux(trainHits(0), 0.U,
    Mux(trainHits(1), 1.U,
      Mux(!bankReads(trainBank)(0).valid, 0.U,
        Mux(!bankReads(trainBank)(1).valid, 1.U,
          replacement(trainBank)(trainRow)))))

  val trainedEntry = Wire(new BranchTargetEntry)
  trainedEntry.valid := true.B
  trainedEntry.tag := trainTag
  trainedEntry.target := io.train.bits.target
  trainedEntry.conditional := io.train.bits.conditional
  trainedEntry.call := io.train.bits.call
  trainedEntry.ret := io.train.bits.ret

  val trainedStorage = Cat(0.U((storageWidth - entryWidth).W), trainedEntry.asUInt)
  for (bank <- 0 until banksCount) {
    arrays(bank).io.writeEnable := training && trainBank === bank.U
    arrays(bank).io.writeAddress := trainRow
    val oldWay0 = bankReads(bank)(0).asUInt.pad(storageWidth)
    val oldWay1 = bankReads(bank)(1).asUInt.pad(storageWidth)
    arrays(bank).io.writeData := Mux(trainWay === 0.U,
      Cat(oldWay1, trainedStorage), Cat(trainedStorage, oldWay0))
    when(scrubbing && !io.invalidate) {
      arrays(bank).io.writeEnable := true.B
      arrays(bank).io.writeAddress := scrubRow
      arrays(bank).io.writeData := 0.U((storageWidth * waysCount).W)
    }

    when(scrubbing && !io.invalidate) {
      replacement(bank)(scrubRow) := false.B
    }.elsewhen(training && trainBank === bank.U) {
      replacement(bank)(trainRow) := !trainWay
    }
  }

  when(io.invalidate) {
    scrubbing := true.B
    scrubRow := 0.U
  }.elsewhen(scrubbing) {
    when(scrubRow === (rowsPerBank - 1).U) {
      scrubbing := false.B
    }.otherwise {
      scrubRow := scrubRow + 1.U
    }
  }

  io.ready := !scrubbing && !io.invalidate && !io.train.valid
  for (slot <- 0 until config.fetchWidth) {
    val entries = Wire(Vec(waysCount, new BranchTargetEntry))
    val wayHits = Wire(Vec(waysCount, Bool()))
    for (way <- 0 until waysCount) {
      entries(way) := Mux1H((0 until banksCount).map(bank =>
        (queryBank(slot) === bank.U) -> bankReads(bank)(way)))
      wayHits(way) := entries(way).valid && entries(way).tag === queryTag(slot)
    }

    val selectedWay = PriorityEncoder(wayHits)
    val selectedEntry = Mux(selectedWay === 1.U, entries(1), entries(0))
    val predictionHit = io.ready && wayHits.asUInt.orR
    io.predictions(slot).hit := predictionHit
    io.predictions(slot).way := Mux(predictionHit, selectedWay, 0.U)
    io.predictions(slot).target := Mux(predictionHit, selectedEntry.target, 0.U)
    io.predictions(slot).conditional := predictionHit && selectedEntry.conditional
    io.predictions(slot).call := predictionHit && selectedEntry.call
    io.predictions(slot).ret := predictionHit && selectedEntry.ret

    when(io.ready) {
      assert(PopCount(wayHits) <= 1.U,
        "a BTB query matched both ways in one set")
    }
  }

  assert(!io.fetchBase(1, 0).orR,
    "BTB fetch base must be four-byte aligned")
  when(io.train.valid) {
    assert(!scrubbing && !io.invalidate,
      "BTB training cannot arrive during invalidation scrub")
    assert(!io.train.bits.pc(1, 0).orR,
      "BTB training PC must be four-byte aligned")
    assert(!io.train.bits.target(1, 0).orR,
      "BTB training target must be four-byte aligned")
    assert(!(io.train.bits.conditional &&
      (io.train.bits.call || io.train.bits.ret)),
      "a conditional BTB entry cannot carry a RAS action")
    assert(PopCount(trainHits) <= 1.U,
      "BTB training matched duplicate ways")
  }
}

class RasAction extends Bundle {
  val push = Bool()
  val pop = Bool()
  val returnAddress = UInt(32.W)
}

class RasRecoveryRequest extends Bundle {
  val pointerBefore = UInt(3.W)
  val countBefore = UInt(4.W)
  val action = new RasAction
}

/** Eight-entry speculative return-address stack with checkpoint recovery. */
class ReturnAddressStack extends Module {
  private val entriesCount = 8

  val io = IO(new Bundle {
    val topValid = Output(Bool())
    val top = Output(UInt(32.W))
    val pointer = Output(UInt(3.W))
    val count = Output(UInt(4.W))
    val speculate = Flipped(Decoupled(new RasAction))
    val recover = Input(Valid(new RasRecoveryRequest))
    val clear = Input(Bool())
  })

  val entries = Reg(Vec(entriesCount, UInt(32.W)))
  val pointer = RegInit(0.U(3.W))
  val count = RegInit(0.U(4.W))

  private def pointerAfterPop(basePointer: UInt, baseCount: UInt,
      pop: Bool): UInt =
    Mux(pop && baseCount =/= 0.U, basePointer -% 1.U, basePointer)

  private def countAfterPop(baseCount: UInt, pop: Bool): UInt =
    Mux(pop && baseCount =/= 0.U, baseCount - 1.U, baseCount)

  private def pointerAfterAction(basePointer: UInt, baseCount: UInt,
      action: RasAction): UInt = {
    val poppedPointer = pointerAfterPop(basePointer, baseCount, action.pop)
    Mux(action.push, poppedPointer +% 1.U, poppedPointer)
  }

  private def countAfterAction(baseCount: UInt, action: RasAction): UInt = {
    val poppedCount = countAfterPop(baseCount, action.pop)
    Mux(action.push,
      Mux(poppedCount < entriesCount.U, poppedCount + 1.U, entriesCount.U),
      poppedCount)
  }

  val speculativeWriteIndex = pointerAfterPop(pointer, count,
    io.speculate.bits.pop)
  val recoveryWriteIndex = pointerAfterPop(io.recover.bits.pointerBefore,
    io.recover.bits.countBefore, io.recover.bits.action.pop)

  io.speculate.ready := !io.clear && !io.recover.valid
  when(io.clear) {
    pointer := 0.U
    count := 0.U
  }.elsewhen(io.recover.valid) {
    when(io.recover.bits.action.push) {
      entries(recoveryWriteIndex) := io.recover.bits.action.returnAddress
    }
    pointer := pointerAfterAction(io.recover.bits.pointerBefore,
      io.recover.bits.countBefore, io.recover.bits.action)
    count := countAfterAction(io.recover.bits.countBefore,
      io.recover.bits.action)
  }.elsewhen(io.speculate.fire) {
    when(io.speculate.bits.push) {
      entries(speculativeWriteIndex) := io.speculate.bits.returnAddress
    }
    pointer := pointerAfterAction(pointer, count, io.speculate.bits)
    count := countAfterAction(count, io.speculate.bits)
  }

  val topIndex = pointer -% 1.U
  io.topValid := count =/= 0.U
  io.top := Mux(io.topValid, entries(topIndex), 0.U)
  io.pointer := pointer
  io.count := count

  assert(count <= entriesCount.U, "RAS occupancy exceeded eight entries")
  when(io.recover.valid) {
    assert(io.recover.bits.countBefore <= entriesCount.U,
      "RAS recovery checkpoint has an illegal occupancy")
    when(io.recover.bits.action.push) {
      assert(!io.recover.bits.action.returnAddress(1, 0).orR,
        "a recovered RAS push address must be four-byte aligned")
    }
  }
  when(io.speculate.fire && io.speculate.bits.push) {
    assert(!io.speculate.bits.returnAddress(1, 0).orR,
      "a speculative RAS push address must be four-byte aligned")
  }
}

class FetchRedirectPrediction(config: ZirconCoreConfig) extends Bundle {
  val slot = UInt(log2Ceil(config.fetchWidth).W)
  val target = UInt(32.W)
  val conditional = Bool()
  val call = Bool()
  val ret = Bool()
  val btbWay = UInt(1.W)
  val btbHit = Bool()
  val rasUsed = Bool()
}

/** Selects the earliest taken control prediction and the matching RAS event. */
class FetchTargetSelector(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val fetchBase = Input(UInt(32.W))
    val predictorsReady = Input(Bool())
    val slotValid = Input(Vec(config.fetchWidth, Bool()))
    val predecode = Input(Vec(config.fetchWidth, new ControlPredecode))
    val btb = Input(Vec(config.fetchWidth, new BranchTargetPrediction))
    val directionTaken = Input(Vec(config.fetchWidth, Bool()))
    val rasTopValid = Input(Bool())
    val rasTop = Input(UInt(32.W))
    val redirect = Output(Valid(new FetchRedirectPrediction(config)))
    val unresolvedIndirect = Output(Valid(UInt(log2Ceil(config.fetchWidth).W)))
    val acceptedMask = Output(UInt(config.fetchWidth.W))
    val rasAction = Output(Valid(new RasAction))
  })

  require(config.fetchWidth == 4,
    "the target selector is frozen for four-wide fetch")

  val redirectCandidates = Wire(Vec(config.fetchWidth, Bool()))
  val unresolvedCandidates = Wire(Vec(config.fetchWidth, Bool()))
  val stopCandidates = Wire(Vec(config.fetchWidth, Bool()))
  for (slot <- 0 until config.fetchWidth) {
    val directTaken = io.predecode(slot).direct &&
      (!io.predecode(slot).conditional || io.directionTaken(slot))
    val indirectTargetValid = io.predecode(slot).indirect &&
      ((io.predecode(slot).ret && io.rasTopValid) || io.btb(slot).hit)
    redirectCandidates(slot) := io.predictorsReady && io.slotValid(slot) &&
      io.predecode(slot).control && (directTaken || indirectTargetValid)
    unresolvedCandidates(slot) := io.predictorsReady && io.slotValid(slot) &&
      io.predecode(slot).control && io.predecode(slot).indirect &&
      !indirectTargetValid
    stopCandidates(slot) := redirectCandidates(slot) ||
      unresolvedCandidates(slot)
  }
  val ownerOH = PriorityEncoderOH(stopCandidates.asUInt)
  val ownerSlot = OHToUInt(ownerOH)
  val selectedPredecode = Mux1H(ownerOH, io.predecode)
  val selectedBtb = Mux1H(ownerOH, io.btb)
  val selectedRedirect = Mux1H(ownerOH, redirectCandidates)
  val selectedUnresolved = Mux1H(ownerOH, unresolvedCandidates)
  val rasUsed = selectedPredecode.ret && io.rasTopValid
  val indirectTarget = Mux(rasUsed, io.rasTop, selectedBtb.target)
  val selectedTarget = Mux(selectedPredecode.direct,
    selectedPredecode.directTarget, indirectTarget)

  io.redirect.valid := stopCandidates.asUInt.orR && selectedRedirect
  io.redirect.bits.slot := ownerSlot
  io.redirect.bits.target := selectedTarget
  io.redirect.bits.conditional := selectedPredecode.conditional
  io.redirect.bits.call := selectedPredecode.call
  io.redirect.bits.ret := selectedPredecode.ret
  io.redirect.bits.btbWay := selectedBtb.way
  io.redirect.bits.btbHit := selectedBtb.hit
  io.redirect.bits.rasUsed := rasUsed

  io.unresolvedIndirect.valid := stopCandidates.asUInt.orR && selectedUnresolved
  io.unresolvedIndirect.bits := ownerSlot

  val accepted = Wire(Vec(config.fetchWidth, Bool()))
  for (slot <- 0 until config.fetchWidth) {
    val stoppedEarlier = if (slot == 0) false.B
      else stopCandidates.take(slot).reduce(_ || _)
    accepted(slot) := io.predictorsReady && io.slotValid(slot) && !stoppedEarlier
  }
  io.acceptedMask := accepted.asUInt

  io.rasAction.valid := io.redirect.valid &&
    (selectedPredecode.call || selectedPredecode.ret)
  io.rasAction.bits.push := selectedPredecode.call
  io.rasAction.bits.pop := selectedPredecode.ret
  io.rasAction.bits.returnAddress := io.fetchBase +
    (ownerSlot << 2) + 4.U

  assert(PopCount(ownerOH) <= 1.U,
    "more than one redirect owner was selected")
  private def isPrefix(mask: UInt): Bool =
    (0 to config.fetchWidth).map(length =>
      mask === ((BigInt(1) << length) - 1).U(config.fetchWidth.W)
    ).reduce(_ || _)
  assert(isPrefix(io.slotValid.asUInt),
    "fetch slot validity must be a low-order prefix")
  assert(isPrefix(io.acceptedMask),
    "accepted fetch slots must be a low-order prefix")
  assert(!(io.redirect.valid && io.unresolvedIndirect.valid),
    "redirect and unresolved-indirect ownership must be exclusive")
  when(!io.predictorsReady) {
    assert(!io.redirect.valid,
      "a redirect escaped while the predictors were not ready")
    assert(!io.unresolvedIndirect.valid,
      "an indirect barrier escaped while the predictors were not ready")
    assert(io.acceptedMask === 0.U,
      "fetch slots were accepted while the predictors were not ready")
  }
}
