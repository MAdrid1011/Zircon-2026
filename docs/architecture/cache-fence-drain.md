# M3 Cache-global FENCE Drain

`CacheFenceDrainController` is the production serializing boundary for
architectural `FENCE` and `FENCE.I` under ADR-0019 and Issue #47. It is not the
trace-only `HostStoreFlush` bridge.

## Interface and state

| Signal | Direction | Rule |
| --- | --- | --- |
| `request` | input | True only for a live head FENCE/FENCE.I after the exact LSQ age barrier is ready. |
| `l1dDrain` / `l1dDrained` | output/input | Requests the L1D dirty sweep and waits for no dirty resident line, MSHR, or local result hold. |
| `l2Drain` / `l2Drained` | output/input | Requests the L2 dirty sweep and waits for no dirty resident line or victim FIFO entry. |
| `writebackBusy` | input | ID-5 retained writeback ownership, including AW/W/B and B-error retry. |
| `complete` | output | Held until the live head serializing instruction retires. |

The controller moves monotonically through `Idle`, `DrainL1D`, `DrainL2`, and
`Complete`. It enters only after exact-age LSQ readiness; it does not use a
global queue-empty predicate, so a younger speculative LQ entry cannot block
the fence before the sweep begins. During each cache phase, that cache rejects
new ingress but continues serving any owner accepted before the phase. This
preserves liveness without creating a second ownership model.

## Data, exceptions, and recovery

L1D transfers a dirty line to L2 and invalidates its old exclusive copy only on
the transfer handshake. L2 transfers a dirty line to its existing victim FIFO
and invalidates its resident copy only when FIFO credit exists. ID-5 owns the
sole dirty line until an OKAY/EXOKAY B response; retryable B errors retain the
line and keep `writebackBusy` asserted.

No FENCE-specific AXI error becomes an architectural fault: a dirty writeback
is an internal durability obligation and retries until success. A recovery that
removes the head request stops future sweep actions, while accepted L2/AXI
owners drain under their normal invariants. `FENCE.I` receives `complete` before
the commit redirect triggers L1I/BTB invalidation and refetch.

## Invariants and verification

- A FENCE/FENCE.I cannot retire until every older dirty resident line has left
  L1D/L2 and ID-5 has no outstanding B response.
- No dirty D line is discarded; it moves only L1D -> L2 -> victim FIFO -> ID-5.
- New cache ingress cannot race a phase's dirty scan, while already accepted
  demand owners can still make progress.
- `CacheFenceDrainControllerSpec`, `L1DLoadCacheSpec`,
  `ExclusiveL2TransferStoreSpec`, and `CoreShellSpec` cover phase order,
  local transfer, B withholding, and self-modifying `FENCE.I` refetch.
