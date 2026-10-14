import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class VectorConfigurationHarness extends Module {
    val io = IO(new Bundle {
        val request = Input(new VectorConfigurationRequest)
        val vl = Output(UInt(32.W))
        val vtype = Output(UInt(32.W))
    })
    val configuration = Module(new VectorConfiguration)
    configuration.io.request := io.request
    io.vl := configuration.io.vl
    io.vtype := configuration.io.vtype
}

class VectorGroupsHarness extends Module {
    val groups = Module(new VectorRegisterGroups)
    val io = IO(chiselTypeOf(groups.io))
    groups.io <> io
}

class VectorGroupingHarness extends Module {
    val io = IO(new Bundle {
        val instruction = Input(UInt(32.W))
        val legal = Output(Bool())
    })
    val decoder = Module(new VectorDecoder)
    decoder.io.instruction := io.instruction
    val control = decoder.io.control
    val shape = new VectorGrouping(io.instruction)
    io.legal := control.legal
    val serial = control.execution === VectorExecution.Serial.U
    when(control.legal) {
        assert(shape.memory === (control.execution === VectorExecution.Load.U ||
            control.execution === VectorExecution.Store.U))
        assert(shape.writesVector === control.writesVector)
        assert(shape.writesMask === control.writesMask)
        assert(shape.widening === (serial && (control.operation === VectorSerialOperation.Widen.U ||
            control.operation === VectorSerialOperation.WidenReduction.U)))
        assert(shape.narrowing === (serial && control.operation === VectorSerialOperation.Narrow.U))
        assert(shape.extending === (serial && control.operation === VectorSerialOperation.Extend.U))
        assert(shape.conversion === (serial && control.operation === VectorSerialOperation.Convert.U))
        assert(shape.reduction === (serial && (control.operation === VectorSerialOperation.IntegerReduction.U ||
            control.operation === VectorSerialOperation.FloatReduction.U ||
            control.operation === VectorSerialOperation.WidenReduction.U)))
        assert(shape.insert === (serial && control.operation === VectorSerialOperation.Insert.U))
        assert(shape.maskLogic === (serial && control.operation === VectorSerialOperation.MaskLogic.U))
        assert(shape.maskPrefix === (serial && control.operation === VectorSerialOperation.MaskPrefix.U))
        assert(shape.iota === (serial && control.operation === VectorSerialOperation.Iota.U))
    }
}

class VectorCapacityHarness extends Module {
    val io = IO(new Bundle {
        val instruction = Input(UInt(32.W))
        val vtype = Input(UInt(32.W))
        val available = Input(UInt(8.W))
        val blocked = Output(Bool())
        val reference = Output(Bool())
        val count = Output(UInt(4.W))
        val referenceCount = Output(UInt(4.W))
    })
    val counts = new VectorGroupCounts(io.instruction, io.vtype)
    assert(chisel3.util.PopCount(counts.allocationClasses) === 1.U)
    val banks = (0 until 8).map { bank =>
        (bank.U(3.W) - io.instruction(9, 7))(2, 0) -> !io.available(bank)
    }
    io.blocked := counts.allocationBlocked(io.available)
    io.count := counts.allocationCount
    io.referenceCount := counts.encodedAllocationCount
    io.reference := banks.map { case (distance, blocked) =>
        distance < counts.encodedAllocationCount && blocked
    }.reduce(_ || _)
}

class VectorStateSpec extends AnyFreeSpec with ChiselSim {
    "direct allocation capacity agrees with encoded group counts across banks and instruction forms" in {
        simulate(new VectorCapacityHarness) { dut =>
            val random = new Random(20261014)
            for (cycle <- 0 until 24000) {
                val opcode = Seq(0x57, 0x07, 0x27)(cycle % 3)
                val inst = (BigInt(random.nextInt()) & 0xffffffffL & ~BigInt(0x7f)) | opcode
                dut.io.instruction.poke(inst)
                dut.io.vtype.poke((random.nextInt(3) << 3) | random.nextInt(8))
                dut.io.available.poke(cycle & 255)
                dut.clock.step()
                dut.io.blocked.expect(dut.io.reference.peek().litToBoolean)
                dut.io.count.expect(dut.io.referenceCount.peek().litValue)
            }
        }
    }

