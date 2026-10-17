import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class PMATestHarness(scratchpadEnabled: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val paddr = Input(UInt(34.W))
        val attribute = Output(UInt(2.W))
        val readable = Output(Bool())
        val writable = Output(Bool())
        val executable = Output(Bool())
        val atomic = Output(Bool())
    })

    io.attribute := PMA.attribute(io.paddr, scratchpadEnabled)
    io.readable := PMA.readable(io.paddr, scratchpadEnabled)
    io.writable := PMA.writable(io.paddr, scratchpadEnabled)
    io.executable := PMA.executable(io.paddr, scratchpadEnabled)
    io.atomic := PMA.atomic(io.paddr, scratchpadEnabled)
}

class PMASpec extends AnyFreeSpec with ChiselSim {
    "the optional scratchpad is readable and writable but never executable or atomic" in {
        simulate(new PMATestHarness(scratchpadEnabled = true)) { dut =>
            for (address <- Seq("b0000000", "b00fffff")) {
                dut.io.paddr.poke(BigInt(address, 16))
                dut.io.attribute.expect(2)
                dut.io.readable.expect(true)
                dut.io.writable.expect(true)
                dut.io.executable.expect(false)
                dut.io.atomic.expect(false)
            }
            for (address <- Seq("afffffff", "b0100000", "1b0000000")) {
                dut.io.paddr.poke(BigInt(address, 16))
                dut.io.attribute.expect(if (address == "afffffff") 2 else 3)
            }
        }
    }
    "PMA separates main memory, the device window, and invalid addresses" in {
        simulate(new PMATestHarness) { dut =>
            def expect(address: BigInt, attribute: Int, executable: Boolean): Unit = {
                dut.io.paddr.poke(address)
                dut.io.attribute.expect(attribute)
                dut.io.readable.expect(attribute != 3)
                dut.io.writable.expect(attribute != 3)
                dut.io.executable.expect(executable)
                dut.io.atomic.expect(executable)
            }

            expect(BigInt("80000000", 16), attribute = 0, executable = true)
            expect(BigInt("9fffffff", 16), attribute = 0, executable = true)
            expect(BigInt("a0000000", 16), attribute = 2, executable = false)
            expect(BigInt("a2000000", 16), attribute = 1, executable = false)
            expect(BigInt("a2ffffff", 16), attribute = 1, executable = false)
            expect(BigInt("affff000", 16), attribute = 2, executable = false)
            expect(BigInt("afffffff", 16), attribute = 2, executable = false)
            expect(BigInt("00000000", 16), attribute = 3, executable = false)
            expect(BigInt("b0000000", 16), attribute = 3, executable = false)
            expect(BigInt("100000000", 16), attribute = 3, executable = false)
        }
    }
}
