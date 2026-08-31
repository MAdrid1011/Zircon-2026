package zircon.memory

import chisel3._
import chisel3.util._
import zircon.{PMARegionKind, ZirconCoreConfig}
import zircon.backend.{EndpointMask, UopClass, UopRef}
import zircon.frontend.IntOperation

/** Input to the common M0/M1 address-generation boundary. The base and store
  * value originate from the shared integer PRF read arbitration; atomic aq/rl
  * stays ROB-owned until this point rather than expanding `UopRef`.
  */
class MemoryAddressRequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val uop = new UopRef(config)
  val base = UInt(32.W)
  val storeData = UInt(32.W)
  val atomicAq = Bool()
  val atomicRl = Bool()
}

/** Decoded address, byte lanes, PMA disposition, and exact-fault classification.
  * `m1Eligible` is deliberately narrower than a legal load: device, atomic,
  * inaccessible, and misaligned requests must replay through M0.
  */
class MemoryAddressResult(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val robTag = UInt(config.robTagWidth.W)
  val legalMemoryOperation = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val isAtomic = Bool()
  val unsignedLoad = Bool()
  val accessSize = UInt(2.W)
  val address = UInt(32.W)
  val readMask = UInt(4.W)
  val writeMask = UInt(4.W)
  val writeData = UInt(32.W)
  val pmaKind = UInt(2.W)
  val naturallyAligned = Bool()
  val m1Eligible = Bool()
  val faultValid = Bool()
  val faultCause = UInt(32.W)
  val faultTval = UInt(32.W)
  val aq = Bool()
  val rl = Bool()
}

/** An address-classified LSU request. It is valid only while one LSU owns the
  * request; no completion or external effect is implied by this record.
  */
class MemoryLSURequest(config: ZirconCoreConfig = ZirconCoreConfig.default) extends Bundle {
  val request = new MemoryAddressRequest(config)
  val address = new MemoryAddressResult(config)
  val m1Owner = Bool()
}

/** Shared RV32I/A effective-address and PMA decoder for the two LSU paths. */
class MemoryAddressUnit(
    config: ZirconCoreConfig = ZirconCoreConfig.default
) extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val request = Input(new MemoryAddressRequest(config))
    val result = Output(new MemoryAddressResult(config))
  })

  val request = io.request
  val address = request.base + request.uop.immediate
  val (operation, operationValid) = IntOperation.safe(request.uop.operation(5, 0))

  val isIntegerLoad = operation === IntOperation.Lb || operation === IntOperation.Lh ||
    operation === IntOperation.Lw || operation === IntOperation.Lbu ||
    operation === IntOperation.Lhu
  val isIntegerStore = operation === IntOperation.Sb || operation === IntOperation.Sh ||
    operation === IntOperation.Sw
  val isLr = operation === IntOperation.LrW
  val isSc = operation === IntOperation.ScW
  val isAmo = operation === IntOperation.AmoSwapW || operation === IntOperation.AmoAddW ||
    operation === IntOperation.AmoXorW || operation === IntOperation.AmoAndW ||
    operation === IntOperation.AmoOrW || operation === IntOperation.AmoMinW ||
    operation === IntOperation.AmoMaxW || operation === IntOperation.AmoMinuW ||
    operation === IntOperation.AmoMaxuW
  val isAtomic = isLr || isSc || isAmo
  val isLoad = isIntegerLoad || isLr || isAmo
  val isStore = isIntegerStore || isSc || isAmo
  val legalMemoryOperation = operationValid && (isLoad || isStore)

  val accessSize = MuxLookup(operation.asUInt, 2.U(2.W))(Seq(
    IntOperation.Lb.asUInt -> 0.U,
    IntOperation.Lbu.asUInt -> 0.U,
    IntOperation.Sb.asUInt -> 0.U,
    IntOperation.Lh.asUInt -> 1.U,
    IntOperation.Lhu.asUInt -> 1.U,
    IntOperation.Sh.asUInt -> 1.U
  ))
  val unsignedLoad = operation === IntOperation.Lbu || operation === IntOperation.Lhu
  val byteMask = (1.U(4.W) << address(1, 0))(3, 0)
  val halfMask = Mux(address(1), "b1100".U(4.W), "b0011".U(4.W))
  val accessMask = MuxLookup(accessSize, "b1111".U(4.W))(Seq(
    0.U -> byteMask,
    1.U -> halfMask,
    2.U -> "b1111".U(4.W)
  ))
  val byteData = (request.storeData(7, 0) << (address(1, 0) << 3))(31, 0)
  val halfData = Mux(address(1),
    Cat(request.storeData(15, 0), 0.U(16.W)),
    Cat(0.U(16.W), request.storeData(15, 0)))
  val writeData = MuxLookup(accessSize, request.storeData)(Seq(
    0.U -> byteData,
    1.U -> halfData,
    2.U -> request.storeData
  ))
  val naturallyAligned = MuxLookup(accessSize, false.B)(Seq(
    0.U -> true.B,
    1.U -> !address(0),
    2.U -> !address(1, 0).orR
  ))

  val pma = Module(new PMAClassifier(config))
  pma.io.address := address
  val readsMemory = isLoad
  val writesMemory = isStore
  val pmaAllowed = pma.io.matched &&
    (!readsMemory || pma.io.attributes.readable) &&
    (!writesMemory || pma.io.attributes.writable) &&
    (!isAtomic || pma.io.attributes.atomic)
  val misaligned = legalMemoryOperation && !naturallyAligned
  val accessFault = legalMemoryOperation && !misaligned && !pmaAllowed
  val storeFault = isStore
  val faultCause = Mux(misaligned,
    Mux(storeFault, 6.U, 4.U),
    Mux(storeFault, 7.U, 5.U))

  io.result.robTag := request.uop.robTag
  io.result.legalMemoryOperation := legalMemoryOperation
  io.result.isLoad := isLoad
  io.result.isStore := isStore
  io.result.isAtomic := isAtomic
  io.result.unsignedLoad := unsignedLoad
  io.result.accessSize := accessSize
  io.result.address := address
  io.result.readMask := Mux(isLoad, accessMask, 0.U)
  io.result.writeMask := Mux(isStore, accessMask, 0.U)
  io.result.writeData := writeData
  io.result.pmaKind := pma.io.attributes.kind
  io.result.naturallyAligned := naturallyAligned
  io.result.m1Eligible := legalMemoryOperation && request.uop.uopClass === UopClass.Load &&
    !isAtomic && naturallyAligned && pmaAllowed &&
    pma.io.attributes.kind === PMARegionKind.Memory.code.U
  io.result.faultValid := misaligned || accessFault
  io.result.faultCause := faultCause
  io.result.faultTval := address
  io.result.aq := request.atomicAq
  io.result.rl := request.atomicRl

  when(io.valid && legalMemoryOperation) {
    assert((request.uop.uopClass === UopClass.Load) ===
      (isIntegerLoad && !isAtomic),
      "integer load operations must carry the Load uop class")
    assert((request.uop.uopClass === UopClass.Store) === isIntegerStore,
      "integer store operations must carry the Store uop class")
    assert((request.uop.uopClass === UopClass.Atomic) === isAtomic,
      "RV32A operations must carry the Atomic uop class")
    when(isAtomic) {
      assert(request.uop.allowedEndpoints(3),
        "atomic operation escaped its M0-only endpoint restriction")
    }
  }
}