    "parallel grouping agrees with execution decode for every arithmetic encoding and memory form" in {
        simulate(new VectorGroupingHarness) { dut =>
            var legal = 0
            def check(inst: BigInt): Unit = {
                dut.io.instruction.poke(inst)
                dut.clock.step()
                if (dut.io.legal.peek().litToBoolean) legal += 1
            }
            for (funct <- 0 until 64; form <- 0 until 8; argument <- 0 until 32;
                 vm <- 0 until 2; vs2 <- Seq(0, 8)) {
                check((BigInt(funct) << 26) | (BigInt(vm) << 25) | (vs2 << 20) |
                    (argument << 15) | (form << 12) | (16 << 7) | 0x57)
            }
            for (opcode <- Seq(0x07, 0x27); fields <- 0 until 8; mew <- 0 until 2;
                 mop <- 0 until 4; argument <- 0 until 32; form <- 0 until 8; vm <- 0 until 2) {
                check((BigInt(fields) << 29) | (BigInt(mew) << 28) | (BigInt(mop) << 26) |
                    (BigInt(vm) << 25) | (argument << 20) | (form << 12) | (16 << 7) | opcode)
            }
            assert(legal > 10000)
            println(s"Parallel vector grouping checked $legal legal encodings")
        }
    }
    "parallel group counts cover segmented memory and arithmetic width changes" in {
        simulate(new VectorGroupsHarness) { dut =>
            def count(exponent: Int): Int = if (exponent <= 0) 1 else 1 << exponent
            VectorTestUtils.clear(dut.io.control)
            dut.io.control.readsVs2.poke(true); dut.io.control.readsVs1.poke(true)
            dut.io.control.writesVector.poke(true)
            dut.io.vl.poke(8); dut.io.vstart.poke(0)
            for (sew <- 0 to 2; eew <- 0 to 2; encoded <- Seq(0, 1, 2, 3, 5, 6, 7);
                 indexed <- Seq(false, true); fields <- 1 to 8) {
                val lmul = if (encoded < 4) encoded else encoded - 8
                val emul = lmul + eew - sew
                val group = count(if (indexed) lmul else emul)
                if (emul >= -3 && emul <= 3 && fields * group <= 8) {
                    dut.io.control.execution.poke(VectorExecution.Load)
                    dut.io.vtype.poke(0xc0 | (sew << 3) | encoded)
                    dut.io.instruction.poke((BigInt(fields - 1) << 29) | (BigInt(1) << 25) |
                        (if (indexed) (BigInt(1) << 26) | (8 << 20) else BigInt(0)) | (16 << 7) |
                        (Seq(0, 5, 6)(eew) << 12) | 0x07)
                    dut.io.groups.destinationCount.expect(fields * group)
                    dut.io.groups.sourceCount(0).expect(fields * group)
                    dut.io.groups.sourceCount(1).expect(if (indexed) count(emul) else count(lmul))
                }
            }
            dut.io.control.execution.poke(VectorExecution.Serial)
            for (sew <- 0 to 2; encoded <- Seq(0, 1, 2, 3, 5, 6, 7);
                 (operation, funct, argument, form, destinationDelta, sourceDelta) <- Seq(
                    (VectorSerialOperation.Widen, 0x30, 0, 2, 1, 0),
                    (VectorSerialOperation.Widen, 0x34, 0, 2, 1, 1),
                    (VectorSerialOperation.Narrow, 0x2c, 0, 0, 0, 1),
                    (VectorSerialOperation.Extend, 0x12, 6, 2, 0, -1),
                    (VectorSerialOperation.Extend, 0x12, 4, 2, 0, -2),
                    (VectorSerialOperation.Convert, 0x12, 10, 1, 1, 0),
                    (VectorSerialOperation.Convert, 0x12, 16, 1, 0, 1))) {
                val lmul = if (encoded < 4) encoded else encoded - 8
                if (sew + destinationDelta <= 2 && sew + sourceDelta >= 0 && sew + sourceDelta <= 2 &&
                    lmul + destinationDelta <= 3 && lmul + sourceDelta >= -3 && lmul + sourceDelta <= 3) {
                    dut.io.vtype.poke(0xc0 | (sew << 3) | encoded)
                    dut.io.control.operation.poke(operation)
                    dut.io.instruction.poke((BigInt(funct) << 26) | (BigInt(1) << 25) |
                        (8 << 20) | (argument << 15) | (form << 12) | (16 << 7) | 0x57)
                    dut.io.groups.destinationCount.expect(count(lmul + destinationDelta))
                    dut.io.groups.sourceCount(0).expect(count(lmul + sourceDelta))
                }
            }
        }
    }
    "memory EMUL arithmetic retains the full signed intermediate at every EEW/LMUL" in {
        simulate(new VectorGroupsHarness) { dut =>
            VectorTestUtils.clear(dut.io.control)
            dut.io.control.execution.poke(VectorExecution.Load)
            dut.io.control.writesVector.poke(true)
            dut.io.vl.poke(0); dut.io.vstart.poke(0)
            for (sew <- 0 to 2; eew <- 0 to 2; lmul <- Seq(0, 1, 2, 3, 5, 6, 7)) {
                val signed = if (lmul < 4) lmul else lmul - 8
                val exponent = signed + eew - sew
                dut.io.vtype.poke((sew << 3) | lmul)
                val form = Seq(0, 5, 6)(eew)
                dut.io.instruction.poke((BigInt(1) << 25) | (8 << 7) | (form << 12) | 0x07)
                val legal = exponent >= -3 && exponent <= 3
                dut.io.groups.legal.expect(legal)
                if (legal) dut.io.groups.destinationCount.expect(if (exponent > 0) 1 << exponent else 1)
            }
            for (count <- Seq(1, 2, 4, 8); vtype <- Seq(0x10L, 0xd3L, 0x80000000L)) {
                dut.io.vtype.poke(vtype)
                dut.io.instruction.poke((BigInt(count - 1) << 29) | (BigInt(1) << 25) |
                    (8 << 20) | (8 << 7) | (6 << 12) | 0x07)
                dut.io.groups.legal.expect(true)
                dut.io.groups.destinationCount.expect(count)
            }
        }
    }
    "VSET configuration covers SEW, all LMUL encodings, AVL and zero-register rules" in {
        simulate(new VectorConfigurationHarness) { dut =>
            val r = dut.io.request
            r.rs1Zero.poke(false); r.rdZero.poke(false); r.immediateAvl.poke(false); r.oldVl.poke(7)
            for (sew <- 0 until 8; lmul <- 0 until 8; policy <- 0 until 4; avl <- Seq(0, 1, 7, 8, 31, 32, 255, 256, 1000)) {
                val vtype = (policy << 6) | (sew << 3) | lmul
                r.vtype.poke(vtype); r.avl.poke(avl)
                val signed = if (lmul < 4) lmul else lmul - 8
                val legal = sew <= 2 && lmul != 4 && (signed >= 0 || sew - signed <= 2)
                val maximum = if (legal) 1 << (5 - sew + signed) else 0
                dut.io.vl.expect(math.min(avl, maximum))
                dut.io.vtype.expect(if (legal) vtype else 0x80000000L)
            }
            r.vtype.poke(0x10); r.avl.poke(1); r.rs1Zero.poke(true)
            dut.io.vl.expect(8)
            r.rdZero.poke(true); dut.io.vl.expect(7)
            r.immediateAvl.poke(true); dut.io.vl.expect(1)
            r.vtype.poke(0x100); dut.io.vtype.expect(0x80000000L); dut.io.vl.expect(0)
        }
    }

