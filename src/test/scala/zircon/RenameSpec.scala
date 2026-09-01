package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.IntegerRename

class RenameSpec extends AnyFunSpec with ChiselSim {
  private def clearRequests(dut: IntegerRename): Unit = {
    dut.io.request.foreach { request =>
      request.valid.poke(false)
      request.rs1.poke(0)
      request.rs2.poke(0)
      request.rd.poke(0)
      request.readsRs1.poke(false)
      request.readsRs2.poke(false)
      request.writesRd.poke(false)
    }
    dut.io.accept.poke(false)
  }

  private def clearCommit(dut: IntegerRename): Unit = {
    dut.io.commit.foreach { commit =>
      commit.valid.poke(false)
      commit.architectural.poke(0)
      commit.oldPhysical.poke(0)
      commit.newPhysical.poke(0)
    }
    dut.io.flushToCommitted.poke(false)
    dut.io.rollback.valid.poke(false)
    dut.io.rollback.bits.count.poke(1)
    for (lane <- 0 until 2) {
      dut.io.rollback.bits.records(lane).robTag.poke(0)
      dut.io.rollback.bits.records(lane).architecturalDestination.poke(0)
      dut.io.rollback.bits.records(lane).oldPhysicalDestination.poke(0)
      dut.io.rollback.bits.records(lane).newPhysicalDestination.poke(0)
      dut.io.rollback.bits.records(lane).allocatesPhysical.poke(false)
    }
  }

  private def rollbackRecord(dut: IntegerRename, lane: Int,
      architectural: Int, oldPhysical: Int, newPhysical: Int): Unit = {
    val record = dut.io.rollback.bits.records(lane)
    record.robTag.poke(0)
    record.architecturalDestination.poke(architectural)
    record.oldPhysicalDestination.poke(oldPhysical)
    record.newPhysicalDestination.poke(newPhysical)
    record.allocatesPhysical.poke(true)
  }

  private def request(
      dut: IntegerRename,
      lane: Int,
      rs1: Int,
      rs2: Int,
      rd: Int,
      writes: Boolean = true
  ): Unit = {
    dut.io.request(lane).valid.poke(true)
    dut.io.request(lane).rs1.poke(rs1)
    dut.io.request(lane).rs2.poke(rs2)
    dut.io.request(lane).rd.poke(rd)
    dut.io.request(lane).readsRs1.poke(true)
    dut.io.request(lane).readsRs2.poke(true)
    dut.io.request(lane).writesRd.poke(writes)
  }

