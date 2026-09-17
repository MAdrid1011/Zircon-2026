import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.BypassParams

class BypassSpec extends AnyFreeSpec with ChiselSim {
    private def clear(dut: Bypass): Unit = {
        for (producer <- dut.io.producer) {
            producer.nextWb.valid.poke(false)
            producer.nextWb.bits.poke(1)
            producer.result.valid.poke(false)
            producer.result.bits.prd.poke(1)
            producer.result.bits.data.poke(0)
        }
        for (consumer <- dut.p.consumerSources.indices;
             source <- 0 until dut.p.consumerSources(consumer)) {
            dut.io.consumer(consumer).query(source).prs.poke(0)
        }
        for (consumer <- dut.p.consumerSources.indices) {
            dut.io.consumer(consumer).advance.poke(false)
        }
    }

    "routes only the producers required by each execution source" in {
        simulate(new Bypass(BypassParams.backend())) { dut =>
            clear(dut)

            val fp7 = (1 << dut.p.backend.physWidth) | 7
            dut.io.consumer(0).query(0).prs.poke(7)
            dut.io.consumer(1).query(1).prs.poke(fp7 + 1)
            dut.io.consumer(0).advance.poke(true)
            dut.io.consumer(1).advance.poke(true)
            dut.io.producer(0).nextWb.valid.poke(true)
            dut.io.producer(0).nextWb.bits.poke(7)
            dut.io.producer(4).nextWb.valid.poke(true)
            dut.io.producer(4).nextWb.bits.poke(fp7 + 1)
            dut.clock.step()

            dut.io.consumer(0).advance.poke(false)
            dut.io.consumer(1).advance.poke(false)
            dut.io.producer(0).nextWb.valid.poke(false)
            dut.io.producer(4).nextWb.valid.poke(false)
            dut.io.producer(0).result.valid.poke(true)
            dut.io.producer(0).result.bits.prd.poke(7)
            dut.io.producer(0).result.bits.data.poke(BigInt("12345678", 16))
            dut.io.producer(4).result.valid.poke(true)
            dut.io.producer(4).result.bits.prd.poke(fp7 + 1)
            dut.io.producer(4).result.bits.data.poke(BigInt("87654321", 16))
            dut.io.consumer(0).value(0).valid.expect(true)
            dut.io.consumer(0).value(0).bits.expect(BigInt("12345678", 16))
            dut.io.consumer(1).value(1).valid.expect(true)
            dut.io.consumer(1).value(1).bits.expect(BigInt("87654321", 16))
        }
    }
}
