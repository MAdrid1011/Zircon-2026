# ADR-0012: M3 memory transaction ownership and precise effects

Status: Accepted

Related Issue: #47

## Context

M1/M2 deliberately give the memory dispatch boundary zero capacity. This avoids
inventing load/store/atomic completion, but it also means that neither a `tohost`
store nor any real data-memory ELF can finish. M3 introduces two LSUs, queues,
caches, ordered MMIO, and AXI data traffic while preserving the M1 commit/trap,
recovery, and AXI fetch-drain contracts.

The critical design problem is ownership. A speculative load can be killed after
an AXI request has been accepted, whereas a store, AMO, or device operation is
irreversible once it changes the cache or bus. AXI read responses may arrive by
ID out of request order, and an error must become a fault on the exact ROB entry
rather than on the currently visible fetch or commit instruction.

## Decision

M3 defines one `MemoryTransaction` record per issued cache, refill, writeback, or
device action. The record is owned by exactly one of `MemIQ`, `LQ/SQ`, an L1/L2
MSHR, the ordered-device group, or the AXI data engine at every time. It carries
an owner kind, the owning ROB tag when architectural, AXI ID, expected beat count,
response status, cancellation state, and a link to `MemoryRetireMetadata`.

`MemIQ` contains eight compact `UopRef` entries and starts at most one M0 request
and one M1 request per cycle. M1 may issue only an aligned, readable, cacheable,
non-atomic integer load after every older SQ address is known. All other memory
uops, including stores, LR/SC, AMO, device accesses, PMA faults, and M1 replay,
go to M0. Both LSU completion paths use two-entry buffers and enter the existing
five-endpoint/two-port completion network. M4 retains the third external endpoint
for FPU work.

LQ and SQ each contain eight entries. A load cannot reach L1D while an older store
address is unknown. Once addresses are known, byte-lane forwarding takes priority
over cache data; a partially covered word merges forwarded bytes and cache bytes.
Stores retain address, mask, data, and ordering state in SQ until their owning ROB
entry obtains commit authorization. No store can alter L1D/L2, issue a writeback,
or generate a device write before this authorization.

Every architectural memory effect has a `MemoryRetireMetadata` record keyed by
ROB tag. The trace formatter reads this committed record, not a live LSU signal or
an AXI response. RRESP/BRESP errors produce one `FirstFaultRecord` for that owner
with the aligned faulting address. Misaligned accesses fault before cache or AXI
allocation. A killed speculative load or refill may suppress completion, but an
already accepted AXI transaction stays owned by the data engine until every beat
and required response has been drained; unknown IDs, duplicate beats, and wrong
RLAST are assertions.

The AXI data engine owns four read-owner slots and one write-owner slot. It shares
the top-level AXI master with L1I through explicit request arbitration and reserves
disjoint IDs for live reads. Cache refill is exactly eight 32-bit INCR beats;
device groups are one through four beats; all requests obey the 4 KiB boundary.
AXI errors remain associated with the original owner even if another ID's response
arrives first.

Device requests enter `OrderedIOCombiner` only through M0. `DeviceStrong` always
forms one group. `DeviceBurstable` can form up to four same-direction, same-width,
adjacent, consecutive-ROB-order operations in the same PMA region and 4 KiB page.
The next device group cannot pass the current one; read beats map back to their
individual ROB entries; all related operations wait for a successful response.

L1I and L1D are 1 KiB, two-way, 32-byte-line caches. L1D has four MSHRs and may
hit under miss, miss under miss, and merge same-line secondary misses. L2 is
four-way with four MSHRs and a selectable 4 KiB or 8 KiB capacity. The default is
4 KiB. D-side ownership is exclusive: a stable line exists in exactly one of L1D,
L2, or a transfer buffer. I-side is non-inclusive. A two-entry victim/writeback
queue absorbs dirty eviction and must backpressure rather than lose ownership.

LR/SC reservation granularity is a 32-bit word. LR creates a reservation only for
aligned cacheable atomic PMA. A same-hart conflicting store/AMO, trap, interrupt,
or reservation replacement clears it. SC returns success only with the live
reservation and clears it in either case. AMO and device accesses never pass M1.
`aq`, `rl`, and FENCE obtain their effect by blocking until the required LQ/SQ,
device, and outstanding-owner sets drain.

## Alternatives considered

- **Let speculative stores update L1D and undo them on squash:** this needs a
  second rollback protocol for cache data and breaks the frozen irreversible
  effect boundary; rejected.
- **Discard accepted AXI reads after a squash:** this violates AXI and makes a
  later reused ID ambiguous; rejected.
- **Create a separate data AXI port:** the frozen top-level has one AXI4 master
  and M3 must prove shared-ID ownership; rejected.
- **Have M1 issue all loads and let it serialize special cases:** this violates
  the frozen two-LSU roles and hides M0 replay behavior; rejected.
- **Record retire memory fields directly from AXI:** response timing is not
  retirement timing and cannot represent store forwarding or killed loads;
  rejected.

## Consequences

- M3 adds queue/MSHR/AXI-owner credit conservation and cache-exclusivity
  assertions. Every recovery path must either cancel local work or drain accepted
  AXI work.
- The existing one-outstanding `AXIInstructionFetch` is no longer the final
  top-level transport, but its redirect, drain, RRESP, 4 KiB, and protocol
  semantics stay mandatory inside the L1I/data-engine integration.
- The static-area ledger must explicitly list MemIQ, LQ, SQ, MSHRs, tag/data
  arrays, victim buffers, ID tables, comparators, arbiters, and both LSUs. Any
  unavailable ledger input remains `missing` or `PARTIAL`; it is never valued at
  zero.
- M3 cannot claim completion until directed and explicit-seed randomized tests,
  deterministic ELF/tohost execution, response-error paths, and the listed
  architecture invariants have evidence in Git.
