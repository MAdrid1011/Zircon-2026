package zircon

import chisel3.simulator.scalatest.ChiselSim
import java.nio.file.{Files, Paths}
import org.scalatest.funspec.AnyFunSpec
import scala.util.Random
import zircon.core.ZirconCore

class CoreShellSpec extends AnyFunSpec with ChiselSim {
  private val ResetVector = BigInt("80000000", 16)
  private val Nop = BigInt("00000013", 16)
  private val M2RecoveryBackpressureSeeds = Seq(0x5eedL, 0x5eed1001L,
    0x5eed2002L, 0x5eed3003L)

  private case class TraceSample(
      order: BigInt,
      pc: BigInt,
      instruction: BigInt,
      gprWrite: Boolean,
      gprAddress: BigInt,
      gprData: BigInt,
      csrWrite: Boolean,
      csrAddress: BigInt,
      csrData: BigInt,
      trap: Boolean,
      interrupt: Boolean,
      cause: BigInt,
      trapValue: BigInt
  )

  private def clearInputs(dut: ZirconCore): Unit = {
    dut.io.interrupts.meip.poke(false)
    dut.io.interrupts.msip.poke(false)
    dut.io.interrupts.mtip.poke(false)
    dut.io.axi.aw.ready.poke(true)
    dut.io.axi.w.ready.poke(true)
    dut.io.axi.ar.ready.poke(false)
    dut.io.axi.b.valid.poke(false)
    dut.io.axi.b.bits.id.poke(0)
    dut.io.axi.b.bits.resp.poke(0)
    dut.io.axi.r.valid.poke(false)
    dut.io.axi.r.bits.id.poke(0)
    dut.io.axi.r.bits.data.poke(0)
    dut.io.axi.r.bits.resp.poke(0)
    dut.io.axi.r.bits.last.poke(false)
  }