    for (registers <- Seq(56, 64)) s"$registers-entry lane PRF preserves three reads, two writes and bytes" in {
        simulate(new VectorRegisterFile(ZirconConfig.VectorParams(physicalRegisters = registers))) { dut =>
            val rng = new Random(20261013)
            val reference = Array.fill(registers, 8)(BigInt(0))
            for (r <- dut.io.read) { r.address.poke(0); r.oneHot.poke(1) }
            for (w <- dut.io.write) { w.valid.poke(false); w.address.poke(0); w.data.foreach(_.poke(0)); w.bytes.foreach(_.poke(0)) }
            for (address <- 0 until registers) {
                val w = dut.io.write(0)
                w.valid.poke(true); w.address.poke(address); w.data.foreach(_.poke(0)); w.bytes.foreach(_.poke(15))
                dut.clock.step()
            }
            for (_ <- 0 until 500) {
                val addresses = rng.shuffle((0 until registers).toList).take(2)
                for (port <- 0 until 2) {
                    val w = dut.io.write(port); val addr = addresses(port)
                    w.valid.poke(true); w.address.poke(addr)
                    for (lane <- 0 until 8) {
                        val data = BigInt(32, rng); val mask = rng.nextInt(16)
                        w.data(lane).poke(data); w.bytes(lane).poke(mask)
                        for (byte <- 0 until 4 if (mask & (1 << byte)) != 0) {
                            val byteMask = BigInt(255) << (8 * byte)
                            reference(addr)(lane) = (reference(addr)(lane) & ~byteMask) | (data & byteMask)
                        }
                    }
                }
                dut.clock.step()
                for (r <- dut.io.read) {
                    val addr = if (rng.nextBoolean()) addresses.head else rng.nextInt(registers)
                    r.address.poke(addr)
                    r.oneHot.poke(BigInt(1) << addr)
                    for (lane <- 0 until 8) r.data(lane).expect(reference(addr)(lane))
                }
            }
        }
    }

