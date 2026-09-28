import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.BackendParams
import scala.util.Random

class SharedReadyBoardComparison(p: BackendParams) extends Module {
    val io = IO(new Bundle {
        val query = Input(Vec(2, new ReadyBoardQuery(p, 3)))
        val allocate = Input(Vec(2, Valid(UInt(p.tagWidth.W))))
        val wakeup = Input(Vec(8, new BackendWakeup(p)))
        val memoryMixWakeup = Input(new BackendWakeup(p))
        val speculation = Input(new SpeculationResolution(p))
        val flush = Input(Bool())
    })
    val shared = Module(new ReadyBoard(p, 2, 8, dualMemory = true))
    val compute = Module(new ReadyBoard(p, 2, 8))
    val memory = Module(new ReadyBoard(p, 2, 8))
    for (board <- Seq(shared, compute, memory)) {
        board.io.query := io.query
        board.io.allocate := io.allocate
        board.io.speculation := io.speculation
        board.io.flush := io.flush
    }
    shared.io.wakeup := io.wakeup
    shared.io.memoryWakeup.get := io.memoryMixWakeup
    compute.io.wakeup := io.wakeup
    for (port <- 0 until 8) {
        memory.io.wakeup(port) := (if (port == 2) io.memoryMixWakeup else io.wakeup(port))
    }
    for (lane <- 0 until 2) {
        assert(shared.io.state(lane).asUInt === compute.io.state(lane).asUInt)
        assert(shared.io.memoryState.get(lane).asUInt === memory.io.state(lane).asUInt)
    }
}

class ReadyBoardSpec extends AnyFreeSpec with ChiselSim {
    private val p = BackendParams()
    private def tag(isFp: Boolean, index: Int): Int =
        (if (isFp) 1 << p.physWidth else 0) | index