  private def sendInstructionPacket(dut: ZirconCore, words: Seq[BigInt],
      responses: Seq[Int] = Seq.empty): Unit = {
    words.zipWithIndex.foreach { case (word, index) =>
      dut.io.axi.r.valid.poke(true)
      dut.io.axi.r.bits.id.poke(0)
      dut.io.axi.r.bits.data.poke(word)
      dut.io.axi.r.bits.resp.poke(responses.lift(index).getOrElse(0))
      dut.io.axi.r.bits.last.poke(index == words.length - 1)
      dut.io.axi.r.ready.expect(true)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false)
    }
  }

  /** Drives a deterministic one-outstanding AXI instruction memory.
    *
    * The memory returns each accepted AR burst in order and holds R valid until
    * the core accepts the beat. The default instruction is NOP so a test only
    * needs to specify architecturally relevant addresses.
    */
  private def runProgram(
      dut: ZirconCore,
      program: Map[BigInt, BigInt],
      cycles: Int = 128,
      driveInterrupts: (ZirconCore, Seq[TraceSample]) => Unit = (_, _) => (),
      arReadyForCycle: Int => Boolean = _ => true,
      rValidForCycle: Int => Boolean = _ => true,
      observeCycle: (ZirconCore, Int) => Unit = (_, _) => ()
  ): Seq[TraceSample] = {
    val pendingReads = scala.collection.mutable.Queue.empty[(BigInt, Boolean)]
    val events = scala.collection.mutable.ArrayBuffer.empty[TraceSample]

    dut.clock.step(128) // Deterministic bimodal/BTB scrubs.
    for (cycle <- 0 until cycles) {
      driveInterrupts(dut, events.toSeq)
      dut.io.axi.ar.ready.poke(arReadyForCycle(cycle))
      val rOffered = pendingReads.nonEmpty && rValidForCycle(cycle)
      if (rOffered) {
        val (data, last) = pendingReads.front
        dut.io.axi.r.valid.poke(true)
        dut.io.axi.r.bits.id.poke(0)
        dut.io.axi.r.bits.data.poke(data)
        dut.io.axi.r.bits.resp.poke(0)
        dut.io.axi.r.bits.last.poke(last)
      } else {
        dut.io.axi.r.valid.poke(false)
      }

      dut.io.trace.get.foreach { event =>
        if (event.valid.peek().litToBoolean) {
          events += TraceSample(
            order = event.order.peek().litValue,
            pc = event.pc.peek().litValue,
            instruction = event.instruction.peek().litValue,
            gprWrite = event.gprWrite.peek().litToBoolean,
            gprAddress = event.gprAddress.peek().litValue,
            gprData = event.gprData.peek().litValue,
            csrWrite = event.csrWrite.peek().litToBoolean,
            csrAddress = event.csrAddress.peek().litValue,
            csrData = event.csrData.peek().litValue,
            trap = event.trap.peek().litToBoolean,
            interrupt = event.interrupt.peek().litToBoolean,
            cause = event.cause.peek().litValue,
            trapValue = event.trapValue.peek().litValue
          )
        }
      }

      val arFire = dut.io.axi.ar.valid.peek().litToBoolean &&
        dut.io.axi.ar.ready.peek().litToBoolean
      val arAddress = dut.io.axi.ar.bits.addr.peek().litValue
      val arBeats = dut.io.axi.ar.bits.len.peek().litValue.toInt + 1
      val rFire = rOffered && dut.io.axi.r.ready.peek().litToBoolean

      observeCycle(dut, cycle)
      dut.clock.step()

      if (rFire) {
        pendingReads.dequeue()
      }
      if (arFire) {
        for (beat <- 0 until arBeats) {
          val address = arAddress + beat * 4
          pendingReads.enqueue((program.getOrElse(address, Nop), beat == arBeats - 1))
        }
      }
    }
    events.toSeq
  }

  private def throughFirstTrap(events: Seq[TraceSample]): Seq[TraceSample] = {
    throughTrap(events, 1)
  }

  private def throughTrap(events: Seq[TraceSample], count: Int): Seq[TraceSample] = {
    val trapIndices = events.indices.filter(events(_).trap)
    assert(trapIndices.size >= count,
      s"the program did not reach expected trap number $count")
    events.take(trapIndices(count - 1) + 1)
  }

  private def seededBackpressure(seed: Long, cycles: Int):
      (IndexedSeq[Boolean], IndexedSeq[Boolean]) = {
    val random = new Random(seed)
    val arReady = Vector.tabulate(cycles) { cycle =>
      cycle % 7 == 0 || random.nextInt(100) < 70
    }
    val rValid = Vector.tabulate(cycles) { cycle =>
      cycle % 5 == 0 || random.nextInt(100) < 65
    }
    (arReady, rValid)
  }

  private def saveM2RecoveryFailure(
      seed: Long,
      arReady: IndexedSeq[Boolean],
      rValid: IndexedSeq[Boolean],
      events: Seq[TraceSample]
  ): Unit = {
    val directory = Paths.get("target", "zircon-failures")
    Files.createDirectories(directory)
    val evidence =
      s"seed=$seed\n" +
        s"ar_ready=${arReady.map(value => if (value) '1' else '0').mkString}\n" +
        s"r_valid=${rValid.map(value => if (value) '1' else '0').mkString}\n" +
        events.mkString("retire_trace=\n", "\n", "\n")
    Files.writeString(directory.resolve(s"m2-recovery-backpressure-$seed.txt"), evidence)
  }

  describe("ZirconCore executable M1/M2 integration") {
    it("executes an AXI-fed RV32I dependency chain and emits precise retire events") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)

        dut.io.axi.aw.valid.expect(false)
        dut.io.axi.w.valid.expect(false)
        dut.io.axi.ar.valid.expect(false)
        dut.io.trace.get.foreach(_.valid.expect(false))

        dut.clock.step(128)
        dut.clock.step()
        dut.io.axi.ar.valid.expect(true)
        dut.io.axi.ar.bits.id.expect(0)
        dut.io.axi.ar.bits.addr.expect(ResetVector)
        dut.io.axi.ar.bits.len.expect(3)
        dut.io.axi.ar.ready.poke(true)
        dut.clock.step()
        dut.io.axi.ar.ready.poke(false)

        sendInstructionPacket(dut, Seq(
          BigInt("00500093", 16), // addi x1,x0,5
          BigInt("00308113", 16), // addi x2,x1,3
          BigInt("00100073", 16), // ebreak
          BigInt("00000013", 16)
        ))

        val events = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt, Boolean, Boolean, BigInt)]
        for (_ <- 0 until 48) {
          for (lane <- 0 until 2) {
            val event = dut.io.trace.get(lane)
            if (event.valid.peek().litToBoolean) {
              events += ((
                event.order.peek().litValue,
                event.pc.peek().litValue,
                event.instruction.peek().litValue,
                event.gprWrite.peek().litToBoolean,
                event.trap.peek().litToBoolean,
                event.cause.peek().litValue
              ))
            }
          }
          dut.clock.step()
        }

        assert(events.take(3).map(_._1) == Seq(BigInt(0), BigInt(1), BigInt(2)))
        assert(events(0) == (BigInt(0), ResetVector, BigInt("00500093", 16), true, false, BigInt(0)))
        assert(events(1) == (BigInt(1), ResetVector + 4, BigInt("00308113", 16), true, false, BigInt(0)))
        assert(events(2) == (BigInt(2), ResetVector + 8, BigInt("00100073", 16), false, true, BigInt(3)))
      }
    }

    it("removes trace and M2 observation ports from the production configuration") {
      simulate(new ZirconCore(ZirconCoreConfig.default)) { dut =>
        clearInputs(dut)
        assert(dut.io.trace.isEmpty)
        assert(dut.io.m2Observation.isEmpty)
      }
    }

    it("turns an AXI instruction RRESP error into a precise fetch-fault trap event") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        dut.clock.step(128)
        dut.clock.step()
        dut.io.axi.ar.valid.expect(true)
        dut.io.axi.ar.ready.poke(true)
        dut.clock.step()
        dut.io.axi.ar.ready.poke(false)
        sendInstructionPacket(dut, Seq(0, 0, 0, 0),
          responses = Seq(2, 0, 0, 0))

        var observed = false
        for (_ <- 0 until 32) {
          for (lane <- 0 until 2) {
            val event = dut.io.trace.get(lane)
            if (event.valid.peek().litToBoolean && event.trap.peek().litToBoolean) {
              event.pc.expect(ResetVector)
              event.instruction.expect(0)
              event.cause.expect(1)
              event.trapValue.expect(ResetVector)
              observed = true
            }
          }
          dut.clock.step()
        }
        assert(observed, "the instruction access fault did not reach retire trace")
      }
    }

    it("flushes a cold not-taken branch's wrong path before retire") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00100093", 16), // addi x1,x0,1
          ResetVector + 4 -> BigInt("00108463", 16), // beq x1,x1,+8
          ResetVector + 8 -> BigInt("00200113", 16), // wrong-path addi x2,x0,2
          ResetVector + 12 -> BigInt("00300193", 16), // addi x3,x0,3
          ResetVector + 16 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.order) == Seq(0, 1, 2, 3))
        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 12, ResetVector + 16))
        assert(!events.exists(_.instruction == BigInt("00200113", 16)))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == 3)
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("uses a direct JAL target and commits its architectural link value") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("008000ef", 16), // jal x1,+8
          ResetVector + 4 -> BigInt("00200113", 16), // skipped addi x2,x0,2
          ResetVector + 8 -> BigInt("00208193", 16), // addi x3,x1,2
          ResetVector + 12 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 8,
          ResetVector + 12))
        assert(events.head.gprWrite && events.head.gprAddress == 1 &&
          events.head.gprData == ResetVector + 4)
        assert(events(1).gprWrite && events(1).gprAddress == 3 &&
          events(1).gprData == ResetVector + 6)
        assert(!events.exists(_.instruction == BigInt("00200113", 16)))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("blocks after a targetless JALR and resumes only at execute recovery target") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("01008093", 16), // addi x1,x1,16
          ResetVector + 8 -> BigInt("000082e7", 16), // jalr x5,x1,0
          ResetVector + 12 -> BigInt("00200113", 16), // blocked wrong-path addi x2,x0,2
          ResetVector + 16 -> BigInt("00328193", 16), // addi x3,x5,3
          ResetVector + 20 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 16, ResetVector + 20))
        assert(events(2).gprWrite && events(2).gprAddress == 5 &&
          events(2).gprData == ResetVector + 12)
        assert(events(3).gprWrite && events(3).gprAddress == 3 &&
          events(3).gprData == ResetVector + 15)
        assert(!events.exists(_.instruction == BigInt("00200113", 16)))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("commits CSR write data before a dependent architectural CSR read") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("01200093", 16), // addi x1,x0,18
          ResetVector + 4 -> BigInt("340092f3", 16), // csrrw x5,mscratch,x1
          ResetVector + 8 -> BigInt("34002373", 16), // csrrs x6,mscratch,x0
          ResetVector + 12 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12))
        assert(events(1).gprWrite && events(1).gprAddress == 5 &&
          events(1).gprData == 0)
        assert(events(1).csrWrite && events(1).csrAddress == 0x340 &&
          events(1).csrData == 18)
        assert(events(2).gprWrite && events(2).gprAddress == 6 &&
          events(2).gprData == 18 && !events(2).csrWrite)
        assert(events.last.trap && !events.last.interrupt && events.last.cause == 3)
      }
    }

    it("returns from an ECALL handler through a programmed mtvec and MRET") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val handler = ResetVector + 32
        val events = throughTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("02008093", 16), // addi x1,x1,32
          ResetVector + 8 -> BigInt("30509073", 16), // csrw mtvec,x1
          ResetVector + 12 -> BigInt("00000073", 16), // ecall
          ResetVector + 16 -> BigInt("00300193", 16), // addi x3,x0,3
          ResetVector + 20 -> BigInt("00100073", 16), // ebreak
          handler -> BigInt("34102173", 16), // csrrs x2,mepc,x0
          handler + 4 -> BigInt("00410113", 16), // addi x2,x2,4
          handler + 8 -> BigInt("34111073", 16), // csrw mepc,x2
          handler + 12 -> BigInt("30200073", 16) // mret
        )), count = 2)

        val ecall = events.find(_.instruction == BigInt("00000073", 16)).get
        assert(ecall.trap && !ecall.interrupt && ecall.cause == 11 &&
          ecall.pc == ResetVector + 12)
        val mtvecWrite = events.find(_.instruction == BigInt("30509073", 16)).get
        assert(mtvecWrite.csrWrite && mtvecWrite.csrAddress == 0x305 &&
          mtvecWrite.csrData == handler)
        val mepcWrite = events.find(_.instruction == BigInt("34111073", 16)).get
        assert(mepcWrite.csrWrite && mepcWrite.csrAddress == 0x341 &&
          mepcWrite.csrData == ResetVector + 16)
        val mret = events.find(_.instruction == BigInt("30200073", 16)).get
        assert(!mret.trap)
        assert(events.exists(event => event.pc == ResetVector + 16 &&
          event.gprWrite && event.gprAddress == 3 && event.gprData == 3))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("serializes FENCE and redirects FENCE.I to its architectural successor") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("0000000f", 16), // fence
          ResetVector + 4 -> BigInt("0000100f", 16), // fence.i
          ResetVector + 8 -> BigInt("00100093", 16), // addi x1,x0,1
          ResetVector + 12 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12))
        assert(events(0).instruction == BigInt("0000000f", 16) &&
          !events(0).trap)
        assert(events(1).instruction == BigInt("0000100f", 16) &&
          !events(1).trap)
        assert(events(2).gprWrite && events(2).gprAddress == 1 &&
          events(2).gprData == 1)
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("takes a pending software interrupt at the live head then returns through MRET") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val handler = ResetVector + 64
        val events = throughTrap(runProgram(
          dut,
          Map(
            ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
            ResetVector + 4 -> BigInt("04008093", 16), // addi x1,x1,64
            ResetVector + 8 -> BigInt("30509073", 16), // csrw mtvec,x1
            ResetVector + 12 -> BigInt("30445073", 16), // csrrwi x0,mie,8
            ResetVector + 16 -> BigInt("30045073", 16), // csrrwi x0,mstatus,8
            ResetVector + 20 -> BigInt("00300193", 16), // interrupted addi x3,x0,3
            ResetVector + 24 -> BigInt("00100073", 16), // ebreak
            handler -> BigInt("30200073", 16) // mret
          ),
          cycles = 192,
          driveInterrupts = (core, observed) => core.io.interrupts.msip.poke(
            !observed.exists(event => event.trap && event.interrupt))
        ), count = 2)

        val interrupt = events.find(event => event.trap && event.interrupt).get
        assert(interrupt.cause == BigInt("80000003", 16) &&
          interrupt.pc == ResetVector + 20 && interrupt.trapValue == 0)
        val mret = events.find(_.instruction == BigInt("30200073", 16)).get
        assert(!mret.trap && mret.pc == handler)
        assert(events.exists(event => event.pc == ResetVector + 20 &&
          event.gprWrite && event.gprAddress == 3 && event.gprData == 3))
        assert(events.last.trap && !events.last.interrupt && events.last.cause == 3)
      }
    }

    it("preserves AXI instruction traffic through deterministic channel backpressure") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(
          dut,
          Map(
            ResetVector -> BigInt("00500093", 16), // addi x1,x0,5
            ResetVector + 4 -> BigInt("00308113", 16), // addi x2,x1,3
            ResetVector + 8 -> BigInt("00100073", 16) // ebreak
          ),
          cycles = 192,
          arReadyForCycle = cycle => cycle % 3 != 1,
          rValidForCycle = cycle => cycle % 4 != 2
        ))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8))
        assert(events(1).gprWrite && events(1).gprAddress == 2 &&
          events(1).gprData == 8)
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("executes dependent RV32M operations through E2 and preserves retire trace data") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00700093", 16), // addi x1,x0,7
          ResetVector + 4 -> BigInt("00300113", 16), // addi x2,x0,3
          ResetVector + 8 -> BigInt("022081b3", 16), // mul x3,x1,x2
          ResetVector + 12 -> BigInt("0221c233", 16), // div x4,x3,x2
          ResetVector + 16 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12, ResetVector + 16))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == 21)
        assert(events(3).gprWrite && events(3).gprAddress == 4 &&
          events(3).gprData == 7)
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("wakes an E1 consumer from an E2 integer completion") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00500093", 16), // addi x1,x0,5
          ResetVector + 4 -> BigInt("00600113", 16), // addi x2,x0,6
          ResetVector + 8 -> BigInt("022081b3", 16), // mul x3,x1,x2
          ResetVector + 12 -> BigInt("00b18213", 16), // addi x4,x3,11
          ResetVector + 16 -> BigInt("00100073", 16) // ebreak
        )))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12, ResetVector + 16))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == 30)
        assert(events(3).gprWrite && events(3).gprAddress == 4 &&
          events(3).gprData == 41)
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("starts E0, E1, and E2 together then kills younger work on recovery") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        var observedThreeStarts = false
        val startMasks = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean, Boolean, Boolean)]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00700093", 16), // addi x1,x0,7
          ResetVector + 4 -> BigInt("00300113", 16), // addi x2,x0,3
          ResetVector + 8 -> BigInt("0220c1b3", 16), // div x3,x1,x2
          ResetVector + 12 -> BigInt("00318663", 16), // beq x3,x3,+12
          ResetVector + 16 -> BigInt("00118213", 16), // wrong-path addi x4,x3,1
          ResetVector + 20 -> BigInt("022182b3", 16), // wrong-path mul x5,x3,x2
          ResetVector + 24 -> BigInt("00218313", 16), // addi x6,x3,2
          ResetVector + 28 -> BigInt("00100073", 16) // ebreak
        ), cycles = 192, observeCycle = (core, cycle) => {
          val observation = core.io.m2Observation.get
          val e0Start = observation.e0Start.peek().litToBoolean
          val e1Start = observation.e1Start.peek().litToBoolean
          val e2Start = observation.e2Start.peek().litToBoolean
          if (e0Start || e1Start || e2Start) {
            startMasks += ((cycle, e0Start, e1Start, e2Start))
          }
          observedThreeStarts ||= e0Start && e1Start && e2Start
        }))

        assert(observedThreeStarts,
          s"the frozen E0/E1/E2 three-start contract was not observed: $startMasks")
        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12, ResetVector + 24, ResetVector + 28))
        assert(events(4).gprWrite && events(4).gprAddress == 6 &&
          events(4).gprData == 4)
        assert(!events.exists(_.instruction == BigInt("00118213", 16)))
        assert(!events.exists(_.instruction == BigInt("022182b3", 16)))
      }
    }

    it("accepts simultaneous E1/E2 completions and retires their ordered pair") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        var observedTwoCompletions = false
        var observedDualRetirement = false
        val completionMasks = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean, Boolean)]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00500093", 16), // addi x1,x0,5
          ResetVector + 4 -> BigInt("00600113", 16), // addi x2,x0,6
          ResetVector + 8 -> BigInt("0220c1b3", 16), // div x3,x1,x2
          ResetVector + 12 -> BigInt("00118213", 16), // addi x4,x3,1
          ResetVector + 16 -> BigInt("022182b3", 16), // mul x5,x3,x2
          ResetVector + 20 -> BigInt("00120313", 16), // addi x6,x4,1
          ResetVector + 24 -> BigInt("00100073", 16) // ebreak
        ), cycles = 160, observeCycle = (core, cycle) => {
          val observation = core.io.m2Observation.get
          val e1Completion = observation.e1Completion.peek().litToBoolean
          val e2Completion = observation.e2Completion.peek().litToBoolean
          if (e1Completion || e2Completion) {
            completionMasks += ((cycle, e1Completion, e2Completion))
          }
          observedTwoCompletions ||= e1Completion && e2Completion
          observedDualRetirement ||= core.io.trace.get(0).valid.peek().litToBoolean &&
            core.io.trace.get(1).valid.peek().litToBoolean
        }))

        assert(observedTwoCompletions,
          s"E1 and E2 never used the two completion ports in the same cycle: $completionMasks")
        assert(observedDualRetirement,
          "the contiguous E2/E1 pair did not retire through both commit lanes")
        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12, ResetVector + 16, ResetVector + 20,
          ResetVector + 24))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == 0)
        assert(events(3).gprWrite && events(3).gprAddress == 4 &&
          events(3).gprData == 1)
        assert(events(4).gprWrite && events(4).gprAddress == 5 &&
          events(4).gprData == 0)
        assert(events(5).gprWrite && events(5).gprAddress == 6 &&
          events(5).gprData == 2)
      }
    }

    it("preserves RV32M recovery under explicitly seeded AXI backpressure") {
      for (seed <- M2RecoveryBackpressureSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
          enableM2Observation = true))) { dut =>
          clearInputs(dut)
          val cycles = 384
          val (arReady, rValid) = seededBackpressure(seed, cycles)
          var observedThreeStarts = false
          val events = runProgram(dut, Map(
            ResetVector -> BigInt("00700093", 16), // addi x1,x0,7
            ResetVector + 4 -> BigInt("00300113", 16), // addi x2,x0,3
            ResetVector + 8 -> BigInt("0220c1b3", 16), // div x3,x1,x2
            ResetVector + 12 -> BigInt("00318663", 16), // beq x3,x3,+12
            ResetVector + 16 -> BigInt("00118213", 16), // wrong-path addi x4,x3,1
            ResetVector + 20 -> BigInt("022182b3", 16), // wrong-path mul x5,x3,x2
            ResetVector + 24 -> BigInt("00218313", 16), // addi x6,x3,2
            ResetVector + 28 -> BigInt("00100073", 16) // ebreak
          ), cycles = cycles, arReadyForCycle = cycle => arReady(cycle),
            rValidForCycle = cycle => rValid(cycle),
            observeCycle = (core, _) => {
              val observation = core.io.m2Observation.get
              observedThreeStarts ||= observation.e0Start.peek().litToBoolean &&
                observation.e1Start.peek().litToBoolean &&
                observation.e2Start.peek().litToBoolean
            })

          try {
            val retired = throughFirstTrap(events)
            withClue(s"seed=$seed") {
              assert(observedThreeStarts)
              assert(retired.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12, ResetVector + 24, ResetVector + 28))
              assert(retired(4).gprWrite && retired(4).gprAddress == 6 &&
                retired(4).gprData == 4)
              assert(!retired.exists(_.instruction == BigInt("00118213", 16)))
              assert(!retired.exists(_.instruction == BigInt("022182b3", 16)))
            }
          } catch {
            case failure: Throwable =>
              saveM2RecoveryFailure(seed, arReady, rValid, events)
              throw failure
          }
        }
      }
    }

    it("recovers a taken E2-dependent branch without retiring its wrong-path divide") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00700093", 16), // addi x1,x0,7
          ResetVector + 4 -> BigInt("00300113", 16), // addi x2,x0,3
          ResetVector + 8 -> BigInt("0220c1b3", 16), // div x3,x1,x2
          ResetVector + 12 -> BigInt("00318663", 16), // beq x3,x3,+12
          ResetVector + 16 -> BigInt("0220c233", 16), // wrong-path div x4,x1,x2
          ResetVector + 20 -> BigInt("06300213", 16), // wrong-path addi x4,x0,99
          ResetVector + 24 -> BigInt("00118293", 16), // addi x5,x3,1
          ResetVector + 28 -> BigInt("00100073", 16) // ebreak
        ), cycles = 192))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
          ResetVector + 8, ResetVector + 12, ResetVector + 24, ResetVector + 28))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == 2)
        assert(events(4).gprWrite && events(4).gprAddress == 5 &&
          events(4).gprData == 3)
        assert(!events.exists(_.instruction == BigInt("0220c233", 16)))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("blocks a legal LSU instruction until the M3 endpoint exists") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = runProgram(dut, Map(
          ResetVector -> BigInt("00002083", 16), // lw x1,0(x0)
          ResetVector + 4 -> BigInt("00100073", 16)
        ), cycles = 96)

        assert(events.isEmpty,
          "a load reached retire before a real LSU completion path existed")
      }
    }
  }
}
