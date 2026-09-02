# ADR-0024: Tagged floating-scoreboard recovery

Status: Accepted

Related Issue: #47

## Context

FPRs are architectural, unrenamed state. The existing M4 foundation
scoreboard recorded only per-register pending-write bits and source-read
counts. Those aggregates correctly blocked RAW/WAR/WAW in straight-line
execution, but could not identify which reservations belonged to a branch
younger than a recovery boundary. Clearing all state on a branch squash would
unblock older F operations incorrectly; retaining all state would leak killed
reservations and eventually deadlock F dispatch.

The frozen design permits at most four outstanding F operations and requires
the same precise selective-squash/global-flush behavior as the ROB and
four-entry floating result queue.

## Decision

Each admitted F scoreboard allocation carries its ROB tag and occupies one of
four retained reservation records. A record contains up to three FPR sources,
one optional FPR destination, and whether its source operands have been
consumed. RAW, WAR, and WAW checks are derived from the live records, including
same-cycle lane-0 reservations visible to lane 1.

`readRelease` identifies its reservation by ROB tag and releases all source
claims only once E2 has consumed the operands. `complete` identifies the same
ROB tag and removes its destination claim only at commit-qualified F result
queue transfer. Source-only F operations release their record after operand
consumption because their GPR result remains on the ordinary completion path.

Selective squash removes exactly records younger than its tag boundary using
`ROBTagOrder`; global flush removes all records. Neither recovery operation
admits allocation, read release, or completion transfer in the same cycle.
Duplicate tags, releases/completions without their live record, mismatched
destination completion, and capacity overflow assert immediately.

## Alternatives considered

- Keep aggregate per-register counters and clear them on squash: rejected
  because older architectural F work would lose its hazards.
- Retain all aggregate counters until commit: rejected because killed work
  leaks FPR reservations and can permanently block dispatch.
- Add FPR renaming: rejected by the frozen 32x32 architectural FPR contract
  and static-area budget.

## Consequences

- The scoreboard interface gains ROB-tagged allocation/release/completion and
  recovery inputs before it may be wired to executable F decode or E2.
- Its four retained records and tag comparators are mandatory static-area
  ledger entries when M4 becomes executable.
- Unit tests must cover survivor/killed combinations around source consumption,
  destination commit, backpressure, and ROB tag wrap before F instructions are
  admitted to the production core.
