import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class RegisterInfoDecoderIO extends Bundle {
    val inst = Input(UInt(32.W))
    val fields = Input(new FrontendPredecodeFields)
    val rinfo = Output(new FrontendRegisterInfo)
    val kind = Output(UInt(3.W))
}

/** PD resolves operand domains and the architectural x1/x5 RAS hints. */
class RegisterInfoDecoder extends RawModule {
    val io = IO(new RegisterInfoDecoderIO)
    val inst = io.inst
    val opcode = inst(6, 0)
    val funct3 = inst(14, 12)
    val funct7 = inst(31, 25)
    val rd = inst(11, 7)
    val rs1 = inst(19, 15)
    val rs2 = inst(24, 20)

    /* Control-Flow Kind and RAS Hints */
    // x1 and x5 are link registers; a different source/destination pair requests pop+push.
    val rdLink = rd === 1.U || rd === 5.U
    val rsLink = rs1 === 1.U || rs1 === 5.U
    val pop = rsLink && (!rdLink || rd =/= rs1)
    io.kind := MuxLookup(io.fields.cfiClass, 0.U)(Seq(
        FrontendCfiClass.Branch.U -> FrontendCfi.Conditional.U,
        FrontendCfiClass.Jal.U -> Mux(rdLink, FrontendCfi.Call.U, FrontendCfi.Jump.U),
        FrontendCfiClass.Jalr.U -> Mux(
            pop,
            Mux(rdLink, FrontendCfi.Coroutine.U, FrontendCfi.Return.U),
            Mux(rdLink, FrontendCfi.IndirectCall.U, FrontendCfi.Indirect.U)
        )
    ))

    /* Logical Register Requests */
    val decoded = WireDefault(0.U.asTypeOf(new FrontendRegisterInfo))
    decoded.src(0).index := rs1
    decoded.src(1).index := rs2
    decoded.src(2).index := inst(31, 27)
    decoded.dest.index := rd
    def source(index: Int, fp: Boolean = false): Unit = {
        decoded.src(index).valid := true.B
        decoded.src(index).isFp := fp.B
    }
    def destination(fp: Boolean = false): Unit = {
        decoded.dest.valid := true.B
        decoded.dest.isFp := fp.B
    }

    /* Integer and FP Operand Classification */
    // Decode checks full instruction legality later; this stage supplies rename requests.
    switch(opcode) {
        // LUI, AUIPC, JAL
        is("h37".U, "h17".U, "h6f".U) {
            destination()
        }
        // JALR
        is("h67".U) {
            when(funct3 === 0.U) {
                source(0)
                destination()
            }
        }
        // Conditional branch
        is("h63".U) {
            when(io.fields.cfiClass === FrontendCfiClass.Branch.U) {
                source(0)
                source(1)
            }
        }
        // Integer load
        is("h03".U) {
            when(funct3 === 0.U || funct3 === 1.U || funct3 === 2.U || funct3 === 4.U || funct3 === 5.U) {
                source(0)
                destination()
            }
        }
        // Integer store
        is("h23".U) {
            when(funct3 <= 2.U) {
                source(0)
                source(1)
            }
        }
        // Integer immediate
        is("h13".U) {
            source(0)
            destination()
        }
        // Integer register-register, including M-extension
        is("h33".U) {
            source(0)
            source(1)
            destination()
        }
        // Word AMO; LR has no second source
        is("h2f".U) {
            when(funct3 === 2.U) {
                source(0)
                destination()
                when(inst(31, 27) =/= 2.U) { source(1) }
            }
        }
        // CSR; immediate forms do not read rs1
        is("h73".U) {
            when(funct3 =/= 0.U && funct3 =/= 4.U) {
                destination()
                when(!funct3(2)) { source(0) }
            }
        }
        // FLW
        is("h07".U) {
            when(funct3 === 2.U) {
                source(0)
                destination(true)
            }
        }
        // FSW
        is("h27".U) {
            when(funct3 === 2.U) {
                source(0)
                source(1, true)
            }
        }
        // FP32 fused multiply-add variants
        is("h43".U, "h47".U, "h4b".U, "h4f".U) {
            when(inst(26, 25) === 0.U) {
                source(0, true)
                source(1, true)
                source(2, true)
                destination(true)
            }
        }
        // FP32 arithmetic, comparison, conversion, and moves
        is("h53".U) {
            switch(funct7) {
                // Arithmetic, sign injection, min/max
                is(0.U, 4.U, 8.U, 12.U, 16.U, 20.U) {
                    source(0, true)
                    source(1, true)
                    destination(true)
                }
                // FSQRT.S
                is(44.U) {
                    when(rs2 === 0.U) {
                        source(0, true)
                        destination(true)
                    }
                }
                // FP comparisons produce an integer result
                is(80.U) {
                    source(0, true)
                    source(1, true)
                    destination()
                }
                // FP32 to integer
                is(96.U) {
                    when(rs2 <= 1.U) {
                        source(0, true)
                        destination()
                    }
                }
                // Integer to FP32
                is(104.U) {
                    when(rs2 <= 1.U) {
                        source(0)
                        destination(true)
                    }
                }
                // FMV.X.W and FCLASS.S
                is(112.U) {
                    when(rs2 === 0.U && funct3 <= 1.U) {
                        source(0, true)
                        destination()
                    }
                }
                // FMV.W.X
                is(120.U) {
                    when(rs2 === 0.U && funct3 === 0.U) {
                        source(0)
                        destination(true)
                    }
                }
            }
        }
    }

    /* Zero-Register Filtering */
    // Integer x0 has no rename request; floating-point f0 remains an ordinary register.
    io.rinfo := decoded
    for (i <- 0 until 3) {
        io.rinfo.src(i).valid := decoded.src(i).valid && (decoded.src(i).isFp || decoded.src(i).index =/= 0.U)
    }
    io.rinfo.dest.valid := decoded.dest.valid && (decoded.dest.isFp || rd =/= 0.U)
}
