package ZirconConfig

import chisel3.util.log2Ceil

object IssueQueueIndex {
    val Arith0 = 0
    val Arith1 = 1
    val MixArith = 2
    val Load = 3
    val LoadStoreAddress = 4
    val StoreData = 5
    val Count = 6
}

sealed trait IssueQueueProfile {
    def label: String
    def sourceMask: Int
    def allowedUnits: Set[Int]
}

object IssueQueueProfile {
    case object ArithBranch extends IssueQueueProfile {
        val label = "ArithBranch"
        val sourceMask = 0x3
        val allowedUnits = Set(DecodeUnit.ALU, DecodeUnit.Branch)
    }

    case object MixArith extends IssueQueueProfile {
        val label = "MixArith"
        val sourceMask = 0x7
        val allowedUnits = Set(DecodeUnit.Multiply, DecodeUnit.Divide, DecodeUnit.FpMisc, DecodeUnit.System)
    }

    case object Load extends IssueQueueProfile {
        val label = "Load"
        val sourceMask = 0x1
        val allowedUnits = Set(DecodeUnit.Load)
    }

    case object LoadStoreAddress extends IssueQueueProfile {
        val label = "LoadStoreAddress"
        val sourceMask = 0x1
        val allowedUnits = Set(DecodeUnit.Load, DecodeUnit.Store, DecodeUnit.Atomic)
    }

    case object StoreData extends IssueQueueProfile {
        val label = "StoreData"
        val sourceMask = 0x1
        val allowedUnits = Set(DecodeUnit.Store, DecodeUnit.Atomic)
    }
}

final case class IssueQueueParams(
    entries: Int,
    profile: IssueQueueProfile,
    enqueueWidth: Int = 2,
    wakeupPorts: Int = 8,
    replayEntries: Int = 0,
) {
    require(entries >= enqueueWidth, "Issue queue must accept one complete dispatch group")
    require(enqueueWidth > 0 && wakeupPorts > 0 && replayEntries >= 0)
    require(profile.sourceMask >= 0 && profile.sourceMask < 8)
}

final case class IssueParams(
    dispatchWidth: Int = 3,
    wakeupPorts: Int = 8,
    arith0Entries: Int = 7,
    arith1Entries: Int = 7,
    mixArithEntries: Int = 8,
    loadEntries: Int = 6,
    loadStoreAddressEntries: Int = 8,
    storeDataEntries: Int = 6,
    arithReplayEntries: Int = 4,
) {
    require(dispatchWidth >= 1 && dispatchWidth <= 4, "Dispatch width must be between one and four")
    require(wakeupPorts > 0 && arithReplayEntries > 0)

    val countWidth: Int = log2Ceil(Seq(
        arith0Entries,
        arith1Entries,
        mixArithEntries,
        loadEntries,
        loadStoreAddressEntries,
        storeDataEntries,
    ).max + 1)

    def arith0: IssueQueueParams =
        IssueQueueParams(
            arith0Entries,
            IssueQueueProfile.ArithBranch,
            dispatchWidth,
            wakeupPorts,
            arithReplayEntries
        )
    def arith1: IssueQueueParams =
        IssueQueueParams(arith1Entries, IssueQueueProfile.ArithBranch, dispatchWidth, wakeupPorts, arithReplayEntries)
    def mixArith: IssueQueueParams =
        IssueQueueParams(mixArithEntries, IssueQueueProfile.MixArith, dispatchWidth, wakeupPorts)
    def load: IssueQueueParams =
        IssueQueueParams(loadEntries, IssueQueueProfile.Load, dispatchWidth, wakeupPorts)
    def loadStoreAddress: IssueQueueParams =
        IssueQueueParams(loadStoreAddressEntries, IssueQueueProfile.LoadStoreAddress, dispatchWidth, wakeupPorts)
    def storeData: IssueQueueParams =
        IssueQueueParams(storeDataEntries, IssueQueueProfile.StoreData, dispatchWidth, wakeupPorts)

    /** Queue parameters in the order defined by IssueQueueIndex. */
    def queueParams: Seq[IssueQueueParams] = Seq(
        arith0,
        arith1,
        mixArith,
        load,
        loadStoreAddress,
        storeData,
    )
}
