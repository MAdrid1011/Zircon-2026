package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.{AXIDataReadEngine, L2DemandClient}

class AXIDataReadEngineSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: AXIDataReadEngine): Unit = {
    dut.io.request.valid.poke(false)
    dut.io.request.bits.client.poke(L2DemandClient.Data)
    dut.io.request.bits.clientMshr.poke(0)
    dut.io.request.bits.lineAddress.poke(0)
    dut.io.response.ready.poke(false)
    dut.io.ar.ready.poke(false)
    dut.io.r.valid.poke(false)
    dut.io.r.bits.id.poke(0)
    dut.io.r.bits.data.poke(0)
    dut.io.r.bits.resp.poke(0)
    dut.io.r.bits.last.poke(false)
  }

  private def acceptRequest(
      dut: AXIDataReadEngine,
      mshr: Int,
      address: BigInt,
      client: Int = L2DemandClient.Data,
      physicalId: Int = 0
  ): Unit = {
    dut.io.request.valid.poke(true)
    dut.io.request.bits.client.poke(client)
    dut.io.request.bits.clientMshr.poke(mshr)
    dut.io.request.bits.lineAddress.poke(address)
    dut.io.request.ready.expect(true)
    dut.clock.step()
    dut.io.request.valid.poke(false)
    dut.io.ar.valid.expect(true)
    dut.io.ar.bits.id.expect(if (physicalId == 0) mshr + 1 else physicalId)
    dut.io.ar.bits.addr.expect(address)
    dut.io.ar.bits.len.expect(7)
    dut.io.ar.bits.size.expect(2)
    dut.io.ar.ready.poke(true)
    dut.clock.step()
    dut.io.ar.ready.poke(false)
  }

  private def receiveBeat(
      dut: AXIDataReadEngine,
      id: Int,
      data: BigInt,
      last: Boolean,
      response: Int = 0
  ): Unit = {
    dut.io.r.valid.poke(true)
    dut.io.r.bits.id.poke(id)
    dut.io.r.bits.data.poke(data)
    dut.io.r.bits.resp.poke(response)
    dut.io.r.bits.last.poke(last)
    dut.io.r.ready.expect(true)
    dut.clock.step()
    dut.io.r.valid.poke(false)
  }

  describe("AXIDataReadEngine") {
    it("holds AR through backpressure and assembles interleaved eight-beat owners") {
      simulate(new AXIDataReadEngine) { dut =>
        clear(dut)
        dut.io.request.valid.poke(true)
        dut.io.request.bits.client.poke(L2DemandClient.Data)
        dut.io.request.bits.clientMshr.poke(0)
        dut.io.request.bits.lineAddress.poke(BigInt("80001000", 16))
        dut.io.request.ready.expect(true)
        dut.clock.step()
        dut.io.request.valid.poke(false)
        dut.io.ar.valid.expect(true)
        dut.io.ar.bits.id.expect(1)
        dut.io.ar.bits.addr.expect(BigInt("80001000", 16))
        dut.clock.step(2)
        dut.io.ar.valid.expect(true)
        dut.io.ar.bits.id.expect(1)
        dut.io.ar.bits.addr.expect(BigInt("80001000", 16))
        dut.io.ar.ready.poke(true)
        dut.clock.step()
        dut.io.ar.ready.poke(false)

        acceptRequest(dut, mshr = 1, BigInt("80002000", 16))
        for (beat <- 0 until 7) {
          receiveBeat(dut, id = 1, data = BigInt("10000000", 16) + beat,
            last = false)
          receiveBeat(dut, id = 2, data = BigInt("20000000", 16) + beat,
            last = false)
        }
        receiveBeat(dut, id = 1, data = BigInt("10000007", 16), last = true)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.client.expect(L2DemandClient.Data)
        dut.io.response.bits.clientMshr.expect(0)
        dut.io.response.bits.accessFault.expect(false)
        for (beat <- 0 until 8) {
          dut.io.response.bits.lineData(beat).expect(BigInt("10000000", 16) + beat)
        }
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)

        receiveBeat(dut, id = 2, data = BigInt("20000007", 16), last = true)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.client.expect(L2DemandClient.Data)
        dut.io.response.bits.clientMshr.expect(1)
        for (beat <- 0 until 8) {
          dut.io.response.bits.lineData(beat).expect(BigInt("20000000", 16) + beat)
        }
      }
    }

    it("retains an RRESP failure on the exact line owner") {
      simulate(new AXIDataReadEngine) { dut =>
        clear(dut)
        acceptRequest(dut, mshr = 3, BigInt("80003000", 16), physicalId = 1)
        for (beat <- 0 until 8) {
          receiveBeat(dut, id = 1, data = beat, last = beat == 7,
            response = if (beat == 2) 2 else 0)
        }
        dut.io.response.valid.expect(true)
        dut.io.response.bits.client.expect(L2DemandClient.Data)
        dut.io.response.bits.clientMshr.expect(3)
        dut.io.response.bits.accessFault.expect(true)
      }
    }

    it("keeps four live owners distinct across interleaved eight-beat refills") {
      simulate(new AXIDataReadEngine) { dut =>
        clear(dut)
        for (mshr <- 0 until 4) {
          acceptRequest(dut, mshr, BigInt("80001000", 16) + mshr * 0x20)
        }

        for (beat <- 0 until 7) {
          for (mshr <- 0 until 4) {
            receiveBeat(dut, id = mshr + 1,
              data = BigInt("40000000", 16) + mshr * 0x100 + beat,
              last = false)
          }
        }
        for (mshr <- 0 until 4) {
          receiveBeat(dut, id = mshr + 1,
            data = BigInt("40000007", 16) + mshr * 0x100, last = true)
          dut.io.response.valid.expect(true)
          dut.io.response.bits.client.expect(L2DemandClient.Data)
          dut.io.response.bits.clientMshr.expect(mshr)
          dut.io.response.bits.accessFault.expect(false)
          for (beat <- 0 until 8) {
            dut.io.response.bits.lineData(beat).expect(
              BigInt("40000000", 16) + mshr * 0x100 + beat)
          }
          dut.io.response.ready.poke(true)
          dut.clock.step()
          dut.io.response.ready.poke(false)
        }
      }
    }

    it("accepts the last aligned line in a 4 KiB page without crossing it") {
      simulate(new AXIDataReadEngine) { dut =>
        clear(dut)
        acceptRequest(dut, mshr = 0, BigInt("80000fe0", 16))
      }
    }

    it("returns the original client token instead of the physical AXI slot") {
      simulate(new AXIDataReadEngine) { dut =>
        clear(dut)
        // The first accepted request uses physical ID 1 despite carrying an
        // instruction-side token 3. A later L1I can share this pool without
        // claiming an L1D-local MSHR number.
        acceptRequest(dut, mshr = 3, BigInt("80004000", 16),
          client = L2DemandClient.Instruction, physicalId = 1)
        for (beat <- 0 until 8) {
          receiveBeat(dut, id = 1, data = BigInt("60000000", 16) + beat,
            last = beat == 7)
        }
        dut.io.response.valid.expect(true)
        dut.io.response.bits.client.expect(L2DemandClient.Instruction)
        dut.io.response.bits.clientMshr.expect(3)
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)
      }
    }
  }
}
