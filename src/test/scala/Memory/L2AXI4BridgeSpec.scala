import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

class L2AXI4BridgeDriver(dut: L2AXI4Bridge) extends chisel3.simulator.PeekPokeAPI {
    private val lineMask = (BigInt(1) << dut.p.lineBits) - 1
    var cycle = 0

    def initialize(): Unit = {
        dut.io.memory.req.valid.poke(false)
        dut.io.memory.req.bits.paddr.poke(0)
        dut.io.memory.req.bits.write.poke(false)
        dut.io.memory.req.bits.uncache.poke(false)
        dut.io.memory.req.bits.size.poke(0)
        dut.io.memory.req.bits.data.poke(0)
        dut.io.memory.req.bits.mask.poke(0)
        dut.io.memory.rsp.ready.poke(false)

        dut.io.axi.ar.ready.poke(false)
        dut.io.axi.r.valid.poke(false)
        dut.io.axi.r.bits.id.poke(0)
        dut.io.axi.r.bits.data.poke(0)
        dut.io.axi.r.bits.resp.poke(0)
        dut.io.axi.r.bits.last.poke(false)
        dut.io.axi.aw.ready.poke(false)
        dut.io.axi.w.ready.poke(false)
        dut.io.axi.b.valid.poke(false)
        dut.io.axi.b.bits.id.poke(0)
        dut.io.axi.b.bits.resp.poke(0)

        dut.reset.poke(true)
        step(2)
        dut.reset.poke(false)
    }

    def step(count: Int = 1): Unit = {
        dut.clock.step(count)
        cycle += count
        assert(cycle < 100000, "L2AXI4Bridge test timed out")
    }

    def request(
        address: BigInt,
        write: Boolean,
        uncached: Boolean,
        size: Int = 2,
        data: BigInt = 0,
        mask: BigInt = 0,
    ): Unit = {
        assert(dut.io.memory.req.ready.peek().litToBoolean)
        dut.io.memory.req.bits.paddr.poke(address)
        dut.io.memory.req.bits.write.poke(write)
        dut.io.memory.req.bits.uncache.poke(uncached)
        dut.io.memory.req.bits.size.poke(size)
        dut.io.memory.req.bits.data.poke(data & lineMask)
        dut.io.memory.req.bits.mask.poke(mask)
        dut.io.memory.req.valid.poke(true)
        step()
        dut.io.memory.req.valid.poke(false)
    }

    private def checkAddress(
        address: BigInt,
        length: Int,
        size: Int,
        cached: Boolean,
        read: Boolean,
    ): Unit = {
        val channel = if (read) dut.io.axi.ar else dut.io.axi.aw
        channel.valid.expect(true)
        channel.bits.id.expect(0)
        channel.bits.addr.expect(address)
        channel.bits.len.expect(length)
        channel.bits.size.expect(size)
        channel.bits.burst.expect(1)
        channel.bits.lock.expect(false)
        channel.bits.cache.expect(if (cached) 15 else 0)
        channel.bits.prot.expect(0)
        channel.bits.qos.expect(0)
    }

    def acceptReadAddress(address: BigInt, length: Int, size: Int, cached: Boolean, stalls: Int): Unit = {
        dut.io.axi.ar.ready.poke(false)
        for (_ <- 0 until stalls) {
            checkAddress(address, length, size, cached, read = true)
            step()
        }
        checkAddress(address, length, size, cached, read = true)
        dut.io.axi.ar.ready.poke(true)
        step()
        dut.io.axi.ar.ready.poke(false)
    }

    def acceptWriteAddress(address: BigInt, length: Int, size: Int, cached: Boolean, stalls: Int): Unit = {
        dut.io.axi.aw.ready.poke(false)
        for (_ <- 0 until stalls) {
            checkAddress(address, length, size, cached, read = false)
            step()
        }
        checkAddress(address, length, size, cached, read = false)
        dut.io.axi.aw.ready.poke(true)
        step()
        dut.io.axi.aw.ready.poke(false)
    }

    def sendReadBeat(data: BigInt, last: Boolean, response: Int = 0, delay: Int = 0): Unit = {
        dut.io.axi.r.valid.poke(false)
        step(delay)
        dut.io.axi.r.bits.id.poke(0)
        dut.io.axi.r.bits.data.poke(data)
        dut.io.axi.r.bits.resp.poke(response)
        dut.io.axi.r.bits.last.poke(last)
        dut.io.axi.r.valid.poke(true)
        dut.io.axi.r.ready.expect(true)
        step()
        dut.io.axi.r.valid.poke(false)
    }

    def acceptWriteBeat(data: BigInt, strobe: Int, last: Boolean, stalls: Int): Unit = {
        dut.io.axi.w.ready.poke(false)
        for (_ <- 0 until stalls) {
            dut.io.axi.w.valid.expect(true)
            dut.io.axi.w.bits.data.expect(data)
            dut.io.axi.w.bits.strb.expect(strobe)
            dut.io.axi.w.bits.last.expect(last)
            step()
        }
        dut.io.axi.w.valid.expect(true)
        dut.io.axi.w.bits.data.expect(data)
        dut.io.axi.w.bits.strb.expect(strobe)
        dut.io.axi.w.bits.last.expect(last)
        dut.io.axi.w.ready.poke(true)
        step()
        dut.io.axi.w.ready.poke(false)
    }

