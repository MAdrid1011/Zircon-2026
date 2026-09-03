# M2 E2 LongPipe

`LongIssueQueue` 和 `LongPipe` 组成 M2 的 E2 execution endpoint。它执行全部
RV32M，并为 M4 保留 F multiply/FMA 和 divide/sqrt 的共享资源边界。E2 不访问
memory，不产生 control redirect，也不修改 CSR/FPR。

## 固定资源和接口

| 项目 | 配置 | 归属 |
|---|---:|---|
| LongIQ | 4 compact `UopRef` | `LongIssueQueue` |
| issue | 2 enqueue / 1 E2 start | `LongIssueQueue` |
| operand read | 与 E1 共享 2 source read | M2 operand admission |
| active operation | 1 | `LongPipe` |
| result buffer | 2 entries | `LongPipe` |
| global start | E0 + E1 + E2 at most 3 | top-level issue arbitration |
| global completion | at most 2 | existing completion router |

`LongIssueQueue` receives two `UopRef` inputs and the existing integer PRF-ready
bitmap. It exports one ready uop only when both integer sources are ready and no older
ready uop exists. Its `capacity` is the number of registered free enqueue positions,
saturated at decode width; it deliberately does not promise same-cycle full-queue
recycle, which keeps dispatch capacity out of the dynamic PRF-ready loop. It uses the
existing ROB head-relative modulo-24 age order; lane 1 cannot enqueue without lane 0.

`LongPipe` receives one decoupled request containing the `UopRef` and resolved 32-bit
operands. M2 arithmetic does not need PC or wider ROB context; its tag is retained for
completion and recovery. It returns existing `CompletionResult` records through two
decoupled result slots. A response remains stable until accepted or discarded.

## Decode and arithmetic

`OP` with `funct7=1` is the only M encoding. `funct3=0..3` maps to `MUL`, `MULH`,
`MULHSU`, `MULHU` and has `UopClass.Multiply`; `funct3=4..7` maps to `DIV`, `DIVU`,
`REM`, `REMU` and has `UopClass.Divide`. Every mapping permits only `EndpointMask.E2`.
The decoded uop reads rs1/rs2, writes rd when nonzero, and carries no immediate.

Multiply must form the architecturally correct 64-bit product before selecting its
low/high half. Divide/remainder must implement the zero-divisor and signed overflow
rules in ADR-0011 before starting the iterative engine. Callers rely only on
ready/valid and cannot infer a latency from operation class.

All four multiply signedness variants derive from one unsigned 32x32 raw product;
two's-complement high-half corrections select `MULH`, `MULHSU`, and `MULHU` without
replicating the partial-product array.

## State, recovery, and completion

LongIQ may dequeue only on a request fire. LongPipe owns one active tag and two result
slots. It may accept a new operation only when the active engine and an output slot
permit it; result-slot backpressure therefore propagates to issue without dropping
work.

On a selective squash, LongIQ drops every uop younger than the resolving tag. LongPipe
cancels a younger active operation and removes younger buffered results. On a global
flush, it clears LongIQ, active work, and result buffers. A completion that had already
reached the common router remains subject to existing ROB accepted/discarded signals;
only accepted results write PRF, ready state, or wakeup.

## Invariants and counters

- E2 accepts only `Multiply`/`Divide` uops whose endpoint mask contains E2.
- Each accepted uop creates at most one completion; a killed uop creates none.
- A completion payload and valid remain stable under backpressure.
- E2 cannot exceed one start/cycle, two buffered results, or the global two completion
  ports.
- Source wakeup, ROB generation, and accepted/discarded behavior are identical to E0/E1.
- M5 performance integration must expose LongIQ occupancy, E2 starts, active cycles,
  result-buffer occupancy, completion stalls, accepted/discarded results, squash kills,
  and global-flush kills without changing this ready/valid contract.

## Verification Mapping

- Decoder tests cover all eight legal M encodings and nearby illegal encodings.
- LongIQ tests cover two-wide enqueue, ROB-wrap age, full recycle, same-cycle wakeup,
  selective squash, and global flush.
- LongPipe tests cover signed/unsigned product halves, div/rem corner cases, variable
  latency, response backpressure, stale discard, and both kill modes.
- Integration tests cover E1-to-E2 and E2-to-E1 RAW, an observed E0/E1/E2
  three-start followed by recovery kill, simultaneous E1/E2 completion with dual
  retirement, and four explicit-seed AXI AR/R backpressure recovery runs. The
  test-only observation port is absent from the default production configuration.
