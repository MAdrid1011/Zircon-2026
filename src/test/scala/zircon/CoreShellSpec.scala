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
      trapValue: BigInt,
      memoryAddress: BigInt,
      memoryReadMask: BigInt,
      memoryReadData: BigInt,
      memoryWriteMask: BigInt,
      memoryWriteData: BigInt
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
    dut.io.hostFlush.foreach { control =>
      control.enable.poke(false)
      control.address.poke(0)
    }
  }

  private def sendInstructionPacket(dut: ZirconCore, words: Seq[BigInt],
      responses: Seq[Int] = Seq.empty): Unit = {
    require(words.length <= 8)
    (0 until 8).foreach { index =>
      dut.io.axi.r.valid.poke(true)
      dut.io.axi.r.bits.id.poke(1)
      dut.io.axi.r.bits.data.poke(words.lift(index).getOrElse(Nop))
      dut.io.axi.r.bits.resp.poke(responses.lift(index).getOrElse(0))
      dut.io.axi.r.bits.last.poke(index == 7)
      dut.io.axi.r.ready.expect(true)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false)
    }
  }

  /** Drives deterministic AXI memory with per-request ID-preserving responses.
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
      rResponse: (BigInt, BigInt) => Int = (_, _) => 0,
      writeResponse: Option[Int] = None,
      observeCycle: (ZirconCore, Int) => Unit = (_, _) => ()
  ): Seq[TraceSample] = {
    val pendingReads = scala.collection.mutable.Queue.empty[(BigInt, BigInt, BigInt, Boolean)]
    val events = scala.collection.mutable.ArrayBuffer.empty[TraceSample]
    var awSeen = false
    var wLastSeen = false
    var bQueued = false
    var writeId = BigInt(5)

    dut.clock.step(128) // Deterministic bimodal/BTB scrubs.
    for (cycle <- 0 until cycles) {
      driveInterrupts(dut, events.toSeq)
      dut.io.axi.ar.ready.poke(arReadyForCycle(cycle))
      dut.io.axi.aw.ready.poke(true)
      dut.io.axi.w.ready.poke(true)
      val rOffered = pendingReads.nonEmpty && rValidForCycle(cycle)
      if (rOffered) {
        val (id, address, data, last) = pendingReads.front
        dut.io.axi.r.valid.poke(true)
        dut.io.axi.r.bits.id.poke(id)
        dut.io.axi.r.bits.data.poke(data)
        dut.io.axi.r.bits.resp.poke(rResponse(id, address))
        dut.io.axi.r.bits.last.poke(last)
      } else {
        dut.io.axi.r.valid.poke(false)
      }
      dut.io.axi.b.valid.poke(writeResponse.nonEmpty && bQueued)
      dut.io.axi.b.bits.id.poke(writeId)
      dut.io.axi.b.bits.resp.poke(writeResponse.getOrElse(0))

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
            trapValue = event.trapValue.peek().litValue,
            memoryAddress = event.memoryAddress.peek().litValue,
            memoryReadMask = event.memoryReadMask.peek().litValue,
            memoryReadData = event.memoryReadData.peek().litValue,
            memoryWriteMask = event.memoryWriteMask.peek().litValue,
            memoryWriteData = event.memoryWriteData.peek().litValue
          )
        }
      }

      val arFire = dut.io.axi.ar.valid.peek().litToBoolean &&
        dut.io.axi.ar.ready.peek().litToBoolean
      val arAddress = dut.io.axi.ar.bits.addr.peek().litValue
      val arId = dut.io.axi.ar.bits.id.peek().litValue
      val arBeats = dut.io.axi.ar.bits.len.peek().litValue.toInt + 1
      val rFire = rOffered && dut.io.axi.r.ready.peek().litToBoolean
      val awFire = dut.io.axi.aw.valid.peek().litToBoolean &&
        dut.io.axi.aw.ready.peek().litToBoolean
      val awId = dut.io.axi.aw.bits.id.peek().litValue
      val wFire = dut.io.axi.w.valid.peek().litToBoolean &&
        dut.io.axi.w.ready.peek().litToBoolean
      val wLast = dut.io.axi.w.bits.last.peek().litToBoolean
      val bFire = writeResponse.nonEmpty && bQueued &&
        dut.io.axi.b.ready.peek().litToBoolean

      observeCycle(dut, cycle)
      dut.clock.step()

      if (rFire) {
        pendingReads.dequeue()
      }
      if (arFire) {
        for (beat <- 0 until arBeats) {
          val address = arAddress + beat * 4
          pendingReads.enqueue((arId, address, program.getOrElse(address, Nop),
            beat == arBeats - 1))
        }
      }
      if (awFire) {
        awSeen = true
        writeId = awId
      }
      if (wFire && wLast) wLastSeen = true
      if (writeResponse.nonEmpty && !bQueued && awSeen && wLastSeen) {
        bQueued = true
      }
      if (bFire) {
        bQueued = false
        awSeen = false
        wLastSeen = false
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

  /** RV32A remains outside the current M0 slice and must not borrow the device
    * or L1D owner while its reservation/read-modify-write lifecycle is absent.
    */
  private def assertBlockedAtomicLoad(
      dut: ZirconCore,
      baseInstruction: BigInt,
      memoryInstruction: BigInt,
      name: String
  ): Unit = {
    clearInputs(dut)
    var lsuIngress = false
    var dataReadAddress = false
    val events = runProgram(dut, Map(
      ResetVector -> baseInstruction,
      ResetVector + 4 -> memoryInstruction,
      ResetVector + 8 -> BigInt("00100073", 16)
    ), cycles = 192, observeCycle = (core, _) => {
      val observation = core.io.m2Observation.get
      lsuIngress ||= observation.m0Ingress.peek().litToBoolean ||
        observation.m1Ingress.peek().litToBoolean
      val dataAr = core.io.axi.ar.valid.peek().litToBoolean &&
        core.io.axi.ar.ready.peek().litToBoolean &&
        core.io.axi.ar.bits.id.peek().litValue != 0
      dataReadAddress ||= dataAr
    })

    withClue(s"$name trace=$events") {
      assert(lsuIngress, s"$name never reached the dual-LSU ownership path")
      assert(!dataReadAddress, s"$name incorrectly issued a data AXI read")
      assert(events.map(_.instruction) == Seq(baseInstruction),
        s"$name created a false memory or following-instruction retirement")
    }
  }

  private def assertDeviceLoad(
      dut: ZirconCore,
      baseInstruction: BigInt,
      deviceAddress: BigInt,
      name: String
  ): Unit = {
    clearInputs(dut)
    val deviceData = BigInt("44332211", 16)
    val deviceReads = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    var cacheReadSeen = false
    val loadInstruction = BigInt("0000a103", 16) // lw x2,0(x1)
    val events = throughFirstTrap(runProgram(dut, Map(
      ResetVector -> baseInstruction,
      ResetVector + 4 -> loadInstruction,
      ResetVector + 8 -> BigInt("00100073", 16),
      deviceAddress -> deviceData
    ), cycles = 128, observeCycle = (core, _) => {
      val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
        core.io.axi.ar.ready.peek().litToBoolean
      if (arFire && core.io.axi.ar.bits.id.peek().litValue == 6) {
        deviceReads += ((core.io.axi.ar.bits.addr.peek().litValue,
          core.io.axi.ar.bits.len.peek().litValue))
      }
      cacheReadSeen ||= arFire && core.io.axi.ar.bits.id.peek().litValue >= 1 &&
        core.io.axi.ar.bits.id.peek().litValue <= 4 &&
        core.io.axi.ar.bits.addr.peek().litValue == deviceAddress
    }))

    withClue(s"$name trace=$events deviceReads=$deviceReads") {
      assert(deviceReads.toSeq == Seq((deviceAddress, BigInt(0))),
        s"$name did not issue exactly one ID-6 single-beat read")
      assert(!cacheReadSeen, s"$name incorrectly issued an L1D refill")
      assert(events.map(_.instruction) == Seq(baseInstruction, loadInstruction,
        BigInt("00100073", 16)))
      assert(events(1).gprWrite && events(1).gprAddress == 2 &&
        events(1).gprData == deviceData)
      assert(events(1).memoryAddress == deviceAddress &&
        events(1).memoryReadMask == 15 && events(1).memoryReadData == deviceData)
      assert(events.last.trap && events.last.cause == 3)
    }
  }

  describe("ZirconCore executable M1-M3 integration") {
    it("executes an AXI-fed RV32I dependency chain and emits precise retire events") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)

        dut.io.axi.aw.valid.expect(false)
        dut.io.axi.w.valid.expect(false)
        dut.io.axi.ar.valid.expect(false)
        dut.io.trace.get.foreach(_.valid.expect(false))

        dut.clock.step(128)
        var arWaitCycles = 0
        while (!dut.io.axi.ar.valid.peek().litToBoolean && arWaitCycles < 8) {
          dut.clock.step()
          arWaitCycles += 1
        }
        dut.io.axi.ar.valid.expect(true)
        dut.io.axi.ar.bits.id.expect(1)
        dut.io.axi.ar.bits.addr.expect(ResetVector)
        dut.io.axi.ar.bits.len.expect(7)
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
        assert(dut.io.hostFlush.isEmpty)
      }
    }

    it("turns an AXI instruction RRESP error into a precise fetch-fault trap event") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        dut.clock.step(128)
        dut.clock.step(3)
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

    it("write-allocates a cacheable store without an external single-beat write") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val storeAddress = BigInt("80000100", 16)
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("00000097", 16), // auipc x1,0
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("1020a023", 16), // sw x2,256(x1)
          ResetVector + 12 -> BigInt("00100073", 16) // ebreak
        )
        var externalWrite = false
        val events = runProgram(dut, program, cycles = 192,
          observeCycle = (core, _) => {
            externalWrite ||= core.io.axi.aw.valid.peek().litToBoolean ||
              core.io.axi.w.valid.peek().litToBoolean
          })
        assert(!externalWrite,
          "a cacheable store must retain dirty ownership in L1D, not issue ID 5")
        assert(events.exists(event => event.pc == ResetVector + 8 &&
          event.memoryAddress == storeAddress && event.memoryWriteMask == 15 &&
          event.memoryWriteData == 90 && !event.trap),
          s"missing exact store retire trace: $events")
      }
    }

    it("keeps a cacheable store local when the AXI model would fail a write") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("00000097", 16), // auipc x1,0
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("1020a023", 16), // sw x2,256(x1)
          ResetVector + 12 -> BigInt("00100073", 16)
        )
        var externalWrite = false
        val events = runProgram(dut, program, cycles = 192, writeResponse = Some(2),
          observeCycle = (core, _) => {
            externalWrite ||= core.io.axi.aw.valid.peek().litToBoolean ||
              core.io.axi.w.valid.peek().litToBoolean
          })
        assert(!externalWrite, "cacheable store unexpectedly reached the write channel")
        assert(events.exists(event => event.pc == ResetVector + 8 && !event.trap),
          s"cacheable store did not retire locally: $events")
      }
    }

    it("delays a trace-selected cacheable store retirement until its ID-5 writeback") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableHostFlush = true))) { dut =>
        clearInputs(dut)
        val storeAddress = BigInt("80000100", 16)
        dut.io.hostFlush.get.enable.poke(true)
        dut.io.hostFlush.get.address.poke(storeAddress)
        val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val writebackWords = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var id5BSeen = false
        var storeRetiredBeforeB = false
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("00000097", 16), // auipc x1,0
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("1020a023", 16), // sw x2,256(x1)
          ResetVector + 12 -> BigInt("00100073", 16) // ebreak
        )
        val events = throughFirstTrap(runProgram(dut, program, cycles = 384,
          writeResponse = Some(0), observeCycle = (core, _) => {
            val storeRetired = core.io.trace.get.exists(lane =>
              lane.valid.peek().litToBoolean && lane.pc.peek().litValue == ResetVector + 8)
            storeRetiredBeforeB ||= storeRetired && !id5BSeen
            val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean
            val wFire = core.io.axi.w.valid.peek().litToBoolean &&
              core.io.axi.w.ready.peek().litToBoolean
            if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
              writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
              core.io.axi.aw.bits.len.expect(7)
            }
            if (wFire && writebackAddresses.nonEmpty) {
              writebackWords += core.io.axi.w.bits.data.peek().litValue
            }
            val bFire = core.io.axi.b.valid.peek().litToBoolean &&
              core.io.axi.b.ready.peek().litToBoolean &&
              core.io.axi.b.bits.id.peek().litValue == 5
            id5BSeen ||= bFire
          }))

        withClue(s"host-flush trace=$events writes=$writebackAddresses/$writebackWords") {
          assert(writebackAddresses.toSeq == Seq(storeAddress))
          assert(writebackWords.toSeq == Seq(BigInt(90)) ++ Seq.fill(7)(Nop))
          assert(id5BSeen)
          assert(!storeRetiredBeforeB, "store retired before its successful ID-5 B response")
          assert(events.exists(event => event.pc == ResetVector + 8 &&
            event.memoryAddress == storeAddress && event.memoryWriteMask == 15 &&
            event.memoryWriteData == 90 && !event.trap))
        }
      }
    }

    it("writes back a dirty L2 victim through one eight-beat ID-5 burst") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val firstAddress = BigInt("80000100", 16)
        val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val writebackWords = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("10008193", 16), // addi x3,x1,256
          ResetVector + 12 -> BigInt("0021a023", 16), // sw x2,0(x3)
          ResetVector + 16 -> BigInt("40018193", 16), // addi x3,x3,1024
          ResetVector + 20 -> BigInt("0021a023", 16),
          ResetVector + 24 -> BigInt("40018193", 16),
          ResetVector + 28 -> BigInt("0021a023", 16),
          ResetVector + 32 -> BigInt("40018193", 16),
          ResetVector + 36 -> BigInt("0021a023", 16),
          ResetVector + 40 -> BigInt("40018193", 16),
          ResetVector + 44 -> BigInt("0021a023", 16),
          ResetVector + 48 -> BigInt("40018193", 16),
          ResetVector + 52 -> BigInt("0021a023", 16),
          ResetVector + 56 -> BigInt("40018193", 16),
          ResetVector + 60 -> BigInt("0021a023", 16),
          ResetVector + 64 -> BigInt("00100073", 16)
        )
        val events = throughFirstTrap(runProgram(dut, program, cycles = 1024,
          writeResponse = Some(0), observeCycle = (core, _) => {
            val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean
            val wFire = core.io.axi.w.valid.peek().litToBoolean &&
              core.io.axi.w.ready.peek().litToBoolean
            if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
              writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
              core.io.axi.aw.bits.len.expect(7)
              core.io.axi.aw.bits.size.expect(2)
            }
            if (wFire && writebackAddresses.nonEmpty) {
              writebackWords += core.io.axi.w.bits.data.peek().litValue
            }
          }))

        assert(writebackAddresses.toSeq == Seq(firstAddress),
          s"dirty L2 replacement did not issue exactly one ID-5 writeback: $writebackAddresses")
        assert(writebackWords.toSeq == Seq(BigInt(90)) ++ Seq.fill(7)(Nop),
          s"ID-5 burst did not preserve the dirty L1D word and refill payload: $writebackWords")
        assert(events.count(_.memoryWriteMask == 15) == 7,
          s"not every committed store retained exact retire metadata: $events")
        assert(events.last.trap && events.last.cause == 3)
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

    it("takes an inaccessible load through the M0 replay fault path") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        val activity = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean, Boolean,
          Boolean, Boolean, BigInt, BigInt)]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("00002083", 16), // lw x1,0(x0)
          ResetVector + 4 -> BigInt("00100073", 16)
        ), cycles = 96, observeCycle = (core, cycle) => {
          val observation = core.io.m2Observation.get
          val m0Ingress = observation.m0Ingress.peek().litToBoolean
          val m1Ingress = observation.m1Ingress.peek().litToBoolean
          val m0Fault = observation.m0Fault.peek().litToBoolean
          val m1Fault = observation.m1Fault.peek().litToBoolean
          if (m0Ingress || m1Ingress || m0Fault || m1Fault) {
            activity += ((cycle, m0Ingress, m1Ingress, m0Fault, m1Fault,
              observation.m0FaultTag.peek().litValue,
              observation.robHeadTag.peek().litValue))
          }
        }))

        withClue(s"LSU activity=$activity, trace=$events") {
          assert(events.size == 1)
        }
        assert(events.head.trap && events.head.cause == 5 &&
          events.head.trapValue == 0 && events.head.pc == ResetVector)
      }
    }

    it("executes a DeviceStrong load on ID 6 without an L1D refill") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        assertDeviceLoad(dut,
          baseInstruction = BigInt("a00000b7", 16), // lui x1,0xa0000
          deviceAddress = BigInt("a0000000", 16),
          name = "DeviceStrong load")
      }
    }

    it("executes a DeviceBurstable load on ID 6 without an L1D refill") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        assertDeviceLoad(dut,
          baseInstruction = BigInt("b00000b7", 16), // lui x1,0xb0000
          deviceAddress = BigInt("b0000000", 16),
          name = "DeviceBurstable load")
      }
    }

    it("turns a DeviceStrong RRESP error into an exact cause-5 trap") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val deviceAddress = BigInt("a0000000", 16)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("a00000b7", 16), // lui x1,0xa0000
          ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 8 -> BigInt("00100073", 16)
        ), cycles = 128, rResponse = (id, _) => if (id == 6) 2 else 0))
        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4))
        assert(events(1).trap && !events(1).gprWrite && events(1).cause == 5 &&
          events(1).trapValue == deviceAddress)
      }
    }

    it("executes a DeviceStrong store through ID 6 and retires exact metadata") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val deviceAddress = BigInt("a0000000", 16)
        var awSeen = false
        var wSeen = false
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("a00000b7", 16), // lui x1,0xa0000
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
          ResetVector + 12 -> BigInt("00100073", 16)
        ), cycles = 192, writeResponse = Some(0), observeCycle = (core, _) => {
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          val wFire = core.io.axi.w.valid.peek().litToBoolean &&
            core.io.axi.w.ready.peek().litToBoolean
          if (awFire) {
            core.io.axi.aw.bits.id.expect(6)
            core.io.axi.aw.bits.addr.expect(deviceAddress)
            core.io.axi.aw.bits.len.expect(0)
            core.io.axi.aw.bits.cache.expect(0)
            awSeen = true
          }
          if (wFire) {
            core.io.axi.w.bits.data.expect(90)
            core.io.axi.w.bits.strb.expect(15)
            core.io.axi.w.bits.last.expect(true)
            wSeen = true
          }
        }))

        withClue(s"device-store trace=$events") {
          assert(awSeen && wSeen)
          assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
            ResetVector + 8, ResetVector + 12))
          assert(events(2).memoryAddress == deviceAddress &&
            events(2).memoryWriteMask == 15 && events(2).memoryWriteData == 90)
          assert(events.last.trap && events.last.cause == 3)
        }
      }
    }

    it("turns a DeviceBurstable store BRESP error into an exact cause-7 trap") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val deviceAddress = BigInt("b0000000", 16)
        val events = runProgram(dut, Map(
          ResetVector -> BigInt("b00000b7", 16), // lui x1,0xb0000
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
          ResetVector + 12 -> BigInt("00100073", 16)
        ), cycles = 192, writeResponse = Some(2))
        val trap = events.find(event => event.trap && event.pc == ResetVector + 8)
        assert(trap.exists(event => event.cause == 7 &&
          event.trapValue == deviceAddress), s"missing device BRESP trap in $events")
      }
    }

    it("merges four consecutive DeviceBurstable loads into one ID-6 read group") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        val address = BigInt("b0000000", 16)
        val values = Seq(BigInt("11111111", 16), BigInt("22222222", 16),
          BigInt("33333333", 16), BigInt("44444444", 16))
        val groups = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
        val lsuIngressCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
        val groupPreviews = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("b00000b7", 16), // lui x1,0xb0000
          ResetVector + 4 -> BigInt("02004333", 16), // div x6,x0,x0
          ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 12 -> BigInt("0040a183", 16), // lw x3,4(x1)
          ResetVector + 16 -> BigInt("0080a203", 16), // lw x4,8(x1)
          ResetVector + 20 -> BigInt("00c0a283", 16), // lw x5,12(x1)
          ResetVector + 24 -> BigInt("00100073", 16), // ebreak
          address -> values(0),
          address + 4 -> values(1),
          address + 8 -> values(2),
          address + 12 -> values(3)
        ), cycles = 256, observeCycle = (core, cycle) => {
          if (core.io.m2Observation.get.m0Ingress.peek().litToBoolean ||
              core.io.m2Observation.get.m1Ingress.peek().litToBoolean) {
            lsuIngressCycles += cycle
          }
          val observation = core.io.m2Observation.get
          if (observation.orderedGroupValid.peek().litToBoolean) {
            groupPreviews += ((cycle, observation.loadQueueCount.peek().litValue,
              observation.orderedGroupCount.peek().litValue))
          }
          val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
            core.io.axi.ar.ready.peek().litToBoolean
          if (arFire && core.io.axi.ar.bits.id.peek().litValue == 6) {
            groups += ((core.io.axi.ar.bits.addr.peek().litValue,
              core.io.axi.ar.bits.len.peek().litValue))
          }
        }))

        assert(groups.toSeq == Seq((address, BigInt(3))),
          s"expected one four-beat DeviceBurstable AR: groups=$groups " +
            s"lsuIngressCycles=$lsuIngressCycles groupPreviews=$groupPreviews events=$events")
        for (member <- values.indices) {
          val event = events.find(_.pc == ResetVector + 8 + member * 4).get
          assert(event.gprWrite && event.gprAddress == member + 2 &&
            event.gprData == values(member))
          assert(event.memoryAddress == address + member * 4 &&
            event.memoryReadMask == 15 && event.memoryReadData == values(member))
        }
      }
    }

    it("merges four consecutive DeviceBurstable stores into one ID-6 write group") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        val address = BigInt("b0000000", 16)
        val writeData = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var awGroup = Option.empty[(BigInt, BigInt)]
        val busActivity = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean, Boolean,
          Boolean, Boolean, Boolean)]
        val groupPreviews = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)]
        val events = runProgram(dut, Map(
          ResetVector -> BigInt("b00000b7", 16), // lui x1,0xb0000
          ResetVector + 4 -> BigInt("01100113", 16), // addi x2,x0,17
          ResetVector + 8 -> BigInt("02200193", 16), // addi x3,x0,34
          ResetVector + 12 -> BigInt("03300213", 16), // addi x4,x0,51
          ResetVector + 16 -> BigInt("04400293", 16), // addi x5,x0,68
          ResetVector + 20 -> BigInt("02004333", 16), // div x6,x0,x0
          ResetVector + 24 -> BigInt("0020a023", 16), // sw x2,0(x1)
          ResetVector + 28 -> BigInt("0030a223", 16), // sw x3,4(x1)
          ResetVector + 32 -> BigInt("0040a423", 16), // sw x4,8(x1)
          ResetVector + 36 -> BigInt("0050a623", 16), // sw x5,12(x1)
          ResetVector + 40 -> BigInt("00100073", 16) // ebreak
        ), cycles = 320, writeResponse = Some(0), observeCycle = (core, cycle) => {
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          val wFire = core.io.axi.w.valid.peek().litToBoolean &&
            core.io.axi.w.ready.peek().litToBoolean
          val wLast = core.io.axi.w.bits.last.peek().litToBoolean
          val bValid = core.io.axi.b.valid.peek().litToBoolean
          val bReady = core.io.axi.b.ready.peek().litToBoolean
          if (awFire || wFire || bValid) {
            busActivity += ((cycle, awFire, wFire, wLast, bValid, bReady))
          }
          val observation = core.io.m2Observation.get
          if (observation.orderedGroupValid.peek().litToBoolean) {
            groupPreviews += ((cycle, observation.storeQueueCount.peek().litValue,
              observation.orderedGroupCount.peek().litValue))
          }
          if (awFire && core.io.axi.aw.bits.id.peek().litValue == 6) {
            awGroup = Some((core.io.axi.aw.bits.addr.peek().litValue,
              core.io.axi.aw.bits.len.peek().litValue))
          }
          if (wFire && core.io.axi.w.bits.last.peek().litToBoolean) {
            core.io.axi.w.bits.strb.expect(15)
          }
          if (wFire) writeData += core.io.axi.w.bits.data.peek().litValue
        })

        assert(awGroup.contains((address, BigInt(3))),
          s"expected one four-beat DeviceBurstable AW: aw=$awGroup " +
            s"busActivity=$busActivity groupPreviews=$groupPreviews events=$events")
        assert(writeData.toSeq == Seq(BigInt(17), BigInt(34), BigInt(51), BigInt(68)))
        assert(events.exists(event => event.trap && event.cause == 3),
          s"four-beat DeviceBurstable write did not reach ebreak: " +
            s"busActivity=$busActivity events=$events")
        for (member <- 0 until 4) {
          val event = events.find(_.pc == ResetVector + 24 + member * 4).get
          assert(event.memoryAddress == address + member * 4 &&
            event.memoryWriteMask == 15 && event.memoryWriteData == writeData(member))
        }
      }
    }

    it("takes an interrupt before an unaccepted device load, then reexecutes it") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val handler = ResetVector + 64
        var interruptArmed = false
        var interruptTaken = false
        var id6BeforeInterrupt = 0
        var id6Requests = 0
        val events = throughTrap(runProgram(
          dut,
          Map(
            ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
            ResetVector + 4 -> BigInt("04008093", 16), // addi x1,x1,64
            ResetVector + 8 -> BigInt("30509073", 16), // csrw mtvec,x1
            ResetVector + 12 -> BigInt("30445073", 16), // csrrwi x0,mie,8
            ResetVector + 16 -> BigInt("30045073", 16), // csrrwi x0,mstatus,8
            ResetVector + 20 -> BigInt("a00000b7", 16), // lui x1,0xa0000
            ResetVector + 24 -> BigInt("0000a103", 16), // lw x2,0(x1)
            ResetVector + 28 -> BigInt("00100073", 16), // ebreak
            BigInt("a0000000", 16) -> BigInt("55667788", 16),
            handler -> BigInt("30200073", 16) // mret
          ),
          cycles = 256,
          driveInterrupts = (core, observed) => {
            interruptArmed = interruptArmed || observed.exists(_.pc == ResetVector + 20)
            interruptTaken = interruptTaken || observed.exists(event => event.trap && event.interrupt)
            core.io.interrupts.msip.poke(interruptArmed && !interruptTaken)
          },
          observeCycle = (core, _) => {
            val id6Ar = core.io.axi.ar.valid.peek().litToBoolean &&
              core.io.axi.ar.ready.peek().litToBoolean &&
              core.io.axi.ar.bits.id.peek().litValue == 6
            if (id6Ar) {
              id6Requests += 1
              if (interruptArmed && !interruptTaken) id6BeforeInterrupt += 1
            }
          }
        ), count = 2)

        val interrupt = events.find(event => event.trap && event.interrupt).get
        val mretIndex = events.indexWhere(_.instruction == BigInt("30200073", 16))
        val reexecutedLoad = events.indexWhere(event => event.pc == ResetVector + 24 &&
          event.gprWrite && event.gprAddress == 2 && event.gprData == BigInt("55667788", 16))
        assert(interrupt.pc == ResetVector + 24 &&
          interrupt.cause == BigInt("80000003", 16))
        assert(id6BeforeInterrupt == 0,
          s"device AR escaped before pending interrupt: events=$events")
        assert(id6Requests == 1, s"expected one post-MRET device read: events=$events")
        assert(mretIndex >= 0 && reexecutedLoad > mretIndex,
          s"device load did not reexecute after MRET: events=$events")
      }
    }

    it("defers an interrupt until an accepted device load retires") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val handler = ResetVector + 64
        var deviceReadAccepted = false
        var interruptTaken = false
        var id6Requests = 0
        val events = throughTrap(runProgram(
          dut,
          Map(
            ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
            ResetVector + 4 -> BigInt("04008093", 16), // addi x1,x1,64
            ResetVector + 8 -> BigInt("30509073", 16), // csrw mtvec,x1
            ResetVector + 12 -> BigInt("30445073", 16), // csrrwi x0,mie,8
            ResetVector + 16 -> BigInt("30045073", 16), // csrrwi x0,mstatus,8
            ResetVector + 20 -> BigInt("a00000b7", 16), // lui x1,0xa0000
            ResetVector + 24 -> BigInt("0000a103", 16), // lw x2,0(x1)
            ResetVector + 28 -> BigInt("00100073", 16), // ebreak
            BigInt("a0000000", 16) -> BigInt("10203040", 16),
            handler -> BigInt("30200073", 16) // mret
          ),
          cycles = 256,
          driveInterrupts = (core, observed) => {
            interruptTaken = interruptTaken || observed.exists(event => event.trap && event.interrupt)
            core.io.interrupts.msip.poke(deviceReadAccepted && !interruptTaken)
          },
          observeCycle = (core, _) => {
            val id6Ar = core.io.axi.ar.valid.peek().litToBoolean &&
              core.io.axi.ar.ready.peek().litToBoolean &&
              core.io.axi.ar.bits.id.peek().litValue == 6
            if (id6Ar) {
              deviceReadAccepted = true
              id6Requests += 1
            }
          }
        ), count = 2)

        val interruptIndex = events.indexWhere(event => event.trap && event.interrupt)
        val interrupt = events(interruptIndex)
        val loadIndex = events.indexWhere(event => event.pc == ResetVector + 24 &&
          event.gprWrite && event.gprAddress == 2 && event.gprData == BigInt("10203040", 16))
        assert(interrupt.pc == ResetVector + 28 &&
          interrupt.cause == BigInt("80000003", 16))
        assert(loadIndex >= 0 && loadIndex < interruptIndex,
          s"interrupt preempted accepted device read: events=$events")
        assert(id6Requests == 1, s"accepted device read was repeated: events=$events")
      }
    }

    it("executes response-gated LR/SC through the ID-7 atomic owner") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val atomicReads = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
        val atomicWrites = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("1000a12f", 16), // lr.w x2,(x1)
          ResetVector + 8 -> BigInt("1820a1af", 16), // sc.w x3,x2,(x1)
          ResetVector + 12 -> BigInt("00100073", 16)
        ), cycles = 256, writeResponse = Some(0), observeCycle = (core, _) => {
          val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
            core.io.axi.ar.ready.peek().litToBoolean
          if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) {
            atomicReads += ((core.io.axi.ar.bits.addr.peek().litValue,
              core.io.axi.ar.bits.len.peek().litValue))
          }
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) {
            atomicWrites += core.io.axi.aw.bits.addr.peek().litValue
          }
        }))

        assert(atomicReads.toSeq == Seq((ResetVector, BigInt(0))))
        assert(atomicWrites.toSeq == Seq(ResetVector))
        assert(events.map(_.instruction) == Seq(
          BigInt("800000b7", 16), BigInt("1000a12f", 16),
          BigInt("1820a1af", 16), BigInt("00100073", 16)))
        assert(events(1).gprWrite && events(1).gprAddress == 2 &&
          events(1).gprData == BigInt("800000b7", 16) &&
          events(1).memoryAddress == ResetVector && events(1).memoryReadMask == 15 &&
          events(1).memoryReadData == BigInt("800000b7", 16))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == 0 && events(2).memoryAddress == ResetVector &&
          events(2).memoryWriteMask == 15 &&
          events(2).memoryWriteData == BigInt("800000b7", 16))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("invalidates LR/SC reservation on a conflicting local store") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val writeIds = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("1000a12f", 16), // lr.w x2,(x1)
          ResetVector + 8 -> BigInt("0000a023", 16), // sw x0,0(x1)
          ResetVector + 12 -> BigInt("1820a1af", 16), // sc.w x3,x2,(x1)
          ResetVector + 16 -> BigInt("00100073", 16)
        ), cycles = 320, writeResponse = Some(0), observeCycle = (core, _) => {
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          if (awFire) writeIds += core.io.axi.aw.bits.id.peek().litValue
        }))

        assert(writeIds.isEmpty,
          s"reservation-clearing local store or failed SC issued an external write: $writeIds")
        assert(events(3).gprWrite && events(3).gprAddress == 3 &&
          events(3).gprData == 1 && events(3).memoryWriteMask == 0,
          s"SC failure did not preserve no-write retire metadata: ${events(3)}")
      }
    }

    it("returns the old AMO value only after its ID-7 read-modify-write response") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val atomicWriteData = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var activeWriteOwner = Option.empty[BigInt]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("00500113", 16), // addi x2,x0,5
          ResetVector + 8 -> BigInt("0020a1af", 16), // amoadd.w x3,x2,(x1)
          ResetVector + 12 -> BigInt("00100073", 16)
        ), cycles = 256, writeResponse = Some(0), observeCycle = (core, _) => {
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          val wFire = core.io.axi.w.valid.peek().litToBoolean &&
            core.io.axi.w.ready.peek().litToBoolean
          if (awFire) {
            assert(activeWriteOwner.isEmpty,
              s"a second AW arrived before WLAST for $activeWriteOwner")
            activeWriteOwner = Some(core.io.axi.aw.bits.id.peek().litValue)
          }
          if (wFire) {
            val owner = activeWriteOwner.getOrElse(
              fail("W handshake arrived without a preceding accepted AW"))
            if (owner == 7) {
              atomicWriteData += core.io.axi.w.bits.data.peek().litValue
            }
            if (core.io.axi.w.bits.last.peek().litToBoolean) {
              activeWriteOwner = None
            }
          }
        }))

        assert(atomicWriteData.toSeq == Seq(BigInt("800000bc", 16)))
        assert(events(2).gprWrite && events(2).gprAddress == 3 &&
          events(2).gprData == BigInt("800000b7", 16) &&
          events(2).memoryAddress == ResetVector && events(2).memoryReadMask == 15 &&
          events(2).memoryReadData == BigInt("800000b7", 16) &&
          events(2).memoryWriteMask == 15 &&
          events(2).memoryWriteData == BigInt("800000bc", 16))
      }
    }

    it("converts an atomic ID-7 RRESP failure into the exact store/AMO trap") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("0000a1af", 16), // amoadd.w x3,x0,(x1)
          ResetVector + 8 -> BigInt("00100073", 16)
        ), cycles = 192, rResponse = (id, _) => if (id == 7) 2 else 0))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4))
        assert(events(1).trap && !events(1).gprWrite && events(1).cause == 7 &&
          events(1).trapValue == ResetVector)
      }
    }

    it("clears an LR reservation across trap and MRET before a following SC") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val atomicWrites = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val handler = ResetVector + 64
        val events = throughTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("80000237", 16), // lui x4,0x80000
          ResetVector + 8 -> BigInt("04020213", 16), // addi x4,x4,64
          ResetVector + 12 -> BigInt("30521073", 16), // csrrw x0,mtvec,x4
          ResetVector + 16 -> BigInt("30445073", 16), // csrrwi x0,mie,8
          ResetVector + 20 -> BigInt("30045073", 16), // csrrwi x0,mstatus,8
          ResetVector + 24 -> BigInt("1000a12f", 16), // lr.w x2,(x1)
          ResetVector + 28 -> BigInt("1820a1af", 16), // sc.w x3,x2,(x1)
          ResetVector + 32 -> BigInt("00100073", 16), // ebreak
          handler -> BigInt("30200073", 16) // mret
        ), cycles = 512, driveInterrupts = (core, observed) => {
          val lrRetired = observed.exists(event => event.pc == ResetVector + 24 &&
            event.gprWrite && event.gprAddress == 2)
          val interruptTaken = observed.exists(event => event.trap && event.interrupt)
          core.io.interrupts.msip.poke(lrRetired && !interruptTaken)
        }, observeCycle = (core, _) => {
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) {
            atomicWrites += core.io.axi.aw.bits.addr.peek().litValue
          }
        }), count = 2)

        val sc = events.find(event => event.pc == ResetVector + 28 &&
          event.gprWrite && event.gprAddress == 3).getOrElse(
          fail(s"SC did not retire after MRET: $events"))
        assert(atomicWrites.isEmpty, s"SC after trap issued an atomic write: $atomicWrites")
        assert(sc.gprData == 1 && sc.memoryWriteMask == 0)
      }
    }

    it("holds a younger cacheable load behind an aq atomic until its read response") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        var atomicResponseCycle = -1
        var firstDataReadCycle = -1
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("1400a12f", 16), // lr.w.aq x2,(x1)
          ResetVector + 8 -> BigInt("0040a183", 16), // lw x3,4(x1)
          ResetVector + 12 -> BigInt("00100073", 16)
        ), cycles = 256, observeCycle = (core, cycle) => {
          val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
            core.io.axi.ar.ready.peek().litToBoolean
          if (atomicResponseCycle >= 0 && arFire &&
              core.io.axi.ar.bits.id.peek().litValue >= 1 &&
              core.io.axi.ar.bits.id.peek().litValue <= 4 && firstDataReadCycle < 0) {
            firstDataReadCycle = cycle
          }
          val rFire = core.io.axi.r.valid.peek().litToBoolean &&
            core.io.axi.r.ready.peek().litToBoolean
          if (rFire && core.io.axi.r.bits.id.peek().litValue == 7) {
            atomicResponseCycle = cycle
          }
        }))

        assert(events.exists(event => event.pc == ResetVector + 8 && event.gprWrite &&
          event.gprAddress == 3))
        assert(atomicResponseCycle >= 0 && firstDataReadCycle > atomicResponseCycle,
          s"aq atomic did not order the younger load: atomic R=$atomicResponseCycle, " +
            s"data AR=$firstDataReadCycle")
      }
    }

    it("executes a cacheable load through the data AXI refill path") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 8 -> BigInt("00100073", 16)
        ), cycles = 192))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4, ResetVector + 8))
        assert(events(1).gprWrite && events(1).gprAddress == 2 &&
          events(1).gprData == BigInt("800000b7", 16))
        assert(events(1).memoryAddress == ResetVector &&
          events(1).memoryReadMask == 15 &&
          events(1).memoryReadData == BigInt("800000b7", 16))
      }
    }

    it("serves an evicted L1D line from the exclusive L2 without another AXI refill") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val lineA = BigInt("80001000", 16)
        val lineB = BigInt("80001200", 16)
        val lineC = BigInt("80001400", 16)
        val dataReads = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("0000a083", 16), // lw x1,0(x1): A -> B
          ResetVector + 8 -> BigInt("0000a083", 16), // B -> C
          ResetVector + 12 -> BigInt("0000a083", 16), // C -> A, evicting A
          ResetVector + 16 -> BigInt("0000a103", 16), // lw x2,0(x1): L2 hit A
          ResetVector + 20 -> BigInt("00100073", 16),
          lineA -> lineB,
          lineB -> lineC,
          lineC -> lineA
        ), cycles = 384, observeCycle = (core, _) => {
          val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
            core.io.axi.ar.ready.peek().litToBoolean
          val id = core.io.axi.ar.bits.id.peek().litValue
          val address = core.io.axi.ar.bits.addr.peek().litValue
          if (arFire && id >= 1 && id <= 4 &&
              Set(lineA, lineB, lineC).contains(address)) {
            dataReads += address
          }
        }))

        assert(dataReads == Seq(lineA, lineB, lineC),
          s"final load should move the L2 line, not refill AXI again: $dataReads")
        val finalLoad = events.find(_.pc == ResetVector + 16).getOrElse(
          fail(s"final L2-served load did not retire: $events"))
        assert(finalLoad.gprWrite && finalLoad.gprAddress == 2 &&
          finalLoad.gprData == lineB)
        assert(finalLoad.memoryAddress == lineA &&
          finalLoad.memoryReadMask == 15 && finalLoad.memoryReadData == lineB)
      }
    }

    it("turns a data AXI RRESP error into the exact load-access trap") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 8 -> BigInt("00100073", 16)
        ), cycles = 192, rResponse = (_, address) =>
          if (address >= BigInt("80001000", 16) && address < BigInt("80001020", 16)) 2 else 0))

        assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4))
        assert(events(1).trap && !events(1).gprWrite && events(1).cause == 5 &&
          events(1).trapValue == BigInt("80001000", 16))
      }
    }
  }
}
