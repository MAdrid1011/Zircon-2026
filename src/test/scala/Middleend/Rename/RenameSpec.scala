import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{BackendParams, RenameParams}

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
        dut.io.allocate.poke(0)
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
            dut.clock.step()
            dut.io.commit(0).valid.poke(false)

            dut.io.restore.poke(true)
            dut.clock.step()
            dut.io.restore.poke(false)
            dut.io.pinfo(0).prs(0).expect(0)
        }
    }
}
