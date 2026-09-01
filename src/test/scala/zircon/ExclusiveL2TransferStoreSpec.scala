package zircon

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import zircon.memory.ExclusiveL2TransferStore

class ExclusiveL2TransferStoreSpec extends AnyFunSpec with ChiselSim {
  private def clear(dut: ExclusiveL2TransferStore): Unit = {
    dut.io.insert.valid.poke(false)
    dut.io.insert.bits.lineAddress.poke(0)
    dut.io.insert.bits.lineData.foreach(_.poke(0))
    dut.io.insert.bits.dirty.poke(false)
    dut.io.instructionInsert.valid.poke(false)
    dut.io.instructionInsert.bits.lineAddress.poke(0)
    dut.io.instructionInsert.bits.lineData.foreach(_.poke(0))
    dut.io.instructionInsert.bits.dirty.poke(false)
    dut.io.lookup.valid.poke(false)
    dut.io.lookup.bits.lineAddress.poke(0)
    dut.io.response.ready.poke(false)
    dut.io.instructionLookup.valid.poke(false)
    dut.io.instructionLookup.bits.poke(0)
    dut.io.instructionResponse.ready.poke(false)
    dut.io.flushLine.valid.poke(false)
    dut.io.flushLine.bits.poke(0)
    dut.io.fenceDrain.poke(false)
    dut.io.invalidate.valid.poke(false)
    dut.io.invalidate.bits.poke(0)
    dut.io.victim.ready.poke(false)
  }

