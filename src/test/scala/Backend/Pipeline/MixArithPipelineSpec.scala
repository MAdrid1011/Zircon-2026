import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{BypassParams, DecodeSource, DecodeUnit, DivideOp, EXEOp, FpMiscOp, MultiplyOp, RegfileParams}

class MixArithPipelineTestTop extends Module {
    val io = IO(new Bundle {
        val issue = Flipped(Decoupled(new BackendPackage))
        val pc = Input(UInt(32.W))
        val flush = Input(Bool())
        val intSeed = Flipped(Valid(new MixArithLocalWrite))
        val fpSeed = Flipped(Valid(new MixArithLocalWrite))
        val external = Input(Vec(2, new BypassSource))
        val completion = Output(Valid(new MixArithCompletion))
        val intWrite = Output(Valid(new MixArithLocalWrite))
        val fpWrite = Output(Valid(new MixArithLocalWrite))
        val wakeup = Output(Valid(UInt(MixArithConstants.physTagWidth.W)))
        val available = Output(Bool())
    })

    val pipeline = Module(new MixArithPipeline)
    val intRF = Module(new Regfile(RegfileParams(
        numEntries = MixArithConstants.numIntPhys,
        numReadPorts = 2,
        numWritePorts = 2
    )))
    val fpRF = Module(new Regfile(RegfileParams(
        numEntries = MixArithConstants.numFpPhys,
        numReadPorts = 3,
        numWritePorts = 2,
        hasZeroReg = false
    )))
    val bypass = Module(new Bypass(BypassParams(
        numProducers = 3,
        consumerSources = Seq(3),
        consumerProducers = Seq(Seq(Seq(0, 1, 2), Seq(0, 1, 2), Seq(2))),
    )))

    pipeline.io.iq <> io.issue
    pipeline.io.cmt.rob.pc := io.pc
    pipeline.io.cmt.flush := io.flush
    pipeline.io.csr.frm := 0.U

    for (source <- 0 until 2) {
        intRF.io.read(source).addr := pipeline.io.rf.intRead(source).addr
        pipeline.io.rf.intRead(source).data := intRF.io.read(source).data
    }
    for (source <- 0 until 3) {
        fpRF.io.read(source).addr := pipeline.io.rf.fpRead(source).addr
        pipeline.io.rf.fpRead(source).data := fpRF.io.read(source).data
    }

    intRF.io.write(0).addr := pipeline.io.rf.intWrite.bits.addr
    intRF.io.write(0).we := pipeline.io.rf.intWrite.valid
    intRF.io.write(0).data := pipeline.io.rf.intWrite.bits.data
    intRF.io.write(1).addr := io.intSeed.bits.addr
    intRF.io.write(1).we := io.intSeed.valid
    intRF.io.write(1).data := io.intSeed.bits.data

    fpRF.io.write(0).addr := pipeline.io.rf.fpWrite.bits.addr
    fpRF.io.write(0).we := pipeline.io.rf.fpWrite.valid
    fpRF.io.write(0).data := pipeline.io.rf.fpWrite.bits.data
    fpRF.io.write(1).addr := io.fpSeed.bits.addr
    fpRF.io.write(1).we := io.fpSeed.valid
    fpRF.io.write(1).data := io.fpSeed.bits.data

    bypass.io.consumer(0) <> pipeline.io.bypass.consumer
    bypass.io.producer(0) := io.external(0)
    bypass.io.producer(1) := io.external(1)
    bypass.io.producer(2) := pipeline.io.bypass.producer

    io.completion := pipeline.io.cmt.rob.complete
    io.intWrite := pipeline.io.rf.intWrite
    io.fpWrite := pipeline.io.rf.fpWrite
    io.wakeup := pipeline.io.wakeup
    io.available := pipeline.io.available
}

class MixArithPipelineSpec extends AnyFreeSpec with ChiselSim {
    private val mask = BigInt("ffffffff", 16)
    private val fpBit = 1 << MixArithConstants.localPhysWidth

