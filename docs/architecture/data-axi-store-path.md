# M3 committed cacheable-store AXI write slice

This document specifies the first executable store path under ADR-0012 and
Issue #47. It enables an aligned cacheable RV32I store to become an exact,
commit-authorized AXI write. It is deliberately not the final write-back L1D,
MMIO, AMO, LR/SC, or L2 implementation.

## Scope and ownership

`LoadStoreQueues` retains an integer store's effective address, byte mask, and
data until that exact ROB tag is the head. `ZirconCore` then offers only that
head tag on `commitAuthorize`. An M0 store with atomic state or a non-`Memory`
PMA kind is not eligible for this slice and remains blocked for its later owner;
it cannot create a synthetic completion.

`AXIDataStoreEngine` owns one accepted cacheable store from the LSQ
`StoreEffect` handshake through the one-beat AXI `AW`, one-beat `W`, and exact
`B` response. It reserves AXI ID 5, while fetch owns ID 0 and data refills own
IDs 1--4. `AW` and `W` are held independently under backpressure; neither is
withdrawn after becoming valid. A `B` must identify ID 5 and may be accepted
only after both address and data handshakes. The engine holds its exact result
until the M0 completion boundary accepts it.

| Event | Owner | Architectural result |
| --- | --- | --- |
| SQ allocation/address/data | SQ | speculative state only |
| head `commitAuthorize` | SQ | eligible store effect, no retirement |
| store-engine effect handshake | AXI write owner | line invalidated; AW/W/B drain required |
| OKAY/EXOKAY `B` | M0 completion plus SQ | non-writing exact completion; store can retire |
| other `BRESP` | M0 fault plus SQ | precise store/AMO access fault, cause 7, `tval=effective address` |

The SQ records `StoreEffectComplete` only on the same edge that the result
router accepts the engine result. Therefore a completed successful store retains
its real `MemoryRetireMetadata` until retirement, while a B-error store retains
no false metadata and feeds the fault path instead.

## Cache interaction

This temporary write-through path prevents stale reads from the read-only L1D:

- before an effect is accepted, L1D refuses it while a same-line refill MSHR is
  live;
- on the effect handshake, a matching resident L1D line is invalidated;
- while the AXI write owner is active, cache-dependent requests for that line
  are blocked, preventing a stale hit or a new refill before the write result;
- an already accepted AXI read continues to drain. The store is delayed rather
  than cancelling or racing that owner.

The final M3 L1D replaces this invalidate/write-through behavior with dirty
write-back, write-allocate, L2 exclusivity, and the victim/writeback queue. It
must preserve the commit authorization and exact B-error ownership described
here.

## Recovery, interrupts, and invariants

An authorized store blocks interrupts until it retires. Selective squash may
never discard it. A global flush may discard the SQ record only after an exact
B-error has made that store faulting; it must never discard a successful or
still-draining write. `AW`, `W`, and `B` are not cancelled by recovery.

The active implementation asserts all of the following:

- cacheable-store PMA, non-atomic ownership, transfer size, and natural
  alignment;
- one live AXI write owner, ID 5 on `B`, and no `B` before both AW/W handshakes;
- no same-line L1D refill at the effect boundary;
- no retirement before `StoreEffectComplete`, and no flush of a successful or
  undrained authorized store.

`AXIDataStoreEngineSpec` covers independent AW/W backpressure, write data and
mask retention, safe-invalidation backpressure, `BRESP` success/error, result
retention, and wrong-ID assertion. `LoadStoreQueuesSpec` covers cacheable-only
authorization, fault-aware lifecycle, and blocked atomics. Top-level tests add
the real store instruction, AXI write channels, precise B-error trap, retire
trace, and L1D invalidation coverage.
