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

## Measured follow-up (2026-09-05)

The complete implementation of commit `7be7aaa` on `xc7a200tfbg676-2L`
completed with WNS `-59.628 ns`, TNS `-1,850,192.202 ns`, and 62,698 failing
setup endpoints. The worst path was still a 119-level live-ROB-head cone
reaching FirstFault and issue state, showing that outer boundaries did not
isolate the internal integer backend.

The follow-up adds a registered head snapshot inside `IntegerExecutionBackend`
and feeds it to IntIQ, short pipes, branch recovery, and FirstFault; live
ROB/commit signals remain unchanged. `IntegerRename` also maintains a narrow
event-updated free-register counter instead of a 64-bit PopCount in dispatch
ready logic. A fresh fixed-target post-route run is required before claiming
timing improvement.

The subsequent `9e6fc83` full implementation did not complete routing: the
router oscillated between 41,224 and 24,826 overlap nodes and was interrupted
after 37 minutes. Its place checkpoint measured `entryComplete_9` to
`FirstFault.recordReg_robTag` at 130 levels and `-64.900 ns` WNS. Commit
`128d81b` therefore adds a production-only registered E0 fault candidate and
registered IntIQ wakeup; synthesis-only maps to 65,754 LUTs, 32,801 FFs, 133
BRAM tiles, and 4 DSPs. These are structural/timing-progress data, not a
100-MHz release result.

## Fault-candidate boundary (2026-09-05)

The next place checkpoint still showed `entryComplete_9` traversing the LSU
completion and issue-control network into `FirstFault.recordReg` (130 logic
levels, `-64.900 ns` estimated place WNS). Production
`IntegerDispatchRecoveryBackend` now registers all external endpoint fault
candidates before the age arbiter. The register clears on global flush or
selective squash in the capture cycle. Decode faults remain direct so an
illegal instruction is visible at allocation; E0 faults retain their existing
registered sample.

This changes only fault-observation latency, not completion ownership: the ROB
completion edge is followed by a registered candidate and the tracker output is
visible before the next commit decision. Focused reserved-`frm`, data AXI RRESP,
platform RTL, and deterministic `tohost` regressions pass. A fresh post-route
report is required to quantify the timing effect.
