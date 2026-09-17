import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class CSRSpec extends AnyFreeSpec with ChiselSim {
    private val word = BigInt("ffffffff", 16)

    private class Driver(val dut: CSR) {
        dut.io.req.valid.poke(false)
        dut.io.req.bits.addr.poke(0)
        dut.io.req.bits.op.poke(0)
        dut.io.req.bits.source.poke(0)
        dut.io.req.bits.data.poke(0)
        dut.io.req.bits.rdZero.poke(false)
        dut.io.commit.poke(false)
        dut.io.privilege.poke(3)
        dut.io.retired.poke(0)
        dut.io.time.poke(0)
        dut.io.interrupt.software.poke(false)
        dut.io.interrupt.timer.poke(false)
        dut.io.interrupt.external.poke(false)
        dut.io.interrupt.supervisorExternal.poke(false)
        dut.io.fp.valid.poke(false)
        dut.io.fp.bits.flags.poke(0)
        dut.io.fp.bits.dirty.poke(false)
        dut.io.trap.valid.poke(false)
        dut.io.trap.bits.supervisor.poke(false)
        dut.io.trap.bits.pc.poke(0)
        dut.io.trap.bits.cause.poke(0)
        dut.io.trap.bits.tval.poke(0)
        dut.io.xret.valid.poke(false)
        dut.io.xret.bits.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)

        def request(
            addr: Int,
            op: Int = 3,
            source: Int = 0,
            data: BigInt = 0,
            rdZero: Boolean = false,
            privilege: Int = 3,
            illegal: Boolean = false
        ): Unit = {
            dut.io.req.valid.poke(true)
            dut.io.req.bits.addr.poke(addr)
            dut.io.req.bits.op.poke(op)
            dut.io.req.bits.source.poke(source)
            dut.io.req.bits.data.poke(data)
            dut.io.req.bits.rdZero.poke(rdZero)
            dut.io.privilege.poke(privilege)
            dut.io.rsp.valid.expect(true)
            dut.io.rsp.bits.illegal.expect(illegal.B, s"addr=0x${addr.toHexString} op=$op privilege=$privilege")
            if (illegal) {
                dut.io.rsp.bits.data.expect(0)
                dut.io.rsp.bits.read.expect(false)
                dut.io.rsp.bits.write.expect(false)
            }
        }
        def consume(
            addr: Int,
            op: Int = 3,
            source: Int = 0,
            data: BigInt = 0,
            rdZero: Boolean = false,
            privilege: Int = 3,
            illegal: Boolean = false
        ): BigInt = {
            request(addr, op, source, data, rdZero, privilege, illegal)
            val result = dut.io.rsp.bits.data.peek().litValue
            dut.io.commit.poke(true)
            dut.io.retired.poke(if (illegal) 0 else 1)
            dut.clock.step()
            dut.io.commit.poke(false)
            dut.io.req.valid.poke(false)
            dut.io.retired.poke(0)
            result
        }
        def write(addr: Int, data: BigInt, privilege: Int = 3): Unit = {
            consume(addr, op = 2, source = 1, data = data, rdZero = true, privilege = privilege)
        }
        def expect(addr: Int, value: BigInt, privilege: Int = 3): Unit = {
            request(addr, privilege = privilege)
            dut.io.rsp.bits.data.expect(value, s"addr=0x${addr.toHexString}")
        }
    }

    "all six operations preserve CSR read/write intent, x0 semantics and commit gating" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            val random = new Random(20260914L)
            var expected = BigInt(0)
            var checked = 0
            for (op <- 2 to 7; source <- Seq(0, 1, 31); operand <- Seq(BigInt(0), word); rdZero <- Seq(false, true)) {
                d.write(0x340, 0x12345678)
                val replace = op == 2 || op == 5
                val set = op == 3 || op == 6
                val value = if (op >= 5) BigInt(source) else if (source == 0) BigInt(0) else operand
                d.request(0x340, op, source, operand, rdZero)
                dut.io.rsp.bits.read.expect(!replace || !rdZero)
                dut.io.rsp.bits.write.expect(replace || source != 0)
                dut.io.rsp.bits.data.expect(if (replace && rdZero) BigInt(0) else BigInt(0x12345678))
                d.consume(0x340, op, source, operand, rdZero)
                expected =
                    if (replace) value else if (set) BigInt(0x12345678) | value else BigInt(0x12345678) & (word ^ value)
                d.expect(0x340, expected)
                checked += 1
            }
            for (i <- 0 until 1200) {
                val op = 2 + i % 6
                val source = if (random.nextInt(4) == 0) 0 else 1 + random.nextInt(31)
                val operand = if (random.nextInt(5) == 0) BigInt(0) else BigInt(32, random)
                val rdZero = random.nextBoolean()
                val value = if (op >= 5) BigInt(source) else if (source == 0) BigInt(0) else operand
                val replace = op == 2 || op == 5
                val set = op == 3 || op == 6
                val writes = replace || source != 0
                d.request(0x340, op, source, operand, rdZero)
                dut.io.rsp.bits.read.expect(!replace || !rdZero)
                dut.io.rsp.bits.write.expect(writes)
                dut.io.rsp.bits.data.expect(if (replace && rdZero) BigInt(0) else expected)
                if (i % 7 == 0) {
                    // A held, uncommitted access must never modify storage.
                    dut.clock.step(2)
                } else {
                    d.consume(0x340, op, source, operand, rdZero)
                    if (writes)
                        expected = if (replace) value else if (set) expected | value else expected & (word ^ value)
                }
                d.expect(0x340, expected)
                checked += 1
            }
            // A nonzero encoded source still requests a write even if its runtime value is zero.
            d.request(0xf14, source = 1, data = 0, illegal = true)
            d.request(0xf14, op = 6, source = 0)
            dut.io.rsp.bits.write.expect(false)
            // CSRRW with x0 writes zero rather than using stale PRF data.
            d.consume(0x340, op = 2, source = 0, data = word)
            d.expect(0x340, 0)
            for (op <- Seq(0, 1, 8, 31)) d.consume(0x340, op = op, illegal = true)
            d.expect(0x340, 0)
            info(s"Checked $checked directed/random CSR operations and additional zero-source cases")
        }
    }

    "the address map and permissions reject holes, privilege violations and read-only writes" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            d.write(0x300, 0x2000)
            val present = Set(
                0x001, 0x002, 0x003, 0x100, 0x104, 0x105, 0x106, 0x140, 0x141, 0x142,
                0x143, 0x144, 0x180, 0x300, 0x301, 0x302, 0x303, 0x304, 0x305, 0x306, 0x310,
                0x320, 0x340, 0x341, 0x342, 0x343, 0x344, 0xb00, 0xb02, 0xb80, 0xb82,
                0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82, 0xf11, 0xf12, 0xf13, 0xf14, 0xf15
            )
            for (addr <- 0 until 4096) d.request(addr, illegal = !present(addr))
            d.expect(0xf11, 0)
            d.expect(0xf12, 0)
            d.expect(0xf13, 0)
            d.expect(0xf14, 0)
            d.expect(0xf15, 0)
            d.expect(0x301, BigInt("40141121", 16))
            d.write(0x301, 0)
            d.expect(0x301, BigInt("40141121", 16))
            for (addr <- Seq(0xf11, 0xc00, 0xc01, 0xc02)) {
                d.consume(addr, op = 2, source = 0, illegal = true)
                d.consume(addr, source = 1, data = 0, illegal = true)
                d.consume(addr, op = 7, source = 0)
            }
            for (priv <- 0 until 3) d.consume(0x340, op = 2, source = 1, data = word, privilege = priv, illegal = true)
            d.expect(0x340, 0)
            d.request(0x140, privilege = 1)
            d.request(0x140, privilege = 0, illegal = true)
            d.request(0x001, privilege = 2, illegal = true)
            d.write(0x300, 1 << 20)
            d.consume(0x180, privilege = 1, illegal = true)
            d.consume(0x180, op = 2, source = 1, data = word, privilege = 1, illegal = true)
            d.expect(0x180, 0)
            d.request(0x001, illegal = true)
        }
    }

    "status aliases, WARL fields and Sv32 state preserve unrelated machine bits" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            val machineMask = BigInt("007e79aa", 16)
            val supervisorMask = BigInt("000c6122", 16)
            d.write(0x300, word)
            d.expect(0x300, machineMask | (BigInt(1) << 31))
            d.expect(0x100, supervisorMask | (BigInt(1) << 31))
            d.write(0x100, 0, privilege = 1)
            d.expect(0x300, machineMask & (word ^ supervisorMask))
            d.write(0x300, 2 << 11)
            d.expect(0x300, 0)
            d.write(0x310, word)
            d.expect(0x310, 0)
            d.write(0x305, 0x12345679)
            d.expect(0x305, 0x12345679)
            d.write(0x305, 0x1234567b)
            d.expect(0x305, 0x12345678)
            d.write(0x105, 0x87654322L)
            d.expect(0x105, 0x87654320L)
            d.write(0x341, word)
            d.expect(0x341, 0xfffffffcL)
            d.write(0x141, 0x1234567b)
            d.expect(0x141, 0x12345678)
            d.write(0x342, word)
            d.expect(0x342, 0x8000001fL)
            d.write(0x302, word)
            d.expect(0x302, 0xb3ff)
            d.write(0x303, word)
            d.expect(0x303, 0x222)
            d.write(0x180, 0xffd23456L, privilege = 1)
            d.expect(0x180, 0xffd23456L, privilege = 1)
            dut.io.state.satp.expect(0xffd23456L)
        }
    }

    "interrupt views preserve delegation and never latch external SEIP during read-modify-write" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            d.write(0x304, word)
            d.expect(0x304, 0xaaa)
            d.expect(0x104, 0)
            d.write(0x303, 0x222)
            d.expect(0x104, 0x222, privilege = 1)
            d.write(0x104, 0, privilege = 1)
            d.expect(0x304, 0x888)
            dut.io.interrupt.software.poke(true)
            dut.io.interrupt.timer.poke(true)
            dut.io.interrupt.external.poke(true)
            dut.io.interrupt.supervisorExternal.poke(true)
            d.expect(0x344, 0xa88)
            assert(d.consume(0x344, source = 1, data = 0) == 0xa88)
            dut.io.interrupt.supervisorExternal.poke(false)
            d.expect(0x344, 0x888)
            d.consume(0x344, source = 1, data = 0x222)
            d.expect(0x344, 0xaaa)
            d.expect(0x144, 0x222, privilege = 1)
            d.write(0x144, 0, privilege = 1)
            d.expect(0x344, 0xaa8)
            d.write(0x303, 0)
            d.write(0x144, word, privilege = 1)
            d.expect(0x344, 0xaa8)
            d.expect(0x144, 0, privilege = 1)
            d.write(0x344, 0)
            d.expect(0x344, 0x888)
            dut.io.interrupt.software.poke(false)
            dut.io.interrupt.timer.poke(false)
            dut.io.interrupt.external.poke(false)
            d.expect(0x344, 0)
        }
    }

    "64-bit counters handle rollover, privilege enables, inhibit and explicit-write priority" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            d.write(0x320, 5)
            d.write(0xb80, 0x12345678)
            d.write(0xb00, 0xfffffffeL)
            d.write(0xb82, 7)
            d.write(0xb02, 0xffffffffL)
            d.expect(0xc00, 0xfffffffeL)
            d.expect(0xc80, 0x12345678)
            dut.clock.step(3)
            d.expect(0xc00, 0xfffffffeL)
            d.write(0x320, 0)
            dut.clock.step(2)
            d.expect(0xc00, 0)
            d.expect(0xc80, 0x12345679)
            dut.io.retired.poke(3)
            dut.clock.step()
            dut.io.retired.poke(0)
            d.expect(0xc02, 2)
            d.expect(0xc82, 8)
            // Writing minstret replaces the automatic increment from this CSR's own retirement.
            d.write(0xb02, 41)
            d.expect(0xc02, 41)
            assert(d.consume(0xc02) == 41)
            d.expect(0xc02, 42)
            d.write(0x320, 5)
            dut.io.time.poke(BigInt("1234567887654321", 16))
            d.expect(0xc01, 0x87654321L)
            d.expect(0xc81, 0x12345678)
            for (addr <- Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)) {
                d.request(addr, privilege = 1, illegal = true)
                d.request(addr, privilege = 0, illegal = true)
            }
            d.write(0x306, 7)
            for (addr <- Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)) {
                d.request(addr, privilege = 1)
                d.request(addr, privilege = 0, illegal = true)
            }
            d.write(0x106, 2)
            d.expect(0xc01, 0x87654321L, privilege = 0)
            d.expect(0xc81, 0x12345678, privilege = 0)
            d.request(0xc00, privilege = 0, illegal = true)
            d.request(0xc02, privilege = 0, illegal = true)
            d.write(0x306, 0)
            d.request(0xc01, privilege = 0, illegal = true)
        }
    }

    "floating aliases and retired flags preserve precise state and FS dirty tracking" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            d.consume(0x003, op = 2, source = 1, data = 0xff, illegal = true)
            dut.io.state.fflags.expect(0)
            d.write(0x300, 0x2000)
            d.write(0x003, 0xb5)
            d.expect(0x001, 0x15, privilege = 0)
            d.expect(0x002, 5, privilege = 0)
            d.expect(0x003, 0xb5, privilege = 0)
            d.expect(0x300, 0x80006000L)
            d.write(0x002, 3, privilege = 0)
            d.expect(0x003, 0x75)
            d.write(0x001, 0, privilege = 0)
            d.write(0x300, 0x4000)
            var flags = 0
            val random = new Random(9401)
            for (_ <- 0 until 100) {
                val retiredFlags = random.nextInt(32)
                dut.io.fp.valid.poke(true)
                dut.io.fp.bits.flags.poke(retiredFlags)
                dut.clock.step()
                dut.io.fp.valid.poke(false)
                flags |= retiredFlags
                d.expect(0x001, flags)
                d.expect(0x003, 0x60 | flags)
            }
            d.write(0x001, 0)
            d.write(0x300, 0x4000)
            dut.io.fp.valid.poke(true)
            dut.io.fp.bits.flags.poke(0)
            dut.io.fp.bits.dirty.poke(true)
            dut.clock.step()
            dut.io.fp.valid.poke(false)
            d.expect(0x300, 0x80006000L)
            d.expect(0x001, 0)
            d.write(0x300, 0)
            for (addr <- 1 to 3) d.request(addr, illegal = true)
        }
    }

    "accepted trap and return events update CSR state without selecting or redirecting execution" in {
        simulate(new CSR) { dut =>
            val d = new Driver(dut)
            d.write(0x300, 0x20008)
            dut.io.privilege.poke(0)
            dut.io.trap.valid.poke(true)
            dut.io.trap.bits.pc.poke(0x1234567b)
            dut.io.trap.bits.cause.poke(8)
            dut.io.trap.bits.tval.poke(0xdeadbeefL)
            dut.clock.step()
            dut.io.trap.valid.poke(false)
            d.expect(0x341, 0x12345678)
            d.expect(0x342, 8)
            d.expect(0x343, 0xdeadbeefL)
            d.expect(0x300, 0x20080)
            dut.io.xret.valid.poke(true)
            dut.clock.step()
            dut.io.xret.valid.poke(false)
            d.expect(0x300, 0x88)
            d.write(0x300, 0x21880)
            dut.io.xret.valid.poke(true)
            dut.clock.step()
            dut.io.xret.valid.poke(false)
            d.expect(0x300, 0x20088)
            d.write(0x300, 0x20002)
            dut.io.privilege.poke(1)
            dut.io.trap.valid.poke(true)
            dut.io.trap.bits.supervisor.poke(true)
            dut.io.trap.bits.pc.poke(0x80000103L)
            dut.io.trap.bits.cause.poke(0x80000009L)
            dut.io.trap.bits.tval.poke(0x1234)
            dut.clock.step()
            dut.io.trap.valid.poke(false)
            d.expect(0x141, 0x80000100L)
            d.expect(0x142, 0x80000009L)
            d.expect(0x143, 0x1234)
            d.expect(0x300, 0x20120)
            dut.io.xret.valid.poke(true)
            dut.io.xret.bits.poke(true)
            dut.io.privilege.poke(1)
            dut.clock.step()
            dut.io.xret.valid.poke(false)
            d.expect(0x300, 0x22)
        }
    }

}
