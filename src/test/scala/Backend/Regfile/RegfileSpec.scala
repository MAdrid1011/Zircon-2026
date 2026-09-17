import ZirconConfig.RegfileParams
import chisel3.simulator.Exceptions.AssertionFailed
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class RegfileSpec extends AnyFreeSpec with ChiselSim {
    "parameters reject unsupported dimensions" in {
        for (
            p <- Seq(
                () => RegfileParams(numEntries = 1),
                () => RegfileParams(numEntries = 1, hasZeroReg = false),
                () => RegfileParams(dataWidth = 0),
                () => RegfileParams(numReadPorts = 0),
                () => RegfileParams(numWritePorts = 0),
            )
        ) intercept[IllegalArgumentException](p())
    }

    private val configurations = Seq(
        RegfileParams(),
        RegfileParams(numEntries = 7, dataWidth = 16, numReadPorts = 3, numWritePorts = 2),
        RegfileParams(numEntries = 64, dataWidth = 64, numReadPorts = 4, numWritePorts = 3),
        RegfileParams(numEntries = 65, dataWidth = 8, numReadPorts = 1, numWritePorts = 3),
        RegfileParams(numEntries = 2, dataWidth = 1, numReadPorts = 1, numWritePorts = 1),
        RegfileParams(numEntries = 7, dataWidth = 32, numReadPorts = 3, numWritePorts = 2, hasZeroReg = false),
        RegfileParams(numEntries = 64, dataWidth = 64, numReadPorts = 4, numWritePorts = 3, hasZeroReg = false),
    )

    for (p <- configurations) {
        s"$p preserves storage, concurrent writes, WB bypass, p0 and reset" in {
            simulate(new Regfile(p)) { dut =>
                val random = new Random(20260909L + p.numEntries)
                val mask = (BigInt(1) << p.dataWidth) - 1
                val stored = Array.fill(p.numEntries)(BigInt(0))
                case class Write(addr: Int, data: BigInt, enable: Boolean = true)
                val idle = Seq.fill(p.numWritePorts)(Write(0, 0, false))
                var cycles = 0

                def drive(writes: Seq[Write], reads: Seq[Int]): Unit = {
                    require(writes.size == p.numWritePorts && reads.size == p.numReadPorts)
                    dut.io.write.zip(writes).foreach { case (port, w) =>
                        port.addr.poke(w.addr)
                        port.we.poke(w.enable)
                        port.data.poke(w.data)
                    }
                    dut.io.read.zip(reads).foreach { case (port, addr) => port.addr.poke(addr) }
                }
                def check(writes: Seq[Write], reads: Seq[Int]): Unit = {
                    dut.io.read.zip(reads).foreach { case (port, addr) =>
                        val forwarded = writes.find(w => w.enable && w.addr == addr)
                        val expected = if (p.hasZeroReg && addr == 0) BigInt(0)
                        else forwarded.map(_.data).getOrElse(stored(addr))
                        port.data.expect(expected, s"cycle=$cycles address=$addr")
                    }
                }
                def step(writes: Seq[Write], reads: Seq[Int]): Unit = {
                    drive(writes, reads)
                    check(writes, reads) // Forwarding must work before the storage write edge.
                    dut.clock.step()
                    writes.filter(_.enable).foreach(w => stored(w.addr) = w.data)
                    cycles += 1
                    check(writes, reads)
                    // Disable WB to distinguish real storage updates from a held bypass value.
                    drive(idle, reads)
                    check(idle, reads)
                }
                def scan(): Unit = {
                    for (start <- 0 until p.numEntries by p.numReadPorts) {
                        val reads = Seq.tabulate(p.numReadPorts)(i => (start + i) % p.numEntries)
                        drive(idle, reads)
                        check(idle, reads)
                    }
                }
                def reset(withWrite: Boolean = false): Unit = {
                    val addr = if (p.hasZeroReg) p.numEntries - 1 else 0
                    val writes = if (withWrite) idle.updated(0, Write(addr, mask)) else idle
                    drive(writes, Seq.fill(p.numReadPorts)(0))
                    dut.reset.poke(true)
                    dut.clock.step()
                    drive(idle, Seq.fill(p.numReadPorts)(0))
                    dut.reset.poke(false)
                    stored.indices.foreach(i => stored(i) = BigInt(0))
                    scan()
                }

                reset()
                // Exercise every WB/read pair at the last entry and, when writable, p0.
                val targets = if (p.hasZeroReg) Seq(p.numEntries - 1) else Seq(0, p.numEntries - 1)
                for (addr <- targets; writer <- 0 until p.numWritePorts; reader <- 0 until p.numReadPorts) {
                    val value = (BigInt(writer + reader + 1) | (BigInt(1) << (p.dataWidth - 1))) & mask
                    val writes = idle.updated(writer, Write(addr, value))
                    val reads = Seq.fill(p.numReadPorts)(0).updated(reader, addr)
                    step(writes, reads)
                }
                // A disabled write to p0 must preserve its constant or stored value.
                step(Seq.fill(p.numWritePorts)(Write(0, mask, false)), Seq.fill(p.numReadPorts)(0))
                // Disabled ports may alias an active write and carry an unused address encoding.
                val highestEncoding = (1 << p.addrWidth) - 1
                step(
                    Seq.fill(p.numWritePorts)(Write(highestEncoding, mask, false)),
                    Seq.fill(p.numReadPorts)(p.numEntries - 1)
                )
                step(
                    Seq.fill(p.numWritePorts)(Write(1, 0, false)).updated(0, Write(1, mask)),
                    Seq.fill(p.numReadPorts)(1)
                )
                if (!p.hasZeroReg && p.numWritePorts > 1) {
                    step(
                        idle.updated(0, Write(0, mask)).updated(1, Write(p.numEntries - 1, 0)),
                        Seq.tabulate(p.numReadPorts)(i => if (i % 2 == 0) 0 else p.numEntries - 1)
                    )
                }

                for (cycle <- 0 until 256) {
                    val destinations = random.shuffle(((if (p.hasZeroReg) 1 else 0) until p.numEntries).toList)
                    val writes = Seq.tabulate(p.numWritePorts) { i =>
                        if (i < destinations.size) Write(destinations(i), BigInt(p.dataWidth, random), cycle % 4 != 0)
                        else Write(0, BigInt(p.dataWidth, random), false)
                    }
                    val reads = Seq.tabulate(p.numReadPorts) { i =>
                        if (i < writes.size && cycle % 2 == 0) writes(i).addr else random.nextInt(p.numEntries)
                    }
                    step(writes, reads)
                    if (cycle == 127) reset()
                }
                scan()
                reset(withWrite = true)
                info(s"Validated $cycles cycles and all ${p.numReadPorts * p.numWritePorts} WB/read port pairs for $p")
            }
        }
    }

    "held reads preserve the sampled WB operand while storage and other ports keep advancing" in {
        val p = RegfileParams(numEntries = 7, numReadPorts = 2, numWritePorts = 2, holdReads = true)
        simulate(new Regfile(p)) { dut =>
            dut.io.readHold.get.foreach(_.poke(false))
            dut.io.read.foreach(_.addr.poke(3))
            dut.io.write.zipWithIndex.foreach { case (w, i) =>
                w.addr.poke(i + 3)
                w.data.poke(0)
                w.we.poke(false)
            }
            dut.reset.poke(true)
            dut.clock.step()
            dut.reset.poke(false)
            // The snapshot must capture same-cycle WB, not the older stored value.
            dut.io.write(0).we.poke(true)
            dut.io.write(0).data.poke(0x12345678L)
            dut.io.read.foreach(_.data.expect(0x12345678L))
            dut.clock.step()
            dut.io.readHold.get(0).poke(true)
            for (value <- 1 to 12) {
                dut.io.write(0).data.poke(value)
                dut.io.write(1).we.poke(true)
                dut.io.write(1).data.poke(value + 100)
                dut.io.read(0).addr.poke(if (value % 2 == 0) 4 else 3)
                dut.io.read(1).addr.poke(4)
                dut.io.read(0).data.expect(0x12345678L)
                dut.io.read(1).data.expect(value + 100)
                dut.clock.step()
                dut.io.read(0).data.expect(0x12345678L)
            }
            // Release is a combinational read, with no extra cycle before fresh WB is visible.
            dut.io.readHold.get(0).poke(false)
            dut.io.read(0).addr.poke(3)
            dut.io.write(0).data.poke(99)
            dut.io.read(0).data.expect(99)
            dut.io.readHold.get(1).poke(true)
            dut.io.write(1).data.poke(200)
            dut.io.read(1).data.expect(112)
            dut.clock.step()
            dut.io.write.foreach(_.we.poke(false))
            dut.io.read(0).data.expect(99)
            dut.io.readHold.get(1).poke(false)
            dut.io.read(1).data.expect(200)
            dut.io.read(0).addr.poke(0)
            dut.io.read(0).data.expect(0)
            dut.clock.step()
            dut.io.readHold.get(0).poke(true)
            dut.io.read(0).addr.poke(3)
            dut.io.read(0).data.expect(0)
        }
    }

    private val violations = Seq("read address", "write address", "write to p0", "simultaneous writes")
        .map(_ -> true) :+ ("simultaneous writes" -> false)
    for ((violation, hasZeroReg) <- violations) {
        s"PRF rejects $violation violations with hasZeroReg=$hasZeroReg" in {
            val error = intercept[AssertionFailed] {
                simulate(new Regfile(RegfileParams(
                    numEntries = 7,
                    numReadPorts = 1,
                    numWritePorts = 2,
                    hasZeroReg = hasZeroReg
                ))) { dut =>
                    dut.io.read(0).addr.poke(0)
                    dut.io.write.foreach { port =>
                        port.addr.poke(if (hasZeroReg) 1 else 0)
                        port.we.poke(false)
                        port.data.poke(1)
                    }
                    dut.reset.poke(true)
                    dut.clock.step()
                    dut.reset.poke(false)
                    violation match {
                        case "read address" => dut.io.read(0).addr.poke(7)
                        case "write address" =>
                            dut.io.write(0).addr.poke(7)
                            dut.io.write(0).we.poke(true)
                        case "write to p0" =>
                            dut.io.write(0).addr.poke(0)
                            dut.io.write(0).we.poke(true)
                        case "simultaneous writes" => dut.io.write.foreach(_.we.poke(true))
                    }
                    dut.clock.step()
                }
            }
            assert(error.getMessage.contains(s"PRF $violation"))
        }
    }
}
