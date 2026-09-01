# ADR-0019: Cache-global FENCE drain and I/D coherence

Status: Accepted

Related Issue: #47

## Context

The age-tagged LSQ barrier proves that owners older than a head `FENCE` or
`FENCE.I` have completed their architectural queue work. It does not prove that
dirty committed bytes have left L1D or L2. Retiring at that point can expose
stale backing memory to a device or, for `FENCE.I`, let the instruction side
refetch code before its data-side store has reached AXI.

The trace-only targeted flush in ADR-0015 is intentionally not an ISA semantic:
it depends on a host-selected address and is absent from production RTL. A
general architectural drain must therefore reuse the production L1D-to-L2,
victim FIFO, and retained ID-5 ownership paths.

## Decision

`ZirconCore` starts `CacheFenceDrainController` only while the exact live ROB
head is `FENCE` or `FENCE.I` and the LSQ age barrier reports all older
load/store/device/atomic owners drained. The controller has three ordered
phases:

1. `L1DLoadCache` blocks new L1D ingress but continues already accepted
   demand/completion traffic. After its MSHRs and local result holds drain, it
   transfers one dirty resident line at a time into exclusive L2 and invalidates
   its former L1D copy.
2. `ExclusiveL2TransferStore` blocks new L2 array ingress and moves one dirty
   resident line at a time into its existing two-entry victim FIFO. Clean lines
   need not be invalidated or written back by this single-hart ordering point.
3. `AXIL2WritebackEngine` retains each FIFO line through ID-5 AW/W and its
   matching successful B response. A failing B retries the same retained line.
   The controller reports completion only once L2 has no dirty residents or
   victims and ID-5 is no longer busy.

`CommitController` receives `systemSerializingReady` only after both the LSQ
barrier and this controller complete. Thus neither `FENCE` nor `FENCE.I` can
retire before all pre-fence dirty lines have a successful external writeback.
`FENCE.I` then performs its existing commit redirect, L1I/BTB invalidation, and
front-end flush; the redirected fetch observes the written backing memory.

If an older trap or recovery removes the head fence, the controller returns to
idle. Any transfer already accepted by L2 or ID-5 remains owned by its existing
production owner and completes normally; the controller never discards a dirty
line or an accepted AXI transaction.

## Consequences

- The global drain is production logic and is included in the static-area
  ledger. It is distinct from, and does not require, `HostStoreFlush`.
- Younger cache operations receive temporary L1D/L2 backpressure during the
  drain. Existing transactions continue, so the fence cannot deadlock behind a
  previously accepted refill or completion.
- The D-side exclusivity invariant remains unchanged: each transfer first
  removes its source copy before the destination owns it.
- `FENCE.I` now has directed self-modifying-code evidence, but external
  coherency, long random pressure, and formal drain/credit proofs remain M3
  work.

## Verification

- `CacheFenceDrainControllerSpec` proves the L1D -> L2 -> ID-5 B completion
  order.
- `L1DLoadCacheSpec` and `ExclusiveL2TransferStoreSpec` prove each local dirty
  sweep and ingress backpressure.
- `CoreShellSpec` proves a dirty `FENCE` cannot retire while ID-5 B is withheld,
  and proves a dirty self-modifying instruction executes only after `FENCE.I`
  writes it back, invalidates L1I, and refetches it from deterministic AXI
  backing memory.
