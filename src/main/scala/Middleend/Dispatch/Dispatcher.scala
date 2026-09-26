import chisel3._
import chisel3.util._
import ZirconConfig._

/** Registered dispatch classification for the Rename-to-Dispatch boundary. */
class DispatchClass extends Bundle {
    val shortArith = Bool()
    val mixArith = Bool()
    val load = Bool()
    val storeOrAtomic = Bool()
    val noIssue = Bool()
}

class DispatcherIO(p: BackendParams, issue: IssueParams) extends Bundle {
    val in = Input(new IssueEnqueueGroup(p, issue.dispatchWidth))
    val memoryEntries = Input(Vec(issue.dispatchWidth, new BackendPackage(p)))
    val dispatchClass = Input(Vec(issue.dispatchWidth, new DispatchClass))
    // Bit n permits the ordered prefix through instruction n.
    val resourcePrefix = Input(UInt(issue.dispatchWidth.W))
    // Bit n means that the queue can accept at least n + 1 entries.
    val freePrefix = Input(Vec(IssueQueueIndex.Count, UInt(issue.dispatchWidth.W)))
    val clearPreference = Input(Bool())

    val accepted = Output(UInt(issue.dispatchWidth.W))
    val enqueue = Output(Vec(IssueQueueIndex.Count, new RoutedIssueEnqueueGroup(p, issue.dispatchWidth)))
}

/**
  * Whole-group dispatcher with alternating routes for interchangeable queues.
  *
  * Rename stores one operation class with every stage entry. Dispatch derives
  * the corresponding queue requests in parallel. A Store or Atomic reserves
  * its address and data queues together.
  */
class Dispatcher(
    val p: BackendParams = BackendParams(),
    val issue: IssueParams = IssueParams(),
) extends Module {
    val io = IO(new DispatcherIO(p, issue))
    private val width = issue.dispatchWidth

    val preferArith1 = RegInit(false.B)
    val preferLoadAddress = RegInit(false.B)
    val active = io.in.valid.asBools
    val arithClasses = io.dispatchClass.map(_.shortArith)
    val loadClasses = io.dispatchClass.map(_.load)
    val arith = (0 until width).map(lane => active(lane) && io.dispatchClass(lane).shortArith)
    val loads = (0 until width).map(lane => active(lane) && io.dispatchClass(lane).load)
    val arithTo1 = (0 until width).map { lane =>
        // A live lane implies all older lanes are live (ordered-prefix input).
        val olderParity = if (lane == 0) false.B else PopCount(arithClasses.take(lane))(0)
        olderParity ^ preferArith1
    }
    val loadToAddress = (0 until width).map { lane =>
        val olderParity = if (lane == 0) false.B else PopCount(loadClasses.take(lane))(0)
        olderParity ^ preferLoadAddress
    }

    private def laneRequest(index: Int, lane: Int): Bool = {
        val dispatchClass = io.dispatchClass(lane)
        index match {
            case IssueQueueIndex.Arith0 => dispatchClass.shortArith && !arithTo1(lane)
            case IssueQueueIndex.Arith1 => dispatchClass.shortArith && arithTo1(lane)
            case IssueQueueIndex.MixArith => dispatchClass.mixArith
            case IssueQueueIndex.Load => dispatchClass.load && !loadToAddress(lane)
            case IssueQueueIndex.LoadStoreAddress =>
                dispatchClass.storeOrAtomic || (dispatchClass.load && loadToAddress(lane))
            case IssueQueueIndex.StoreData => dispatchClass.storeOrAtomic
            case _ => false.B
        }
    }

    private def atLeast(values: Seq[Bool], amount: Int): Bool = {
        require(amount >= 0)
        if (amount == 0) true.B
        else if (amount > values.size) false.B
        else values.combinations(amount).map(_.reduce(_ && _)).reduce(_ || _)
    }

    private def hasCapacity(index: Int, requests: Seq[Bool]): Bool = {
        (1 to width).map { amount =>
            !atLeast(requests, amount) || io.freePrefix(index)(amount - 1)
        }.reduce(_ && _)
    }

    val classRequests = (0 until IssueQueueIndex.Count).map { index =>
        (0 until width).map(lane => laneRequest(index, lane))
    }
    val queueRequests = classRequests.map(_.zip(active).map { case (request, valid) => request && valid })
    // Both vectors are prefixes: a parallel subset check replaces last-lane
    // selection on the Dispatch-to-Rename writeback path.
    val resourceReady = (io.in.valid & ~io.resourcePrefix) === 0.U
    // Capacity for each possible live prefix is independent of the late valid
    // bits. Only the final prefix choice depends on the held stage occupancy.
    val capacityByPrefix = (0 to width).map { prefix =>
        classRequests.zipWithIndex.map { case (requests, index) =>
            hasCapacity(index, requests.take(prefix))
        }.reduce(_ && _)
    }
    val prefixSelect = (0 to width).map { prefix =>
        if (prefix == 0) !active(0)
        else if (prefix == width) active(width - 1)
        else active(prefix - 1) && !active(prefix)
    }
    val queueReady = Mux1H(prefixSelect, capacityByPrefix)
    assert(PopCount(prefixSelect) === 1.U, "Dispatch valid must identify one ordered prefix")
    val groupReady = resourceReady && queueReady

    // The fixed Rename-to-Dispatch register is either consumed as an ordered
    // group or retained intact. This is the state boundary used by Rename
    // writeback and by every Commit allocation in the same cycle.
    io.accepted := Mux(groupReady, io.in.valid, 0.U)

    when(io.clearPreference) {
        preferArith1 := false.B
        preferLoadAddress := false.B
    }.elsewhen(io.accepted.orR) {
        preferArith1 := preferArith1 ^ PopCount(arith)(0)
        preferLoadAddress := preferLoadAddress ^ PopCount(loads)(0)
    }

    for ((output, index) <- io.enqueue.zipWithIndex) {
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
        val hits = queueRequests(index)
        output.candidates := VecInit(entries)
        output.valid := VecInit((1 to width).map(amount => groupReady && atLeast(hits, amount))).asUInt
        for (position <- 0 until width) {
            val selection = VecInit((0 until width).map { lane =>
                hits(lane) && PopCount(hits.take(lane)) === position.U
            }).asUInt
            output.selection(position) := selection
            output.entries(position) := Mux1H(selection.asBools, entries)
            assert(PopCount(selection) <= 1.U, "Dispatch queue selection must be one-hot or zero-hot")
        }
    }

    FIFOUtil.assertPrefix(io.in.valid.asBools, "Dispatcher input valid must be a prefix")
    FIFOUtil.assertPrefix(io.resourcePrefix.asBools, "Dispatcher resource permission must be a prefix")
    FIFOUtil.assertPrefix(io.accepted.asBools, "Dispatcher acceptance must be a prefix")
    io.enqueue.foreach(output => FIFOUtil.assertPrefix(output.valid.asBools, "Issue queue enqueue must be a prefix"))
    for (lane <- 0 until width) {
        when(io.in.valid(lane)) {
            val classes = io.dispatchClass(lane)
            assert(
                PopCount(Seq(
                    classes.shortArith,
                    classes.mixArith,
                    classes.load,
                    classes.storeOrAtomic,
                    classes.noIssue,
                )) === 1.U,
                "Every live dispatch lane must have exactly one route class",
            )
        }
    }
    for ((output, index) <- io.enqueue.zipWithIndex) {
        for (lane <- 0 until width) {
            assert(!output.valid(lane) || io.freePrefix(index)(lane), "Dispatcher must not overbook an issue queue")
        }
    }
}
