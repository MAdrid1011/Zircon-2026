package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.core.ZirconCore

class CoreShellSpec extends AnyFunSpec with ChiselSim {
  private val ResetVector = BigInt("80000000", 16)

  private def clearInputs(dut: ZirconCore): Unit = {
    dut.io.interrupts.meip.poke(false)
    dut.io.interrupts.msip.poke(false)
    dut.io.interrupts.mtip.poke(false)
    dut.io.axi.aw.ready.poke(true)
    dut.io.axi.w.ready.poke(true)
    dut.io.axi.ar.ready.poke(false)
    dut.io.axi.b.valid.poke(false)
    dut.io.axi.b.bits.id.poke(0)
    dut.io.axi.b.bits.resp.poke(0)
    dut.io.axi.r.valid.poke(false)
    dut.io.axi.r.bits.id.poke(0)
    dut.io.axi.r.bits.data.poke(0)
    dut.io.axi.r.bits.resp.poke(0)
    dut.io.axi.r.bits.last.poke(false)
  }

  private def sendInstructionPacket(dut: ZirconCore, words: Seq[BigInt],
      responses: Seq[Int] = Seq.empty): Unit = {
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.axi.r.valid.poke(true)
      dut.io.axi.r.bits.id.poke(0)
      dut.io.axi.r.bits.data.poke(word)
      dut.io.axi.r.bits.resp.poke(responses.lift(index).getOrElse(0))
      dut.io.axi.r.bits.last.poke(index == words.length - 1)
      dut.io.axi.r.ready.expect(true)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false)
    }
  }

  describe("ZirconCore executable M1 integration") {
    it("executes an AXI-fed RV32I dependency chain and emits precise retire events") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)

        dut.io.axi.aw.valid.expect(false)
        dut.io.axi.w.valid.expect(false)
        dut.io.axi.ar.valid.expect(false)
        dut.io.trace.get.foreach(_.valid.expect(false))

        dut.clock.step(128)
        dut.clock.step()
        dut.io.axi.ar.valid.expect(true)
        dut.io.axi.ar.bits.id.expect(0)
        dut.io.axi.ar.bits.addr.expect(ResetVector)
        dut.io.axi.ar.bits.len.expect(3)
        dut.io.axi.ar.ready.poke(true)
        dut.clock.step()
        dut.io.axi.ar.ready.poke(false)

        sendInstructionPacket(dut, Seq(
          BigInt("00500093", 16), // addi x1,x0,5
          BigInt("00308113", 16), // addi x2,x1,3
          BigInt("00100073", 16), // ebreak
          BigInt("00000013", 16)
        ))

        val events = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt, Boolean, Boolean, BigInt)]
        for (_ <- 0 until 48) {
          for (lane <- 0 until 2) {
            val event = dut.io.trace.get(lane)
            if (event.valid.peek().litToBoolean) {
              events += ((
                event.order.peek().litValue,
                event.pc.peek().litValue,
                event.instruction.peek().litValue,
                event.gprWrite.peek().litToBoolean,
                event.trap.peek().litToBoolean,
                event.cause.peek().litValue
              ))
            }
          }
          dut.clock.step()
        }

        assert(events.take(3).map(_._1) == Seq(BigInt(0), BigInt(1), BigInt(2)))
        assert(events(0) == (BigInt(0), ResetVector, BigInt("00500093", 16), true, false, BigInt(0)))
        assert(events(1) == (BigInt(1), ResetVector + 4, BigInt("00308113", 16), true, false, BigInt(0)))
        assert(events(2) == (BigInt(2), ResetVector + 8, BigInt("00100073", 16), false, true, BigInt(3)))
      }
    }

    it("removes the trace port from a trace-disabled configuration") {
      simulate(new ZirconCore(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        assert(dut.io.trace.isEmpty)
      }
    }

    it("turns an AXI instruction RRESP error into a precise fetch-fault trap event") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        dut.clock.step(128)
        dut.clock.step()
        dut.io.axi.ar.valid.expect(true)
        dut.io.axi.ar.ready.poke(true)
        dut.clock.step()
        dut.io.axi.ar.ready.poke(false)
        sendInstructionPacket(dut, Seq(0, 0, 0, 0),
          responses = Seq(2, 0, 0, 0))

        var observed = false
        for (_ <- 0 until 32) {
          for (lane <- 0 until 2) {
            val event = dut.io.trace.get(lane)
            if (event.valid.peek().litToBoolean && event.trap.peek().litToBoolean) {
              event.pc.expect(ResetVector)
              event.instruction.expect(0)
              event.cause.expect(1)
              event.trapValue.expect(ResetVector)
              observed = true
            }
          }
          dut.clock.step()
        }
        assert(observed, "the instruction access fault did not reach retire trace")
      }
    }
  }
}
