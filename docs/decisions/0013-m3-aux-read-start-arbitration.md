# ADR-0013: M3 auxiliary PRF-read and start arbitration

Status: Accepted

Related Issue: #47

## Context

The frozen integer PRF is 56x32 with six combinational read ports and two write
ports. E0 and E1 permanently consume read ports 0/1 and 2/3. Ports 4/5 are
currently shared by retire-trace GPR capture and E2. M3 must add M0/M1 operand
reads without adding a seventh port, while preserving the maximum of three
starts per cycle and the existing trace priority.

An M0 store or atomic may require base and store-data reads, while an M1 load
requires only its base. Consequently M0-store plus M1-load cannot be admitted
in the same cycle through two physical auxiliary ports, but an M0-load plus
M1-load can. ROB context is not reconstructed from a uop: M0 and M1 each need
an exact live-tag context view for privilege and `aq`/`rl` metadata.

## Decision

M3 retains the six-read/two-write PRF unchanged. E0/E1 keep their four fixed
ports. Retire trace has exclusive priority for the two auxiliary ports; in a
trace-read cycle E2, M0, and M1 receive no start grant.

On a non-trace cycle a combinational global arbiter considers E2, M0, and M1
in ROB age order. It grants the oldest candidate that fits both the remaining
two auxiliary physical reads and the remaining global start slots after E0/E1
fires, then repeats for the next oldest candidate. A candidate consumes one
read for each integer-register source and no read for PC, immediate, zero, or
unused sources. The arbiter compacts granted virtual sources onto auxiliary
ports 4/5. It may therefore start E2 alone, M0 store/atomic alone, M0-load plus
M1-load, or one smaller request, but never exceeds three total starts or two
auxiliary reads. A request that is not granted remains in its issue queue;
there is no synthetic completion or source substitution.

The ROB gains two additional combinational exact-tag context views for M0/M1.
They share ROB entry state with the existing E0/E1 views and add no mirrored
ROB payload. All active execution-context tags must be live, in range, and
pairwise distinct. Their mux cost is reported by the static-area ledger with
the M3 integration, not hidden as a zero-cost interface.

## Alternatives considered

- Add dedicated M0/M1 PRF read ports: rejected because it violates the frozen
  6R2W resource point and conceals area growth.
- Give M0/M1 placeholder operands or completions: rejected because it can
  create incorrect addresses or false architectural progress.
- Permanently prioritize E2 over memory: rejected because a sustained M stream
  could starve the LQ/SQ path and prevent `tohost` progress.
- Reconstruct atomic metadata from current decode: rejected because a flushed
  or reused ROB index can no longer identify the issuing instruction.

## Consequences

- M3 top-level integration must test trace priority, E0/E1 plus external-start
  limits, E2/M0/M1 age ordering, source compaction, and M0-store/M1-load
  serialization.
- Completion remains independently arbitrated by the frozen five-endpoint,
  two-port network. The new issue arbiter does not alter completion priority.
- Cache, AXI, and store-effect execution remain separate M3 work; an admitted
  request without a real data endpoint remains blocked rather than completing.
