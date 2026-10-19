# Shared divide and square root implementation

`DivSqrtSRT4` implements the scalar/shared vector integer divide/remainder and
FP32 divide/square root resource described in [Backend](../../../../../docs/Backend.md)
and [PerformanceCore](../../../../../docs/PerformanceCore.md).

Decoupled request/response ports carry operands, operation, resolved rounding
mode and a transaction tag. Four registered stages classify magnitudes, execute
the iterative radix-4 recurrence, correct/normalize, then round or restore the
integer sign. EX2 owns INIT/ITERATE/RESOLVE; its busy bit excludes completed
output backpressure. Flush clears stage validity and EX2 state. Ready chains
preserve held transactions; response and writeback input retain the original
cycle boundaries and tags.

EX2 RESOLVE uses `BLevelPAdder36` for the carry-save residual CPA. Its modulo-36
result is modulo 2^36 and captures at the RESOLVE boundary; INIT/ITERATE/RESOLVE
retain ownership of the EX2 transaction. Both this CPA and EX3's existing correction adder need the
36-bit grouped structure included in ASIC mapping discovery.

`SRT4Logic.round` jams discarded significand bits, computes the IEEE rounding
increment, and applies it through the existing grouped `BLevelPAdder32` structure.
The significand increment is modulo 2^24; its carry also determines exponent
adjustment. Tininess, special results and exception flags follow IEEE rounding. The mapping boundary avoids a serial gate
chain from overlapping prefix reductions; mapped timing requires independent STA.

`SharedDivSqrtSpec` compares mixed traffic with the SoftFloat oracle, checks
ready/flush/busy timing, and exercises normalization, integer windows and sticky
shifts. CoreMark-PRO and complete-system GEMM remain downstream acceptance tests.
