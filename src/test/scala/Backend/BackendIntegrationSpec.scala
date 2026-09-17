import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Valid
import org.scalatest.freespec.AnyFreeSpec
import ZirconConfig.{DecodeSource, DecodeUnit, EXEOp}

class BackendIdleHarness extends Module {
    val io = IO(new Bundle {
        val freeCount = Output(Vec(6, UInt(4.W)))
        val wakeup = Output(Vec(7, new BackendWakeup))
        val dcacheIdle = Output(Bool())
    })
    val backend = Module(new Backend(ramBackend = DualPortRamBackend.Registers, tlbEnabled = false))

    backend.io.middleend.arith0 := 0.U.asTypeOf(backend.io.middleend.arith0)
    backend.io.middleend.arith1 := 0.U.asTypeOf(backend.io.middleend.arith1)
    backend.io.middleend.mixArith := 0.U.asTypeOf(backend.io.middleend.mixArith)
    backend.io.middleend.load := 0.U.asTypeOf(backend.io.middleend.load)
    backend.io.middleend.loadStoreAddress := 0.U.asTypeOf(backend.io.middleend.loadStoreAddress)
    backend.io.middleend.storeData := 0.U.asTypeOf(backend.io.middleend.storeData)
    backend.io.flush := false.B
    backend.io.maintenance.request := false.B
    backend.io.csrGrant.valid := false.B
    backend.io.csrGrant.bits := 0.U.asTypeOf(backend.io.csrGrant.bits)

    backend.io.arith0.rob.pc := 0.U
    backend.io.arith0.csr.rsp := 0.U.asTypeOf(backend.io.arith0.csr.rsp)
    backend.io.arith1.rob.pc := 0.U
    backend.io.mixRob.pc := 0.U

    for (load <- Seq(backend.io.ls0, backend.io.ls1)) {
        load.sqResult.valid := true.B
        load.sqResult.bits := 0.U.asTypeOf(load.sqResult.bits)
        load.sbResult.valid := true.B
        load.sbResult.bits := 0.U.asTypeOf(load.sbResult.bits)
    }
    backend.io.ls1.storeAddress.get.ready := true.B
    backend.io.ls1.storeData.get.ready := true.B

    backend.io.store.req.valid := false.B
    backend.io.store.req.bits := 0.U.asTypeOf(backend.io.store.req.bits)
    backend.io.store.rsp.ready := true.B
    backend.io.l2.req.ready := true.B
    backend.io.l2.rsp.valid := false.B
    backend.io.l2.rsp.bits := 0.U.asTypeOf(backend.io.l2.rsp.bits)

    io.freeCount := backend.io.middleend.freeCount
    io.wakeup := backend.io.middleend.wakeup
    io.dcacheIdle := backend.io.dcacheIdle
}

class BackendForwardHarness extends Module {
    val io = IO(new Bundle {
        val start = Input(Bool())
        val arithComplete = Output(Valid(new ArithCompletion))
        val mixComplete = Output(Valid(new MixArithCompletion))
    })
    val backend = Module(new Backend(ramBackend = DualPortRamBackend.Registers, tlbEnabled = false))

    val arithGroup = WireDefault(0.U.asTypeOf(backend.io.middleend.arith0))
    arithGroup.valid := Mux(io.start, 1.U, 0.U)
    arithGroup.entries(0).fu := DecodeUnit.ALU.U
    arithGroup.entries(0).op := EXEOp.ADD
    arithGroup.entries(0).src1Sel := DecodeSource.Zero.U
    arithGroup.entries(0).src2Imm := true.B
    arithGroup.entries(0).imm := 40.U
    arithGroup.entries(0).prd := 20.U
    arithGroup.entries(0).rdValid := true.B
    arithGroup.entries(0).robIdx := 10.U
    backend.io.middleend.arith0 := arithGroup

    val mixGroup = WireDefault(0.U.asTypeOf(backend.io.middleend.mixArith))
    mixGroup.valid := Mux(io.start, 3.U, 0.U)
    for ((entry, source, destination, immediate, rob) <- Seq(
        (0, 20, 21, 2, 11),
        (1, 21, 22, 1, 12),
    )) {
        mixGroup.entries(entry).fu := DecodeUnit.ALU.U
        mixGroup.entries(entry).op := EXEOp.ADD
        mixGroup.entries(entry).src1Sel := DecodeSource.Register.U
        mixGroup.entries(entry).src2Imm := true.B
        mixGroup.entries(entry).imm := immediate.U
        mixGroup.entries(entry).prs(0) := source.U
        mixGroup.entries(entry).sourceValid(0) := true.B
        mixGroup.entries(entry).sourceReady(0) := false.B
        mixGroup.entries(entry).prd := destination.U
        mixGroup.entries(entry).rdValid := true.B
        mixGroup.entries(entry).robIdx := rob.U
    }
    backend.io.middleend.mixArith := mixGroup