    private case class Result(
        prd: Int,
        data: BigInt,
        flags: Int,
        flagsValid: Boolean
    )

    private def fpTag(index: Int): Int = fpBit | index

    private def pokePackage(
        x: BackendPackage,
        fu: Int,
        op: Int,
        sources: Seq[(Int, Boolean)] = Seq.empty,
        prd: Int = 0,
        src1Sel: Int = DecodeSource.Register,
        src2Imm: Boolean = false,
        imm: BigInt = 0,
        roundingMode: Int = 0,
        rob: Int = 0,
        fpFlagsValid: Boolean = false
    ): Unit = {
        BackendPackageTestUtils.clear(x)
        for (i <- 0 until MixArithConstants.numSources) {
            x.prs(i).poke(sources.lift(i).map(_._1).getOrElse(0))
            x.sourceValid(i).poke(sources.lift(i).exists(_._2))
        }
        x.prd.poke(prd)
        x.rdValid.poke(prd != 0)
        x.fu.poke(fu)
        x.op.poke(op)
        x.src1Sel.poke(src1Sel)
        x.src2Imm.poke(src2Imm)
        x.imm.poke(imm)
        x.roundingMode.poke(roundingMode)
        x.inst.poke(0)
        x.rdZero.poke(false)
        x.robIdx.poke(rob)
        x.sqTail.poke(0)
        x.sqIdx.poke(0)
        x.exception.valid.poke(false)
        x.exception.cause.poke(0)
        x.exception.tval.poke(0)
        x.uncache.poke(false)
        x.ioAuthorized.poke(false)
        x.store.poke(false)
        x.mtype.poke(0)
        x.size.poke(0)
        x.fpFlagsValid.poke(fpFlagsValid)
        for (source <- 0 until MixArithConstants.numSources) {
            x.source(source).poke(0)
        }
        x.result.poke(0)
        x.fflags.poke(0)
        x.resultIsFp.poke(false)
        x.pc.poke(0)
        x.predictedTaken.poke(false)
        x.predictedValue.poke(0)
        x.branchTaken.poke(false)
        x.branchTarget.poke(0)
        x.predFail.poke(false)
    }

    private def initialize(dut: MixArithPipelineTestTop): Unit = {
        dut.io.issue.valid.poke(false)
        pokePackage(dut.io.issue.bits, DecodeUnit.ALU, EXEOp.ADD.litValue.toInt)
        dut.io.pc.poke(0)
        dut.io.flush.poke(false)
        dut.io.intSeed.valid.poke(false)
        dut.io.intSeed.bits.addr.poke(1)
        dut.io.intSeed.bits.data.poke(0)
        dut.io.fpSeed.valid.poke(false)
        dut.io.fpSeed.bits.addr.poke(0)
        dut.io.fpSeed.bits.data.poke(0)
        for (producer <- dut.io.external) {
            producer.nextWb.valid.poke(false)
            producer.nextWb.bits.poke(1)
            producer.result.valid.poke(false)
            producer.result.bits.prd.poke(1)
            producer.result.bits.data.poke(0)
        }
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
        dut.clock.step()
    }

    private def sample(dut: MixArithPipelineTestTop, results: collection.mutable.Map[Int, Result]): Unit = {
        if (dut.io.completion.valid.peek().litToBoolean) {
            val bits = dut.io.completion.bits
            val rob = bits.robIdx.peek().litValue.toInt
            assert(!results.contains(rob), s"ROB $rob completed more than once")
            results(rob) = Result(
                bits.prd.peek().litValue.toInt,
                bits.data.peek().litValue & mask,
                bits.fflags.peek().litValue.toInt,
                bits.fpFlagsValid.peek().litToBoolean
            )
            bits.rdValid.expect(bits.prd.peek().litValue != 0)
            dut.io.wakeup.valid.expect(bits.rdValid.peek().litToBoolean)
            if (bits.rdValid.peek().litToBoolean) {
                dut.io.wakeup.bits.expect(bits.prd.peek().litValue)
            }
        }
    }

