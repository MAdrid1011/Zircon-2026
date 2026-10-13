import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class FpEstimateSpec extends AnyFreeSpec with ChiselSim {
    "RVV seven-bit estimates match SoftFloat across tables, classes and rounding with stalls" in {
        val resource = scala.io.Source.fromResource("fp-estimate-softfloat.txt")
        val oracle = try resource.getLines().map(_.split(" ").map(BigInt(_, 16))).toVector finally resource.close()
        simulate(new FpEstimate) { dut =>
            val rng = new Random(20261013)
            dut.io.in.valid.poke(false); dut.io.out.ready.poke(true); dut.io.flush.poke(false)
            dut.io.in.bits.source.poke(0); dut.io.in.bits.rounding.poke(0); dut.io.in.bits.reciprocalSqrt.poke(false)
            dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
            var pending = Vector.empty[Array[BigInt]]
            var index = 0; var completed = 0; var cycles = 0
            while (index < oracle.size || pending.nonEmpty) {
                val ready = rng.nextInt(7) != 0
                val flush = cycles % 733 == 732
                dut.io.out.ready.poke(ready); dut.io.flush.poke(flush)
                dut.io.in.valid.poke(index < oracle.size)
                if (index < oracle.size) {
                    val sample = oracle(index)
                    dut.io.in.bits.reciprocalSqrt.poke(sample(0) != 0)
                    dut.io.in.bits.rounding.poke(sample(1)); dut.io.in.bits.source.poke(sample(2))
                }
                if (flush) pending = Vector.empty
                else {
                    if (dut.io.out.valid.peek().litToBoolean) {
                        assert(pending.nonEmpty)
                        dut.io.out.bits.result.expect(pending.head(3), s"cycle=$cycles input=${pending.head(2).toString(16)}")
                        dut.io.out.bits.flags.expect(pending.head(4))
                        if (ready) { pending = pending.tail; completed += 1 }
                    }
                    if (index < oracle.size && dut.io.in.ready.peek().litToBoolean) {
                        pending :+= oracle(index); index += 1
                    }
                }
                dut.clock.step(); cycles += 1
                assert(cycles < 10000)
            }
            info(s"$completed SoftFloat estimate results over $cycles cycles with output stalls and flush")
        }
    }
}