    def sendWriteResponse(response: Int = 0, delay: Int = 0): Unit = {
        dut.io.axi.b.valid.poke(false)
        step(delay)
        dut.io.axi.b.bits.id.poke(0)
        dut.io.axi.b.bits.resp.poke(response)
        dut.io.axi.b.valid.poke(true)
        dut.io.axi.b.ready.expect(true)
        step()
        dut.io.axi.b.valid.poke(false)
    }

    def acceptResponse(expectedData: BigInt, error: Boolean, stalls: Int): Unit = {
        dut.io.memory.rsp.ready.poke(false)
        for (_ <- 0 until stalls) {
            dut.io.memory.rsp.valid.expect(true)
            dut.io.memory.rsp.bits.data.expect(expectedData & lineMask)
            dut.io.memory.rsp.bits.error.expect(error)
            dut.io.memory.req.ready.expect(false)
            step()
        }
        dut.io.memory.rsp.valid.expect(true)
        dut.io.memory.rsp.bits.data.expect(expectedData & lineMask)
        dut.io.memory.rsp.bits.error.expect(error)
        dut.io.memory.rsp.ready.poke(true)
        step()
        dut.io.memory.rsp.ready.poke(false)
        dut.io.memory.req.ready.expect(true)
    }
}

class L2AXI4BridgeSpec extends AnyFreeSpec with ChiselSim {
    private def line(words: Seq[BigInt]): BigInt = words.zipWithIndex.foldLeft(BigInt(0)) { case (result, (word, i)) =>
        result | ((word & BigInt("ffffffffffffffff", 16)) << (64 * i))
    }

    "cached reads preserve 34-bit addresses and assemble a complete cache line" in {
        simulate(new L2AXI4Bridge) { dut =>
            val d = new L2AXI4BridgeDriver(dut)
            d.initialize()

            val address = BigInt("312345640", 16)
            val words = (0 until dut.p.lineBytes / 8).map(i => BigInt("8123000000000000", 16) + i * 0x101)
            d.request(address, write = false, uncached = false)
            d.acceptReadAddress(address, length = words.size - 1, size = 3, cached = true, stalls = 3)
            words.zipWithIndex.foreach { case (word, i) =>
                d.sendReadBeat(word, last = i == words.size - 1, delay = i % 3)
            }
            d.acceptResponse(line(words), error = false, stalls = 4)
        }
    }

    "cached writes split a line into stable AXI write beats" in {
        simulate(new L2AXI4Bridge) { dut =>
            val d = new L2AXI4BridgeDriver(dut)
            d.initialize()

            val address = BigInt("220004000", 16)
            val words = (0 until dut.p.lineBytes / 8).map(i => BigInt("dead000000000000", 16) + i * 0x111)
            d.request(
                address,
                write = true,
                uncached = false,
                data = line(words),
                mask = 15
            )
            d.acceptWriteAddress(address, length = words.size - 1, size = 3, cached = true, stalls = 2)
            words.zipWithIndex.foreach { case (word, i) =>
                d.acceptWriteBeat(word, strobe = 255, last = i == words.size - 1, stalls = (i + 1) % 3)
            }
            d.sendWriteResponse(delay = 3)
            d.acceptResponse(0, error = false, stalls = 2)
        }
    }

    "uncached accesses use narrow transfers and address-selected byte lanes" in {
        simulate(new L2AXI4Bridge) { dut =>
            val d = new L2AXI4BridgeDriver(dut)
            d.initialize()

            val byteAddress = BigInt("100002003", 16)
            d.request(byteAddress, write = false, uncached = true, size = 0)
            d.acceptReadAddress(byteAddress, length = 0, size = 0, cached = false, stalls = 1)
            d.sendReadBeat(BigInt("a5000000", 16), last = true, delay = 2)
            d.acceptResponse(BigInt("a5", 16), error = false, stalls = 1)

            val halfAddress = BigInt("200003002", 16)
            d.request(halfAddress, write = false, uncached = true, size = 1)
            d.acceptReadAddress(halfAddress, length = 0, size = 1, cached = false, stalls = 2)
            d.sendReadBeat(BigInt("cdef0000", 16), last = true)
            d.acceptResponse(BigInt("cdef", 16), error = false, stalls = 0)

            val writeAddress = BigInt("300004003", 16)
            d.request(
                writeAddress,
                write = true,
                uncached = true,
                size = 0,
                data = BigInt("5a000000", 16),
                mask = 8
            )
            d.acceptWriteAddress(writeAddress, length = 0, size = 0, cached = false, stalls = 2)
            d.acceptWriteBeat(BigInt("5a000000", 16), strobe = 8, last = true, stalls = 3)
            d.sendWriteResponse(delay = 1)
            d.acceptResponse(0, error = false, stalls = 1)
        }
    }

