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

  val arrays = Seq.tabulate(banksCount, waysCount)((_, _) =>
    Mem(rowsPerBank, new BranchTargetEntry))
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

    for (way <- 0 until waysCount) {
      bankReads(bank)(way) := arrays(bank)(way).read(readRow)
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

  for (bank <- 0 until banksCount) {
    for (way <- 0 until waysCount) {
      when(scrubbing && !io.invalidate) {
        arrays(bank)(way).write(scrubRow,
          0.U.asTypeOf(new BranchTargetEntry))
      }.elsewhen(training && trainBank === bank.U && trainWay === way.U) {
        arrays(bank)(way).write(trainRow, trainedEntry)
      }
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
  val rasUsed = Bool()
}

/** Selects the earliest taken control prediction and the matching RAS event. */
class FetchTargetSelector(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val fetchBase = Input(UInt(32.W))
    val btbReady = Input(Bool())
    val btb = Input(Vec(config.fetchWidth, new BranchTargetPrediction))
    val directionTaken = Input(Vec(config.fetchWidth, Bool()))
    val rasTopValid = Input(Bool())
    val rasTop = Input(UInt(32.W))
    val redirect = Output(Valid(new FetchRedirectPrediction(config)))
    val rasAction = Output(Valid(new RasAction))
  })

  require(config.fetchWidth == 4,
    "the target selector is frozen for four-wide fetch")

  val candidates = Wire(Vec(config.fetchWidth, Bool()))
  for (slot <- 0 until config.fetchWidth) {
    candidates(slot) := io.btbReady && io.btb(slot).hit &&
      (!io.btb(slot).conditional || io.directionTaken(slot))
  }
  val ownerOH = PriorityEncoderOH(candidates.asUInt)
  val ownerSlot = OHToUInt(ownerOH)
  val selected = Mux1H(ownerOH, io.btb)
  val rasUsed = selected.ret && io.rasTopValid
  val selectedTarget = Mux(rasUsed, io.rasTop, selected.target)

  io.redirect.valid := candidates.asUInt.orR
  io.redirect.bits.slot := ownerSlot
  io.redirect.bits.target := selectedTarget
  io.redirect.bits.conditional := selected.conditional
  io.redirect.bits.call := selected.call
  io.redirect.bits.ret := selected.ret
  io.redirect.bits.btbWay := selected.way
  io.redirect.bits.rasUsed := rasUsed

  io.rasAction.valid := io.redirect.valid && (selected.call || selected.ret)
  io.rasAction.bits.push := selected.call
  io.rasAction.bits.pop := selected.ret
  io.rasAction.bits.returnAddress := io.fetchBase +
    (ownerSlot << 2) + 4.U

  assert(PopCount(ownerOH) <= 1.U,
    "more than one redirect owner was selected")
  when(!io.btbReady) {
    assert(!io.redirect.valid,
      "a redirect escaped while the BTB query was not ready")
  }
}