  describe("IntegerRename") {
    it("renames two lanes atomically with same-cycle RAW and WAW bypass") {
      simulate(new IntegerRename(ZirconCoreConfig.default)) { dut =>
        clearRequests(dut)
        clearCommit(dut)
        dut.io.freeCount.expect(24)
        dut.io.speculativeMap(0).expect(0)
        dut.io.speculativeMap(31).expect(31)

        request(dut, 0, rs1 = 1, rs2 = 2, rd = 5)
        request(dut, 1, rs1 = 5, rs2 = 3, rd = 6)
        dut.io.accept.poke(true)
        dut.io.canAllocate.expect(true)
        dut.io.response(0).newDestinationPhysical.expect(32)
        dut.io.response(0).oldDestinationPhysical.expect(5)
        dut.io.response(1).sourcePhysical1.expect(32)
        dut.io.response(1).newDestinationPhysical.expect(33)
        dut.clock.step()
        dut.io.speculativeMap(5).expect(32)
        dut.io.speculativeMap(6).expect(33)
        dut.io.freeCount.expect(22)

        request(dut, 0, rs1 = 5, rs2 = 1, rd = 5)
        request(dut, 1, rs1 = 5, rs2 = 2, rd = 5)
        dut.io.response(0).newDestinationPhysical.expect(34)
        dut.io.response(0).oldDestinationPhysical.expect(32)
        dut.io.response(1).sourcePhysical1.expect(34)
        dut.io.response(1).oldDestinationPhysical.expect(34)
        dut.io.response(1).newDestinationPhysical.expect(35)
        dut.clock.step()
        dut.io.speculativeMap(5).expect(35)
      }
    }

    it("does not allocate for x0 and stalls an entire pair when the free list is empty") {
      simulate(new IntegerRename(ZirconCoreConfig.default)) { dut =>
        clearRequests(dut)
        clearCommit(dut)

        request(dut, 0, rs1 = 0, rs2 = 0, rd = 0)
        dut.io.request(1).valid.poke(false)
        dut.io.accept.poke(true)
        dut.io.response(0).allocates.expect(false)
        dut.clock.step()
        dut.io.freeCount.expect(24)
        dut.io.speculativeMap(0).expect(0)

        (0 until 12).foreach { index =>
          request(dut, 0, rs1 = 1, rs2 = 2, rd = (index % 31) + 1)
          request(dut, 1, rs1 = 3, rs2 = 4, rd = ((index + 1) % 31) + 1)
          dut.io.accept.poke(true)
          dut.io.canAllocate.expect(true)
          dut.clock.step()
        }
        dut.io.freeCount.expect(0)
        request(dut, 0, rs1 = 1, rs2 = 2, rd = 3)
        dut.io.request(1).valid.poke(false)
        dut.io.accept.poke(false)
        dut.io.canAllocate.expect(false)
        dut.io.response(0).valid.expect(false)
      }
    }

    it("updates committed state in order and restores it on a global flush") {
      simulate(new IntegerRename(ZirconCoreConfig.default)) { dut =>
        clearRequests(dut)
        clearCommit(dut)

        request(dut, 0, rs1 = 1, rs2 = 2, rd = 5)
        request(dut, 1, rs1 = 5, rs2 = 3, rd = 5)
        dut.io.accept.poke(true)
        dut.io.response(0).newDestinationPhysical.expect(32)
        dut.io.response(1).newDestinationPhysical.expect(33)
        dut.clock.step()

        clearRequests(dut)
        dut.io.commit(0).valid.poke(true)
        dut.io.commit(0).architectural.poke(5)
        dut.io.commit(0).oldPhysical.poke(5)
        dut.io.commit(0).newPhysical.poke(32)
        dut.io.commit(1).valid.poke(true)
        dut.io.commit(1).architectural.poke(5)
        dut.io.commit(1).oldPhysical.poke(32)
        dut.io.commit(1).newPhysical.poke(33)
        dut.clock.step()
        dut.io.committedMap(5).expect(33)
        val committedFree = dut.io.committedFree.peek().litValue
        assert(committedFree.testBit(5))
        assert(committedFree.testBit(32))
        assert(!committedFree.testBit(33))

        clearCommit(dut)
        request(dut, 0, rs1 = 1, rs2 = 2, rd = 7)
        dut.io.request(1).valid.poke(false)
        dut.io.accept.poke(true)
        dut.io.response(0).newDestinationPhysical.expect(5)
        dut.clock.step()
        dut.io.speculativeMap(7).expect(5)

        clearRequests(dut)
        dut.io.flushToCommitted.poke(true)
        dut.clock.step()
        dut.io.speculativeMap(5).expect(33)
        dut.io.speculativeMap(7).expect(7)
        assert(dut.io.speculativeFree.peek().litValue.testBit(5))
        dut.io.speculativeFree.expect(dut.io.committedFree.peek().litValue)
      }
    }

    it("accepts a lane-1 mapping update after a lane-0 non-writing retirement") {
      simulate(new IntegerRename(ZirconCoreConfig.default)) { dut =>
        clearRequests(dut)
        clearCommit(dut)

        // The surrounding two-wide commit controller retired a store in lane
        // 0 and an integer writer in lane 1. Only the latter changes the RAT.
        dut.io.commit(1).valid.poke(true)
        dut.io.commit(1).architectural.poke(6)
        dut.io.commit(1).oldPhysical.poke(6)
        dut.io.commit(1).newPhysical.poke(32)
        dut.clock.step()
        dut.io.committedMap(6).expect(32)
        assert(dut.io.committedFree.peek().litValue.testBit(6))
        assert(!dut.io.committedFree.peek().litValue.testBit(32))
      }
    }

    it("undoes dual WAW allocations newest-first without a RAT checkpoint") {
      simulate(new IntegerRename(ZirconCoreConfig.default)) { dut =>
        clearRequests(dut)
        clearCommit(dut)

        request(dut, 0, rs1 = 1, rs2 = 2, rd = 5)
        request(dut, 1, rs1 = 3, rs2 = 4, rd = 5)
        dut.io.accept.poke(true)
        dut.io.response(0).newDestinationPhysical.expect(32)
        dut.io.response(1).newDestinationPhysical.expect(33)
        dut.clock.step()
        request(dut, 0, rs1 = 1, rs2 = 2, rd = 5)
        request(dut, 1, rs1 = 3, rs2 = 4, rd = 5)
        dut.io.response(0).newDestinationPhysical.expect(34)
        dut.io.response(1).newDestinationPhysical.expect(35)
        dut.clock.step()
        clearRequests(dut)
        dut.io.speculativeMap(5).expect(35)
        dut.io.freeCount.expect(20)

        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.count.poke(2)
        rollbackRecord(dut, 0, architectural = 5,
          oldPhysical = 34, newPhysical = 35)
        rollbackRecord(dut, 1, architectural = 5,
          oldPhysical = 33, newPhysical = 34)
        dut.io.rollback.ready.expect(true)
        dut.clock.step()
        dut.io.speculativeMap(5).expect(33)
        assert(dut.io.speculativeFree.peek().litValue.testBit(34))
        assert(dut.io.speculativeFree.peek().litValue.testBit(35))
        assert(!dut.io.speculativeFree.peek().litValue.testBit(33))

        rollbackRecord(dut, 0, architectural = 5,
          oldPhysical = 32, newPhysical = 33)
        rollbackRecord(dut, 1, architectural = 5,
          oldPhysical = 5, newPhysical = 32)
        dut.clock.step()
        dut.io.rollback.valid.poke(false)
        dut.io.speculativeMap(5).expect(5)
        dut.io.freeCount.expect(24)
        for (physical <- 32 to 35) {
          assert(dut.io.speculativeFree.peek().litValue.testBit(physical))
        }
      }
    }

    it("backpressures rollback during commit and gives global flush priority") {
      simulate(new IntegerRename(ZirconCoreConfig.default)) { dut =>
        clearRequests(dut)
        clearCommit(dut)
        request(dut, 0, rs1 = 1, rs2 = 2, rd = 7)
        dut.io.request(1).valid.poke(false)
        dut.io.accept.poke(true)
        dut.io.response(0).newDestinationPhysical.expect(32)
        dut.clock.step()
        clearRequests(dut)

        dut.io.rollback.valid.poke(true)
        dut.io.rollback.bits.count.poke(1)
        rollbackRecord(dut, 0, architectural = 7,
          oldPhysical = 7, newPhysical = 32)
        dut.io.commit(0).valid.poke(true)
        dut.io.commit(0).architectural.poke(7)
        dut.io.commit(0).oldPhysical.poke(7)
        dut.io.commit(0).newPhysical.poke(32)
        dut.io.rollback.ready.expect(false)
        dut.clock.step()
        dut.io.speculativeMap(7).expect(32)

        dut.io.commit(0).valid.poke(false)
        dut.io.flushToCommitted.poke(true)
        dut.io.rollback.ready.expect(false)
        dut.clock.step()
        dut.io.flushToCommitted.poke(false)
        dut.io.rollback.valid.poke(false)
        dut.io.speculativeMap(7).expect(32)
        dut.io.speculativeFree.expect(dut.io.committedFree.peek().litValue)
      }
    }
  }
}
