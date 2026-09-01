# M3 RV32A AXI owner

This document specifies the executable M3 RV32A slice under Issue #47 and
ADR-0012. It covers LR.W, SC.W, and all nine AMO.W operations on the existing
cacheable atomic `Memory` PMA. It is not the final write-back/exclusive L1D-L2
implementation, and it does not make device regions atomic-capable.

## Ownership and ordering

`MemoryQueueIngress` retains the decoded atomic operation in its LQ and/or SQ
entry. LR owns an LQ, SC owns an SQ, and every AMO owns both. `LoadStoreQueues`
may offer the record only when its ROB tag is the live head, its address/data
are ready, and no prior atomic response is awaiting M0 completion. The owner
then transfers to `AtomicMemoryEngine`; it never reconstructs the operation or
destination from a later decode slot.

MemIQ conservatively prevents a younger M1 load from passing any live atomic.
After LSQ allocation, an `aq` record additionally blocks younger memory issue
until that tag completes. `rl` is satisfied by head-only launch: all older
architectural work has retired before the atomic can accept AXI. This is
stronger than necessary for a non-aq atomic but avoids a pre-LSQ ordering hole
without making mutable ordering state part of `UopRef`.

## AXI ID 7 lifecycle

ID 7 is reserved exclusively for `AtomicMemoryEngine`. LR and AMO issue one
aligned one-beat `AR`; an AMO computes its word result from that R data, then
issues independent one-beat `AW` and `W`. SC first compares the retained
reservation. A reservation miss returns `rd=1`, clears the reservation, and
does not issue a write. A reservation hit writes the retained SC value and
returns `rd=0` only after the exact `B` response.

| Operation | AXI path | Completion value | Memory metadata |
| --- | --- | --- | --- |
| LR.W | `AR` then `R` | loaded word | read only |
| SC.W miss | no AXI transaction | `1` | no write mask/data |
| SC.W hit | `AW` + `W`, then `B` | `0` after B | write only |
| AMO*.W | `AR` + `R`, then `AW` + `W` + `B` | old word after B | old read plus computed write |

`RLAST` must be true for the single-beat read. Any non-OKAY/EXOKAY `RRESP` or
`BRESP` becomes one cause-7 store/AMO access fault with the retained effective
address. A success returns through the M0 load-result formatter exactly once,
with the retained integer destination. An atomic does not use a normal store's
non-writing completion. LQ/SQ metadata is updated before the result leaves the
queue, so `RetireEvent` reads committed old-data/write-data rather than AXI
signals.

## Reservation, cache, and recovery

The reservation is one 32-bit word. A successful LR installs it; another LR
replaces it. SC always clears it. A matching committed cacheable store, a
matching AMO, and every commit-stage global flush clear it. Global flush covers
the required trap and interrupt cases. The core blocks interrupts from an
accepted atomic through retirement, so an MRET cannot repeat an irreversible
AMO/SC write.

Before ID 7 accepts an atomic, `L1DLoadCache` requires any same-line refill MSHR
to drain and blocks a matching dirty L1D line. `ExclusiveL2TransferStore` also
refuses to invalidate a dirty L2 line; `AXIL2WritebackEngine` drains such a
victim through ID 5 before a later atomic can obtain clean external backing
memory. A result with an externally attempted atomic write invalidates a
matching clean resident L1D line when its response is accepted, including a
BRESP error conservatively. An SC whose reservation is already absent is exempt:
it returns the architectural no-write result locally and need not wait on dirty
cache data or an external owner. External multi-master coherency remains M3
work.

Flush before AXI acceptance cancels the local effect. Once AR, AW, or W has
accepted, the engine drains the required response; a killed result is discarded
instead of completing a flushed ROB tag. Assertions reject an unexpected ID,
missing RLAST, unsupported operation, misalignment, and B acceptance before
the owner reaches its write-response state.

## Evidence

`AtomicMemoryEngineSpec` covers LR, SC hit/miss, all AMO arithmetic including
signed/unsigned min/max, RRESP/BRESP faults, and flush drain. `LoadStoreQueuesSpec`
covers paired AMO LQ/SQ metadata. `DualMemoryLoadCompletionSpec` covers the
single writable M0 completion and cause-7 conversion. `L1DLoadCacheSpec`
covers same-line MSHR exclusion and invalidation. `CoreShellSpec` covers real
ID-7 LR/SC, local-store and trap/MRET reservation invalidation, AMO retirement,
RRESP fault, and aq ordering. Run `make test-m3-atomic` for the focused local
regression.
