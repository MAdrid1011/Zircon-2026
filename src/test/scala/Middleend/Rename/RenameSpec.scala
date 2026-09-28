import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{BackendParams, RenameParams}
import scala.util.Random

class CommitDestinationPolicyHarness extends Module {
    private val backend = BackendParams()
    val io = IO(new Bundle {
        val isFp = Input(Bool())
        val prd = Input(UInt(backend.physWidth.W))
        val writesPhysical = Output(Bool())
    })

    val destination = WireDefault(0.U.asTypeOf(new MiddleendCommitDestination(backend)))
    destination.isFp := io.isFp
    destination.prd := io.prd
    io.writesPhysical := destination.writesPhysical
}

class RenameSpec extends AnyFreeSpec with ChiselSim {
    private def initialize(dut: Rename): Unit = {
        dut.io.rinfo.foreach { info =>
            info.src.foreach(_.poke(0))
            info.srcValid.foreach(_.poke(false))
            info.rd.poke(0)
            info.request.poke(false)
        }
        dut.io.prepare.poke(0)
        dut.io.preview.foreach { write =>
            write.valid.poke(false)
            write.bits.rd.poke(0)
            write.bits.prd.poke(0)
        }
        dut.io.writeback.foreach { write =>
            write.valid.poke(false)
            write.bits.rd.poke(0)
            write.bits.prd.poke(0)
        }
        dut.io.commit.foreach { commit =>
            commit.valid.poke(false)
            commit.bits.rd.poke(0)
            commit.bits.prd.poke(0)
            commit.bits.pprd.poke(0)
        }
        dut.io.restore.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
    }

    "Commit distinguishes floating-point physical zero from integer physical zero" in {
        simulate(new CommitDestinationPolicyHarness) { dut =>
            dut.io.isFp.poke(false)
            dut.io.prd.poke(0)
            dut.io.writesPhysical.expect(false)

            dut.io.isFp.poke(true)
            dut.io.writesPhysical.expect(true)

            dut.io.isFp.poke(false)
            dut.io.prd.poke(1)
            dut.io.writesPhysical.expect(true)
        }
    }

    "floating-point physical zero retirement updates the committed map used by recovery" in {
        val params = RenameParams(
            numPhys = 40,
            renameWidth = 1,
            commitWidth = 1,
            numSources = 2,
            hasZeroReg = false,
        )
        simulate(new Rename(params)) { dut =>
            initialize(dut)
            dut.io.rinfo(0).src(0).poke(7)
            dut.io.rinfo(0).srcValid(0).poke(true)
            dut.io.pinfo(0).prs(0).expect(7)

            dut.io.commit(0).valid.poke(true)
            dut.io.commit(0).bits.rd.poke(7)
            dut.io.commit(0).bits.prd.poke(0)
            dut.io.commit(0).bits.pprd.poke(7)
            dut.io.restore.poke(true)
            dut.clock.step()
            dut.io.commit(0).valid.poke(false)
            dut.io.restore.poke(false)
            dut.io.pinfo(0).prs(0).expect(0)
        }
    }

    "a Q-side writeback is forwarded to Rename reads and advances physical allocation" in {
        val params = RenameParams(
            numPhys = 40,
            renameWidth = 1,
            commitWidth = 1,
            numSources = 2,
            hasZeroReg = true,
        )
        simulate(new Rename(params)) { dut =>
            initialize(dut)
            dut.io.rinfo(0).src(0).poke(5)
            dut.io.rinfo(0).srcValid(0).poke(true)
            dut.io.rinfo(0).rd.poke(6)
            dut.io.rinfo(0).request.poke(true)
            dut.io.prepare.poke(1)
            dut.io.preview(0).valid.poke(true)
            dut.io.preview(0).bits.rd.poke(5)
            dut.io.preview(0).bits.prd.poke(32)
            dut.io.writeback(0).valid.poke(true)
            dut.io.writeback(0).bits.rd.poke(5)
            dut.io.writeback(0).bits.prd.poke(32)

            dut.io.pinfo(0).prs(0).expect(32)
            dut.io.pinfo(0).prd.expect(33)
            dut.clock.step()

            dut.io.preview(0).valid.poke(false)
            dut.io.writeback(0).valid.poke(false)
            dut.io.pinfo(0).prs(0).expect(32)
            dut.io.pinfo(0).prd.expect(33)
        }
    }

    "a stalled Q-side preview changes only the combinational Rename view" in {
        val params = RenameParams(
            numPhys = 40,
            renameWidth = 1,
            commitWidth = 1,
            numSources = 2,
            hasZeroReg = true,
        )
        simulate(new Rename(params)) { dut =>
            initialize(dut)
            dut.io.rinfo(0).src(0).poke(5)
            dut.io.rinfo(0).srcValid(0).poke(true)
            dut.io.rinfo(0).rd.poke(6)
            dut.io.rinfo(0).request.poke(true)
            dut.io.prepare.poke(1)
            dut.io.preview(0).valid.poke(true)
            dut.io.preview(0).bits.rd.poke(5)
            dut.io.preview(0).bits.prd.poke(32)
            dut.io.pinfo(0).prs(0).expect(32)
            dut.io.pinfo(0).prd.expect(33)

            dut.clock.step(3)
            dut.io.preview(0).valid.poke(false)
            dut.io.pinfo(0).prs(0).expect(5)
            dut.io.pinfo(0).prd.expect(32)
        }
    }

