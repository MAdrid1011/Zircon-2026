# M3 committed L1D store path

This document specifies the executable write-back cacheable-store path under
ADR-0012 and Issue #47. It replaces the earlier single-beat write-through
experiment: an aligned `Memory` PMA store becomes irreversible only when its
exact ROB-head SQ record handshakes with `L1DLoadCache`, then retires only after
that cache owner returns the matching `StoreWriteResult`.

## Ownership and interface

`LoadStoreQueues` retains a store's address, byte mask, data, and retire
metadata until its tag is commit-authorized. `ZirconCore` routes only a
non-atomic `Memory` effect to the L1D `storeRequest` channel. `storeResult`
contains the same tag and effective address, is retained under backpressure,
and becomes `StoreEffectComplete` only on the result-router handshake.

| Stage | Owner | Result |
| --- | --- | --- |
| SQ allocation/address/data | SQ | speculative state only |
| exact-head `commitAuthorize` | SQ | effect eligible, not retired |
| `storeRequest` fire | L1D | cache update is irreversible |
| hit | exclusive L1D line | byte merge, dirty set, buffered success result |
| miss | L1D MSHR then L2/AXI read owner | reserve victim, fill line, byte merge, dirty set |
| `storeResult` fire | M0 completion plus SQ | exact successful completion; store may retire |

`L1DLoadCache` has one store in flight. A store miss may attach to an existing
same-line unfilled MSHR or allocate a free one; it does not fabricate a result
while the L2 probe or eight-beat AXI refill is pending. A refill `RRESP` error
produces the exact store access-fault result and does not install a line.

## Cache and exclusivity rules

On a hit, the owner merges each asserted byte lane into the selected word and
sets the line dirty. On a miss, it reserves a way before probing L2. A resident
victim transfers its complete line and dirty bit to `ExclusiveL2TransferStore`;
the old L1D valid and dirty state are cleared on that same ownership transfer.
An L2 hit removes the L2 copy, preserving its dirty bit in L1D before any store
bytes are merged. An AXI fill starts clean unless the waiting store updates it.

There is no ordinary cacheable `AW/W/B` transaction in this slice. AXI ID 5 is
owned by `AXIL2WritebackEngine`, which drains a dirty L2 victim as one retained
eight-beat burst and retries after a B error. Until later coherent-atomic work,
a same-line external atomic is backpressured when L1D or L2 holds dirty data; it
cannot observe stale backing memory or discard dirty state. Device stores remain
on ID 6 and atomic transactions remain on ID 7.

## Recovery and invariants

A committed store MSHR is not cancelled by squash or flush. Accepted L2 and AXI
read work drains, then either installs the dirty line and reports success or
reports the exact refill fault. A speculative load waiter may be removed during
recovery without removing the store owner. The implementation asserts:

- only non-atomic `Memory` PMA effects enter `storeRequest`;
- every store miss has one live MSHR and a non-reserved victim;
- no dirty line is discarded by an atomic invalidation;
- a dirty L1D victim transfers its dirty bit with the only copy;
- a store result is retained until its exact SQ consumer accepts it.

## Verification mapping

`L1DLoadCacheSpec` covers byte-masked store hits, store-miss write allocation,
exact fault/result retention, dirty victim transfer, and trace-selected exact
dirty-line transfer. `CoreShellSpec` runs a real RV32I store from AXI-fed
instructions, verifies its retire metadata, and proves no per-store external
write occurs. `AXIL2WritebackEngineSpec` adds the ID-5 burst and B-error retry
evidence, while `CoreShellSpec` forces a dirty L2 replacement and observes its
merged writeback payload. In trace elaborations, `HostStoreFlush` adds directed
evidence that a selected `tohost` store does not retire until its exact ID-5 B
response; ZirconSim still needs to drive the control from an ELF symbol and
gate host exit on observed backing memory.