    "AXI response and burst termination errors reach the L2 response" in {
        simulate(new L2AXI4Bridge) { dut =>
            val d = new L2AXI4BridgeDriver(dut)
            d.initialize()

            val readAddress = BigInt("100006000", 16)
            d.request(readAddress, write = false, uncached = false)
            val lineWords = dut.p.lineBytes / 8
            d.acceptReadAddress(readAddress, length = lineWords - 1, size = 3, cached = true, stalls = 0)
            d.sendReadBeat(BigInt("12345678", 16), last = true)
            d.acceptResponse(BigInt("12345678", 16), error = true, stalls = 2)

            val responseErrorAddress = BigInt("100007000", 16)
            d.request(responseErrorAddress, write = false, uncached = true, size = 2)
            d.acceptReadAddress(responseErrorAddress, length = 0, size = 2, cached = false, stalls = 0)
            d.sendReadBeat(BigInt("89abcdef", 16), last = true, response = 2)
            d.acceptResponse(BigInt("89abcdef", 16), error = true, stalls = 0)

            val missingLastAddress = BigInt("100007040", 16)
            val missingLastWords = (0 until lineWords).map(i => BigInt("7654000000000000", 16) + i)
            d.request(missingLastAddress, write = false, uncached = false)
            d.acceptReadAddress(missingLastAddress, length = lineWords - 1, size = 3, cached = true, stalls = 0)
            missingLastWords.foreach(word => d.sendReadBeat(word, last = false))
            d.acceptResponse(line(missingLastWords), error = true, stalls = 0)

            val writeAddress = BigInt("100008000", 16)
            val words = (0 until lineWords).map(i => BigInt(i + 1))
            d.request(
                writeAddress,
                write = true,
                uncached = false,
                data = line(words),
                mask = 15
            )
            d.acceptWriteAddress(writeAddress, length = lineWords - 1, size = 3, cached = true, stalls = 0)
            words.zipWithIndex.foreach { case (word, i) =>
                d.acceptWriteBeat(word, strobe = 255, last = i == words.size - 1, stalls = 0)
            }
            d.sendWriteResponse(response = 3)
            d.acceptResponse(0, error = true, stalls = 0)
        }
    }

    "randomized channel stalls preserve every cached and uncached transaction" in {
        simulate(new L2AXI4Bridge) { dut =>
            val d = new L2AXI4BridgeDriver(dut)
            val random = new Random(0x2026)
            d.initialize()

            for (transaction <- 0 until 80) {
                val cached = random.nextBoolean()
                val write = random.nextBoolean()
                val size = if (cached) 2 else random.nextInt(3)
                val offset = if (cached || size == 2) {
                    0
                } else if (size == 1) {
                    random.nextInt(2) * 2
                } else {
                    random.nextInt(4)
                }
                val base = (BigInt(random.nextInt(4)) << 32) | BigInt(0x10000 + transaction * 0x40)
                val lineBytes = dut.p.lineBytes
                val address = if (cached) base & ~(BigInt(lineBytes) - 1) else (base & ~BigInt(3)) + offset
                val beats = if (cached) lineBytes / 8 else 1
                val words = (0 until beats).map(_ => BigInt(64, random))
                val requestData = if (write) {
                    if (cached) line(words) else if ((address & 4) == 0) words.head else words.head << 32
                } else {
                    BigInt(0)
                }
                val requestMask = if (!write) {
                    BigInt(0)
                } else if (cached) {
                    BigInt(255)
                } else {
                    val busOffset = offset + (if ((address & 4) == 0) 0 else 4)
                    ((BigInt(1) << (1 << size)) - 1) << busOffset
                }

                d.request(address, write, uncached = !cached, size, requestData, requestMask)
                if (write) {
                    d.acceptWriteAddress(
                        address,
                        beats - 1,
                        size = if (cached) 3 else size,
                        cached,
                        random.nextInt(5)
                    )
                    words.zipWithIndex.foreach { case (word, i) =>
                        d.acceptWriteBeat(
                            word,
                            strobe = if (cached) 255 else requestMask.toInt,
                            last = i == beats - 1,
                            stalls = random.nextInt(4)
                        )
                    }
                    d.sendWriteResponse(delay = random.nextInt(5))
                    d.acceptResponse(0, error = false, stalls = random.nextInt(4))
                } else {
                    d.acceptReadAddress(
                        address,
                        beats - 1,
                        size = if (cached) 3 else size,
                        cached,
                        random.nextInt(5)
                    )
                    words.zipWithIndex.foreach { case (word, i) =>
                        d.sendReadBeat(
                            if (!cached && (address & 4) != 0) word << 32 else word,
                            last = i == beats - 1,
                            delay = random.nextInt(4)
                        )
                    }
                    val expected = if (cached) {
                        line(words)
                    } else {
                        val widthMask = (BigInt(1) << (8 * (1 << size))) - 1
                        (words.head >> (8 * offset)) & widthMask
                    }
                    d.acceptResponse(expected, error = false, stalls = random.nextInt(4))
                }
            }
        }
    }
}
