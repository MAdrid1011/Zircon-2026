# ADR-0020: Dual load-forward arbitration before the L1D request port

Status: Accepted

Related Issue: #47

## Context

`MemIssueQueue` can start one M0 and one M1 request in the same cycle, but the
original LQ forward interface had one combinational valid/ready pulse. It could
not represent two eligible load-address updates at once, and its payload was
not held when the downstream cache backpressured. This obscured conflicts and
left no safe boundary from which to widen the current one-port L1D slice.

The frozen M3 contract requires exact ROB ownership, no dropped request, and
deterministic backpressure/replay for every dual-LSU conflict. The present L1D
data/tag/completion slice remains one request wide, so this decision must not
claim dual-bank hit throughput before that structure is implemented.

## Decision

The LQ accepts two simultaneous load-address candidates and exports two
`Decoupled[LoadStoreForward]` records. Each record holds its calculated
byte-forward payload until its exact L1D or ordered-M0 consumer handshakes.
`DualLoadForwardArbiter` accepts the two cacheable records and chooses the
oldest by live ROB age. It locks the selected lane while L1D backpressures,
then releases only that request; the unselected record remains owned by its LQ
update slot. Non-cacheable device and atomic forwards bypass this arbiter to
their existing M0 owners.

This creates a two-candidate, one-L1D-port matrix boundary. Same bank/set/line,
hit/miss, dual-miss, MSHR-full, and victim-full candidates therefore receive a
deterministic oldest-first grant or retained backpressure. A later ADR/RTL step
must widen L1D bank/tag and completion resources before two conflict-free hits
can fire in one cycle.

## Consequences

- Normal M0 and M1 memory loads retain their original completion owner.
- No query is consumed until its selected downstream request fires; recovery
  suppresses all grants and clears the lock.
- The static area ledger charges the ROB-age select comparison and lock state.
- Directed tests cover two-candidate ordering across ROB wrap, stable payload
  under backpressure, independent LQ dual queries, and recovery suppression.
- This is an intermediate M3 step, not evidence that the full dual-LSU/cache
  conflict matrix or dual-hit performance target is complete.
