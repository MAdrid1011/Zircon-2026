# RV32F metadata decoder

`RV32FMetadataDecoder` is an M4 foundation that describes, but does not admit,
RV32F single-precision encodings. It recognizes `FLW`/`FSW`, fused multiply-add
forms, arithmetic, sign/min/max, conversions, comparisons, classification, and
integer/float moves. The decoded metadata identifies integer and FPR namespaces
independently, up to three FPR sources, result namespace, memory direction, and
whether the instruction consumes one of the five static rounding modes or the
dynamic `frm` mode.

Reserved rounding encodings and unsupported formats are illegal in this
metadata decoder. `FSQRT.S`, conversion, class, and move encodings additionally
check their required `rs2` and `funct3` fields.

## Integration boundary

This decoder is deliberately not connected to `RV32IDecoder`, frontend
dispatch, the ROB, E2, either LSU, or commit. Consequently all RV32F opcode
encodings remain illegal in executable Zircon RTL. It creates no issue,
completion, FPR write, CSR flag update, AXI request, or retire event.

M4 integration may consume this metadata only after the FPR scoreboard,
two-cycle FMA source acquisition, E2 FPU operations, result queue, precise
FPR/`fflags` commit, `FS` legality, and trace fields are connected as one
verified path.

## Verification mapping

`FloatingDecoderSpec` checks memory namespace selection, arithmetic/FMA source
counts, static and dynamic rounding metadata, conversion/compare/move result
namespaces, reserved encodings, and that `RV32IDecoder` still rejects an FADD.S
encoding.
