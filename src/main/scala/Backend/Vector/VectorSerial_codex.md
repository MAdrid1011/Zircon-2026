# VectorSerial implementation

`VectorSerial.scala` implements the element-serial RVV path described in
[Backend](../../../../../docs/Backend.md). It owns one accepted instruction,
retains its ROB/PRF metadata through completion, and shares three vector read
ports with the other vector executors. Read requests wait for `readAvailable`;
completion holds until accepted. Flush cancels the instruction and has priority
over acceptance and phase updates. Writeback is a single-cycle valid pulse.

The thirteen phases collect operands, evaluate, finish fixed arithmetic or await
shared arithmetic, update packed output, write back, and complete. Carry,
saturation, average, widening, and rounded shifts use the existing
`evaluate` → `finishArithmetic` register boundary. `arithmeticResult` holds an
unrounded, already scaled average, a shifted operand, or an unscaled add/subtract
result. `shiftRound` holds the increment bit for both average and shift. The
following incrementer consumes those register outputs directly. This retiming
adds no phase, read, completion, or writeback cycle; saturation and widening
still consume the unscaled add/subtract value.

Gather/slide validity uses the zero-or-power-of-two VLMAX invariant: a nonzero
VLMAX admits only index bits below its set bit. The low-bit mask is prepared
from instruction state before late element data is selected. The 32-bit index,
including wrapping slide arithmetic, is unchanged. No state, phase, or read
latency is added. The mask covers zero VLMAX and all encoded exponent values;
module verification must compare full-width gathered indices and slide wrap.

`VectorSerialSpec` covers integer/FP operations, all SEWs and rounding modes,
saturation, narrowing, masking/tails, held completion, read arbitration, and
flush. Seed: 20261013. TSMC28 timing and whole-core regression evidence is
tracked in [TSMC28Timing](../../../../../docs/TSMC28Timing.md); physical and SRAM
timing closure remain separate from module functional verification.
