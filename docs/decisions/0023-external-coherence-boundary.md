# ADR-0023: External cache-coherence boundary

Status: Accepted; implementation pending

Related Issue: #47

## Context

`ZirconCore` exposes one AXI4 **master** port. AXI write channels alone cannot
notify the core about writes or atomics initiated by another master, so they
cannot make the private L1I/L1D and dynamic L2 coherent. Treating a later AXI
response as an external snoop would silently permit stale instruction/data
lines and a stale LR reservation.

The frozen M3 design nevertheless requires a concrete integration contract for
external cacheable writes and atomics. It must preserve dirty data before the
external action, invalidate every locally observable copy, and never acknowledge
an external modifier while an accepted local transaction is orphaned.

## Decision

The production top-level will add a one-outstanding sideband
`ExternalCoherencePort`; it is deliberately separate from `AXI4MasterPort`.
The platform adapter, not an implicit AXI decoder, converts every external
cacheable modifier into this port:

| Channel | Payload | Rule |
| --- | --- | --- |
| `request` | `{kind, lineAddress}` | Decoupled request, line address aligned to 32 bytes. `kind` is `WriteInvalidate` or `AtomicInvalidate`. |
| `response` | `{kind, lineAddress}` | Decoupled acknowledgement. Only one request may be outstanding; the adapter must not issue the external write/atomic until this response fires. |

On request acceptance the coherence controller blocks new cacheable ingress.
It drains any matching accepted L1D/L2 demand owner, transfers dirty matching
L1D data through L2 and ID-5, then invalidates matching L1D, L2, and L1I copies.
It also clears a matching word LR reservation. The response may fire only after
all local copies for the line are invalid and every required writeback has an
OKAY/EXOKAY B response. A failing ID-5 B response retains and retries the exact
dirty line; it cannot produce a coherence acknowledgement.

`WriteInvalidate` is required before an external cacheable store. `AtomicInvalidate`
is required before an external LR/SC or AMO and has the same invalidation and
reservation semantics. The adapter owns the external transaction after the
response; because the line is invalid locally, that transaction cannot observe
or create a stale local copy. External reads and shared-cache snoop responses
are outside this narrow port and require a future ADR rather than an inferred
behavior.

## Consequences

- A board integration without `ExternalCoherencePort` is single-hart/private-
  memory only; it must not advertise multi-master coherent memory.
- The controller needs one retained request record and must compose with
  `CacheFenceDrainController`, L1I invalidate/drain, L1D-L2 transfer, ID-5
  retry, `AtomicMemoryEngine.invalidate`, recovery, and interrupt gating.
- Every accepted request eventually produces exactly one response unless reset;
  reset drains already accepted AXI work and drops the unacknowledged sideband
  request, leaving the adapter responsible for retrying after reset.
- Verification must cover clean/dirty copies in L1I/L1D/L2, in-flight refill and
  writeback, selective squash/global flush, ID-5 retry, matching/non-matching
  reservation loss, and no external action before acknowledgement.
- This interface is a required M3 release item. The current absence of its RTL,
  platform adapter, formal proof, and tests remains explicitly incomplete.
