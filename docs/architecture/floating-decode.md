# RV32F metadata decoder

`RV32FMetadataDecoder` describes RV32F single-precision encodings. It recognizes
`FLW`/`FSW`, fused multiply-add forms, arithmetic, sign/min/max, conversions,
comparisons, classification, and integer/float moves. The decoded metadata
identifies integer and FPR namespaces independently, up to three FPR sources,
result namespace, memory direction, and whether the instruction consumes one of
the five static rounding modes or the dynamic `frm` mode.

`FloatingAdmission` resolves dynamic `rm=111` to the committed `frm` value and
exports this effective mode to dispatch. Modes 0--4 are legal; reserved 5/6
make a rounding operation illegal. `FCVT.S.W/U` consumes the frozen value;
later CSR writes cannot alter its in-flight conversion or any future arithmetic
operation.

Reserved rounding encodings and unsupported formats are illegal in this
metadata decoder. `FSQRT.S`, conversion, class, and move encodings additionally
check their required `rs2` and `funct3` fields.

## Integration boundary

The decoder reaches executable RTL only through the end-to-end protocol in
[Executable floating-point path](floating-execution.md). `FloatingAdmission`
identifies the RV32F namespace separately from integer instructions. With
`mstatus.FS != Off`, it admits the verified E2 subset:
`FMV.W.X`, `FMV.X.W`, `FSGNJ.S`, `FSGNJN.S`, `FSGNJX.S`, `FMIN.S`, `FMAX.S`,
`FEQ.S`, `FLT.S`, `FLE.S`, `FCLASS.S`, `FCVT.S.W`, and `FCVT.S.WU`. Their FPR
scoreboard, operand read, E2 execution, retained result/flag state, commit,
and retire trace paths are connected.

Every other F encoding, every reserved encoding, and every F instruction with
`FS=Off` remains on the precise illegal-instruction path until the required
execution and commit protocol has been implemented and verified. Admission
alone creates no completion or state mutation.

## Verification mapping

`FloatingDecoderSpec` checks memory namespace selection, arithmetic/FMA source
counts, static and dynamic rounding metadata, conversion/compare/move result
namespaces, reserved encodings, and that `RV32IDecoder` still rejects an FADD.S
encoding.
