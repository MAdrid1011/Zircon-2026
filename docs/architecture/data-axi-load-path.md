# M3 data AXI and L1D load-path architecture

This document defines the first executable cacheable-load slice of the frozen
M3 memory contract in ADR-0012 and Issue #47. It does not relax the final L1D,
L2, store, MMIO, or atomic requirements. Its only architectural effect is an
aligned cacheable integer load with an M1-owned LQ record. M0 device, atomic,
and general-load records remain pending until their respective M3 execution
owners are implemented.

## Scope and geometry

`L1DLoadCache` uses the frozen 1 KiB, two-way, 32-byte-line L1D geometry:
16 sets, eight 32-bit words per line, and four independently owned miss slots.
The initial slice is read-only. Commit-authorized stores, AMOs, dirty state,
writeback, L2 transfers, and device traffic are not accepted by this module and
remain blocked at their existing legal boundaries.

The cache accepts a `LoadStoreForward` only when its retained LQ owner marks it
cacheable, and it has either an immediate response slot or a free miss-waiter
slot. The eligibility bit is derived from `m1Owner && !isAtomic`; it is checked
again by `L1DLoadCache` so a future integration error cannot execute an M0
device/atomic request through the cache. A full store-forward request returns a
zero cache word to the LQ, which retains responsibility for merging every
forwarded byte. A hit returns the selected cached word. A miss allocates or
merges into one of four line MSHRs. Each outstanding architectural load has one
waiter record containing its ROB tag and word offset; same-line secondary loads
join the existing MSHR rather than issue a duplicate AXI request.

Each MSHR owns its line address until one eight-beat refill response is
accepted. The refill installs the line and returns pending waiters one at a
time through `LoadCompletion`. The LQ controls response backpressure, so a
completion buffer full condition never loses a cache response.

## Data AXI read engine

`AXIDataReadEngine` is the only data-side AXI read client. It accepts line-read
requests from L1D and reserves IDs 1 through 4; instruction fetch retains ID 0.
For every accepted request it emits one 8-beat, 32-bit, aligned INCR burst with
`len=7` and `size=2`. It may own four accepted bursts and one held AR payload.

An owner record contains the MSHR index, line address, received-beat count,
accumulated line words, and a sticky response-error bit. R beats may interleave
by ID. The engine checks that every beat belongs to a live owner, has the exact
expected count, and asserts `last` only on beat seven. A non-OKAY/non-EXOKAY
response becomes a sticky line error returned to the original MSHR. Unknown ID,
duplicate/extra beat, early/late `last`, and a 4 KiB-crossing request are
immediate assertions.

An accepted AR is never retracted. On recovery, L1D removes only killed waiter
records. Its owning MSHR and the data engine keep the AXI transaction alive
until all eight beats drain. A cancelled refill may populate the cache but must
never produce a completion for a killed ROB tag.

## Shared top-level read channel

`ZirconCore` arbitrates the existing fetch AR request and the data-engine AR
request onto the one AXI master. The selected AR payload is locked while the
external channel backpressures it. Fair round-robin arbitration changes only
after an AR handshake, so neither client can have a valid request silently
withdrawn. R responses are demultiplexed by ID: ID 0 goes only to fetch and
IDs 1-4 go only to the data engine. An unknown top-level R ID asserts before it
can be interpreted as a fetch or data result.

## Invariants and verification mapping

- A cache request handshakes only when it receives exactly one immediate
  response owner or one MSHR waiter owner.
- An M0 device or atomic LQ owner cannot enter L1D, issue a data AR, or create
  a completion before the ordered-MMIO/RV32A owner exists.
- Every miss waiter belongs to one live MSHR; every live MSHR has at least one
  waiter or an accepted AXI transaction still draining.
- A data AXI owner exists from AR acceptance through the final R handshake and
  is not reused early.
- A cache response contains the requested word from its filled line; all
  byte-forwarding remains in `LoadStoreQueues`.
- Selective recovery/global flush creates no architectural completion for a
  killed load and never drops an accepted AXI read.

`AXIDataReadEngineSpec` covers AR/R backpressure, four owners, ID interleaving,
response errors, and beat/`last` assertions. `L1DLoadCacheSpec` covers hit,
miss, same-line secondary merge, four-MSHR backpressure, refill response
backpressure, forwarding-only completion, non-cacheable rejection, and recovery
drain. `CoreShellSpec` adds an AXI-fed cacheable RV32I load that writes the real
returned value and retires exactly once, plus DeviceStrong, DeviceBurstable, and
LR.W cases proving no L1D/data-AXI request or false retirement before M0 grows
its required owners.