    private def tick(
        dut: MixArithPipelineTestTop,
        results: collection.mutable.Map[Int, Result],
        cycles: Int = 1
    ): Unit = {
        for (_ <- 0 until cycles) {
            dut.clock.step()
            sample(dut, results)
        }
    }

    private def seedInt(
        dut: MixArithPipelineTestTop,
        results: collection.mutable.Map[Int, Result],
        index: Int,
        data: BigInt
    ): Unit = {
        dut.io.intSeed.valid.poke(true)
        dut.io.intSeed.bits.addr.poke(index)
        dut.io.intSeed.bits.data.poke(data & mask)
        tick(dut, results)
        dut.io.intSeed.valid.poke(false)
    }

    private def seedFp(
        dut: MixArithPipelineTestTop,
        results: collection.mutable.Map[Int, Result],
        index: Int,
        data: BigInt
    ): Unit = {
        dut.io.fpSeed.valid.poke(true)
        dut.io.fpSeed.bits.addr.poke(index)
        dut.io.fpSeed.bits.data.poke(data & mask)
        tick(dut, results)
        dut.io.fpSeed.valid.poke(false)
    }

    private def issue(
        dut: MixArithPipelineTestTop,
        results: collection.mutable.Map[Int, Result]
    )(poke: BackendPackage => Unit): Unit = {
        poke(dut.io.issue.bits)
        dut.io.issue.valid.poke(true)
        var waited = 0
        while (!dut.io.issue.ready.peek().litToBoolean && waited < 200) {
            tick(dut, results)
            waited += 1
        }
        assert(dut.io.issue.ready.peek().litToBoolean, "MixArith issue timed out")
        tick(dut, results)
        dut.io.issue.valid.poke(false)
    }

    private def waitFor(
        dut: MixArithPipelineTestTop,
        results: collection.mutable.Map[Int, Result],
        expected: Set[Int],
        limit: Int = 300
    ): Unit = {
        var cycles = 0
        while (!expected.subsetOf(results.keySet) && cycles < limit) {
            tick(dut, results)
            cycles += 1
        }
        assert(expected.subsetOf(results.keySet), s"Missing completions ${expected -- results.keySet}")
    }

    private def forwardNextWb(
        dut: MixArithPipelineTestTop,
        results: collection.mutable.Map[Int, Result],
        producer: Int,
        tag: Int,
        data: BigInt
    ): Unit = {
        dut.io.external(producer).nextWb.valid.poke(true)
        dut.io.external(producer).nextWb.bits.poke(tag)
        tick(dut, results)
        dut.io.external(producer).nextWb.valid.poke(false)
        dut.io.external(producer).result.valid.poke(true)
        dut.io.external(producer).result.bits.prd.poke(tag)
        dut.io.external(producer).result.bits.data.poke(data & mask)
        tick(dut, results)
        dut.io.external(producer).result.valid.poke(false)
    }

    "routes integer, floating-point and conversion operations through the shared path" in {
        simulate(new MixArithPipelineTestTop) { dut =>
            val results = collection.mutable.Map.empty[Int, Result]
            initialize(dut)
            seedInt(dut, results, 1, 7)
            seedInt(dut, results, 2, 5)
            seedInt(dut, results, 3, 6)
            seedInt(dut, results, 4, 9)
            seedInt(dut, results, 5, BigInt("fffffffe", 16))
            seedInt(dut, results, 6, 21)
            seedInt(dut, results, 7, 3)
            seedFp(dut, results, 1, BigInt("3f800000", 16))
            seedFp(dut, results, 2, BigInt("80000000", 16))
            seedFp(dut, results, 5, BigInt("40000000", 16))

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.ALU,
                    EXEOp.ADD.litValue.toInt,
                    Seq(1 -> true, 2 -> true),
                    prd = 10,
                    rob = 1
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Multiply,
                    MultiplyOp.MUL.litValue.toInt,
                    Seq(3 -> true, 4 -> true),
                    prd = 11,
                    rob = 2
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.FpMisc,
                    FpMiscOp.FSGNJ,
                    Seq(fpTag(1) -> true, fpTag(2) -> true),
                    prd = fpTag(3),
                    rob = 3
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.FpMisc,
                    FpMiscOp.FCVT_S_W,
                    Seq(5 -> true),
                    prd = fpTag(4),
                    rob = 4,
                    fpFlagsValid = true
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Multiply,
                    MultiplyOp.FADD.litValue.toInt,
                    Seq(fpTag(1) -> true, fpTag(5) -> true),
                    prd = fpTag(6),
                    rob = 5,
                    fpFlagsValid = true
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Divide,
                    DivideOp.DIV,
                    Seq(6 -> true, 7 -> true),
                    prd = 12,
                    rob = 6
                )
            }

