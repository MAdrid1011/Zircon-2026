package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.{BackendDispatch, BranchProvider}

class BackendDispatchSpec extends AnyFunSpec with ChiselSim {
  private val AddiX5X1One = BigInt("00108293", 16)
  private val AddX6X5X2 = BigInt("00228333", 16)
  private val LoadWordX7X1 = BigInt("0000a383", 16)
  private val BranchPlusEight = BigInt("00000463", 16)

  private def clearInputs(dut: BackendDispatch): Unit = {
    dut.io.blocked.poke(false)
    dut.io.renameFreeCount.poke(24)
    dut.io.renameReady.poke(true)
    dut.io.robCapacity.poke(2)
    dut.io.intCapacity.poke(2)
    dut.io.longCapacity.poke(2)
    dut.io.memCapacity.poke(2)
    dut.io.integerReady.poke((BigInt(1) << 56) - 1)

    for (lane <- 0 until 2) {
      val input = dut.io.input(lane)
      input.valid.poke(false)
      input.bits.instruction.poke(BigInt("00000013", 16))
      input.bits.privilege.poke(3)
      input.bits.fault.valid.poke(false)
      input.bits.fault.cause.poke(0)
      input.bits.fault.tval.poke(0)
      val prediction = input.bits.prediction
      prediction.pc.poke(BigInt("80000000", 16) + lane * 4)
      prediction.historyBefore.poke(0)
      prediction.predictedTaken.poke(false)
      prediction.predictedTarget.poke(BigInt("80000004", 16) + lane * 4)
      prediction.conditional.poke(false)
      prediction.call.poke(false)
      prediction.ret.poke(false)
      prediction.provider.poke(BranchProvider.Base)
      prediction.alternateProvider.poke(BranchProvider.Base)
      prediction.providerPrediction.poke(false)
      prediction.alternatePrediction.poke(false)
      prediction.btbWay.poke(0)
      prediction.rasPointerBefore.poke(0)
      prediction.rasCountBefore.poke(0)

      dut.io.renameResponse(lane).valid.poke(true)
      dut.io.renameResponse(lane).sourcePhysical1.poke(if (lane == 0) 1 else 32)
      dut.io.renameResponse(lane).sourcePhysical2.poke(2)
      dut.io.renameResponse(lane).oldDestinationPhysical.poke(5 + lane)
      dut.io.renameResponse(lane).newDestinationPhysical.poke(32 + lane)
      dut.io.renameResponse(lane).allocates.poke(true)

      dut.io.robEnqueue(lane).ready.poke(true)
      dut.io.robTags(lane).valid.poke(true)
      dut.io.robTags(lane).bits.poke(10 + lane)
      dut.io.intEnqueue(lane).ready.poke(true)
      dut.io.longEnqueue(lane).ready.poke(true)
      dut.io.memEnqueue(lane).ready.poke(true)
    }

    dut.io.bdbAllocate.ready.poke(true)
    dut.io.bdbAllocatedIndex.valid.poke(true)
    dut.io.bdbAllocatedIndex.bits.poke(5)
  }

  private def driveInstruction(
      dut: BackendDispatch,
      lane: Int,
      instruction: BigInt
  ): Unit = {
    dut.io.input(lane).valid.poke(true)
    dut.io.input(lane).bits.instruction.poke(instruction)
  }

  describe("BackendDispatch") {
    it("dispatches two integer uops and marks a same-cycle lane-1 RAW not ready") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, AddX6X5X2)

