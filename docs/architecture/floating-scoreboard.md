# Floating-point scoreboard

`FloatingScoreboard` tracks hazards for the unrenamed 32-entry architectural
FPR file. It is an M4 foundation module, not an F execution path: no decoder,
issue queue, result queue, or commit plumbing drives it yet, and F encodings
remain illegal until those paths exist.

## State and interfaces

The default configuration admits at most four outstanding F operations and
holds a pending-write bit plus a pending-read reservation count per FPR.
`allocate[2]` contains up to three sources and one optional destination for
each in-order dispatch lane. Lane 1 observes lane 0's accepted reservations,
so same-cycle RAW/WAR/WAW conflicts cannot enter independently.

`readRelease` releases all source reservations only when an operation has
actually consumed its operands. `complete` clears a destination write bit only
from the commit-qualified F result queue. This separates speculative source
reads from architectural FPR mutation.

## Hazard rules

- A source with a pending write blocks RAW allocation.
- A destination with pending reads blocks WAR allocation.
- A destination with a pending write blocks WAW allocation.
- Repeated FMA sources reserve and release their FPR independently, preserving
  a precise count rather than collapsing duplicate operands.

Assertions reject read-count underflow, a completion without a pending write,
and reservations beyond the four-operation/12-source budget. Later integration
must connect selective squash/global flush through the LongIQ/result-queue
lifecycle; no flush input is exposed until a live F uop owner exists.

## Verification mapping

`FloatingScoreboardSpec` covers RAW release on commit, WAR release after source
consumption, same-cycle two-lane WAW, and a three-identical-source FMA-style
reservation. Integration must add F decode, issue, squash, result backpressure,
precise FPR commit, and retire-trace coverage.