  private def insert(
      dut: ExclusiveL2TransferStore,
      address: BigInt,
      words: Seq[BigInt],
      dirty: Boolean
  ): Unit = {
    dut.io.insert.valid.poke(true)
    dut.io.insert.bits.lineAddress.poke(address)
    dut.io.insert.bits.dirty.poke(dirty)
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.insert.bits.lineData(index).poke(word)
    }
    dut.io.insert.ready.expect(true)
    dut.clock.step()
    dut.io.insert.valid.poke(false)
  }

  private def lookup(dut: ExclusiveL2TransferStore, address: BigInt): Unit = {
    dut.io.lookup.valid.poke(true)
    dut.io.lookup.bits.lineAddress.poke(address)
    dut.io.lookup.ready.expect(true)
    dut.clock.step()
    dut.io.lookup.valid.poke(false)
  }

  private def instructionInsert(
      dut: ExclusiveL2TransferStore,
      address: BigInt,
      words: Seq[BigInt]
  ): Unit = {
    dut.io.instructionInsert.valid.poke(true)
    dut.io.instructionInsert.bits.lineAddress.poke(address)
    dut.io.instructionInsert.bits.dirty.poke(false)
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.instructionInsert.bits.lineData(index).poke(word)
    }
    dut.io.instructionInsert.ready.expect(true)
    dut.clock.step()
    dut.io.instructionInsert.valid.poke(false)
  }

  describe("ExclusiveL2TransferStore") {
    it("dynamically allocates a clean instruction refill and merges a later exact collision") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val instructionLine = BigInt("80000800", 16)
        val instructionWords = Seq.tabulate(8)(word => BigInt("f00d0000", 16) + word)
        instructionInsert(dut, instructionLine, instructionWords)
        dut.io.residentLineCount.expect(1)

        dut.io.instructionLookup.valid.poke(true)
        dut.io.instructionLookup.bits.poke(instructionLine)
        dut.io.instructionLookup.ready.expect(true)
        dut.clock.step()
        dut.io.instructionLookup.valid.poke(false)
        dut.io.instructionResponse.valid.expect(true)
        dut.io.instructionResponse.bits.hit.expect(true)
        instructionWords.zipWithIndex.foreach { case (word, index) =>
          dut.io.instructionResponse.bits.lineData(index).expect(word)
        }
        dut.io.instructionResponse.ready.poke(true)
        dut.clock.step()
        dut.io.instructionResponse.ready.poke(false)

        val residentLine = BigInt("80000c00", 16)
        val residentWords = Seq.tabulate(8)(word => BigInt("d00d0000", 16) + word)
        insert(dut, residentLine, residentWords, dirty = true)
        dut.io.instructionInsert.valid.poke(true)
        dut.io.instructionInsert.bits.lineAddress.poke(residentLine)
        dut.io.instructionInsert.bits.dirty.poke(false)
        dut.io.instructionInsert.bits.lineData.foreach(_.poke(BigInt("aaaaaaaa", 16)))
        dut.io.instructionInsertHit.expect(true)
        residentWords.zipWithIndex.foreach { case (word, index) =>
          dut.io.instructionInsertData(index).expect(word)
        }
        dut.io.instructionInsert.ready.expect(true)
        dut.clock.step()
        dut.io.instructionInsert.valid.poke(false)
        dut.io.residentLineCount.expect(2)
      }
    }

    it("gives a D-side insertion priority over a simultaneous instruction refill") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val dataLine = BigInt("80003000", 16)
        val instructionLine = BigInt("80003400", 16)
        dut.io.insert.valid.poke(true)
        dut.io.insert.bits.lineAddress.poke(dataLine)
        dut.io.insert.bits.dirty.poke(true)
        dut.io.insert.bits.lineData.foreach(_.poke(BigInt("11111111", 16)))
        dut.io.instructionInsert.valid.poke(true)
        dut.io.instructionInsert.bits.lineAddress.poke(instructionLine)
        dut.io.instructionInsert.bits.dirty.poke(false)
        dut.io.instructionInsert.bits.lineData.foreach(_.poke(BigInt("22222222", 16)))
        dut.io.insert.ready.expect(true)
        dut.io.instructionInsert.ready.expect(false)
        dut.clock.step()
        dut.io.insert.valid.poke(false)
        dut.io.instructionInsert.ready.expect(true)
        dut.clock.step()
        dut.io.instructionInsert.valid.poke(false)
        dut.io.residentLineCount.expect(2)
      }
    }

    it("backpressures a clean instruction fill when its dirty victim FIFO is full") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val base = BigInt("80004000", 16)
        val stride = BigInt("400", 16) // 32 L2 sets times one 32-byte line.
        (0 until 4).foreach { index =>
          insert(dut, base + stride * index, Seq.fill(8)(BigInt(index + 1)), dirty = true)
        }
        instructionInsert(dut, base + stride * 4, Seq.fill(8)(BigInt("10", 16)))
        instructionInsert(dut, base + stride * 5, Seq.fill(8)(BigInt("20", 16)))
        dut.io.victimCount.expect(2)

        dut.io.instructionInsert.valid.poke(true)
        dut.io.instructionInsert.bits.lineAddress.poke(base + stride * 6)
        dut.io.instructionInsert.bits.dirty.poke(false)
        dut.io.instructionInsert.bits.lineData.foreach(_.poke(BigInt("30", 16)))
        dut.io.instructionInsert.ready.expect(false)

        dut.io.victim.ready.poke(true)
        dut.clock.step()
        dut.io.victim.ready.poke(false)
        dut.io.instructionInsert.ready.expect(true)
        dut.clock.step()
        dut.io.instructionInsert.valid.poke(false)
      }
    }

    it("serves an instruction probe without transferring the resident D-side line") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val line = BigInt("80001200", 16)
        val words = Seq.tabulate(8)(word => BigInt("ca110000", 16) + word)
        insert(dut, line, words, dirty = true)

        dut.io.instructionLookup.valid.poke(true)
        dut.io.instructionLookup.bits.poke(line)
        dut.io.instructionLookup.ready.expect(true)
        dut.clock.step()
        dut.io.instructionLookup.valid.poke(false)
        dut.io.instructionResponse.valid.expect(true)
        dut.io.instructionResponse.bits.hit.expect(true)
        dut.io.instructionResponse.bits.lineAddress.expect(line)
        words.zipWithIndex.foreach { case (word, index) =>
          dut.io.instructionResponse.bits.lineData(index).expect(word)
        }
        dut.io.residentLineCount.expect(1)
        dut.io.instructionResponse.ready.poke(true)
        dut.clock.step()
        dut.io.instructionResponse.ready.poke(false)

        lookup(dut, line)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.hit.expect(true)
        dut.io.response.bits.transfer.dirty.expect(true)
      }
    }

    it("moves an L2 hit into the sole response transfer buffer") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val line = BigInt("80001000", 16)
        val words = Seq.tabulate(8)(word => BigInt("12340000", 16) + word)
        insert(dut, line, words, dirty = true)
        dut.io.residentLineCount.expect(1)

        lookup(dut, line)
        dut.io.transferBusy.expect(true)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.hit.expect(true)
        dut.io.response.bits.transfer.lineAddress.expect(line)
        dut.io.response.bits.transfer.dirty.expect(true)
        words.zipWithIndex.foreach { case (word, index) =>
          dut.io.response.bits.transfer.lineData(index).expect(word)
        }
        dut.io.residentLineCount.expect(0)
        dut.io.lookup.ready.expect(false)
        dut.io.response.ready.poke(true)
        dut.clock.step()
        dut.io.response.ready.poke(false)
        dut.io.transferBusy.expect(false)

        lookup(dut, line)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.hit.expect(false)
      }
    }

    it("keeps a dirty replacement in FIFO order and backpressures a full victim queue") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val base = BigInt("80001000", 16)
        val stride = BigInt("400", 16) // 32 L2 sets times one 32-byte line.
        val lines = (0 until 7).map(index => base + stride * index)
        val words = (0 until 7).map(index => Seq.fill(8)(BigInt(index + 1)))

        (0 until 4).foreach(index => insert(dut, lines(index), words(index), dirty = true))
        insert(dut, lines(4), words(4), dirty = false)
        dut.io.victim.valid.expect(true)
        dut.io.victim.bits.lineAddress.expect(lines(0))
        dut.io.victim.bits.dirty.expect(true)
        dut.io.victim.bits.lineData(0).expect(1)

        insert(dut, lines(5), words(5), dirty = false)
        dut.io.victimCount.expect(2)
        dut.io.insert.valid.poke(true)
        dut.io.insert.bits.lineAddress.poke(lines(6))
        dut.io.insert.bits.dirty.poke(false)
        dut.io.insert.bits.lineData.foreach(_.poke(7))
        dut.io.insert.ready.expect(false)

        dut.io.victim.ready.poke(true)
        dut.io.victim.bits.lineAddress.expect(lines(0))
        dut.clock.step()
        dut.io.victim.ready.poke(false)
        dut.io.insert.ready.expect(true)
        dut.clock.step()
        dut.io.insert.valid.poke(false)
        dut.io.victim.valid.expect(true)
        dut.io.victim.bits.lineAddress.expect(lines(1))
      }
    }

    it("removes a clean L2 line before a direct external store can expose it stale") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val line = BigInt("80002400", 16)
        insert(dut, line, Seq.fill(8)(BigInt("decafbad", 16)), dirty = false)
        dut.io.invalidate.valid.poke(true)
        dut.io.invalidate.bits.poke(line)
        dut.io.invalidateReady.expect(true)
        dut.clock.step()
        dut.io.invalidate.valid.poke(false)
        lookup(dut, line)
        dut.io.response.valid.expect(true)
        dut.io.response.bits.hit.expect(false)
      }
    }

    it("moves an exact dirty L2 line into the retained victim FIFO") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val line = BigInt("80002800", 16)
        val words = Seq.tabulate(8)(word => BigInt("babe0000", 16) + word)
        insert(dut, line, words, dirty = true)

        dut.io.flushLine.valid.poke(true)
        dut.io.flushLine.bits.poke(line)
        dut.io.flushLine.ready.expect(true)
        dut.clock.step()
        dut.io.flushLine.valid.poke(false)
        dut.io.residentLineCount.expect(0)
        dut.io.victim.valid.expect(true)
        dut.io.victim.bits.lineAddress.expect(line)
        dut.io.victim.bits.dirty.expect(true)
        words.zipWithIndex.foreach { case (word, index) =>
          dut.io.victim.bits.lineData(index).expect(word)
        }
      }
    }

    it("drains dirty residents into the victim FIFO for a cache-global FENCE") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val line = BigInt("80002a00", 16)
        val words = Seq.tabulate(8)(word => BigInt("cafe0000", 16) + word)
        insert(dut, line, words, dirty = true)
        dut.io.fenceDrain.poke(true)
        dut.io.insert.valid.poke(true)
        dut.io.insert.ready.expect(false)
        dut.io.insert.valid.poke(false)
        dut.io.fenceDrained.expect(false)
        dut.clock.step()
        dut.io.residentLineCount.expect(0)
        dut.io.victim.valid.expect(true)
        dut.io.victim.bits.lineAddress.expect(line)
        dut.io.fenceDrained.expect(false)
        dut.io.victim.ready.poke(true)
        dut.clock.step()
        dut.io.victim.ready.poke(false)
        dut.io.fenceDrained.expect(true)
      }
    }

    it("does not accept a targeted flush for a clean L2 line") {
      simulate(new ExclusiveL2TransferStore) { dut =>
        clear(dut)
        val line = BigInt("80002c00", 16)
        insert(dut, line, Seq.fill(8)(BigInt("1badb002", 16)), dirty = false)
        dut.io.flushLine.valid.poke(true)
        dut.io.flushLine.bits.poke(line)
        dut.io.flushLine.ready.expect(false)
        dut.io.victim.valid.expect(false)
      }
    }

    it("preserves the frozen 8 KiB L2 geometry") {
      simulate(new ExclusiveL2TransferStore(ZirconCoreConfig.l2EightKiB)) { dut =>
        clear(dut)
        val line = BigInt("80001800", 16)
        val words = Seq.tabulate(8)(word => BigInt("a5a50000", 16) + word)
        insert(dut, line, words, dirty = false)
        lookup(dut, line)
        dut.io.response.bits.hit.expect(true)
        dut.io.response.bits.transfer.lineData(7).expect(words(7))
      }
    }
  }
}
