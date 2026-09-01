# M3 exclusive L2 transfer store

`ExclusiveL2TransferStore` is the first state-owning L2 component under
ADR-0012 and Issue #47. It implements the frozen four-way, 32-byte-line,
4 KiB or 8 KiB D-side L2 geometry and the exclusive transfer boundary. It does
not yet issue AXI reads or writebacks, allocate L2 MSHRs, or serve I-side
requests; those owners are added only after this component is integrated with
the L1D miss and eviction paths.

## Parameters and interfaces

The store takes `config.l2`, which is constrained to four ways, 32-byte lines,
four MSHRs in the surrounding hierarchy, and either 4 KiB (32 sets) or 8 KiB
(64 sets). It has three decoupled boundaries:

| Interface | Direction | Ownership rule |
| --- | --- | --- |
| `insert` | L1D/victim buffer to L2 | A valid L1D eviction transfers one complete line and its dirty bit into L2. The source must not retain a stable copy after the handshake. |
| `lookup` / `response` | L1D refill path | A lookup hit removes the L2 line on the request handshake and retains it in the response register, which is the transfer buffer until L1D accepts it. A miss has no line payload or ownership. |
| `victim` | L2 to later writeback owner | A displaced dirty L2 line enters the two-entry FIFO. A full FIFO backpressures an insertion that would need another dirty eviction. |

L2 never duplicates a D line. Insertion requires that the line is absent from
L2. A successful hit immediately invalidates the matching L2 entry before
exposing the response, so exactly one of L2 or the response transfer buffer
owns the line. A clean L2 victim can be discarded; a dirty victim cannot be
discarded and remains in the FIFO until a later AXI writeback owner accepts it.

## State and arbitration

Tags, valid bits, dirty bits, data words, and per-set round-robin replacement
are stored locally. `insert` has priority over `lookup` to avoid a same-cycle
read/write ownership ambiguity in this first single-port component. A lookup is
accepted only when the response transfer buffer is empty. The component permits
one retained L2-to-L1D transfer and two retained dirty victims; no recovery path
may erase either because both are microarchitectural ownership, not speculative
architectural completion.

The surrounding final hierarchy will feed L1D dirty evictions to `insert`, send
an L1D miss to `lookup`, route a hit response directly to the L1D fill owner,
and send `victim` to an AXI writeback owner. An L2 lookup miss remains blocked
at that later owner until an L2 MSHR and AXI refill exist; this component never
fabricates cache data or an architectural load completion.

## Invariants and counters

- An inserted line is absent from L2 at transfer time; a duplicate is an
  exclusivity assertion failure.
- A lookup hit moves a line out of L2 before the response becomes visible.
- Every dirty displaced line enters the victim FIFO exactly once, and a full
  FIFO prevents another dirty displacement.
- The response transfer buffer and victim FIFO survive squash and global flush.

The component exposes transfer and victim occupancy so the final M3 counters
can report L2 occupancy, transfer pressure, and victim-full stalls rather than
recording unimplemented activity as zero.

## Verification mapping

`ExclusiveL2TransferStoreSpec` covers hit removal, miss behavior, exact line
payload/dirty-bit transfer, dirty victim FIFO ordering and full backpressure,
and the 8 KiB configuration. L1D/L2 integration tests must later add every
L1D-to-L2 state transition, dirty writeback overlap, L2 MSHR pressure, and the
global single-owner assertion across L1D, L2, and transfer buffers.