        dut.io.acceptedCount.expect(2)
        dut.io.input(0).ready.expect(true)
        dut.io.input(1).ready.expect(true)
        dut.io.renameAccept.expect(true)
        dut.io.robEnqueue(0).valid.expect(true)
        dut.io.robEnqueue(1).valid.expect(true)
        dut.io.intEnqueue(0).valid.expect(true)
        dut.io.intEnqueue(1).valid.expect(true)
        dut.io.intEnqueue(0).bits.robTag.expect(10)
        dut.io.intEnqueue(1).bits.robTag.expect(11)
        dut.io.intEnqueue(1).bits.sourcePhysical(0).expect(32)
        dut.io.intEnqueue(1).bits.sourceReady(0).expect(false)
        dut.io.readyAllocation(0).bits.expect(32)
        dut.io.readyAllocation(1).bits.expect(33)
        dut.io.readyAllocation.foreach(_.valid.expect(true))
        dut.io.bdbAllocate.valid.expect(false)
      }
    }

    it("dispatches only lane zero when a bundle contains two branches") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, BranchPlusEight)
        driveInstruction(dut, 1, BranchPlusEight)
        dut.io.renameResponse.foreach(_.allocates.poke(false))

        dut.io.acceptedCount.expect(1)
        dut.io.input(0).ready.expect(true)
        dut.io.input(1).ready.expect(false)
        dut.io.robEnqueue(0).valid.expect(true)
        dut.io.robEnqueue(1).valid.expect(false)
        dut.io.bdbAllocate.valid.expect(true)
        dut.io.bdbAllocate.bits.robTag.expect(10)
        dut.io.robEnqueue(0).bits.entry.hasBranchData.expect(true)
        dut.io.robEnqueue(0).bits.entry.branchDataIndex.expect(5)
        dut.io.intEnqueue(0).valid.expect(true)
        dut.io.intEnqueue(1).valid.expect(false)
      }
    }

    it("chooses the longest prefix allowed by queue, BDB, and ROB capacity") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, LoadWordX7X1)
        dut.io.memCapacity.poke(0)
        dut.io.acceptedCount.expect(1)
        dut.io.intEnqueue(0).valid.expect(true)
        dut.io.memEnqueue(0).valid.expect(false)

        dut.io.memCapacity.poke(2)
        dut.io.robCapacity.poke(1)
        dut.io.acceptedCount.expect(1)

        dut.io.robCapacity.poke(2)
        dut.io.input(1).bits.instruction.poke(AddX6X5X2)
        dut.io.renameResponse(1).allocates.poke(true)
        dut.io.renameFreeCount.poke(1)
        dut.io.acceptedCount.expect(1)

        dut.io.renameFreeCount.poke(24)
        dut.io.input(1).bits.instruction.poke(BranchPlusEight)
        dut.io.renameResponse(1).allocates.poke(false)
        dut.io.bdbAllocate.ready.poke(false)
        dut.io.acceptedCount.expect(1)
        dut.io.bdbAllocate.valid.expect(false)

        dut.io.intCapacity.poke(0)
        dut.io.acceptedCount.expect(0)
        dut.io.input(0).ready.expect(false)
      }
    }

    it("allocates BDB for a lane-one branch while dispatching lane zero") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, BranchPlusEight)
        dut.io.renameResponse(1).allocates.poke(false)

        dut.io.acceptedCount.expect(2)
        dut.io.bdbAllocate.valid.expect(true)
        dut.io.bdbAllocate.bits.robTag.expect(11)
        dut.io.robEnqueue(0).bits.entry.hasBranchData.expect(false)
        dut.io.robEnqueue(1).bits.entry.hasBranchData.expect(true)
        dut.io.robEnqueue(1).bits.entry.branchDataIndex.expect(5)
        dut.io.intEnqueue(0).bits.robTag.expect(10)
        dut.io.intEnqueue(1).bits.robTag.expect(11)
      }
    }

    it("compacts simultaneous integer and memory uops into each queue lane zero") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, LoadWordX7X1)

        dut.io.acceptedCount.expect(2)
        dut.io.intEnqueue(0).valid.expect(true)
        dut.io.intEnqueue(0).bits.robTag.expect(10)
        dut.io.intEnqueue(1).valid.expect(false)
        dut.io.memEnqueue(0).valid.expect(true)
        dut.io.memEnqueue(0).bits.robTag.expect(11)
        dut.io.memEnqueue(1).valid.expect(false)
      }
    }

    it("puts fetch and illegal faults only in the ROB and FirstFault candidates") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, BigInt("ffffffff", 16))
        dut.io.input(0).bits.fault.valid.poke(true)
        dut.io.input(0).bits.fault.cause.poke(1)
        dut.io.input(0).bits.fault.tval.poke(BigInt("80000000", 16))
        dut.io.renameResponse.foreach(_.allocates.poke(false))

        dut.io.acceptedCount.expect(2)
        dut.io.robEnqueue.foreach(_.valid.expect(true))
        dut.io.robEnqueue.foreach(_.bits.initiallyComplete.expect(true))
        dut.io.intEnqueue.foreach(_.valid.expect(false))
        dut.io.longEnqueue.foreach(_.valid.expect(false))
        dut.io.memEnqueue.foreach(_.valid.expect(false))
        dut.io.bdbAllocate.valid.expect(false)
        dut.io.readyAllocation.foreach(_.valid.expect(false))
        dut.io.renameRequest.foreach(_.writesRd.expect(false))
        dut.io.faultCandidate(0).valid.expect(true)
        dut.io.faultCandidate(0).record.robTag.expect(10)
        dut.io.faultCandidate(0).record.cause.expect(1)
        dut.io.faultCandidate(0).record.trapValue.expect(
          BigInt("80000000", 16))
        dut.io.faultCandidate(1).valid.expect(true)
        dut.io.faultCandidate(1).record.robTag.expect(11)
        dut.io.faultCandidate(1).record.cause.expect(2)
        dut.io.faultCandidate(1).record.trapValue.expect(
          BigInt("ffffffff", 16))
      }
    }

    it("compacts a normal lane one behind a lane-zero fetch fault") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, AddX6X5X2)
        dut.io.input(0).bits.fault.valid.poke(true)
        dut.io.input(0).bits.fault.cause.poke(1)
        dut.io.input(0).bits.fault.tval.poke(BigInt("80000000", 16))
        dut.io.renameResponse(0).allocates.poke(false)
        dut.io.renameResponse(1).sourcePhysical1.poke(5)

        dut.io.acceptedCount.expect(2)
        dut.io.robEnqueue.foreach(_.valid.expect(true))
        dut.io.robEnqueue(0).bits.initiallyComplete.expect(true)
        dut.io.robEnqueue(1).bits.initiallyComplete.expect(false)
        dut.io.intEnqueue(0).valid.expect(true)
        dut.io.intEnqueue(0).bits.robTag.expect(11)
        dut.io.intEnqueue(1).valid.expect(false)
        dut.io.readyAllocation(0).valid.expect(false)
        dut.io.readyAllocation(1).valid.expect(true)
        dut.io.faultCandidate(0).valid.expect(true)
        dut.io.faultCandidate(1).valid.expect(false)
      }
    }

    it("suppresses every child transaction while recovery blocks dispatch") {
      simulate(new BackendDispatch) { dut =>
        clearInputs(dut)
        driveInstruction(dut, 0, AddiX5X1One)
        driveInstruction(dut, 1, AddX6X5X2)
        dut.io.blocked.poke(true)

        dut.io.acceptedCount.expect(0)
        dut.io.input.foreach(_.ready.expect(false))
        dut.io.robEnqueue.foreach(_.valid.expect(false))
        dut.io.intEnqueue.foreach(_.valid.expect(false))
        dut.io.bdbAllocate.valid.expect(false)
        dut.io.faultCandidate.foreach(_.valid.expect(false))
      }
    }
  }
}
