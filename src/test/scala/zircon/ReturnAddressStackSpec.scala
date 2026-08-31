package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.frontend.ReturnAddressStack

class ReturnAddressStackSpec extends AnyFunSpec with ChiselSim {
  private def clearInputs(dut: ReturnAddressStack): Unit = {
    dut.io.clear.poke(false)
    dut.io.recover.valid.poke(false)
    dut.io.recover.bits.pointerBefore.poke(0)
    dut.io.recover.bits.countBefore.poke(0)
    dut.io.recover.bits.action.push.poke(false)
    dut.io.recover.bits.action.pop.poke(false)
    dut.io.recover.bits.action.returnAddress.poke(0)
    dut.io.speculate.valid.poke(false)
    dut.io.speculate.bits.push.poke(false)
    dut.io.speculate.bits.pop.poke(false)
    dut.io.speculate.bits.returnAddress.poke(0)
  }

  private def action(dut: ReturnAddressStack, push: Boolean,
      pop: Boolean, address: BigInt = 0): Unit = {
    dut.io.speculate.valid.poke(true)
    dut.io.speculate.bits.push.poke(push)
    dut.io.speculate.bits.pop.poke(pop)
    dut.io.speculate.bits.returnAddress.poke(address)
    dut.io.speculate.ready.expect(true)
    dut.clock.step()
    dut.io.speculate.valid.poke(false)
  }

  describe("ReturnAddressStack") {
    it("tracks nested calls and returns in LIFO order") {
      simulate(new ReturnAddressStack) { dut =>
        clearInputs(dut)
        dut.io.topValid.expect(false)
        action(dut, push = true, pop = false, 0x1004)
        action(dut, push = true, pop = false, 0x2004)
        dut.io.top.expect(0x2004)
        dut.io.count.expect(2)
        action(dut, push = false, pop = true)
        dut.io.top.expect(0x1004)
        dut.io.count.expect(1)
        action(dut, push = false, pop = true)
        dut.io.topValid.expect(false)
        dut.io.count.expect(0)
      }
    }

    it("saturates overflow and leaves pointer stable on underflow") {
      simulate(new ReturnAddressStack) { dut =>
        clearInputs(dut)
        for (index <- 0 until 10) {
          action(dut, push = true, pop = false, 0x1000 + index * 4)
        }
        dut.io.count.expect(8)
        dut.io.top.expect(0x1024)

        for (index <- 9 to 2 by -1) {
          dut.io.top.expect(0x1000 + index * 4)
          action(dut, push = false, pop = true)
        }
        dut.io.count.expect(0)
        val emptyPointer = dut.io.pointer.peek().litValue
        action(dut, push = false, pop = true)
        dut.io.pointer.expect(emptyPointer)
        dut.io.count.expect(0)
      }
    }

    it("performs coroutine pop-then-push as one speculative event") {
      simulate(new ReturnAddressStack) { dut =>
        clearInputs(dut)
        action(dut, push = true, pop = false, 0x1004)
        action(dut, push = true, pop = true, 0x2004)
        dut.io.pointer.expect(1)
        dut.io.count.expect(1)
        dut.io.top.expect(0x2004)

        action(dut, push = false, pop = true)
        action(dut, push = true, pop = true, 0x3004)
        dut.io.count.expect(1)
        dut.io.top.expect(0x3004)
      }
    }

    it("recovers a checkpoint, reapplies the resolved event, and blocks speculation") {
      simulate(new ReturnAddressStack) { dut =>
        clearInputs(dut)
        action(dut, push = true, pop = false, 0x1004)
        action(dut, push = true, pop = false, 0x2004)
        action(dut, push = true, pop = false, 0x3004)

        dut.io.recover.valid.poke(true)
        dut.io.recover.bits.pointerBefore.poke(1)
        dut.io.recover.bits.countBefore.poke(1)
        dut.io.recover.bits.action.push.poke(true)
        dut.io.recover.bits.action.pop.poke(false)
        dut.io.recover.bits.action.returnAddress.poke(0x4004)
        dut.io.speculate.valid.poke(true)
        dut.io.speculate.ready.expect(false)
        dut.clock.step()
        dut.io.recover.valid.poke(false)
        dut.io.speculate.valid.poke(false)
        dut.io.pointer.expect(2)
        dut.io.count.expect(2)
        dut.io.top.expect(0x4004)

        dut.io.clear.poke(true)
        dut.clock.step()
        dut.io.clear.poke(false)
        dut.io.pointer.expect(0)
        dut.io.count.expect(0)
        dut.io.topValid.expect(false)
      }
    }
  }
}
