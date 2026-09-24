import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class PredictorTableRamSpec extends AnyFreeSpec with ChiselSim {
    for ((backend, name) <- Seq(
        DualPortRamBackend.Registers -> "registers",
        DualPortRamBackend.Vivado -> "vivado",
        DualPortRamBackend.BSG -> "bsg",
    )) {
        s"$name preserves synchronous reads and forwards a colliding update read" in {
            simulate(new PredictorTableRam(32, 43, backend)) { d =>
                def idle(): Unit = {
                    d.io.predictEnable.poke(false)
                    d.io.predictAddress.poke(0)
                    d.io.updateReadEnable.poke(false)
                    d.io.updateReadAddress.poke(0)
                    d.io.updateWriteEnable.poke(false)
                    d.io.updateWriteAddress.poke(0)
                    d.io.updateWriteData.poke(0)
                }
                def write(address: Int, data: BigInt): Unit = {
                    idle()
                    d.io.updateWriteEnable.poke(true)
                    d.io.updateWriteAddress.poke(address)
                    d.io.updateWriteData.poke(data)
                    d.clock.step()
                }
                def predict(address: Int, expected: BigInt): Unit = {
                    idle()
                    d.io.predictEnable.poke(true)
                    d.io.predictAddress.poke(address)
                    d.clock.step()
                    d.io.predictData.expect(expected)
                }

                val first = BigInt("523456789ab", 16)
                val second = BigInt("13579abcdef", 16)
                val replacement = BigInt("6fedcba9876", 16)
                idle()
                d.clock.step()
                write(3, first)
                write(20, second)
                predict(3, first)

                idle()
                d.io.updateReadEnable.poke(true)
                d.io.updateReadAddress.poke(20)
                d.clock.step()
                d.io.updateReadData.expect(second)

                idle()
                d.io.predictEnable.poke(true)
                d.io.predictAddress.poke(3)
                d.io.updateReadEnable.poke(true)
                d.io.updateReadAddress.poke(3)
                d.io.updateWriteEnable.poke(true)
                d.io.updateWriteAddress.poke(3)
                d.io.updateWriteData.poke(replacement)
                d.clock.step()
                d.io.updateReadData.expect(replacement)

                idle()
                d.clock.step()
                d.io.predictData.expect(replacement)

                idle()
                d.io.predictEnable.poke(true)
                d.io.predictAddress.poke(20)
                d.io.updateReadEnable.poke(true)
                d.io.updateReadAddress.poke(3)
                d.io.updateWriteEnable.poke(true)
                d.io.updateWriteAddress.poke(3)
                d.io.updateWriteData.poke(first)
                d.clock.step()
                d.io.predictData.expect(second)
            }
        }
    }
}
