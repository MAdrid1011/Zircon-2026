import chisel3._
import chisel3.util._

class VectorConfigurationRequest extends Bundle {
    val vtype = UInt(32.W)
    val avl = UInt(32.W)
    val rs1Zero = Bool()
    val rdZero = Bool()
    val immediateAvl = Bool()
    val oldVl = UInt(32.W)
}

object VectorConfiguration {
    /** An immediate configuration that preserves all speculative vector state
      * needs no decode fence. It still executes and retires normally, including
      * its scalar VL result. Register AVL and VTYPE forms keep the full fence.
      */
    def unchangedImmediate(inst: UInt, state: VectorCSRState): Bool =
        inst(31, 30) === 3.U && state.vtype === inst(29, 20).pad(32) &&
            state.vl === inst(19, 15).pad(32) && state.vstart === 0.U
}

/** VSETVL/I/IVLI use the same deterministic min(AVL, VLMAX) policy. */
class VectorConfiguration extends RawModule {
    val io = IO(new Bundle {
        val request = Input(new VectorConfigurationRequest)
        val vl = Output(UInt(32.W))
        val vtype = Output(UInt(32.W))
    })
    val requested = io.request.vtype
    val sew = requested(5, 3)
    val lmul = requested(2, 0).asSInt
    val exponent = 5.S(5.W) - sew.zext + lmul
    // ELEN32 fractional groups support SEW <= LMUL * ELEN, matching Spike.
    val fractionalLegal = !requested(2) || sew.zext - lmul <= 2.S
    val legal = !requested(31, 8).orR && sew <= 2.U &&
        requested(2, 0) =/= 4.U && fractionalLegal
    val maximum = (1.U(9.W) << exponent.asUInt)(8, 0)
    val keepVl = io.request.rs1Zero && io.request.rdZero && !io.request.immediateAvl
    val maxVl = io.request.rs1Zero && !io.request.rdZero && !io.request.immediateAvl
    val avl = Mux(keepVl, io.request.oldVl, io.request.avl)
    io.vl := Mux(legal, Mux(maxVl || avl >= maximum, maximum, avl), 0.U)
    io.vtype := Mux(legal, requested, "h80000000".U)
}
