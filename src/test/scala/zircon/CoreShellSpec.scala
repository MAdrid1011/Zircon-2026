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
  private val M3AxiStressSeeds = Seq(0x5eed3004L, 0x5eed3005L,
    0x5eed3006L, 0x5eed3007L)
  private val M3AxiResetSeeds = Seq(0x5eed7001L, 0x5eed7002L,
    0x5eed7003L, 0x5eed7004L)
  private val M3AxiWritebackResetSeeds = Seq(0x5eed8001L, 0x5eed8002L,
    0x5eed8003L, 0x5eed8004L)
  private val M3AxiLongStreamSeeds = Seq(0x5eed9001L, 0x5eed9002L,
    0x5eed9003L, 0x5eed9004L)
  private val M3AxiFaultOrderSeeds = Seq(0x5eeda001L, 0x5eeda002L,
    0x5eeda003L, 0x5eeda004L)
  private val M3AxiMixedFaultSeeds = Seq(0x5eedb001L, 0x5eedb002L,
    0x5eedb003L, 0x5eedb004L)
  private val M3AxiFourFaultOrderSeeds = Seq(0x5eee0001L, 0x5eee0002L,
    0x5eee0003L, 0x5eee0004L)
  private val M3FencePressureSeeds = Seq(0x5eedc001L, 0x5eedc002L,
    0x5eedc003L, 0x5eedc004L)
  private val M3FenceRetrySeeds = Seq(0x5eedd001L, 0x5eedd002L,
    0x5eedd003L, 0x5eedd004L)
  private val M3AxiMixedTrafficSeeds = Seq(0x5eede001L, 0x5eede002L,
    0x5eede003L, 0x5eede004L)
  private val M3AxiLongMixedTrafficSeeds = Seq(0x5eedf101L, 0x5eedf102L,
    0x5eedf103L, 0x5eedf104L)
  private val M3AxiLongMixedRetrySeeds = Seq(0x5eedf201L, 0x5eedf202L,
    0x5eedf203L, 0x5eedf204L)
  private val M3AtomicMixedTrafficSeeds = Seq(0x5eedf301L, 0x5eedf302L,
    0x5eedf303L)
  private val M3AtomicRandomSeeds = Seq(0x5eedf401L, 0x5eedf402L,
    0x5eedf403L)
  private val M3LrScRandomSeeds = Seq(0x5eedf501L, 0x5eedf502L,
    0x5eedf503L)
  private val M3AtomicErrorSeeds = Seq(0x5eedf601L, 0x5eedf602L,
    0x5eedf603L)
  private val M3LrScInterruptSeeds = Seq(0x5eedf701L, 0x5eedf702L,
    0x5eedf703L)
  private val M3LrScErrorSeeds = Seq(0x5eedf801L, 0x5eedf802L,
    0x5eedf803L)
  private val M3ScErrorSeeds = Seq(0x5eedf901L, 0x5eedf902L,
    0x5eedf903L)
  private val M3LrScGranularitySeeds = Seq(0x5eedfa01L, 0x5eedfa02L,
    0x5eedfa03L)
  private val M3LrScReplacementSeeds = Seq(0x5eedfb01L, 0x5eedfb02L,
    0x5eedfb03L)
  private val M3DualLoadMergeSeeds = Seq(0x5eedfc01L, 0x5eedfc02L,
    0x5eedfc03L)
  private val M3PartialStoreForwardSeeds = Seq(0x5eedfd01L, 0x5eedfd02L,
    0x5eedfd03L)
  private val M3MshrPressureSeeds = Seq(0x5eedfe01L, 0x5eedfe02L,
    0x5eedfe03L, 0x5eedfe04L)
  private val M3OrderedIoTopSeeds = Seq(0x5eed0201L, 0x5eed0202L,
    0x5eed0203L, 0x5eed0204L)
  private val M3FenceFifoRetrySeeds = Seq(0x5eedf001L, 0x5eedf002L,
    0x5eedf003L, 0x5eedf004L)
  private val M3ExternalCoherenceReservationSeeds = Seq(0x5eedf501L,
    0x5eedf502L, 0x5eedf503L)

  private case class AxiSchedule(
      arReady: IndexedSeq[Boolean],
      rValid: IndexedSeq[Boolean],
      awReady: IndexedSeq[Boolean],
      wReady: IndexedSeq[Boolean],
      bValid: IndexedSeq[Boolean]
  )

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
    dut.io.externalCoherence.request.valid.poke(false)
    dut.io.externalCoherence.request.bits.kind.poke(0)
    dut.io.externalCoherence.request.bits.lineAddress.poke(0)
    dut.io.externalCoherence.response.ready.poke(true)
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
      driveExternalCoherence: (ZirconCore, Int) => Unit = (_, _) => (),
      arReadyForCycle: Int => Boolean = _ => true,
      rValidForCycle: Int => Boolean = _ => true,
      readSelectForCycle: (Int, Seq[(BigInt, BigInt, BigInt, Boolean)]) => Int =
        (_, _) => 0,
      readValidForCycle: (Int, BigInt, BigInt) => Boolean = (_, _, _) => true,
      rResponse: (BigInt, BigInt) => Int = (_, _) => 0,
      writeResponse: Option[Int] = None,
      writeResponseForCycle: Option[(Int, BigInt) => Int] = None,
      observeCycle: (ZirconCore, Int) => Unit = (_, _) => (),
      awReadyForCycle: Int => Boolean = _ => true,
      wReadyForCycle: Int => Boolean = _ => true,
      bValidForCycle: Int => Boolean = _ => true,
      resetForCycle: (ZirconCore, Int) => Boolean = (_, _) => false
  ): Seq[TraceSample] = {
    val pendingReads = scala.collection.mutable.Queue.empty[(BigInt, BigInt, BigInt, Boolean)]
    val backingMemory = scala.collection.mutable.Map.empty[BigInt, BigInt] ++ program
    val events = scala.collection.mutable.ArrayBuffer.empty[TraceSample]
    var rHeld = false
    var awSeen = false
    var wLastSeen = false
    var bQueued = false
    var bHeld = false
    var heldReadIndex = 0
    var writeId = BigInt(5)
    var writeAddress = BigInt(0)
    var writeBeat = 0
    val hasWriteResponse = writeResponse.nonEmpty || writeResponseForCycle.nonEmpty

    dut.clock.step(128) // Deterministic bimodal/BTB scrubs.
    for (cycle <- 0 until cycles) {
      val resetActive = resetForCycle(dut, cycle)
      dut.reset.poke(resetActive)
      if (resetActive) {
        // AXI reset starts a new ownership epoch. The external model drops
        // responses for requests from the old epoch but retains memory data.
        pendingReads.clear()
        events.clear()
        rHeld = false
        awSeen = false
        wLastSeen = false
        bQueued = false
        bHeld = false
        heldReadIndex = 0
        writeBeat = 0
      } else {
        driveInterrupts(dut, events.toSeq)
        driveExternalCoherence(dut, cycle)
      }
      dut.io.axi.ar.ready.poke(!resetActive && arReadyForCycle(cycle))
      dut.io.axi.aw.ready.poke(!resetActive && awReadyForCycle(cycle))
      dut.io.axi.w.ready.poke(!resetActive && wReadyForCycle(cycle))
      val selectedReadIndex = if (rHeld) heldReadIndex else {
        val requested = readSelectForCycle(cycle, pendingReads.toSeq)
        requested.max(0).min(pendingReads.size - 1)
      }
      val selectedReadPermitted = pendingReads.nonEmpty &&
        readValidForCycle(cycle, pendingReads(selectedReadIndex)._1,
          pendingReads(selectedReadIndex)._2)
      val rOffered = !resetActive && pendingReads.nonEmpty &&
        (rHeld || (rValidForCycle(cycle) && selectedReadPermitted))
      if (rOffered) {
        val (id, address, data, last) = pendingReads(selectedReadIndex)
        dut.io.axi.r.valid.poke(true)
        dut.io.axi.r.bits.id.poke(id)
        dut.io.axi.r.bits.data.poke(data)
        dut.io.axi.r.bits.resp.poke(rResponse(id, address))
        dut.io.axi.r.bits.last.poke(last)
      } else {
        dut.io.axi.r.valid.poke(false)
      }
      val bOffered = !resetActive && hasWriteResponse && bQueued &&
        (bHeld || bValidForCycle(cycle))
      dut.io.axi.b.valid.poke(bOffered)
      dut.io.axi.b.bits.id.poke(writeId)
      dut.io.axi.b.bits.resp.poke(writeResponseForCycle.map(response =>
        response(cycle, writeId)).getOrElse(writeResponse.getOrElse(0)))

      dut.io.trace.get.foreach { event =>
        if (!resetActive && event.valid.peek().litToBoolean) {
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
      val awAddress = dut.io.axi.aw.bits.addr.peek().litValue
      val wFire = dut.io.axi.w.valid.peek().litToBoolean &&
        dut.io.axi.w.ready.peek().litToBoolean
      val wLast = dut.io.axi.w.bits.last.peek().litToBoolean
      val wData = dut.io.axi.w.bits.data.peek().litValue
      val wMask = dut.io.axi.w.bits.strb.peek().litValue
      val bFire = bOffered &&
        dut.io.axi.b.ready.peek().litToBoolean

      observeCycle(dut, cycle)
      dut.clock.step()

      if (rFire) {
        val remaining = pendingReads.toSeq.zipWithIndex.collect {
          case (entry, index) if index != selectedReadIndex => entry
        }
        pendingReads.clear()
        remaining.foreach(pendingReads.enqueue(_))
        rHeld = false
      } else if (rOffered) {
        rHeld = true
        heldReadIndex = selectedReadIndex
      }
      if (arFire) {
        for (beat <- 0 until arBeats) {
          val address = arAddress + beat * 4
          pendingReads.enqueue((arId, address, backingMemory.getOrElse(address, Nop),
            beat == arBeats - 1))
        }
      }
      if (awFire) {
        awSeen = true
        writeId = awId
        writeAddress = awAddress
        writeBeat = 0
      }
      if (wFire) {
        val address = writeAddress + writeBeat * 4
        val oldData = backingMemory.getOrElse(address, Nop)
        val merged = (0 until 4).foldLeft(oldData) { (value, byte) =>
          if (((wMask >> byte) & 1) != 0) {
            val laneMask = BigInt(0xff) << (byte * 8)
            (value & ~laneMask) | (wData & laneMask)
          } else value
        }
        backingMemory.update(address, merged)
        writeBeat += 1
        if (wLast) wLastSeen = true
      }
      if (hasWriteResponse && !bQueued && awSeen && wLastSeen) {
        bQueued = true
      }
      if (bFire) {
        bQueued = false
        bHeld = false
        awSeen = false
        wLastSeen = false
      } else if (bOffered) {
        bHeld = true
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

  private def seededAxiSchedule(seed: Long, cycles: Int): AxiSchedule = {
    val random = new Random(seed)
    def channel(period: Int, chancePercent: Int): IndexedSeq[Boolean] =
      Vector.tabulate(cycles) { cycle =>
        cycle % period == 0 || random.nextInt(100) < chancePercent
      }
    AxiSchedule(
      arReady = channel(period = 7, chancePercent = 70),
      rValid = channel(period = 5, chancePercent = 65),
      awReady = channel(period = 11, chancePercent = 60),
      wReady = channel(period = 13, chancePercent = 60),
      bValid = channel(period = 17, chancePercent = 65)
    )
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

  private def saveM3AxiStressFailure(
      seed: Long,
      scenario: String,
      schedule: AxiSchedule,
      events: Seq[TraceSample],
      responseSelectorSeed: Option[Long] = None,
      program: Option[Map[BigInt, BigInt]] = None
  ): Unit = {
    val directory = Paths.get("target", "zircon-failures")
    Files.createDirectories(directory)
    val evidence =
      s"test=CoreShellSpec\nscenario=$scenario\nseed=0x${java.lang.Long.toHexString(seed)}\n" +
        s"ar_ready=${schedule.arReady.map(value => if (value) '1' else '0').mkString}\n" +
        s"r_valid=${schedule.rValid.map(value => if (value) '1' else '0').mkString}\n" +
        s"aw_ready=${schedule.awReady.map(value => if (value) '1' else '0').mkString}\n" +
        s"w_ready=${schedule.wReady.map(value => if (value) '1' else '0').mkString}\n" +
        s"b_valid=${schedule.bValid.map(value => if (value) '1' else '0').mkString}\n" +
        responseSelectorSeed.map(selectorSeed =>
          s"response_selector_seed=0x${java.lang.Long.toHexString(selectorSeed)}\n").getOrElse("") +
        program.map(words => words.toSeq.sortBy(_._1).map { case (address, word) =>
          f"program[0x$address%08x]=0x$word%08x"
        }.mkString("program=\n", "\n", "\n")).getOrElse("") +
        events.mkString("retire_trace=\n", "\n", "\n")
    Files.writeString(directory.resolve(
      s"m3-axi-stress-$scenario-${java.lang.Long.toHexString(seed)}.txt"), evidence)
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
        var arWaitCycles = 0
        while (!dut.io.axi.ar.valid.peek().litToBoolean && arWaitCycles < 8) {
          dut.clock.step()
          arWaitCycles += 1
        }
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

    it("writes back the selected dirty L2 victim through one eight-beat ID-5 burst") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val firstAddress = BigInt("80000100", 16)
        val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val writebackWords = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var activeWritebackAddress = Option.empty[BigInt]
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
              val address = core.io.axi.aw.bits.addr.peek().litValue
              writebackAddresses += address
              activeWritebackAddress = Some(address)
              core.io.axi.aw.bits.len.expect(7)
              core.io.axi.aw.bits.size.expect(2)
            }
            if (wFire && activeWritebackAddress.contains(firstAddress)) {
              writebackWords += core.io.axi.w.bits.data.peek().litValue
            }
          }))

        assert(writebackAddresses.count(_ == firstAddress) == 1,
          s"selected dirty L2 line did not issue exactly one ID-5 writeback: $writebackAddresses")
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

    it("drains an accepted wrong-path cache refill without retiring its load") {
      val seed = 0x5eed0301L
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        val cycles = 384
        val schedule = seededAxiSchedule(seed, cycles)
        val branchLine = BigInt("80001000", 16)
        val dataLine = BigInt("80001020", 16)
        val dataOwnerBeats = scala.collection.mutable.Map.empty[BigInt, Int]
        var dataArs = 0
        var drainedBeats = 0
        var firstDrainCycle = Option.empty[Int]
        var branchRetireCycle = Option.empty[Int]
        var firstTrapCycle = Option.empty[Int]
        var events = Seq.empty[TraceSample]
        val program = Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 8 -> BigInt("00210463", 16), // beq x2,x2,+8
          ResetVector + 12 -> BigInt("0200a183", 16), // wrong-path lw x3,32(x1)
          ResetVector + 16 -> BigInt("00100073", 16), // ebreak target
          branchLine -> BigInt("11223344", 16),
          dataLine -> BigInt("55667788", 16)
        )
        try {
          events = runProgram(dut, program, cycles = cycles,
            arReadyForCycle = cycle => schedule.arReady(cycle),
            rValidForCycle = cycle => schedule.rValid(cycle),
            // Keep the wrong-path line live through branch recovery and the
            // target trap. Once accepted, its AXI owner must still drain.
            readValidForCycle = (cycle, _, address) =>
              (address != branchLine || cycle >= 64) &&
                (address != dataLine || cycle >= 256),
            awReadyForCycle = cycle => schedule.awReady(cycle),
            wReadyForCycle = cycle => schedule.wReady(cycle),
            bValidForCycle = cycle => schedule.bValid(cycle),
            observeCycle = (core, cycle) => {
              core.io.trace.get.foreach { event =>
                if (event.valid.peek().litToBoolean &&
                    event.pc.peek().litValue == ResetVector + 8 && !event.trap.peek().litToBoolean &&
                    branchRetireCycle.isEmpty) {
                  branchRetireCycle = Some(cycle)
                }
                if (event.valid.peek().litToBoolean && event.trap.peek().litToBoolean &&
                    firstTrapCycle.isEmpty) {
                  firstTrapCycle = Some(cycle)
                }
              }
              val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                core.io.axi.ar.ready.peek().litToBoolean
              val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                core.io.axi.r.ready.peek().litToBoolean
              if (arFire && core.io.axi.ar.bits.addr.peek().litValue == dataLine) {
                val id = core.io.axi.ar.bits.id.peek().litValue
                dataArs += 1
                dataOwnerBeats.update(id, 8)
              }
              if (rFire) {
                val id = core.io.axi.r.bits.id.peek().litValue
                dataOwnerBeats.get(id).foreach { remaining =>
                  drainedBeats += 1
                  if (firstDrainCycle.isEmpty) firstDrainCycle = Some(cycle)
                  if (remaining == 1) dataOwnerBeats.remove(id)
                  else dataOwnerBeats.update(id, remaining - 1)
                }
              }
            })

          val retired = throughFirstTrap(events)
          withClue(s"seed=0x${java.lang.Long.toHexString(seed)} dataArs=$dataArs " +
            s"drained=$drainedBeats branchRetire=$branchRetireCycle " +
            s"firstTrap=$firstTrapCycle firstDrain=$firstDrainCycle " +
            s"trace=$retired") {
            assert(dataArs == 1, "wrong-path load did not acquire exactly one data AXI owner")
            assert(drainedBeats == 8 && dataOwnerBeats.isEmpty,
              "accepted wrong-path refill did not drain every AXI beat")
            assert(branchRetireCycle.nonEmpty &&
              firstDrainCycle.exists(_ > branchRetireCycle.get),
              "wrong-path data response arrived before its resolving branch retired")
            assert(!retired.exists(_.pc == ResetVector + 12),
              "wrong-path load reached architectural retirement")
            assert(retired.last.pc == ResetVector + 16 && retired.last.trap &&
              retired.last.cause == 3)
          }
        } catch {
          case failure: Throwable =>
            saveM3AxiStressFailure(seed, "wrong-path-refill-drain", schedule, events,
              program = Some(program))
            throw failure
        }
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

    it("allows FENCE to retire while a younger cacheable load owns LQ state") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(
          enableTrace = true, enableM2Observation = true))) { dut =>
        clearInputs(dut)
        var youngerLoadEntered = false
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("0000000f", 16), // fence
          ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 12 -> BigInt("00100073", 16), // ebreak
          BigInt("80001000", 16) -> BigInt("44332211", 16)
        ), cycles = 256, observeCycle = (core, _) => {
          youngerLoadEntered ||= core.io.m2Observation.get.m1Ingress.peek().litToBoolean
        }))

        val fenceIndex = events.indexWhere(_.instruction == BigInt("0000000f", 16))
        val loadIndex = events.indexWhere(_.instruction == BigInt("0000a103", 16))
        assert(youngerLoadEntered,
          s"the younger load never entered the M1/LQ ownership path: $events")
        assert(fenceIndex >= 0 && loadIndex > fenceIndex,
          s"FENCE did not retire before the younger load: $events")
        val load = events(loadIndex)
        assert(load.gprWrite && load.gprAddress == 2 &&
          load.gprData == BigInt("44332211", 16))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("does not retire a dirty cache-global FENCE before the ID-5 B response") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        var writebackIssued = false
        var finalWriteBeatIssued = false
        val events = runProgram(dut, Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("00100113", 16), // addi x2,x0,1
          ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
          ResetVector + 12 -> BigInt("0000000f", 16), // fence
          ResetVector + 16 -> BigInt("00100073", 16) // ebreak
        ), cycles = 384, writeResponse = Some(0), bValidForCycle = _ => false,
          observeCycle = (core, _) => {
            val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean
            writebackIssued ||= awFire && core.io.axi.aw.bits.id.peek().litValue == 5 &&
              core.io.axi.aw.bits.len.peek().litValue == 7
            val wFire = core.io.axi.w.valid.peek().litToBoolean &&
              core.io.axi.w.ready.peek().litToBoolean
            finalWriteBeatIssued ||= wFire && core.io.axi.w.bits.last.peek().litToBoolean
          })

        assert(writebackIssued && finalWriteBeatIssued,
          s"dirty FENCE never reached a complete retained ID-5 writeback: $events")
        assert(!events.exists(_.instruction == BigInt("0000000f", 16)),
          s"FENCE retired before its dirty writeback B response: $events")
        assert(!events.exists(_.instruction == BigInt("00100073", 16)),
          s"post-FENCE instruction retired without the dirty writeback B response: $events")
      }
    }

    it("drains every dirty line before a cache-global FENCE retires") {
      for (seed <- M3FencePressureSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1024
          val schedule = seededAxiSchedule(seed, cycles)
          val firstLine = BigInt("80001000", 16)
          val secondLine = firstLine + 32
          val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var writebackResponses = 0
          var fenceBeforeAllWritebacks = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("00100113", 16), // addi x2,x0,1
              ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
              ResetVector + 12 -> BigInt("0220a023", 16), // sw x2,32(x1)
              ResetVector + 16 -> BigInt("0000000f", 16), // fence
              ResetVector + 20 -> BigInt("00100073", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
                  core.io.axi.aw.bits.len.expect(7)
                  writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
                }
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 5) {
                  writebackResponses += 1
                }
                core.io.trace.get.foreach { event =>
                  if (event.valid.peek().litToBoolean &&
                      event.instruction.peek().litValue == BigInt("0000000f", 16) &&
                      writebackResponses < 2) {
                    fenceBeforeAllWritebacks = true
                  }
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"writebacks=$writebackAddresses, bCount=$writebackResponses, trace=$retired") {
              assert(writebackAddresses.toSet == Set(firstLine, secondLine))
              assert(writebackAddresses.size == 2)
              assert(writebackResponses == 2)
              assert(!fenceBeforeAllWritebacks)
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "fence-multi-dirty-writeback", schedule, events)
              throw failure
          }
        }
      }
    }

    it("retries an errored dirty FENCE writeback before retirement") {
      for (seed <- M3FenceRetrySeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1024
          val schedule = seededAxiSchedule(seed, cycles)
          val line = BigInt("80001000", 16)
          val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var bResponses = 0
          var bErrors = 0
          var successfulResponses = 0
          var fenceBeforeSuccessfulRetry = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("00100113", 16), // addi x2,x0,1
              ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
              ResetVector + 12 -> BigInt("0000000f", 16), // fence
              ResetVector + 16 -> BigInt("00100073", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              writeResponseForCycle = Some((_, _) => if (bResponses == 0) 2 else 0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
                  core.io.axi.aw.bits.len.expect(7)
                  writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
                }
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 5) {
                  bResponses += 1
                  if (core.io.axi.b.bits.resp.peek().litValue == 0) successfulResponses += 1
                  else bErrors += 1
                }
                core.io.trace.get.foreach { event =>
                  if (event.valid.peek().litToBoolean &&
                      event.instruction.peek().litValue == BigInt("0000000f", 16) &&
                      successfulResponses == 0) {
                    fenceBeforeSuccessfulRetry = true
                  }
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"writebacks=$writebackAddresses, b=$bResponses, errors=$bErrors, " +
              s"success=$successfulResponses, trace=$retired") {
              assert(writebackAddresses.toSeq == Seq(line, line))
              assert(bResponses == 2 && bErrors == 1 && successfulResponses == 1)
              assert(!fenceBeforeSuccessfulRetry)
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "fence-writeback-retry", schedule, events)
              throw failure
          }
        }
      }
    }

    it("fills the dirty L2 victim FIFO before a cache-global FENCE can retire") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(
          enableTrace = true, enableM2Observation = true))) { dut =>
        clearInputs(dut)
        val base = BigInt("80001140", 16)
        var maxVictimCount = 0
        var sawWritebackBusy = false
        var sawWritebackAw = false
        val events = runProgram(dut, Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("14008093", 16), // addi x1,x1,320
          ResetVector + 8 -> BigInt("00100113", 16), // addi x2,x0,1
          ResetVector + 12 -> BigInt("0020a023", 16), // sw x2,0(x1)
          ResetVector + 16 -> BigInt("40008093", 16), // addi x1,x1,1024
          ResetVector + 20 -> BigInt("0020a023", 16),
          ResetVector + 24 -> BigInt("40008093", 16),
          ResetVector + 28 -> BigInt("0020a023", 16),
          ResetVector + 32 -> BigInt("40008093", 16),
          ResetVector + 36 -> BigInt("0020a023", 16),
          ResetVector + 40 -> BigInt("40008093", 16),
          ResetVector + 44 -> BigInt("0020a023", 16),
          ResetVector + 48 -> BigInt("40008093", 16),
          ResetVector + 52 -> BigInt("0020a023", 16),
          ResetVector + 56 -> BigInt("0000000f", 16), // fence
          ResetVector + 60 -> BigInt("00100073", 16)
        ), cycles = 2048, writeResponse = Some(0), bValidForCycle = _ => false,
          observeCycle = (core, _) => {
            val observation = core.io.m2Observation.get
            maxVictimCount = maxVictimCount.max(
              observation.l2VictimCount.peek().litValue.toInt)
            sawWritebackBusy ||= observation.l2WritebackBusy.peek().litToBoolean
            sawWritebackAw ||= core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean &&
              core.io.axi.aw.bits.id.peek().litValue == 5
          })

        withClue(s"base=0x${base.toString(16)}, maxVictims=$maxVictimCount, " +
          s"writebackBusy=$sawWritebackBusy, aw=$sawWritebackAw, trace=$events") {
          assert(maxVictimCount == 2,
            "the FENCE sequence never reached the two-entry dirty victim FIFO limit")
          assert(sawWritebackBusy && sawWritebackAw,
            "the retained ID-5 writeback owner never became active")
          assert(!events.exists(_.instruction == BigInt("0000000f", 16)),
            "FENCE retired while the dirty victim FIFO and ID-5 owner were not drained")
          assert(!events.exists(_.instruction == BigInt("00100073", 16)),
            "younger work retired while the cache-global FENCE was blocked")
        }
      }
    }

    it("retries the oldest dirty FENCE writeback before draining the next victim") {
      for (seed <- M3FenceFifoRetrySeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1280
          val schedule = seededAxiSchedule(seed, cycles)
          val firstLine = BigInt("80001000", 16)
          val secondLine = firstLine + 32
          val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var bResponses = 0
          var bErrors = 0
          var successfulResponses = 0
          var fenceBeforeAllSuccess = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("00100113", 16), // addi x2,x0,1
              ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
              ResetVector + 12 -> BigInt("0220a023", 16), // sw x2,32(x1)
              ResetVector + 16 -> BigInt("0000000f", 16), // fence
              ResetVector + 20 -> BigInt("00100073", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              writeResponseForCycle = Some((_, _) => if (bResponses == 0) 2 else 0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
                  core.io.axi.aw.bits.len.expect(7)
                  writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
                }
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 5) {
                  bResponses += 1
                  if (core.io.axi.b.bits.resp.peek().litValue == 0) successfulResponses += 1
                  else bErrors += 1
                }
                core.io.trace.get.foreach { event =>
                  if (event.valid.peek().litToBoolean &&
                      event.instruction.peek().litValue == BigInt("0000000f", 16) &&
                      successfulResponses < 2) {
                    fenceBeforeAllSuccess = true
                  }
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"writebacks=$writebackAddresses, b=$bResponses, errors=$bErrors, " +
              s"success=$successfulResponses, trace=$retired") {
              assert(writebackAddresses.toSeq == Seq(firstLine, firstLine, secondLine))
              assert(bResponses == 3 && bErrors == 1 && successfulResponses == 2)
              assert(!fenceBeforeAllSuccess)
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "fence-fifo-writeback-retry", schedule, events)
              throw failure
          }
        }
      }
    }

    it("preserves mixed cache and device AXI traffic through seeded backpressure") {
      for (seed <- M3AxiMixedTrafficSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1280
          val schedule = seededAxiSchedule(seed, cycles)
          val firstLine = BigInt("80001000", 16)
          val secondLine = firstLine + 64
          val deviceAddress = BigInt("b0000000", 16)
          val dataArAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          val writeAw = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val writeResponses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("b0000237", 16), // lui x4,0xb0000
              ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 12 -> BigInt("0220a023", 16), // sw x2,32(x1)
              ResetVector + 16 -> BigInt("00222023", 16), // sw x2,0(x4)
              ResetVector + 20 -> BigInt("0400a283", 16), // lw x5,64(x1)
              ResetVector + 24 -> BigInt("0000000f", 16), // fence
              ResetVector + 28 -> BigInt("00100073", 16),
              firstLine -> BigInt("11223344", 16),
              secondLine -> BigInt("55667788", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire) {
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (address == firstLine || address == secondLine) dataArAddresses += address
                }
                if (awFire) {
                  writeAw += ((core.io.axi.aw.bits.id.peek().litValue,
                    core.io.axi.aw.bits.len.peek().litValue))
                }
                if (bFire) writeResponses += core.io.axi.b.bits.id.peek().litValue
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, dataAr=$dataArAddresses, " +
              s"aw=$writeAw, b=$writeResponses, trace=$retired") {
              assert(dataArAddresses.toSet == Set(firstLine, secondLine))
              assert(writeAw.toSet == Set((BigInt(5), BigInt(7)), (BigInt(6), BigInt(0))))
              assert(writeResponses.toSet == Set(BigInt(5), BigInt(6)))
              assert(retired.exists(event => event.pc == ResetVector + 8 &&
                event.gprWrite && event.gprAddress == 2 &&
                event.gprData == BigInt("11223344", 16) &&
                event.memoryAddress == firstLine && event.memoryReadData ==
                  BigInt("11223344", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 12 &&
                event.memoryAddress == firstLine + 32 && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("11223344", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 16 &&
                event.memoryAddress == deviceAddress && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("11223344", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 20 &&
                event.gprWrite && event.gprAddress == 5 &&
                event.gprData == BigInt("55667788", 16) &&
                event.memoryAddress == secondLine && event.memoryReadData ==
                  BigInt("55667788", 16)))
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "mixed-cache-device-traffic", schedule, events)
              throw failure
          }
        }
      }
    }

    it("drains two dirty cache lines and device AXI traffic through seeded backpressure") {
      for (seed <- M3AxiLongMixedTrafficSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1600
          val schedule = seededAxiSchedule(seed, cycles)
          val firstLine = BigInt("80001000", 16)
          val secondLine = firstLine + 64
          val thirdLine = firstLine + 128
          val deviceAddress = BigInt("b0000000", 16)
          val dataArAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          val writeAw = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val writeResponses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("b0000237", 16), // lui x4,0xb0000
              ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 12 -> BigInt("0220a023", 16), // sw x2,32(x1)
              ResetVector + 16 -> BigInt("00222023", 16), // sw x2,0(x4)
              ResetVector + 20 -> BigInt("0400a283", 16), // lw x5,64(x1)
              ResetVector + 24 -> BigInt("0800a303", 16), // lw x6,128(x1)
              ResetVector + 28 -> BigInt("0a60a023", 16), // sw x6,160(x1)
              ResetVector + 32 -> BigInt("0000000f", 16), // fence
              ResetVector + 36 -> BigInt("00100073", 16),
              firstLine -> BigInt("11223344", 16),
              secondLine -> BigInt("55667788", 16),
              thirdLine -> BigInt("99aabbcc", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire) {
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (Set(firstLine, secondLine, thirdLine).contains(address)) {
                    dataArAddresses += address
                  }
                }
                if (awFire) {
                  writeAw += ((core.io.axi.aw.bits.id.peek().litValue,
                    core.io.axi.aw.bits.len.peek().litValue))
                }
                if (bFire) writeResponses += core.io.axi.b.bits.id.peek().litValue
              })

            val retired = throughFirstTrap(events)
            val id5Writes = writeAw.count(_ == (BigInt(5), BigInt(7)))
            val id6Writes = writeAw.count(_ == (BigInt(6), BigInt(0)))
            val id5Responses = writeResponses.count(_ == BigInt(5))
            val id6Responses = writeResponses.count(_ == BigInt(6))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, dataAr=$dataArAddresses, " +
              s"aw=$writeAw, b=$writeResponses, trace=$retired") {
              assert(dataArAddresses.toSet == Set(firstLine, secondLine, thirdLine))
              assert(id5Writes == 2 && id6Writes == 1)
              assert(id5Responses == 2 && id6Responses == 1)
              assert(retired.exists(event => event.pc == ResetVector + 24 &&
                event.gprWrite && event.gprAddress == 6 &&
                event.gprData == BigInt("99aabbcc", 16) &&
                event.memoryAddress == thirdLine && event.memoryReadData ==
                  BigInt("99aabbcc", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 28 &&
                event.memoryAddress == thirdLine + 32 && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("99aabbcc", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 16 &&
                event.memoryAddress == deviceAddress && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("11223344", 16)))
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "long-mixed-cache-device-traffic", schedule, events)
              throw failure
          }
        }
      }
    }

    it("retries a dirty writeback with mixed device AXI traffic through seeded backpressure") {
      for (seed <- M3AxiLongMixedRetrySeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1920
          val schedule = seededAxiSchedule(seed, cycles)
          val firstLine = BigInt("80001000", 16)
          val secondLine = firstLine + 64
          val thirdLine = firstLine + 128
          val deviceAddress = BigInt("b0000000", 16)
          val writeAw = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val writeResponses = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          var id5Responses = 0
          var id5Successes = 0
          var fenceBeforeDrain = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("b0000237", 16), // lui x4,0xb0000
              ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 12 -> BigInt("0220a023", 16), // sw x2,32(x1)
              ResetVector + 16 -> BigInt("00222023", 16), // sw x2,0(x4)
              ResetVector + 20 -> BigInt("0400a283", 16), // lw x5,64(x1)
              ResetVector + 24 -> BigInt("0800a303", 16), // lw x6,128(x1)
              ResetVector + 28 -> BigInt("0a60a023", 16), // sw x6,160(x1)
              ResetVector + 32 -> BigInt("0000000f", 16), // fence
              ResetVector + 36 -> BigInt("00100073", 16),
              firstLine -> BigInt("11223344", 16),
              secondLine -> BigInt("55667788", 16),
              thirdLine -> BigInt("99aabbcc", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              writeResponseForCycle = Some((_, id) =>
                if (id == 5 && id5Responses == 0) 2 else 0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (awFire) {
                  writeAw += ((core.io.axi.aw.bits.id.peek().litValue,
                    core.io.axi.aw.bits.len.peek().litValue))
                }
                if (bFire) {
                  val id = core.io.axi.b.bits.id.peek().litValue
                  val response = core.io.axi.b.bits.resp.peek().litValue
                  writeResponses += ((id, response))
                  if (id == 5) {
                    id5Responses += 1
                    if (response == 0) id5Successes += 1
                  }
                }
                core.io.trace.get.foreach { event =>
                  if (event.valid.peek().litToBoolean &&
                      event.instruction.peek().litValue == BigInt("0000000f", 16) &&
                      id5Successes < 2) {
                    fenceBeforeDrain = true
                  }
                }
              })

            val retired = throughFirstTrap(events)
            val id5Writes = writeAw.count(_ == (BigInt(5), BigInt(7)))
            val id6Writes = writeAw.count(_ == (BigInt(6), BigInt(0)))
            val id5Errors = writeResponses.count(_ == (BigInt(5), BigInt(2)))
            val id6Responses = writeResponses.count(_ == (BigInt(6), BigInt(0)))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, aw=$writeAw, " +
              s"b=$writeResponses, trace=$retired") {
              assert(id5Writes == 3 && id6Writes == 1)
              assert(id5Responses == 3 && id5Errors == 1 && id5Successes == 2)
              assert(id6Responses == 1)
              assert(!fenceBeforeDrain)
              assert(retired.exists(event => event.pc == ResetVector + 16 &&
                event.memoryAddress == deviceAddress && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("11223344", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 28 &&
                event.memoryAddress == thirdLine + 32 && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("99aabbcc", 16)))
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "long-mixed-cache-device-writeback-retry",
                schedule, events)
              throw failure
          }
        }
      }
    }

    it("writes back dirty code before FENCE.I invalidates the I-side and refetches it") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val writebacks = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
        val writebackWords = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val target = ResetVector + 28
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800000b7", 16), // lui x1,0x80000
          ResetVector + 4 -> BigInt("01c08093", 16), // addi x1,x1,28
          ResetVector + 8 -> BigInt("00100137", 16), // lui x2,0x00100
          ResetVector + 12 -> BigInt("19310113", 16), // addi x2,x2,0x193
          ResetVector + 16 -> BigInt("0020a023", 16), // sw x2,0(x1)
          ResetVector + 20 -> BigInt("0000100f", 16), // fence.i
          ResetVector + 24 -> BigInt("0040006f", 16), // jal x0,target
          target -> Nop,
          ResetVector + 32 -> BigInt("00100073", 16) // ebreak
        ), cycles = 512, writeResponse = Some(0), observeCycle = (core, _) => {
          val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
            core.io.axi.aw.ready.peek().litToBoolean
          if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
            writebacks += ((core.io.axi.aw.bits.addr.peek().litValue,
              core.io.axi.aw.bits.len.peek().litValue))
          }
          val wFire = core.io.axi.w.valid.peek().litToBoolean &&
            core.io.axi.w.ready.peek().litToBoolean
          if (wFire) writebackWords += core.io.axi.w.bits.data.peek().litValue
        }))

        val fenceIndex = events.indexWhere(_.instruction == BigInt("0000100f", 16))
        val rewritten = events.find(_.pc == target).getOrElse(
          fail(s"FENCE.I did not refetch the rewritten target: $events"))
        assert(writebacks.toSeq == Seq((ResetVector, BigInt(7))),
          s"expected one eight-beat dirty-code ID-5 writeback, got $writebacks")
        assert(fenceIndex >= 0 && events.indexOf(rewritten) > fenceIndex,
          s"rewritten instruction executed before FENCE.I retired: $events")
        assert(rewritten.instruction == BigInt("00100193", 16) &&
          rewritten.gprWrite && rewritten.gprAddress == 3 && rewritten.gprData == 1,
          s"stale I-side word survived FENCE.I: rewritten=$rewritten " +
            s"writebackWords=$writebackWords events=$events")
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

    it("preserves ordered device writes through explicitly seeded all-channel AXI backpressure") {
      for (seed <- M3AxiStressSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 512
          val schedule = seededAxiSchedule(seed, cycles)
          val deviceAddress = BigInt("a0000000", 16)
          var sawAr = false
          var sawR = false
          var sawAw = false
          var sawW = false
          var sawB = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("a00000b7", 16), // lui x1,0xa0000
              ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
              ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
              ResetVector + 12 -> BigInt("00100073", 16) // ebreak
            ), cycles = cycles, arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                sawAr ||= core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                sawR ||= core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                sawAw ||= core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                sawW ||= core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                sawB ||= core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$retired") {
              assert(sawAr && sawR && sawAw && sawW && sawB)
              assert(retired.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12))
              val store = retired(2)
              assert(store.memoryAddress == deviceAddress && store.memoryWriteMask == 15 &&
                store.memoryWriteData == 90)
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "device-write", schedule, events)
              throw failure
          }
        }
      }
    }

    it("preserves exact data RRESP and device BRESP faults under seeded AXI backpressure") {
      for ((seed, index) <- M3AxiStressSeeds.zipWithIndex) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 512
          val schedule = seededAxiSchedule(seed, cycles)
          val dataFault = index % 2 == 0
          val dataAddress = BigInt("80001000", 16)
          val deviceAddress = BigInt("b0000000", 16)
          val scenario = if (dataFault) "data-rresp" else "device-bresp"
          var sawAr = false
          var sawR = false
          var sawAw = false
          var sawW = false
          var sawB = false
          var events = Seq.empty[TraceSample]
          try {
            val program = if (dataFault) Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 8 -> BigInt("00100073", 16), // ebreak
              dataAddress -> BigInt("deadbeef", 16)
            ) else Map(
              ResetVector -> BigInt("b00000b7", 16), // lui x1,0xb0000
              ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
              ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1)
              ResetVector + 12 -> BigInt("00100073", 16) // ebreak
            )
            events = runProgram(dut, program, cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              rResponse = (_, address) => if (dataFault && address >= dataAddress &&
                address < dataAddress + 32) 2 else 0,
              writeResponse = if (dataFault) None else Some(2),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                sawAr ||= core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                sawR ||= core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                sawAw ||= core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                sawW ||= core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                sawB ||= core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
              })

            val retired = throughFirstTrap(events)
            val expectedPc = if (dataFault) ResetVector + 4 else ResetVector + 8
            val expectedCause = if (dataFault) 5 else 7
            val expectedTval = if (dataFault) dataAddress else deviceAddress
            val trap = retired.find(event => event.trap && event.pc == expectedPc)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, scenario=$scenario, " +
              s"trace=$retired") {
              assert(sawAr && sawR)
              if (!dataFault) assert(sawAw && sawW && sawB)
              assert(trap.exists(event => event.cause == expectedCause &&
                event.trapValue == expectedTval))
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, scenario, schedule, events)
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

    it("runs seeded one-to-four beat DeviceBurstable groups under ROB pressure") {
      val loadInstructions = Seq(
        BigInt("0000a103", 16), BigInt("0040a183", 16),
        BigInt("0080a203", 16), BigInt("00c0a283", 16))
      val storeInstructions = Seq(
        BigInt("0020a023", 16), BigInt("0030a223", 16),
        BigInt("0040a423", 16), BigInt("0050a623", 16))
      val storeValues = Seq(BigInt(17), BigInt(34), BigInt(51), BigInt(68))
      for ((seed, index) <- M3OrderedIoTopSeeds.zipWithIndex) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
          enableM2Observation = true))) { dut =>
          clearInputs(dut)
          val count = index + 1
          val write = index % 2 == 1
          val cycles = 768
          val schedule = seededAxiSchedule(seed, cycles)
          val address = BigInt("b0000000", 16)
          val values = (0 until count).map(member => BigInt("41000000", 16) + member)
          val program = scala.collection.mutable.Map[BigInt, BigInt](
            ResetVector -> BigInt("b00000b7", 16) // lui x1,0xb0000
          )
          val deviceFirstPc = if (write) ResetVector + 24 else ResetVector + 8
          if (write) {
            program.update(ResetVector + 4, BigInt("01100113", 16))
            program.update(ResetVector + 8, BigInt("02200193", 16))
            program.update(ResetVector + 12, BigInt("03300213", 16))
            program.update(ResetVector + 16, BigInt("04400293", 16))
            program.update(ResetVector + 20, BigInt("02004333", 16)) // div x6,x0,x0
            for (member <- 0 until count) {
              program.update(deviceFirstPc + member * 4, storeInstructions(member))
            }
          } else {
            program.update(ResetVector + 4, BigInt("02004333", 16)) // div x6,x0,x0
            for (member <- 0 until count) {
              program.update(deviceFirstPc + member * 4, loadInstructions(member))
              program.update(address + member * 4, values(member))
            }
          }
          program.update(deviceFirstPc + count * 4, BigInt("00100073", 16))
          val id6Groups = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val writeData = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var maxQueueCount = BigInt(0)
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, program.toMap, cycles = cycles,
              // The fixed six-cycle MMIO collection window must see every
              // already-dispatched member. Fetch pressure is covered by the
              // AXI suites; here it would intentionally seal a smaller group
              // before later instructions reach the LQ/SQ.
              arReadyForCycle = cycle => cycle < 128 || schedule.arReady(cycle),
              rValidForCycle = cycle => cycle < 128 || schedule.rValid(cycle),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              writeResponse = if (write) Some(0) else None,
              observeCycle = (core, _) => {
                val observation = core.io.m2Observation.get
                val queueCount = if (write) observation.storeQueueCount.peek().litValue
                else observation.loadQueueCount.peek().litValue
                maxQueueCount = maxQueueCount.max(queueCount)
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val wFire = core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 6) {
                  id6Groups += ((core.io.axi.ar.bits.addr.peek().litValue,
                    core.io.axi.ar.bits.len.peek().litValue))
                }
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 6) {
                  id6Groups += ((core.io.axi.aw.bits.addr.peek().litValue,
                    core.io.axi.aw.bits.len.peek().litValue))
                }
                if (wFire) writeData += core.io.axi.w.bits.data.peek().litValue
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)} count=$count " +
              s"write=$write groups=$id6Groups maxQueue=$maxQueueCount " +
              s"writeData=$writeData trace=$retired") {
              assert(id6Groups.toSeq == Seq((address, BigInt(count - 1))),
                "DeviceBurstable operations did not form one exact ID-6 group")
              assert(maxQueueCount >= count,
                "all same-group device owners were not simultaneously live under ROB pressure")
              if (write) assert(writeData.toSeq == storeValues.take(count))
              for (member <- 0 until count) {
                val event = retired.find(_.pc == deviceFirstPc + member * 4).getOrElse(
                  fail(s"missing device member $member"))
                assert(event.memoryAddress == address + member * 4)
                if (write) {
                  assert(event.memoryWriteMask == 15 &&
                    event.memoryWriteData == storeValues(member))
                } else {
                  assert(event.gprWrite && event.gprAddress == member + 2 &&
                    event.gprData == values(member) && event.memoryReadMask == 15 &&
                    event.memoryReadData == values(member))
                }
              }
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, s"ordered-io-top-$count-${if (write) "write" else "read"}",
                schedule, events, program = Some(program.toMap))
              throw failure
          }
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

    it("preserves ID-7 AMO ownership through seeded mixed AXI traffic") {
      for (seed <- M3AtomicMixedTrafficSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1600
          val schedule = seededAxiSchedule(seed, cycles)
          val selectorSeed = seed ^ 0x7a0a0L
          val selectorRandom = new Random(selectorSeed)
          val cacheLine = BigInt("80001000", 16)
          val deviceAddress = BigInt("b0000000", 16)
          val observedAr = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val observedAw = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val observedB = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("b0000237", 16), // lui x4,0xb0000
              ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 12 -> BigInt("00500193", 16), // addi x3,x0,5
              ResetVector + 16 -> BigInt("0030a2af", 16), // amoadd.w x5,x3,(x1)
              ResetVector + 20 -> BigInt("0220a023", 16), // sw x2,32(x1)
              ResetVector + 24 -> BigInt("00222023", 16), // sw x2,0(x4)
              ResetVector + 28 -> BigInt("0000000f", 16), // fence
              ResetVector + 32 -> BigInt("00100073", 16),
              cacheLine -> BigInt("11223344", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                if (candidates.isEmpty) 0
                else candidates(selectorRandom.nextInt(candidates.size))
              },
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire) observedAr += ((core.io.axi.ar.bits.id.peek().litValue,
                  core.io.axi.ar.bits.addr.peek().litValue))
                if (awFire) observedAw += ((core.io.axi.aw.bits.id.peek().litValue,
                  core.io.axi.aw.bits.len.peek().litValue))
                if (bFire) observedB += core.io.axi.b.bits.id.peek().litValue
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, ar=$observedAr, " +
              s"aw=$observedAw, b=$observedB, trace=$retired") {
              assert(observedAr.exists(_ == (BigInt(7), cacheLine)),
                "the AMO did not acquire the reserved ID-7 read owner")
              assert(observedAw.contains((BigInt(7), BigInt(0))),
                "the AMO did not complete with an ID-7 single-beat write")
              assert(observedAw.contains((BigInt(6), BigInt(0))),
                "the device store did not retain its ID-6 write owner")
              assert(observedAw.contains((BigInt(5), BigInt(7))),
                "FENCE did not drain the dirty cache line through ID-5")
              assert(observedB.toSet == Set(BigInt(5), BigInt(6), BigInt(7)),
                "one mixed write owner did not receive its exact B response")
              assert(retired.exists(event => event.pc == ResetVector + 16 &&
                event.gprWrite && event.gprAddress == 5 &&
                event.gprData == BigInt("11223344", 16) &&
                event.memoryAddress == cacheLine && event.memoryReadMask == 15 &&
                event.memoryReadData == BigInt("11223344", 16) &&
                event.memoryWriteMask == 15 && event.memoryWriteData ==
                  BigInt("11223349", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 20 &&
                event.memoryAddress == cacheLine + 32 && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("11223344", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 24 &&
                event.memoryAddress == deviceAddress && event.memoryWriteMask == 15 &&
                event.memoryWriteData == BigInt("11223344", 16)))
              assert(retired.exists(_.instruction == BigInt("0000000f", 16)))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "atomic-mixed-axi-traffic", schedule, events,
                Some(selectorSeed))
              throw failure
          }
        }
      }
    }

    it("runs seeded random RV32A AMO programs through the ID-7 owner") {
      val mask = (BigInt(1) << 32) - 1
      val operations = Seq(1, 0, 4, 12, 8, 16, 20, 24, 28)
      def amoInstruction(funct5: Int, rd: Int): BigInt =
        (BigInt(funct5) << 27) | (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(2) << 12) | (BigInt(rd) << 7) | BigInt(0x2f)
      def signed(value: BigInt): BigInt =
        if ((value & (BigInt(1) << 31)) != 0) value - (BigInt(1) << 32) else value
      def applyAmo(funct5: Int, oldValue: BigInt, operand: BigInt): BigInt =
        (funct5 match {
          case 1 => operand
          case 0 => oldValue + operand
          case 4 => oldValue ^ operand
          case 12 => oldValue & operand
          case 8 => oldValue | operand
          case 16 => if (signed(oldValue) < signed(operand)) oldValue else operand
          case 20 => if (signed(oldValue) > signed(operand)) oldValue else operand
          case 24 => if (oldValue < operand) oldValue else operand
          case 28 => if (oldValue > operand) oldValue else operand
        }) & mask

      for (seed <- M3AtomicRandomSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 3000)
          val selectorSeed = seed ^ 0x7a0b0L
          val selectorRandom = new Random(selectorSeed)
          val base = BigInt("80001000", 16)
          val operand = BigInt(random.nextInt(2048))
          val shuffled = random.shuffle(operations)
          val initial = shuffled.indices.map(_ => BigInt(random.nextInt()) & mask)
          val program = scala.collection.mutable.Map[BigInt, BigInt](
            ResetVector -> BigInt("800010b7", 16),
            ResetVector + 4 -> ((operand << 20) | (BigInt(2) << 7) | BigInt(0x13)))
          var pc = ResetVector + 8
          val expected = shuffled.zipWithIndex.map { case (funct5, index) =>
            val address = base + index * 4
            val instruction = amoInstruction(funct5, index + 3)
            program.update(pc, instruction)
            val operationPc = pc
            pc += 4
            if (index != shuffled.size - 1) {
              program.update(pc, BigInt("00408093", 16)) // addi x1,x1,4
              pc += 4
            }
            program.update(address, initial(index))
            (operationPc, instruction, index + 3, address, initial(index),
              applyAmo(funct5, initial(index), operand))
          }
          program.update(pc, BigInt("00100073", 16))
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          var id7Responses = 0
          try {
            events = runProgram(dut, program.toMap, cycles = 3000,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 7) id7Responses += 1
              })
            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$retired") {
              assert(id7Reads == operations.size && id7Writes == operations.size &&
                id7Responses == operations.size)
              expected.foreach { case (operationPc, instruction, rd, address, oldValue, writeValue) =>
                val event = retired.find(_.pc == operationPc).getOrElse(
                  fail(s"AMO at pc=0x${operationPc.toString(16)} did not retire"))
                assert(event.instruction == instruction && event.gprWrite &&
                  event.gprAddress == rd && event.gprData == oldValue &&
                  event.memoryAddress == address && event.memoryReadMask == 15 &&
                  event.memoryReadData == oldValue && event.memoryWriteMask == 15 &&
                  event.memoryWriteData == writeValue)
              }
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-rv32a-amo", schedule, events,
                Some(selectorSeed), Some(program.toMap))
              throw failure
          }
        }
      }
    }

    it("preserves seeded LR/SC success and local reservation loss under AXI backpressure") {
      for (seed <- M3LrScRandomSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1800)
          val selectorSeed = seed ^ 0x7a0c0L
          val selectorRandom = new Random(selectorSeed)
          val base = BigInt("80001000", 16)
          val storeData = BigInt(random.nextInt(2048))
          val initialFirst = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val initialSecond = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> ((storeData << 20) | (BigInt(2) << 7) | BigInt(0x13)),
            ResetVector + 8 -> BigInt("1000a1af", 16), // lr.w x3,(x1)
            ResetVector + 12 -> BigInt("1820a22f", 16), // sc.w x4,x2,(x1)
            ResetVector + 16 -> BigInt("00408093", 16), // addi x1,x1,4
            ResetVector + 20 -> BigInt("1000a2af", 16), // lr.w x5,(x1)
            ResetVector + 24 -> BigInt("0000a023", 16), // sw x0,0(x1)
            ResetVector + 28 -> BigInt("1820a32f", 16), // sc.w x6,x2,(x1)
            ResetVector + 32 -> BigInt("00100073", 16),
            base -> initialFirst,
            base + 4 -> initialSecond)
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          var id7Responses = 0
          try {
            events = runProgram(dut, program, cycles = 1800,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 7) id7Responses += 1
              })
            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$retired") {
              assert(id7Reads == 2 && id7Writes == 1 && id7Responses == 1)
              val firstLr = retired.find(_.pc == ResetVector + 8).get
              val firstSc = retired.find(_.pc == ResetVector + 12).get
              val secondLr = retired.find(_.pc == ResetVector + 20).get
              val secondSc = retired.find(_.pc == ResetVector + 28).get
              assert(firstLr.gprData == initialFirst && firstLr.memoryReadData == initialFirst)
              assert(firstSc.gprData == 0 && firstSc.memoryWriteData == storeData)
              assert(secondLr.gprData == initialSecond && secondLr.memoryReadData == initialSecond)
              assert(secondSc.gprData == 1 && secondSc.memoryWriteMask == 0)
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-lr-sc-reservation", schedule, events,
                Some(selectorSeed), Some(program))
              throw failure
          }
        }
      }
    }

    it("clears seeded LR/SC reservations across an interrupt and MRET under AXI backpressure") {
      for (seed <- M3LrScInterruptSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1400)
          val selectorSeed = seed ^ 0x7a0e0L
          val selectorRandom = new Random(selectorSeed)
          val base = BigInt("80001000", 16)
          val initial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val handler = ResetVector + 64
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("80000237", 16), // lui x4,0x80000
            ResetVector + 8 -> BigInt("04020213", 16), // addi x4,x4,64
            ResetVector + 12 -> BigInt("30521073", 16), // csrw mtvec,x4
            ResetVector + 16 -> BigInt("30445073", 16), // csrrwi x0,mie,8
            ResetVector + 20 -> BigInt("30045073", 16), // csrrwi x0,mstatus,8
            ResetVector + 24 -> BigInt("1000a12f", 16), // lr.w x2,(x1)
            ResetVector + 28 -> BigInt("1820a1af", 16), // sc.w x3,x2,(x1)
            ResetVector + 32 -> BigInt("00100073", 16), // ebreak
            handler -> BigInt("30200073", 16), // mret
            base -> initial)
          var events = Seq.empty[TraceSample]
          var interruptTaken = false
          var id7Reads = 0
          var id7Writes = 0
          try {
            events = throughTrap(runProgram(dut, program, cycles = 1400,
              driveInterrupts = (core, observed) => {
                interruptTaken ||= observed.exists(event => event.trap && event.interrupt)
                val lrRetired = observed.exists(event => event.pc == ResetVector + 24 &&
                  event.gprWrite && event.gprAddress == 2)
                core.io.interrupts.msip.poke(lrRetired && !interruptTaken)
              },
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
              }), count = 2)
            val lr = events.find(event => event.pc == ResetVector + 24 &&
              event.gprWrite && event.gprAddress == 2).getOrElse(
              fail(s"LR did not retire before the interrupt: $events"))
            val interrupt = events.find(event => event.trap && event.interrupt).getOrElse(
              fail(s"MSI did not trap: $events"))
            val sc = events.find(event => event.pc == ResetVector + 28 &&
              event.gprWrite && event.gprAddress == 3).getOrElse(
              fail(s"SC did not retire after MRET: $events"))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$events") {
              assert(id7Reads == 1 && id7Writes == 0,
                s"reservation-lost SC issued unexpected ID-7 traffic: reads=$id7Reads writes=$id7Writes")
              assert(lr.gprData == initial && lr.memoryReadData == initial)
              assert(interrupt.pc == ResetVector + 28 &&
                interrupt.cause == BigInt("80000003", 16))
              assert(sc.gprData == 1 && sc.memoryWriteMask == 0 && sc.memoryWriteData == 0)
              assert(events.last.trap && !events.last.interrupt && events.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-lr-sc-interrupt-reservation", schedule,
                events, Some(selectorSeed), Some(program))
              throw failure
          }
        }
      }
    }

    it("turns seeded non-line-base LR RRESP errors into one exact trap") {
      for (seed <- M3LrScErrorSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1200)
          val selectorSeed = seed ^ 0x7a0f0L
          val selectorRandom = new Random(selectorSeed)
          val address = BigInt("80001004", 16)
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("00408093", 16), // addi x1,x1,4
            ResetVector + 8 -> BigInt("1000a1af", 16), // lr.w x3,(x1)
            ResetVector + 12 -> BigInt("1820a22f", 16), // sc.w x4,x2,(x1)
            ResetVector + 16 -> BigInt("00100073", 16),
            address -> (BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)))
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          try {
            events = throughFirstTrap(runProgram(dut, program, cycles = 1200,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              rResponse = (id, responseAddress) =>
                if (id == 7 && responseAddress == address) 2 else 0,
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
              }))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$events") {
              assert(id7Reads == 1 && id7Writes == 0,
                s"faulting LR issued unexpected ID-7 traffic: reads=$id7Reads writes=$id7Writes")
              assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4, ResetVector + 8))
              assert(events.last.trap && !events.last.gprWrite && events.last.cause == 7 &&
                events.last.trapValue == address)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-lr-rresp-error", schedule, events,
                Some(selectorSeed), Some(program))
              throw failure
          }
        }
      }
    }

    it("turns seeded non-line-base SC BRESP errors into one exact trap") {
      for (seed <- M3ScErrorSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1400)
          val selectorSeed = seed ^ 0x7a100L
          val selectorRandom = new Random(selectorSeed)
          val address = BigInt("80001004", 16)
          val storeData = BigInt(random.nextInt(2048))
          val initial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("00408093", 16), // addi x1,x1,4
            ResetVector + 8 -> ((storeData << 20) | (BigInt(2) << 7) | BigInt(0x13)),
            ResetVector + 12 -> BigInt("1000a1af", 16), // lr.w x3,(x1)
            ResetVector + 16 -> BigInt("1820a22f", 16), // sc.w x4,x2,(x1)
            ResetVector + 20 -> BigInt("00100073", 16),
            address -> initial)
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          var id7Responses = 0
          try {
            events = throughFirstTrap(runProgram(dut, program, cycles = 1400,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              writeResponse = Some(2),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 7) id7Responses += 1
              }))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$events") {
              assert(id7Reads == 1 && id7Writes == 1 && id7Responses == 1)
              val lr = events.find(event => event.pc == ResetVector + 12 &&
                event.gprWrite && event.gprAddress == 3).getOrElse(
                fail(s"LR did not complete before SC fault: $events"))
              assert(lr.gprData == initial && lr.memoryReadData == initial)
              assert(events.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12, ResetVector + 16))
              assert(events.last.trap && !events.last.gprWrite && events.last.cause == 7 &&
                events.last.trapValue == address)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-sc-bresp-error", schedule, events,
                Some(selectorSeed), Some(program))
              throw failure
          }
        }
      }
    }

    it("preserves seeded LR/SC reservations across a disjoint local store") {
      for (seed <- M3LrScGranularitySeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1600)
          val selectorSeed = seed ^ 0x7a110L
          val selectorRandom = new Random(selectorSeed)
          val address = BigInt("80001000", 16)
          val disjointAddress = address + 32
          val storeData = BigInt(random.nextInt(2048))
          val initial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> ((storeData << 20) | (BigInt(2) << 7) | BigInt(0x13)),
            ResetVector + 8 -> BigInt("1000a1af", 16), // lr.w x3,(x1)
            ResetVector + 12 -> BigInt("02008293", 16), // addi x5,x1,32
            ResetVector + 16 -> BigInt("0002a023", 16), // sw x0,0(x5)
            ResetVector + 20 -> BigInt("1820a22f", 16), // sc.w x4,x2,(x1)
            ResetVector + 24 -> BigInt("00100073", 16),
            address -> initial,
            disjointAddress -> (BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)))
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          var id7Responses = 0
          try {
            events = throughFirstTrap(runProgram(dut, program, cycles = 1600,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 7) id7Responses += 1
              }))
            val lr = events.find(event => event.pc == ResetVector + 8 &&
              event.gprWrite && event.gprAddress == 3).getOrElse(
              fail(s"LR did not retire: $events"))
            val sc = events.find(event => event.pc == ResetVector + 20 &&
              event.gprWrite && event.gprAddress == 4).getOrElse(
              fail(s"SC did not retire: $events"))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$events") {
              assert(id7Reads == 1 && id7Writes == 1 && id7Responses == 1)
              assert(lr.gprData == initial && lr.memoryAddress == address &&
                lr.memoryReadData == initial)
              assert(sc.gprData == 0 && sc.memoryAddress == address &&
                sc.memoryWriteMask == 15 && sc.memoryWriteData == storeData)
              assert(events.last.trap && events.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-lr-sc-disjoint-store", schedule, events,
                Some(selectorSeed), Some(program))
              throw failure
          }
        }
      }
    }

    it("replaces seeded LR/SC reservations with a later LR") {
      for (seed <- M3LrScReplacementSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1600)
          val selectorSeed = seed ^ 0x7a120L
          val selectorRandom = new Random(selectorSeed)
          val firstAddress = BigInt("80001000", 16)
          val secondAddress = firstAddress + 32
          val firstInitial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val secondInitial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("1000a1af", 16), // lr.w x3,(x1)
            ResetVector + 8 -> BigInt("02008293", 16), // addi x5,x1,32
            ResetVector + 12 -> BigInt("1002a32f", 16), // lr.w x6,(x5)
            ResetVector + 16 -> BigInt("1820a22f", 16), // sc.w x4,x2,(x1)
            ResetVector + 20 -> BigInt("00100073", 16),
            firstAddress -> firstInitial,
            secondAddress -> secondInitial)
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          try {
            events = throughFirstTrap(runProgram(dut, program, cycles = 1600,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
              }))
            val firstLr = events.find(event => event.pc == ResetVector + 4 &&
              event.gprWrite && event.gprAddress == 3).getOrElse(
              fail(s"first LR did not retire: $events"))
            val secondLr = events.find(event => event.pc == ResetVector + 12 &&
              event.gprWrite && event.gprAddress == 6).getOrElse(
              fail(s"replacement LR did not retire: $events"))
            val sc = events.find(event => event.pc == ResetVector + 16 &&
              event.gprWrite && event.gprAddress == 4).getOrElse(
              fail(s"SC did not retire after reservation replacement: $events"))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$events") {
              assert(id7Reads == 2 && id7Writes == 0)
              assert(firstLr.gprData == firstInitial && firstLr.memoryAddress == firstAddress)
              assert(secondLr.gprData == secondInitial && secondLr.memoryAddress == secondAddress)
              assert(sc.gprData == 1 && sc.memoryWriteMask == 0 && sc.memoryWriteData == 0)
              assert(events.last.trap && events.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-lr-sc-reservation-replacement", schedule,
                events, Some(selectorSeed), Some(program))
              throw failure
          }
        }
      }
    }

    it("turns seeded non-line-base AMO BRESP errors into one exact trap") {
      for (seed <- M3AtomicErrorSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val random = new Random(seed)
          val schedule = seededAxiSchedule(seed, 1200)
          val selectorSeed = seed ^ 0x7a0d0L
          val selectorRandom = new Random(selectorSeed)
          val address = BigInt("80001004", 16)
          val operand = BigInt(random.nextInt(2048))
          val initial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("00408093", 16), // addi x1,x1,4
            ResetVector + 8 -> ((operand << 20) | (BigInt(2) << 7) | BigInt(0x13)),
            ResetVector + 12 -> BigInt("0020a1af", 16), // amoadd.w x3,x2,(x1)
            ResetVector + 16 -> BigInt("00100073", 16),
            address -> initial)
          var events = Seq.empty[TraceSample]
          var id7Reads = 0
          var id7Writes = 0
          var id7Responses = 0
          try {
            events = runProgram(dut, program, cycles = 1200,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter(index =>
                  !pending.take(index).exists(_._1 == pending(index)._1))
                if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
              },
              writeResponseForCycle = Some((_, id) => if (id == 7) 2 else 0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) id7Writes += 1
                if (bFire && core.io.axi.b.bits.id.peek().litValue == 7) id7Responses += 1
              })
            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, trace=$retired") {
              assert(id7Reads == 1 && id7Writes == 1 && id7Responses == 1)
              val fault = retired.last
              assert(fault.pc == ResetVector + 12 && fault.trap && !fault.gprWrite &&
                fault.cause == 7 && fault.trapValue == address)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "random-amo-bresp-error", schedule, events,
                Some(selectorSeed), Some(program))
              throw failure
          }
        }
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

    it("merges an older partial store forward with a cacheable refill") {
      for (seed <- M3PartialStoreForwardSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
          enableM2Observation = true))) { dut =>
          clearInputs(dut)
          val cycles = 512
          val schedule = seededAxiSchedule(seed, cycles)
          val line = BigInt("80001000", 16)
          val initialWord = BigInt("11223344", 16)
          val mergedWord = BigInt("1122bb44", 16)
          val dataLineRefills = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("fbb00113", 16), // addi x2,x0,-69
            ResetVector + 8 -> BigInt("002080a3", 16), // sb x2,1(x1)
            ResetVector + 12 -> BigInt("0000a183", 16), // lw x3,0(x1)
            ResetVector + 16 -> BigInt("00100073", 16), // ebreak
            line -> initialWord
          )
          try {
            events = runProgram(dut, program, cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val id = core.io.axi.ar.bits.id.peek().litValue
                val address = core.io.axi.ar.bits.addr.peek().litValue
                if (arFire && id >= 1 && id <= 4 && address == line) {
                  dataLineRefills += address
                }
              })

            val retired = throughFirstTrap(events)
            val store = retired.find(_.pc == ResetVector + 8).getOrElse(
              fail(s"partial store did not retire: seed=0x${java.lang.Long.toHexString(seed)} " +
                s"trace=$retired"))
            val load = retired.find(_.pc == ResetVector + 12).getOrElse(
              fail(s"partial forwarded load did not retire: seed=0x${java.lang.Long.toHexString(seed)} " +
                s"trace=$retired"))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)} " +
              s"lineRefills=$dataLineRefills trace=$retired") {
              assert(dataLineRefills == Seq(line),
                "partial-forwarding miss did not use exactly one data-line refill")
              assert(store.memoryAddress == line + 1 && store.memoryWriteMask == 2 &&
                store.memoryWriteData == BigInt("bb00", 16) && !store.trap)
              assert(load.gprWrite && load.gprAddress == 3 && load.gprData == mergedWord &&
                load.memoryAddress == line && load.memoryReadMask == 15 &&
                load.memoryReadData == mergedWord && !load.trap)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "partial-store-forward-refill", schedule, events,
                program = Some(program))
              throw failure
          }
        }
      }
    }

    it("merges an older halfword store forward with a cacheable refill") {
      for (seed <- M3PartialStoreForwardSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
          enableM2Observation = true))) { dut =>
          clearInputs(dut)
          val cycles = 512
          val schedule = seededAxiSchedule(seed, cycles)
          val line = BigInt("80001000", 16)
          val initialWord = BigInt("11223344", 16)
          val mergedWord = BigInt("aabb3344", 16)
          val dataLineRefills = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("0000b137", 16), // lui x2,0xb
            ResetVector + 8 -> BigInt("abb10113", 16), // addi x2,x2,-1349
            ResetVector + 12 -> BigInt("00209123", 16), // sh x2,2(x1)
            ResetVector + 16 -> BigInt("0000a183", 16), // lw x3,0(x1)
            ResetVector + 20 -> BigInt("00100073", 16), // ebreak
            line -> initialWord
          )
          try {
            events = runProgram(dut, program, cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val id = core.io.axi.ar.bits.id.peek().litValue
                val address = core.io.axi.ar.bits.addr.peek().litValue
                if (arFire && id >= 1 && id <= 4 && address == line) {
                  dataLineRefills += address
                }
              })

            val retired = throughFirstTrap(events)
            val store = retired.find(_.pc == ResetVector + 12).getOrElse(
              fail(s"partial halfword store did not retire: seed=0x${java.lang.Long.toHexString(seed)} " +
                s"trace=$retired"))
            val load = retired.find(_.pc == ResetVector + 16).getOrElse(
              fail(s"partial halfword forwarded load did not retire: " +
                s"seed=0x${java.lang.Long.toHexString(seed)} trace=$retired"))
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)} " +
              s"lineRefills=$dataLineRefills trace=$retired") {
              assert(dataLineRefills == Seq(line),
                "partial-halfword forwarding miss did not use exactly one data-line refill")
              assert(store.memoryAddress == line + 2 && store.memoryWriteMask == 12 &&
                store.memoryWriteData == BigInt("aabb0000", 16) && !store.trap)
              assert(load.gprWrite && load.gprAddress == 3 && load.gprData == mergedWord &&
                load.memoryAddress == line && load.memoryReadMask == 15 &&
                load.memoryReadData == mergedWord && !load.trap)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "partial-halfword-store-forward-refill", schedule,
                events, program = Some(program))
              throw failure
          }
        }
      }
    }

    it("executes independent cacheable loads through both M0 and M1 ownership") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
        enableM2Observation = true))) { dut =>
        clearInputs(dut)
        var m0Ingress = false
        var m1Ingress = false
        val dualIngressCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
        val forwardCycles = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean, Boolean)]
        val dualL1dRequestCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
        val l1dRequests = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val firstLine = BigInt("80001000", 16)
        val secondLine = BigInt("80002000", 16)
        val events = throughFirstTrap(runProgram(dut, Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("80002237", 16), // lui x4,0x80002
          ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 12 -> BigInt("00022183", 16), // lw x3,0(x4)
          ResetVector + 16 -> BigInt("00100073", 16),
          firstLine -> BigInt("11223344", 16),
          secondLine -> BigInt("55667788", 16)
        ), cycles = 384, observeCycle = (core, cycle) => {
          val observation = core.io.m2Observation.get
          val m0Fire = observation.m0Ingress.peek().litToBoolean
          val m1Fire = observation.m1Ingress.peek().litToBoolean
          m0Ingress ||= m0Fire
          m1Ingress ||= m1Fire
          if (m0Fire && m1Fire) dualIngressCycles += cycle
          val forward0 = observation.loadForwardValid(0).peek().litToBoolean
          val forward1 = observation.loadForwardValid(1).peek().litToBoolean
          if (forward0 || forward1) forwardCycles += ((cycle, forward0, forward1))
          val l1dFire0 = observation.l1dRequest(0).peek().litToBoolean
          val l1dFire1 = observation.l1dRequest(1).peek().litToBoolean
          if (l1dFire0 && l1dFire1) dualL1dRequestCycles += cycle
          for (lane <- 0 until 2) {
            if (observation.l1dRequest(lane).peek().litToBoolean) {
              l1dRequests += observation.l1dRequestTag(lane).peek().litValue
            }
          }
        }))

        assert(m0Ingress && m1Ingress,
          s"independent loads did not enter both LSU owners: $events")
        assert(dualIngressCycles.nonEmpty,
          s"independent loads never entered M0/M1 together: $dualIngressCycles")
        assert(dualL1dRequestCycles.nonEmpty,
          s"independent loads never reached both L1D ports together: " +
            s"ingress=$dualIngressCycles forwards=$forwardCycles l1d=$l1dRequests")
        val firstLoad = events.find(_.pc == ResetVector + 8).getOrElse(
          fail(s"M1-owned load did not retire: forwards=$forwardCycles " +
            s"l1dRequests=$l1dRequests events=$events"))
        val secondLoad = events.find(_.pc == ResetVector + 12).getOrElse(
          fail(s"M0-owned load did not retire: $events"))
        assert(firstLoad.gprWrite && firstLoad.gprAddress == 2 &&
          firstLoad.gprData == BigInt("11223344", 16) &&
          firstLoad.memoryAddress == firstLine && firstLoad.memoryReadData ==
            BigInt("11223344", 16))
        assert(secondLoad.gprWrite && secondLoad.gprAddress == 3 &&
          secondLoad.gprData == BigInt("55667788", 16) &&
          secondLoad.memoryAddress == secondLine && secondLoad.memoryReadData ==
            BigInt("55667788", 16))
        assert(events.last.trap && events.last.cause == 3)
      }
    }

    it("serializes an external cacheable invalidation through the live top-level port") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val targetLine = ResetVector
        var requestAccepted = false
        var responseCount = 0
        var events = Seq.empty[TraceSample]
        val program = (0 until 64).map(index =>
          ResetVector + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 64 * 4 -> BigInt("00100073", 16))
        events = runProgram(dut, program, cycles = 640,
          driveExternalCoherence = (core, cycle) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (!requestAccepted && cycle >= 24) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(targetLine)
              if (request.ready.peek().litToBoolean) requestAccepted = true
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean &&
                response.ready.peek().litToBoolean) {
              response.bits.kind.expect(0)
              response.bits.lineAddress.expect(targetLine)
              responseCount += 1
            }
          })
        val retired = throughFirstTrap(events)
        assert(requestAccepted, "top-level external coherence request never handshook")
        assert(responseCount == 1,
          s"top-level external coherence response count was $responseCount")
        assert(retired.last.trap && retired.last.cause == 3,
          s"program did not continue to EBREAK after coherence cleanup: $retired")
      }
    }

    it("holds a top-level external-coherence response through backpressure") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val targetLine = ResetVector
        var requestAccepted = false
        var heldResponseCycles = 0
        var responseCount = 0
        val program = (0 until 64).map(index =>
          ResetVector + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 64 * 4 -> BigInt("00100073", 16))
        val events = runProgram(dut, program, cycles = 640,
          driveExternalCoherence = (core, cycle) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            if (!requestAccepted && cycle >= 24) {
              request.valid.poke(true)
              request.bits.kind.poke(1)
              request.bits.lineAddress.poke(targetLine)
              if (request.ready.peek().litToBoolean) requestAccepted = true
            } else {
              request.valid.poke(false)
            }

            // Keep the platform acknowledgement stalled for three full
            // cycles. The response must retain its exact payload throughout.
            val releaseResponse = heldResponseCycles >= 3
            response.ready.poke(releaseResponse)
            if (response.valid.peek().litToBoolean) {
              response.bits.kind.expect(1)
              response.bits.lineAddress.expect(targetLine)
              heldResponseCycles += 1
              if (releaseResponse) responseCount += 1
            }
          })
        val retired = throughFirstTrap(events)
        withClue(s"request=$requestAccepted held=$heldResponseCycles " +
          s"responses=$responseCount trace=$retired") {
          assert(requestAccepted)
          assert(heldResponseCycles >= 4 && responseCount == 1)
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("invalidates a clean resident L1D line before a later external modifier") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val dataLine = BigInt("80001000", 16)
        val firstLoadPc = ResetVector + 4
        val secondLoadPc = ResetVector + 8 + 64 * 4
        var firstLoadRetired = false
        var requestAccepted = false
        var responseCount = 0
        var dataArCount = 0
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          firstLoadPc -> BigInt("0000a103", 16), // lw x2,0(x1)
          secondLoadPc -> BigInt("0000a183", 16), // lw x3,0(x1)
          secondLoadPc + 4 -> BigInt("00100073", 16),
          dataLine -> BigInt("11223344", 16)
        ) ++ (0 until 64).map(index =>
          ResetVector + 8 + index * 4 -> Nop).toMap
        val events = runProgram(dut, program, cycles = 896,
          driveExternalCoherence = (core, _) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (firstLoadRetired && !requestAccepted) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(dataLine)
              if (request.ready.peek().litToBoolean) requestAccepted = true
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
              response.bits.kind.expect(0)
              response.bits.lineAddress.expect(dataLine)
              responseCount += 1
            }
          }, observeCycle = (core, _) => {
            firstLoadRetired ||= core.io.trace.get.exists(lane =>
              lane.valid.peek().litToBoolean && lane.pc.peek().litValue == firstLoadPc &&
                !lane.trap.peek().litToBoolean)
            val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
              core.io.axi.ar.ready.peek().litToBoolean
            if (arFire && core.io.axi.ar.bits.addr.peek().litValue == dataLine) {
              dataArCount += 1
            }
          })
        val retired = throughFirstTrap(events)
        val firstLoad = retired.find(_.pc == firstLoadPc).getOrElse(
          fail(s"first load did not retire: $retired"))
        val secondLoad = retired.find(_.pc == secondLoadPc).getOrElse(
          fail(s"second load did not retire: $retired"))
        withClue(s"request=$requestAccepted response=$responseCount ar=$dataArCount " +
          s"trace=$retired") {
          assert(firstLoadRetired && requestAccepted && responseCount == 1)
          assert(dataArCount == 2,
            "the second load reused a stale resident L1D line after external invalidation")
          assert(firstLoad.gprWrite && firstLoad.gprAddress == 2 &&
            firstLoad.gprData == BigInt("11223344", 16))
          assert(secondLoad.gprWrite && secondLoad.gprAddress == 3 &&
            secondLoad.gprData == BigInt("11223344", 16))
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("drains an in-flight instruction refill before external-coherence response") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        var firstArCycle = -1
        var requestAcceptedCycle = -1
        var firstBurstDrained = false
        var arWhileBlocked = false
        var responseBeforeDrain = false
        var responseCount = 0
        val targetLine = ResetVector
        val program = (0 until 64).map(index =>
          ResetVector + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 64 * 4 -> BigInt("00100073", 16))
        val events = runProgram(dut, program, cycles = 640,
          rValidForCycle = cycle => firstArCycle >= 0 && cycle >= firstArCycle + 12,
          driveExternalCoherence = (core, cycle) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (firstArCycle >= 0 && requestAcceptedCycle < 0) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(targetLine)
              if (request.ready.peek().litToBoolean) requestAcceptedCycle = cycle
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
              responseBeforeDrain ||= !firstBurstDrained
              response.bits.kind.expect(0)
              response.bits.lineAddress.expect(targetLine)
              responseCount += 1
            }
          }, observeCycle = (core, cycle) => {
            val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
              core.io.axi.ar.ready.peek().litToBoolean
            if (arFire && firstArCycle < 0) firstArCycle = cycle
            if (arFire && requestAcceptedCycle >= 0 && !firstBurstDrained) {
              arWhileBlocked = true
            }
            val rFire = core.io.axi.r.valid.peek().litToBoolean &&
              core.io.axi.r.ready.peek().litToBoolean
            if (rFire && core.io.axi.r.bits.last.peek().litToBoolean) {
              firstBurstDrained = true
            }
          })
        val retired = throughFirstTrap(events)
        withClue(s"firstAr=$firstArCycle request=$requestAcceptedCycle " +
          s"drained=$firstBurstDrained blockedAr=$arWhileBlocked " +
          s"response=$responseCount beforeDrain=$responseBeforeDrain trace=$retired") {
          assert(firstArCycle >= 0 && requestAcceptedCycle > firstArCycle)
          assert(firstBurstDrained && !arWhileBlocked)
          assert(responseCount == 1 && !responseBeforeDrain)
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("drops a reset external-coherence request and accepts a fresh epoch") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val firstLine = ResetVector
        val secondLine = ResetVector + 32
        var firstArCycle = -1
        var firstRequestAcceptedCycle = -1
        var resetCycle = -1
        var postResetArCycle = -1
        var secondRequestAcceptedCycle = -1
        var postResetBurstDrained = false
        var responseBeforeDrain = false
        var responseCount = 0
        val program = (0 until 64).map(index =>
          ResetVector + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 64 * 4 -> BigInt("00100073", 16))
        val events = runProgram(dut, program, cycles = 768,
          rValidForCycle = cycle => {
            if (resetCycle < 0) firstArCycle >= 0 && cycle >= firstArCycle + 12
            else postResetArCycle >= 0 && cycle >= postResetArCycle + 12
          },
          resetForCycle = (_, cycle) => {
            val resetActive = firstRequestAcceptedCycle >= 0 && resetCycle < 0 &&
              cycle == firstRequestAcceptedCycle + 1
            if (resetActive) resetCycle = cycle
            resetActive
          },
          driveExternalCoherence = (core, cycle) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (resetCycle < 0 && firstArCycle >= 0 && firstRequestAcceptedCycle < 0) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(firstLine)
              if (request.ready.peek().litToBoolean) firstRequestAcceptedCycle = cycle
            } else if (resetCycle >= 0 && postResetArCycle >= 0 &&
                secondRequestAcceptedCycle < 0) {
              request.valid.poke(true)
              request.bits.kind.poke(1)
              request.bits.lineAddress.poke(secondLine)
              if (request.ready.peek().litToBoolean) secondRequestAcceptedCycle = cycle
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
              response.bits.kind.expect(1)
              response.bits.lineAddress.expect(secondLine)
              responseBeforeDrain ||= !postResetBurstDrained
              responseCount += 1
            }
          }, observeCycle = (core, cycle) => {
            val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
              core.io.axi.ar.ready.peek().litToBoolean
            if (arFire && resetCycle < 0 && firstArCycle < 0) firstArCycle = cycle
            if (arFire && resetCycle >= 0 && postResetArCycle < 0) postResetArCycle = cycle
            val rFire = core.io.axi.r.valid.peek().litToBoolean &&
              core.io.axi.r.ready.peek().litToBoolean
            if (rFire && resetCycle >= 0 && core.io.axi.r.bits.last.peek().litToBoolean) {
              postResetBurstDrained = true
            }
          })
        val retired = throughFirstTrap(events)
        withClue(s"firstAr=$firstArCycle firstRequest=$firstRequestAcceptedCycle " +
          s"reset=$resetCycle postAr=$postResetArCycle secondRequest=$secondRequestAcceptedCycle " +
          s"drained=$postResetBurstDrained response=$responseCount " +
          s"beforeDrain=$responseBeforeDrain trace=$retired") {
          assert(firstArCycle >= 0 && firstRequestAcceptedCycle > firstArCycle)
          assert(resetCycle == firstRequestAcceptedCycle + 1)
          assert(postResetArCycle > resetCycle && secondRequestAcceptedCycle > postResetArCycle)
          assert(postResetBurstDrained && !responseBeforeDrain && responseCount == 1)
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("drains a matching in-flight data refill before external invalidation") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val dataLine = BigInt("80001000", 16)
        var dataArCycle = -1
        var dataArId = BigInt(-1)
        var requestAcceptedCycle = -1
        var dataBurstDrained = false
        var responseBeforeDrain = false
        var responseCount = 0
        val program = Map(
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
          ResetVector + 8 -> BigInt("00100073", 16),
          dataLine -> BigInt("11223344", 16)
        )
        val events = runProgram(dut, program, cycles = 768,
          readValidForCycle = (cycle, _, address) =>
            (address & ~BigInt(31)) != dataLine ||
              (dataArCycle >= 0 && cycle >= dataArCycle + 12),
          driveExternalCoherence = (core, cycle) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (dataArCycle >= 0 && requestAcceptedCycle < 0) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(dataLine)
              if (request.ready.peek().litToBoolean) requestAcceptedCycle = cycle
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
              response.bits.kind.expect(0)
              response.bits.lineAddress.expect(dataLine)
              responseBeforeDrain ||= !dataBurstDrained
              responseCount += 1
            }
          }, observeCycle = (core, cycle) => {
            val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
              core.io.axi.ar.ready.peek().litToBoolean
            val arAddress = core.io.axi.ar.bits.addr.peek().litValue
            if (arFire && arAddress == dataLine && dataArCycle < 0) {
              dataArCycle = cycle
              dataArId = core.io.axi.ar.bits.id.peek().litValue
            }
            val rFire = core.io.axi.r.valid.peek().litToBoolean &&
              core.io.axi.r.ready.peek().litToBoolean
            if (rFire && core.io.axi.r.bits.id.peek().litValue == dataArId &&
                core.io.axi.r.bits.last.peek().litToBoolean) {
              dataBurstDrained = true
            }
          })
        val retired = throughFirstTrap(events)
        val load = retired.find(_.pc == ResetVector + 4).getOrElse(
          fail(s"matching refill load did not retire: $retired"))
        withClue(s"dataAr=$dataArCycle id=$dataArId request=$requestAcceptedCycle " +
          s"drained=$dataBurstDrained response=$responseCount " +
          s"beforeDrain=$responseBeforeDrain trace=$retired") {
          assert(dataArCycle >= 0 && requestAcceptedCycle > dataArCycle)
          assert(dataBurstDrained && !responseBeforeDrain && responseCount == 1)
          assert(load.gprWrite && load.gprAddress == 2 &&
            load.gprData == BigInt("11223344", 16) &&
            load.memoryAddress == dataLine && load.memoryReadData == BigInt("11223344", 16))
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("waits for dirty external-coherence writeback before acknowledging") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val dirtyAddress = BigInt("80001000", 16)
        var storeRetired = false
        var requestAccepted = false
        var responseCount = 0
        var id5BSeen = false
        var responseBeforeB = false
        val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var events = Seq.empty[TraceSample]
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
          ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
          ResetVector + 8 -> BigInt("0020a023", 16) // sw x2,0(x1)
        ) ++ (0 until 64).map(index =>
          ResetVector + 12 + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 12 + 64 * 4 -> BigInt("00100073", 16))
        events = runProgram(dut, program, cycles = 896, writeResponse = Some(0),
          driveExternalCoherence = (core, _) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (storeRetired && !requestAccepted) {
              request.valid.poke(true)
              request.bits.kind.poke(1)
              request.bits.lineAddress.poke(dirtyAddress)
              if (request.ready.peek().litToBoolean) requestAccepted = true
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean &&
                response.ready.peek().litToBoolean) {
              response.bits.kind.expect(1)
              response.bits.lineAddress.expect(dirtyAddress)
              responseBeforeB ||= !id5BSeen
              responseCount += 1
            }
          }, observeCycle = (core, _) => {
            storeRetired ||= core.io.trace.get.exists(lane =>
              lane.valid.peek().litToBoolean && lane.pc.peek().litValue == ResetVector + 8 &&
                !lane.trap.peek().litToBoolean)
            val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean
            val bFire = core.io.axi.b.valid.peek().litToBoolean &&
              core.io.axi.b.ready.peek().litToBoolean
            if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
              writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
              core.io.axi.aw.bits.len.expect(7)
            }
            id5BSeen ||= bFire && core.io.axi.b.bits.id.peek().litValue == 5
          })
        val retired = throughFirstTrap(events)
        withClue(s"trace=$retired writes=$writebackAddresses b=$id5BSeen " +
          s"response=$responseCount beforeB=$responseBeforeB") {
          assert(storeRetired && requestAccepted)
          assert(writebackAddresses.toSeq == Seq(dirtyAddress))
          assert(id5BSeen && !responseBeforeB && responseCount == 1)
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("retries a failing coherence writeback before acknowledging") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val dirtyAddress = BigInt("80001000", 16)
        var storeRetired = false
        var requestAccepted = false
        var id5BCount = 0
        var responseCount = 0
        var responseBeforeRetry = false
        val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var events = Seq.empty[TraceSample]
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("800010b7", 16),
          ResetVector + 4 -> BigInt("05a00113", 16),
          ResetVector + 8 -> BigInt("0020a023", 16)
        ) ++ (0 until 64).map(index =>
          ResetVector + 12 + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 12 + 64 * 4 -> BigInt("00100073", 16))
        events = runProgram(dut, program, cycles = 1024,
          writeResponseForCycle = Some((_, id) =>
            if (id == 5 && id5BCount == 0) 2 else 0),
          driveExternalCoherence = (core, _) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (storeRetired && !requestAccepted) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(dirtyAddress)
              if (request.ready.peek().litToBoolean) requestAccepted = true
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean &&
                response.ready.peek().litToBoolean) {
              responseBeforeRetry ||= id5BCount < 2
              responseCount += 1
            }
          }, observeCycle = (core, _) => {
            storeRetired ||= core.io.trace.get.exists(lane =>
              lane.valid.peek().litToBoolean && lane.pc.peek().litValue == ResetVector + 8 &&
                !lane.trap.peek().litToBoolean)
            val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean
            val bFire = core.io.axi.b.valid.peek().litToBoolean &&
              core.io.axi.b.ready.peek().litToBoolean
            if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
              writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
            }
            if (bFire && core.io.axi.b.bits.id.peek().litValue == 5) {
              id5BCount += 1
            }
          })
        val retired = throughFirstTrap(events)
        withClue(s"trace=$retired writes=$writebackAddresses b=$id5BCount " +
          s"response=$responseCount beforeRetry=$responseBeforeRetry") {
          assert(storeRetired && requestAccepted)
          assert(writebackAddresses.toSeq == Seq(dirtyAddress, dirtyAddress))
          assert(id5BCount == 2 && !responseBeforeRetry && responseCount == 1)
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("drops a dirty coherence writeback on reset and completes a fresh epoch") {
      simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
        clearInputs(dut)
        val dirtyAddress = BigInt("80001000", 16)
        var resetSeen = false
        var resetCycle = -1
        var firstStoreRetired = false
        var secondStoreRetired = false
        var firstRequestAccepted = false
        var secondRequestAccepted = false
        var preResetAw = false
        var preResetWLast = false
        var postResetAw = false
        var postResetB = false
        var staleResponseCount = 0
        var freshResponseCount = 0
        val writebackAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var events = Seq.empty[TraceSample]
        val program = Map[BigInt, BigInt](
          ResetVector -> BigInt("800010b7", 16),
          ResetVector + 4 -> BigInt("05a00113", 16),
          ResetVector + 8 -> BigInt("0020a023", 16)
        ) ++ (0 until 64).map(index =>
          ResetVector + 12 + index * 4 -> Nop).toMap ++ Map(
          ResetVector + 12 + 64 * 4 -> BigInt("00100073", 16))
        events = runProgram(dut, program, cycles = 1280, writeResponse = Some(0),
          // The first ID-5 writeback must remain incomplete until reset. A
          // fresh epoch is then allowed to receive its own response.
          bValidForCycle = _ => resetSeen,
          resetForCycle = (_, cycle) => {
            val assertReset = !resetSeen && preResetAw && preResetWLast
            if (assertReset) {
              resetSeen = true
              resetCycle = cycle
            }
            assertReset
          },
          driveExternalCoherence = (core, _) => {
            val request = core.io.externalCoherence.request
            val response = core.io.externalCoherence.response
            response.ready.poke(true)
            if (!resetSeen && firstStoreRetired && !firstRequestAccepted) {
              request.valid.poke(true)
              request.bits.kind.poke(0)
              request.bits.lineAddress.poke(dirtyAddress)
              if (request.ready.peek().litToBoolean) firstRequestAccepted = true
            } else if (resetSeen && secondStoreRetired && !secondRequestAccepted) {
              request.valid.poke(true)
              request.bits.kind.poke(1)
              request.bits.lineAddress.poke(dirtyAddress)
              if (request.ready.peek().litToBoolean) secondRequestAccepted = true
            } else {
              request.valid.poke(false)
            }
            if (response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
              if (resetSeen) {
                response.bits.kind.expect(1)
                response.bits.lineAddress.expect(dirtyAddress)
                freshResponseCount += 1
              } else {
                staleResponseCount += 1
              }
            }
          }, observeCycle = (core, _) => {
            val storeRetired = core.io.trace.get.exists(lane =>
              lane.valid.peek().litToBoolean && lane.pc.peek().litValue == ResetVector + 8 &&
                !lane.trap.peek().litToBoolean)
            if (resetSeen) secondStoreRetired ||= storeRetired
            else firstStoreRetired ||= storeRetired

            val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
              core.io.axi.aw.ready.peek().litToBoolean
            val wFire = core.io.axi.w.valid.peek().litToBoolean &&
              core.io.axi.w.ready.peek().litToBoolean
            val bFire = core.io.axi.b.valid.peek().litToBoolean &&
              core.io.axi.b.ready.peek().litToBoolean
            if (awFire && core.io.axi.aw.bits.id.peek().litValue == 5) {
              writebackAddresses += core.io.axi.aw.bits.addr.peek().litValue
              if (resetSeen) postResetAw = true else preResetAw = true
            }
            if (wFire && core.io.axi.w.bits.last.peek().litToBoolean) {
              if (!resetSeen) preResetWLast = true
            }
            if (resetSeen && bFire && core.io.axi.b.bits.id.peek().litValue == 5) {
              postResetB = true
            }
          })
        val retired = throughFirstTrap(events)
        withClue(s"trace=$retired reset=$resetCycle firstRequest=$firstRequestAccepted " +
          s"secondRequest=$secondRequestAccepted writes=$writebackAddresses " +
          s"stale=$staleResponseCount fresh=$freshResponseCount postB=$postResetB") {
          assert(firstStoreRetired && firstRequestAccepted && preResetAw && preResetWLast)
          assert(resetCycle >= 0 && secondStoreRetired && secondRequestAccepted)
          assert(writebackAddresses.toSeq == Seq(dirtyAddress, dirtyAddress))
          assert(staleResponseCount == 0 && postResetAw && postResetB && freshResponseCount == 1)
          assert(retired.last.trap && retired.last.cause == 3)
        }
      }
    }

    it("preserves line-scoped LR reservations across seeded external coherence") {
      val address = BigInt("80001000", 16)
      for ((matchingLine, kind, expectedSc, scenario) <- Seq(
          (true, BigInt(0), BigInt(1), "matching-write-invalidate"),
          (false, BigInt(1), BigInt(0), "disjoint-atomic-invalidate"))) {
        for (seed <- M3ExternalCoherenceReservationSeeds) {
          simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
            clearInputs(dut)
            val random = new Random(seed ^ (if (matchingLine) 0x41L else 0x82L))
            val schedule = seededAxiSchedule(seed ^ (if (matchingLine) 0x510L else 0x520L), 1200)
            val selectorSeed = seed ^ (if (matchingLine) 0x7a1d0L else 0x7a1e0L)
            val selectorRandom = new Random(selectorSeed)
            val storeData = BigInt(random.nextInt(2048))
            val initial = BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)
            val coherenceLine = if (matchingLine) address else address + 32
            var lrRetired = false
            var requestAccepted = false
            var responseCount = 0
            var id7Reads = 0
            var id7Writes = 0
            var id7Responses = 0
            var atomicWriteBeforeAcknowledgement = false
            var events = Seq.empty[TraceSample]
            val program = Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> ((storeData << 20) | (BigInt(2) << 7) | BigInt(0x13)),
              ResetVector + 8 -> BigInt("1000a1af", 16), // lr.w x3,(x1)
              ResetVector + 12 -> BigInt("1820a22f", 16), // sc.w x4,x2,(x1)
              ResetVector + 16 -> BigInt("00100073", 16),
              address -> initial,
              address + 32 -> (BigInt(random.nextInt()) & ((BigInt(1) << 32) - 1)))
            try {
              events = throughFirstTrap(runProgram(dut, program, cycles = 1200,
                arReadyForCycle = cycle => schedule.arReady(cycle),
                rValidForCycle = cycle => schedule.rValid(cycle),
                readSelectForCycle = (_, pending) => {
                  val candidates = pending.indices.filter(index =>
                    !pending.take(index).exists(_._1 == pending(index)._1))
                  if (candidates.isEmpty) 0 else candidates(selectorRandom.nextInt(candidates.size))
                },
                writeResponse = Some(0),
                awReadyForCycle = cycle => schedule.awReady(cycle),
                wReadyForCycle = cycle => schedule.wReady(cycle),
                bValidForCycle = cycle => schedule.bValid(cycle),
                driveExternalCoherence = (core, _) => {
                  val request = core.io.externalCoherence.request
                  val response = core.io.externalCoherence.response
                  response.ready.poke(true)
                  if (lrRetired && !requestAccepted) {
                    request.valid.poke(true)
                    request.bits.kind.poke(kind)
                    request.bits.lineAddress.poke(coherenceLine)
                    if (request.ready.peek().litToBoolean) requestAccepted = true
                  } else {
                    request.valid.poke(false)
                  }
                  if (response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
                    response.bits.kind.expect(kind)
                    response.bits.lineAddress.expect(coherenceLine)
                    responseCount += 1
                  }
                }, observeCycle = (core, _) => {
                  lrRetired ||= core.io.trace.get.exists(lane =>
                    lane.valid.peek().litToBoolean && lane.pc.peek().litValue == ResetVector + 8 &&
                      lane.gprWrite.peek().litToBoolean && lane.gprAddress.peek().litValue == 3)
                  val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                    core.io.axi.ar.ready.peek().litToBoolean
                  val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                    core.io.axi.aw.ready.peek().litToBoolean
                  val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                    core.io.axi.b.ready.peek().litToBoolean
                  if (arFire && core.io.axi.ar.bits.id.peek().litValue == 7) id7Reads += 1
                  if (awFire && core.io.axi.aw.bits.id.peek().litValue == 7) {
                    id7Writes += 1
                    atomicWriteBeforeAcknowledgement ||= responseCount == 0
                  }
                  if (bFire && core.io.axi.b.bits.id.peek().litValue == 7) id7Responses += 1
                }))
              val lr = events.find(event => event.pc == ResetVector + 8 &&
                event.gprWrite && event.gprAddress == 3).getOrElse(
                fail(s"LR did not retire: seed=0x${java.lang.Long.toHexString(seed)} trace=$events"))
              val sc = events.find(event => event.pc == ResetVector + 12 &&
                event.gprWrite && event.gprAddress == 4).getOrElse(
                fail(s"SC did not retire: seed=0x${java.lang.Long.toHexString(seed)} trace=$events"))
              withClue(s"scenario=$scenario seed=0x${java.lang.Long.toHexString(seed)} " +
                s"request=$requestAccepted response=$responseCount id7=($id7Reads,$id7Writes,$id7Responses) " +
                s"earlyWrite=$atomicWriteBeforeAcknowledgement trace=$events") {
                assert(lrRetired && requestAccepted && responseCount == 1)
                assert(lr.gprData == initial && lr.memoryAddress == address &&
                  lr.memoryReadMask == 15 && lr.memoryReadData == initial)
                assert(!atomicWriteBeforeAcknowledgement,
                  "SC write bypassed the retained external-coherence acknowledgement")
                assert(sc.gprData == expectedSc && sc.memoryAddress == address)
                if (matchingLine) {
                  assert(id7Reads == 1 && id7Writes == 0 && id7Responses == 0)
                  assert(sc.memoryWriteMask == 0 && sc.memoryWriteData == 0)
                } else {
                  assert(id7Reads == 1 && id7Writes == 1 && id7Responses == 1)
                  assert(sc.memoryWriteMask == 15 && sc.memoryWriteData == storeData)
                }
                assert(events.last.trap && events.last.cause == 3)
              }
            } catch {
              case failure: Throwable =>
                saveM3AxiStressFailure(seed, s"external-coherence-reservation-$scenario", schedule,
                  events, Some(selectorSeed), Some(program))
                throw failure
            }
          }
        }
      }
    }

    it("replays seeded same-bank M0 and M1 loads into one exact AXI refill") {
      for (seed <- M3DualLoadMergeSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
          enableM2Observation = true))) { dut =>
          clearInputs(dut)
          val cycles = 768
          val schedule = seededAxiSchedule(seed, cycles)
          val line = BigInt("80001000", 16)
          val firstWord = BigInt("11223344", 16)
          val secondWord = BigInt("55667788", 16)
          var m0Ingress = false
          var m1Ingress = false
          val l1dRequestTags = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          val lineRefillAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          val program = Map(
            ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
            ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
            ResetVector + 8 -> BigInt("0100a183", 16), // lw x3,16(x1), same word bank
            ResetVector + 12 -> BigInt("00100073", 16),
            line -> firstWord,
            line + 16 -> secondWord
          )
          try {
            events = runProgram(dut, program, cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => schedule.bValid(cycle),
              observeCycle = (core, _) => {
                val observation = core.io.m2Observation.get
                m0Ingress ||= observation.m0Ingress.peek().litToBoolean
                m1Ingress ||= observation.m1Ingress.peek().litToBoolean
                for (lane <- 0 until 2) {
                  if (observation.l1dRequest(lane).peek().litToBoolean) {
                    l1dRequestTags += observation.l1dRequestTag(lane).peek().litValue
                  }
                }
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val id = core.io.axi.ar.bits.id.peek().litValue
                val address = core.io.axi.ar.bits.addr.peek().litValue
                // Physical data-owner IDs overlap the instruction side's AXI
                // IDs, so identify this refill by the exact data-line address.
                if (arFire && id >= 1 && id <= 4 && address == line) {
                  lineRefillAddresses += address
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"m0=$m0Ingress m1=$m1Ingress l1d=$l1dRequestTags " +
              s"lineDataAr=$lineRefillAddresses trace=$retired") {
              assert(m0Ingress && m1Ingress,
                "same-bank loads did not reach both M0 and M1 ingress")
              assert(l1dRequestTags.distinct.size == 2,
                "the two exact L1D request owners were not both accepted")
              assert(lineRefillAddresses == Seq(line),
                "same-line loads did not merge into one data-line AXI refill")
              assert(retired.exists(event => event.pc == ResetVector + 4 &&
                event.gprWrite && event.gprAddress == 2 && event.gprData == firstWord &&
                event.memoryAddress == line && event.memoryReadData == firstWord))
              assert(retired.exists(event => event.pc == ResetVector + 8 &&
                event.gprWrite && event.gprAddress == 3 && event.gprData == secondWord &&
                event.memoryAddress == line + 16 && event.memoryReadData == secondWord))
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "dual-lsu-same-line-merge", schedule, events,
                program = Some(program))
              throw failure
          }
        }
      }
    }

    it("preserves cross-ID AXI read ownership under seeded response interleaving") {
      val seeds = Seq(0x5eed5001L, 0x5eed5002L, 0x5eed5003L, 0x5eed5004L)
      for (seed <- seeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 768
          val schedule = seededAxiSchedule(seed, cycles)
          val selectorRandom = new Random(seed ^ 0x13579bdfL)
          val selectorSeed = seed ^ 0x13579bdfL
          val observedDataIds = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          val firstLine = BigInt("80001000", 16)
          val secondLine = BigInt("80002000", 16)
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("80002237", 16), // lui x4,0x80002
              ResetVector + 8 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 12 -> BigInt("00022183", 16), // lw x3,0(x4)
              ResetVector + 16 -> BigInt("00100073", 16),
              firstLine -> BigInt("11223344", 16),
              secondLine -> BigInt("55667788", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                // Keep beats ordered within each AXI ID while allowing IDs to
                // be selected in a deterministic, seed-controlled order.
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                if (candidates.isEmpty) 0
                else candidates(selectorRandom.nextInt(candidates.size))
              },
              observeCycle = (core, _) => {
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                if (rFire) {
                  val id = core.io.axi.r.bits.id.peek().litValue
                  if (id >= 1 && id <= 4) observedDataIds += id
                }
              })

            val retired = throughFirstTrap(events)
            val dataIdSwitch = observedDataIds.sliding(2).exists(pair => pair.head != pair.last)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"dataIds=$observedDataIds, trace=$retired") {
              assert(observedDataIds.distinct.size >= 2,
                "the program did not create two data AXI owners")
              assert(dataIdSwitch,
                "data AXI responses were not interleaved across owner IDs")
              assert(retired.exists(event => event.pc == ResetVector + 8 &&
                event.gprWrite && event.gprAddress == 2 &&
                event.gprData == BigInt("11223344", 16)))
              assert(retired.exists(event => event.pc == ResetVector + 12 &&
                event.gprWrite && event.gprAddress == 3 &&
                event.gprData == BigInt("55667788", 16)))
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "multi-owner-interleave", schedule, events,
                Some(selectorSeed))
              throw failure
          }
        }
      }
    }

    it("retains four data owners before seeded cross-ID AXI drain") {
      val seeds = Seq(0x5eed6001L, 0x5eed6002L, 0x5eed6003L, 0x5eed6004L)
      for (seed <- seeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1024
          val schedule = seededAxiSchedule(seed, cycles)
          val selectorSeed = seed ^ 0x2468ace0L
          val selectorRandom = new Random(selectorSeed)
          val observedDataIds = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          val dataArIdsBeforeFirstResponse = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          val dataOwnerBeats = scala.collection.mutable.Map.empty[BigInt, Int]
          var firstDataResponseSeen = false
          val lines = Seq(
            BigInt("80001000", 16), BigInt("80001020", 16),
            BigInt("80001040", 16), BigInt("80001060", 16))
          val expected = Seq(
            BigInt("11223344", 16), BigInt("55667788", 16),
            BigInt("99aabbcc", 16), BigInt("ddeeff00", 16))
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 8 -> BigInt("0200a183", 16), // lw x3,32(x1)
              ResetVector + 12 -> BigInt("0400a203", 16), // lw x4,64(x1)
              ResetVector + 16 -> BigInt("0600a283", 16), // lw x5,96(x1)
              ResetVector + 20 -> BigInt("00100073", 16),
              lines(0) -> expected(0), lines(1) -> expected(1),
              lines(2) -> expected(2), lines(3) -> expected(3)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              // First complete L1I's initial burst, then withhold R long enough
              // for all four independent data lines to claim physical owners.
              rValidForCycle = cycle => cycle < 128 || schedule.rValid(cycle),
              readSelectForCycle = (cycle, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                if (cycle < 128 && candidates.exists(index => pending(index)._1 == 0)) {
                  candidates.find(index => pending(index)._1 == 0).get
                } else if (candidates.isEmpty) 0
                else candidates(selectorRandom.nextInt(candidates.size))
              },
              readValidForCycle = (cycle, _, address) => {
                val dataAddress = address >= lines.head && address < lines.last + 32
                !dataAddress || cycle >= 128
              },
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                if (arFire) {
                  val id = core.io.axi.ar.bits.id.peek().litValue
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (lines.contains(address)) {
                    dataOwnerBeats.update(id, 8)
                    if (!firstDataResponseSeen) {
                      dataArIdsBeforeFirstResponse += id
                    }
                  }
                }
                if (rFire) {
                  val id = core.io.axi.r.bits.id.peek().litValue
                  if (dataOwnerBeats.get(id).exists(_ > 0)) {
                    firstDataResponseSeen = true
                    observedDataIds += id
                    val remaining = dataOwnerBeats(id) - 1
                    if (remaining == 0) dataOwnerBeats.remove(id)
                    else dataOwnerBeats.update(id, remaining)
                  }
                }
              })

            val retired = throughFirstTrap(events)
            val dataIdSwitch = observedDataIds.sliding(2).exists(pair => pair.head != pair.last)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"arBeforeDataR=$dataArIdsBeforeFirstResponse, dataIds=$observedDataIds, " +
              s"trace=$retired") {
              assert(dataArIdsBeforeFirstResponse.distinct.size == 4,
                "four data owners did not become live before the first data response")
              assert(observedDataIds.distinct.size == 4,
                "the four physical data AXI owners were not all drained")
              assert(dataIdSwitch,
                "data AXI responses were not interleaved across the four owners")
              for ((pc, register, value, address) <- Seq(
                  (ResetVector + 4, 2, expected(0), lines(0)),
                  (ResetVector + 8, 3, expected(1), lines(1)),
                  (ResetVector + 12, 4, expected(2), lines(2)),
                  (ResetVector + 16, 5, expected(3), lines(3)))) {
                assert(retired.exists(event => event.pc == pc && event.gprWrite &&
                  event.gprAddress == register && event.gprData == value &&
                  event.memoryAddress == address && event.memoryReadData == value))
              }
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "four-owner-interleave", schedule, events,
                Some(selectorSeed))
              throw failure
          }
        }
      }
    }

    it("holds a fifth cache miss until a seeded live owner releases credit") {
      for (seed <- M3MshrPressureSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1280
          val schedule = seededAxiSchedule(seed, cycles)
          val selectorSeed = seed ^ 0x6d736872L
          val selectorRandom = new Random(selectorSeed)
          val lines = (0 until 5).map(index => BigInt("80001000", 16) + index * 0x20)
          val expected = (0 until 5).map(index => BigInt("30000000", 16) + index)
          val dataArs = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val dataOwnerBeats = scala.collection.mutable.Map.empty[BigInt, Int]
          var dataResponseReleased = false
          var firstDataResponseSeen = false
          var fifthBeforeRelease = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 8 -> BigInt("0200a183", 16), // lw x3,32(x1)
              ResetVector + 12 -> BigInt("0400a203", 16), // lw x4,64(x1)
              ResetVector + 16 -> BigInt("0600a283", 16), // lw x5,96(x1)
              ResetVector + 20 -> BigInt("0800a303", 16), // lw x6,128(x1)
              ResetVector + 24 -> BigInt("00100073", 16),
              lines(0) -> expected(0), lines(1) -> expected(1),
              lines(2) -> expected(2), lines(3) -> expected(3),
              lines(4) -> expected(4)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                if (candidates.isEmpty) 0
                else candidates(selectorRandom.nextInt(candidates.size))
              },
              readValidForCycle = (_, _, address) =>
                !lines.contains(address) || dataResponseReleased,
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                val rId = core.io.axi.r.bits.id.peek().litValue
                val dataRFire = rFire && dataOwnerBeats.contains(rId)
                if (arFire) {
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (lines.contains(address)) {
                    val id = core.io.axi.ar.bits.id.peek().litValue
                    dataArs += ((address, id))
                    dataOwnerBeats.update(id, 8)
                    if (address == lines(4) && !firstDataResponseSeen && !dataRFire) {
                      fifthBeforeRelease = true
                    }
                    if (dataArs.map(_._1).distinct.size == 4) {
                      dataResponseReleased = true
                    }
                  }
                }
                if (dataRFire) {
                  firstDataResponseSeen = true
                  val remaining = dataOwnerBeats(rId) - 1
                  if (remaining == 0) dataOwnerBeats.remove(rId)
                  else dataOwnerBeats.update(rId, remaining)
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)} selector=0x" +
              s"${java.lang.Long.toHexString(selectorSeed)} ars=$dataArs trace=$retired") {
              assert(dataArs.take(4).map(_._1).toSet == lines.take(4).toSet,
                "four live MSHRs did not own the oldest four lines before release")
              assert(!fifthBeforeRelease,
                "fifth miss acquired an AXI owner before a live owner released credit")
              assert(dataArs.map(_._1).toSet == lines.toSet,
                "fifth miss did not acquire a reclaimed owner credit")
              for (index <- lines.indices) {
                val pc = ResetVector + 4 + index * 4
                assert(retired.exists(event => event.pc == pc && event.gprWrite &&
                  event.gprAddress == index + 2 && event.gprData == expected(index) &&
                  event.memoryAddress == lines(index) && event.memoryReadData == expected(index)))
              }
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "mshr-full-credit-reclaim", schedule, events,
                Some(selectorSeed))
              throw failure
          }
        }
      }
    }

    it("reuses all data AXI owners across a seeded long cross-ID load stream") {
      for (seed <- M3AxiLongStreamSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 2048
          val schedule = seededAxiSchedule(seed, cycles)
          val selectorSeed = seed ^ 0x4a3b2c1dL
          val selectorRandom = new Random(selectorSeed)
          val lines = (0 until 8).map(index => BigInt("80001000", 16) + index * 0x20)
          val expected = (0 until 8).map(index => BigInt("20000000", 16) + index)
          val dataArs = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
          val liveDataOwners = scala.collection.mutable.Map.empty[BigInt, (BigInt, Int)]
          val observedDataIds = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var lastDataResponseId = Option.empty[BigInt]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 8 -> BigInt("0200a183", 16), // lw x3,32(x1)
              ResetVector + 12 -> BigInt("0400a203", 16), // lw x4,64(x1)
              ResetVector + 16 -> BigInt("0600a283", 16), // lw x5,96(x1)
              ResetVector + 20 -> BigInt("0800a303", 16), // lw x6,128(x1)
              ResetVector + 24 -> BigInt("0a00a383", 16), // lw x7,160(x1)
              ResetVector + 28 -> BigInt("0c00a403", 16), // lw x8,192(x1)
              ResetVector + 32 -> BigInt("0e00a483", 16), // lw x9,224(x1)
              ResetVector + 36 -> BigInt("00100073", 16),
              lines(0) -> expected(0), lines(1) -> expected(1),
              lines(2) -> expected(2), lines(3) -> expected(3),
              lines(4) -> expected(4), lines(5) -> expected(5),
              lines(6) -> expected(6), lines(7) -> expected(7)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                val dataCandidates = candidates.filter(index =>
                  lines.contains(pending(index)._2))
                val alternativeData = lastDataResponseId match {
                  case Some(previous) => dataCandidates.filter(index =>
                    pending(index)._1 != previous)
                  case None => dataCandidates
                }
                val selectedPool = if (alternativeData.nonEmpty) alternativeData
                else if (dataCandidates.nonEmpty) dataCandidates
                else candidates
                if (selectedPool.nonEmpty) {
                  val selected = selectedPool(selectorRandom.nextInt(selectedPool.size))
                  if (lines.contains(pending(selected)._2)) {
                    lastDataResponseId = Some(pending(selected)._1)
                  }
                  selected
                } else 0
              },
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                if (arFire) {
                  val id = core.io.axi.ar.bits.id.peek().litValue
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (lines.contains(address)) {
                    assert(!liveDataOwners.contains(id),
                      s"seed=$seed reused live data AXI owner $id")
                    dataArs += ((address, id))
                    liveDataOwners.update(id, (address, 8))
                  }
                }
                if (rFire) {
                  val id = core.io.axi.r.bits.id.peek().litValue
                  liveDataOwners.get(id).foreach { case (address, remaining) =>
                    observedDataIds += id
                    if (remaining == 1) liveDataOwners.remove(id)
                    else liveDataOwners.update(id, (address, remaining - 1))
                  }
                }
              })

            val retired = throughFirstTrap(events)
            val dataIdSwitch = observedDataIds.sliding(2).exists(pair => pair.head != pair.last)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, ars=$dataArs, " +
              s"dataIds=$observedDataIds, trace=$retired") {
              assert(dataArs.map(_._1).toSet == lines.toSet)
              assert(dataArs.size == lines.size)
              assert(dataArs.map(_._2).distinct.size == 4)
              assert(dataArs.groupBy(_._2).exists { case (_, owners) => owners.size > 1 })
              assert(liveDataOwners.isEmpty)
              assert(dataIdSwitch)
              assert(retired.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12, ResetVector + 16,
                ResetVector + 20, ResetVector + 24, ResetVector + 28,
                ResetVector + 32, ResetVector + 36))
              for (index <- lines.indices) {
                val pc = ResetVector + 4 + index * 4
                val register = index + 2
                assert(retired.exists(event => event.pc == pc && event.gprWrite &&
                  event.gprAddress == register && event.gprData == expected(index) &&
                  event.memoryAddress == lines(index) &&
                  event.memoryReadData == expected(index) && !event.trap))
              }
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "long-owner-reuse", schedule, events,
                Some(selectorSeed))
              throw failure
          }
        }
      }
    }

    it("keeps the older load fault when a younger RRESP fault drains first") {
      for (seed <- M3AxiFaultOrderSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1024
          val schedule = seededAxiSchedule(seed, cycles)
          val olderAddress = BigInt("80001000", 16)
          val youngerAddress = BigInt("80001020", 16)
          val dataOwnerAddress = scala.collection.mutable.Map.empty[BigInt, BigInt]
          val dataOwnerBeats = scala.collection.mutable.Map.empty[BigInt, Int]
          val faultCompletionOrder = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var events = Seq.empty[TraceSample]
          def isOlderLine(address: BigInt): Boolean =
            address >= olderAddress && address < olderAddress + 32
          def isYoungerLine(address: BigInt): Boolean =
            address >= youngerAddress && address < youngerAddress + 32
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1), older
              ResetVector + 8 -> BigInt("0200a183", 16), // lw x3,32(x1), younger
              ResetVector + 12 -> BigInt("00100073", 16),
              olderAddress -> BigInt("11111111", 16),
              youngerAddress -> BigInt("22222222", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                val younger = candidates.find(index =>
                  isYoungerLine(pending(index)._2))
                younger.getOrElse(candidates.headOption.getOrElse(0))
              },
              readValidForCycle = (_, _, address) =>
                (!isOlderLine(address) && !isYoungerLine(address)) ||
                  dataOwnerAddress.size == 2,
              rResponse = (_, address) =>
                if (isOlderLine(address) || isYoungerLine(address)) 2 else 0,
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                if (arFire) {
                  val id = core.io.axi.ar.bits.id.peek().litValue
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (address == olderAddress || address == youngerAddress) {
                    dataOwnerAddress.update(id, address)
                    dataOwnerBeats.update(id, 8)
                  }
                }
                if (rFire) {
                  val id = core.io.axi.r.bits.id.peek().litValue
                  dataOwnerBeats.get(id).foreach { remaining =>
                    if (remaining == 1) {
                      faultCompletionOrder += dataOwnerAddress(id)
                      dataOwnerBeats.remove(id)
                    } else dataOwnerBeats.update(id, remaining - 1)
                  }
                }
              })

            val retired = throughFirstTrap(events)
            val trap = retired.find(event => event.trap && event.pc == ResetVector + 4)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, owners=$dataOwnerAddress, " +
              s"faultOrder=$faultCompletionOrder, trace=$retired") {
              assert(dataOwnerAddress.values.toSet == Set(olderAddress, youngerAddress))
              assert(faultCompletionOrder.take(2) == Seq(youngerAddress, olderAddress))
              assert(trap.exists(event => event.cause == 5 &&
                event.trapValue == olderAddress))
              assert(!retired.exists(event => event.pc == ResetVector + 8 && !event.trap))
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "reverse-rresp-fault-order", schedule, events)
              throw failure
          }
        }
      }
    }

    it("keeps the oldest of four RRESP faults after reverse cross-ID drain") {
      for (seed <- M3AxiFourFaultOrderSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1536
          val schedule = seededAxiSchedule(seed, cycles)
          val lines = Seq(
            BigInt("80001000", 16), BigInt("80001020", 16),
            BigInt("80001040", 16), BigInt("80001060", 16))
          val ownerRemaining = scala.collection.mutable.Map.empty[BigInt, (BigInt, Int)]
          val faultCompletionOrder = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          def dataLine(address: BigInt): Option[BigInt] =
            lines.find(line => address >= line && address < line + 32)
          var allDataOwnersObserved = false
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1), oldest
              ResetVector + 8 -> BigInt("0200a183", 16), // lw x3,32(x1)
              ResetVector + 12 -> BigInt("0400a203", 16), // lw x4,64(x1)
              ResetVector + 16 -> BigInt("0600a283", 16), // lw x5,96(x1), youngest
              ResetVector + 20 -> BigInt("00100073", 16),
              lines(0) -> BigInt("11111111", 16),
              lines(1) -> BigInt("22222222", 16),
              lines(2) -> BigInt("33333333", 16),
              lines(3) -> BigInt("44444444", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => cycle < 128 || schedule.rValid(cycle),
              readSelectForCycle = (cycle, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                val dataCandidates = candidates.filter(index =>
                  dataLine(pending(index)._2).nonEmpty)
                if (allDataOwnersObserved && dataCandidates.nonEmpty) {
                  dataCandidates.maxBy(index => lines.indexOf(dataLine(pending(index)._2).get))
                } else if (cycle < 128 &&
                    candidates.exists(index => pending(index)._1 == 0)) {
                  candidates.find(index => pending(index)._1 == 0).get
                } else {
                  candidates.find(index => dataLine(pending(index)._2).isEmpty).getOrElse(
                    candidates.headOption.getOrElse(0))
                }
              },
              readValidForCycle = (_, _, address) =>
                dataLine(address).isEmpty || allDataOwnersObserved,
              rResponse = (_, address) => if (dataLine(address).nonEmpty) 2 else 0,
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                if (arFire) {
                  val address = core.io.axi.ar.bits.addr.peek().litValue
                  if (lines.contains(address)) {
                    ownerRemaining.update(core.io.axi.ar.bits.id.peek().litValue,
                      (address, 8))
                    allDataOwnersObserved ||= ownerRemaining.size == lines.size
                  }
                }
                if (rFire) {
                  val id = core.io.axi.r.bits.id.peek().litValue
                  ownerRemaining.get(id).foreach { case (address, remaining) =>
                    if (remaining == 1) {
                      faultCompletionOrder += address
                      ownerRemaining.remove(id)
                    } else ownerRemaining.update(id, (address, remaining - 1))
                  }
                }
              })

            val retired = withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"ownersObserved=$allDataOwnersObserved, faultOrder=$faultCompletionOrder, " +
              s"live=$ownerRemaining, trace=$events\n") {
              throughFirstTrap(events)
            }
            val oldestTrap = retired.find(event => event.trap && event.pc == ResetVector + 4)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"faultOrder=$faultCompletionOrder, live=$ownerRemaining, trace=$retired") {
              assert(faultCompletionOrder == lines.reverse)
              assert(ownerRemaining.isEmpty)
              assert(oldestTrap.exists(event => event.cause == 5 &&
                event.trapValue == lines.head))
              for (pc <- Seq(ResetVector + 8, ResetVector + 12, ResetVector + 16)) {
                assert(!retired.exists(event => event.pc == pc && !event.trap))
              }
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "four-owner-reverse-rresp-fault-order",
                schedule, events)
              throw failure
          }
        }
      }
    }

    it("keeps an older device BRESP fault when a younger RRESP fault drains first") {
      for (seed <- M3AxiMixedFaultSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1280
          val schedule = seededAxiSchedule(seed, cycles)
          val deviceAddress = BigInt("b0000000", 16)
          val dataAddress = BigInt("80001000", 16)
          val dataOwnerBeats = scala.collection.mutable.Map.empty[BigInt, Int]
          var deviceAwAccepted = false
          var deviceWLastAccepted = false
          var youngerFaultDrained = false
          var youngerFaultDrainCycle = Option.empty[Int]
          var olderFaultDrainCycle = Option.empty[Int]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("b00000b7", 16), // lui x1,0xb0000
              ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
              ResetVector + 8 -> BigInt("0020a023", 16), // sw x2,0(x1), older
              ResetVector + 12 -> BigInt("80001237", 16), // lui x4,0x80001
              ResetVector + 16 -> BigInt("00022183", 16), // lw x3,0(x4), younger
              ResetVector + 20 -> BigInt("00100073", 16),
              dataAddress -> BigInt("11223344", 16)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              readValidForCycle = (_, _, address) =>
                address < dataAddress || address >= dataAddress + 32 ||
                  (deviceAwAccepted && deviceWLastAccepted),
              rResponse = (_, address) =>
                if (address >= dataAddress && address < dataAddress + 32) 2 else 0,
              writeResponse = Some(2),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              // The older device transaction has been accepted, but its B
              // response cannot win merely because it has a separate channel.
              bValidForCycle = cycle => youngerFaultDrained && schedule.bValid(cycle),
              observeCycle = (core, cycle) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val wFire = core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (arFire && core.io.axi.ar.bits.addr.peek().litValue == dataAddress) {
                  dataOwnerBeats.update(core.io.axi.ar.bits.id.peek().litValue, 8)
                }
                if (rFire) {
                  val id = core.io.axi.r.bits.id.peek().litValue
                  dataOwnerBeats.get(id).foreach { remaining =>
                    if (remaining == 1) {
                      youngerFaultDrained = true
                      youngerFaultDrainCycle = Some(cycle)
                      dataOwnerBeats.remove(id)
                    } else dataOwnerBeats.update(id, remaining - 1)
                  }
                }
                if (awFire && core.io.axi.aw.bits.id.peek().litValue == 6) {
                  deviceAwAccepted = true
                }
                if (wFire && core.io.axi.w.bits.last.peek().litToBoolean) {
                  deviceWLastAccepted = true
                }
                if (bFire) olderFaultDrainCycle = Some(cycle)
              })

            val retired = throughFirstTrap(events)
            val trap = retired.find(event => event.trap && event.pc == ResetVector + 8)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, dataBeats=$dataOwnerBeats, " +
              s"youngerR=$youngerFaultDrainCycle, olderB=$olderFaultDrainCycle, trace=$retired") {
              assert(deviceAwAccepted && deviceWLastAccepted)
              assert(youngerFaultDrained)
              assert(youngerFaultDrainCycle.exists(younger =>
                olderFaultDrainCycle.exists(older => younger < older)))
              assert(trap.exists(event => event.cause == 7 &&
                event.trapValue == deviceAddress))
              assert(!retired.exists(event => event.pc == ResetVector + 16 && !event.trap))
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "reverse-rresp-bresp-fault-order", schedule, events)
              throw failure
          }
        }
      }
    }

    it("starts clean AXI read and write owner epochs across reset") {
      for (seed <- M3AxiResetSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 1280
          val schedule = seededAxiSchedule(seed, cycles)
          val selectorSeed = seed ^ 0x13579bdfL
          val selectorRandom = new Random(selectorSeed)
          val lines = Seq(
            BigInt("80001000", 16), BigInt("80001020", 16),
            BigInt("80001040", 16), BigInt("80001060", 16))
          val expected = Seq(
            BigInt("10203040", 16), BigInt("50607080", 16),
            BigInt("90a0b0c0", 16), BigInt("d0e0f000", 16))
          val deviceAddress = BigInt("a0000000", 16)
          val preResetOwnerIds = scala.collection.mutable.Set.empty[BigInt]
          var faultOfferId = Option.empty[BigInt]
          var faultBeatAccepted = false
          var resetPhase = 0
          var resetCount = 0
          var preResetAw = false
          var preResetW = false
          var finalAw = false
          var finalW = false
          var finalB = false
          var events = Seq.empty[TraceSample]

          def isDataAddress(address: BigInt): Boolean =
            address >= lines.head && address < lines.last + 32

          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("0000a103", 16), // lw x2,0(x1)
              ResetVector + 8 -> BigInt("0200a183", 16), // lw x3,32(x1)
              ResetVector + 12 -> BigInt("0400a203", 16), // lw x4,64(x1)
              ResetVector + 16 -> BigInt("0600a283", 16), // lw x5,96(x1)
              ResetVector + 20 -> BigInt("a0000337", 16), // lui x6,0xa0000
              ResetVector + 24 -> BigInt("05a00393", 16), // addi x7,x0,90
              ResetVector + 28 -> BigInt("00732023", 16), // sw x7,0(x6)
              ResetVector + 32 -> BigInt("00100073", 16),
              lines(0) -> expected(0), lines(1) -> expected(1),
              lines(2) -> expected(2), lines(3) -> expected(3)
            ), cycles = cycles,
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              writeResponse = Some(0),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => resetPhase == 2 && schedule.bValid(cycle),
              readSelectForCycle = (_, pending) => {
                val candidates = pending.indices.filter { index =>
                  !pending.take(index).exists(_._1 == pending(index)._1)
                }
                val dataCandidates = candidates.filter(index =>
                  isDataAddress(pending(index)._2))
                val instructionCandidates = candidates.filterNot(index =>
                  isDataAddress(pending(index)._2))
                if (resetPhase == 0 && preResetOwnerIds.size == 4 &&
                    !faultBeatAccepted && dataCandidates.nonEmpty) {
                  val selected = dataCandidates(selectorRandom.nextInt(dataCandidates.size))
                  faultOfferId = Some(pending(selected)._1)
                  selected
                } else if (resetPhase == 0 && instructionCandidates.nonEmpty) {
                  instructionCandidates.head
                } else if (candidates.nonEmpty) {
                  candidates(selectorRandom.nextInt(candidates.size))
                } else 0
              },
              readValidForCycle = (_, _, address) => {
                !isDataAddress(address) || resetPhase > 0 ||
                  (preResetOwnerIds.size == 4 && !faultBeatAccepted)
              },
              rResponse = (_, address) =>
                if (resetPhase == 0 && isDataAddress(address)) 2 else 0,
              resetForCycle = (_, _) => {
                val resetReadOwners = resetPhase == 0 && faultBeatAccepted
                val resetWriteOwner = resetPhase == 1 && preResetAw && preResetW
                val assertReset = resetReadOwners || resetWriteOwner
                if (assertReset) {
                  resetPhase += 1
                  resetCount += 1
                }
                assertReset
              },
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val rFire = core.io.axi.r.valid.peek().litToBoolean &&
                  core.io.axi.r.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val wFire = core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                val deviceAw = awFire && core.io.axi.aw.bits.id.peek().litValue == 6
                if (resetPhase == 0 && arFire &&
                    isDataAddress(core.io.axi.ar.bits.addr.peek().litValue)) {
                  preResetOwnerIds += core.io.axi.ar.bits.id.peek().litValue
                }
                if (resetPhase == 0 && rFire && faultOfferId.contains(
                    core.io.axi.r.bits.id.peek().litValue)) {
                  faultBeatAccepted = true
                }
                if (resetPhase == 1) {
                  preResetAw ||= deviceAw
                  preResetW ||= wFire && (preResetAw || deviceAw)
                } else if (resetPhase == 2) {
                  finalAw ||= deviceAw
                  finalW ||= wFire && (finalAw || deviceAw)
                  finalB ||= bFire && core.io.axi.b.bits.id.peek().litValue == 6
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"preResetOwners=$preResetOwnerIds, faultBeat=$faultBeatAccepted, " +
              s"resetCount=$resetCount, preWrite=($preResetAw,$preResetW), " +
              s"finalWrite=($finalAw,$finalW,$finalB), trace=$retired") {
              assert(preResetOwnerIds.size == 4)
              assert(faultBeatAccepted && resetCount == 2 && resetPhase == 2)
              assert(preResetAw && preResetW)
              assert(finalAw && finalW && finalB)
              assert(retired.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12, ResetVector + 16,
                ResetVector + 20, ResetVector + 24, ResetVector + 28,
                ResetVector + 32))
              for ((pc, register, value, address) <- Seq(
                  (ResetVector + 4, 2, expected(0), lines(0)),
                  (ResetVector + 8, 3, expected(1), lines(1)),
                  (ResetVector + 12, 4, expected(2), lines(2)),
                  (ResetVector + 16, 5, expected(3), lines(3)))) {
                assert(retired.exists(event => event.pc == pc && event.gprWrite &&
                  event.gprAddress == register && event.gprData == value &&
                  event.memoryAddress == address && event.memoryReadData == value &&
                  !event.trap))
              }
              assert(retired.exists(event => event.pc == ResetVector + 28 &&
                event.memoryAddress == deviceAddress && event.memoryWriteMask == 15 &&
                event.memoryWriteData == 90 && !event.trap))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "reset-read-write-epochs", schedule, events,
                Some(selectorSeed))
              throw failure
          }
        }
      }
    }

    it("resets an accepted ID-5 writeback before its response") {
      for (seed <- M3AxiWritebackResetSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true,
          enableHostFlush = true))) { dut =>
          clearInputs(dut)
          val cycles = 896
          val schedule = seededAxiSchedule(seed, cycles)
          val storeAddress = BigInt("80000100", 16)
          dut.io.hostFlush.get.enable.poke(true)
          dut.io.hostFlush.get.address.poke(storeAddress)
          var resetIssued = false
          var preResetAw = false
          var preResetWLast = false
          var finalAw = false
          var finalWLast = false
          var finalB = false
          var activeWriteId = Option.empty[BigInt]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("00000097", 16), // auipc x1,0
              ResetVector + 4 -> BigInt("05a00113", 16), // addi x2,x0,90
              ResetVector + 8 -> BigInt("1020a023", 16), // sw x2,256(x1)
              ResetVector + 12 -> BigInt("00100073", 16)
            ), cycles = cycles, writeResponse = Some(0),
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => resetIssued && schedule.bValid(cycle),
              resetForCycle = (_, _) => {
                val assertReset = preResetAw && preResetWLast && !resetIssued
                if (assertReset) resetIssued = true
                assertReset
              },
              observeCycle = (core, _) => {
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val wFire = core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                if (awFire) activeWriteId = Some(core.io.axi.aw.bits.id.peek().litValue)
                if (!resetIssued && activeWriteId.contains(BigInt(5))) {
                  preResetAw ||= awFire
                  preResetWLast ||= wFire && core.io.axi.w.bits.last.peek().litToBoolean
                } else if (resetIssued && activeWriteId.contains(BigInt(5))) {
                  finalAw ||= awFire
                  finalWLast ||= wFire && core.io.axi.w.bits.last.peek().litToBoolean
                }
                if (wFire && core.io.axi.w.bits.last.peek().litToBoolean) {
                  activeWriteId = None
                }
                if (resetIssued && bFire && core.io.axi.b.bits.id.peek().litValue == 5) {
                  finalB = true
                }
              })

            val retired = throughFirstTrap(events)
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"preWrite=($preResetAw,$preResetWLast), " +
              s"finalWrite=($finalAw,$finalWLast,$finalB), trace=$retired") {
              assert(resetIssued && preResetAw && preResetWLast)
              assert(finalAw && finalWLast && finalB)
              assert(retired.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12))
              assert(retired.exists(event => event.pc == ResetVector + 8 &&
                event.memoryAddress == storeAddress && event.memoryWriteMask == 15 &&
                event.memoryWriteData == 90 && !event.trap))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "id5-writeback-reset", schedule, events)
              throw failure
          }
        }
      }
    }

    it("resets an accepted ID-7 atomic write before its response") {
      for (seed <- M3AxiWritebackResetSeeds) {
        simulate(new ZirconCore(ZirconCoreConfig.default.copy(enableTrace = true))) { dut =>
          clearInputs(dut)
          val cycles = 896
          val schedule = seededAxiSchedule(seed, cycles)
          val atomicAddress = BigInt("80001000", 16)
          val initialData = BigInt("10000000", 16)
          var resetIssued = false
          var preResetAr = false
          var preResetAw = false
          var preResetWLast = false
          var finalAr = false
          var finalAw = false
          var finalWLast = false
          var finalB = false
          var activeWriteId = Option.empty[BigInt]
          var events = Seq.empty[TraceSample]
          try {
            events = runProgram(dut, Map(
              ResetVector -> BigInt("800010b7", 16), // lui x1,0x80001
              ResetVector + 4 -> BigInt("00500113", 16), // addi x2,x0,5
              ResetVector + 8 -> BigInt("0020a1af", 16), // amoadd.w x3,x2,(x1)
              ResetVector + 12 -> BigInt("00100073", 16),
              atomicAddress -> initialData
            ), cycles = cycles, writeResponse = Some(0),
              arReadyForCycle = cycle => schedule.arReady(cycle),
              rValidForCycle = cycle => schedule.rValid(cycle),
              awReadyForCycle = cycle => schedule.awReady(cycle),
              wReadyForCycle = cycle => schedule.wReady(cycle),
              bValidForCycle = cycle => resetIssued && schedule.bValid(cycle),
              resetForCycle = (_, _) => {
                val assertReset = preResetAr && preResetAw && preResetWLast && !resetIssued
                if (assertReset) resetIssued = true
                assertReset
              },
              observeCycle = (core, _) => {
                val arFire = core.io.axi.ar.valid.peek().litToBoolean &&
                  core.io.axi.ar.ready.peek().litToBoolean
                val awFire = core.io.axi.aw.valid.peek().litToBoolean &&
                  core.io.axi.aw.ready.peek().litToBoolean
                val wFire = core.io.axi.w.valid.peek().litToBoolean &&
                  core.io.axi.w.ready.peek().litToBoolean
                val bFire = core.io.axi.b.valid.peek().litToBoolean &&
                  core.io.axi.b.ready.peek().litToBoolean
                val atomicAr = arFire && core.io.axi.ar.bits.id.peek().litValue == 7
                if (awFire) activeWriteId = Some(core.io.axi.aw.bits.id.peek().litValue)
                if (!resetIssued) {
                  preResetAr ||= atomicAr
                  preResetAw ||= activeWriteId.contains(BigInt(7)) && awFire
                  preResetWLast ||= activeWriteId.contains(BigInt(7)) && wFire &&
                    core.io.axi.w.bits.last.peek().litToBoolean
                } else {
                  finalAr ||= atomicAr
                  finalAw ||= activeWriteId.contains(BigInt(7)) && awFire
                  finalWLast ||= activeWriteId.contains(BigInt(7)) && wFire &&
                    core.io.axi.w.bits.last.peek().litToBoolean
                  finalB ||= bFire && core.io.axi.b.bits.id.peek().litValue == 7
                }
                if (wFire && core.io.axi.w.bits.last.peek().litToBoolean) {
                  activeWriteId = None
                }
              })

            val retired = throughFirstTrap(events)
            val afterFirstEpoch = initialData + 5
            withClue(s"seed=0x${java.lang.Long.toHexString(seed)}, " +
              s"preAtomic=($preResetAr,$preResetAw,$preResetWLast), " +
              s"finalAtomic=($finalAr,$finalAw,$finalWLast,$finalB), trace=$retired") {
              assert(resetIssued && preResetAr && preResetAw && preResetWLast)
              assert(finalAr && finalAw && finalWLast && finalB)
              assert(retired.map(_.pc) == Seq(ResetVector, ResetVector + 4,
                ResetVector + 8, ResetVector + 12))
              assert(retired.exists(event => event.pc == ResetVector + 8 &&
                event.gprWrite && event.gprAddress == 3 && event.gprData == afterFirstEpoch &&
                event.memoryAddress == atomicAddress && event.memoryReadData == afterFirstEpoch &&
                event.memoryWriteMask == 15 && event.memoryWriteData == afterFirstEpoch + 5 &&
                !event.trap))
              assert(retired.last.trap && retired.last.cause == 3)
            }
          } catch {
            case failure: Throwable =>
              saveM3AxiStressFailure(seed, "id7-atomic-reset", schedule, events)
              throw failure
          }
        }
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
