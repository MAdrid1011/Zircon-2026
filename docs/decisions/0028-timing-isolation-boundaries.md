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

The production core also registers the backend `integerReady` bitmap before
feeding LongIQ, FloatingIQ, and MemIQ.  This wakeup snapshot is deliberately
one cycle stale at most; it prevents completion/commit feedback from
re-entering all three queue candidate trees and the auxiliary PRF path in the
same cycle.

All non-commit consumers of ROB age (issue queues, operand boundaries, LSU,
floating state, and L1D scheduling) use the same registered head tag.  Commit
authorization and retire observation intentionally remain on the live head so
precise architectural ownership is unchanged.

Each boundary is ready/valid, clears on global flush, and removes a younger
   held entry on selective squash using its real ROB tag.  No boundary creates
   a completion or changes ROB ownership.  The `enableM2Observation` test
   configuration keeps a transparent implementation so the existing
   same-cycle E0/E1/E2 start observation remains valid; production builds use
   the registered form.

## Consequences

The E2 path gains bounded launch latency and may reduce peak issue throughput
when a boundary is full, but preserves age ordering, kill/drain behavior, and
the shared three-start/two-completion contract.  Wakeup may arrive one cycle
later, which is legal for the issue queues and keeps completion ownership
unchanged.  Post-route timing must be remeasured on the same device after every
subsequent structural change; the `-92.911 ns` baseline and the first boundary
run's `-88.879 ns` are measured failures, not release results.

## Verification

`./scripts/sbtw compile`, `make platform-verilog`, and the focused production
CoreShell scenarios pass after the change.  The observation-only three-start
scenario remains a pre-existing failure: the same start mask and cycle list
fails on a clean `46cd3ac` worktree, so it is not attributed to these ingress
boundaries. Focused M3/M4 regressions remain required before the next
fixed-target implementation.

## Timing isolation follow-up (2026-09-04)

The post-route report still showed a 119-level ROB-head-to-MemIQ path. The
production core now adds a registered, squash-aware ingress for each MemIQ,
LongIQ, and FloatingIQ lane. Dispatch observes MemIQ boundary readiness before
allocating a ROB entry, while production queue capacities are held at the
two-wide boundary capacity to remove queue occupancy from the ROB/rename
feedback cone. The `enableM2Observation` configuration is compile-time
transparent and retains the original direct queue wiring for cycle-accurate
start-mask tests.
