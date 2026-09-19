import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.FrontendParams

class FrontendPredictionSelectTestTop(p: FrontendParams) extends Module {
    val io = IO(new FrontendPredictionSelectIO(p))
    val select = Module(new FrontendPredictionSelect(p))
    select.io.pcBlock := io.pcBlock
    select.io.range := io.range
    select.io.kinds := io.kinds
    select.io.control := io.control
    select.io.conditional := io.conditional
    select.io.backward := io.backward
    select.io.targets := io.targets
    select.io.directions := io.directions
    io.prediction := select.io.prediction
}

class FrontendSelectorSpec extends AnyFreeSpec with ChiselSim {
    for (width <- Seq(1, 4, 8)) {
        s"select the earliest taken instruction by conditional rank at width $width" in {
            val p = FrontendParams(fetchWidth = width)
            simulate(new FrontendPredictionSelectTestTop(p)) { d =>
                val rng = new scala.util.Random(20260910 + width)
                for (caseIndex <- 0 until 400) {
                    val start = rng.nextInt(width)
                    val pc = 0x80000000L + start * 4
                    val kinds = if (caseIndex % 2 == 0) Vector.fill(width)(1) else Vector.fill(width)(rng.nextInt(8))
                    val directions = rng.nextInt(1 << width)
                    val targets = Vector.fill(width)(0x80000000L + (rng.nextInt(128) - 64) * 2)
                    val backwards = targets.zipWithIndex.map { case (target, i) => target <= 0x80000000L + i * 4 }
                    val backwardMask = backwards.zipWithIndex.filter(_._1).map(x => 1 << x._2).sum
                    val range = ((1 << width) - 1) ^ ((1 << start) - 1)
                    d.io.pcBlock.poke(pc >> p.blockBits); d.io.range.poke(range); d.io.directions.poke(directions)
                    d.io.backward.poke(backwardMask)
                    d.io.control.zip(kinds).foreach { case (port, value) => port.poke(value != 0) }
                    d.io.conditional.zip(kinds).foreach { case (port, value) => port.poke(value == 1) }
                    d.io.kinds.zip(kinds).foreach { case (port, value) => port.poke(value) }
                    d.io.targets.zip(targets).foreach { case (port, value) => port.poke(value) }
                    var rank = 0
                    var mask = 0
                    var taken = 0
                    var next = 0x80000000L + width * 4
                    for (slot <- start until width if taken == 0) {
                        mask |= 1 << slot
                        val kind = kinds(slot)
                        val go = kind != 0 && (kind != 1 || (directions & (1 << rank)) != 0)
                        if (go) {
                            taken = 1 << slot
                            if ((targets(slot) & 3) == 0) next = targets(slot)
                        }
                        if (kind == 1) rank += 1
                    }
                    d.io.prediction.mask.expect(mask)
                    d.io.prediction.taken.expect(taken)
                    d.io.prediction.nextPc.expect(next)
                    d.io.prediction.backward.expect(kinds.zipWithIndex.filter(x => x._1 == 1 && backwards(x._2)).map(
                        x => 1 << x._2
                    ).sum)
                }
            }
        }
    }
}