    "candidate source addresses share RAT ports across selection, preview and restore" in {
        simulate(new VectorRename(sourceCandidates = 3)) { dut =>
            val mapping = Array.tabulate(32)(identity)
            val random = new Random(20261014)
            def destination(x: VectorDestination, base: Int, physical: Seq[Int], previous: Seq[Int]): Unit = {
                x.base.poke(base); x.count.poke(physical.size)
                if (x eq dut.io.preview.bits) dut.io.previewBanks.poke(
                    (0 until physical.size).foldLeft(BigInt(0))((mask, i) => mask.setBit((base + i) & 7)))
                for (i <- 0 until 8) {
                    x.physical(i).poke(physical.lift(i).getOrElse(0))
                    x.previous(i).poke(previous.lift(i).getOrElse(0))
                }
            }
            dut.io.sourceBase.foreach(_.poke(0)); dut.io.sourceCount.foreach(_.poke(8))
            dut.io.sourceCandidates.get.foreach(_.foreach(_.poke(0)))
            dut.io.sourceSelection.get.poke(0)
            dut.io.destinationBase.poke(7); dut.io.destinationCount.poke(8)
            dut.io.preview.valid.poke(false); dut.io.accept.poke(false); dut.io.restore.poke(false)
            destination(dut.io.preview.bits, 0, Seq.empty, Seq.empty)
            for (r <- dut.io.retire) { r.valid.poke(false); destination(r.bits, 0, Seq.empty, Seq.empty) }
            dut.clock.step()
            def checkSources(): Unit = {
                for (iteration <- 0 until 64) {
                    val candidates = (0 until 3).map(lane => (0 until 3).map(source =>
                        (iteration + 7 * lane + 11 * source) & 31))
                    for (lane <- 0 until 3; source <- 0 until 3)
                        dut.io.sourceCandidates.get(lane)(source).poke(candidates(lane)(source))
                    for (lane <- 0 until 3) {
                        dut.io.sourceSelection.get.poke(1 << lane)
                        for (source <- 0 until 3) {
                            dut.io.sourceBase(source).poke(candidates(lane)(source))
                            dut.io.sourceCount(source).poke(random.nextInt(9))
                            for (element <- 0 until 8)
                                dut.io.source(source)(element).expect(mapping((candidates(lane)(source) + element) & 31))
                        }
                        dut.io.mask.expect(mapping(0))
                    }
                    // No selected vector leaves source metadata unobserved.
                    dut.io.sourceSelection.get.poke(0)
                    for (source <- dut.io.source; tag <- source) tag.expect(0)
                    dut.io.mask.expect(mapping(0))
                }
            }
            checkSources()
            val committedTags = (0 until 8).map(i => dut.io.destination.physical(i).peek().litValue.toInt)
            val committedPrevious = (0 until 8).map(i => mapping(7 + i))
            destination(dut.io.preview.bits, 7, committedTags, committedPrevious)
            dut.io.preview.valid.poke(true)
            for (i <- 0 until 8) mapping(7 + i) = committedTags(i)
            checkSources() // Held Q-side writes are visible before acceptance.
            dut.io.accept.poke(true); dut.clock.step()
            dut.io.preview.valid.poke(false); dut.io.accept.poke(false)
            checkSources()
            destination(dut.io.retire(0).bits, 7, committedTags, committedPrevious)
            dut.io.retire(0).valid.poke(true); dut.clock.step(); dut.io.retire(0).valid.poke(false)
            val committed = mapping.clone()
            dut.io.destinationBase.poke(0)
            val speculativeTags = (0 until 8).map(i => dut.io.destination.physical(i).peek().litValue.toInt)
            destination(dut.io.preview.bits, 0, speculativeTags, mapping.take(8).toSeq)
            dut.io.preview.valid.poke(true)
            for (i <- 0 until 8) mapping(i) = speculativeTags(i)
            checkSources() // Includes v0 preview through the fourth shared read.
            dut.io.accept.poke(true); dut.clock.step()
            dut.io.preview.valid.poke(false); dut.io.accept.poke(false)
            checkSources()
            dut.io.restore.poke(true); dut.clock.step(); dut.io.restore.poke(false)
            Array.copy(committed, 0, mapping, 0, 32)
            checkSources()
        }
    }

