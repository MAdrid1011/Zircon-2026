import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.BypassParams

class BypassSpec extends AnyFreeSpec with ChiselSim {
    private def clear(dut: Bypass): Unit = {
        for (producer <- dut.io.producer) {
            producer.nextWb.valid.poke(false)
            producer.nextWb.bits.poke(1)
            producer.nextResult.valid.poke(false)
            producer.nextResult.bits.poke(0)
            producer.result.poke(0)
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
            dut.io.producer(0).nextResult.valid.poke(true)
            dut.io.producer(0).nextResult.bits.poke(BigInt("12345678", 16))
            dut.io.producer(4).nextWb.valid.poke(true)
            dut.io.producer(4).nextWb.bits.poke(fp7 + 1)
            dut.io.producer(4).nextResult.valid.poke(true)
            dut.io.producer(4).nextResult.bits.poke(BigInt("87654321", 16))
            dut.io.consumer(0).capture(0).valid.expect(true)
            dut.io.consumer(0).capture(0).bits.expect(BigInt("12345678", 16))
            dut.io.consumer(0).captureFpZero(0).expect(false)
            dut.io.consumer(1).capture(1).valid.expect(true)
            dut.io.consumer(1).capture(1).bits.expect(BigInt("87654321", 16))
            dut.io.consumer(1).captureFpZero(1).expect(false)
            dut.clock.step()

            dut.io.consumer(0).advance.poke(false)
            dut.io.consumer(1).advance.poke(false)
            dut.io.producer(0).nextWb.valid.poke(false)
            dut.io.producer(4).nextWb.valid.poke(false)
            dut.io.producer(0).nextResult.valid.poke(false)
            dut.io.producer(4).nextResult.valid.poke(false)
            dut.io.producer(0).result.poke(BigInt("12345678", 16))
            dut.io.producer(4).result.poke(BigInt("87654321", 16))
            dut.io.consumer(0).value(0).valid.expect(true)
            dut.io.consumer(0).value(0).bits.expect(BigInt("12345678", 16))
            dut.io.consumer(0).valueFpZero(0).expect(false)
            dut.io.consumer(1).value(1).valid.expect(true)
            dut.io.consumer(1).value(1).bits.expect(BigInt("87654321", 16))
            dut.io.consumer(1).valueFpZero(1).expect(false)
        }
    }

    "keeps only unavailable capture results for deferred EX forwarding" in {
        val params = BypassParams(
            numProducers = 2,
            consumerSources = Seq(1),
            captureConsumers = Set(0),
            guaranteedCaptureProducers = Set(0),
        )
        simulate(new Bypass(params)) { dut =>
            clear(dut)
            dut.io.consumer(0).query(0).prs.poke(7)
            dut.io.consumer(0).advance.poke(true)
            dut.io.producer(0).nextWb.valid.poke(true)
            dut.io.producer(0).nextWb.bits.poke(7)
            dut.io.producer(0).nextResult.valid.poke(true)
            dut.io.producer(0).nextResult.bits.poke(BigInt("80000000", 16))
            dut.io.consumer(0).capture(0).valid.expect(true)
            dut.io.consumer(0).captureFpZero(0).expect(true)
            dut.clock.step()
            dut.io.consumer(0).advance.poke(false)
            dut.io.consumer(0).value(0).valid.expect(false)

            dut.io.consumer(0).advance.poke(true)
            dut.io.producer(0).nextWb.valid.poke(false)
            dut.io.producer(1).nextWb.valid.poke(true)
            dut.io.producer(1).nextWb.bits.poke(7)
            dut.io.producer(0).nextResult.valid.poke(false)
            dut.clock.step()
            dut.io.consumer(0).advance.poke(false)
            dut.io.producer(1).nextWb.valid.poke(false)
            dut.io.producer(1).result.poke(BigInt("80000000", 16))
            dut.io.consumer(0).value(0).valid.expect(true)
            dut.io.consumer(0).value(0).bits.expect(BigInt("80000000", 16))
            dut.io.consumer(0).valueFpZero(0).expect(true)
        }
    }
}
