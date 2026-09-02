# ADR-0021: Dual-request L1D conflict policy

Status: Accepted; implementation in progress

Related Issue: #47

## Context

ADR-0020 makes the two LQ forwards explicit but still serializes them before a
one-request L1D. The frozen M3 design requires two tag lookups, four word banks,
four MSHRs, exact LQ ownership, and deterministic replay for two loads that
arrive together. Widening only the request type is unsafe: the current L2
transfer, probe, demand-read, and load-completion boundaries each have one
physical transfer per cycle.

The Zircon-2024 DCache is useful evidence that a two-port tag/data RAM is a
practical FPGA resource shape. Its write-through protocol, replacement rules,
and non-precise pipeline interface do not meet the frozen Zircon-2026 contract,
so they are not reused.

## Decision

The final M3 L1D accepts two cacheable `LoadStoreForward` candidates and
performs both tag comparisons in parallel. It applies the following matrix
before either candidate changes architectural or cache ownership:

| Pair | Rule |
| --- | --- |
| Two hits, different word banks | Accept both. Each result retains its LQ/ROB tag and may wait in an exact completion queue. |
| Two hits, same word bank or same address | Accept the older ROB tag; retain the younger candidate unchanged for replay. |
| Hit plus miss, different sets | Accept both if the miss has an MSHR, waiter, and any required victim-transfer credit. |
| Hit plus miss, same set | Accept the older tag only unless the miss can reserve a way distinct from the hit way without invalidating the hit-visible line. |
| Same-line misses | Allocate or reuse one MSHR and attach both exact waiters when two waiter credits exist. |
| Different-line misses | Allocate distinct MSHRs only when each candidate has a distinct reserved way and every displaced victim has transfer capacity. Otherwise accept the older candidate only. |
| MSHR, waiter, victim, L2-probe, or L2-demand contention | Backpressure the younger candidate. No request, completion, or cache invalidation may be fabricated. |
| Squash or flush | Suppress new acceptance; accepted AXI/L2 work remains owned and drains while killed LQ waiters are removed. |

`L2DemandEngine`, `ExclusiveL2TransferStore`, and AXI retain their single
physical transfer ports. Multiple local MSHRs may queue behind those ports; the
dual request interface does not promise two AXI transactions per cycle.

The first implementation increments have replaced the active top-level
`DualLoadForwardArbiter` connection with direct two-lane L1D ingress. It
performs both tag lookups, accepts different-bank hit pairs into two exact
result slots, backpressures a same-bank or same-address younger request, and
merges two same-line misses into one MSHR with two exact waiters. It also accepts
a different-set cache-hit/miss pair when the hit has a retained result slot and
the miss either joins an existing MSHR or reserves an invalid way. For a
same-set hit/miss pair it applies the same rule only when an invalid way distinct
from the hit way is available; a resident or dirty-victim replacement still
uses the older request only. It also accepts
two different-set misses when each has an invalid way plus distinct free MSHR
and waiter credits; the same-set case additionally requires two distinct
invalid ways. L2 probes remain serialized. Pairs that need merge,
victim-transfer, or shared-set arbitration still use the older request only.
This is explicitly not evidence that the remaining MSHR, victim, and L2 rows
are done.

Recovery preserves transfer ownership independently from waiter ownership. A
flush or squash may release a killed MSHR only before its L2 probe fires. Once
the probe is accepted, the MSHR remains the sole response sink until its L2
response is consumed, even when all of its load waiters have been removed. A
surviving older MSHR may then issue its own serialized probe. This prevents a
killed request from being completed while also preventing an accepted transfer
from being orphaned or reassigned to the survivor.

The implementation must use a two-port FPGA-friendly tag/data organization.
For the Nexys4 DDR point this is a registered RAM boundary or an equivalent
inferred/instantiated true dual-port RAM with documented read-during-write
semantics. It must not duplicate the complete cache array merely to shorten a
combinational path.

## Consequences

- `DualLoadForwardArbiter` is an intermediate one-port boundary and is removed
  from the active top-level path once the L1D consumes both inputs directly.
- The L1D completion boundary must retain two simultaneous hit results without
  losing M0/M1 ownership; a single downstream transport may serialize only
  after both result records have been captured.
- Tests must cover dual hit, hit/miss, same-line secondary merge, dual miss,
  same bank/set/address, MSHR full, waiter full, dirty-victim pressure, L2
  backpressure, response order, and squash/flush for each accepted owner.
- The current resource-pressure tests prove that a fifth live miss, a ninth
  waiter on one MSHR, and a dirty-victim miss behind a backpressured L2 insert
  are all held at ready/valid without creating an unowned transaction.
- The current directed matrix proves that a lane-1-old hit beats a lane-0-young
  miss, and that a lane-1-old different-line miss is the sole admitted owner.
  It also proves concurrent different-set hit/miss completion with one exact
  retained hit and one exact miss waiter, and two distinct MSHR IDs for an
  invalid-way different-set dual miss. Same-set replay remains conservative
  when both ways require replacement; the invalid-way same-set hit/miss case is
  covered separately and does not claim victim-transfer safety.
- Recovery coverage proves both dual-miss cases: a squashed younger MSHR that
  has not issued an L2 probe is released, while a squashed younger MSHR with an
  accepted probe drains its L2 response without a completion before the older
  survivor probes and completes.
- A dirty-victim miss held before an L2 transfer handshake is cancelled by full
  flush without transferring the resident line or creating an MSHR/L2/AXI/
  completion owner; the original dirty line remains locally hit-visible.
- Same-set dual-miss coverage proves two independent MSHRs and ordered
  completions when both ways are invalid, plus oldest-only admission when both
  ways require a victim transfer.
- The static-area ledger must include added port state, result buffering,
  conflict comparators, and any RAM port replication. A later timing report
  must identify whether a failing path is dominated by RAM routing or logic.