    "held bank selection preserves partial and wrapped groups through stall, acceptance and recovery" in {
        simulate(new VectorRename) { dut =>
            VectorTestUtils.clear(dut.io.preview.bits)
            for (retire <- dut.io.retire) {
                retire.valid.poke(false); VectorTestUtils.clear(retire.bits)
            }
            dut.io.preview.valid.poke(false); dut.io.previewBanks.poke(0)
            dut.io.accept.poke(false); dut.io.restore.poke(false)
            dut.io.sourceBase.foreach(_.poke(0)); dut.io.sourceCount.foreach(_.poke(8))
            dut.io.destinationBase.poke(0); dut.io.destinationCount.poke(0)
            dut.clock.step()
            for (base <- 0 until 32; count <- 0 to math.min(8, 32 - base)) {
                dut.io.sourceBase(0).poke(base)
                dut.io.destinationBase.poke(base); dut.io.destinationCount.poke(count)
                val tags = dut.io.destination.physical.take(count).map(_.peek().litValue)
                dut.io.preview.bits.base.poke(base); dut.io.preview.bits.count.poke(count)
                for (i <- 0 until 8) {
                    dut.io.preview.bits.physical(i).poke(tags.lift(i).getOrElse(BigInt(0)))
                    dut.io.preview.bits.previous(i).poke((base + i) & 31)
                }
                dut.io.previewBanks.poke((0 until count).foldLeft(BigInt(0))(
                    (mask, i) => mask.setBit((base + i) & 7)))
                dut.io.preview.valid.poke(true)
                def checkSources(): Unit = for (i <- 0 until 8)
                    dut.io.source(0)(i).expect(if (i < count) tags(i) else BigInt((base + i) & 31))
                checkSources()
                dut.clock.step(2); checkSources() // A held preview consumes no tags.
                dut.io.accept.poke(true); dut.clock.step()
                dut.io.accept.poke(false); dut.io.preview.valid.poke(false)
                checkSources()
                dut.io.restore.poke(true); dut.clock.step(); dut.io.restore.poke(false)
                for (i <- 0 until 8) dut.io.source(0)(i).expect((base + i) & 31)
            }
        }
    }

