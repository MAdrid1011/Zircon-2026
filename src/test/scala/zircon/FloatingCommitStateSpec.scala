package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.FloatingCommitState

class FloatingCommitStateSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: FloatingCommitState): Unit = {
    dut.io.enqueue.valid.poke(false)
    dut.io.enqueue.bits.robTag.poke(0)
    dut.io.enqueue.bits.writesFloat.poke(false)
    dut.io.enqueue.bits.fprAddress.poke(0)
    dut.io.enqueue.bits.fprData.poke(0)
    dut.io.enqueue.bits.flags.poke(0)
    dut.io.commitTag.poke(0)
    dut.io.commitEnable.poke(false)
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
    dut.io.readAddress.foreach(_.poke(0))
  }

  private def enqueue(dut: FloatingCommitState, tag: Int, writesFloat: Boolean,
      address: Int, data: BigInt, flags: Int): Unit = {
    dut.io.enqueue.valid.poke(true)
    dut.io.enqueue.bits.robTag.poke(tag)
    dut.io.enqueue.bits.writesFloat.poke(writesFloat)
    dut.io.enqueue.bits.fprAddress.poke(address)
    dut.io.enqueue.bits.fprData.poke(data)
    dut.io.enqueue.bits.flags.poke(flags)
    dut.io.enqueue.ready.expect(true)
    dut.clock.step()
    dut.io.enqueue.valid.poke(false)
  }

  describe("FloatingCommitState") {
    it("commits retained results only at the matching ROB head") {
      simulate(new FloatingCommitState) { dut =>
        clear(dut)
        enqueue(dut, tag = 3, writesFloat = true, address = 7,
          data = BigInt("40400000", 16), flags = 1)
        enqueue(dut, tag = 2, writesFloat = false, address = 0,
          data = 0, flags = 4)

        dut.io.commitTag.poke(2)
        dut.io.robHeadTag.poke(2)
        dut.io.commitEnable.poke(false)
        dut.io.fpCommit.valid.expect(false)
        dut.io.fprWrite.valid.expect(false)
        dut.io.resultCount.expect(2)

        dut.io.commitEnable.poke(true)
        dut.io.fpCommit.valid.expect(true)
        dut.io.fpCommit.bits.flags.expect(4)
        dut.io.fpCommit.bits.dirty.expect(true)
        dut.io.fprWrite.valid.expect(false)
        dut.io.scoreboardComplete.valid.expect(false)
        dut.clock.step()

        dut.io.commitTag.poke(3)
        dut.io.robHeadTag.poke(3)
        dut.io.fpCommit.valid.expect(true)
        dut.io.fpCommit.bits.flags.expect(1)
        dut.io.fprWrite.valid.expect(true)
        dut.io.fprWrite.bits.address.expect(7)
        dut.io.fprWrite.bits.data.expect(BigInt("40400000", 16))
        dut.io.scoreboardComplete.valid.expect(true)
        dut.io.scoreboardComplete.bits.robTag.expect(3)
        dut.io.scoreboardComplete.bits.destination.expect(7)
        dut.clock.step()

        dut.io.commitEnable.poke(false)
        dut.io.readAddress(0).poke(7)
        dut.io.readData(0).expect(BigInt("40400000", 16))
        dut.io.resultCount.expect(0)
      }
    }
  }
}
