package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.backend.CompletionWritebackRouter

class CompletionWritebackRouterSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: CompletionWritebackRouter): Unit = {
    dut.io.robHeadTag.poke(0)
    dut.io.squash.valid.poke(false)
    dut.io.squash.bits.poke(0)
    dut.io.flush.poke(false)
    for (port <- 0 until 2) {
      dut.io.robCompletionAccepted(port).poke(false)
      dut.io.robCompletionDiscarded(port).poke(false)
    }
    dut.io.endpoints.zipWithIndex.foreach { case (endpoint, index) =>
      endpoint.valid.poke(false)
      endpoint.bits.robTag.poke(index)
      endpoint.bits.writesInteger.poke(false)
      endpoint.bits.destinationPhysical.poke(0)
      endpoint.bits.data.poke(0)
    }
  }

  private def driveResult(
      dut: CompletionWritebackRouter,
      endpoint: Int,
      tag: Int,
      writesInteger: Boolean,
      physical: Int,
      data: BigInt
  ): Unit = {
    val result = dut.io.endpoints(endpoint)
    result.valid.poke(true)
    result.bits.robTag.poke(tag)
    result.bits.writesInteger.poke(writesInteger)
    result.bits.destinationPhysical.poke(physical)
    result.bits.data.poke(data)
  }

  describe("CompletionWritebackRouter") {
    it("updates ROB, PRF, and wakeup from the same accepted handshakes") {
      simulate(new CompletionWritebackRouter) { dut =>
        clearInputs(dut)
        driveResult(dut, endpoint = 0, tag = 4, writesInteger = true,
          physical = 34, data = BigInt("44444444", 16))
        driveResult(dut, endpoint = 1, tag = 2, writesInteger = true,
          physical = 32, data = BigInt("22222222", 16))

        dut.io.robCompletion(0).valid.expect(true)
        dut.io.robCompletion(0).robTag.expect(2)
        dut.io.robCompletion(1).valid.expect(true)
        dut.io.robCompletion(1).robTag.expect(4)
        dut.io.endpoints(0).ready.expect(false)
        dut.io.endpoints(1).ready.expect(false)
        dut.io.prfWrite.foreach(_.valid.expect(false))
        dut.io.wakeup.foreach(_.valid.expect(false))

        dut.io.robCompletionAccepted.foreach(_.poke(true))
        dut.io.endpoints(0).ready.expect(true)
        dut.io.endpoints(1).ready.expect(true)
        dut.io.prfWrite(0).valid.expect(true)
        dut.io.prfWrite(0).bits.physical.expect(32)
        dut.io.prfWrite(0).bits.data.expect(BigInt("22222222", 16))
        dut.io.prfWrite(1).valid.expect(true)
        dut.io.prfWrite(1).bits.physical.expect(34)
        dut.io.prfWrite(1).bits.data.expect(BigInt("44444444", 16))
        dut.io.wakeup(0).valid.expect(true)
        dut.io.wakeup(0).physical.expect(32)
        dut.io.wakeup(1).valid.expect(true)
        dut.io.wakeup(1).physical.expect(34)
      }
    }

    it("lets completion port one progress independently of port zero") {
      simulate(new CompletionWritebackRouter) { dut =>
        clearInputs(dut)
        driveResult(dut, endpoint = 0, tag = 1, writesInteger = true,
          physical = 32, data = 1)
        driveResult(dut, endpoint = 1, tag = 2, writesInteger = true,
          physical = 33, data = 2)

        dut.io.robCompletionAccepted(1).poke(true)
        dut.io.endpoints(0).ready.expect(false)
        dut.io.endpoints(1).ready.expect(true)
        dut.io.prfWrite(0).valid.expect(false)
        dut.io.prfWrite(1).valid.expect(true)
        dut.io.prfWrite(1).bits.physical.expect(33)
        dut.io.wakeup(1).valid.expect(true)
      }
    }

    it("drains a stale result without PRF or wakeup side effects") {
      simulate(new CompletionWritebackRouter) { dut =>
        clearInputs(dut)
        driveResult(dut, endpoint = 3, tag = 7, writesInteger = true,
          physical = 40, data = BigInt("deadbeef", 16))

        dut.io.robCompletion(0).valid.expect(true)
        dut.io.robCompletion(0).robTag.expect(7)
        dut.io.robCompletionDiscarded(0).poke(true)
        dut.io.endpoints(3).ready.expect(true)
        dut.io.prfWrite(0).valid.expect(false)
        dut.io.wakeup(0).valid.expect(false)
      }
    }

    it("completes a non-writing uop and blocks every transfer on recovery") {
      simulate(new CompletionWritebackRouter) { dut =>
        clearInputs(dut)
        driveResult(dut, endpoint = 0, tag = 3, writesInteger = false,
          physical = 0, data = 0)
        dut.io.robCompletionAccepted(0).poke(true)
        dut.io.endpoints(0).ready.expect(true)
        dut.io.prfWrite(0).valid.expect(false)
        dut.io.wakeup(0).valid.expect(false)

        dut.io.robCompletionAccepted(0).poke(false)
        dut.io.squash.valid.poke(true)
        dut.io.squash.bits.poke(2)
        dut.io.robCompletion.foreach(_.valid.expect(false))
        dut.io.endpoints.foreach(_.ready.expect(false))

        dut.io.squash.valid.poke(false)
        dut.io.flush.poke(true)
        dut.io.robCompletion.foreach(_.valid.expect(false))
        dut.io.endpoints.foreach(_.ready.expect(false))
      }
    }
  }
}