    for (registers <- Seq(56, 64)) s"$registers-register group rename excludes held tags, recycles and restores" in {
        simulate(new VectorRename(physicalRegisters = registers)) { dut =>
            def destination(x: VectorDestination, base: Int, physical: Seq[Int], previous: Seq[Int]): Unit = {
                x.base.poke(base); x.count.poke(physical.size)
                if (x eq dut.io.preview.bits) dut.io.previewBanks.poke(
                    (0 until physical.size).foldLeft(BigInt(0))((mask, i) => mask.setBit((base + i) & 7)))
                for (i <- 0 until 8) {
                    x.physical(i).poke(physical.lift(i).getOrElse(0))
                    x.previous(i).poke(previous.lift(i).getOrElse(0))
                }
            }
            dut.io.sourceBase.foreach(_.poke(0)); dut.io.sourceCount.foreach(_.poke(8))
            dut.io.destinationBase.poke(0); dut.io.destinationCount.poke(8)
            dut.io.preview.valid.poke(false); dut.io.accept.poke(false); dut.io.restore.poke(false)
            destination(dut.io.preview.bits, 0, Seq.empty, Seq.empty)
            for (r <- dut.io.retire) { r.valid.poke(false); destination(r.bits, 0, Seq.empty, Seq.empty) }
            dut.clock.step()
            dut.io.available.expect(true)
            for (i <- 0 until 8) {
                dut.io.source(0)(i).expect(i)
                dut.io.destination.physical(i).expect(32 + i)
                dut.io.destination.previous(i).expect(i)
            }
            dut.io.preview.valid.poke(true)
            destination(dut.io.preview.bits, 0, 32 until 40, 0 until 8)
            for (i <- 0 until 8) {
                dut.io.source(0)(i).expect(32 + i)
                dut.io.destination.physical(i).expect(40 + i)
            }
            dut.io.accept.poke(true); dut.clock.step()
            dut.io.accept.poke(false); dut.io.preview.valid.poke(false)
            for (i <- 0 until 8) dut.io.source(0)(i).expect(32 + i)
            dut.io.retire(0).valid.poke(true)
            destination(dut.io.retire(0).bits, 0, 32 until 40, 0 until 8)
            dut.clock.step(); dut.io.retire(0).valid.poke(false)
            val nextTags = (0 until 8).map(i => dut.io.destination.physical(i).peek().litValue.toInt)
            assert(nextTags.distinct.size == 8 && nextTags.forall(tag => tag < 32 || tag >= 40))
            dut.io.preview.valid.poke(true); dut.io.accept.poke(true)
            destination(dut.io.preview.bits, 0, nextTags, 32 until 40)
            dut.clock.step(); dut.io.preview.valid.poke(false); dut.io.accept.poke(false)
            for (i <- 0 until 8) dut.io.source(0)(i).expect(nextTags(i))
            dut.io.restore.poke(true); dut.clock.step(); dut.io.restore.poke(false)
            for (i <- 0 until 8) {
                dut.io.source(0)(i).expect(32 + i)
                dut.io.destination.physical(i).expect(nextTags(i))
            }
            // Single-register masked results and whole-register groups may start
            // at any architectural bank, including an unaligned eight-reg group.
            dut.io.destinationBase.poke(7); dut.io.destinationCount.poke(8)
            for (i <- 0 until 8) {
                val physical = dut.io.destination.physical(i).peek().litValue.toInt
                assert((physical & 7) == ((7 + i) & 7))
            }
            val wrappedTags = (0 until 8).map(i => dut.io.destination.physical(i).peek().litValue.toInt)
            val wrappedPrevious = (0 until 8).map(i => dut.io.destination.previous(i).peek().litValue.toInt)
            dut.io.preview.valid.poke(true); dut.io.accept.poke(true)
            destination(dut.io.preview.bits, 7, wrappedTags, wrappedPrevious)
            dut.clock.step(); dut.io.preview.valid.poke(false); dut.io.accept.poke(false)
            dut.io.sourceBase(0).poke(7)
            for (i <- 0 until 8) dut.io.source(0)(i).expect(wrappedTags(i))
            dut.io.retire(0).valid.poke(true)
            destination(dut.io.retire(0).bits, 7, wrappedTags, wrappedPrevious)
            dut.clock.step(); dut.io.retire(0).valid.poke(false)
            dut.io.restore.poke(true); dut.clock.step(); dut.io.restore.poke(false)
            for (i <- 0 until 8) dut.io.source(0)(i).expect(wrappedTags(i))

            // Scalar-result/configuration instructions still pass the vector
            // stage, but a zero-sized destination cannot consume or recycle a
            // tag, even when all unused tag fields carry nonzero values.
            val unchanged = (0 until 8).map(i => dut.io.destination.physical(i).peek().litValue.toInt)
            val available = dut.io.bankAvailable.peek().litValue
            for (base <- 0 until 32) {
                destination(dut.io.preview.bits, base, Seq.empty, Seq.empty)
                destination(dut.io.retire(0).bits, base, Seq.empty, Seq.empty)
                for (i <- 0 until 8) {
                    dut.io.preview.bits.physical(i).poke(48 + i)
                    dut.io.retire(0).bits.previous(i).poke(48 + i)
                }
                dut.io.preview.valid.poke(true); dut.io.accept.poke(true)
                dut.io.retire(0).valid.poke(true)
                dut.clock.step()
                dut.io.bankAvailable.expect(available)
                for (i <- 0 until 8) {
                    dut.io.source(0)(i).expect(wrappedTags(i))
                    dut.io.destination.physical(i).expect(unchanged(i))
                }
            }
        }
    }
}