    "free-list tags and capacity include each possible Q-side allocation count" in {
        simulate(new PRegFreeList(40, 3, 3)) { dut =>
            dut.io.request.foreach(_.poke(false))
            dut.io.previewAllocate.foreach(_.poke(false))
            dut.io.allocate.foreach(_.poke(false))
            dut.io.release.foreach { release =>
                release.valid.poke(false)
                release.bits.poke(0)
            }
            dut.io.restore.poke(false)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            for (accepted <- 0 to 3) {
                dut.io.allocate.zipWithIndex.foreach { case (port, lane) =>
                    port.poke(lane < accepted)
                }
                dut.io.previewAllocate.zipWithIndex.foreach { case (port, lane) =>
                    port.poke(lane < accepted)
                }
                dut.io.request(0).poke(true)
                dut.io.request(2).poke(true)
                dut.io.prd(0).expect(32 + accepted)
                dut.io.prd(1).expect(0)
                dut.io.prd(2).expect(33 + accepted)
                dut.io.available.expect(true)
                dut.io.availablePrefix.expect(7)
            }

            dut.io.request(1).poke(true)
            dut.io.allocate.foreach(_.poke(true))
            dut.io.previewAllocate.foreach(_.poke(true))
            dut.io.available.expect(true)
            dut.io.prd(2).expect(37)
            dut.clock.step()
            dut.io.allocate.foreach(_.poke(false))
            dut.io.previewAllocate.foreach(_.poke(false))
            dut.io.prd(0).expect(35)
            dut.io.prd(1).expect(36)
            dut.io.prd(2).expect(37)
            dut.io.availablePrefix.expect(7)

            dut.io.allocate.foreach(_.poke(true))
            dut.io.previewAllocate.foreach(_.poke(true))
            dut.io.release.zipWithIndex.foreach { case (release, lane) =>
                release.valid.poke(true)
                release.bits.poke(37 + lane)
            }
            dut.io.available.expect(false)
            dut.io.availablePrefix.expect(3)
            dut.clock.step()
            dut.io.allocate.foreach(_.poke(false))
            dut.io.previewAllocate.foreach(_.poke(false))
            dut.io.release.foreach(_.valid.poke(false))
            dut.io.prd(0).expect(38)
            dut.io.prd(1).expect(39)
            dut.io.prd(2).expect(37)
        }
    }

    "free-list preserves sparse order through depletion and recovery" in {
        simulate(new PRegFreeList(40, 3, 3)) { dut =>
            dut.io.request.foreach(_.poke(false))
            dut.io.previewAllocate.foreach(_.poke(false))
            dut.io.allocate.foreach(_.poke(false))
            dut.io.release.foreach { port =>
                port.valid.poke(false)
                port.bits.poke(0)
            }
            dut.io.restore.poke(false)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            val random = new Random(75)
            val ring = Array.tabulate(8)(index => 32 + index)
            var head = 0
            var tail = 0
            var count = 8
            for (cycle <- 0 until 300) {
                val allocate = if (cycle < 4) math.min(3, count) else random.nextInt(math.min(3, count) + 1)
                val release = if (cycle < 4) 0 else random.nextInt(math.min(3, 8 - count + allocate) + 1)
                val releaseLanes = random.shuffle((0 until 3).toList).take(release).sorted
                val preview = random.nextInt(4)
                val requests = Array.fill(3)(random.nextBoolean())
                val restoring = cycle > 0 && cycle % 37 == 0
                dut.io.restore.poke(restoring)
                for (lane <- 0 until 3) {
                    dut.io.allocate(lane).poke(lane < allocate)
                    dut.io.previewAllocate(lane).poke(lane < preview)
                    dut.io.request(lane).poke(requests(lane))
                    dut.io.release(lane).valid.poke(releaseLanes.contains(lane))
                    dut.io.release(lane).bits.poke(32 + (cycle + lane) % 8)
                }
                val requested = requests.count(identity)
                dut.io.available.expect(count >= preview + requested)
                for (lane <- 0 until 3) {
                    val rank = requests.take(lane).count(identity)
                    dut.io.prd(lane).expect(if (requests(lane)) ring((head + preview + rank) % 8) else 0)
                }
                val prefix = (0 until 3).map { lane =>
                    if (count >= preview + requests.take(lane + 1).count(identity)) 1 << lane else 0
                }.sum
                dut.io.availablePrefix.expect(prefix)

                for ((lane, rank) <- releaseLanes.zipWithIndex) {
                    ring((tail + rank) % 8) = 32 + (cycle + lane) % 8
                }
                tail = (tail + release) % 8
                if (restoring) {
                    head = tail
                    count = 8
                } else {
                    head = (head + allocate) % 8
                    count += release - allocate
                }
                dut.clock.step()
            }
        }
    }
}
