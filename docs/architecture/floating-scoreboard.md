# Floating-point scoreboard

`FloatingScoreboard` tracks hazards for the unrenamed 32-entry architectural
FPR file. It is an M4 foundation module, not an F execution path: no decoder,
issue queue, result queue, or commit plumbing drives it yet, and F encodings
remain illegal until those paths exist.

## State and interfaces

The default configuration admits at most four outstanding F operations and
holds one ROB-tagged reservation record for each. `allocate[2]` contains a ROB
tag, up to three sources, and one optional destination for each in-order
dispatch lane. Lane 1 observes lane 0's accepted reservation, so same-cycle
RAW/WAR/WAW conflicts cannot enter independently.

`readRelease` identifies a record by its ROB tag and releases all source
reservations only when an operation has actually consumed its operands.
`complete` identifies the same tag and clears its destination reservation only
from the commit-qualified F result queue after that release. An operation with
no FPR source still emits an empty, matching `readRelease` before completion.
This separates speculative source reads from architectural FPR mutation.

## Hazard rules

- A source with a pending write blocks RAW allocation.
- A destination with pending reads blocks WAR allocation.
- A destination with a pending write blocks WAW allocation.
- Repeated FMA sources reserve and release their FPR independently, preserving
  a precise count rather than collapsing duplicate operands.

Assertions reject read-count underflow, a completion without a pending write,
duplicate tags, a completion without its pending write, and reservations beyond
the four-operation budget. `squash` removes only records younger than its ROB
boundary; `flush` clears every record. Later integration must wire those inputs
through the LongIQ/result-queue lifecycle before F instructions become live.

## Verification mapping

`FloatingScoreboardSpec` covers RAW release on commit, WAR release after source
consumption, same-cycle two-lane WAW, a three-identical-source FMA-style
reservation, and tagged squash/flush recovery. Integration must add F decode,
issue, result backpressure, precise FPR commit, and retire-trace coverage.
