package zircon.backend

import chisel3._
import chisel3.util._
import zircon.ZirconCoreConfig

/** Integrated integer architectural state and completion handshake domain.
  *
  * Dispatch/rename provide ROB entries and physical allocations. Endpoint
  * results are accepted or discarded by the ROB before the same live
  * handshake writes the PRF, marks the destination ready, and wakes the IQ.
  */
class IntegerBackendState(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  private val physicalWidth = log2Ceil(config.intPhysicalRegisters)
  private val robCountWidth = log2Ceil(config.robEntries + 1)

  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(config.decodeWidth,
      Decoupled(new ROBEnqueue(config))))
    val enqueueTag = Output(Vec(config.decodeWidth,
      Valid(UInt(config.robTagWidth.W))))
    val enqueueCapacity = Output(UInt(2.W))
    val readyAllocation = Input(Vec(config.decodeWidth,
      Valid(UInt(physicalWidth.W))))

    val endpointCompletion = Flipped(Vec(5,
      Decoupled(new CompletionResult(config))))
    val wakeup = Output(Vec(config.completionWidth,
      new PhysicalWakeup(config)))
    val completionAccepted = Output(Vec(config.completionWidth, Bool()))
    val completionDiscarded = Output(Vec(config.completionWidth, Bool()))

    val readPhysical = Input(Vec(6, UInt(physicalWidth.W)))
    val readData = Output(Vec(6, UInt(32.W)))
    val integerReady = Output(UInt(config.intPhysicalRegisters.W))

    val executionRead = Input(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val executionContext = Output(Vec(2,
      Valid(new ROBExecutionContext(config))))
    val memoryExecutionRead = Input(Vec(2,
      Valid(UInt(config.robTagWidth.W))))
    val memoryExecutionContext = Output(Vec(2,
      Valid(new ROBExecutionContext(config))))

    val commit = Vec(config.commitWidth, Decoupled(new ROBCommit(config)))
    val rollback = Flipped(Decoupled(UInt(config.robTagWidth.W)))
    val rollbackUndo = Decoupled(new ROBRollbackBundle(config))
    val rollbackActive = Output(Bool())
    val rollbackDone = Output(Bool())

    val robHeadTag = Output(UInt(config.robTagWidth.W))
    val robHead = Output(Valid(new ROBCommit(config)))
    val robCount = Output(UInt(robCountWidth.W))
    val squash = Input(Valid(UInt(config.robTagWidth.W)))
    val flush = Input(Bool())
  })

  val rob = Module(new ReorderBuffer(config))
  val completion = Module(new CompletionWritebackRouter(config))
  val prf = Module(new IntegerPhysicalRegisterFile(config))
  val readyTable = Module(new IntegerReadyTable(config))

  rob.io.enqueue <> io.enqueue
  io.enqueueTag := rob.io.enqueueTag
  io.enqueueCapacity := rob.io.enqueueCapacity
  rob.io.commit <> io.commit
  for (port <- 0 until 2) {
    rob.io.executionRead(port) := io.executionRead(port)
    io.executionContext(port) := rob.io.executionContext(port)
    rob.io.executionRead(port + 2) := io.memoryExecutionRead(port)
    io.memoryExecutionContext(port) := rob.io.executionContext(port + 2)
  }
  rob.io.rollback <> io.rollback
  rob.io.rollbackUndo <> io.rollbackUndo
  io.rollbackActive := rob.io.rollbackActive
  io.rollbackDone := rob.io.rollbackDone
  io.robHeadTag := rob.io.headTag
  io.robHead := rob.io.head
  io.robCount := rob.io.count
  rob.io.flush := io.flush

  completion.io.endpoints <> io.endpointCompletion
  completion.io.robCompletionAccepted := rob.io.completionAccepted
  completion.io.robCompletionDiscarded := rob.io.completionDiscarded
  io.completionAccepted := rob.io.completionAccepted
  io.completionDiscarded := rob.io.completionDiscarded
  completion.io.robHeadTag := rob.io.headTag
  completion.io.squash := io.squash
  completion.io.flush := io.flush
  rob.io.completion := completion.io.robCompletion

  prf.io.readPhysical := io.readPhysical
  io.readData := prf.io.readData
  prf.io.write := completion.io.prfWrite

  readyTable.io.allocate := io.readyAllocation
  for (port <- 0 until config.completionWidth) {
    readyTable.io.complete(port).valid := completion.io.prfWrite(port).valid
    readyTable.io.complete(port).bits :=
      completion.io.prfWrite(port).bits.physical
  }
  io.integerReady := readyTable.io.ready
  io.wakeup := completion.io.wakeup

  when(io.flush) {
    assert(!completion.io.prfWrite.map(_.valid).reduce(_ || _),
      "integer backend state wrote the PRF during global flush")
    assert(!io.wakeup.map(_.valid).reduce(_ || _),
      "integer backend state emitted wakeup during global flush")
  }
}
