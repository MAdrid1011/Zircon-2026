# ADR-0028: E2 timing isolation boundaries

状态：Accepted

关联 Issue：#47

## 背景

The first complete post-route report for `xc7a200tfbg676-2L` showed a shared
critical structure rather than an isolated arithmetic operation.  The worst
20 paths all started at the ROB head-index fanout and traversed ROB/commit,
issue age selection, auxiliary PRF reads, and the LongPipe divide-special
result register.  The worst path was 169 logic levels and 103.587 ns of data
delay, of which 83.957 ns (81.050%) was routing.

## 决策

Production `ZirconCore` inserts three elastic timing boundaries around E2:

1. a registered ROB head tag feeds LongIQ, FloatingIQ, and auxiliary age
   scheduling;
2. each E2 issue queue captures its selected compact `UopRef` before operand
   arbitration; and
3. resolved LongPipe operands are captured before the arithmetic engine.

Each boundary is ready/valid, clears on global flush, and removes a younger
   held entry on selective squash using its real ROB tag.  No boundary creates
   a completion or changes ROB ownership.  The `enableM2Observation` test
   configuration keeps a transparent implementation so the existing
   same-cycle E0/E1/E2 start observation remains valid; production builds use
   the registered form.

## Consequences

The E2 path gains bounded launch latency and may reduce peak issue throughput
when a boundary is full, but preserves age ordering, kill/drain behavior, and
the shared three-start/two-completion contract.  Post-route timing must be
remeasured on the same device after every subsequent structural change; the
`-92.911 ns` baseline is a measured failure, not a release result.

## Verification

`./scripts/sbtw compile`, `make platform-verilog`, and the 13 RV32M/RV32F
`CoreShellSpec` scenarios pass after the change, including seeded recovery and
the observed three-start case.  Focused M3/M4 regressions remain required
before the next fixed-target implementation.