    private def initialize(dut: ReadyBoard): Unit = {
        for (lane <- 0 until dut.width; source <- 0 until 3) {
            dut.io.query(lane).prs(source).poke(0)
            dut.io.query(lane).valid(source).poke(false)
        }
        dut.io.allocate.foreach { entry => entry.valid.poke(false); entry.bits.poke(0) }
        dut.io.wakeup.foreach { wakeup => wakeup.prd.poke(0); wakeup.specMask.poke(0) }
        dut.io.loadWakeupBeforeD1.foreach(_.foreach(_.poke(0)))
        dut.io.speculation.resolvedMask.poke(0)
        dut.io.speculation.failedMask.poke(0)
        dut.io.flush.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    "regional Load mask copies align with the D1 wakeup tag" in {
        simulate(new ReadyBoard(width = 1, wakeupPorts = 8, replicateLoadMasks = true)) { dut =>
            initialize(dut)
            val physical = tag(isFp = false, 33)
            val floating = tag(isFp = true, 31)
            dut.io.query(0).valid(0).poke(true)
            dut.io.query(0).prs(0).poke(physical)
            dut.io.query(0).valid(1).poke(true)
            dut.io.query(0).prs(1).poke(floating)
            dut.io.loadWakeupBeforeD1.get(0).poke(4)
            dut.io.loadWakeupBeforeD1.get(1).poke(8)
            dut.clock.step()
            dut.io.loadWakeupBeforeD1.get(0).poke(0)
            dut.io.loadWakeupBeforeD1.get(1).poke(0)
            dut.io.wakeup(4).prd.poke(physical)
            dut.io.wakeup(4).specMask.poke(0)
            dut.io.wakeup(5).prd.poke(floating)
            dut.io.wakeup(5).specMask.poke(0)
            dut.clock.step()
            dut.io.wakeup(4).prd.poke(0)
            dut.io.wakeup(5).prd.poke(0)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(4)
            dut.io.state(0).ready(1).expect(true)
            dut.io.state(0).specMask(1).expect(8)
            dut.io.speculation.failedMask.poke(12)
            dut.io.state(0).ready(0).expect(false)
            dut.io.state(0).ready(1).expect(false)
        }
    }

    "allocation, wakeup, speculation resolution and flush preserve readiness" in {
        simulate(new ReadyBoard) { dut =>
            initialize(dut)
            val integer = tag(isFp = false, 33)
            val floatingZero = tag(isFp = true, 0)
            dut.io.query(0).valid(0).poke(true)
            dut.io.query(0).prs(0).poke(integer)
            dut.io.query(0).valid(1).poke(true)
            dut.io.query(0).prs(1).poke(floatingZero)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).ready(1).expect(true)

            dut.io.allocate(0).valid.poke(true)
            dut.io.allocate(0).bits.poke(integer)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.state(0).ready(0).expect(false)

            dut.io.wakeup(0).prd.poke(integer)
            dut.io.wakeup(0).specMask.poke(4)
            dut.io.state(0).ready(0).expect(false)
            dut.io.state(0).specMask(0).expect(0)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(4)

            dut.io.speculation.resolvedMask.poke(4)
            dut.io.speculation.failedMask.poke(4)
            dut.io.state(0).ready(0).expect(false)
            dut.io.state(0).specMask(0).expect(0)
            dut.clock.step()
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.state(0).ready(0).expect(false)

            // Flush has final state priority over a coincident wakeup and failed
            // speculation result, so producers need not mask either event.
            dut.io.wakeup(0).prd.poke(integer)
            dut.io.wakeup(0).specMask.poke(8)
            dut.io.speculation.resolvedMask.poke(8)
            dut.io.speculation.failedMask.poke(8)
            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(0)

            dut.io.flush.poke(true)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(0)

            dut.io.wakeup(0).prd.poke(integer)
            dut.io.wakeup(0).specMask.poke(2)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.wakeup(0).specMask.poke(0)
            dut.io.speculation.resolvedMask.poke(2)
            dut.io.state(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(0)
        }
    }

    "wakeup updates the registered query state at the clock edge" in {
        simulate(new ReadyBoard) { dut =>
            initialize(dut)
            val physical = tag(isFp = true, 39)
            dut.io.query(1).valid(2).poke(true)
            dut.io.query(1).prs(2).poke(physical)
            dut.io.allocate(1).valid.poke(true)
            dut.io.allocate(1).bits.poke(physical)
            dut.clock.step()
            dut.io.allocate(1).valid.poke(false)
            dut.io.state(1).ready(2).expect(false)
            dut.io.wakeup(6).prd.poke(physical)
            dut.io.state(1).ready(2).expect(false)
            dut.clock.step()
            dut.io.state(1).ready(2).expect(true)
        }
    }

    "wakeup overrides allocation and flush overrides both domains" in {
        simulate(new ReadyBoard(p, 2, 8, dualMemory = true)) { dut =>
            dut.io.query.foreach { lane =>
                lane.prs.foreach(_.poke(0))
                lane.valid.foreach(_.poke(false))
            }
            dut.io.allocate.foreach { entry => entry.valid.poke(false); entry.bits.poke(0) }
            dut.io.wakeup.foreach { wakeup => wakeup.prd.poke(0); wakeup.specMask.poke(0) }
            dut.io.memoryWakeup.get.prd.poke(0)
            dut.io.memoryWakeup.get.specMask.poke(0)
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.flush.poke(false)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            val physical = tag(isFp = false, 9)
            dut.io.query(0).prs(0).poke(physical)
            dut.io.query(0).valid(0).poke(true)
            dut.io.allocate(0).valid.poke(true)
            dut.io.allocate(0).bits.poke(physical)
            dut.clock.step()
            dut.io.allocate(0).valid.poke(false)
            dut.io.wakeup(0).prd.poke(physical)
            dut.io.wakeup(0).specMask.poke(4)
            dut.io.memoryWakeup.get.prd.poke(physical)
            dut.io.speculation.failedMask.poke(4)
            dut.clock.step()
            dut.io.wakeup(0).prd.poke(0)
            dut.io.memoryWakeup.get.prd.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.state(0).ready(0).expect(false)
            dut.io.memoryState.get(0).ready(0).expect(false)
            dut.io.state(0).specMask(0).expect(4)

            dut.io.flush.poke(true)
            dut.io.wakeup(0).prd.poke(physical)
            dut.clock.step()
            dut.io.flush.poke(false)
            dut.io.wakeup(0).prd.poke(0)
            dut.io.state(0).ready(0).expect(true)
            dut.io.memoryState.get(0).ready(0).expect(true)
            dut.io.state(0).specMask(0).expect(0)
        }
    }

    "shared compute and memory state matches independent boards cycle by cycle" in {
        simulate(new SharedReadyBoardComparison(p)) { dut =>
            val random = new Random(75)
            val mixTags = (33 until 41).map(index => tag(isFp = false, index))
            val commonTags = (1 until 12).map(index => tag(isFp = false, index))
            val tags = mixTags ++ commonTags
            dut.io.query.foreach { lane =>
                lane.prs.foreach(_.poke(tags.head))
                lane.valid.foreach(_.poke(false))
            }
            dut.io.allocate.foreach { entry => entry.valid.poke(false); entry.bits.poke(0) }
            dut.io.wakeup.foreach { wakeup => wakeup.prd.poke(0); wakeup.specMask.poke(0) }
            dut.io.memoryMixWakeup.prd.poke(0)
            dut.io.memoryMixWakeup.specMask.poke(0)
            dut.io.speculation.resolvedMask.poke(0)
            dut.io.speculation.failedMask.poke(0)
            dut.io.flush.poke(false)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            var pendingMix = 0
            for (cycle <- 0 until 300) {
                for (lane <- 0 until 2; source <- 0 until 3) {
                    dut.io.query(lane).prs(source).poke(tags(random.nextInt(tags.size)))
                    dut.io.query(lane).valid(source).poke(random.nextBoolean())
                }
                val phase = cycle % 53
                dut.io.allocate(0).valid.poke(phase < mixTags.size)
                dut.io.allocate(0).bits.poke(mixTags(phase % mixTags.size))
                dut.io.allocate(1).valid.poke(random.nextBoolean())
                dut.io.allocate(1).bits.poke(commonTags(random.nextInt(commonTags.size)))
                val divideWake = phase == 20
                val computeMix = if (phase >= 8 && phase < 15) mixTags(phase - 8)
                    else if (divideWake) mixTags(7) else 0
                val memoryMix = if (divideWake) computeMix else pendingMix
                dut.io.wakeup(2).prd.poke(computeMix)
                dut.io.memoryMixWakeup.prd.poke(memoryMix)
                dut.io.wakeup(4).prd.poke(
                    if (random.nextBoolean()) commonTags(random.nextInt(commonTags.size)) else 0)
                dut.io.wakeup(4).specMask.poke(random.nextInt(1 << p.specWidth))
                dut.io.speculation.resolvedMask.poke(random.nextInt(1 << p.specWidth))
                dut.io.speculation.failedMask.poke(random.nextInt(1 << p.specWidth))
                val flush = cycle % 53 == 52
                dut.io.flush.poke(flush)
                dut.clock.step()
                pendingMix = if (flush || divideWake) 0 else computeMix
            }
        }
    }
}
