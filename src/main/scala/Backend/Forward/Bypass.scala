import chisel3._
import chisel3.util._
import ZirconConfig.BypassParams

class BypassIO(val p: BypassParams) extends Bundle {
    val consumer = MixedVec(p.consumerSources.map(sources => Flipped(new BypassConsumerPort(sources, p.backend))))
    val producer = Input(Vec(p.numProducers, new BypassSource(p.backend)))
}

/** RF-stage tag comparisons select the registered WB result used in EX/EX1 one cycle later. */
class Bypass(val p: BypassParams = BypassParams()) extends Module {
    val io = IO(new BypassIO(p))

    for (consumer <- p.consumerSources.indices; source <- 0 until p.consumerSources(consumer)) {
        val query = io.consumer(consumer).query(source)
        val producerIds = p.producerSets(consumer)(source)
        val producers = producerIds.map(io.producer(_))
        val nextHit = VecInit(producers.map { producer =>
            producer.nextWb.valid && producer.nextWb.bits === query.prs
        })
        val selected = RegInit(0.U(producers.size.W))
        selected := 0.U
        when(io.consumer(consumer).advance) {
            val selectedHit = if (p.captureConsumers.contains(consumer)) {
                nextHit.zip(producers).zip(producerIds).map { case ((hit, producer), producerId) =>
                    if (p.guaranteedCaptureProducers.contains(producerId)) false.B
                    else hit && !producer.nextResult.valid
                }
            } else nextHit
            selected := VecInit(selectedHit).asUInt
        }

        val captureHit = nextHit.zip(producers).map { case (hit, producer) =>
            hit && producer.nextResult.valid
        }
        io.consumer(consumer).capture(source).valid :=
            io.consumer(consumer).advance && VecInit(captureHit).asUInt.orR
        io.consumer(consumer).capture(source).bits :=
            Mux1H(captureHit, producers.map(_.nextResult.bits))
        // Reduce each candidate before the one-hot mux so FP zero detection does
        // not extend the selected 32-bit bypass-data cone at the consumer.
        io.consumer(consumer).captureFpZero(source) :=
            Mux1H(captureHit, producers.map(producer => !producer.nextResult.bits(30, 0).orR))
        io.consumer(consumer).value(source).valid := selected.orR
        io.consumer(consumer).value(source).bits := Mux1H(selected.asBools, producers.map(_.result))
        io.consumer(consumer).valueFpZero(source) :=
            Mux1H(selected.asBools, producers.map(producer => !producer.result(30, 0).orR))
        assert(PopCount(nextHit) <= 1.U, "One RF source cannot match multiple promised WB producers")
        when(selected.orR) {
            assert(PopCount(selected) === 1.U, "One EX source must select exactly one WB producer")
        }
    }
    for (producer <- p.guaranteedCaptureProducers) {
        assert(
            !io.producer(producer).nextWb.valid || io.producer(producer).nextResult.valid,
            "Guaranteed-capture producer must publish its result with its WB tag",
        )
    }
    for (i <- 0 until p.numProducers; j <- 0 until i) {
        assert(
            !(io.producer(i).nextWb.valid && io.producer(j).nextWb.valid &&
                io.producer(i).nextWb.bits === io.producer(j).nextWb.bits),
            "Promised WB producers must have distinct physical destinations"
        )
    }
}