            waitFor(dut, results, (1 to 6).toSet)
            assert(results(1).data == 12 && results(1).prd == 10)
            assert(results(2).data == 54 && results(2).prd == 11)
            assert(results(3).data == BigInt("bf800000", 16) && results(3).prd == fpTag(3))
            assert(results(4).data == BigInt("c0000000", 16) && results(4).prd == fpTag(4))
            assert(results(5).data == BigInt("40400000", 16) && results(5).prd == fpTag(6))
            assert(results(6).data == 7 && results(6).prd == 12)
            assert(results(4).flagsValid && results(5).flagsValid)
        }
    }

    "aligns mixed short-unit results and returns each exactly once" in {
        simulate(new MixArithPipelineTestTop) { dut =>
            val results = collection.mutable.Map.empty[Int, Result]
            initialize(dut)
            seedInt(dut, results, 1, 3)
            seedFp(dut, results, 1, BigInt("3f800000", 16))
            seedFp(dut, results, 2, BigInt("80000000", 16))

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.FpMisc,
                    FpMiscOp.FCVT_S_W,
                    Seq(1 -> true),
                    prd = fpTag(10),
                    rob = 10,
                    fpFlagsValid = true
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.FpMisc,
                    FpMiscOp.FSGNJ,
                    Seq(fpTag(1) -> true, fpTag(2) -> true),
                    prd = fpTag(11),
                    rob = 11
                )
            }

            waitFor(dut, results, Set(10, 11))
            assert(results.size == 2)
            assert(results(10).data == BigInt("40400000", 16))
            assert(results(11).data == BigInt("bf800000", 16))
        }
    }

    "prevents younger operations from bypassing a divide held in EX2" in {
        simulate(new MixArithPipelineTestTop) { dut =>
            val results = collection.mutable.Map.empty[Int, Result]
            initialize(dut)
            seedInt(dut, results, 1, BigInt("7fffffff", 16))
            seedInt(dut, results, 2, 3)
            seedInt(dut, results, 3, 8)
            seedInt(dut, results, 4, 9)

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Divide,
                    DivideOp.DIV,
                    Seq(1 -> true, 2 -> true),
                    prd = 20,
                    rob = 30
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.ALU,
                    EXEOp.ADD.litValue.toInt,
                    Seq(3 -> true, 4 -> true),
                    prd = 21,
                    rob = 31
                )
            }

            var cycles = 0
            while (!results.contains(30) && cycles < 80) {
                tick(dut, results)
                assert(!results.contains(31), "A younger ALU operation bypassed the divide in EX2")
                cycles += 1
            }
            assert(results.contains(30), "The divide did not complete")
            var followingCycles = 0
            while (!results.contains(31) && followingCycles < 20) {
                tick(dut, results)
                followingCycles += 1
            }
            assert(results.contains(31), "The younger ALU operation did not complete")
            assert(followingCycles == 2, "EX1 did not restart as the divide left EX2")
            assert(results(30).data == BigInt("2aaaaaaa", 16))
            assert(results(31).data == 17)
        }
    }

    "forwards scheduled integer sources and excludes Arith producers from FP source 3" in {
        simulate(new MixArithPipelineTestTop) { dut =>
            val results = collection.mutable.Map.empty[Int, Result]
            initialize(dut)
            seedInt(dut, results, 1, 3)

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.ALU,
                    EXEOp.ADD.litValue.toInt,
                    Seq(20 -> true, 1 -> true),
                    prd = 21,
                    rob = 20
                )
            }
            forwardNextWb(dut, results, 0, 20, 40)
            waitFor(dut, results, Set(20))
            assert(results(20).data == 43)

            seedFp(dut, results, 1, BigInt("3f800000", 16))
            seedFp(dut, results, 2, BigInt("40000000", 16))
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Multiply,
                    MultiplyOp.FMADD.litValue.toInt,
                    Seq(fpTag(1) -> true, fpTag(2) -> true, fpTag(10) -> true),
                    prd = fpTag(12),
                    rob = 21,
                    fpFlagsValid = true
                )
            }
            // Arith producers cannot write FP registers and therefore cannot feed source 2.
            forwardNextWb(dut, results, 1, 10, BigInt("40400000", 16))
            waitFor(dut, results, Set(21))
            assert(results(21).data == BigInt("40000000", 16))

            seedFp(dut, results, 11, BigInt("40800000", 16))
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Multiply,
                    MultiplyOp.FMADD.litValue.toInt,
                    Seq(fpTag(1) -> true, fpTag(2) -> true, fpTag(11) -> true),
                    prd = fpTag(13),
                    rob = 22,
                    fpFlagsValid = true
                )
            }
            // The same local number in the integer domain must not replace FP source 3.
            forwardNextWb(dut, results, 1, 11, BigInt("7f800000", 16))
            waitFor(dut, results, Set(22))
            assert(results(22).data == BigInt("40c00000", 16))
        }
    }

    "retains a one-cycle forwarded value while the selected unit is blocked" in {
        simulate(new MixArithPipelineTestTop) { dut =>
            val results = collection.mutable.Map.empty[Int, Result]
            initialize(dut)
            seedInt(dut, results, 1, BigInt("7fffffff", 16))
            seedInt(dut, results, 2, 3)
            seedInt(dut, results, 5, 7)

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Divide,
                    DivideOp.DIV,
                    Seq(1 -> true, 2 -> true),
                    prd = 30,
                    rob = 40
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.ALU,
                    EXEOp.ADD.litValue.toInt,
                    Seq(20 -> true, 5 -> true),
                    prd = 33,
                    rob = 43
                )
            }

            forwardNextWb(dut, results, 0, 20, 84)

            waitFor(dut, results, Set(40, 43), limit = 100)
            assert(results(40).data == BigInt("2aaaaaaa", 16))
            assert(results(43).data == 91)
        }
    }

    "flushes every arithmetic stage at commit and reopens a busy divider" in {
        simulate(new MixArithPipelineTestTop) { dut =>
            val results = collection.mutable.Map.empty[Int, Result]
            initialize(dut)
            seedInt(dut, results, 1, BigInt("7fffffff", 16))
            seedInt(dut, results, 2, 3)
            seedInt(dut, results, 3, 8)
            seedInt(dut, results, 4, 9)

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Divide,
                    DivideOp.DIV,
                    Seq(1 -> true, 2 -> true),
                    prd = 20,
                    rob = 30
                )
            }
            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.Multiply,
                    MultiplyOp.MUL.litValue.toInt,
                    Seq(3 -> true, 4 -> true),
                    prd = 22,
                    rob = 32
                )
            }
            tick(dut, results, 2)
            dut.io.flush.poke(true)
            tick(dut, results)
            dut.io.flush.poke(false)

            issue(dut, results) { x =>
                pokePackage(
                    x,
                    DecodeUnit.ALU,
                    EXEOp.ADD.litValue.toInt,
                    Seq(3 -> true, 4 -> true),
                    prd = 21,
                    rob = 31
                )
            }
            waitFor(dut, results, Set(31), limit = 40)
            assert(results(31).data == 17)
            tick(dut, results, 80)
            assert(!results.contains(30))
            assert(!results.contains(32))
        }
    }
}
