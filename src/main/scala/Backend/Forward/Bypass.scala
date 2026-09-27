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

    for (consumer <- p.consumerSources.indices) {
        val deferredSources = Wire(Vec(p.consumerSources(consumer), Bool()))
        for (source <- 0 until p.consumerSources(consumer)) {
            val query = io.consumer(consumer).query(source)
            val producerIds = p.producerSets(consumer)(source)
            val producers = producerIds.map(io.producer(_))
            val nextHit = VecInit(producers.map { producer =>
                producer.nextWb.valid && producer.nextWb.bits === query.prs
            })
            val selected = RegInit(0.U(producers.size.W))
            // Keep the data selector and payload-enable validity on separate registers.
            val selectedValid = RegInit(false.B)
            selected := 0.U
            selectedValid := false.B
            val selectedHit = if (p.captureConsumers.contains(consumer)) {
                nextHit.zip(producers).zip(producerIds).map { case ((hit, producer), producerId) =>
                    if (p.deferredCaptureProducers.contains(producerId)) hit
                    else if (p.guaranteedCaptureProducers.contains(producerId)) false.B
                    else hit && !producer.nextResult.valid
                }
            } else nextHit
            when(io.consumer(consumer).advance) {
                selected := VecInit(selectedHit).asUInt
                selectedValid := VecInit(selectedHit).asUInt.orR
            }
            deferredSources(source) := VecInit(selectedHit).asUInt.orR

            val captureHit = nextHit.zip(producers).zip(producerIds).map { case ((hit, producer), producerId) =>
                hit && producer.nextResult.valid && !p.deferredCaptureProducers.contains(producerId).B
            }
            io.consumer(consumer).capture(source).valid :=
                io.consumer(consumer).advance && VecInit(captureHit).asUInt.orR
            io.consumer(consumer).capture(source).bits :=
                Mux1H(captureHit, producers.map(_.nextResult.bits))
            // Reduce candidates before the one-hot mux so FP zero detection stays off the data cone.
            io.consumer(consumer).captureFpZero(source) :=
                Mux1H(captureHit, producers.map(producer => !producer.nextResult.bits(30, 0).orR))
            io.consumer(consumer).value(source).valid := selectedValid
            io.consumer(consumer).value(source).bits := Mux1H(selected.asBools, producers.map(_.result))
            io.consumer(consumer).valueFpZero(source) :=
                Mux1H(selected.asBools, producers.map(producer => !producer.result(30, 0).orR))
            assert(PopCount(nextHit) <= 1.U, "One RF source cannot match multiple promised WB producers")
            when(selectedValid) {
                assert(PopCount(selected) === 1.U, "One EX source must select exactly one WB producer")
            }
        }
        val deferredAny = RegNext(
            io.consumer(consumer).advance && deferredSources.asUInt.orR,
            false.B,
        )
        io.consumer(consumer).deferred := deferredAny
        assert(deferredAny === VecInit(io.consumer(consumer).value.map(_.valid)).asUInt.orR)
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
