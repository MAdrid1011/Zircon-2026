import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class PerformanceCSRSpec extends AnyFreeSpec with ChiselSim {
    "nonprivileged counters, floating aliases and vector retirement have ordered state" in {
        simulate(new PerformanceCSR) { dut =>
            dut.io.req.valid.poke(false)
            dut.io.commit.poke(false)
            dut.io.retired.poke(0)
            dut.io.fp.valid.poke(false)
            dut.io.vector.get.retire.valid.poke(false)
            dut.io.trap.valid.poke(false)
            dut.io.xret.valid.poke(false)
            dut.io.privilege.poke(0)
            dut.io.time.poke(123)
            dut.io.interrupt.software.poke(true)
            dut.io.interrupt.timer.poke(true)
            dut.io.interrupt.external.poke(true)
            dut.io.interrupt.supervisorExternal.poke(true)
            dut.reset.poke(true)
            dut.clock.step(2)
            dut.reset.poke(false)

            def access(address: Int, data: Option[BigInt] = None): BigInt = {
                dut.io.req.valid.poke(true)
                dut.io.req.bits.addr.poke(address)
                dut.io.req.bits.op.poke(if (data.isDefined) 2 else 3)
                dut.io.req.bits.rw.poke(data.isDefined)
                dut.io.req.bits.rs.poke(data.isEmpty)
                dut.io.req.bits.rc.poke(false)
                dut.io.req.bits.immediate.poke(false)
                dut.io.req.bits.source.poke(if (data.isDefined) 1 else 0)
                dut.io.req.bits.rdZero.poke(false)
                dut.io.req.bits.data.poke(data.getOrElse(BigInt(0)))
                dut.io.rsp.bits.illegal.expect(false)
                val result = dut.io.rsp.bits.data.peek().litValue
                dut.io.commit.poke(true)
                dut.io.retired.poke(1)
                dut.clock.step()
                dut.io.commit.poke(false)
                dut.io.req.valid.poke(false)
                dut.io.retired.poke(0)
                result
            }
            dut.io.vector.get.state.vtype.expect(0x80000000L)
            assert(access(0xc00) == 0)
            dut.clock.step(7)
            assert(access(0xc01) == 8)
            assert(access(0xc02) == 2)
            for (address <- Seq(0xc80, 0xc81, 0xc82)) assert(access(address) == 0)
            access(0x003, Some(BigInt(0x67)))
            assert(access(0x001) == 7)
            assert(access(0x002) == 3)
            access(0x00f, Some(BigInt(7)))
            assert(access(0x009) == 1)
            assert(access(0x00a) == 3)
            access(0x008, Some(BigInt(5)))
            val retire = dut.io.vector.get.retire
            retire.valid.poke(true)
            retire.bits.configuration.poke(true)
            retire.bits.vl.poke(19)
            retire.bits.vtype.poke(0xc1)
            retire.bits.fflags.poke(16)
            retire.bits.saturated.poke(false)
            dut.clock.step()
            retire.valid.poke(false)
            assert(access(0x008) == 0)
            assert(access(0xc20) == 19)
            assert(access(0xc21) == 0xc1)
            assert(access(0xc22) == 32)
            assert(access(0x003) == 0x77)
            for (address <- Seq(0x100, 0x180, 0x300, 0x305, 0xb00)) {
                dut.io.req.bits.addr.poke(address)
                dut.io.req.valid.poke(true)
                dut.io.rsp.bits.illegal.expect(true)
            }
            dut.io.state.mstatus.expect(0)
            dut.io.state.satp.expect(0)
            dut.io.state.mip.expect(0)
        }
    }
}
