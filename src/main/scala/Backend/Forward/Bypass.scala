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
        val expectedTag = RegEnable(query.prs, io.consumer(consumer).advance)

        selected := 0.U
        when(io.consumer(consumer).advance) {
            selected := nextHit.asUInt
        }

        io.consumer(consumer).value(source).valid := selected.orR
        io.consumer(consumer).value(source).bits := Mux1H(selected.asBools, producers.map(_.result.bits.data))
        assert(PopCount(nextHit) <= 1.U, "One RF source cannot match multiple promised WB producers")
        when(selected.orR) {
            assert(PopCount(selected) === 1.U, "One EX source must select exactly one WB producer")
            for (((producer, producerId), index) <- producers.zip(producerIds).zipWithIndex) {
                when(selected(index)) {
                    if (producerId < 3) {
                        assert(
                            producer.result.valid && producer.result.bits.prd === expectedTag,
                            "A fixed arithmetic WB result must fulfill its scheduled bypass",
                        )
                    } else {
                        assert(
                            !producer.result.valid || producer.result.bits.prd === expectedTag,
                            "A successful speculative Load bypass must keep its scheduled destination",
                        )
                    }
                }
            }
        }
    }

    for (producer <- io.producer) {
        when(producer.result.valid) {
            val fp = producer.result.bits.prd(p.backend.tagWidth - 1)
            val local = producer.result.bits.prd(p.backend.physWidth - 1, 0)
            when(fp) {
                assert(local < p.backend.numFpPhys.U, "FP WB bypass destination is out of range")
            }.otherwise {
                assert(local =/= 0.U && local < p.backend.numIntPhys.U,
                    "Integer WB bypass destination is out of range")
            }
        }
    }

    for (i <- 0 until p.numProducers; j <- 0 until i) {
        assert(
            !(io.producer(i).result.valid && io.producer(j).result.valid &&
                io.producer(i).result.bits.prd === io.producer(j).result.bits.prd),
            "WB bypass producers must have distinct physical destinations"
        )
        assert(
            !(io.producer(i).nextWb.valid && io.producer(j).nextWb.valid &&
                io.producer(i).nextWb.bits === io.producer(j).nextWb.bits),
            "Promised WB producers must have distinct physical destinations"
        )
    }
}