    backend.io.middleend.arith1 := 0.U.asTypeOf(backend.io.middleend.arith1)
    backend.io.middleend.load := 0.U.asTypeOf(backend.io.middleend.load)
    backend.io.middleend.loadStoreAddress := 0.U.asTypeOf(backend.io.middleend.loadStoreAddress)
    backend.io.middleend.storeData := 0.U.asTypeOf(backend.io.middleend.storeData)
    backend.io.flush := false.B
    backend.io.maintenance.request := false.B
    backend.io.csrGrant.valid := false.B
    backend.io.csrGrant.bits := 0.U.asTypeOf(backend.io.csrGrant.bits)

    backend.io.arith0.rob.pc := 0.U
    backend.io.arith0.csr.rsp := 0.U.asTypeOf(backend.io.arith0.csr.rsp)
    backend.io.arith1.rob.pc := 0.U
    backend.io.mixRob.pc := 0.U

    for (load <- Seq(backend.io.ls0, backend.io.ls1)) {
        load.sqResult.valid := true.B
        load.sqResult.bits := 0.U.asTypeOf(load.sqResult.bits)
        load.sbResult.valid := true.B
        load.sbResult.bits := 0.U.asTypeOf(load.sbResult.bits)
    }
    backend.io.ls1.storeAddress.get.ready := true.B
    backend.io.ls1.storeData.get.ready := true.B
    backend.io.store.req.valid := false.B
    backend.io.store.req.bits := 0.U.asTypeOf(backend.io.store.req.bits)
    backend.io.store.rsp.ready := true.B
    backend.io.l2.req.ready := true.B
    backend.io.l2.rsp.valid := false.B
    backend.io.l2.rsp.bits := 0.U.asTypeOf(backend.io.l2.rsp.bits)

    io.arithComplete := backend.io.arith0.rob.complete
    io.mixComplete := backend.io.mixRob.complete
}

class BackendIntegrationSpec extends AnyFreeSpec with ChiselSim {
    "the assembled backend resets and remains idle with all six queues empty" in {
        simulate(new BackendIdleHarness) { dut =>
            dut.reset.poke(true)
            dut.clock.step(3)
            dut.reset.poke(false)
            dut.clock.step(5)
            dut.io.freeCount(0).expect(6)
            dut.io.freeCount(1).expect(6)
            dut.io.freeCount(2).expect(8)
            dut.io.freeCount(3).expect(6)
            dut.io.freeCount(4).expect(8)
            dut.io.freeCount(5).expect(6)
            dut.io.wakeup.foreach { wakeup =>
                wakeup.prd.expect(0)
                wakeup.specMask.expect(0)
            }
            dut.io.dcacheIdle.expect(true)
        }
    }

    "scheduled bypass preserves an Arith-to-Mix-to-Mix dependency chain" in {
        simulate(new BackendForwardHarness) { dut =>
            dut.io.start.poke(false)
            dut.reset.poke(true)
            dut.clock.step(3)
            dut.reset.poke(false)
            dut.io.start.poke(true)
            dut.clock.step()
            dut.io.start.poke(false)

            var arithResult = Option.empty[BigInt]
            val mixResults = collection.mutable.Map.empty[Int, BigInt]
            var cycles = 0
            while ((arithResult.isEmpty || mixResults.size < 2) && cycles < 60) {
                if (dut.io.arithComplete.valid.peek().litToBoolean) {
                    dut.io.arithComplete.bits.robIdx.expect(10)
                    arithResult = Some(dut.io.arithComplete.bits.data.peek().litValue)
                }
                if (dut.io.mixComplete.valid.peek().litToBoolean) {
                    val rob = dut.io.mixComplete.bits.robIdx.peek().litValue.toInt
                    assert(!mixResults.contains(rob), s"ROB $rob completed more than once")
                    mixResults(rob) = dut.io.mixComplete.bits.data.peek().litValue
                }
                dut.clock.step()
                cycles += 1
            }

            assert(arithResult.contains(40), s"Unexpected Arith result $arithResult")
            assert(mixResults.get(11).contains(42), s"Unexpected first Mix result $mixResults")
            assert(mixResults.get(12).contains(43), s"Unexpected dependent Mix result $mixResults")
        }
    }
}
