import chisel3._
import chisel3.util._
import ZirconConfig._

class DispatcherIO(p: BackendParams, issue: IssueParams) extends Bundle {
    val in = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val memoryEntries = Input(Vec(issue.dispatchWidth, new BackendPackage(p)))
    // Bit n permits the ordered prefix through instruction n.
    val resourcePrefix = Input(UInt(issue.dispatchWidth.W))
    val freeCount = Input(Vec(IssueQueueIndex.Count, UInt(issue.countWidth.W)))
    val flush = Input(Bool())

    val accepted = Output(UInt(issue.dispatchWidth.W))
    val arith0 = Output(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val arith1 = Output(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val mixArith = Output(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val load = Output(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val loadStoreAddress = Output(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val storeData = Output(new IssueEnqueueGroup(p, issue.dispatchWidth))
}

/**
  * Atomic dispatcher for the six active backend issue queues.
  *
  * All lane routes are evaluated from the same state. Every prefix plan computes
  * its complete queue demand before selection, so a younger lane never consumes a
  * result produced by an older lane's allocation decision.
  */
class Dispatcher(
    val p: BackendParams = BackendParams(),
    val issue: IssueParams = IssueParams(),
) extends Module {
    val io = IO(new DispatcherIO(p, issue))
    private val width = issue.dispatchWidth
    private val queueCount = IssueQueueIndex.Count
    private def queue(index: Int): UInt = (BigInt(1) << index).U(queueCount.W)

    val preferArith = RegInit(false.B)
    val preferLoadStore = RegInit(false.B)

    val alu = VecInit(io.in.entries.map(_.fu === DecodeUnit.ALU.U))
    val branch = VecInit(io.in.entries.map(_.fu === DecodeUnit.Branch.U))
    val multiply = VecInit(io.in.entries.map(_.fu === DecodeUnit.Multiply.U))
    val divide = VecInit(io.in.entries.map(_.fu === DecodeUnit.Divide.U))
    val fpMisc = VecInit(io.in.entries.map(_.fu === DecodeUnit.FpMisc.U))
    val load = VecInit(io.in.entries.map(_.fu === DecodeUnit.Load.U))
    val store = VecInit(io.in.entries.map(_.fu === DecodeUnit.Store.U))
    val atomic = VecInit(io.in.entries.map(_.fu === DecodeUnit.Atomic.U))
    val csr = VecInit(io.in.entries.map(item =>
        item.fu === DecodeUnit.System.U && item.op >= SystemOp.CSRRW.U && item.op <= SystemOp.CSRRCI.U
    ))
    val commitSystem = VecInit(io.in.entries.map(item =>
        item.fu === DecodeUnit.System.U &&
            (item.op <= SystemOp.FENCE_I.U || item.op >= SystemOp.ECALL.U)
    ))
    val nativeMix = VecInit((0 until width).map(lane => multiply(lane) || divide(lane) || fpMisc(lane)))
    val shortArith = VecInit((0 until width).map(lane => alu(lane) || branch(lane)))

    val noIssue = VecInit((0 until width).map { lane =>
        io.in.entries(lane).exception.valid || commitSystem(lane)
    })
    val recognized = VecInit((0 until width).map { lane =>
        alu(lane) || branch(lane) || nativeMix(lane) || load(lane) || store(lane) || atomic(lane) || csr(lane) ||
            noIssue(lane)
    })

    private def arithQueue(index: UInt): UInt =
        Mux(index(0), queue(IssueQueueIndex.Arith1), queue(IssueQueueIndex.Arith0))

    val arithFree = VecInit(Seq(
        io.freeCount(IssueQueueIndex.Arith0),
        io.freeCount(IssueQueueIndex.Arith1),
    ))
    val firstArith = Mux(
        arithFree(1) > arithFree(0) || (arithFree(1) === arithFree(0) && preferArith),
        1.U(1.W),
        0.U(1.W),
    )
    val arithOrder = VecInit(Seq(firstArith, ~firstArith))

    val prefixPlans: Seq[(Bool, Seq[UInt])] = if (width == 2) {
        val choiceValid = Wire(Vec(width, Vec(2, Bool())))
        val choiceRoute = Wire(Vec(width, Vec(2, UInt(queueCount.W))))
        for (lane <- 0 until width) {
            val loadStoreFirst = preferLoadStore ^ (lane == 1).B
            val fixed = MuxCase(0.U(queueCount.W), Seq(
                csr(lane) -> queue(IssueQueueIndex.MixArith),
                nativeMix(lane) -> queue(IssueQueueIndex.MixArith),
                (store(lane) || atomic(lane)) ->
                    (queue(IssueQueueIndex.LoadStoreAddress) | queue(IssueQueueIndex.StoreData)),
                noIssue(lane) -> 0.U(queueCount.W),
            ))
            choiceValid(lane)(0) := recognized(lane)
            choiceRoute(lane)(0) := Mux(
                shortArith(lane),
                arithQueue(arithOrder(0)),
                Mux(
                    load(lane),
                    Mux(loadStoreFirst, queue(IssueQueueIndex.LoadStoreAddress), queue(IssueQueueIndex.Load)),
                    fixed,
                ),
            )
            choiceValid(lane)(1) := shortArith(lane) || load(lane)
            choiceRoute(lane)(1) := Mux(
                shortArith(lane),
                arithQueue(arithOrder(1)),
                Mux(loadStoreFirst, queue(IssueQueueIndex.Load), queue(IssueQueueIndex.LoadStoreAddress)),
            )
        }

        def planFits(route0: UInt, route1: UInt, active1: Bool): Bool = {
            val capacities = (0 until queueCount).map { index =>
                val need = route0(index).asUInt +& (route1(index) && active1).asUInt
                io.freeCount(index) >= need
            }
            capacities.reduce(_ && _)
        }

        case class TwoRoutePlan(valid: Bool, route0: UInt, route1: UInt, collision: Bool)
        val pairOrder = (0 to 2).flatMap { rank =>
            (0 until 2).flatMap { first =>
                val second = rank - first
                if (second >= 0 && second < 2) Some(first -> second) else None
            }
        }
        val pairPlans = pairOrder.map { case (first, second) =>
            val route0 = choiceRoute(0)(first)
            val route1 = choiceRoute(1)(second)
            val valid = io.in.valid.andR && choiceValid(0)(first) && choiceValid(1)(second) &&
                planFits(route0, route1, true.B)
            TwoRoutePlan(valid, route0, route1, (route0 & route1).orR)
        }
        val nonCollidingPair = VecInit(pairPlans.map(plan => plan.valid && !plan.collision)).asUInt
        val anyPair = VecInit(pairPlans.map(_.valid)).asUInt
        val preferredPair = Mux(nonCollidingPair.orR, nonCollidingPair, anyPair)
        val pairSelect = PriorityEncoderOH(preferredPair)
        val pairFound = preferredPair.orR
        val pairRoute0 = Mux1H(pairSelect.asBools, pairPlans.map(_.route0))
        val pairRoute1 = Mux1H(pairSelect.asBools, pairPlans.map(_.route1))

        val singlePlans = (0 until 2).map { choice =>
            val route = choiceRoute(0)(choice)
            TwoRoutePlan(
                io.in.valid(0) && choiceValid(0)(choice) && planFits(route, 0.U, false.B),
                route,
                0.U,
                false.B,
            )
        }
        val singleValid = VecInit(singlePlans.map(_.valid)).asUInt
        val singleSelect = PriorityEncoderOH(singleValid)
        val singleFound = singleValid.orR
        val singleRoute = Mux1H(singleSelect.asBools, singlePlans.map(_.route0))
        Seq(
            (pairFound || singleFound) -> Seq(Mux(pairFound, pairRoute0, singleRoute), 0.U(queueCount.W)),
            pairFound -> Seq(pairRoute0, pairRoute1),
        )
    } else {
        (1 to width).map { count =>
            val inputValid = io.in.valid(count - 1, 0).andR && recognized.take(count).reduce(_ && _)
            val plans = for (arithOffset <- 0 until 2; loadOffset <- 0 until 2) yield {
                val routes = (0 until width).map { lane =>
                    if (lane >= count) {
                        0.U(queueCount.W)
                    } else {
                        val arithRank = if (lane == 0) 0.U else PopCount(shortArith.take(lane))
                        val loadRank = if (lane == 0) 0.U else PopCount(load.take(lane))
                        val arithIndex = (arithRank + arithOffset.U)(0)
                        val loadToAddress = (loadRank + loadOffset.U)(0)
                        MuxCase(0.U(queueCount.W), Seq(
                            noIssue(lane) -> 0.U(queueCount.W),
                            shortArith(lane) -> arithQueue(arithIndex),
                            (csr(lane) || nativeMix(lane)) -> queue(IssueQueueIndex.MixArith),
                            (store(lane) || atomic(lane)) ->
                                (queue(IssueQueueIndex.LoadStoreAddress) | queue(IssueQueueIndex.StoreData)),
                            load(lane) -> Mux(
                                loadToAddress,
                                queue(IssueQueueIndex.LoadStoreAddress),
                                queue(IssueQueueIndex.Load),
                            ),
                        ))
                    }
                }
                val capacities = (0 until queueCount).map { index =>
                    val need = PopCount(routes.map(_(index)))
                    io.freeCount(index) >= need
                }
                (inputValid && capacities.reduce(_ && _)) -> routes
            }
            val validPlans = VecInit(plans.map(_._1)).asUInt
            val selected = PriorityEncoderOH(validPlans)
            val routes = (0 until width).map { lane =>
                Mux1H(selected.asBools, plans.map(_._2(lane)))
            }
            validPlans.orR -> routes
        }
    }

    val eligiblePrefix = VecInit((1 to width).map { count =>
        !io.flush && io.resourcePrefix(count - 1) && prefixPlans(count - 1)._1
    }).asUInt
    val selectedPrefix = VecInit((1 to width).map { count =>
        val larger = if (count == width) false.B else eligiblePrefix(width - 1, count).orR
        eligiblePrefix(count - 1) && !larger
    })
    io.accepted := VecInit((0 until width).map { lane =>
        selectedPrefix.drop(lane).reduce(_ || _)
    }).asUInt
    val selectedRoute = Wire(Vec(width, UInt(queueCount.W)))
    for (lane <- 0 until width) {
        selectedRoute(lane) := Mux1H(selectedPrefix, prefixPlans.map(_._2(lane)))
    }

    val outputs = Seq(
        io.arith0,
        io.arith1,
        io.mixArith,
        io.load,
        io.loadStoreAddress,
        io.storeData,
    )
    for ((output, index) <- outputs.zipWithIndex) {
        val hits = VecInit((0 until width).map(lane => io.accepted(lane) && selectedRoute(lane)(index)))
        val inputEntries = if (
            index == IssueQueueIndex.Load || index == IssueQueueIndex.LoadStoreAddress ||
                index == IssueQueueIndex.StoreData
        ) {
            io.memoryEntries
        } else {
            io.in.entries
        }
        val entries = inputEntries.map { entry =>
            if (index == IssueQueueIndex.StoreData) BackendPackage.forStoreData(entry) else entry
        }
        val count = PopCount(hits)
        output.valid := VecInit((0 until width).map(lane => count > lane.U)).asUInt
        for (position <- 0 until width) {
            val sources = (0 until width).map { lane =>
                val older = if (lane == 0) 0.U else PopCount(hits.take(lane))
                hits(lane) && older === position.U
            }
            output.entries(position) := Mux1H(sources, entries)
        }
    }

    when(io.flush) {
        preferArith := false.B
        preferLoadStore := false.B
    }.otherwise {
        when(VecInit((0 until width).map(lane => io.accepted(lane) && shortArith(lane))).asUInt.orR) {
            preferArith := ~preferArith
        }
        when(VecInit((0 until width).map(lane => io.accepted(lane) && load(lane))).asUInt.orR) {
            preferLoadStore := ~preferLoadStore
        }
    }

    FIFOUtil.assertPrefix(io.in.valid.asBools, "Dispatcher input valid must be a prefix")
    FIFOUtil.assertPrefix(io.resourcePrefix.asBools, "Dispatcher resource permission must be a prefix")
    FIFOUtil.assertPrefix(io.accepted.asBools, "Dispatcher acceptance must be a prefix")
    outputs.foreach(output => FIFOUtil.assertPrefix(output.valid.asBools, "Issue queue enqueue must be a prefix"))
    for ((output, index) <- outputs.zipWithIndex) {
        assert(PopCount(output.valid) <= io.freeCount(index), "Dispatcher must not overbook an issue queue")
    }
}
